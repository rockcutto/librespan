package eu.siacs.conversations.persistance;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;

public class MessageRetractionJournalApiTest {

    private static final Jid ROOM =
            Jid.of("room@conference.example");
    private static final Jid ALICE =
            Jid.of("room@conference.example/alice");

    private static Conversation room() {
        return new Conversation(
                "room",
                new Account(Jid.of("me@example.test"), ""),
                ROOM,
                Conversation.MODE_MULTI,
                null);
    }

    private static boolean valid(
            Conversation room, String request,
            String target, Jid sender, String occupant) {
        return DatabaseBackendImpl.validUnverifiedMucRetraction(
                room, request, target, sender, occupant, 100L);
    }

    @Test
    public void validRoomEventAccepted() {
        assertTrue(valid(
                room(), "request-1", "target-1",
                ALICE, "occupant-alice"));
    }

    @Test
    public void missingIdsRejected() {
        assertFalse(valid(
                room(), "", "target-1", ALICE, null));
        assertFalse(valid(
                room(), "request-1", "", ALICE, null));
        assertFalse(valid(
                room(), null, "target-1", ALICE, null));
    }

    @Test
    public void foreignRoomAndBareSenderRejected() {
        assertFalse(valid(
                room(), "request-1", "target-1",
                Jid.of("elsewhere@conference.example/alice"), null));
        assertFalse(valid(
                room(), "request-1", "target-1",
                ROOM, null));
    }

    @Test
    public void oversizedRequestIdRejected() {
        assertFalse(valid(
                room(), "x".repeat(513), "target-1",
                ALICE, null));
    }

    @Test
    public void emptyOccupantIdRejected() {
        assertFalse(valid(
                room(), "request-1", "target-1",
                ALICE, ""));
    }

    @Test
    public void missingTimestampRejected() {
        assertFalse(DatabaseBackendImpl.validUnverifiedMucRetraction(
                room(), "request-1", "target-1",
                ALICE, null, 0L));
    }
}
