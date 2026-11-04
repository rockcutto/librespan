package eu.siacs.conversations.http;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real connection methods; queue execution/HTTP responses are controlled without SCS writes. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class SecureHttpDownloadPreflightTest {
    @Test public void unknownSizeBlockedBeforeEnqueuePreservesManualPreflightAndMessageFacts() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        String body = f.message.getBody();
        f.message.setSecureMediaPresentationMetadata("image/jpeg", "photo.jpg", null);
        f.service.allowed = false;
        invoke(f.connection, "checkFileSize", false);
        assertEquals(Transferable.STATUS_OFFER_CHECK_FILESIZE, f.connection.getStatus());
        assertEquals(0, f.manager.automatic.pendingCount());
        assertEquals(0, f.manager.automatic.runningCount());
        assertTrue(f.manager.requests.isEmpty());
        assertFalse(f.message.isDeleted());
        assertEquals(body, f.message.getBody());
        assertEquals("image/jpeg", f.message.getMimeType());
        assertEquals("photo.jpg", f.message.getSecureMediaFileName());
        assertEquals(0, f.manager.connections().size());
        assertTrue(f.connection.start());
        assertTrue(f.manager.interactiveRestart);
        assertSame(f.message, f.manager.restartedMessage);
    }

    @Test public void knownAudioBlockedBeforeEnqueueStaysAvailableForManualDownload() throws Exception {
        Fixture f = new Fixture("music.mp3", 1024L);
        f.service.allowed = false;
        invokeSize(f.connection, 1024L, false);
        assertEquals(Transferable.STATUS_OFFER, f.connection.getStatus());
        assertEquals(Long.valueOf(1024), f.connection.getFileSize());
        assertTrue(f.manager.requests.isEmpty());
        assertFalse(f.message.isDeleted());
    }

    @Test public void policyChangeWhileQueuedProducesNoHeadOrRangeGet() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        invoke(f.connection, "checkFileSize", false);
        assertEquals(Transferable.STATUS_CHECKING, f.connection.getStatus());
        f.service.allowed = false;
        f.manager.workers.drain();
        assertTrue(f.manager.requests.isEmpty());
        assertEquals(Transferable.STATUS_OFFER_CHECK_FILESIZE, f.connection.getStatus());
        assertEquals(0, f.manager.connections().size());
        assertFalse(f.message.isDeleted());
    }

    @Test public void headFallbackUsesRangedGetAndReturnsItsTotalSize() throws Exception {
        Fixture f = new Fixture("voice.ogg", null);
        assertEquals(500L, (long) invoke(f.connection, "retrieveFileSize", false));
        assertEquals(java.util.Arrays.asList("HEAD", "GET bytes=0-0"), f.manager.requests);
    }

    @Test public void successfulHeadNeedsNoRangedGet() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        f.manager.headWorks = true;
        assertEquals(500L, (long) invoke(f.connection, "retrieveFileSize", false));
        assertEquals(java.util.Arrays.asList("HEAD"), f.manager.requests);
    }

    @Test public void dataSaverChangeAfterHeadStopsRangeFallback() throws Exception {
        Fixture f = new Fixture("voice.ogg", null);
        f.manager.disableAfterHead = true;
        assertThrows(HttpDownloadRequestGate.Deferred.class,
                () -> invoke(f.connection, "retrieveFileSize", false));
        assertEquals(java.util.Arrays.asList("HEAD"), f.manager.requests);
    }

    @Test public void interactivePreflightBypassesDataSaver() throws Exception {
        Fixture f = new Fixture("voice.ogg", null);
        f.service.allowed = false;
        assertEquals(500L, (long) invoke(f.connection, "retrieveFileSize", true));
        assertEquals(java.util.Arrays.asList("HEAD", "GET bytes=0-0"), f.manager.requests);
    }

    @Test public void liveMucPhotoAndAudioAreAdmittedWithoutTrustGate() throws Exception {
        for (String file : new String[] {"photo.jpg", "music.mp3", "voice.oga", "voice.opus"}) {
            Fixture f = new Fixture(file, 1024L);
            invokeSize(f.connection, 1024L, false);
            assertEquals(1, f.manager.automatic.runningCount());
            assertEquals(Transferable.STATUS_DOWNLOADING, f.connection.getStatus());
            assertSame(f.connection, f.message.getTransferable());
        }
    }

    @Test public void leavingMucWhileQueuedDefersWithoutNetworkOrDeletion() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        invoke(f.connection, "checkFileSize", false);
        f.conversation.setMucExplicitlyLeft(true);
        f.manager.workers.drain();
        assertTrue(f.manager.requests.isEmpty());
        assertEquals(Transferable.STATUS_OFFER_CHECK_FILESIZE, f.connection.getStatus());
        assertFalse(f.message.isDeleted());
    }

    @Test public void cleanupCancelsWaitingAndReservedOperationsWithoutLateRequests() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        f.manager.automatic.submit("other-running", () -> {}, () -> fail());
        invoke(f.connection, "checkFileSize", false);
        assertEquals(1, f.manager.automatic.pendingCount());
        f.manager.beginAccountCleanup(f.account);
        assertEquals(0, f.manager.automatic.pendingCount());
        assertEquals(0, f.manager.connections().size());
        f.manager.workers.drain();
        assertTrue(f.manager.requests.isEmpty());
        assertNull(f.message.getTransferable());
        assertFalse(f.connection.start());
    }

    @Test public void cleanupAlsoFencesReservedWorkerBeforeItStartsHttp() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        invoke(f.connection, "checkFileSize", false);
        assertEquals(1, f.manager.automatic.runningCount());
        f.manager.beginAccountCleanup(f.account);
        assertEquals(1, f.manager.automatic.runningCount());
        f.manager.workers.drain();
        assertEquals(0, f.manager.automatic.runningCount());
        assertTrue(f.manager.requests.isEmpty());
        assertEquals(0, f.manager.connections().size());
    }

    @Test public void sameMessageUuidIsDeduplicatedAcrossDifferentMessageInstances() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        Message second = new Message(f.conversation, f.message.getBody(), Message.ENCRYPTION_NONE);
        second.setUuid(f.message.getUuid());
        assertNotSame(f.message, second);
        assertTrue(HttpConnectionManager.sameDownload(f.message, second));
        Fixture otherAccount = new Fixture("photo.jpg", null);
        otherAccount.message.setUuid(f.message.getUuid());
        assertFalse(HttpConnectionManager.sameDownload(f.message, otherAccount.message));
    }

    @Test public void manualPromotionLeavesAutomaticQueueAndDoesNotDuplicateWork() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        f.manager.automatic.submit("running", () -> {}, () -> fail());
        invoke(f.connection, "checkFileSize", false);
        assertEquals(1, f.manager.automatic.pendingCount());
        assertTrue(f.connection.start());
        assertEquals(0, f.manager.automatic.pendingCount());
        assertEquals(1, f.manager.manual.tasks.size());
        assertTrue(f.connection.start());
        assertEquals(1, f.manager.manual.tasks.size());
    }

    @Test public void failedManualPreflightRemainsRetryableAndKeepsUrlAndMetadata() throws Exception {
        Fixture f = new Fixture("photo.jpg", null);
        String body = f.message.getBody();
        f.manager.failRange = true;
        invoke(f.connection, "checkFileSize", true);
        f.manager.manual.drain();
        assertEquals(Transferable.STATUS_OFFER_CHECK_FILESIZE, f.connection.getStatus());
        assertEquals(body, f.message.getBody());
        assertFalse(f.message.isDeleted());
        assertEquals(1, f.service.errors);
        assertEquals(0, f.manager.connections().size());
        assertTrue(f.connection.start());
        assertTrue(f.manager.interactiveRestart);
    }

    @Test public void actual500ConnectionsStayBoundedThenDataSaverDefersEveryRemainingJob() throws Exception {
        TestService service = new TestService();
        TestManager manager = new TestManager(service, 4);
        Account account = new Account(Jid.of("me@example.test"), "");
        Conversation muc = new Conversation("room", account, Jid.of("room@conference.example"),
                Conversation.MODE_MULTI, null);
        List<SecureHttpDownloadConnection> offers = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String body = "https://files.example.test/" + i + ".jpg";
            Message message = new Message(muc, body, Message.ENCRYPTION_NONE);
            message.setType(Message.TYPE_FILE);
            SecureHttpDownloadConnection connection = new SecureHttpDownloadConnection(message, manager);
            message.setTransferable(connection);
            field(connection, "url", HttpUrl.get(body));
            manager.connections().add(connection);
            offers.add(connection);
            bodies.add(body);
            invoke(connection, "checkFileSize", false);
            assertTrue(manager.connections().size() <= 20);
        }
        assertEquals(20, manager.connections().size());
        assertEquals(4, manager.automatic.runningCount());
        assertEquals(16, manager.automatic.pendingCount());
        assertTrue(manager.requests.isEmpty());
        service.allowed = false;
        manager.workers.drain();
        assertEquals(0, manager.connections().size());
        assertEquals(0, manager.automatic.pendingCount());
        assertTrue(manager.requests.isEmpty());
        for (int i = 0; i < offers.size(); i++) {
            assertEquals(Transferable.STATUS_OFFER_CHECK_FILESIZE, offers.get(i).getStatus());
            assertEquals(bodies.get(i), offers.get(i).getMessage().getBody());
            assertFalse(offers.get(i).getMessage().isDeleted());
        }
        assertTrue(offers.get(0).start());
        assertTrue(manager.interactiveRestart);
        service.allowed = true;
        Message live = new Message(muc, "https://files.example.test/new.jpg", Message.ENCRYPTION_NONE);
        SecureHttpDownloadConnection connection = new SecureHttpDownloadConnection(live, manager);
        live.setTransferable(connection);
        field(connection, "url", HttpUrl.get(live.getBody()));
        invoke(connection, "checkFileSize", false);
        assertEquals(1, manager.automatic.runningCount());
    }

    @Test public void existingResumeOffsetValidationStillRejectsWrongOffset() {
        assertTrue(SecureHttpDownloadConnection.isResumeContentRange("bytes 1024-4095/4096", 1024));
        assertFalse(SecureHttpDownloadConnection.isResumeContentRange("bytes 0-4095/4096", 1024));
        assertFalse(SecureHttpDownloadConnection.isResumeContentRange(null, 1024));
    }

    private static Object invoke(SecureHttpDownloadConnection connection, String method, boolean interactive)
            throws Exception {
        Method target = SecureHttpDownloadConnection.class.getDeclaredMethod(method, boolean.class);
        target.setAccessible(true);
        try { return target.invoke(connection, interactive); }
        catch (InvocationTargetException wrapped) { throw (Exception) wrapped.getCause(); }
    }
    private static void invokeSize(SecureHttpDownloadConnection connection, long size, boolean inline)
            throws Exception {
        Method target = SecureHttpDownloadConnection.class.getDeclaredMethod(
                "maybeAutoDownloadKnownSize", long.class, boolean.class);
        target.setAccessible(true);
        target.invoke(connection, size, inline);
    }
    private static void field(Object target, String name, Object value) throws Exception {
        Field field = SecureHttpDownloadConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture {
        final TestService service = new TestService();
        final TestManager manager = new TestManager(service);
        final Account account = new Account(Jid.of("me@example.test"), "");
        final Conversation conversation = new Conversation("room", account,
                Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        final Message message;
        final SecureHttpDownloadConnection connection;
        Fixture(String file, Long size) throws Exception {
            message = new Message(conversation, "https://files.example.test/" + file
                    + (size == null ? "" : "|" + size), Message.ENCRYPTION_NONE);
            message.setType(Message.TYPE_FILE);
            connection = new SecureHttpDownloadConnection(message, manager);
            message.setTransferable(connection);
            field(connection, "url", HttpUrl.get("https://files.example.test/" + file));
            if (size != null) {
                field(connection, "expectedWireSize", size);
                field(connection, "expectedPlaintextSize", size);
            }
            manager.connections().add(connection);
        }
    }
    private static final class TestService extends XmppConnectionService {
        boolean allowed = true;
        int errors;
        @Override public void showErrorToastInUi(int resId) { errors++; }
        @Override public boolean isDataSaverDisabled() { return allowed; }
        @Override public boolean hasInternetConnection() { return true; }
    }
    private static final class TestManager extends HttpConnectionManager {
        final TestService service;
        final AutomaticDownloadQueueTest.PausedExecutor workers = new AutomaticDownloadQueueTest.PausedExecutor();
        final AutomaticDownloadQueueTest.PausedExecutor manual = new AutomaticDownloadQueueTest.PausedExecutor();
        final AutomaticDownloadQueue automatic;
        final List<String> requests = new ArrayList<>();
        boolean disableAfterHead;
        boolean headWorks;
        boolean failRange;
        boolean interactiveRestart;
        Message restartedMessage;
        TestManager(TestService service) { this(service, 1); }
        TestManager(TestService service, int parallelism) {
            super(service);
            this.service = service;
            automatic = new AutomaticDownloadQueue(workers, parallelism, 16);
        }
        @Override public long getAutoAcceptFileSize() { return 1024L * 1024L; }
        @Override public void updateConversationUi(boolean force) { }
        @Override void submitAutomaticDownload(Object key, Runnable work, Runnable deferred) {
            automatic.submit(key, work, deferred);
        }
        @Override void submitManualDownload(Runnable work) { manual.execute(work); }
        @Override boolean removePendingAutomaticDownload(Object key) { return automatic.removePending(key); }
        @Override public void createNewDownloadConnection(Message message, boolean interactive) {
            restartedMessage = message; interactiveRestart = interactive;
        }
        @SuppressWarnings("unchecked") List<HttpDownloadConnection> connections() throws Exception {
            Field field = HttpConnectionManager.class.getDeclaredField("downloadConnections");
            field.setAccessible(true);
            return (List<HttpDownloadConnection>) field.get(this);
        }
        @Override OkHttpClient buildHttpClient(HttpUrl url, Account account, int timeout, boolean interactive) {
            return new OkHttpClient.Builder().addInterceptor(chain -> {
                boolean head = chain.request().method().equals("HEAD");
                requests.add(head ? "HEAD" : "GET " + chain.request().header("Range"));
                if (head && disableAfterHead) service.allowed = false;
                Response.Builder response = new Response.Builder().request(chain.request())
                        .protocol(Protocol.HTTP_1_1).message("test")
                        .code(head && !headWorks ? 405 : head ? 200 : failRange ? 500 : 206)
                        .body(ResponseBody.create(MediaType.parse("application/octet-stream"), new byte[] {0}));
                if (headWorks) response.header("Content-Length", "500");
                if (!head) response.header("Content-Range", "bytes 0-0/500");
                return response.build();
            }).build();
        }
    }
}
