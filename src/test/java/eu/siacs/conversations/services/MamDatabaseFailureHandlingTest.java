package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamDatabaseFailureHandlingTest {
    private XmppConnectionService service() {
        return new XmppConnectionService() { @Override public void updateConversationUi() {} };
    }
    private Message attachment() {
        var account = new Account(Jid.of("test@example.test"), "");
        var room = new Conversation("test", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        var message = new Message(room, "https://example.test/a.ogg", 0);
        message.setType(Message.TYPE_FILE); return message;
    }
    @Test public void nonProtectedMamInsertDoesNotUseVoidCreateMessage() {
        var service = service();
        service.databaseBackend = new DatabaseBackendStub() {
            @Override public void createMessage(Message message) { fail("void insert is not a MAM acknowledgement"); }
            @Override public boolean createMessageForMam(Message message) { return false; }
        };
        assertFalse(service.persistIncomingMamMessage(attachment()));
    }
    @Test public void zeroRowMessageUpdatePropagatesToCaller() {
        var service = service();
        service.databaseBackend = new DatabaseBackendStub() {
            @Override public boolean updateMessage(Message message, boolean body) { return false; }
            @Override public boolean updateMessage(Message message, String previousUuid) { return false; }
        };
        var message = attachment();
        assertFalse(service.updateMamMessage(message, false));
        assertFalse(service.updateMamMessage(message, message.getUuid()));
    }
}
