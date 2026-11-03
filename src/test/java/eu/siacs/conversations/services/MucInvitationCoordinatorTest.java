package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import android.content.Intent;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.ui.ChooseContactActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Message;
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

/** Real coordinator, F2A, service context checks and packet generators. No fake preflight result. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucInvitationCoordinatorTest {
    static final Jid TARGET = Jid.of("target@example.test");
    static final String[] ROLES = {"owner", "admin", "member", "outcast"};

    @Test public void knownOwnerAdminMemberUseDirectWithoutAffiliationMutation() {
        for (String role : List.of("owner", "admin", "member")) {
            Fixture f = new Fixture(); f.start(); f.reply(0, f.disco(true)); f.lists(1, role);
            f.status(MucInvitationCoordinator.Status.HANDED_OFF);
            assertEquals(1, f.service.messages.size()); assertEquals(5, f.service.iqs.size());
            Message packet = f.service.messages.get(0);
            assertEquals(TARGET, packet.getTo()); assertNotNull(packet.findChild("x", "jabber:x:conference"));
            assertNull(packet.findChild("x", Namespace.MUC_USER)); assertNull(f.options().findUserByRealJid(TARGET));
        }
    }
    @Test public void noneNeedsExplicitMembershipAndDoesNotRunMediatedHook() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.disco(true)); f.lists(1, "none");
        f.status(MucInvitationCoordinator.Status.NEEDS_MEMBERSHIP); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void serverOutcastIsBlockedEvenWhenMissingFromRoster() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.disco(true)); f.lists(1, "outcast");
        f.status(MucInvitationCoordinator.Status.BANNED); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void unknownFromForbiddenListCannotBeInvited() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.disco(true)); f.reply(1, f.error());
        f.status(MucInvitationCoordinator.Status.FORBIDDEN); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void preflightConflictIsUnverifiableNotAdmission() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.disco(true));
        f.reply(1, f.list("owner", "owner")); f.reply(2, f.list("admin", "admin"));
        f.status(MucInvitationCoordinator.Status.UNVERIFIABLE); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void openRoomUsesDirectWithoutPrivilegedListAccess() {
        Fixture f = new Fixture(); f.options().getSelf().setAffiliation("none"); f.start(); f.reply(0, f.disco(false));
        f.status(MucInvitationCoordinator.Status.HANDED_OFF); assertEquals(1, f.service.iqs.size());
        assertEquals(1, f.service.messages.size());
    }
    @Test public void knownOutcastVetoAlsoAppliesToOpenRoom() {
        Fixture f = new Fixture(); f.ban(TARGET); f.start(); f.status(MucInvitationCoordinator.Status.BANNED);
        assertTrue(f.service.iqs.isEmpty()); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void ambiguousRoomPolicyDoesNotGuessOpen() {
        Fixture f = new Fixture(); f.start(); Iq reply = f.disco(true);
        reply.findChild("query", Namespace.DISCO_INFO).addChild("feature").setAttribute("var", "muc_open");
        f.reply(0, reply); f.status(MucInvitationCoordinator.Status.UNVERIFIABLE); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void duplicateCallbacksSendExactlyOneInvitation() {
        Fixture f = new Fixture(); f.start(); Iq disco = f.disco(true); f.reply(0, disco); f.reply(0, disco);
        for (int i = 0; i < 4; i++) { Iq reply = f.list(ROLES[i], "member"); f.reply(i+1, reply); f.reply(i+1, reply); }
        f.service.deadlines.get(0).fire();
        f.status(MucInvitationCoordinator.Status.HANDED_OFF); assertEquals(1, f.service.messages.size()); assertEquals(5, f.service.iqs.size());
    }
    @Test public void resourcesAndRepeatedTargetsAreDeduplicatedWithinBatch() {
        Fixture f = new Fixture(); f.start(List.of(TARGET, TARGET.withResource("device"), TARGET)); f.reply(0, f.disco(false));
        assertEquals(1, f.results.get(0).outcomes.size()); assertEquals(1, f.service.messages.size());
    }
    @Test public void repeatedPressIsBusyUntilOriginalOperationEnds() {
        Fixture f = new Fixture(); f.start(); List<MucInvitationCoordinator.Summary> duplicate = new ArrayList<>();
        f.service.inviteMucSafely(f.room, List.of(TARGET), duplicate::add);
        assertEquals(MucInvitationCoordinator.Status.BUSY, duplicate.get(0).outcomes.get(TARGET));
        assertEquals(1, f.service.iqs.size()); f.reply(0, f.disco(false)); assertEquals(1, f.service.messages.size());
    }
    @Test public void multipleRecipientsReportPartialHandoffAndMembershipFailure() {
        Fixture f = new Fixture(); Jid other = Jid.of("other@example.test"); f.start(List.of(TARGET, other));
        f.reply(0, f.disco(false)); assertTrue(f.results.isEmpty());
        f.reply(1, f.disco(true)); f.lists(2, "none");
        assertEquals(1, f.results.size()); assertEquals(1, f.results.get(0).handedOff());
        assertEquals(MucInvitationCoordinator.Status.HANDED_OFF, f.results.get(0).outcomes.get(TARGET));
        assertEquals(MucInvitationCoordinator.Status.NEEDS_MEMBERSHIP, f.results.get(0).outcomes.get(other));
    }
    @Test public void allRejectedHasZeroHandoffsWithSeparateReasons() {
        Fixture f = new Fixture(); f.ban(TARGET); f.start(List.of(TARGET, Jid.of("example.test")));
        assertEquals(0, f.results.get(0).handedOff()); assertEquals(2, f.results.get(0).outcomes.size());
        assertTrue(f.service.messages.isEmpty());
    }
    @Test public void cancelIgnoresLateReplyAndDoesNotReleaseNewRequest() {
        Fixture f = new Fixture(); f.start(); f.ticket.cancel(); f.status(MucInvitationCoordinator.Status.CANCELLED);
        f.start(); f.reply(0, f.disco(false)); assertTrue(f.results.isEmpty());
        List<MucInvitationCoordinator.Summary> busy = new ArrayList<>();
        f.service.inviteMucSafely(f.room, List.of(TARGET), busy::add);
        assertEquals(MucInvitationCoordinator.Status.BUSY, busy.get(0).outcomes.get(TARGET));
        f.reply(1, f.disco(false)); f.status(MucInvitationCoordinator.Status.HANDED_OFF); assertEquals(1, f.service.messages.size());
    }
    @Test public void disconnectResetLeaveArchiveAndActorChangeInvalidateWork() {
        for (String change : List.of("disconnect", "reconnect", "reset", "leave", "archive", "connection", "actor")) {
            Fixture f = new Fixture(); f.start();
            switch (change) {
                case "disconnect": f.account.setStatus(Account.State.OFFLINE); break;
                case "reconnect": f.account.setStatus(Account.State.OFFLINE); f.account.setXmppConnection(new XmppConnection(f.account, f.service)); f.account.setStatus(Account.State.ONLINE); f.room.resetMucOptions(); f.options().setOnline(); break;
                case "reset": f.room.resetMucOptions(); f.options().setOnline(); break;
                case "leave": f.options().setOffline(); break;
                case "archive": f.room.setStatus(Conversation.STATUS_ARCHIVED); break;
                case "connection": f.account.setXmppConnection(new XmppConnection(f.account, f.service)); break;
                default: f.options().getSelf().setAffiliation("member");
            }
            f.reply(0, f.disco(false)); f.status(MucInvitationCoordinator.Status.STALE); assertTrue(f.service.messages.isEmpty());
        }
    }
    @Test public void transportTimeoutAndWholeOperationDeadlineAreFinite() {
        Fixture f = new Fixture(); f.start(); f.reply(0, Iq.TIMEOUT); f.status(MucInvitationCoordinator.Status.TIMEOUT);
        f = new Fixture(); f.start(); f.reply(0, f.disco(true)); f.reply(1, Iq.TIMEOUT); f.status(MucInvitationCoordinator.Status.TIMEOUT);
        f = new Fixture(); f.start(); f.reply(0, f.disco(true)); f.service.deadlines.get(0).fire();
        f.status(MucInvitationCoordinator.Status.TIMEOUT); f.reply(1, f.list("owner", "owner")); assertTrue(f.service.messages.isEmpty());
    }
    @Test public void directPacketPreservesRoomAndPasswordMetadata() {
        Fixture f = new Fixture(); f.options().setPassword("synthetic password"); f.start(); f.reply(0, f.disco(false));
        Element x = f.service.messages.get(0).findChild("x", "jabber:x:conference");
        assertEquals(f.room.getJid().asBareJid().toString(), x.getAttribute("jid"));
        assertEquals("synthetic password", x.getAttribute("password"));
    }
    @Test public void unconfirmedHandoffNeverReportsSuccessOrRetries() {
        Fixture f = new Fixture(); f.service.failMessage = true; f.start(); f.reply(0, f.disco(false)); f.reply(0, f.disco(false));
        f.status(MucInvitationCoordinator.Status.UNCONFIRMED); assertEquals(0, f.results.get(0).handedOff()); assertEquals(1, f.service.messages.size());
    }
    @Test public void pickerReturnsPendingAndOnlyAsyncSummaryReportsHandoff() {
        Fixture f = new Fixture(); Activity activity = new Activity(f.service);
        Intent data = new Intent().putExtra(ChooseContactActivity.EXTRA_CONVERSATION, f.room.getUuid())
                .putExtra(ChooseContactActivity.EXTRA_SELECT_MULTIPLE, true).putExtra("contacts", new String[] {TARGET.toString()});
        assertEquals(XmppActivity.ConferenceInvite.Result.INVITES_PENDING, XmppActivity.ConferenceInvite.parse(data).execute(activity));
        assertTrue(activity.results.isEmpty()); f.reply(0, f.disco(false));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1, activity.results.size()); assertEquals(1, activity.results.get(0).handedOff());
    }
    @Test public void ownOtherDeviceDirectPacketRetainsFullJidAndHints() {
        Fixture f = new Fixture(); Jid other = f.account.getJid().withResource("other"); f.service.directInvite(f.room, other);
        Message packet = f.service.messages.get(0); assertEquals(other, packet.getTo());
        assertNotNull(packet.findChild("no-copy", "urn:xmpp:hints")); assertNotNull(packet.findChild("no-store", "urn:xmpp:hints"));
    }
    @Test public void legacyClosedGroupMediatedPathIsPreservedButNotAnAdmissionProof() {
        Fixture f = new Fixture(); assertEquals("1", IqGenerator.defaultGroupChatConfiguration().getString("muc#roomconfig_membersonly"));
        assertTrue(f.service.invite(f.room, TARGET)); assertEquals(1, f.service.messages.size());
        Message packet = f.service.messages.get(0); assertEquals(f.room.getJid(), packet.getTo());
        assertEquals(TARGET.toString(), packet.findChild("x", Namespace.MUC_USER).findChild("invite").getAttribute("to"));
        assertTrue(f.service.iqs.isEmpty()); // Preserved baseline, not an end-to-end safe bootstrap test.
    }

    @Test public void pickerCachedBanHasAsyncReasonInsteadOfSentToast() {
        Fixture f = new Fixture(); f.ban(TARGET); Activity activity = new Activity(f.service);
        Intent data = new Intent().putExtra(ChooseContactActivity.EXTRA_CONVERSATION, f.room.getUuid())
                .putExtra(ChooseContactActivity.EXTRA_SELECT_MULTIPLE, true).putExtra("contacts", new String[] {TARGET.toString()});
        assertEquals(XmppActivity.ConferenceInvite.Result.INVITES_PENDING, XmppActivity.ConferenceInvite.parse(data).execute(activity));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(MucInvitationCoordinator.Status.BANNED, activity.results.get(0).outcomes.get(TARGET));
        assertTrue(f.service.messages.isEmpty());
    }
    @Test public void activityCancellationBeforeHandoffPreventsLateSend() {
        Fixture f = new Fixture(); Activity activity = new Activity(f.service);
        activity.startMucInvitations(f.room, List.of(TARGET)); activity.cancelForTest(); f.reply(0, f.disco(false));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(f.service.messages.isEmpty());
        assertEquals(MucInvitationCoordinator.Status.CANCELLED, activity.results.get(0).outcomes.get(TARGET));
    }
    public static final class Activity extends XmppActivity {
        final List<MucInvitationCoordinator.Summary> results = new ArrayList<>();
        Activity(Service service) { xmppConnectionService = service; }
        void cancelForTest() { cancelMucInvitationRequests(); }
        @Override public void showMucInvitationChecking() {}
        @Override public void showMucInvitationSummary(MucInvitationCoordinator.Summary summary) { results.add(summary); }
        @Override protected void onBackendConnected() {}
        @Override protected void refreshUiReal() {}
    }
    static final class Fixture {
        final Service service = new Service(); final Account account = new Account(Jid.of("owner@example.test/device"), "");
        final Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        final List<MucInvitationCoordinator.Summary> results = new ArrayList<>(); MucInvitationCoordinator.Ticket ticket;
        Fixture() { service.room = room; service.account = account; account.setStatus(Account.State.ONLINE); account.setXmppConnection(new XmppConnection(account, service)); options().getSelf().setAffiliation("owner"); options().setOnline(); }
        MucOptions options() { return room.getMucOptions(); }
        void start() { start(List.of(TARGET)); }
        void start(List<Jid> targets) { results.clear(); ticket = service.inviteMucSafely(room, targets, results::add); }
        Iq base() { Iq reply = new Iq(Iq.Type.RESULT); reply.setFrom(room.getJid().asBareJid()); return reply; }
        Iq disco(boolean members) { Iq reply = base(); reply.addChild("query", Namespace.DISCO_INFO).addChild("feature").setAttribute("var", members ? "muc_membersonly" : "muc_open"); return reply; }
        Iq list(String role, String targetRole) { Iq reply = base(); Element query = reply.addChild("query", Namespace.MUC_ADMIN); if (role.equals(targetRole)) query.addChild("item").setAttribute("affiliation", role).setAttribute("jid", TARGET); return reply; }
        void lists(int offset, String role) { for (int i = 0; i < 4; i++) reply(offset+i, list(ROLES[i], role)); }
        Iq error() { Iq reply = new Iq(Iq.Type.ERROR); reply.setFrom(room.getJid()); var error = new im.conversations.android.xmpp.model.error.Error(); error.setCondition(new im.conversations.android.xmpp.model.error.Condition.Forbidden()); reply.addExtension(error); return reply; }
        void reply(int index, Iq reply) { service.callbacks.get(index).accept(reply); }
        void status(MucInvitationCoordinator.Status status) { assertEquals(1, results.size()); assertEquals(status, results.get(0).outcomes.get(TARGET)); }
        void ban(Jid jid) { var user = new MucOptions.User(options(), room.getJid().withResource("cached")); user.setRealJid(jid); user.setRole("participant"); user.setAffiliation("member"); options().updateUser(user); user.setAffiliation("outcast"); }
    }
    static final class Service extends XmppConnectionService {
        Account account; Conversation room; boolean failMessage;
        final List<Iq> iqs = new ArrayList<>(); final List<Consumer<Iq>> callbacks = new ArrayList<>(); final List<Message> messages = new ArrayList<>(); final List<Deadline> deadlines = new ArrayList<>();
        @Override public Account findAccountByUuid(String uuid) { return account; }
        @Override public Conversation findConversationByUuid(String uuid) { return room; }
        @Override void executeMucAffiliationPreflight(Runnable work) { work.run(); }
        @Override ScheduledFuture<?> scheduleMucAffiliationPreflight(Runnable work, long seconds) { assertTrue(seconds == 40 || seconds == 50); Deadline deadline = new Deadline(work); deadlines.add(deadline); return deadline; }
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback, Long seconds) { assertEquals(Iq.Type.GET, iq.getType()); assertEquals(Long.valueOf(10), seconds); iqs.add(iq); callbacks.add(callback); }
        @Override public void sendMessagePacket(Account account, Message message) { messages.add(message); if (failMessage) throw new IllegalStateException("test send interrupted"); }
        @Override public void showErrorToastInUi(int message) {}
    }
    static final class Deadline implements ScheduledFuture<Object> {
        final Runnable work; boolean cancelled;
        Deadline(Runnable work) { this.work = work; }
        void fire() { work.run(); }
        public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return cancelled; }
        public Object get() { return null; }
        public Object get(long seconds, TimeUnit unit) { return null; }
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
    }
}
