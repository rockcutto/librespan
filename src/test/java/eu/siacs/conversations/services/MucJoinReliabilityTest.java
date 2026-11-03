package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.generator.PresenceGenerator;
import eu.siacs.conversations.parser.PresenceParser;
import eu.siacs.conversations.persistance.DatabaseBackendStub;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual service join/disco callbacks + PresenceParser; only transport, vault and clock are controlled. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucJoinReliabilityTest {
    @Test public void offlineErrorChangeRefreshesUiWithoutParticipantChanges() throws Exception {
        Fixture f = new Fixture();
        int before = f.service.ui;
        f.parser.parseConferencePresence(f.error(null, "not-authorized"), f.account);
        assertEquals(MucOptions.Error.PASSWORD_REQUIRED, f.options().getError());
        assertFalse(f.options().online());
        assertEquals(before + 1, f.service.ui);
        f.parser.parseConferencePresence(f.error(null, "not-authorized"), f.account);
        assertEquals(before + 1, f.service.ui);
    }

    @Test public void missingPasswordGetsPromptAndReleasesJoinState() throws Exception {
        Fixture f = new Fixture();
        f.join();
        Presence sent = f.lastPresence();
        assertFalse(sent.findChild("x", Namespace.MUC).hasChild("password"));
        int ui = f.service.ui;
        f.reject(sent, "not-authorized");
        assertEquals(MucOptions.Error.PASSWORD_REQUIRED, f.options().getError());
        assertEquals(ui + 1, f.service.ui);
        f.assertTerminal();
        assertTrue(f.service.deadlines.get(0).isCancelled());
    }

    @Test public void providedWrongPasswordIsRetryableWithoutRestartOrAutomaticRetry() throws Exception {
        Fixture f = new Fixture();
        f.service.providePasswordForMuc(f.room, "wrong-one");
        f.disco(0);
        Presence first = f.lastPresence();
        assertEquals("wrong-one", first.findChild("x", Namespace.MUC).findChildContent("password"));
        f.reject(first, "not-authorized");
        assertEquals(MucOptions.Error.PASSWORD_INCORRECT, f.options().getError());
        f.assertTerminal();
        f.service.joinMuc(f.room); // automatic join must not guess with rejected credentials
        assertEquals(1, f.service.iqs.size());
        f.service.providePasswordForMuc(f.room, "wrong-two");
        assertEquals(2, f.service.iqs.size());
        f.disco(1);
        Presence second = f.lastPresence();
        assertNotEquals(first.getId(), second.getId());
        int ui = f.service.ui;
        f.reject(second, "not-authorized");
        assertEquals(MucOptions.Error.PASSWORD_INCORRECT, f.options().getError());
        assertEquals(ui + 1, f.service.ui);
        f.reject(second, "not-authorized");
        assertEquals(ui + 1, f.service.ui); // duplicate rejection does not rebuild the UI
    }

    @Test public void membershipAndBanRemainAuthoritativeEvenWithPassword() throws Exception {
        for (String condition : List.of("registration-required", "forbidden")) {
            Fixture f = new Fixture();
            f.service.password = "provided";
            f.join();
            f.reject(f.lastPresence(), condition);
            assertEquals(condition.equals("forbidden") ? MucOptions.Error.BANNED : MucOptions.Error.MEMBERS_ONLY,
                    f.options().getError());
            f.assertTerminal();
            f.service.joinMuc(f.room);
            assertEquals(1, f.service.iqs.size());
        }
    }

    @Test public void successfulSelfPresenceEndsJoiningAndCancelsDeadline() throws Exception {
        Fixture f = new Fixture();
        f.service.password = "correct";
        f.join();
        assertTrue(f.options().getJoinAttempt().isPending());
        f.selfPresence();
        assertTrue(f.options().online());
        assertEquals(MucOptions.Error.NONE, f.options().getError());
        f.assertTerminal();
        assertTrue(f.service.deadlines.get(0).isCancelled());
        f.service.deadlines.get(0).runEvenIfCancelled();
        assertTrue(f.options().online());
    }

    @Test public void staleJoinErrorCannotRejectNewerPendingOrSuccessfulAttempt() throws Exception {
        Fixture f = new Fixture();
        f.join();
        Presence old = f.lastPresence();
        f.service.deadlines.get(0).runEvenIfCancelled();
        f.service.joinMucExplicitly(f.room);
        MucOptions newer = f.options();
        int ui = f.service.ui;
        f.reject(old, "not-authorized");
        assertSame(newer, f.options());
        assertEquals(MucOptions.Error.NONE, newer.getError());
        assertEquals(ui, f.service.ui);
        f.disco(1);
        f.selfPresence();
        f.reject(old, "forbidden");
        f.parser.parseConferencePresence(f.error(null, "not-authorized"), f.account);
        assertTrue(f.options().online());
        assertEquals(MucOptions.Error.NONE, f.options().getError());
    }

    @Test public void staleDiscoAndDeadlineCannotMutateNewAttemptOrSendAnotherJoin() throws Exception {
        Fixture f = new Fixture();
        f.service.joinMuc(f.room);
        f.service.deadlines.get(0).runEvenIfCancelled();
        f.service.joinMucExplicitly(f.room);
        MucOptions newer = f.options();
        f.disco(0);
        assertSame(newer, f.options());
        assertEquals(0, f.service.sent.size());
        f.disco(1);
        assertEquals(1, f.service.sent.size());
        f.service.deadlines.get(0).runEvenIfCancelled();
        assertEquals(MucOptions.Error.NONE, newer.getError());
        assertTrue(newer.getJoinAttempt().isPending());
        f.service.iqs.get(0).accept(new Iq(Iq.Type.ERROR));
        assertEquals(1, f.service.sent.size());
    }

    @Test public void configurationTimeoutIsTerminalInsteadOfLogOnly() throws Exception {
        Fixture f = new Fixture();
        f.service.joinMuc(f.room);
        assertEquals(Long.valueOf(eu.siacs.conversations.Config.CONNECT_DISCO_TIMEOUT), f.service.iqTimeouts.get(0));
        f.service.iqs.get(0).accept(Iq.TIMEOUT);
        assertEquals(MucOptions.Error.REMOTE_SERVER_TIMEOUT, f.options().getError());
        f.assertTerminal();
        assertEquals(0, f.service.sent.size());
        f.disco(0);
        assertEquals(0, f.service.sent.size());
    }

    @Test public void missingSelfPresenceHasFiniteExplicitlyRetryableState() throws Exception {
        Fixture f = new Fixture();
        f.join();
        f.service.deadlines.get(0).runEvenIfCancelled();
        assertEquals(MucOptions.Error.REMOTE_SERVER_TIMEOUT, f.options().getError());
        f.assertTerminal();
        assertEquals(1, f.service.iqs.size());
        f.service.joinMucExplicitly(f.room);
        f.disco(1);
        assertEquals(2, f.service.sent.size());
    }

    @Test public void concurrentJoinRequestsAreCoalescedThroughPresencePhase() throws Exception {
        Fixture f = new Fixture();
        f.service.joinMuc(f.room);
        f.service.joinMucExplicitly(f.room);
        assertEquals(1, f.service.iqs.size());
        f.disco(0);
        f.service.joinMucExplicitly(f.room);
        assertEquals(1, f.service.iqs.size());
        f.disco(0); // duplicate callback
        assertEquals(1, f.service.sent.size());
    }

    @Test public void archivedOrLeftRoomCannotBeRejoinedByLateConfiguration() throws Exception {
        for (boolean archive : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.service.joinMuc(f.room);
            if (archive) f.room.setStatus(Conversation.STATUS_ARCHIVED);
            else f.service.leaveMuc(f.room);
            int before = f.service.sent.size();
            f.disco(0);
            assertEquals(before, f.service.sent.size());
            f.assertTerminal();
        }
    }

    @Test public void normalPublicRoomReconnectKeepsSuccessfulBehavior() throws Exception {
        Fixture f = new Fixture();
        f.join();
        f.selfPresence();
        f.service.leaveMuc(f.room);
        f.service.joinMuc(f.room);
        f.disco(1);
        f.selfPresence();
        assertEquals(MucOptions.Error.NONE, f.options().getError());
        assertTrue(f.options().online());
    }

    @Test public void bareRoomErrorWithMatchingIdIsAlsoHandled() throws Exception {
        Fixture f = new Fixture();
        f.service.password = "wrong";
        f.join();
        Presence error = f.error(f.lastPresence().getId(), "not-authorized");
        error.setFrom(f.room.getJid().asBareJid());
        f.parser.parseConferencePresence(error, f.account);
        assertEquals(MucOptions.Error.PASSWORD_INCORRECT, f.options().getError());
        f.assertTerminal();
    }

    @Test public void uncorrelatedIdlessJoinErrorCannotOverrideCurrentAttempt() throws Exception {
        Fixture f = new Fixture();
        f.join();
        f.parser.parseConferencePresence(f.error(null, "not-authorized"), f.account);
        assertTrue(f.options().getJoinAttempt().isPending());
        assertEquals(MucOptions.Error.NONE, f.options().getError());
        f.service.deadlines.get(0).runEvenIfCancelled();
        assertEquals(MucOptions.Error.REMOTE_SERVER_TIMEOUT, f.options().getError());
    }

    @Test public void participantPresenceCannotClearRejectedOfflineRoomError() throws Exception {
        Fixture f = new Fixture();
        f.parser.parseConferencePresence(f.error(null, "not-authorized"), f.account);
        Presence peer = new Presence();
        peer.setFrom(Jid.of("room@conference.example/peer"));
        peer.addChild("x", Namespace.MUC_USER).addChild("item")
                .setAttribute("role", "participant").setAttribute("affiliation", "member");
        f.parser.parseConferencePresence(peer, f.account);
        assertEquals(MucOptions.Error.PASSWORD_REQUIRED, f.options().getError());
        assertFalse(f.options().online());
    }

    @Test public void failedVaultWriteCannotStartPasswordRetry() throws Exception {
        Fixture f = new Fixture();
        f.service.acceptPassword = false;
        f.service.providePasswordForMuc(f.room, "not-stored");
        assertTrue(f.service.iqs.isEmpty());
        assertTrue(f.service.sent.isEmpty());
    }

    @Test public void freeFormErrorTextDoesNotClassifyIncorrectPassword() throws Exception {
        Fixture f = new Fixture();
        f.service.password = "provided";
        f.join();
        Presence error = f.error(f.lastPresence().getId(), "undefined-condition");
        error.findChild("error").addChild("text", Namespace.STANZAS).setContent("wrong password");
        f.parser.parseConferencePresence(error, f.account);
        assertEquals(MucOptions.Error.UNKNOWN, f.options().getError());
    }

    @Test public void offlineTransitionCancelsOldJoinAndAllowsFreshReconnect() throws Exception {
        Fixture f = new Fixture();
        f.service.joinMuc(f.room);
        f.account.setStatus(Account.State.OFFLINE);
        var method = XmppConnectionService.class.getDeclaredMethod("cancelPendingMucJoins", Account.class);
        method.setAccessible(true);
        method.invoke(f.service, f.account);
        f.disco(0);
        assertEquals(0, f.service.sent.size());
        f.assertTerminal();
        f.account.setStatus(Account.State.ONLINE);
        f.service.joinMuc(f.room);
        f.disco(1);
        f.selfPresence();
        assertTrue(f.options().online());
    }

    @Test public void onlineRosterAndRenameErrorsKeepTheirExistingCallbacks() throws Exception {
        Fixture f = new Fixture();
        f.join(); f.selfPresence();
        int roster = f.service.roster;
        f.selfPresence();
        assertEquals(roster + 1, f.service.roster);
        final boolean[] rejected = {false};
        f.options().setOnRenameListener(new MucOptions.OnRenameListener() {
            @Override public void onSuccess() { fail("rename conflict must fail"); }
            @Override public void onFailure() { rejected[0] = true; }
        });
        f.parser.parseConferencePresence(f.error(null, "conflict"), f.account);
        assertTrue(rejected[0]);
        assertTrue(f.options().online());
    }

    @Test public void fallbackDiscoCannotClearALaterAuthenticationRejection() throws Exception {
        Fixture f = new Fixture();
        f.service.password = "wrong";
        f.service.joinMuc(f.room);
        Iq deniedDisco = new Iq(Iq.Type.ERROR);
        deniedDisco.addChild("error").addChild("service-unavailable", Namespace.STANZAS);
        f.service.iqs.get(0).accept(deniedDisco);
        assertEquals(1, f.service.sent.size());
        assertEquals(2, f.service.iqs.size());
        f.reject(f.lastPresence(), "not-authorized");
        f.disco(1);
        assertEquals(MucOptions.Error.PASSWORD_INCORRECT, f.options().getError());
        f.assertTerminal();
    }

    @Test public void twoThreadsCannotStartParallelRoomJoins() throws Exception {
        Fixture f = new Fixture();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable join = () -> {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                f.service.joinMucExplicitly(f.room);
            } catch (Throwable error) { failure.set(error); }
        };
        Thread first = new Thread(join);
        Thread second = new Thread(join);
        first.start(); second.start(); start.countDown();
        first.join(5000); second.join(5000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, f.service.iqs.size());
        assertEquals(1, f.service.deadlines.size());
    }

    static final class Fixture {
        final TestService service = new TestService();
        final Account account = new Account(Jid.of("guest@example.test/device"), "");
        final Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        final PresenceParser parser = new PresenceParser(service, account);
        Fixture() throws Exception {
            account.setStatus(Account.State.ONLINE);
            service.room = room;
            service.databaseBackend = new DatabaseBackendStub();
            Field generator = XmppConnectionService.class.getDeclaredField("mPresenceGenerator");
            generator.setAccessible(true);
            generator.set(service, new PresenceGenerator(service) {
                @Override public Presence selfPresence(Account account, eu.siacs.conversations.entities.Presence.Status status, boolean personal) {
                    return new Presence();
                }
            });
        }
        MucOptions options() { return room.getMucOptions(); }
        void join() { service.joinMuc(room); disco(service.iqs.size() - 1); }
        void disco(int i) {
            Iq result = new Iq(Iq.Type.RESULT);
            result.addChild("query", Namespace.DISCO_INFO);
            service.iqs.get(i).accept(result);
        }
        Presence lastPresence() { return service.sent.get(service.sent.size() - 1); }
        Presence error(String id, String condition) {
            Presence packet = new Presence();
            packet.setFrom(options().getSelf().getFullJid());
            packet.setAttribute("type", "error");
            if (id != null) packet.setId(id);
            packet.addChild("error").addChild(condition, Namespace.STANZAS);
            return packet;
        }
        void reject(Presence sent, String condition) {
            Presence packet = error(sent.getId(), condition);
            packet.setFrom(sent.getTo());
            parser.parseConferencePresence(packet, account);
        }
        void selfPresence() {
            Presence packet = new Presence();
            packet.setFrom(options().getSelf().getFullJid());
            var x = packet.addChild("x", Namespace.MUC_USER);
            x.addChild("status").setAttribute("code", "110");
            x.addChild("item").setAttribute("affiliation", "member").setAttribute("role", "participant")
                    .setAttribute("jid", account.getJid().asBareJid().toString());
            parser.parseConferencePresence(packet, account);
        }
        void assertTerminal() {
            assertFalse(options().getJoinAttempt().isPending());
            assertFalse(account.inProgressConferenceJoins.contains(room));
            assertFalse(account.pendingConferenceJoins.contains(room));
        }
    }

    static final class TestService extends XmppConnectionService {
        TestService() { attachBaseContext(RuntimeEnvironment.getApplication()); }
        Conversation room;
        String password;
        boolean acceptPassword = true;
        int ui;
        int roster;
        Runnable duringJoinSend;
        final List<Consumer<Iq>> iqs = new ArrayList<>();
        final List<Long> iqTimeouts = new ArrayList<>();
        final List<Presence> sent = new ArrayList<>();
        final List<Deadline> deadlines = new ArrayList<>();
        final List<Runnable> catchups = new ArrayList<>();
        final List<Iq> mamRequests = new ArrayList<>();
        final List<Consumer<Iq>> mamCallbacks = new ArrayList<>();
        final AvatarService avatars = new AvatarService(this) {
            @Override public void clear(MucOptions options) {}
            @Override public void clear(MucOptions.User user) {}
        };
        @Override public List<Conversation> findAll(Account account, Jid jid) { return List.of(room); }
        @Override public List<Conversation> getConversations() { return List.of(room); }
        @Override public AvatarService getAvatarService() { return avatars; }
        @Override public long getAutomaticMessageDeletionDate() { return 0L; }
        @Override public void updateConversationUi() { ui++; }
        @Override public void updateMucRosterUi() { roster++; }
        @Override public void updateConversation(Conversation conversation) {}
        @Override public void persistSelfNick(MucOptions.User self, boolean modified) {}
        @Override public void hydrateConversationSecrets(Conversation conversation) { conversation.getMucOptions().setPassword(password); }
        @Override public boolean rememberMucPassword(Conversation conversation, String value) {
            if (!acceptPassword) return false;
            password = value;
            conversation.getMucOptions().setPassword(value);
            return true;
        }
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback, Long timeout) {
            iqs.add(callback); iqTimeouts.add(timeout);
        }
        @Override public void sendPresencePacket(Account account, Presence packet) {
            sent.add(packet);
            if (duringJoinSend != null && packet.hasChild("x", Namespace.MUC)) duringJoinSend.run();
        }
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback) {
            assertFalse("MAM must not run under Conversation", Thread.holdsLock(room));
            mamRequests.add(iq);
            mamCallbacks.add(callback);
        }
        @Override void dispatchMucJoinCatchup(Runnable task) { catchups.add(task); }
        void runCatchups() {
            List<Runnable> tasks = new ArrayList<>(catchups);
            catchups.clear();
            for (Runnable task : tasks) task.run();
        }
        @Override void dispatchMucJoinTimeout(Runnable task) { task.run(); }
        @Override ScheduledFuture<?> scheduleMucJoinTimeout(Runnable task) {
            Deadline deadline = new Deadline(task);
            deadlines.add(deadline);
            return deadline;
        }
    }
    private static final class Deadline implements ScheduledFuture<Object> {
        final Runnable task;
        boolean cancelled;
        Deadline(Runnable task) { this.task = task; }
        void runEvenIfCancelled() { task.run(); }
        @Override public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(eu.siacs.conversations.Config.CONNECT_TIMEOUT, TimeUnit.SECONDS); }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
