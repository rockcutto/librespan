package eu.siacs.conversations.entities;

import static org.junit.Assert.*;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MessageRetractionPresentationTest {

    private Message message() {
        final Account account =
                new Account(Jid.of("me@example.test"), "");
        final Conversation room = new Conversation(
                "Room",
                account,
                Jid.of("room@conference.example"),
                Conversation.MODE_MULTI,
                null);

        return new Message(
                room, "sensitive text",
                Message.ENCRYPTION_NONE,
                Message.STATUS_RECEIVED);
    }

    @Test
    public void retractionClearsResidentPlaintext() {
        final Message message = message();

        message.markRetracted();

        assertTrue(message.isRetracted());
        assertFalse(message.isModerated());
        assertEquals("", message.getBody());
        assertEquals("", message.getBodyForDisplaying().toString());
        assertEquals("", message.getBodyForSecurePublication());
    }

    @Test
    public void staleSettersCannotRestoreText() {
        final Message message = message();

        message.markRetracted();
        message.setBody("restored secret");
        message.appendBody("another secret");

        assertEquals("", message.getBody());
        assertEquals("", message.getBodyForSecurePublication());
        assertEquals("", message.getContentValues()
                .getAsString(Message.BODY));
    }

    @Test
    public void retractionIsIdempotent() {
        final Message message = message();

        message.markRetracted();
        message.markRetracted();

        assertTrue(message.isRetracted());
        assertFalse(message.isRetractionRetired());
        assertEquals("", message.getBody());
    }

    @Test
    public void ordinaryMessagesRemainWritable() {
        final Message message = message();

        message.setBody("updated");

        assertFalse(message.isRetracted());
        assertEquals("updated", message.getBody());
    }
}
