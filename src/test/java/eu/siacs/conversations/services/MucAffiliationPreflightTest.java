package eu.siacs.conversations.services;

import static org.junit.Assert.*;

import android.util.Pair;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Actual read-only component and service context guard; only IQ transport/executor/clock are fake. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucAffiliationPreflightTest {
    private static final Jid TARGET = Jid.of("absent@example.test");
    private static final String[] LISTS = {"owner", "admin", "member", "outcast"};

    @Test public void ownerAbsentFromLocalRosterIsFoundOnServer() { known("owner", MucAffiliationPreflight.Affiliation.OWNER); }
    @Test public void adminAbsentFromLocalRosterIsFoundOnServer() { known("admin", MucAffiliationPreflight.Affiliation.ADMIN); }
    @Test public void memberIsFoundOnServer() { known("member", MucAffiliationPreflight.Affiliation.MEMBER); }
    @Test public void outcastIsFoundOnServerWithoutChangingBan() { known("outcast", MucAffiliationPreflight.Affiliation.OUTCAST); }

    private void known(String role, MucAffiliationPreflight.Affiliation expected) {
        Fixture f = new Fixture(); assertNull(f.room.getMucOptions().findUserByRealJid(TARGET));
        f.start(); f.complete(role);
        f.result(expected, MucAffiliationPreflight.Reason.VERIFIED);
        assertNull(f.room.getMucOptions().findUserByRealJid(TARGET));
    }

    @Test public void noneRequiresAllFourSuccessfulCompleteLists() {
        Fixture f = new Fixture(); f.start();
        for (int i = 0; i < 3; i++) { f.reply(i, f.list(LISTS[i], null)); assertTrue(f.results.isEmpty()); }
        f.reply(3, f.list("outcast", null));
        f.result(MucAffiliationPreflight.Affiliation.NONE, MucAffiliationPreflight.Reason.VERIFIED);
        assertEquals(4, f.service.sent.size());
    }

    @Test public void successfulEmptyListIsNotAnError() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.list("owner", null));
        assertTrue(f.results.isEmpty()); assertEquals(2, f.service.sent.size());
        f.reply(1, f.list("admin", "admin")); f.reply(2, f.list("member", null)); f.reply(3, f.list("outcast", null));
        f.result(MucAffiliationPreflight.Affiliation.ADMIN, MucAffiliationPreflight.Reason.VERIFIED);
    }

    @Test public void forbiddenAnyMandatoryListIsUnknownEvenAfterPositiveObservation() {
        for (int failed = 0; failed < 4; failed++) {
            Fixture f = new Fixture(); f.start();
            for (int i = 0; i < failed; i++) f.reply(i, f.list(LISTS[i], "owner"));
            f.reply(failed, f.error(true));
            f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.FORBIDDEN);
            assertEquals(failed + 1, f.service.sent.size());
        }
    }

    @Test public void otherServerErrorIsUnknownNotNone() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.error(false));
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.SERVER_ERROR);
    }

    @Test public void syntheticIqTimeoutWithoutFromOrIdTerminates() {
        Fixture f = new Fixture(); f.start(); f.reply(0, Iq.TIMEOUT);
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.TIMEOUT);
        assertTrue(f.service.deadline.cancelled);
    }

    @Test public void watchdogBoundsLostCallbackAtAnyStage() {
        for (int stage = 0; stage < 4; stage++) {
            Fixture f = new Fixture(); f.start();
            for (int i = 0; i < stage; i++) f.reply(i, f.list(LISTS[i], null));
            assertEquals(40L, f.service.budget); f.service.deadline.fire();
            f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.TIMEOUT);
            f.reply(stage, f.list(LISTS[stage], null)); assertEquals(1, f.results.size());
        }
    }

    @Test public void malformedReplyCannotBecomeAnEmptyList() {
        for (String kind : List.of("missing-query", "wrong-namespace", "two-queries", "unexpected-type",
                "missing-affiliation", "wrong-affiliation", "missing-jid", "invalid-jid", "resource-jid", "wrong-item-namespace")) {
            Fixture f = new Fixture(); f.start(); Iq reply = f.list("owner", "owner");
            Element query = reply.findChild("query", Namespace.MUC_ADMIN);
            Element item = query.getChildren().get(0);
            switch (kind) {
                case "missing-query": reply.removeChild(query); break;
                case "wrong-namespace": query.setAttribute("xmlns", "unexpected"); break;
                case "two-queries": reply.addChild("query", Namespace.MUC_ADMIN); break;
                case "unexpected-type": reply.setAttribute("type", "get"); break;
                case "missing-affiliation": item.getAttributes().remove("affiliation"); break;
                case "wrong-affiliation": item.setAttribute("affiliation", "admin"); break;
                case "missing-jid": item.getAttributes().remove("jid"); break;
                case "invalid-jid": item.setAttribute("jid", "broken@"); break;
                case "resource-jid": item.setAttribute("jid", TARGET.withResource("device")); break;
                default: item.setAttribute("xmlns", "unexpected");
            }
            f.reply(0, reply);
            assertEquals(kind, 1, f.results.size());
            f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.MALFORMED_RESPONSE);
        }
    }

    @Test public void explicitPartialAndPaginationMarkersCannotProveNone() {
        for (String marker : List.of("false", "0", "unknown", "rsm", "more")) {
            Fixture f = new Fixture(); f.start(); Iq reply = f.list("owner", null);
            Element query = reply.findChild("query", Namespace.MUC_ADMIN);
            if (marker.equals("rsm")) query.addChild("set", "http://jabber.org/protocol/rsm");
            else if (marker.equals("more")) query.setAttribute("more", "1");
            else query.setAttribute("complete", marker);
            f.reply(0, reply);
            f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.MALFORMED_RESPONSE);
        }
    }

    @Test public void duplicateRecordsWithinListAreConflicting() {
        Fixture f = new Fixture(); f.start(); Iq reply = f.list("owner", "owner");
        reply.findChild("query", Namespace.MUC_ADMIN).addChild("item").setAttribute("affiliation", "owner").setAttribute("jid", TARGET);
        f.reply(0, reply); f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.CONFLICT);
    }

    @Test public void conflictingRolesAcrossListsNeverProduceNoneOrPrivilegeGuess() {
        Fixture f = new Fixture(); f.start(); f.reply(0, f.list("owner", "owner")); f.reply(1, f.list("admin", "admin"));
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.CONFLICT);
    }

    @Test public void matchingDomainRulesAreNotGuessedAsUserNone() {
        Fixture f = new Fixture(); f.start();
        for (int i = 0; i < 4; i++) {
            Iq reply = f.list(LISTS[i], null);
            if (i == 3) reply.findChild("query", Namespace.MUC_ADMIN).addChild("item")
                    .setAttribute("affiliation", "outcast").setAttribute("jid", "example.test");
            f.reply(i, reply);
        }
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.UNVERIFIABLE);
    }

    @Test public void readsDoNotUpdateCachedAffiliationRosterOrCryptoTargets() {
        Fixture f = new Fixture(); MucOptions options = f.room.getMucOptions();
        MucOptions.User cached = new MucOptions.User(options, f.room.getJid().withResource("cached"));
        cached.setRealJid(TARGET); cached.setRole("participant"); cached.setAffiliation("member"); options.updateUser(cached);
        f.room.setAcceptedCryptoTargets(List.of(TARGET));
        var users = new ArrayList<>(options.getUsers()); var crypto = new ArrayList<>(f.room.getAcceptedCryptoTargets());
        f.start(); f.complete("owner"); f.result(MucAffiliationPreflight.Affiliation.OWNER, MucAffiliationPreflight.Reason.VERIFIED);
        assertEquals(MucOptions.Affiliation.MEMBER, cached.getAffiliation()); assertEquals(users, options.getUsers());
        assertEquals(crypto, f.room.getAcceptedCryptoTargets());
    }

    @Test public void duplicateCallbackAndExpiredWatchdogCannotChangeFinalResult() {
        Fixture f = new Fixture(); f.start();
        for (int i = 0; i < 4; i++) { Iq reply = f.list(LISTS[i], "member"); f.reply(i, reply); f.reply(i, reply); }
        f.result(MucAffiliationPreflight.Affiliation.MEMBER, MucAffiliationPreflight.Reason.VERIFIED);
        f.service.deadline.fire(); assertEquals(1, f.results.size()); assertEquals(4, f.service.sent.size());
    }

    @Test public void resetMakesOldCallbackStaleAndDoesNotCompleteNewRequest() {
        Fixture f = new Fixture(); f.start(); MucAffiliationPreflight old = f.request;
        f.room.resetMucOptions(); f.room.getMucOptions().setOnline();
        List<MucAffiliationPreflight.Result> newer = new ArrayList<>();
        f.service.preflightMucAffiliation(f.account, f.room, TARGET, newer::add);
        f.reply(0, f.list("owner", "owner"));
        assertTrue(old.isDone()); f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.STALE_CONTEXT);
        assertTrue(newer.isEmpty());
        for (int i = 0; i < 4; i++) f.reply(i+1, f.list(LISTS[i], "admin"));
        assertEquals(1, newer.size()); assertEquals(MucAffiliationPreflight.Affiliation.ADMIN, newer.get(0).affiliation);
        f.reply(0, f.list("owner", "owner")); assertEquals(1, newer.size());
    }

    @Test public void changedAccountRoomOrConnectionCancelsObservation() throws Exception {
        for (String change : List.of("disconnect", "leave", "archive", "delete-account", "delete-room", "connection", "room-address", "destroy")) {
            Fixture f = new Fixture(); f.start();
            switch (change) {
                case "disconnect": f.account.setStatus(Account.State.OFFLINE); break;
                case "leave": f.room.getMucOptions().setOffline(); break;
                case "archive": f.room.setStatus(Conversation.STATUS_ARCHIVED); break;
                case "delete-account": f.service.account = null; break;
                case "delete-room": f.service.room = null; break;
                case "connection": f.account.setXmppConnection(new XmppConnection(f.account, f.service)); break;
                case "room-address": f.room.setContactJid(Jid.of("other@conference.example")); break;
                default: Field destroyed = XmppConnectionService.class.getDeclaredField("destroyed"); destroyed.setAccessible(true); destroyed.setBoolean(f.service, true);
            }
            f.reply(0, f.list("owner", "owner"));
            f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.STALE_CONTEXT);
            assertEquals(1, f.service.sent.size());
        }
    }

    @Test public void disconnectWithoutTransportCallbackTerminatesAtWatchdog() {
        Fixture f = new Fixture(); f.start(); f.account.setStatus(Account.State.OFFLINE); f.service.deadline.fire();
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.STALE_CONTEXT);
    }

    @Test public void invalidInputAndUnjoinedRoomSendNoIq() {
        for (Jid target : new Jid[] {null, Jid.of("example.test"), TARGET.withResource("resource"), Jid.ofOrInvalid("broken@")}) {
            Fixture f = new Fixture(); f.request = f.service.preflightMucAffiliation(f.account, f.room, target, f.results::add);
            f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.INVALID_INPUT);
            assertTrue(f.service.sent.isEmpty());
        }
        Fixture f = new Fixture(); f.room.getMucOptions().setOffline(); f.start();
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.STALE_CONTEXT); assertTrue(f.service.sent.isEmpty());
    }

    @Test public void rejectedExecutorAndUnavailableTransportCompleteSafely() {
        Fixture f = new Fixture(); f.service.closed = true; f.start();
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.STALE_CONTEXT);
        f = new Fixture(); f.service.failSend = true; f.start();
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.SERVER_ERROR);
    }

    @Test public void explicitCancelIgnoresLateCallback() {
        Fixture f = new Fixture(); f.start(); f.request.cancel(); f.reply(0, f.list("owner", "owner"));
        f.result(MucAffiliationPreflight.Affiliation.UNKNOWN, MucAffiliationPreflight.Reason.CANCELLED);
    }

    @Test @SuppressWarnings("unchecked") public void actualTransportCorrelatesIdAndExactRoomSender() throws Exception {
        Fixture f = new Fixture(); XmppConnection transport = f.account.getXmppConnection();
        Field field = XmppConnection.class.getDeclaredField("packetCallbacks"); field.setAccessible(true);
        Map<String, Object> callbacks = (Map<String, Object>) field.get(transport);
        Iq sent = f.service.getIqGenerator().queryAffiliation(f.room, "owner"); sent.setId("preflight-id");
        Consumer<Iq> consumer = ignored -> {};
        callbacks.put(sent.getId(), Pair.create(sent, Pair.create(consumer, null)));
        Method lookup = XmppConnection.class.getDeclaredMethod("getIqPacketReceivedCallback", Iq.class); lookup.setAccessible(true);
        for (Jid sender : new Jid[] {null, TARGET, f.room.getJid().withResource("occupant")}) {
            Iq reply = f.base(); reply.setId(sent.getId()); if (sender == null) reply.getAttributes().remove("from"); else reply.setFrom(sender);
            assertNull(lookup.invoke(transport, reply)); assertTrue(callbacks.containsKey(sent.getId()));
        }
        Iq wrongId = f.base(); wrongId.setId("unrelated"); assertNull(lookup.invoke(transport, wrongId));
        Iq valid = f.base(); valid.setId(sent.getId()); assertSame(consumer, lookup.invoke(transport, valid));
        assertNull(lookup.invoke(transport, valid));
    }

    static final class Fixture {
        final Service service = new Service();
        final Account account = new Account(Jid.of("reader@example.test/device"), "");
        final Conversation room = new Conversation("room", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null);
        final Jid initialRoomJid = room.getJid().asBareJid();
        final List<MucAffiliationPreflight.Result> results = new ArrayList<>();
        MucAffiliationPreflight request;
        Fixture() {
            service.account = account; service.room = room;
            account.setStatus(Account.State.ONLINE); account.setXmppConnection(new XmppConnection(account, service)); room.getMucOptions().setOnline();
        }
        void start() { request = service.preflightMucAffiliation(account, room, TARGET, results::add); }
        Iq base() { Iq iq = new Iq(Iq.Type.RESULT); iq.setFrom(room.getJid().asBareJid()); return iq; }
        Iq list(String role, String targetRole) {
            Iq iq = base(); Element query = iq.addChild("query", Namespace.MUC_ADMIN);
            if (role.equals(targetRole)) query.addChild("item").setAttribute("jid", TARGET).setAttribute("affiliation", role);
            return iq;
        }
        Iq error(boolean forbidden) {
            Iq iq = new Iq(Iq.Type.ERROR); iq.setFrom(room.getJid().asBareJid());
            var error = new im.conversations.android.xmpp.model.error.Error();
            error.setCondition(forbidden ? new im.conversations.android.xmpp.model.error.Condition.Forbidden()
                    : new im.conversations.android.xmpp.model.error.Condition.ServiceUnavailable());
            iq.addExtension(error); return iq;
        }
        void reply(int index, Iq reply) { service.callbacks.get(index).accept(reply); }
        void complete(String role) { for (int i = 0; i < 4; i++) reply(i, list(LISTS[i], role)); }
        void result(MucAffiliationPreflight.Affiliation affiliation, MucAffiliationPreflight.Reason reason) {
            assertEquals(1, results.size()); assertEquals(affiliation, results.get(0).affiliation); assertEquals(reason, results.get(0).reason);
            if (request != null) assertTrue(request.isDone());
            for (Iq iq : service.sent) { assertEquals(Iq.Type.GET, iq.getType()); assertEquals(initialRoomJid, iq.getTo()); }
        }
    }
    static final class Service extends XmppConnectionService {
        Account account; Conversation room;
        final List<Iq> sent = new ArrayList<>(); final List<Consumer<Iq>> callbacks = new ArrayList<>();
        Deadline deadline; long budget; boolean closed; boolean failSend;
        @Override public Account findAccountByUuid(String uuid) { return account != null && account.getUuid().equals(uuid) ? account : null; }
        @Override public Conversation findConversationByUuid(String uuid) { return room != null && room.getUuid().equals(uuid) ? room : null; }
        @Override void executeMucAffiliationPreflight(Runnable work) {
            if (closed) throw new java.util.concurrent.RejectedExecutionException(); work.run();
        }
        @Override ScheduledFuture<?> scheduleMucAffiliationPreflight(Runnable timeout, long seconds) {
            budget = seconds; deadline = new Deadline(timeout); return deadline;
        }
        @Override public void sendIqPacket(Account account, Iq iq, Consumer<Iq> callback, Long seconds) {
            assertEquals(Iq.Type.GET, iq.getType()); assertEquals(Long.valueOf(10), seconds);
            if (failSend) throw new IllegalStateException("test transport unavailable"); sent.add(iq); callbacks.add(callback);
        }
        @Override public void sendMessagePacket(Account account, im.conversations.android.xmpp.model.stanza.Message packet) { fail("preflight must not send messages or invitations"); }
        @Override public void sendPresencePacket(Account account, im.conversations.android.xmpp.model.stanza.Presence packet) { fail("preflight must not send presence"); }
    }
    static final class Deadline implements ScheduledFuture<Object> {
        final Runnable timeout; boolean cancelled;
        Deadline(Runnable timeout) { this.timeout = timeout; }
        void fire() { timeout.run(); }
        public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return cancelled; }
        public Object get() { return null; }
        public Object get(long timeout, TimeUnit unit) { return null; }
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
    }
}
