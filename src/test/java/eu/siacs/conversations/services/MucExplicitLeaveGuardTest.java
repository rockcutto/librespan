package eu.siacs.conversations.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;

public class MucExplicitLeaveGuardTest {

    @Test
    public void suppressesWholeBareRoomForOneAccountUntilExplicitlyAllowed() {
        final MucExplicitLeaveGuard guard = new MucExplicitLeaveGuard();
        final Jid occupant = Jid.of("room@conference.example/alice");
        final Jid sameRoomOtherOccupant = Jid.of("room@conference.example/bob");

        guard.suppress("account-a", occupant);

        assertTrue(guard.contains("account-a", occupant));
        assertTrue(guard.contains("account-a", sameRoomOtherOccupant));
        assertTrue(guard.contains("account-a", Jid.of("room@conference.example")));
        assertFalse(guard.contains("account-b", Jid.of("room@conference.example")));

        guard.allow("account-a", Jid.of("room@conference.example"));
        assertFalse(guard.contains("account-a", occupant));
    }

    @Test
    public void invalidKeysNeverCreateSuppression() {
        final MucExplicitLeaveGuard guard = new MucExplicitLeaveGuard();

        guard.suppress(null, Jid.of("room@conference.example"));
        guard.suppress("", Jid.of("room@conference.example"));
        guard.suppress("account-a", null);

        assertFalse(guard.contains("account-a", Jid.of("room@conference.example")));
    }
}
