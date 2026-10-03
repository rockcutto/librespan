package eu.siacs.conversations.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;

public class MessageSearchModerationFilterTest {

    @Test
    public void visibleMessageIsSearchable() {
        assertTrue(MessageSearchTask.isSearchVisible(message("visible")));
    }

    @Test
    public void moderatedMessageIsNeverSearchableEvenIfObjectStillExists() {
        final Message message = message("sensitive text");
        message.markModerated("mod@example.test", "reason", 10L);

        assertFalse(MessageSearchTask.isSearchVisible(message));
    }

    @Test
    public void nullMessageFailsClosed() {
        assertFalse(MessageSearchTask.isSearchVisible(null));
    }

    private static Message message(final String body) {
        final Account account = new Account(Jid.of("me@example.test"), "");
        final Conversation conversation =
                new Conversation(
                        "room",
                        account,
                        Jid.of("room@conference.example"),
                        Conversation.MODE_MULTI,
                        null);
        return new Message(
                conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
    }
}
