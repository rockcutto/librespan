package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real checked adapter methods, with raw SQLite driver outcomes injected at their narrow seam.
 * This deliberately does not claim to open SQLCipher or simulate a real filesystem crash. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamCheckedDatabaseWriteTest {
    static final class Driver extends DatabaseBackendImpl {
        long insertResult = 1;
        int updateResult = 1;
        boolean throwInsert, throwUpdate;
        Driver() { super(RuntimeEnvironment.getApplication(), "synthetic-test-key"); }
        @Override long insertMamMessageRow(Message message) {
            if (throwInsert) throw new IllegalStateException("injected driver exception");
            return insertResult;
        }
        @Override int updateMamConversationRow(Conversation conversation) {
            if (throwUpdate) throw new IllegalStateException("injected driver exception");
            return updateResult;
        }
    }
    private Conversation room() {
        var account = new Account(Jid.of("test@example.test"), "");
        return new Conversation("test", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
    }
    @Test public void minusOneInsertIsNotSuccessfulWithoutAnException() {
        var db = new Driver(); var message = new Message(room(), "synthetic", 0);
        db.insertResult = -1; assertFalse(db.createMessageForMam(message));
        db.insertResult = 1; assertTrue(db.createMessageForMam(message));
    }
    @Test public void zeroOrMultipleUpdatedConversationRowsAreNotSuccessful() {
        var db = new Driver(); var conversation = room();
        db.updateResult = 0; assertFalse(db.updateConversationForMam(conversation));
        db.updateResult = 2; assertFalse(db.updateConversationForMam(conversation));
        db.updateResult = 1; assertTrue(db.updateConversationForMam(conversation));
    }
    @Test public void exceptionsAreNotConvertedToSuccessfulWrites() {
        var db = new Driver(); var conversation = room();
        db.throwInsert = true;
        assertThrows(IllegalStateException.class, () -> db.createMessageForMam(new Message(conversation, "", 0)));
        db.throwUpdate = true;
        assertThrows(IllegalStateException.class, () -> db.updateConversationForMam(conversation));
    }
    @Test public void unsupportedBackendCannotAcknowledgeVoidWritesAsDurable() {
        var db = new DatabaseBackendStub();
        assertFalse(db.createMessageForMam(new Message(room(), "", 0)));
        assertFalse(db.updateConversationForMam(room()));
    }
}
