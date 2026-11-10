package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.parser.MessageParser;
import eu.siacs.conversations.services.MucJoinReliabilityTest.Fixture;
import eu.siacs.conversations.services.MucJoinReliabilityTest.TestService;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageTextRepository;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Actual join, parser, MAM queries and resendMessage; transport/persistence/notifications recorded.
 * Historical unclassified text is used for resend; this fixture does not validate SCS crypto. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MucRecoverySystemTest {
    private final long messageTime = System.currentTimeMillis() - 60_000;
    private String stamp(int n) { return java.time.Instant.ofEpochMilli(messageTime + n * 1000L).toString(); }
    private Fixture fixture() throws Exception {
        return initialize(new Fixture(new SystemService()));
    }
    private Fixture initialize(Fixture f) throws Exception {
        f.account.setXmppConnection(new XmppConnection(f.account, f.service));
        java.lang.reflect.Field axolotl=Account.class.getDeclaredField("axolotlService");
        axolotl.setAccessible(true);
        axolotl.set(f.account,new eu.siacs.conversations.crypto.axolotl.AxolotlService(f.account,f.service));
        return f;
    }
    private void mamDisco(Fixture f, int index) {
        Iq result = new Iq(Iq.Type.RESULT);
        var info = result.addChild("query", Namespace.DISCO_INFO);
        info.addChild("feature").setAttribute("var", "urn:xmpp:mam:2");
        info.addChild("feature").setAttribute("var", Namespace.STANZA_IDS);
        f.service.iqs.get(index).accept(result);
    }
    private void removal(Fixture f, String code) {
        Presence p = new Presence(); p.setFrom(f.options().getSelf().getFullJid());
        p.setAttribute("type", "unavailable");
        var x = p.addChild("x", Namespace.MUC_USER);
        x.addChild("status").setAttribute("code", "110");
        x.addChild("status").setAttribute("code", code);
        f.parser.parseConferencePresence(p, f.account);
    }
    private void queue(Fixture f) {
        Message text = new Message(f.room, "queued historical text", Message.ENCRYPTION_NONE, Message.STATUS_WAITING);
        f.room.add(text);
        Message file = new Message(f.room, "https://files.example/file.pdf|123", Message.ENCRYPTION_NONE, Message.STATUS_WAITING);
        file.setType(Message.TYPE_FILE); f.room.add(file);
        Message audio = new Message(f.room, "https://files.example/audio.m4a|321", Message.ENCRYPTION_NONE, Message.STATUS_WAITING);
        audio.setType(Message.TYPE_FILE); f.room.add(audio);
    }
    private im.conversations.android.xmpp.model.stanza.Message live(Fixture f, int n) {
        var m = new im.conversations.android.xmpp.model.stanza.Message();
        m.setFrom(Jid.of("room@conference.example/peer")); m.setTo(f.account.getJid());
        m.setAttribute("type", "groupchat"); m.setId("m"+n); m.setBody("received "+n);
        m.addChild("stanza-id", "urn:xmpp:sid:0").setAttribute("by", f.room.getJid().asBareJid().toString()).setAttribute("id", "r"+n);
        m.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", stamp(n));
        return m;
    }
    private void archive(Fixture f, MessageParser parser, String queryId, int n) {
        var wrapper = new im.conversations.android.xmpp.model.stanza.Message();
        wrapper.setFrom(f.room.getJid().asBareJid()); wrapper.setTo(f.account.getJid());
        var result = wrapper.addChild("result", "urn:xmpp:mam:2");
        result.setAttribute("queryid", queryId); result.setAttribute("id", "r"+n);
        var forward = new im.conversations.android.xmpp.model.forward.Forwarded();
        result.addChild(forward);
        forward.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", stamp(n));
        forward.addChild(live(f,n)); parser.accept(wrapper);
    }
    @Test public void technicalRecoveryReceivesOverlapAndReleasesQueueOnceAsOneLifecycle() throws Exception {
        Fixture f = fixture(); SystemService s = (SystemService) f.service;
        f.service.password = "fixture-secret";
        Message seed = new Message(f.room,"existing history",Message.ENCRYPTION_NONE,Message.STATUS_RECEIVED);
        seed.setTime(System.currentTimeMillis()-86_400_000L);
        seed.setRoomStanzaId("seed"); seed.setServerMsgId("seed"); f.room.add(seed);
        f.service.joinMuc(f.room); mamDisco(f,0); f.selfPresence(); f.service.runCatchups();
        f.service.mamCallbacks.get(0).accept(Iq.TIMEOUT);
        assertTrue(f.room.hasMessagesLeftOnServer());
        removal(f,"332"); queue(f);
        assertTrue(s.outgoing.isEmpty());
        assertEquals(1, s.roomRecoveries.size());
        s.roomRecoveries.get(0).run();
        assertTrue(f.room.hasMessagesLeftOnServer()); // retry must not pretend history is complete
        mamDisco(f,1);
        assertEquals("fixture-secret", f.lastPresence().findChild("x",Namespace.MUC).findChildContent("password"));
        assertTrue(s.outgoing.isEmpty());
        f.selfPresence(); f.service.runCatchups();
        assertEquals(3,s.outgoing.size());
        f.selfPresence(); f.service.onMucJoinConfirmed(f.options()); f.service.runCatchups();
        assertEquals(3,s.outgoing.size());
        assertEquals(2,s.mamRequests.size());
        String q = s.mamRequests.get(1).findChild("query","urn:xmpp:mam:2").getAttribute("queryid");
        MessageParser parser = new MessageParser(s,f.account);
        for (int i=11;i<=20;i++) parser.accept(live(f,i));
        for (int i=1;i<=15;i++) archive(f,parser,q,i);
        Iq fin = new Iq(Iq.Type.RESULT); fin.addChild("fin","urn:xmpp:mam:2").setAttribute("complete","true");
        s.mamCallbacks.get(1).accept(fin);
        assertEquals(20,s.persisted.size());
        assertEquals(20,s.liveNotifications+s.backlogNotifications);
        Set<String> ids = new HashSet<>(); long previous = 0;
        for (Message m : f.room.snapshotMessages()) {
            if (m.getStatus()!=Message.STATUS_RECEIVED || "seed".equals(m.getRoomStanzaId())) continue;
            assertTrue(ids.add(m.getRoomStanzaId()));
            assertTrue(m.getTimeSent()>=previous); previous=m.getTimeSent();
        }
        assertEquals(20,ids.size());
    }
    @Test public void noMamRoomRestoresLiveAndOutgoingWithoutInventingArchive() throws Exception {
        Fixture f=fixture(); SystemService service=(SystemService)f.service;
        queue(f); f.join(); assertTrue(service.outgoing.isEmpty());
        f.selfPresence(); f.service.runCatchups();
        assertEquals(3,service.outgoing.size()); assertTrue(service.mamRequests.isEmpty());
        new MessageParser(service,f.account).accept(live(f,1));
        assertEquals(1,service.persisted.size());
        assertEquals(1,service.liveNotifications+service.backlogNotifications);
    }

    @Test public void finiteDiscoRecoveryStopsAtPasswordRejection() throws Exception {
        Fixture f = fixture(); f.service.password="outdated";
        f.service.joinMuc(f.room);
        f.service.iqs.get(0).accept(Iq.TIMEOUT); f.service.iqs.get(1).accept(Iq.TIMEOUT);
        assertEquals(1,f.service.roomRecoveries.size()); f.service.roomRecoveries.get(0).run();
        f.disco(2); f.reject(f.lastPresence(),"not-authorized");
        assertEquals(MucOptions.Error.PASSWORD_INCORRECT,f.options().getError());
        int requests=f.service.iqs.size(); f.service.roomRecoveries.get(0).run();
        f.service.joinMuc(f.room); assertEquals(requests,f.service.iqs.size());
        assertTrue(((SystemService)f.service).outgoing.isEmpty());
    }
    @Test public void exhaustedRoomBudgetDoesNotRestartItself() throws Exception {
        Fixture f=fixture(); f.service.joinMuc(f.room);
        int next=0;
        for(int round=0;round<4;round++) {
            f.service.iqs.get(next++).accept(Iq.TIMEOUT); f.service.iqs.get(next++).accept(Iq.TIMEOUT);
            if(round<3) f.service.roomRecoveries.get(round).run();
        }
        assertEquals(3,f.service.roomRecoveries.size()); assertEquals(8,f.service.iqs.size());
    }
    @Test public void recoveryNeverRevivesLeaveArchiveOrAnotherAccount() throws Exception {
        for(int action=0;action<3;action++) {
            Fixture f=fixture(); f.service.joinMuc(f.room);
            f.service.iqs.get(0).accept(Iq.TIMEOUT); f.service.iqs.get(1).accept(Iq.TIMEOUT);
            Runnable delayed=f.service.roomRecoveries.get(0);
            if(action==0) f.service.leaveMuc(f.room);
            if(action==1) f.room.setStatus(Conversation.STATUS_ARCHIVED);
            if(action==2) f.room.setAccount(new Account(Jid.of("other@example.test"),""));
            int count=f.service.iqs.size(); delayed.run(); assertEquals(count,f.service.iqs.size());
            assertTrue(((SystemService)f.service).outgoing.isEmpty());
        }
    }
    @Test public void accessRefusalAndKickCloseRecoveryAndKeepQueueHeld() throws Exception {
        for(String condition:List.of("registration-required","forbidden")) {
            Fixture f=fixture(); queue(f); f.join(); f.reject(f.lastPresence(),condition);
            assertTrue(f.service.roomRecoveries.isEmpty());
            int count=f.service.iqs.size(); f.service.joinMuc(f.room);
            assertEquals(count,f.service.iqs.size()); assertTrue(((SystemService)f.service).outgoing.isEmpty());
        }
        Fixture f=fixture(); f.join(); f.selfPresence(); removal(f,"307"); queue(f);
        int count=f.service.iqs.size(); f.service.joinMuc(f.room);
        assertEquals(count,f.service.iqs.size()); assertEquals(MucOptions.Error.KICKED,f.options().getError());
        assertTrue(((SystemService)f.service).outgoing.isEmpty());
    }
    private void seedA(Fixture f) {
        Message seed=new Message(f.room,"A",Message.ENCRYPTION_NONE,Message.STATUS_RECEIVED);
        seed.setTime(messageTime); seed.setServerMsgId("A"); seed.setRoomStanzaId("A"); f.room.add(seed);
    }
    private Conversation privateConversation(Fixture f) {
        Conversation pm=new Conversation("peer",f.account,f.room.getJid().asBareJid(),Conversation.MODE_MULTI,null);
        pm.setNextCounterpart(Jid.of("room@conference.example/peer")); pm.setParentConversation(f.room);
        f.service.extraRooms.add(pm); return pm;
    }
    private void availablePeer(Fixture f) {
        MucOptions.User peer=new MucOptions.User(f.options(),Jid.of("room@conference.example/peer"));
        peer.setRole("participant"); f.options().updateUser(peer);
    }
    private Message waitingPm(Conversation pm) {
        Message m=new Message(pm,"queued private historical text",Message.ENCRYPTION_NONE,Message.STATUS_WAITING);
        m.setType(Message.TYPE_PRIVATE); m.setCounterpart(pm.getNextCounterpart()); pm.add(m); return m;
    }
    private void assertAnchorA(SystemService s,int index) {
        var q=s.mamRequests.get(index).findChild("query","urn:xmpp:mam:2");
        assertEquals("A",q.findChild("set","http://jabber.org/protocol/rsm").findChildContent("after"));
    }
    @Test public void liveBeforeDeferredCatchupKeepsAnchorAndPmReleaseInTheSameLifecycle() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f,0); availablePeer(f);
        Conversation pm=privateConversation(f); Message queued=waitingPm(pm);
        Message file=new Message(pm,"https://files.example/private.pdf|123",Message.ENCRYPTION_NONE,Message.STATUS_WAITING);
        file.setType(Message.TYPE_PRIVATE_FILE); file.setCounterpart(pm.getNextCounterpart()); pm.add(file);
        f.service.resendMessage(queued,false); assertTrue(s.outgoing.isEmpty());
        f.selfPresence();
        MessageParser parser=new MessageParser(s,f.account);
        parser.accept(live(f,4)); // D arrives BEFORE deferred catchup, while B/C are still missing
        f.selfPresence(); // live self update advances epoch but must retain this membership generation
        assertTrue(s.outgoing.isEmpty());
        f.service.runCatchups();
        assertEquals(2,s.outgoing.size());
        assertEquals(pm.getNextCounterpart(),s.outgoing.get(0).getTo());
        assertEquals("chat",s.outgoing.get(0).getAttribute("type"));
        assertAnchorA(s,0);
        String q=s.mamRequests.get(0).findChild("query","urn:xmpp:mam:2").getAttribute("queryid");
        archive(f,parser,q,2); archive(f,parser,q,3); archive(f,parser,q,4);
        Iq fin=new Iq(Iq.Type.RESULT); fin.addChild("fin","urn:xmpp:mam:2").setAttribute("complete","true");
        s.mamCallbacks.get(0).accept(fin); f.service.runCatchups();
        assertEquals(3,s.persisted.size()); // B,C,D; live/archive D is persisted exactly once
        Set<String> ids=new HashSet<>();
        for(Message m:f.room.snapshotMessages()) if(!"A".equals(m.getRoomStanzaId())) assertTrue(ids.add(m.getRoomStanzaId()));
        assertEquals(Set.of("r2","r3","r4"),ids);
        f.service.onMucJoinConfirmed(f.options()); f.service.runCatchups();
        assertEquals(2,s.outgoing.size()); assertEquals(1,s.mamRequests.size());
    }
    @Test public void oldInFlightMamTimeoutResumesNewCatchupFromFrozenAnchor() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f,0); f.selfPresence();
        var old=f.service.getMessageArchiveService().query(f.room,new eu.siacs.conversations.xmpp.mam.MamReference(0),System.currentTimeMillis(),false);
        assertNotNull(old); f.selfPresence();
        new MessageParser(s,f.account).accept(live(f,4)); f.service.runCatchups();
        assertEquals(1,s.mamRequests.size());
        s.mamCallbacks.get(0).accept(Iq.TIMEOUT); f.service.runCatchups();
        assertEquals(2,s.mamRequests.size()); assertAnchorA(s,1);
        f.service.runCatchups(); assertEquals(2,s.mamRequests.size());
    }
    @Test public void oldCompletedQueryCannotMarkHistoryCompleteWhileNewCatchupIsPending() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f,0); f.selfPresence();
        f.service.getMessageArchiveService().query(f.room,new eu.siacs.conversations.xmpp.mam.MamReference(0),System.currentTimeMillis(),false);
        f.selfPresence(); f.service.runCatchups();
        Iq fin=new Iq(Iq.Type.RESULT); fin.addChild("fin","urn:xmpp:mam:2").setAttribute("complete","true");
        s.mamCallbacks.get(0).accept(fin);
        assertTrue(f.room.hasMessagesLeftOnServer());
        f.service.runCatchups(); assertEquals(2,s.mamRequests.size()); assertAnchorA(s,1);
    }

    @Test public void failedCatchupRetainsFloorUntilLaterRecoveryCompletes() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f,0); f.selfPresence(); f.service.runCatchups();
        MessageParser parser=new MessageParser(s,f.account); parser.accept(live(f,4));
        s.mamCallbacks.get(0).accept(Iq.TIMEOUT); f.service.runCatchups();
        removal(f,"332"); f.service.roomRecoveries.get(0).run(); mamDisco(f,1);
        f.selfPresence(); f.service.runCatchups(); assertAnchorA(s,1);
        String q=s.mamRequests.get(1).findChild("query","urn:xmpp:mam:2").getAttribute("queryid");
        archive(f,parser,q,2); archive(f,parser,q,3); archive(f,parser,q,4);
        Iq fin=new Iq(Iq.Type.RESULT); fin.addChild("fin","urn:xmpp:mam:2").setAttribute("complete","true");
        s.mamCallbacks.get(1).accept(fin); f.service.runCatchups();
        assertEquals(3,s.persisted.size());
        removal(f,"332"); f.service.roomRecoveries.get(1).run(); mamDisco(f,2);
        f.selfPresence(); f.service.runCatchups();
        var last=s.mamRequests.get(2).findChild("query","urn:xmpp:mam:2");
        assertEquals("r4",last.findChild("set","http://jabber.org/protocol/rsm").findChildContent("after"));
    }

    @Test public void partialMamPageFailureDoesNotAdvanceTheRecoveryFloor() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f,0); f.selfPresence(); f.service.runCatchups();
        MessageParser parser=new MessageParser(s,f.account);
        String q=s.mamRequests.get(0).findChild("query","urn:xmpp:mam:2").getAttribute("queryid");
        archive(f,parser,q,2);
        Iq page=new Iq(Iq.Type.RESULT);
        var fin=page.addChild("fin","urn:xmpp:mam:2"); fin.setAttribute("complete","false");
        var set=fin.addChild("set","http://jabber.org/protocol/rsm");
        set.addChild("first").setContent("r2"); set.addChild("last").setContent("r2");
        s.mamCallbacks.get(0).accept(page); assertEquals(2,s.mamRequests.size());
        s.mamCallbacks.get(1).accept(Iq.TIMEOUT); f.service.runCatchups();
        assertTrue(f.room.hasMessagesLeftOnServer());
        removal(f,"332"); f.service.roomRecoveries.get(0).run(); mamDisco(f,1);
        f.selfPresence(); f.service.runCatchups();
        assertEquals(3,s.mamRequests.size()); assertAnchorA(s,2);
    }

    @Test public void leavingWhileOldMamIsInFlightDiscardsPendingFollowup() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f,0); f.selfPresence();
        f.service.getMessageArchiveService().query(f.room,new eu.siacs.conversations.xmpp.mam.MamReference(0),System.currentTimeMillis(),false);
        f.selfPresence(); f.service.runCatchups(); f.service.leaveMuc(f.room);
        s.mamCallbacks.get(0).accept(Iq.TIMEOUT); f.service.runCatchups();
        assertEquals(1,s.mamRequests.size()); assertTrue(s.outgoing.isEmpty());
    }
    @Test public void deferredConfirmationCannotSendOrQueryAfterAccountReplacement() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f,0); availablePeer(f);
        Conversation pm=privateConversation(f); waitingPm(pm); f.selfPresence();
        Account replacement=new Account(Jid.of("other@example.test/device"),"");
        replacement.setStatus(Account.State.ONLINE); f.room.setAccount(replacement);
        f.service.runCatchups();
        assertTrue(s.outgoing.isEmpty()); assertTrue(s.mamRequests.isEmpty());
    }

    @Test public void parentSelfPingSuccessReleasesPrivateQueueWithoutAccountOnlineEvent() throws Exception {
        Fixture f=fixture(); SystemService s=(SystemService)f.service;
        f.join(); availablePeer(f); f.selfPresence(); f.service.runCatchups();
        Conversation pm=privateConversation(f); removal(f,"333"); Message m=waitingPm(pm);
        f.service.resendMessage(m,false); assertTrue(s.outgoing.isEmpty());
        f.service.iqs.get(1).accept(new Iq(Iq.Type.RESULT)); f.service.runCatchups();
        assertEquals(Account.State.ONLINE,f.account.getStatus());
        assertEquals(1,s.outgoing.size()); assertEquals(pm.getNextCounterpart(),s.outgoing.get(0).getTo());
        f.service.onMucJoinConfirmed(f.options()); f.service.runCatchups(); assertEquals(1,s.outgoing.size());
        assertTrue(f.service.secretHydrations.stream().allMatch(c->c==f.room));
    }
    @Test public void privateQueueStaysHeldForUnavailableOrChangedRecipientAndArchivedPm() throws Exception {
        for(int reason=0;reason<7;reason++) {
            Fixture f=fixture(); SystemService s=(SystemService)f.service; f.join();
            Conversation pm=privateConversation(f); Message m=waitingPm(pm);
            if(reason!=0) availablePeer(f);
            if(reason==1) m.setCounterpart(Jid.of("room@conference.example/old-peer"));
            if(reason==2) pm.setStatus(Conversation.STATUS_ARCHIVED);
            if(reason==3) f.room.setMucExplicitlyLeft(true); // explicit leave belongs to the parent room
            if(reason==4) m.setType(Message.TYPE_TEXT);
            if(reason==5) {
                Iq disco=new Iq(Iq.Type.RESULT);
                var x=disco.addChild("query",Namespace.DISCO_INFO).addChild("x","jabber:x:data");
                x.setAttribute("type","result");
                x.addChild("field").setAttribute("var","FORM_TYPE").addChild("value").setContent("http://jabber.org/protocol/muc#roominfo");
                x.addChild("field").setAttribute("var","muc#roomconfig_allowpm").addChild("value").setContent("none");
                f.options().updateConfiguration(new eu.siacs.conversations.entities.ServiceDiscoveryResult(disco));
                assertFalse(f.options().allowPm());
            }
            if(reason==6) {
                MucOptions.User old=new MucOptions.User(f.options(),Jid.of("room@conference.example/old-peer"));
                old.setRole("participant"); f.options().updateUser(old); m.setCounterpart(old.getFullJid());
            }
            f.selfPresence(); f.service.runCatchups();
            assertTrue("reason=" + reason, s.outgoing.isEmpty());
            assertEquals("reason=" + reason, Message.STATUS_WAITING, m.getStatus());
        }
    }
    @Test public void membershipDeniedOrTimedOutNeverReleasesPrivateQueue() throws Exception {
        for(String condition:List.of("forbidden","registration-required","not-authorized","timeout","leave")) {
            Fixture f=fixture(); SystemService s=(SystemService)f.service; f.join(); availablePeer(f);
            Conversation pm=privateConversation(f); Message m=waitingPm(pm);
            if(condition.equals("timeout")) f.service.timeoutMucJoin(f.room,f.options());
            else if(condition.equals("leave")) f.service.leaveMuc(f.room);
            else f.reject(f.lastPresence(),condition);
            f.service.onMucJoinConfirmed(f.options()); f.service.runCatchups();
            assertTrue(s.outgoing.isEmpty()); assertEquals(Message.STATUS_WAITING,m.getStatus());
        }
    }


    private Iq completeMam() {
        Iq fin = new Iq(Iq.Type.RESULT);
        fin.addChild("fin", "urn:xmpp:mam:2").setAttribute("complete", "true");
        return fin;
    }
    private Presence peerPresence(Fixture f, String nick, String occupant) {
        Presence p = new Presence(); p.setFrom(f.room.getJid().asBareJid().withResource(nick));
        p.addChild("x", Namespace.MUC_USER).addChild("item")
                .setAttribute("affiliation", "member").setAttribute("role", "participant");
        if (occupant != null) p.addChild("occupant-id", "urn:xmpp:occupant-id:0").setAttribute("id", occupant);
        return p;
    }
    private Conversation restoreConversation(android.content.ContentValues row, Account account) {
        Conversation restored = new Conversation(row.getAsString(Conversation.UUID), row.getAsString(Conversation.NAME),
                row.getAsString(Conversation.CONTACT), row.getAsString(Conversation.ACCOUNT),
                Jid.of(row.getAsString(Conversation.CONTACTJID)), row.getAsLong(Conversation.CREATED),
                row.getAsInteger(Conversation.STATUS), row.getAsInteger(Conversation.MODE),
                row.getAsString(Conversation.ATTRIBUTES), null);
        restored.setAccount(account);
        return restored;
    }
    @Test public void processRestartRestoresPersistedFloorInsteadOfNewLiveMessage() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            Fixture first = fixture(); seedA(first);
            final android.content.ContentValues[] stored = new android.content.ContentValues[1];
            first.service.databaseBackend = new eu.siacs.conversations.persistance.DatabaseBackendStub() {
                @Override public void updateConversation(Conversation conversation) {
                    stored[0] = new android.content.ContentValues(conversation.getContentValues());
                }
            };
            first.service.joinMuc(first.room); mamDisco(first, 0); first.selfPresence();
            assertNotNull("floor must be committed before a deferred worker", stored[0]);
            assertEquals("A", restoreConversation(stored[0], first.account).getMucRecoveryAnchor().getReference());
            new MessageParser(first.service, first.account).accept(live(first, 4));
            assertEquals("r4", first.room.getLastMessageTransmitted().getReference());
            first.service.runCatchups();
            if (timeout) { first.service.mamCallbacks.get(0).accept(Iq.TIMEOUT); first.service.runCatchups(); }
            // Fresh Conversation and Service: no old maps, callbacks, MucOptions or attempts survive.
            Conversation restored = restoreConversation(stored[0], first.account);
            Message d = new Message(restored, "D", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
            d.setTime(messageTime + 4_000); d.setServerMsgId("r4"); d.setRoomStanzaId("r4"); restored.add(d);
            Fixture second = initialize(new Fixture(new SystemService(), first.account, restored));
            SystemService service = (SystemService) second.service;
            second.service.joinMuc(second.room); mamDisco(second, 0); second.selfPresence(); second.service.runCatchups();
            assertAnchorA(service, 0);
            String queryId = service.mamRequests.get(0).findChild("query", "urn:xmpp:mam:2").getAttribute("queryid");
            MessageParser parser = new MessageParser(service, second.account);
            archive(second, parser, queryId, 2); archive(second, parser, queryId, 3);
            service.mamCallbacks.get(0).accept(completeMam()); service.runCatchups();
            assertEquals(3, second.room.countMessages());
            assertNull(second.room.getMucRecoveryAnchor());
        }
    }
    @Test public void splitCatchupKeepsFloorUnlessBothSerialRangesComplete() throws Exception {
        for (boolean reverseFirst : new boolean[]{true}) {
            for (int reverseOutcome = 0; reverseOutcome < 3; reverseOutcome++) {
                final boolean reverseSuccess = reverseOutcome == 0;
                Iq reverseError = new Iq(Iq.Type.ERROR);
                reverseError.addChild("error").addChild("service-unavailable", Namespace.STANZAS);
                final Iq reverseResult = reverseSuccess ? completeMam() : reverseOutcome == 1 ? Iq.TIMEOUT : reverseError;
                Fixture f = fixture(); SystemService s = (SystemService) f.service;
                Message a = new Message(f.room, "old A", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
                a.setTime(System.currentTimeMillis() - 8 * 86_400_000L);
                a.setServerMsgId("A"); f.room.add(a);
                f.service.joinMuc(f.room); mamDisco(f, 0); f.selfPresence(); f.service.runCatchups();
                assertEquals(1, s.mamRequests.size());
                int first = reverseFirst ? 0 : 1, last = reverseFirst ? 1 : 0;
                s.mamCallbacks.get(first).accept(first == 0 ? reverseResult : completeMam());
                s.runCatchups();
                assertEquals(2, s.mamRequests.size());
                assertNotNull(f.room.getMucRecoveryAnchor());
                assertEquals(XmppConnectionService.MucHistoryRecoveryState.SYNCING, s.getMucHistoryRecoveryState(f.room));
                s.mamCallbacks.get(last).accept(last == 0 ? reverseResult : completeMam());
                s.runCatchups();
                if (reverseSuccess) {
                    assertNull(f.room.getMucRecoveryAnchor());
                    assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, s.getMucHistoryRecoveryState(f.room));
                } else {
                    assertEquals("A", f.room.getMucRecoveryAnchor().getReference());
                    assertTrue(f.room.hasMessagesLeftOnServer());
                    assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE, s.getMucHistoryRecoveryState(f.room));
                    new MessageParser(s, f.account).accept(live(f, 4));
                    removal(f, "332"); s.roomRecoveries.get(0).run(); mamDisco(f, 1); f.selfPresence(); s.runCatchups();
                    assertEquals(3, s.mamRequests.size());
                    var form = s.mamRequests.get(2).findChild("query", "urn:xmpp:mam:2").findChild("x", "jabber:x:data");
                    // Read the actual form start; reverse uses timestamp, not RSM after=A.
                    String actualStart = null;
                    for (var field : form.getChildren()) if ("start".equals(field.getAttribute("var"))) actualStart = field.findChildContent("value");
                    assertNotNull(actualStart);
                    assertEquals(a.getTimeSent() / 1000, java.time.Instant.parse(actualStart).toEpochMilli() / 1000);
                }
            }
        }
    }
    @Test public void explicitHistoryRetryUsesFloorWithoutRejoiningOrReleasingQueueAgain() throws Exception {
        Fixture f = fixture(); SystemService s = (SystemService) f.service; seedA(f);
        f.service.joinMuc(f.room); mamDisco(f, 0); f.selfPresence(); s.runCatchups();
        s.mamCallbacks.get(0).accept(Iq.TIMEOUT); s.runCatchups();
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE, s.getMucHistoryRecoveryState(f.room));
        int presences = s.sent.size(), joins = s.iqs.size(); long generation = f.options().getJoinAttempt().getMembershipGeneration();
        assertTrue(s.retryMucHistoryRecovery(f.room)); assertFalse(s.retryMucHistoryRecovery(f.room)); s.runCatchups();
        assertAnchorA(s, 1); assertEquals(presences, s.sent.size()); assertEquals(joins, s.iqs.size());
        assertEquals(generation, f.options().getJoinAttempt().getMembershipGeneration());
        s.mamCallbacks.get(1).accept(completeMam()); s.runCatchups();
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, s.getMucHistoryRecoveryState(f.room));
        assertFalse(s.retryMucHistoryRecovery(f.room));
    }
    @Test public void deniedMembershipHidesArchiveStatusAndCannotRetryHistory() throws Exception {
        Fixture f = fixture(); seedA(f); f.service.joinMuc(f.room); mamDisco(f, 0); f.selfPresence(); f.service.runCatchups();
        removal(f, "301");
        assertEquals(MucOptions.Error.BANNED, f.options().getError());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, f.service.getMucHistoryRecoveryState(f.room));
        assertFalse(f.service.retryMucHistoryRecovery(f.room));
    }
    @Test public void availableRecipientResumesWaitingPrivateQueueExactlyOnceWithoutAnotherJoin() throws Exception {
        Fixture f = fixture(); SystemService s = (SystemService) f.service; f.join();
        Conversation pm = privateConversation(f); Message queued = waitingPm(pm);
        f.selfPresence(); s.runCatchups(); assertEquals(Message.STATUS_WAITING, queued.getStatus());
        assertTrue(s.outgoing.isEmpty()); int presences = s.sent.size(), joins = s.iqs.size();
        Presence peer = peerPresence(f, "peer", null);
        f.parser.parseConferencePresence(peer, f.account); f.parser.parseConferencePresence(peer, f.account);
        s.runCatchups();
        assertEquals(1, s.outgoing.size()); assertEquals(pm.getNextCounterpart(), s.outgoing.get(0).getTo());
        assertEquals(Message.STATUS_SEND, queued.getStatus());
        f.parser.parseConferencePresence(peer, f.account); s.runCatchups(); assertEquals(1, s.outgoing.size());
        assertEquals(presences, s.sent.size()); assertEquals(joins, s.iqs.size());
    }
    @Test public void presenceCannotReleasePrivateQueueAfterLeaveDenialArchiveOrToDifferentNick() throws Exception {
        for (int reason = 0; reason < 7; reason++) {
            Fixture f = fixture(); SystemService s = (SystemService) f.service; f.join();
            Conversation pm = privateConversation(f); Message queued = waitingPm(pm);
            f.selfPresence(); s.runCatchups();
            Presence peer = peerPresence(f, reason == 0 ? "other" : "peer", null);
            if (reason == 1) { f.parser.parseConferencePresence(peer, f.account); f.service.leaveMuc(f.room); }
            if (reason == 2) pm.setStatus(Conversation.STATUS_ARCHIVED);
            if (reason == 3) removal(f, "301");
            if (reason == 4) queued.setCounterpart(Jid.of("room@conference.example/old-peer"));
            if (reason == 5) peer.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", stamp(1));
            if (reason == 6) {
                Iq disco = new Iq(Iq.Type.RESULT);
                var form = disco.addChild("query", Namespace.DISCO_INFO).addChild("x", "jabber:x:data");
                form.addChild("field").setAttribute("var", "FORM_TYPE").addChild("value").setContent("http://jabber.org/protocol/muc#roominfo");
                form.addChild("field").setAttribute("var", "muc#roomconfig_allowpm").addChild("value").setContent("none");
                f.options().updateConfiguration(new eu.siacs.conversations.entities.ServiceDiscoveryResult(disco));
            }
            f.parser.parseConferencePresence(peer, f.account); s.runCatchups();
            assertTrue("reason " + reason, s.outgoing.isEmpty()); assertEquals(Message.STATUS_WAITING, queued.getStatus());
        }
    }
    @Test public void knownRecipientIdentityChangeFailsInsteadOfSendingOrWaitingForever() throws Exception {
        Fixture f = fixture(); SystemService s = (SystemService) f.service; f.join();
        Conversation pm = privateConversation(f); Message queued = waitingPm(pm);
        queued.setOccupantId("original-occupant"); f.selfPresence(); s.runCatchups();
        f.parser.parseConferencePresence(peerPresence(f, "peer", "replacement-occupant"), f.account); s.runCatchups();
        assertTrue(s.outgoing.isEmpty()); assertEquals(Message.STATUS_SEND_FAILED, queued.getStatus());
        assertEquals(Message.ERROR_MESSAGE_MUC_RECIPIENT_UNVERIFIED, queued.getErrorMessage());
    }

    @Test public void explicitHistoryClearBoundsTheNextRetryDespiteAnOlderMemoryFloor() throws Exception {
        Fixture f = fixture(); seedA(f); f.service.joinMuc(f.room); mamDisco(f, 0);
        f.selfPresence(); f.service.runCatchups();
        f.service.mamCallbacks.get(0).accept(Iq.TIMEOUT); f.service.runCatchups();
        f.room.setLastClearHistory(messageTime + 5_000, "clear");
        assertTrue(f.service.retryMucHistoryRecovery(f.room)); f.service.runCatchups();
        var query = f.service.mamRequests.get(1).findChild("query", "urn:xmpp:mam:2");
        assertEquals("clear", query.findChild("set", "http://jabber.org/protocol/rsm").findChildContent("after"));
    }

    @Test public void duplicateConfirmationAfterCompleteCannotRecreateAnIncompleteFloor() throws Exception {
        Fixture f = fixture(); seedA(f); f.service.joinMuc(f.room); mamDisco(f, 0);
        f.selfPresence(); f.service.runCatchups();
        f.service.mamCallbacks.get(0).accept(completeMam()); f.service.runCatchups();
        assertNull(f.room.getMucRecoveryAnchor());
        f.service.onMucJoinConfirmed(f.options()); f.selfPresence(); f.service.runCatchups();
        assertNull(f.room.getMucRecoveryAnchor()); assertEquals(1, f.service.mamRequests.size());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, f.service.getMucHistoryRecoveryState(f.room));
    }
    @Test public void matchingKnownOccupantResumesButReplacedRealJidFailsClosed() throws Exception {
        for (boolean changedRealJid : new boolean[]{false, true}) {
            Fixture f = fixture(); SystemService s = (SystemService) f.service; f.join();
            Conversation pm = privateConversation(f); Message queued = waitingPm(pm);
            queued.setTrueCounterpart(Jid.of("original@example.test"));
            f.selfPresence(); s.runCatchups();
            // Populate trusted roster evidence directly; event handler still uses real resend gate.
            MucOptions.User peer = new MucOptions.User(f.options(), pm.getNextCounterpart());
            peer.setRole("participant"); peer.setRealJid(Jid.of(changedRealJid ? "replacement@example.test" : "original@example.test"));
            peer.setOccupantId("same-occupant"); queued.setOccupantId("same-occupant"); f.options().updateUser(peer);
            s.onMucPrivateRecipientAvailable(f.options(), pm.getNextCounterpart()); s.runCatchups();
            assertEquals(changedRealJid ? 0 : 1, s.outgoing.size());
            assertEquals(changedRealJid ? Message.STATUS_SEND_FAILED : Message.STATUS_SEND, queued.getStatus());
        }
    }

    @Test public void simulatedPersistenceAcknowledgesOnlyCommittedConversationOwnedRows() throws Exception {
        Fixture f = fixture(); SystemService s = (SystemService) f.service;
        Message live = new Message(f.room, "live r4", Message.ENCRYPTION_NONE);
        live.setServerMsgId("r4");
        assertFalse(s.isMamMessagePersisted(live));
        assertFalse(s.persistIncomingMessage(null));
        assertTrue(s.committedMessageConversations.isEmpty());
        assertTrue(s.persisted.isEmpty());
        assertTrue(s.persistIncomingMessage(live));
        assertTrue(s.isMamMessagePersisted(live));
        Message replay = new Message(f.room, "replay r4", Message.ENCRYPTION_NONE);
        replay.setServerMsgId("r4");
        assertFalse("an archive ID match is not a committed UUID", s.isMamMessagePersisted(replay));
        replay.setUuid(live.getUuid());
        assertTrue(s.isMamMessagePersisted(replay));
        Conversation other = new Conversation("other", f.account, Jid.of("other@conference.example"), Conversation.MODE_MULTI, null);
        Message foreign = new Message(other, "same UUID in another conversation", Message.ENCRYPTION_NONE);
        foreign.setUuid(live.getUuid());
        assertFalse(s.isMamMessagePersisted(foreign));
        assertFalse("a simulated failed cross-conversation write must not change the commit", s.persistIncomingMessage(foreign));
        assertTrue(s.isMamMessagePersisted(live));
        assertEquals(1, s.persisted.size());
        assertFalse(s.isMamMessagePersisted(null));
    }

    static final class SystemService extends TestService {
        final List<Message> persisted=new ArrayList<>();
        // Explicit simulated commits, independent of resident objects and notification recording.
        // Snapshot the conversation UUID so a later mutation cannot move a committed row.
        final Map<String, String> committedMessageConversations=new HashMap<>();
        final List<im.conversations.android.xmpp.model.stanza.Message> outgoing=new ArrayList<>();
        int liveNotifications,backlogNotifications;
        SecureMessageTextRepository repository;
        @Override public Account findAccountByUuid(String uuid) { return room.getAccount().getUuid().equals(uuid)?room.getAccount():null; }
        @Override public SecureMessageTextRepository getSecureMessageTextRepository() {
            if(repository==null) {
                SecureMessagePayloadCoordinator coordinator=(SecureMessagePayloadCoordinator)Proxy.newProxyInstance(
                    SecureMessagePayloadCoordinator.class.getClassLoader(),new Class<?>[]{SecureMessagePayloadCoordinator.class},
                    (proxy,method,args)->{throw new AssertionError("historical resend must not invoke payload publication");});
                repository=new SecureMessageTextRepository(coordinator,databaseBackend);
            }
            return repository;
        }
        @Override public boolean persistIncomingMessage(Message m) {
            if (m == null || m.getUuid() == null || m.getConversationUuid() == null) return false;
            final String committedConversation = committedMessageConversations.get(m.getUuid());
            if (committedConversation != null && !committedConversation.equals(m.getConversationUuid())) return false;
            persisted.add(m);
            committedMessageConversations.put(m.getUuid(), m.getConversationUuid());
            return true;
        }
        @Override public boolean isMamMessagePersisted(Message m) {
            return m != null && m.getUuid() != null && m.getConversationUuid() != null
                    && m.getConversationUuid().equals(committedMessageConversations.get(m.getUuid()));
        }
        @Override public Conversation find(Account account,Jid jid,Jid counterpart) { return room; }
        @Override public boolean checkIsArchived(Account account,Jid jid,Jid counterpart) { return false; }
        @Override public Conversation findOrCreateConversation(Account a,Jid j,MessageArchiveService.Query q,boolean muc,boolean join,boolean async,Jid counterpart) { return room; }
        @Override public boolean confirmMessages() { return false; }
        @Override public boolean sendChatStates() { return false; }
        @Override public void markMessage(Message m,int status) { m.setStatus(status); }
        @Override public void markMessage(Message m,int status,String error) { m.setStatus(status); m.setErrorMessage(error); }
        @Override public void sendMessagePacket(Account account,im.conversations.android.xmpp.model.stanza.Message packet) {
            assertFalse("outbound transport must run outside the root monitor", Thread.holdsLock(room));
            outgoing.add(packet);
        }
        @Override public NotificationService getNotificationService() {
            return new NotificationService(this) {
                @Override void updateErrorNotification() {}
                @Override public void push(Message m) { liveNotifications++; }
                @Override public void pushFromBacklog(Message m) { backlogNotifications++; }
                @Override public void finishBacklog(boolean notify,Account account) {}
            };
        }
    }
}
