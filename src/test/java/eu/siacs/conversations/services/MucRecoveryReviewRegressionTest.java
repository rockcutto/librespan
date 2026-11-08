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
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** V3 review regressions. Real service/parser callbacks, controlled transport and timer. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucRecoveryReviewRegressionTest {
    private Presence unavailable(Fixture f, String... codes) {
        Presence p = new Presence();
        p.setFrom(f.options().getSelf().getFullJid());
        p.setAttribute("type", "unavailable");
        var x = p.addChild("x", Namespace.MUC_USER);
        x.addChild("status").setAttribute("code", "110");
        for (String code : codes) x.addChild("status").setAttribute("code", code);
        return p;
    }
    private void remove(Fixture f, String... codes) { f.parser.parseConferencePresence(unavailable(f,codes), f.account); }
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
    private void online(Fixture f) throws Exception {
        Field field=XmppConnectionService.class.getDeclaredField("statusListener");
        field.setAccessible(true);
        ((OnStatusChanged)field.get(f.service)).onStatusChanged(f.account);
    }
    @Test public void duplicateTechnicalUnavailableDoesNotCancelPendingRecovery() throws Exception {
        Fixture f=new Fixture(); f.join(); f.selfPresence();
        remove(f,"332");
        Runnable original=f.service.roomRecoveries.get(0);
        long first=f.options().getJoinAttempt().getEpoch();
        remove(f,"332"); remove(f,"332");
        assertTrue(f.options().getJoinAttempt().getEpoch()>first);
        assertEquals(1,f.service.roomRecoveries.size());
        assertEquals(List.of(30),f.service.roomRecoveryDelays);
        original.run(); // must use the original deadline, not a replacement timer
        assertEquals(2,f.service.iqs.size());
        f.service.iqs.get(1).accept(Iq.TIMEOUT); f.service.iqs.get(2).accept(Iq.TIMEOUT);
        assertEquals(List.of(30,120),f.service.roomRecoveryDelays); // budget was not reset
        f.service.roomRecoveries.get(1).run(); f.disco(3); f.selfPresence();
        assertTrue(f.options().online());
        int requests=f.service.iqs.size(); original.run();
        assertEquals(requests,f.service.iqs.size()); // stale timer cannot resurrect completed cycle
    }
    @Test public void duplicate333AndTimeoutKeepTheScheduledProbeRound() throws Exception {
        Fixture f=new Fixture(); f.join(); f.selfPresence();
        remove(f,"333");
        Runnable original=f.service.roomRecoveries.get(0);
        f.service.iqs.get(1).accept(Iq.TIMEOUT); // inconclusive, one immediate check retry scheduled
        remove(f,"333");
        f.service.membershipRetries.get(0).run();
        f.service.iqs.get(2).accept(Iq.TIMEOUT); // old epoch discarded; fresh probe starts
        f.service.iqs.get(3).accept(Iq.TIMEOUT);
        f.service.membershipRetries.get(1).run(); f.service.iqs.get(4).accept(Iq.TIMEOUT);
        assertEquals(1,f.service.roomRecoveries.size());
        original.run();
        assertEquals(6,f.service.iqs.size()); // delayed room round still starts a probe
        assertEquals(1,f.service.sent.size()); // no rejoin based only on uncertainty
        assertEquals(List.of(30,120),f.service.roomRecoveryDelays);
    }
    @Test public void repeatedTechnicalCallbackDoesNotConsumeOrPostponeBudget() throws Exception {
        Fixture f=new Fixture(); f.join(); f.selfPresence(); remove(f,"332");
        for(int i=0;i<3;i++) {
            f.options().setError(MucOptions.Error.SHUTDOWN);
            f.service.onMucJoinStateChanged(f.options());
        }
        assertEquals(List.of(30),f.service.roomRecoveryDelays);
        f.service.roomRecoveries.get(0).run(); assertEquals(2,f.service.iqs.size());
    }
    @Test public void genuineRefusalStillCancelsTheOldTimer() throws Exception {
        for(MucOptions.Error error:List.of(MucOptions.Error.BANNED,MucOptions.Error.KICKED,
                MucOptions.Error.MEMBERS_ONLY,MucOptions.Error.PASSWORD_INCORRECT)) {
            Fixture f=new Fixture(); f.join(); f.selfPresence(); remove(f,"332");
            Runnable old=f.service.roomRecoveries.get(0);
            f.options().setError(error); f.service.onMucJoinStateChanged(f.options());
            int requests=f.service.iqs.size(); old.run(); f.service.joinMuc(f.room);
            assertEquals(error,f.options().getError()); assertEquals(requests,f.service.iqs.size());
        }
    }
    @Test public void privateMucConversationCannotStartMembershipProbeOrRejoin() throws Exception {
        Fixture f=new Fixture(); f.service.password="fixture-password";
        f.join(); f.selfPresence();
        Conversation pm=new Conversation("private",f.account,f.room.getJid().asBareJid(),Conversation.MODE_MULTI,null);
        pm.setNextCounterpart(Jid.of("room@conference.example/peer")); pm.setParentConversation(f.room);
        f.service.extraRooms.add(pm);
        assertSame(f.options(),pm.getMucOptions());
        remove(f,"333"); // the PM also appears in findAll; shared options must be processed once
        int requests=f.service.iqs.size();
        f.service.mucSelfPingAndRejoin(pm); online(f);
        assertEquals(2,requests); assertEquals(requests,f.service.iqs.size());
        assertFalse(f.account.inProgressConferencePings.contains(pm));
        f.service.iqs.get(1).accept(error("not-acceptable"));
        assertEquals(3,f.service.iqs.size());
        assertEquals(List.of(f.room,f.room),f.service.secretHydrations);
        f.disco(2);
        assertEquals("fixture-password", f.lastPresence().findChild("x",Namespace.MUC).findChildContent("password"));
        f.selfPresence(); assertTrue(f.options().online());
        assertSame(f.options(),pm.getMucOptions());
        f.service.connectMultiModeConversations(f.account);
        assertTrue(f.service.secretHydrations.stream().allMatch(c -> c==f.room));
    }
    @Test public void technical333With307DoesNotAbortCurrentPreSendJoin() throws Exception {
        Fixture f=new Fixture(); f.service.joinMuc(f.room);
        remove(f,"307","333");
        assertTrue(f.options().getJoinAttempt().isPending());
        assertEquals(MucOptions.Error.NONE,f.options().getError());
        assertTrue(f.service.roomRecoveries.isEmpty());
        f.disco(0); assertEquals(1,f.service.sent.size());
        f.selfPresence(); assertTrue(f.options().online());
        remove(f,"307","333");
        assertEquals(MucOptions.Error.TECHNICAL_PROBLEMS,f.options().getError());
        assertEquals(2,f.service.iqs.size()); // probe, not a second join
        assertEquals(1,f.service.sent.size());
    }
    @Test public void technicalFlagDoesNotOverrideBanOrMembershipRevocation() throws Exception {
        for(String technical:List.of("332","333")) {
        for(String refusal:List.of("301","321","322")) {
            for(boolean joined:new boolean[]{false,true}) {
                Fixture f=new Fixture(); f.service.joinMuc(f.room);
                if(joined) { f.disco(0); f.selfPresence(); }
                remove(f,technical,refusal);
                assertEquals(refusal.equals("301") ? MucOptions.Error.BANNED : MucOptions.Error.MEMBERS_ONLY,
                        f.options().getError());
                assertFalse(f.options().getJoinAttempt().isPending());
                assertTrue(f.service.roomRecoveries.isEmpty());
                int requests=f.service.iqs.size(); f.service.joinMuc(f.room);
                assertEquals(requests,f.service.iqs.size());
            }
        }
    }

    }
    @Test public void shutdownWithAclUsesOwnJidAndNeverRetriesEvenWithout110() throws Exception {
        for(String refusal:List.of("301","321","322")) {
            Fixture f=new Fixture(); f.service.joinMuc(f.room);
            Presence packet=unavailable(f,"332",refusal);
            packet.findChild("x",Namespace.MUC_USER).getChildren().removeIf(child ->
                    child.getName().equals("status") && "110".equals(child.getAttribute("code")));
            Jid own=packet.getFrom(); packet.setFrom(Jid.of("room@conference.example/other"));
            f.parser.parseConferencePresence(packet,f.account);
            assertEquals(MucOptions.Error.NONE,f.options().getError());
            assertTrue(f.options().getJoinAttempt().isPending());
            packet.setFrom(own); f.parser.parseConferencePresence(packet,f.account);
            assertEquals(refusal.equals("301") ? MucOptions.Error.BANNED : MucOptions.Error.MEMBERS_ONLY,
                    f.options().getError());
            assertTrue(f.service.roomRecoveries.isEmpty());
        }
    }

    @Test public void shutdownWithStandaloneKickNeverSchedulesRecovery() throws Exception {
        for(boolean joined:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.service.joinMuc(f.room);
            if(joined) { f.disco(0); f.selfPresence(); }
            remove(f,"332","307");
            assertEquals(MucOptions.Error.KICKED,f.options().getError());
            assertTrue(f.service.roomRecoveries.isEmpty());
        }
    }
    @Test public void standaloneKickRemainsAuthoritativeBeforeAndAfterJoin() throws Exception {
        for(boolean joined:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.service.joinMuc(f.room);
            if(joined) { f.disco(0); f.selfPresence(); }
            remove(f,"307");
            assertEquals(MucOptions.Error.KICKED,f.options().getError());
            assertFalse(f.options().getJoinAttempt().isPending());
            assertTrue(f.service.roomRecoveries.isEmpty());
            int requests=f.service.iqs.size(); f.service.joinMuc(f.room);
            assertEquals(requests,f.service.iqs.size());
        }
    }
}
