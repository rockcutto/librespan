package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.xmpp.Jid;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OutgoingAttachmentRowPublicationTest {
    @Test public void attachmentUsesConfirmedInitialInsertWithoutLateDuplicate() throws Exception {
        for (int mode : new int[] {Conversation.MODE_SINGLE, Conversation.MODE_MULTI}) {
            Fixture f = new Fixture(mode, true);
            f.service.sendMessage(f.message);
            assertEquals(1, f.inserts.get());
            assertNotEquals(Message.STATUS_SEND_FAILED, f.message.getStatus());
            if (mode == Conversation.MODE_MULTI) {
                assertEquals(f.conversation.getMucOptions().getSelf().getFullJid(),
                        f.message.getCounterpart());
            }
        }
    }

    @Test public void transportCannotStartUntilConfirmedInsertInChatsAndMucs() throws Exception {
        for (int mode : new int[] {Conversation.MODE_SINGLE, Conversation.MODE_MULTI}) {
            for (boolean accepted : new boolean[] {false, true}) {
                Fixture f = new Fixture(mode, accepted, true);
                f.service.sendMessage(f.message);
                assertEquals(1, f.inserts.get());
                assertEquals("mode=" + mode + " encryption=" + f.message.getEncryption()
                        + " online=" + f.conversation.getAccount().isOnlineAndConnected()
                        + " status=" + f.message.getStatus(),
                        accepted && mode == Conversation.MODE_SINGLE ? 1 : 0, f.uploadStarts.get());
                if (mode == Conversation.MODE_MULTI && accepted) {
                    assertEquals("MUC upload must wait for confirmed membership",
                            Message.STATUS_WAITING, f.message.getStatus());
                }
            }
        }
    }

    @Test public void rejectedInitialInsertStopsPublicationAndKeepsSourceRetryable() throws Exception {
        Fixture f = new Fixture(Conversation.MODE_SINGLE, false);
        f.service.sendMessage(f.message);
        assertEquals(1, f.inserts.get());
        assertEquals(Message.STATUS_SEND_FAILED, f.message.getStatus());
        assertEquals("|42|0|0|1000", f.message.getBody());
        assertTrue(f.message.needsUploading());
    }

    @Test public void mucReflectionPreservesCanonicalAttachmentBodyAndIdentity() throws Exception {
        for (int type : new int[] {Message.TYPE_IMAGE, Message.TYPE_FILE, Message.TYPE_PRIVATE_FILE}) {
            for (int encryption : new int[] {Message.ENCRYPTION_NONE, Message.ENCRYPTION_AXOLOTL}) {
                Fixture f = new Fixture(Conversation.MODE_MULTI, true);
                f.service.sendMessage(f.message);
                String url = "https://upload.example.test/attachment";
                String canonical = url + "|42|0|0|1000";
                f.message.setBody(canonical);
                f.message.setType(type);
                f.message.setEncryption(encryption);
                f.message.setStatus(Message.STATUS_UNSEND);
                AtomicInteger statusWrites = new AtomicInteger();
                f.service.databaseBackend = new DatabaseBackendStub() {
                    @Override public boolean updateMessage(Message candidate, boolean includeBody) {
                        assertSame(f.message, candidate);
                        assertFalse("media reflection must not overwrite durable canonical BODY", includeBody);
                        statusWrites.incrementAndGet();
                        return true;
                    }
                };
                im.conversations.android.xmpp.model.stanza.Message reflection =
                        new im.conversations.android.xmpp.model.stanza.Message();
                reflection.setBody(url);
                assertTrue(f.service.markMessage(f.conversation, f.message.getUuid(),
                        Message.STATUS_SEND_RECEIVED, "room-stanza-id", reflection.getBody()));
                assertEquals(canonical, f.message.getBody());
                assertEquals(type, f.message.getType());
                assertEquals(encryption, f.message.getEncryption());
                assertEquals("room-stanza-id", f.message.getServerMsgId());
                assertEquals(1, statusWrites.get());
                assertSame(f.message, f.conversation.findSentMessageWithUuid(f.message.getUuid()));
            }
        }
    }

    @Test public void retryConfirmsExistingRowAndCannotRecreateAMissingOne() throws Exception {
        for (boolean rowExists : new boolean[] {false, true}) {
            Fixture f = new Fixture(Conversation.MODE_SINGLE, true, true);
            f.service.sendMessage(f.message);
            assertEquals(1, f.uploadStarts.get());
            AtomicInteger confirmations = new AtomicInteger();
            f.service.databaseBackend = new DatabaseBackendStub() {
                @Override public boolean prepareOutgoingAttachmentMessage(Message message, boolean insert) {
                    assertFalse("retry must not recreate a removed row", insert);
                    confirmations.incrementAndGet();
                    return rowExists;
                }
            };
            f.service.resendMessage(f.message, false);
            assertEquals(1, confirmations.get());
            assertEquals(rowExists ? 2 : 1, f.uploadStarts.get());
            assertEquals(1, f.conversation.snapshotMessages().size());
        }
    }

    @Test public void unsupportedOpenPgpRetainsExistingFailedMessageInsertion() throws Exception {
        Fixture f = new Fixture(Conversation.MODE_SINGLE, true);
        f.message.setEncryption(Message.ENCRYPTION_PGP);
        AtomicInteger legacyInserts = new AtomicInteger();
        f.service.databaseBackend = new DatabaseBackendStub() {
            @Override public boolean prepareOutgoingAttachmentMessage(Message message, boolean insert) {
                fail("OpenPGP does not use HTTP upload publication");
                return false;
            }
            @Override public void createMessage(Message message) {
                assertEquals(Message.STATUS_SEND_FAILED, message.getStatus());
                legacyInserts.incrementAndGet();
            }
        };
        f.service.sendMessage(f.message);
        assertEquals(1, legacyInserts.get());
    }

    private static final class Fixture {
        final TestService service = new TestService();
        final AtomicInteger inserts = new AtomicInteger();
        final AtomicInteger uploadStarts = new AtomicInteger();
        final Conversation conversation;
        final Message message;
        Fixture(int mode, boolean accepted) throws Exception { this(mode, accepted, false); }
        Fixture(int mode, boolean accepted, boolean connected) throws Exception {
            Account account = new Account(Jid.of("sender@example.test"), "") {
                @Override public boolean isOnlineAndConnected() { return connected; }
                @Override public boolean httpUploadAvailable(long size) { return true; }
            };
            Field backend = XmppConnectionService.class.getDeclaredField("fileBackend");
            backend.setAccessible(true);
            backend.set(service, new FileBackend(service) {
                @Override public DownloadableFile getFile(Message candidate, boolean decrypted) {
                    return new DownloadableFile("unused-test-source");
                }
            });
            Field manager = XmppConnectionService.class.getDeclaredField("mHttpConnectionManager");
            manager.setAccessible(true);
            manager.set(service, new HttpConnectionManager(service) {
                @Override public void createNewUploadConnection(Message candidate, boolean delay) {
                    assertEquals("row must precede even immediate completion", 1, inserts.get());
                    assertTrue(accepted);
                    uploadStarts.incrementAndGet();
                }
            });
            Field accounts = XmppConnectionService.class.getDeclaredField("accounts");
            accounts.setAccessible(true);
            accounts.set(service, Collections.singletonList(account));
            conversation = new Conversation("test", account, Jid.of("peer@example.test"), mode, null);
            message = new Message(conversation, "|42|0|0|1000", Message.ENCRYPTION_NONE);
            message.setType(Message.TYPE_FILE);
            service.databaseBackend = new DatabaseBackendStub() {
                @Override public boolean prepareOutgoingAttachmentMessage(Message candidate, boolean insert) {
                    assertSame(message, candidate);
                    if (insert) inserts.incrementAndGet();
                    return accepted;
                }
                @Override public void createMessage(Message candidate) {
                    fail("attachment must not use an unconfirmed late INSERT");
                }
            };
        }
    }
    private static final class TestService extends XmppConnectionService {
        @Override public void updateConversationUi() {}
    }
}
