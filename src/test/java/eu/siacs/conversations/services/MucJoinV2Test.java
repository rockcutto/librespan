package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.MucJoinReliabilityTest.Fixture;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.*;
import im.conversations.android.xmpp.model.stanza.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucJoinV2Test {
    private void status(Fixture f, Account.State state) throws Exception {
        f.account.setStatus(state);
        Field field = XmppConnectionService.class.getDeclaredField("statusListener");
        field.setAccessible(true);
        ((OnStatusChanged) field.get(f.service)).onStatusChanged(f.account);
    }
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    @Test public void onlinePassesEmptyMapBeforeMarkerPublication() throws Exception {
        Fixture f = new Fixture(); f.join();
        CountDownLatch cancelled = new CountDownLatch(1), scanned = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        f.service.afterJoinTerminated = () -> {
            if (Thread.currentThread().getName().equals("cancel-race") && once.compareAndSet(false, true)) {
                cancelled.countDown(); await(scanned);
            }
        };
        f.service.conversationsHook = () -> {
            if (Thread.currentThread().getName().equals("online-race")) scanned.countDown();
        };
        Thread cancel = new Thread(() -> { try { status(f, Account.State.CONNECTING); } catch(Throwable t) { failure.set(t); } }, "cancel-race");
        Thread online = new Thread(() -> { try { status(f, Account.State.ONLINE); } catch(Throwable t) { failure.set(t); } }, "online-race");
        cancel.start(); await(cancelled); online.start();
        cancel.join(6000); online.join(6000);
        assertFalse(cancel.isAlive()); assertFalse(online.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(2, f.service.iqs.size());
        f.disco(1); f.selfPresence(); assertTrue(f.options().online());
    }
    @Test public void offlineTimeoutBeforeStatusListenerRetainsRecovery() throws Exception {
        Fixture f = new Fixture(); f.service.joinMuc(f.room);
        f.account.setStatus(Account.State.CONNECTING);
        f.service.timeoutMucJoin(f.room, f.options());
        status(f, Account.State.ONLINE);
        assertEquals(2, f.service.iqs.size());
        f.disco(1); f.selfPresence(); assertTrue(f.options().online());
    }
    @Test public void discoRetryKeepsPrivacyGuardAndIsFinite() throws Exception {
        Fixture f = new Fixture(); f.service.joinMuc(f.room);
        f.service.iqs.get(0).accept(Iq.TIMEOUT);
        assertTrue(f.service.sent.isEmpty()); assertEquals(2, f.service.iqs.size());
        Iq result = new Iq(Iq.Type.RESULT);
        var q = result.addChild("query", Namespace.DISCO_INFO);
        q.addChild("feature").setAttribute("var", "muc_nonanonymous");
        f.service.iqs.get(1).accept(result);
        assertEquals(MucOptions.Error.NON_ANONYMOUS, f.options().getError());
        assertTrue(f.service.sent.isEmpty());
    }
    private void removed(Fixture f) {
        Presence p = new Presence(); p.setFrom(f.options().getSelf().getFullJid());
        p.setAttribute("type", "unavailable");
        var x = p.addChild("x", Namespace.MUC_USER);
        x.addChild("status").setAttribute("code", "110");
        x.addChild("status").setAttribute("code", "333");
        f.parser.parseConferencePresence(p, f.account);
    }
    private Iq error(String condition) {
        Iq result = new Iq(Iq.Type.ERROR);
        im.conversations.android.xmpp.model.error.Condition code = switch (condition) {
            case "service-unavailable" -> new im.conversations.android.xmpp.model.error.Condition.ServiceUnavailable();
            case "feature-not-implemented" -> new im.conversations.android.xmpp.model.error.Condition.FeatureNotImplemented();
            case "not-acceptable" -> new im.conversations.android.xmpp.model.error.Condition.NotAcceptable();
            case "item-not-found" -> new im.conversations.android.xmpp.model.error.Condition.ItemNotFound();
            case "remote-server-timeout" -> new im.conversations.android.xmpp.model.error.Condition.RemoteServerTimeout();
            default -> throw new IllegalArgumentException("Unexpected fixture error condition: " + condition);
        };
        var error = new im.conversations.android.xmpp.model.error.Error();
        error.setCondition(code);
        result.addExtension(error);
        return result;
    }
    @Test public void membershipPingProvesMembershipWithoutAnotherJoin() throws Exception {
        for (Iq reply : new Iq[] {new Iq(Iq.Type.RESULT), error("service-unavailable"), error("feature-not-implemented")}) {
            Fixture f = new Fixture(); f.join(); f.selfPresence(); removed(f);
            assertFalse(f.options().online());
            f.service.iqs.get(1).accept(reply);
            assertTrue(f.options().online()); assertEquals(1, f.service.sent.size());
            assertFalse(f.account.inProgressConferencePings.contains(f.room));
        }
    }
    @Test public void negativePingRejoinsAndLateResultAfterLeaveIsIgnored() throws Exception {
        Fixture f = new Fixture(); f.join(); f.selfPresence(); removed(f);
        var callback = f.service.iqs.get(1);
        callback.accept(error("not-acceptable"));
        assertEquals(3, f.service.iqs.size());
        f.disco(2); f.selfPresence(); assertTrue(f.options().online());
        callback.accept(new Iq(Iq.Type.RESULT)); assertEquals(3, f.service.iqs.size());
        removed(f); var late = f.service.iqs.get(3);
        f.service.leaveMuc(f.room); late.accept(new Iq(Iq.Type.RESULT));
        assertFalse(f.options().online());
    }
    @Test public void resultFromBefore333CannotRestoreMembership() throws Exception {
        Fixture f = new Fixture(); f.join(); f.selfPresence();
        f.service.mucSelfPingAndRejoin(f.room);
        var old = f.service.iqs.get(1);
        removed(f);
        old.accept(new Iq(Iq.Type.RESULT));
        assertFalse(f.options().online());
        assertEquals(3, f.service.iqs.size());
        f.service.iqs.get(2).accept(new Iq(Iq.Type.RESULT));
        assertTrue(f.options().online());
    }
    @Test public void uncertainMembershipHasOnlyOneRetryAndNeverGuessesRejoin() throws Exception {
        for (Iq reply : new Iq[] {Iq.TIMEOUT, error("item-not-found"), error("remote-server-timeout")}) {
            Fixture f = new Fixture(); f.join(); f.selfPresence(); removed(f);
            f.service.iqs.get(1).accept(reply);
            assertEquals(1, f.service.membershipRetries.size());
            f.service.membershipRetries.get(0).run();
            f.service.iqs.get(2).accept(reply);
            assertEquals(1, f.service.membershipRetries.size());
            assertFalse(f.options().online()); assertEquals(1, f.service.sent.size());
            assertFalse(f.account.inProgressConferencePings.contains(f.room));
        }
    }
    @Test public void outgoingGateClosesUntilSelfPresence() throws Exception {
        Fixture f = new Fixture(); f.join();
        var method = XmppConnectionService.class.getDeclaredMethod("isJoinInProgress", Conversation.class);
        method.setAccessible(true);
        assertEquals(true, method.invoke(f.service, f.room));
        f.selfPresence(); assertEquals(false, method.invoke(f.service, f.room));
        removed(f); assertEquals(true, method.invoke(f.service, f.room));
    }
    @Test public void freshBindStartsAllAvailableRoomsOnce() throws Exception {
        for (int count : new int[] {1,5,15}) {
            Fixture f = new Fixture();
            for (int i = 1; i < count; i++) f.service.extraRooms.add(new Conversation("r"+i, f.account,
                    Jid.of("r"+i+"@conference.example"), Conversation.MODE_MULTI, null));
            f.service.connectMultiModeConversations(f.account);
            assertEquals(count, f.service.iqs.size());
            f.service.connectMultiModeConversations(f.account);
            assertEquals(count, f.service.iqs.size());
        }
    }
}
