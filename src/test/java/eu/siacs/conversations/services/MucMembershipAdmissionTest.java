package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Message;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real admission, F2A, context guard and generators; only transport/executor/timers are fake. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucMembershipAdmissionTest {
    private static final Jid TARGET = Jid.of("target@example.test");
    private static final String[] LISTS = {"owner", "admin", "member", "outcast"};

    @Test public void ownerIsAlreadyEligibleWithoutSet() { already("owner"); }
    @Test public void adminIsAlreadyEligibleWithoutSet() { already("admin"); }
    @Test public void memberIsAlreadyEligibleWithoutSet() { already("member"); }

    private void already(String role) {
        Fixture f = new Fixture(); f.start(); f.lists(0, role);
        f.status(MucMembershipAdmission.Status.ALREADY_ELIGIBLE);
        assertEquals(role, f.result().affiliation.name().toLowerCase(java.util.Locale.ROOT));
        assertTrue(f.result().isEligible());
        assertEquals(MucMembershipAdmission.WriteState.NOT_ATTEMPTED, f.result().writeState);
        assertEquals(0, f.sets()); f.noLocalMutation();
    }

    @Test public void outcastNeverSendsSetOrInvitation() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "outcast");
        f.status(MucMembershipAdmission.Status.BANNED); assertEquals(0, f.sets());
        assertFalse(f.result().isEligible()); f.noLocalMutation();
    }

    @Test public void noneRequiresSetResultAndAuthoritativeReadbackMember() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none");
        assertEquals(1, f.sets()); assertTrue(f.results.isEmpty()); f.noLocalMutation();
        f.reply(4, f.resultIq()); assertTrue(f.results.isEmpty()); f.noLocalMutation();
        f.lists(5, "member"); f.status(MucMembershipAdmission.Status.GRANTED_VERIFIED);
        assertEquals(MucMembershipAdmission.WriteState.ACKNOWLEDGED, f.result().writeState);
        assertEquals(MucAffiliationPreflight.Affiliation.MEMBER, f.result().affiliation);
        assertTrue(f.result().isEligible()); assertEquals(1, f.sets()); f.noLocalMutation();
    }

    @Test public void administratorCanGrantButOrdinaryMemberCannot() {
        Fixture admin = new Fixture(); admin.options().getSelf().setAffiliation("admin");
        admin.start(); admin.lists(0, "none"); assertEquals(1, admin.sets()); admin.request.cancel();
        Fixture member = new Fixture(); member.options().getSelf().setAffiliation("member");
        member.start(); member.lists(0, "none"); member.status(MucMembershipAdmission.Status.FORBIDDEN);
        assertEquals(0, member.sets());
    }

    @Test public void cachedBanIsAdditionalVetoEvenWhenListsReportNone() {
        Fixture f = new Fixture(); f.banCached(); f.start(); f.lists(0, "none");
        f.status(MucMembershipAdmission.Status.BANNED); assertEquals(0, f.sets());
        assertEquals(MucAffiliationPreflight.Reason.UNVERIFIABLE, f.result().reason);
    }

    @Test public void explicitSetErrorIsRejectedWithoutReadbackOrRetry() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.reply(4, f.error());
        f.status(MucMembershipAdmission.Status.FORBIDDEN);
        assertEquals(MucMembershipAdmission.WriteState.REJECTED, f.result().writeState);
        assertEquals(5, f.service.iqs.size()); assertEquals(1, f.sets());
        f.reply(4, f.resultIq()); assertEquals(5, f.service.iqs.size());
    }

    @Test public void nonForbiddenSetErrorRemainsExplicitRejection() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none");
        Iq iq = new Iq(Iq.Type.ERROR); iq.setFrom(f.room.getJid().asBareJid());
        var error = new im.conversations.android.xmpp.model.error.Error();
        error.setCondition(new im.conversations.android.xmpp.model.error.Condition.NotAllowed());
        iq.addExtension(error); f.reply(4, iq); f.status(MucMembershipAdmission.Status.ERROR);
        assertEquals(MucMembershipAdmission.WriteState.REJECTED, f.result().writeState);
        assertEquals(5, f.service.iqs.size()); assertEquals(1, f.sets());
    }

    @Test public void setTimeoutIsWriteUncertainAndNeverRetries() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.reply(4, Iq.TIMEOUT);
        f.status(MucMembershipAdmission.Status.WRITE_UNCERTAIN);
        assertEquals(MucAffiliationPreflight.Reason.TIMEOUT, f.result().reason);
        assertEquals(MucMembershipAdmission.WriteState.UNCONFIRMED, f.result().writeState);
        f.reply(4, f.resultIq()); f.service.deadlines.get(0).fire();
        assertEquals(1, f.results.size()); assertEquals(5, f.service.iqs.size()); assertEquals(1, f.sets());
    }

    @Test public void transportExceptionAfterSendCommitIsConservativelyUncertain() {
        Fixture f = new Fixture(); f.service.throwOnSet = true; f.start(); f.lists(0, "none");
        f.status(MucMembershipAdmission.Status.WRITE_UNCERTAIN); assertEquals(1, f.sets());
        assertEquals(MucAffiliationPreflight.Reason.SERVER_ERROR, f.result().reason);
    }

    @Test public void malformedSetReplyDoesNotClaimFailureToApplyOrAdmission() {
        for (String kind : List.of("null", "wrong-type", "invalid-type", "malformed-error")) {
            Fixture f = new Fixture(); f.start(); f.lists(0, "none");
            Iq reply = kind.equals("null") ? null : new Iq(Iq.Type.GET);
            if (kind.equals("invalid-type")) reply.setAttribute("type", "invalid");
            if (kind.equals("malformed-error")) reply = new Iq(Iq.Type.ERROR);
            f.reply(4, reply); f.status(MucMembershipAdmission.Status.WRITE_UNCERTAIN);
            assertEquals(MucAffiliationPreflight.Reason.MALFORMED_RESPONSE, f.result().reason);
            assertFalse(f.result().isEligible()); assertEquals(1, f.sets());
        }
    }

    @Test public void readbackOutcastOrNoneDoesNotConfirmAdmission() {
        for (String role : List.of("outcast", "none")) {
            Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.reply(4, f.resultIq());
            f.lists(5, role); f.status(role.equals("outcast")
                    ? MucMembershipAdmission.Status.BANNED : MucMembershipAdmission.Status.NOT_GRANTED);
            assertFalse(f.result().isEligible()); assertEquals(1, f.sets());
            assertEquals(MucMembershipAdmission.WriteState.ACKNOWLEDGED, f.result().writeState);
        }
    }

    @Test public void readbackOwnerOrAdminIsEligibleWithoutAnotherSet() {
        for (String role : List.of("owner", "admin")) {
            Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.reply(4, f.resultIq());
            f.lists(5, role); f.status(MucMembershipAdmission.Status.ELIGIBLE_VERIFIED);
            assertEquals(role, f.result().affiliation.name().toLowerCase(java.util.Locale.ROOT));
            assertTrue(f.result().isEligible()); assertEquals(1, f.sets()); f.noLocalMutation();
        }
    }

    @Test public void unknownFromForbiddenListDoesNotBecomeNone() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.error());
        f.status(MucMembershipAdmission.Status.FORBIDDEN); assertEquals(0, f.sets());
        assertEquals(MucAffiliationPreflight.Affiliation.UNKNOWN, f.result().affiliation);
    }

    @Test public void partialOrConflictingListsDoNotCauseSet() {
        Fixture partial = new Fixture(); partial.start(); Iq response = partial.list("owner", "none");
        response.findChild("query", Namespace.MUC_ADMIN).setAttribute("complete", "false");
        partial.reply(0, response); partial.status(MucMembershipAdmission.Status.UNKNOWN);
        assertEquals(MucAffiliationPreflight.Reason.MALFORMED_RESPONSE, partial.result().reason);
        assertEquals(0, partial.sets());
        Fixture conflict = new Fixture(); conflict.start();
        conflict.reply(0, conflict.list("owner", "owner")); conflict.reply(1, conflict.list("admin", "admin"));
        conflict.status(MucMembershipAdmission.Status.UNKNOWN);
        assertEquals(MucAffiliationPreflight.Reason.CONFLICT, conflict.result().reason);
        assertEquals(0, conflict.sets());
    }

    @Test public void readbackUnknownDoesNotTurnAcknowledgementIntoEligibility() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.reply(4, f.resultIq());
        f.reply(5, f.error()); f.status(MucMembershipAdmission.Status.FORBIDDEN);
        assertEquals(MucMembershipAdmission.WriteState.ACKNOWLEDGED, f.result().writeState);
        assertEquals(MucAffiliationPreflight.Affiliation.UNKNOWN, f.result().affiliation);
        assertFalse(f.result().isEligible()); assertEquals(1, f.sets());
    }

    @Test public void duplicateCallbacksProduceOneSetAndOneTerminalCallback() {
        Fixture f = new Fixture(); f.start();
        for (int i = 0; i < 4; i++) {
            Iq reply = f.list(LISTS[i], "none"); f.reply(i, reply); f.reply(i, reply);
        }
        f.reply(4, f.resultIq()); f.reply(4, f.resultIq());
        for (int i = 0; i < 4; i++) {
            Iq reply = f.list(LISTS[i], "member"); f.reply(i + 5, reply); f.reply(i + 5, reply);
        }
        f.service.deadlines.get(0).fire(); f.status(MucMembershipAdmission.Status.GRANTED_VERIFIED);
        assertEquals(9, f.service.iqs.size()); assertEquals(1, f.sets());
    }

    @Test public void cancelBeforeInitialQueuedWorkSendsNothing() {
        Fixture f = new Fixture(); f.service.queueWork = true; f.start(); f.request.cancel();
        f.service.drain(); f.status(MucMembershipAdmission.Status.CANCELLED);
        assertTrue(f.service.iqs.isEmpty()); assertEquals(0, f.sets());
    }

    @Test public void cancelBeforeLastPreflightReplyPreventsSetAndIgnoresLateCallbacks() {
        Fixture f = new Fixture(); f.start();
        for (int i = 0; i < 3; i++) f.reply(i, f.list(LISTS[i], "none"));
        f.service.queueWork = true; f.reply(3, f.list("outcast", "none"));
        f.request.cancel(); f.service.drain(); f.status(MucMembershipAdmission.Status.CANCELLED);
        assertEquals(0, f.sets()); assertEquals(4, f.service.iqs.size());
    }

    @Test public void cancelAfterSetReportsUncertaintyNotRollback() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.request.cancel();
        f.status(MucMembershipAdmission.Status.WRITE_UNCERTAIN);
        assertEquals(MucAffiliationPreflight.Reason.CANCELLED, f.result().reason);
        f.reply(4, f.resultIq()); assertEquals(5, f.service.iqs.size()); assertEquals(1, f.sets());
    }

    @Test public void cancelDuringReadbackKeepsAcknowledgedWriteVisible() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.reply(4, f.resultIq());
        f.request.cancel(); f.status(MucMembershipAdmission.Status.CANCELLED);
        assertEquals(MucMembershipAdmission.WriteState.ACKNOWLEDGED, f.result().writeState);
        assertFalse(f.result().isEligible()); f.reply(5, f.list("owner", "owner"));
        assertEquals(6, f.service.iqs.size()); assertEquals(1, f.sets());
    }

    @Test public void contextChangesBeforeSetAreStaleAndDoNotWrite() {
        for (String change : List.of("room-reset", "connection", "account", "conversation", "archive", "leave", "actor")) {
            Fixture f = new Fixture(); f.start(); f.change(change);
            f.reply(0, f.list("owner", "none"));
            // F2A checks room/account/connection, but not the actor's affiliation.
            // In this case finish the read-only preflight so admission can reject the
            // stale actor before it reaches its SET MEMBER boundary.
            if ("actor".equals(change)) {
                for (int i = 1; i < 4; i++) f.reply(i, f.list(LISTS[i], "none"));
            }
            f.status(MucMembershipAdmission.Status.STALE);
            assertEquals(0, f.sets());
        }
    }

    @Test public void contextChangesAfterSetAreUncertainAndDoNotStartReadback() {
        for (String change : List.of("room-reset", "connection", "account", "conversation", "archive", "leave", "actor")) {
            Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.change(change);
            f.reply(4, f.resultIq()); f.status(MucMembershipAdmission.Status.WRITE_UNCERTAIN);
            assertEquals(MucAffiliationPreflight.Reason.STALE_CONTEXT, f.result().reason);
            assertEquals(5, f.service.iqs.size()); assertEquals(1, f.sets());
        }
    }

    @Test public void disconnectHookInvalidatesEvenReusedConnectionAndOptions() {
        for (boolean sent : List.of(false, true)) {
            Fixture f = new Fixture(); f.start(); if (sent) f.lists(0, "none");
            f.account.setStatus(Account.State.OFFLINE);
            f.service.invalidateMucMembershipAdmissions(f.account);
            f.account.setStatus(Account.State.ONLINE); // Same identities; old result is still terminal.
            f.status(sent ? MucMembershipAdmission.Status.WRITE_UNCERTAIN : MucMembershipAdmission.Status.STALE);
            f.reply(sent ? 4 : 0, sent ? f.resultIq() : f.list("owner", "none"));
            assertEquals(sent ? 5 : 1, f.service.iqs.size());
        }
    }

    @Test public void timeoutBeforeWriteIsDistinctFromWriteUncertainty() {
        Fixture f = new Fixture(); f.start(); f.reply(0, Iq.TIMEOUT);
        f.status(MucMembershipAdmission.Status.TIMEOUT);
        assertEquals(MucMembershipAdmission.WriteState.NOT_ATTEMPTED, f.result().writeState);
        assertEquals(0, f.sets());
    }

    @Test public void wholeDeadlineTerminatesEveryStageAndLateCallbacksDoNotResume() {
        for (int stage = 0; stage < 3; stage++) {
            Fixture f = new Fixture(); f.start();
            if (stage >= 1) f.lists(0, "none");
            if (stage == 2) f.reply(4, f.resultIq());
            assertEquals(90L, f.service.deadlines.get(0).seconds);
            f.service.deadlines.get(0).fire();
            f.status(stage == 1 ? MucMembershipAdmission.Status.WRITE_UNCERTAIN : MucMembershipAdmission.Status.TIMEOUT);
            assertEquals(stage == 0 ? MucMembershipAdmission.WriteState.NOT_ATTEMPTED
                    : stage == 1 ? MucMembershipAdmission.WriteState.UNCONFIRMED
                    : MucMembershipAdmission.WriteState.ACKNOWLEDGED, f.result().writeState);
            int count = f.service.iqs.size();
            f.reply(stage == 0 ? 0 : stage == 1 ? 4 : 5,
                    stage == 1 ? f.resultIq() : f.list("owner", "owner"));
            assertEquals(count, f.service.iqs.size()); assertEquals(1, f.results.size());
        }
    }

    @Test public void repeatedManualOperationAlwaysStartsWithFreshPreflight() {
        Fixture f = new Fixture(); f.start(); f.lists(0, "none"); f.reply(4, Iq.TIMEOUT);
        f.status(MucMembershipAdmission.Status.WRITE_UNCERTAIN);
        f.results.clear(); f.start(); assertEquals(Iq.Type.GET, f.service.iqs.get(5).getType());
        f.reply(4, f.resultIq()); assertTrue(f.results.isEmpty());
        f.lists(5, "member"); f.status(MucMembershipAdmission.Status.ALREADY_ELIGIBLE);
        assertEquals(1, f.sets());
    }

    @Test public void duplicateManualRequestIsBusyWithoutReleasingFirstLease() {
        Fixture f = new Fixture(); f.start(); List<MucMembershipAdmission.Result> second = new ArrayList<>();
        MucMembershipAdmission.Request duplicate = f.service.admitMucMembership(f.account, f.room, TARGET, second::add);
        assertTrue(duplicate.isDone()); assertEquals(MucMembershipAdmission.Status.BUSY, second.get(0).status);
        f.service.admitMucMembership(f.account, f.room, TARGET, second::add);
        assertEquals(MucMembershipAdmission.Status.BUSY, second.get(1).status);
        assertEquals(1, f.service.iqs.size()); f.lists(0, "member");
        f.status(MucMembershipAdmission.Status.ALREADY_ELIGIBLE);
    }

    @Test public void invalidInputsNeverWriteAndCallbacksAreTerminal() {
        Fixture f = new Fixture();
        for (Jid target : new Jid[] {null, TARGET.withResource("device"), Jid.of("example.test")}) {
            List<MucMembershipAdmission.Result> result = new ArrayList<>();
            var request = f.service.admitMucMembership(f.account, f.room, target, result::add);
            assertTrue(request.isDone()); request.cancel(); assertEquals(1, result.size());
            assertEquals(MucMembershipAdmission.Status.INVALID, result.get(0).status);
        }
        assertTrue(f.service.iqs.isEmpty());
    }

    @Test public void standardSetCanOverwriteConcurrentBanDocumentingAcceptedToctouRisk() {
        Fixture f = new Fixture(); f.service.concurrentAffiliation = "outcast";
        f.start(); f.lists(0, "none");
        // Simulated server receives a ban from another actor after the GETs, then our
        // unconditional standard SET overwrites it. This test explicitly does NOT promise CAS.
        assertEquals("outcast", f.service.overwrittenAffiliation);
        assertEquals("member", f.service.serverAffiliation);
        f.reply(4, f.resultIq()); f.lists(5, "member");
        f.status(MucMembershipAdmission.Status.GRANTED_VERIFIED); assertEquals(1, f.sets());
    }

    private static final class Fixture {
        final Service service = new Service();
        final Account account = new Account(Jid.of("actor@example.test/device"), "");
        final Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        final List<MucMembershipAdmission.Result> results = new ArrayList<>();
        MucMembershipAdmission.Request request;
        Fixture() {
            service.account = account; service.room = room;
            account.setStatus(Account.State.ONLINE); account.setXmppConnection(new XmppConnection(account, service));
            options().getSelf().setAffiliation("owner"); options().setOnline();
        }
        MucOptions options() { return room.getMucOptions(); }
        void start() { request = service.admitMucMembership(account, room, TARGET, results::add); }
        Iq resultIq() { Iq iq = new Iq(Iq.Type.RESULT); iq.setFrom(room.getJid().asBareJid()); return iq; }
        Iq list(String role, String targetRole) {
            Iq iq = resultIq(); Element query = iq.addChild("query", Namespace.MUC_ADMIN);
            if (role.equals(targetRole)) query.addChild("item").setAttribute("jid", TARGET).setAttribute("affiliation", role);
            return iq;
        }
        Iq error() {
            Iq iq = new Iq(Iq.Type.ERROR); iq.setFrom(room.getJid().asBareJid());
            var error = new im.conversations.android.xmpp.model.error.Error();
            error.setCondition(new im.conversations.android.xmpp.model.error.Condition.Forbidden());
            iq.addExtension(error); return iq;
        }
        void lists(int offset, String targetRole) {
            for (int i = 0; i < 4; i++) {
                assertEquals(Iq.Type.GET, service.iqs.get(offset + i).getType());
                reply(offset + i, list(LISTS[i], targetRole));
            }
        }
        void reply(int index, Iq iq) { service.callbacks.get(index).accept(iq); }
        MucMembershipAdmission.Result result() { return results.get(0); }
        void status(MucMembershipAdmission.Status expected) {
            assertTrue(request.isDone()); assertEquals(1, results.size());
            assertEquals(expected, result().status); assertTrue(service.messages.isEmpty());
            sets(); // Validate every mutation stanza in every terminal assertion.
        }
        int sets() {
            int count = 0;
            for (Iq iq : service.iqs) {
                assertTrue(iq.getType() == Iq.Type.GET || iq.getType() == Iq.Type.SET);
                Element query = iq.findChild("query", Namespace.MUC_ADMIN); assertNotNull(query);
                assertNull(iq.findChild("x", Namespace.MUC_USER));
                if (iq.getType() == Iq.Type.SET) {
                    count++; assertEquals(1, query.getChildren().size());
                    Element item = query.getChildren().get(0);
                    assertEquals("item", item.getName()); assertEquals(TARGET.toString(), item.getAttribute("jid"));
                    assertEquals("member", item.getAttribute("affiliation"));
                    assertEquals(room.getJid().asBareJid(), iq.getTo());
                }
            }
            return count;
        }
        void noLocalMutation() { assertNull(options().findUserByRealJid(TARGET)); assertTrue(service.messages.isEmpty()); }
        void banCached() {
            MucOptions.User user = new MucOptions.User(options(), room.getJid().withResource("cached"));
            user.setRealJid(TARGET); user.setRole("participant"); user.setAffiliation("member");
            options().updateUser(user); user.setAffiliation("outcast");
        }
        void change(String kind) {
            switch (kind) {
                case "room-reset": room.resetMucOptions(); options().setOnline(); break;
                case "connection": account.setXmppConnection(new XmppConnection(account, service)); break;
                case "account": service.account = new Account(account.getJid(), ""); break;
                case "conversation": service.room = new Conversation("replacement", account, room.getJid(), Conversation.MODE_MULTI, null); break;
                case "archive": room.setStatus(Conversation.STATUS_ARCHIVED); break;
                case "leave": options().setOffline(); break;
                default: options().getSelf().setAffiliation("member");
            }
        }
    }

    private static final class Service extends XmppConnectionService {
        Account account; Conversation room;
        boolean queueWork; boolean throwOnSet;
        String concurrentAffiliation; String overwrittenAffiliation; String serverAffiliation = "none";
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        final List<Iq> iqs = new ArrayList<>();
        final List<Consumer<Iq>> callbacks = new ArrayList<>();
        final List<Message> messages = new ArrayList<>();
        final List<Deadline> deadlines = new ArrayList<>();
        @Override public Account findAccountByUuid(String uuid) { return account; }
        @Override public Conversation findConversationByUuid(String uuid) { return room; }
        @Override void executeMucAffiliationPreflight(Runnable work) {
            if (queueWork) queued.add(work); else work.run();
        }
        void drain() { queueWork = false; while (!queued.isEmpty()) queued.remove().run(); }
        @Override ScheduledFuture<?> scheduleMucAffiliationPreflight(Runnable work, long seconds) {
            assertTrue(seconds == 40 || seconds == 90);
            Deadline deadline = new Deadline(work, seconds); deadlines.add(deadline); return deadline;
        }
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback, Long seconds) {
            assertEquals(Long.valueOf(10), seconds); iqs.add(iq); callbacks.add(callback);
            if (iq.getType() == Iq.Type.SET) {
                if (concurrentAffiliation != null) {
                    overwrittenAffiliation = concurrentAffiliation; serverAffiliation = "member";
                }
                if (throwOnSet) throw new IllegalStateException("synthetic handoff failure");
            }
        }
        @Override public void sendMessagePacket(Account account, Message message) {
            messages.add(message); fail("Admission must never send an invitation or message");
        }
    }

    private static final class Deadline implements ScheduledFuture<Object> {
        final Runnable work; final long seconds; boolean cancelled;
        Deadline(Runnable work, long seconds) { this.work = work; this.seconds = seconds; }
        void fire() { work.run(); }
        public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return cancelled; }
        public Object get() { return null; }
        public Object get(long timeout, TimeUnit unit) { return null; }
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
    }
}
