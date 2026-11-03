package eu.siacs.conversations.http;

import static org.junit.Assert.*;

import android.content.res.Resources;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.storage.secure.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real upload HTTP callback and request body; transport and durable boundary are controlled. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, application = HttpUploadPersistenceTest.TestApplication.class)
public class HttpUploadPersistenceTest {
    @Test public void finalUrlIsStoredBeforeScannerAndResendForAllFileShapes() throws Exception {
        for (String params : new String[] {"|4", "|4|640|480", "|4|1920|1080", "|4|0|0|900"}) {
            try (Fixture f = new Fixture(params)) {
                f.upload();
                f.await();
                assertEquals(List.of("persist", "scanner", "resend"), f.service.events);
                assertEquals("https://upload.example.test/get" + params, f.service.durableBody);
                assertEquals(1, f.service.resends);
                assertNull(f.message.getTransferable());
                // A cancellation or duplicate publication after success cannot revoke the handoff.
                f.connection.cancel();
                assertEquals(1, f.service.resends);
                assertEquals(0, f.service.failures);
            }
        }
    }

    @Test public void rejectedOrThrowingPersistenceNeverResendsOrRetiresSource() throws Exception {
        for (boolean throwsError : new boolean[] {false, true}) {
            try (Fixture f = new Fixture("|4")) {
                f.service.accept = false;
                f.service.throwsError = throwsError;
                f.upload();
                f.await();
                assertEquals(List.of("persist"), f.service.events);
                assertNull(f.service.durableBody);
                assertEquals(0, f.service.resends);
                assertEquals(Message.STATUS_SEND_FAILED, f.message.getStatus());
                assertEquals("|4", f.message.getBody());
                assertTrue(f.file.exists());
            }
        }
    }

    @Test public void cancellationBeforeResponseNeverPublishesFinalUrl() throws Exception {
        try (Fixture f = new Fixture("|4")) {
            f.manager.release = new CountDownLatch(1);
            f.upload();
            assertTrue(f.manager.requested.await(10, TimeUnit.SECONDS));
            f.connection.cancel();
            f.manager.release.countDown();
            assertTrue(f.manager.responseFinished.await(10, TimeUnit.SECONDS));
            assertNull(f.service.durableBody);
            assertEquals(0, f.service.resends);
            assertEquals(1, f.service.failures);
            assertEquals("|4", f.message.getBody());
            assertTrue(f.file.exists());
        }
    }

    @Test public void rejectedHttpResponseNeverPersistsFinalUrl() throws Exception {
        try (Fixture f = new Fixture("|4")) {
            f.manager.code = 400;
            f.upload();
            f.await();
            assertNull(f.service.durableBody);
            assertEquals(0, f.service.resends);
            assertEquals(1, f.service.failures);
            assertTrue(f.file.exists());
        }
    }

    @Test public void secureUploadPersistsBeforeLegacyRetirementAndKeepsCommittedSource() throws Exception {
        for (boolean accepted : new boolean[] {false, true}) {
            try (Fixture f = new Fixture("|4")) {
                UploadPublicationStore store = f.secure();
                f.service.accept = accepted;
                f.upload();
                f.await();
                assertEquals(accepted ? 1 : 0, f.service.resends);
                assertEquals(!accepted, f.file.exists());
                assertNotNull(new SecureMessageMediaCoordinator(store,
                        new SecureContentTransferGateway(store)).resolve(
                                f.message.getConversation().getAccount().getUuid(), f.message.getUuid()));
                if (accepted) assertEquals("https://upload.example.test/get|4", f.service.durableBody);
                else assertEquals("|4", f.message.getBody());
            }
        }
    }

    @Test public void realServiceBoundaryRequiresCommittedScsAndAvailableAccount() throws Exception {
        try (Fixture f = new Fixture("|4")) {
            UploadPublicationStore store = f.secure();
            String url = "https://upload.example.test/get";
            f.message.setBody(url + "|4");
            java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
            f.service.databaseBackend = new DatabaseBackendStub() {
                @Override public boolean persistOutgoingAttachmentUpload(Message message, String pending, String finalUrl) {
                    writes.incrementAndGet();
                    return true;
                }
            };
            assertTrue(f.service.realBoundary(f.message, "|4", url));
            assertEquals(1, writes.get());
            store.setAvailable(false);
            assertFalse(f.service.realBoundary(f.message, "|4", url));
            assertEquals(1, writes.get());
        }
    }

    @Test public void retiredMessageCannotBeRepublishedByLateSuccess() throws Exception {
        for (boolean retracted : new boolean[] {false, true}) {
            try (Fixture f = new Fixture("|4")) {
                f.manager.release = new CountDownLatch(1);
                f.upload();
                assertTrue(f.manager.requested.await(10, TimeUnit.SECONDS));
                if (retracted) f.message.markRetracted();
                else f.message.markModerated(null, null, 1L);
                f.manager.release.countDown();
                f.await();
                assertNull(f.service.durableBody);
                assertEquals(0, f.service.resends);
                assertEquals("", f.message.getBody());
                assertEquals(Message.TYPE_TEXT, f.message.getType());
            }
        }
    }

    @Test public void encryptedUrlKeepsExactFragmentAtDurableBoundary() throws Exception {
        try (Fixture f = new Fixture("|4")) {
            byte[] key = new byte[44];
            java.util.Arrays.fill(key, (byte) 0xab);
            set(f.connection, "key", key);
            f.file.setKeyAndIv(key);
            f.file.setExpectedSize(20);
            f.upload();
            f.await();
            assertEquals("aesgcm://upload.example.test/get#" + "ab".repeat(44) + "|4",
                    f.service.durableBody);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestService service = Robolectric.buildService(TestService.class).get();
        final TestManager manager = new TestManager(service);
        final Message message;
        final DownloadableFile file;
        final HttpUploadConnection connection;
        Fixture(String params) throws Exception {
            Account account = new Account(Jid.of("sender@example.test"), "");
            Conversation conversation = new Conversation("test", account,
                    Jid.of("peer@example.test"), Conversation.MODE_SINGLE, null);
            Field accounts = XmppConnectionService.class.getDeclaredField("accounts");
            accounts.setAccessible(true);
            accounts.set(service, Collections.singletonList(account));
            message = new Message(conversation, params, Message.ENCRYPTION_NONE);
            message.setType(Message.TYPE_FILE);
            message.setStatus(Message.STATUS_UNSEND);
            file = new DownloadableFile(File.createTempFile("upload-test", ".bin").getPath());
            Files.write(file.toPath(), new byte[] {1, 2, 3, 4});
            file.setExpectedSize(4);
            service.params = params;
            connection = new HttpUploadConnection(message, Method.HTTP_UPLOAD, manager);
            message.setTransferable(connection);
            Constructor<SlotRequester.Slot> constructor = SlotRequester.Slot.class.getDeclaredConstructor(
                    HttpUrl.class, HttpUrl.class, Headers.class);
            constructor.setAccessible(true);
            set(connection, "slot", constructor.newInstance(HttpUrl.get("https://upload.example.test/put"),
                    HttpUrl.get("https://upload.example.test/get"), new Headers.Builder().build()));
            set(connection, "file", file);
            set(connection, "uploadAttempt", 1);
        }
        UploadPublicationStore secure() throws Exception {
            UploadPublicationStore store = new UploadPublicationStore(
                    message.getConversation().getAccount().getUuid(), message.getUuid());
            SecureContentStoreProvider provider = new SecureContentStoreProvider(
                    RuntimeEnvironment.getApplication(), new SecureContentAccountRegistry());
            Field runtime = SecureContentStoreProvider.class.getDeclaredField("runtimeStore");
            runtime.setAccessible(true);
            runtime.set(provider, store);
            Field owned = Conversations.class.getDeclaredField("secureContentStoreProvider");
            owned.setAccessible(true);
            owned.set(service.getApplication(), provider);
            SecureContentUploadRoute route = new SecureContentUploadRoute(new SecureContentTransferGateway(store));
            set(connection, "secureContentUploadRoute", route);
            set(connection, "secureContentUploadDescriptor", route.reuseExisting(
                    message.getConversation().getAccount().getUuid(), message.getUuid()));
            return store;
        }
        void upload() throws Exception {
            java.lang.reflect.Method method = HttpUploadConnection.class.getDeclaredMethod("upload");
            method.setAccessible(true);
            method.invoke(connection);
        }
        void await() throws Exception {
            assertTrue("upload terminal callback", service.finished.await(15, TimeUnit.SECONDS));
        }
        @Override public void close() throws Exception {
            if (manager.release != null) manager.release.countDown();
            manager.client.dispatcher().executorService().shutdown();
            assertTrue(manager.client.dispatcher().executorService().awaitTermination(10, TimeUnit.SECONDS));
            Files.deleteIfExists(file.toPath());
        }
    }

    public static final class TestApplication extends Conversations {
        @Override public void onCreate() {}
    }

    public static final class TestService extends XmppConnectionService {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        boolean accept = true;
        boolean throwsError;
        String params;
        String durableBody;
        int resends;
        int failures;
        final Resources resources = new Resources(Resources.getSystem().getAssets(),
                Resources.getSystem().getDisplayMetrics(), Resources.getSystem().getConfiguration()) {
            @Override public CharSequence getText(int id) { return "upload failed"; }
        };
        final FileBackend backend = new FileBackend(this) {
            @Override public void updateFileParams(Message message, String url) {
                message.setBody(url + params);
            }
            @Override public void updateMediaScanner(File file) { events.add("scanner"); }
        };
        boolean realBoundary(Message message, String pending, String url) throws IOException {
            return super.persistOutgoingAttachmentUpload(message, pending, url);
        }
        @Override public Resources getResources() { return resources; }
        @Override public FileBackend getFileBackend() { return backend; }
        @Override public boolean persistOutgoingAttachmentUpload(Message message, String pending,
                                                                 String url) throws IOException {
            events.add("persist");
            assertEquals(params, pending);
            assertTrue(message.getBody().startsWith(url + "|"));
            if (throwsError) throw new IOException("simulated write failure");
            if (accept) durableBody = message.getBody();
            return accept;
        }
        @Override public void resendMessage(Message message, boolean delay) {
            assertEquals(message.getBody(), durableBody);
            events.add("resend");
            resends++;
            finished.countDown();
        }
        @Override public void markMessage(Message message, int status, String error) {
            message.setStatus(status);
            failures++;
            finished.countDown();
        }
        @Override public void updateConversationUi() { finished.countDown(); }
    }

    private static final class TestManager extends HttpConnectionManager {
        final CountDownLatch requested = new CountDownLatch(1);
        final CountDownLatch responseFinished = new CountDownLatch(1);
        CountDownLatch release;
        int code = 201;
        final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            request.body().writeTo(new Buffer());
            requested.countDown();
            if (release != null) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("response gate timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
            responseFinished.countDown();
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test").body(ResponseBody.create(null, new byte[0])).build();
        }).build();
        TestManager(XmppConnectionService service) { super(service); }
        @Override OkHttpClient buildHttpClient(HttpUrl url, Account account, int timeout, boolean interactive) {
            return client;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = HttpUploadConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
