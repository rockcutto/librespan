package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import android.content.ContentValues;
import eu.siacs.conversations.entities.Message;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MessageRetractionTransitionTest {

    @Test
    public void transitionClearsDurablePresentationFields() {
        final ContentValues values =
                DatabaseBackendImpl.retractionScrubValues();

        assertEquals(1,
                values.getAsInteger(Message.RETRACTED).intValue());
        assertEquals(0,
                values.getAsInteger(
                        Message.RETRACTION_RETIRED).intValue());

        assertEquals("", values.getAsString(Message.BODY));
        assertEquals(Message.TYPE_TEXT,
                values.getAsInteger(Message.TYPE).intValue());

        assertTrue(values.containsKey(Message.RELATIVE_FILE_PATH));
        assertNull(values.getAsString(Message.RELATIVE_FILE_PATH));

        assertTrue(values.containsKey(Message.MEDIA_GROUP_ID));
        assertNull(values.getAsString(Message.MEDIA_GROUP_ID));

        assertTrue(values.containsKey(Message.PAYLOADS));
        assertNull(values.getAsString(Message.PAYLOADS));

        assertTrue(values.containsKey(Message.REACTIONS));
        assertNull(values.getAsString(Message.REACTIONS));

        assertEquals(0,
                values.getAsInteger(Message.OOB).intValue());
    }

    @Test
    public void transitionDoesNotImpersonateModerator() {
        final ContentValues values =
                DatabaseBackendImpl.retractionScrubValues();

        assertFalse(values.containsKey(Message.MODERATED));
        assertFalse(values.containsKey(Message.MODERATION_RETIRED));
        assertFalse(values.containsKey(Message.MODERATED_BY));
        assertFalse(values.containsKey(Message.MODERATION_REASON));
    }

    @Test
    public void transitionPreservesIdentityForDeduplication() {
        final ContentValues values =
                DatabaseBackendImpl.retractionScrubValues();

        assertFalse(values.containsKey(Message.UUID));
        assertFalse(values.containsKey(Message.ROOM_STANZA_ID));
        assertFalse(values.containsKey(Message.REMOTE_MSG_ID));
        assertFalse(values.containsKey(Message.SERVER_MSG_ID));
        assertFalse(values.containsKey(Message.OCCUPANT_ID));
    }
}
