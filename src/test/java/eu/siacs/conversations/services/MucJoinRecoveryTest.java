package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.MucJoinReliabilityTest.Fixture;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnStatusChanged;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Uses the real service status listener, the ONLINE boundary of successful SM resume.
 * Transport replay itself is outside this fixture; no test manually rejoins after resume. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucJoinRecoveryTest {
    private void status(Fixture f, Account.State state) throws Exception {
        f.account.setStatus(state);
        Field field = XmppConnectionService.class.getDeclaredField("statusListener");
        field.setAccessible(true);
        ((OnStatusChanged) field.get(f.service)).onStatusChanged(f.account);
    }

    @Test public void resumeRecoversInterruptedDiscoDespiteLateCallbacks() throws Exception {
        Fixture f = new Fixture();
        f.service.joinMuc(f.room);
        MucOptions old = f.options();
        status(f, Account.State.CONNECTING);
        f.disco(0);
        f.service.iqs.get(0).accept(Iq.TIMEOUT);
        status(f, Account.State.ONLINE);
        assertNotSame(old, f.options());
        assertEquals(2, f.service.iqs.size());
        f.disco(1);
        f.selfPresence();
        assertTrue(f.options().online());
        f.assertTerminal();
        status(f, Account.State.ONLINE);
        assertEquals(2, f.service.iqs.size());
        assertEquals(1, f.service.sent.size());
    }

    @Test public void resumedPresenceAttemptIsReplacedOnceAndOldIdsStayRejected() throws Exception {
        Fixture f = new Fixture();
        f.join();
        Presence old = f.lastPresence();
        status(f, Account.State.CONNECTING);
        status(f, Account.State.CONNECTING);
        f.selfPresence(); // ID-less late presence cannot revive the cancelled attempt.
        assertFalse(f.options().online());
        status(f, Account.State.ONLINE);
        assertEquals(2, f.service.iqs.size());
        f.disco(1);
        f.reject(old, "not-authorized");
        assertTrue(f.options().getJoinAttempt().isPending());
        Presence stale = self(f.room, old.getId());
        f.parser.parseConferencePresence(stale, f.account);
        assertFalse(f.options().online());
        f.parser.parseConferencePresence(self(f.room, f.lastPresence().getId()), f.account);
        assertTrue(f.options().online());
        assertEquals(2, f.service.sent.size());
    }

    @Test public void leaveArchiveAccountChangeAndResetInvalidateRecovery() throws Exception {
        for (int action = 0; action < 4; action++) {
            Fixture f = new Fixture();
            f.service.joinMuc(f.room);
            status(f, Account.State.CONNECTING);
            if (action == 0) f.service.leaveMuc(f.room);
            if (action == 1) f.room.setStatus(Conversation.STATUS_ARCHIVED);
            if (action == 2) f.room.setAccount(new Account(Jid.of("other@example.test"), ""));
            if (action == 3) f.room.resetMucOptions();
            status(f, Account.State.ONLINE);
            assertEquals("action=" + action, 1, f.service.iqs.size());
            assertFalse(f.options().getJoinAttempt().isPending());
        }
    }

    @Test public void confirmedAndRejectedRoomsAreNotRecoveryCandidates() throws Exception {
        for (boolean confirmed : new boolean[] {true, false}) {
            Fixture f = new Fixture();
            f.join();
            if (confirmed) f.selfPresence();
            else f.reject(f.lastPresence(), "not-authorized");
            status(f, Account.State.CONNECTING);
            status(f, Account.State.ONLINE);
            assertEquals(1, f.service.iqs.size());
            assertEquals(confirmed, f.options().online());
        }
    }

    @Test public void interruptedMultipleRoomsRecoverExactlyOnce() throws Exception {
        Fixture f = new Fixture();
        Conversation other = new Conversation("other", f.account,
                Jid.of("other@conference.example"), Conversation.MODE_MULTI, null);
        f.service.extraRooms.add(other);
        f.join(); // first room has sent presence; second still waits for disco.
        f.service.joinMuc(other);
        status(f, Account.State.CONNECTING);
        status(f, Account.State.ONLINE);
        assertEquals(4, f.service.iqs.size());
        f.disco(2);
        f.disco(3);
        f.parser.parseConferencePresence(self(f.room, f.options().getJoinAttempt().getPresenceId()), f.account);
        f.parser.parseConferencePresence(self(other, other.getMucOptions().getJoinAttempt().getPresenceId()), f.account);
        assertTrue(f.options().online());
        assertTrue(other.getMucOptions().online());
        assertFalse(other.getMucOptions().getJoinAttempt().isPending());
        status(f, Account.State.ONLINE);
        assertEquals(4, f.service.iqs.size());
        assertEquals(3, f.service.sent.size());
    }

    @Test public void freshBindJoinConsumesRecoveryWithoutDuplicateJoin() throws Exception {
        Fixture f = new Fixture();
        f.join();
        status(f, Account.State.CONNECTING);
        // BindProcessor queues room joins while the account is still CONNECTING.
        f.service.connectMultiModeConversations(f.account);
        status(f, Account.State.ONLINE);
        assertEquals(2, f.service.iqs.size());
        f.disco(1);
        f.selfPresence();
        assertTrue(f.options().online());
        status(f, Account.State.ONLINE);
        assertEquals(2, f.service.iqs.size());
    }

    @Test public void interruptedCreationIsNotReplayedAsOrdinaryJoin() throws Exception {
        Fixture f = new Fixture();
        f.service.joinMuc(f.room);
        f.options().flagNoAutoPushConfiguration();
        status(f, Account.State.CONNECTING);
        status(f, Account.State.ONLINE);
        assertEquals(1, f.service.iqs.size());
        assertFalse(f.options().getJoinAttempt().isPending());
    }

    @Test public void explicitLeaveFlagSuppressesInterruptedJoin() throws Exception {
        Fixture f = new Fixture();
        f.join();
        status(f, Account.State.CONNECTING);
        f.room.setMucExplicitlyLeft(true);
        status(f, Account.State.ONLINE);
        assertEquals(1, f.service.iqs.size());
        assertFalse(f.options().getJoinAttempt().isPending());
    }

    @Test public void replacementFailureDoesNotCreateAutomaticRetryLoop() throws Exception {
        Fixture f = new Fixture();
        f.join();
        status(f, Account.State.CONNECTING);
        status(f, Account.State.ONLINE);
        assertEquals(2, f.service.iqs.size());
        f.service.iqs.get(1).accept(Iq.TIMEOUT);
        f.service.iqs.get(2).accept(Iq.TIMEOUT);
        assertEquals(MucOptions.Error.REMOTE_SERVER_TIMEOUT, f.options().getError());
        status(f, Account.State.CONNECTING);
        status(f, Account.State.ONLINE);
        assertEquals(3, f.service.iqs.size());
    }

    @Test public void discoTimeoutRemainsTerminalAndManualRetrySucceeds() throws Exception {
        Fixture f = new Fixture();
        f.service.joinMuc(f.room);
        int before = f.service.ui;
        f.service.iqs.get(0).accept(Iq.TIMEOUT);
        f.service.iqs.get(1).accept(Iq.TIMEOUT);
        assertEquals(MucOptions.Error.REMOTE_SERVER_TIMEOUT, f.options().getError());
        assertTrue(f.service.ui > before);
        f.assertTerminal();
        f.service.joinMucExplicitly(f.room);
        f.disco(2);
        f.selfPresence();
        assertTrue(f.options().online());
    }

    private Presence self(Conversation room, String id) {
        Presence p = new Presence();
        p.setFrom(room.getMucOptions().getSelf().getFullJid());
        p.setId(id);
        var x = p.addChild("x", Namespace.MUC_USER);
        x.addChild("status").setAttribute("code", "110");
        x.addChild("item").setAttribute("affiliation", "member").setAttribute("role", "participant")
                .setAttribute("jid", room.getAccount().getJid().asBareJid().toString());
        return p;
    }
}
