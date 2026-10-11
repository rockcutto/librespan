package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Test;

public class MessageRetractionVerificationTest {
    private static final Jid ROOM =
            Jid.of("room@conference.example");

    private static Conversation room(boolean anonymous) {
        final Conversation room = new Conversation(
                "room",
                new Account(Jid.of("me@example.test"), ""),
                ROOM,
                Conversation.MODE_MULTI,
                null);

        final Iq result = new Iq(Iq.Type.RESULT);
        final Element query =
                result.addChild("query", Namespace.DISCO_INFO);

        if (anonymous) {
            query.addChild("feature")
                    .setAttribute("var", Namespace.OCCUPANT_ID);
        } else {
            query.addChild("feature")
                    .setAttribute("var", "muc_nonanonymous");
        }

        room.getMucOptions().updateConfiguration(
                new ServiceDiscoveryResult(result));
        return room;
    }

    private static Message original(Conversation room) {
        final Message message = new Message(
                room, "hello", Message.ENCRYPTION_NONE,
                Message.STATUS_RECEIVED);
        message.setCounterpart(
                Jid.of("room@conference.example/alice"));
        message.setRoomStanzaId("room-id-123");
        message.setOccupantId("alice-occupant");
        return message;
    }

    private static DatabaseBackend.PendingRetraction pending(
            String target, String sender, String occupantId) {
        return new DatabaseBackend.PendingRetraction(
                "request-456",
                target,
                sender,
                occupantId,
                100L);
    }

    @Test
    public void nonAnonymousSenderCanVerifyOwnMessage() {
        final Conversation room = room(false);

        assertTrue(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "room-id-123",
                        "room@conference.example/alice",
                        null),
                original(room)));
    }

    @Test
    public void differentSenderCannotVerifyMessage() {
        final Conversation room = room(false);

        assertFalse(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "room-id-123",
                        "room@conference.example/bob",
                        null),
                original(room)));
    }

    @Test
    public void semiAnonymousRoomRequiresOccupantIdentity() {
        final Conversation room = room(true);
        final Message message = original(room);

        assertTrue(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "room-id-123",
                        "room@conference.example/alice",
                        "alice-occupant"),
                message));

        assertFalse(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "room-id-123",
                        "room@conference.example/alice",
                        null),
                message));

        assertFalse(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "room-id-123",
                        "room@conference.example/alice",
                        "bob-occupant"),
                message));
    }

    @Test
    public void wrongTargetAndForeignRoomAreRejected() {
        final Conversation room = room(true);
        final Message message = original(room);

        assertFalse(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "another-room-id",
                        "room@conference.example/alice",
                        "alice-occupant"),
                message));

        assertFalse(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "room-id-123",
                        "other@conference.example/alice",
                        "alice-occupant"),
                message));
    }

    @Test
    public void malformedSenderCannotVerifyMessage() {
        final Conversation room = room(false);

        assertFalse(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending("room-id-123", "", null),
                original(room)));
    }

    @Test
    public void moderatedMessageCannotBeRetractedAgain() {
        final Conversation room = room(false);
        final Message message = original(room);
        message.markModerated("moderator", null, 100L);

        assertFalse(DatabaseBackendImpl.canVerifyMucRetraction(
                room,
                pending(
                        "room-id-123",
                        "room@conference.example/alice",
                        null),
                message));
    }
}
