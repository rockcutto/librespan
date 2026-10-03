package eu.siacs.conversations.persistance;

import android.content.ContentValues;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MucModerationDeferredMarkerTest {

    @Test
    public void durableModerationScrubClearsAllPersistedPresentationPayloads() {
        final ContentValues values =
                DatabaseBackendImpl.moderationScrubValues("mod", "reason", 77L);

        assertEquals("", values.getAsString(Message.BODY));
        assertEquals(Message.TYPE_TEXT, values.getAsInteger(Message.TYPE).intValue());
        assertTrue(values.containsKey(Message.RELATIVE_FILE_PATH));
        assertTrue(values.containsKey(Message.MEDIA_GROUP_ID));
        assertTrue(values.containsKey(Message.PAYLOADS));
        assertTrue(values.containsKey(Message.REACTIONS));
        assertEquals(0, values.getAsInteger(Message.OOB).intValue());
        assertEquals(1, values.getAsInteger(Message.MODERATED).intValue());
        assertEquals(0, values.getAsInteger(Message.MODERATION_RETIRED).intValue());
        assertEquals("mod", values.getAsString(Message.MODERATED_BY));
        assertEquals("reason", values.getAsString(Message.MODERATION_REASON));
        assertEquals(77L, values.getAsLong(Message.MODERATED_AT).longValue());
    }

    @Test
    public void deferredMarkerScrubsMessageBeforeLateInsert() {
        final Message message = incomingMessage("secret");
        final DatabaseBackend.Moderation moderation =
                new DatabaseBackend.Moderation("mod@example.test", "reason", 99L);

        assertTrue(DatabaseBackendImpl.applyModerationMarker(message, moderation));

        assertTrue(message.isModerated());
        assertEquals("", message.getBody());
        assertEquals("mod@example.test", message.getModeratedBy());
        assertEquals("reason", message.getModerationReason());
        assertEquals(99L, message.getModeratedAt());
    }

    @Test
    public void duplicateMarkerApplicationIsIdempotent() {
        final Message message = incomingMessage("secret");
        final DatabaseBackend.Moderation first =
                new DatabaseBackend.Moderation("mod-a", "first", 1L);
        final DatabaseBackend.Moderation duplicate =
                new DatabaseBackend.Moderation("mod-b", "second", 2L);

        assertTrue(DatabaseBackendImpl.applyModerationMarker(message, first));
        assertFalse(DatabaseBackendImpl.applyModerationMarker(message, duplicate));

        assertEquals("mod-a", message.getModeratedBy());
        assertEquals("first", message.getModerationReason());
        assertEquals(1L, message.getModeratedAt());
        assertEquals("", message.getBody());
    }

    @Test
    public void missingMarkerLeavesLateMessageUntouched() {
        final Message message = incomingMessage("visible");

        assertFalse(DatabaseBackendImpl.applyModerationMarker(message, null));

        assertFalse(message.isModerated());
        assertEquals("visible", message.getBody());
    }

    private static Message incomingMessage(final String body) {
        final Account account = new Account(Jid.of("me@example.test"), "");
        final Conversation conversation =
                new Conversation(
                        "room",
                        account,
                        Jid.of("room@conference.example"),
                        Conversation.MODE_MULTI,
                        null);
        final Message message =
                new Message(
                        conversation,
                        body,
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setRoomStanzaId("room-id");
        return message;
    }
}
