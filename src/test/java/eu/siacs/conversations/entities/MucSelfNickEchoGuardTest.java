package eu.siacs.conversations.entities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.Jid;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class MucSelfNickEchoGuardTest {
    private static final Jid OLD = Jid.of("test@conference.example/old");
    private static final Jid NEXT = Jid.of("test@conference.example/new");
    private static final Jid OTHER = Jid.of("test@conference.example/other");
    private static final Jid OTHER_ROOM = Jid.of("another@conference.example/new");

    @Test
    public void immediateMessageAfterConfirmedNicknameChangeMatchesExactLocalUuid() {
        final var guard = new MucSelfNickEchoGuard(() -> 1_000L);
        guard.observe303(OLD, OLD, NEXT);
        assertTrue(guard.matchesEcho(NEXT, "sent-uuid", "sent-uuid"::equals));
        assertFalse(guard.matchesEcho(NEXT, "not-sent", "sent-uuid"::equals));
    }

    @Test
    public void unverifiedNickRequestDoesNotGrantSelfIdentity() {
        final var guard = new MucSelfNickEchoGuard();
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
    }

    @Test
    public void foreign303CannotGrantSelfIdentity() {
        final var guard = new MucSelfNickEchoGuard();
        guard.observe303(OLD, OTHER, NEXT);
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
    }

    @Test
    public void invalidAndCrossRoomNewJidsDoNotGrantIdentity() {
        final var guard = new MucSelfNickEchoGuard();
        guard.observe303(OLD, OLD, OTHER_ROOM);
        assertFalse(guard.matchesEcho(OTHER_ROOM, "sent-uuid", ignored -> true));
        guard.observe303(OLD, OLD, Jid.of("test@conference.example"));
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
    }

    @Test
    public void onlyAnnouncedNewOccupantCanMatch() {
        final var guard = new MucSelfNickEchoGuard();
        guard.observe303(OLD, OLD, NEXT);
        assertFalse(guard.matchesEcho(OTHER, "sent-uuid", ignored -> true));
        assertFalse(guard.matchesEcho(OLD, "sent-uuid", ignored -> true));
    }

    @Test
    public void neverMatchesUnknownEmptyOrMissingMessageId() {
        final var guard = new MucSelfNickEchoGuard();
        guard.observe303(OLD, OLD, NEXT);
        assertFalse(guard.matchesEcho(NEXT, null, ignored -> true));
        assertFalse(guard.matchesEcho(NEXT, "", ignored -> true));
        assertFalse(guard.matchesEcho(NEXT, "changed-server-id", ignored -> false));
        assertFalse(guard.matchesEcho(NEXT, "uuid", null));
    }

    @Test
    public void confirmedSelfPresenceOrDisconnectClearsPendingAlias() {
        final var guard = new MucSelfNickEchoGuard();
        guard.observe303(OLD, OLD, NEXT);
        guard.clear();
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
    }

    @Test
    public void expiryRejectsOldNickAndDoesNotReviveIt() {
        final AtomicLong clock = new AtomicLong(10L);
        final var guard = new MucSelfNickEchoGuard(clock::get);
        guard.observe303(OLD, OLD, NEXT);
        clock.addAndGet(MucSelfNickEchoGuard.WINDOW_NANOS + 1);
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
        clock.set(10L);
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
    }

    @Test
    public void supersededNicknameIsNotAccepted() {
        final var guard = new MucSelfNickEchoGuard();
        guard.observe303(OLD, OLD, NEXT);
        guard.observe303(OLD, OLD, OTHER);
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
        assertTrue(guard.matchesEcho(OTHER, "sent-uuid", ignored -> true));
    }

    @Test
    public void completedRenameCannotBeConfusedWithUnrelatedNewNick() {
        final var guard = new MucSelfNickEchoGuard();
        guard.observe303(OLD, OLD, NEXT);
        guard.clear();
        guard.observe303(NEXT, NEXT, OTHER);
        assertFalse(guard.matchesEcho(NEXT, "sent-uuid", ignored -> true));
        assertTrue(guard.matchesEcho(OTHER, "sent-uuid", ignored -> true));
    }
}
