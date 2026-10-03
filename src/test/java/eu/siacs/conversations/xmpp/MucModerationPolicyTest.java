package eu.siacs.conversations.xmpp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Test;

public class MucModerationPolicyTest {

    private static final Jid ROOM = Jid.of("room@conference.example");

    @Test
    public void moderatorCanModerateForeignReceivedMessageWithRoomId() {
        final Fixture fixture = readyFixture();
        assertTrue(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    @Test
    public void unsupportedRoomOrInsufficientRoleCannotModerate() {
        final Fixture fixture = readyFixture();
        fixture.conversation.getMucOptions().updateConfiguration(ServiceDiscoveryResult.empty());
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));

        enableModeration(fixture.conversation.getMucOptions());
        final MucOptions.User self =
                new MucOptions.User(fixture.conversation.getMucOptions(), ROOM.withResource("me"));
        self.setRole("participant");
        fixture.conversation.getMucOptions().setSelf(self);
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    @Test
    public void missingCanonicalRoomIdOrSelfMessageCannotModerate() {
        final Fixture fixture = readyFixture();
        fixture.message.setRoomStanzaId(null);
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));

        fixture.message.setRoomStanzaId("room-id");
        fixture.message.setCounterpart(ROOM.withResource("me"));
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    @Test
    public void historicalSelfMessageCannotBeModeratedAfterNickChange() {
        final Fixture fixture = readyFixture();
        fixture.conversation.getMucOptions().getSelf().setOccupantId("self-occupant");
        fixture.message.setCounterpart(ROOM.withResource("old-self-nick"));
        fixture.message.setOccupantId("self-occupant");

        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    @Test
    public void realJidIdentityAlsoRejectsHistoricalSelfMessage() {
        final Fixture fixture = readyFixture();
        fixture.message.setCounterpart(ROOM.withResource("old-self-nick"));
        fixture.message.setTrueCounterpart(fixture.conversation.getAccount().getJid().asBareJid());

        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    @Test
    public void wrongStatusPrivateOrAlreadyModeratedCannotModerate() {
        final Fixture fixture = readyFixture();
        fixture.message.setStatus(Message.STATUS_SEND);
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));

        fixture.message.setStatus(Message.STATUS_RECEIVED);
        fixture.message.setType(Message.TYPE_PRIVATE);
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));

        fixture.message.setType(Message.TYPE_TEXT);
        fixture.message.markModerated("mod", null, 1L);
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    @Test
    public void protectedTextWithoutDurableBodyRemainsModeratable() {
        final Fixture fixture = readyFixture();
        fixture.message.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED);
        fixture.message.setVerifiedProtectedBody("protected body");
        assertTrue(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    @Test
    public void emptyNonMediaMessageIsNotModeratable() {
        final Fixture fixture = readyFixture();
        fixture.message.setBody("");
        assertFalse(MucModerationPolicy.canModerate(fixture.conversation, fixture.message));
    }

    private static Fixture readyFixture() {
        final Account account = new Account(Jid.of("me@example.test"), "");
        account.setStatus(Account.State.ONLINE);
        final Conversation conversation =
                new Conversation("room", account, ROOM, Conversation.MODE_MULTI, null);
        final MucOptions options = conversation.getMucOptions();
        final MucOptions.User self = new MucOptions.User(options, ROOM.withResource("me"));
        self.setRole("moderator");
        options.setSelf(self);
        options.setOnline();
        enableModeration(options);

        final Message message =
                new Message(conversation, "visible body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(ROOM.withResource("alice"));
        message.setRoomStanzaId("room-id");
        return new Fixture(conversation, message);
    }

    private static void enableModeration(final MucOptions options) {
        final Iq result = new Iq(Iq.Type.RESULT);
        final Element query = result.addChild("query", Namespace.DISCO_INFO);
        query.addChild("feature").setAttribute("var", Namespace.MESSAGE_MODERATE);
        options.updateConfiguration(new ServiceDiscoveryResult(result));
    }

    private static final class Fixture {
        final Conversation conversation;
        final Message message;

        Fixture(final Conversation conversation, final Message message) {
            this.conversation = conversation;
            this.message = message;
        }
    }
}
