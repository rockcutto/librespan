package eu.siacs.conversations.xmpp;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Test;

public class MessageRetractionPolicyTest {
    private static final Jid ROOM = Jid.of("room@conference.example");
    private static final Jid ALICE = Jid.of("room@conference.example/alice");
    private static final Jid BOB = Jid.of("room@conference.example/bob");

    private Conversation room() {
        final Account account = new Account(Jid.of("me@example.test"), "");
        account.setStatus(Account.State.ONLINE);
        final Conversation room = new Conversation(
                "Room", account, ROOM, Conversation.MODE_MULTI, null);
        room.getMucOptions().setOnline();
        return room;
    }

    private Message ownMessage(final Conversation room) {
        final Message message = new Message(
                room, "Own message", Message.ENCRYPTION_NONE,
                Message.STATUS_SEND_RECEIVED);
        message.setRoomStanzaId("room-id-1");
        return message;
    }

    private Message foreignMessage(final Conversation room) {
        final Message message = new Message(
                room, "Foreign message", Message.ENCRYPTION_NONE,
                Message.STATUS_RECEIVED);
        message.setCounterpart(ALICE);
        message.setRoomStanzaId("room-id-1");
        message.setOccupantId("alice-occupant");
        return message;
    }

    private void configureRoom(
            final Conversation room, final String... features) {
        final Iq result = new Iq(Iq.Type.RESULT);
        final Element query = result.addChild("query", Namespace.DISCO_INFO);
        for (final String feature : features) {
            query.addChild("feature").setAttribute("var", feature);
        }
        room.getMucOptions().updateConfiguration(
                new ServiceDiscoveryResult(result));
    }

    @Test
    public void ownConfirmedMucMessageCanBeRetracted() {
        final Conversation room = room();
        assertTrue(MessageRetractionPolicy.canRetractOwnMucMessage(
                room, ownMessage(room)));
    }

    @Test
    public void missingRoomIdAndUnsentMessageAreRejected() {
        final Conversation room = room();
        final Message message = ownMessage(room);

        message.setRoomStanzaId(null);
        assertFalse(MessageRetractionPolicy.canRetractOwnMucMessage(
                room, message));

        message.setRoomStanzaId("room-id-1");
        message.setStatus(Message.STATUS_UNSEND);
        assertFalse(MessageRetractionPolicy.canRetractOwnMucMessage(
                room, message));
    }

    @Test
    public void foreignCarbonAndPrivateMessagesAreRejected() {
        final Conversation room = room();
        final Message message = ownMessage(room);

        message.setStatus(Message.STATUS_RECEIVED);
        assertFalse(MessageRetractionPolicy.canRetractOwnMucMessage(
                room, message));

        message.setStatus(Message.STATUS_SEND_RECEIVED);
        message.setCarbon(true);
        assertFalse(MessageRetractionPolicy.canRetractOwnMucMessage(
                room, message));

        message.setCarbon(false);
        message.setType(Message.TYPE_PRIVATE);
        assertFalse(MessageRetractionPolicy.canRetractOwnMucMessage(
                room, message));
    }

    @Test
    public void semiAnonymousMucRequiresMatchingOccupantId() {
        final Conversation room = room();
        configureRoom(room, Namespace.OCCUPANT_ID);
        final Message original = foreignMessage(room);

        assertTrue(MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room, original, "room-id-1", ALICE, "alice-occupant"));

        assertFalse(MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room, original, "room-id-1", ALICE, "bob-occupant"));

        assertFalse(MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room, original, "room-id-1", ALICE, null));
    }

    @Test
    public void nonAnonymousMucRequiresSameFullJid() {
        final Conversation room = room();
        configureRoom(room, "muc_nonanonymous");
        final Message original = foreignMessage(room);

        assertTrue(MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room, original, "room-id-1", ALICE, null));

        assertFalse(MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room, original, "room-id-1", BOB, null));
    }

    @Test
    public void ForeignRoomAndWrongTargetAreRejected() {
        final Conversation room = room();
        configureRoom(room, Namespace.OCCUPANT_ID);
        final Message original = foreignMessage(room);

        assertFalse(MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room, original, "other-id", ALICE, "alice-occupant"));

        assertFalse(MessageRetractionPolicy.isAuthorizedIncomingMucRetraction(
                room, original, "room-id-1",
                Jid.of("another@conference.example/alice"),
                "alice-occupant"));
    }
}
