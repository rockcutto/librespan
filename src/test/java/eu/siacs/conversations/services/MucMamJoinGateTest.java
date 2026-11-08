package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.generator.PresenceGenerator;
import eu.siacs.conversations.services.MucJoinReliabilityTest.Fixture;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real join/disco, parser, lifecycle gate, MAM query generation and terminal callbacks. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucMamJoinGateTest {
    private Fixture fixture() throws Exception {
        Fixture f = new Fixture();
        Field axolotl = Account.class.getDeclaredField("axolotlService");
        axolotl.setAccessible(true);
        axolotl.set(f.account, new AxolotlService(f.account, f.service));
        return f;
    }

    private void disco(Fixture f, int index, boolean mam) {
        Iq response = new Iq(Iq.Type.RESULT);
        var query = response.addChild("query", Namespace.DISCO_INFO);
        if (mam) query.addChild("feature").setAttribute("var", "urn:xmpp:mam:2");
        f.service.iqs.get(index).accept(response);
    }

    private void join(Fixture f) {
        f.service.joinMuc(f.room);
        disco(f, f.service.iqs.size() - 1, true);
    }

    private Presence self(Fixture f, String code, String id) {
        Presence packet = new Presence();
        packet.setFrom(f.options().getSelf().getFullJid());
        if (id != null) packet.setId(id);
        var x = packet.addChild("x", Namespace.MUC_USER);
        x.addChild("status").setAttribute("code", code);
        x.addChild("item").setAttribute("role", "participant").setAttribute("affiliation", "member")
                .setAttribute("jid", f.account.getJid().toString());
        return packet;
    }

    @Test public void transportRecoveryStartsExistingMamOnceAfterNewSelfPresence() throws Exception {
        Fixture f = fixture(); join(f);
        f.account.setStatus(Account.State.CONNECTING);
        var cancel = XmppConnectionService.class.getDeclaredMethod("cancelPendingMucJoins", Account.class);
        cancel.setAccessible(true); cancel.invoke(f.service, f.account);
        f.account.setStatus(Account.State.ONLINE);
        var recover = XmppConnectionService.class.getDeclaredMethod("recoverInterruptedMucJoins", Account.class);
        recover.setAccessible(true); recover.invoke(f.service, f.account);
        disco(f, 1, true);
        f.service.runCatchups(); assertTrue(f.service.mamRequests.isEmpty());
        f.selfPresence(); f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
        f.selfPresence(); f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
        f.service.mamCallbacks.get(0).accept(Iq.TIMEOUT);
        assertTrue(f.room.hasMessagesLeftOnServer());
        assertFalse(f.service.getMessageArchiveService().queryInProgress(f.room));
    }

    @Test public void joinPresenceSuppressesLegacyHistoryButDoesNotRequestMam() throws Exception {
        Fixture f = fixture(); join(f);
        var history = f.lastPresence().findChild("x", Namespace.MUC).findChild("history");
        assertEquals("0", history.getAttribute("maxchars"));
        assertNull(history.getAttribute("since"));
        f.service.runCatchups();
        assertTrue(f.service.mamRequests.isEmpty());
        assertTrue(f.options().getJoinAttempt().isPending());
    }

    @Test public void everyTerminalRejectionPreventsAutomaticMam() throws Exception {
        for (String condition : List.of("not-authorized", "registration-required", "forbidden", "conflict", "undefined-condition")) {
            for (boolean password : new boolean[] {false, true}) {
                Fixture f = fixture();
                if (password) f.service.password = "submitted";
                join(f);
                f.reject(f.lastPresence(), condition);
                f.service.runCatchups();
                assertFalse(f.options().online());
                assertFalse(f.options().getJoinAttempt().isPending());
                assertTrue(condition, f.service.mamRequests.isEmpty());
                f.selfPresence(); // late idless success cannot revive a terminal attempt
                f.service.runCatchups();
                assertTrue(f.service.mamRequests.isEmpty());
            }
        }
    }

    @Test public void confirmed110StartsActualRoomMamOutsideConversationMonitor() throws Exception {
        Fixture f = fixture(); join(f);
        f.selfPresence();
        assertTrue(f.options().online());
        assertTrue(f.service.mamRequests.isEmpty()); // deferred beyond presence/join callbacks
        f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
        Iq iq = f.service.mamRequests.get(0);
        assertEquals(f.room.getJid().asBareJid(), iq.getTo());
        assertNotNull(iq.findChild("query", "urn:xmpp:mam:2"));
        assertTrue(f.service.getMessageArchiveService().queryInProgress(f.room));
    }

    @Test public void roomCreation201ForOwnJidAlsoConfirmsJoin() throws Exception {
        Fixture f = fixture(); join(f);
        f.options().flagNoAutoPushConfiguration();
        f.parser.parseConferencePresence(self(f, "201", null), f.account);
        f.service.runCatchups();
        assertTrue(f.options().online());
        assertEquals(1, f.service.mamRequests.size());
    }

    @Test public void peerPresenceAndPeer201DoNotConfirmOurJoin() throws Exception {
        Fixture f = fixture(); join(f);
        f.options().flagNoAutoPushConfiguration();
        Presence peer = self(f, "201", null);
        peer.setFrom(Jid.of("room@conference.example/other"));
        peer.findChild("x", Namespace.MUC_USER).findChild("item").setAttribute("jid", "other@example.test/device");
        f.parser.parseConferencePresence(peer, f.account);
        f.service.runCatchups();
        assertFalse(f.options().online());
        assertTrue(f.service.mamRequests.isEmpty());
    }

    @Test public void duplicateAndNicknameSelfPresenceNeverRestartEvenCompletedMam() throws Exception {
        Fixture f = fixture(); join(f);
        f.selfPresence(); f.selfPresence();
        // Exercise duplicate hook dispatch as well as duplicate stanza handling.
        f.service.onMucJoinConfirmed(f.options());
        f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
        f.service.mamCallbacks.get(0).accept(Iq.TIMEOUT);
        Presence renamed = self(f, "110", null);
        renamed.setFrom(Jid.of("room@conference.example/new-nick"));
        f.parser.parseConferencePresence(renamed, f.account);
        f.service.onMucJoinConfirmed(f.options());
        f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
    }

    @Test public void rejectedPasswordThenSuccessfulRetryStartsExactlyOnce() throws Exception {
        Fixture f = fixture(); f.service.password = "old"; join(f);
        Presence old = f.lastPresence(); f.reject(old, "not-authorized");
        f.service.providePasswordForMuc(f.room, "new"); disco(f, 1, true);
        f.parser.parseConferencePresence(self(f, "110", old.getId()), f.account);
        f.reject(old, "forbidden");
        f.service.runCatchups();
        assertTrue(f.service.mamRequests.isEmpty());
        f.selfPresence(); f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
        assertEquals(MucOptions.Error.NONE, f.options().getError());
    }

    @Test public void leaveBeforeConfirmationCannotLoadArchive() throws Exception {
        Fixture f = fixture(); join(f);
        Presence late = self(f, "110", null);
        f.service.leaveMuc(f.room);
        f.parser.parseConferencePresence(late, f.account);
        f.service.runCatchups();
        assertTrue(f.service.mamRequests.isEmpty());
    }

    @Test public void queuedConfirmationIsInvalidatedByLeaveArchiveResetOrOffline() throws Exception {
        for (String invalidation : List.of("leave", "archive", "reset", "offline", "error")) {
            Fixture f = fixture(); join(f); f.selfPresence();
            assertEquals(1, f.service.catchups.size());
            switch (invalidation) {
                case "leave": f.service.leaveMuc(f.room); break;
                case "archive": f.room.setStatus(Conversation.STATUS_ARCHIVED); break;
                case "reset": f.room.resetMucOptions(); break;
                case "offline": f.account.setStatus(Account.State.OFFLINE); break;
                default: f.options().setError(MucOptions.Error.BANNED);
            }
            f.service.runCatchups();
            assertTrue(invalidation, f.service.mamRequests.isEmpty());
        }
    }

    @Test public void newRejoinOwnsCatchupAndOldQueuedHookCannotStartIt() throws Exception {
        Fixture f = fixture(); join(f); f.selfPresence();
        MucOptions old = f.options();
        f.service.leaveMuc(f.room);
        f.service.joinMuc(f.room); disco(f, 1, true);
        f.service.runCatchups();
        assertTrue(f.service.mamRequests.isEmpty());
        f.selfPresence(); f.service.onMucJoinConfirmed(old);
        f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
    }

    @Test public void confirmedRealReconnectGetsNewCatchupButDuplicateDoesNot() throws Exception {
        Fixture f = fixture(); join(f); f.selfPresence(); f.service.runCatchups();
        f.service.mamCallbacks.get(0).accept(Iq.TIMEOUT);
        f.options().setOffline(); // loss of room membership; full bind starts a fresh join
        f.service.connectMultiModeConversations(f.account); disco(f, 1, true);
        f.service.runCatchups(); assertEquals(1, f.service.mamRequests.size());
        f.selfPresence(); f.service.runCatchups(); assertEquals(2, f.service.mamRequests.size());
        f.selfPresence(); f.service.runCatchups(); assertEquals(2, f.service.mamRequests.size());
    }

    @Test public void unsupportedMamKeepsExactSinceFallbackAndNoMam() throws Exception {
        Fixture f = fixture();
        String expected = PresenceGenerator.getTimestamp(f.room.getLastMessageTransmitted().getTimestamp());
        f.service.joinMuc(f.room); disco(f, 0, false);
        var history = f.lastPresence().findChild("x", Namespace.MUC).findChild("history");
        assertEquals(expected, history.getAttribute("since"));
        assertNull(history.getAttribute("maxchars"));
        f.selfPresence(); f.service.runCatchups();
        assertTrue(f.options().online());
        assertTrue(f.service.mamRequests.isEmpty());
    }

    @Test public void terminalMamErrorAndTimeoutDoNotAutomaticallyRetry() throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            Fixture f = fixture(); join(f); f.selfPresence(); f.service.runCatchups();
            Iq failure = new Iq(Iq.Type.ERROR);
            failure.addChild("error").addChild("forbidden", Namespace.STANZAS);
            f.service.mamCallbacks.get(0).accept(timeout ? Iq.TIMEOUT : failure);
            assertFalse(f.service.getMessageArchiveService().queryInProgress(f.room));
            assertTrue(f.room.hasMessagesLeftOnServer());
            f.selfPresence(); f.service.onMucJoinConfirmed(f.options()); f.service.runCatchups();
            assertEquals(1, f.service.mamRequests.size());
        }
    }

    @Test public void manualHistoryStillWorksOfflineFromRoomAfterRejection() throws Exception {
        Fixture f = fixture(); join(f); f.reject(f.lastPresence(), "not-authorized");
        var query = f.service.getMessageArchiveService().query(f.room, new MamReference(0), System.currentTimeMillis(), false);
        assertNotNull(query);
        assertFalse(query.isCatchup());
        assertEquals(1, f.service.mamRequests.size());
        f.service.runCatchups(); assertEquals(1, f.service.mamRequests.size());
    }

    @Test public void activeInitialOrPendingHistoryIsNotDuplicatedByJoin() throws Exception {
        for (boolean pending : new boolean[] {false, true}) {
            Fixture f = fixture(); join(f);
            if (pending) f.account.setStatus(Account.State.OFFLINE);
            var query = f.service.getMessageArchiveService().query(f.room, new MamReference(0), System.currentTimeMillis(), false);
            assertNotNull(query); assertFalse(query.isCatchup());
            f.account.setStatus(Account.State.ONLINE);
            f.selfPresence(); f.service.runCatchups();
            assertEquals(pending ? 0 : 1, f.service.mamRequests.size());
            if (pending) f.service.getMessageArchiveService().executePendingQueries(f.account);
            assertEquals(1, f.service.mamRequests.size());
            f.service.mamCallbacks.get(0).accept(Iq.TIMEOUT);
            f.service.onMucJoinConfirmed(f.options()); f.service.runCatchups();
            assertEquals(2, f.service.mamRequests.size()); // new join catchup survives the older query timeout
        }
    }

    @Test public void reentrantSelfPresenceDuringSendDefersMamUntilJoinCallbackReturns() throws Exception {
        Fixture f = fixture();
        f.service.duringJoinSend = () -> {
            assertTrue(Thread.holdsLock(f.room));
            f.selfPresence();
            assertTrue(f.options().online());
            assertTrue(f.service.mamRequests.isEmpty());
        };
        join(f);
        f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
    }
    @Test public void timeoutBeforeSelfPresenceCannotAuthorizeHistory() throws Exception {
        Fixture f = fixture(); join(f);
        f.service.timeoutMucJoin(f.room, f.options());
        f.selfPresence(); f.service.onMucJoinConfirmed(f.options()); f.service.runCatchups();
        assertEquals(MucOptions.Error.REMOTE_SERVER_TIMEOUT, f.options().getError());
        assertTrue(f.service.mamRequests.isEmpty());
    }

    @Test public void unsolicitedSelfPresenceBeforeJoinWasSentCannotFinishPendingJoin() throws Exception {
        Fixture f = fixture();
        f.service.joinMuc(f.room); // Disco is outstanding; no join presence has been sent.
        var options = f.options();
        f.selfPresence(); f.service.runCatchups();
        assertSame(options, f.options());
        assertFalse(options.online());
        assertTrue(options.getJoinAttempt().isPending());
        assertFalse(options.getJoinAttempt().hasConfirmedMembership());
        assertTrue(f.service.sent.isEmpty());
        assertTrue(f.service.mamRequests.isEmpty());
        var gate = XmppConnectionService.class.getDeclaredMethod("isJoinInProgress", Conversation.class);
        gate.setAccessible(true);
        assertEquals(true, gate.invoke(f.service, f.room));

        disco(f, 0, true);
        assertEquals(1, f.service.sent.size());
        assertTrue(options.getJoinAttempt().isPending());
        assertFalse(options.online());
        f.selfPresence(); // ID-less self-presence remains compatible AFTER sending the join.
        f.service.runCatchups();
        assertTrue(options.online());
        assertTrue(options.getJoinAttempt().hasConfirmedMembership());
        assertFalse(options.getJoinAttempt().isPending());
        assertEquals(false, gate.invoke(f.service, f.room));
        assertEquals(1, f.service.mamRequests.size());
        f.selfPresence(); f.service.runCatchups();
        assertEquals(1, f.service.sent.size());
        assertEquals(1, f.service.mamRequests.size());
    }

    @Test public void staleTechnicalUnavailableCannotTerminateDiscoButRealRemovalStillDoes() throws Exception {
        for (String code : List.of("332", "333")) {
            Fixture f = fixture();
            f.service.joinMuc(f.room);
            Presence old = self(f, "110", null);
            old.setAttribute("type", "unavailable");
            old.findChild("x", Namespace.MUC_USER).addChild("status").setAttribute("code", code);
            f.parser.parseConferencePresence(old, f.account);
            assertTrue(f.options().getJoinAttempt().isPending());
            assertEquals(MucOptions.Error.NONE, f.options().getError());
            disco(f, 0, true); f.selfPresence(); f.service.runCatchups();
            assertTrue(f.options().online()); assertEquals(1, f.service.mamRequests.size());
            f.parser.parseConferencePresence(old, f.account);
            assertFalse(f.options().online());
            assertEquals(code.equals("332") ? MucOptions.Error.SHUTDOWN : MucOptions.Error.TECHNICAL_PROBLEMS,
                    f.options().getError());
        }
    }

    @Test public void correlatedSelfPresenceBeforeSendCannotFinishPendingJoinEither() throws Exception {
        Fixture f = fixture();
        f.service.joinMuc(f.room);
        var options = f.options();
        f.parser.parseConferencePresence(self(f, "110", options.getJoinAttempt().getPresenceId()), f.account);
        assertFalse(options.online());
        assertTrue(options.getJoinAttempt().isPending());
        disco(f, 0, true);
        f.parser.parseConferencePresence(self(f, "110", options.getJoinAttempt().getPresenceId()), f.account);
        f.service.runCatchups();
        assertTrue(options.online());
        assertEquals(1, f.service.mamRequests.size());
    }

    @Test public void supersededIdAndIdlessPresenceDuringRetryDiscoCannotCancelRetry() throws Exception {
        Fixture f = fixture(); join(f);
        String oldId = f.options().getJoinAttempt().getPresenceId();
        f.service.timeoutMucJoin(f.room, f.options());
        f.service.joinMucExplicitly(f.room);
        var retry = f.options();
        f.parser.parseConferencePresence(self(f, "110", oldId), f.account);
        f.selfPresence();
        assertFalse(retry.online());
        assertTrue(retry.getJoinAttempt().isPending());
        disco(f, 1, true);
        assertEquals(2, f.service.sent.size());
        f.parser.parseConferencePresence(self(f, "110", oldId), f.account);
        assertFalse(retry.online());
        f.selfPresence(); f.service.runCatchups();
        assertTrue(retry.online());
        assertEquals(1, f.service.mamRequests.size());
    }

    @Test public void confirmedCatchupKeepsExistingStanzaIdAnchor() throws Exception {
        Fixture f = fixture();
        Message tail = new Message(f.room, "synthetic tail", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        tail.setTime(System.currentTimeMillis() - 1000);
        tail.setServerMsgId("room-tail-anchor");
        f.room.add(tail);
        join(f); f.selfPresence(); f.service.runCatchups();
        assertEquals(1, f.service.mamRequests.size());
        var query = f.service.mamRequests.get(0).findChild("query", "urn:xmpp:mam:2");
        assertEquals("room-tail-anchor", query.findChild("set", "http://jabber.org/protocol/rsm").findChildContent("after"));
    }

}
