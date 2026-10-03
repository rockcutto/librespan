package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucModerationIqResponseTest {

    @Test
    public void iqResultAcknowledgesRequestWithoutMutatingTimeline() {
        assertResponseDoesNotMutateTimeline(new Iq(Iq.Type.RESULT), true);
    }

    @Test
    public void iqErrorRejectsRequestWithoutMutatingTimeline() {
        assertResponseDoesNotMutateTimeline(new Iq(Iq.Type.ERROR), false);
    }

    private static void assertResponseDoesNotMutateTimeline(
            final Iq response, final boolean expectedAccepted) {
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
                        "visible body",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setCounterpart(Jid.of("room@conference.example/alice"));
        message.setRoomStanzaId("room-id-7");
        conversation.add(message);

        final long revisionBefore = conversation.getTimelineRevision();
        final String bodyBefore = message.getBody();
        final AtomicReference<Boolean> callbackResult = new AtomicReference<>();

        final TestService service = new TestService(response);
        service.moderateMessage(conversation, message, callbackResult::set);

        assertEquals(Boolean.valueOf(expectedAccepted), callbackResult.get());
        assertFalse(message.isModerated());
        assertEquals(bodyBefore, message.getBody());
        assertEquals(revisionBefore, conversation.getTimelineRevision());
    }

    private static final class TestService extends XmppConnectionService {
        private final Iq response;

        private TestService(final Iq response) {
            this.response = response;
        }

        @Override
        public boolean canModerateMessage(
                final Conversation conversation, final Message message) {
            return true;
        }

        @Override
        public void sendIqPacket(
                final Account account, final Iq packet, final Consumer<Iq> callback) {
            callback.accept(response);
        }
    }
}
