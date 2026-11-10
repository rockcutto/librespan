package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import static eu.siacs.conversations.services.MamTestHarness.*;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamP0PersistenceRegressionTest {
    @Test public void failedLastResultCannotClearAnchorAndFreshRetryCanComplete() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 3;
        for (int n = 1; n <= 3; n++) h.result(0, n);
        h.finish(0, true, "f", "last"); assertNotNull(h.f.room.getMucRecoveryAnchor());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE,
                h.service.getMucHistoryRecoveryState(h.f.room));
        h.service.failureAt = -1;
        assertTrue(h.service.retryMucHistoryRecovery(h.f.room)); h.drain();
        assertEquals("floor:opaque:z", h.cursor(1, "after"));
        for (int n = 1; n <= 3; n++) h.result(1, n);
        h.finish(1, true, "f", "last");
        assertNull(h.f.room.getMucRecoveryAnchor()); assertEquals(3, h.service.rows.size());
        assertEquals(3, h.service.writes); // two prior successes were not republished
    }
    @Test public void failedPersistSurvivesSerializedRestart() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 2;
        h.result(0, 1); h.result(0, 2); h.finish(0, true, "f", "l");
        var restarted = new MamTestHarness(h.service.saved, h.f.account, h.service.rows);
        restarted.join(); assertEquals("floor:opaque:z", restarted.cursor(0, "after"));
        restarted.result(0, 1); restarted.result(0, 2); restarted.finish(0, true, "f", "l");
        assertNull(restarted.f.room.getMucRecoveryAnchor()); assertEquals(1, restarted.service.writes);
    }
    @Test public void reconnectGetsNewChainAndOldFinCannotClearIt() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1;
        h.result(0, 1); h.finish(0, true, "f", "l");
        h.f.options().setOffline(); h.service.failureAt = -1; h.join();
        h.service.mamCallbacks.get(0).accept(fin(true, "f", "l")); h.drain();
        assertNotNull(h.f.room.getMucRecoveryAnchor());
        h.result(1, 1); h.finish(1, true, "f", "l"); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @SuppressWarnings("unchecked")
    @Test public void concurrentIndependentQueryInSameServiceDoesNotInheritFailure() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1; h.result(0, 1);
        // V3 deliberately serializes MUC ranges for the same room. Use an independent 1:1
        // conversation to verify that a failed MUC persistence chain cannot poison another
        // query owned by the same MessageArchiveService.
        var discovery = new im.conversations.android.xmpp.model.stanza.Iq(
                im.conversations.android.xmpp.model.stanza.Iq.Type.RESULT);
        var info = discovery.addChild("query", eu.siacs.conversations.xml.Namespace.DISCO_INFO);
        info.addChild("feature").setAttribute("var", MAM);
        var discoField = eu.siacs.conversations.xmpp.XmppConnection.class.getDeclaredField("disco");
        discoField.setAccessible(true);
        var accountDisco = (java.util.Map<eu.siacs.conversations.xmpp.Jid,
                eu.siacs.conversations.entities.ServiceDiscoveryResult>)
                discoField.get(h.f.account.getXmppConnection());
        accountDisco.put(h.f.account.getJid().asBareJid(),
                new eu.siacs.conversations.entities.ServiceDiscoveryResult(discovery));
        var single = new eu.siacs.conversations.entities.Conversation(
                "peer", h.f.account, eu.siacs.conversations.xmpp.Jid.of("peer@example.test"),
                eu.siacs.conversations.entities.Conversation.MODE_SINGLE, null);
        var archive = h.service.getMessageArchiveService();
        var independent = archive.query(single, new MamReference(0), h.floorTime, false);
        assertNotNull(independent);
        assertEquals(2, h.service.mamRequests.size());
        h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
        h.finish(1, true, "f", "l");
        assertTrue(independent.persistence.finishPage());
        assertFalse(archive.queryInProgress(single));
        assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void pendingAndUnavailableDuplicatesNeverClearFloor() throws Exception {
        for (var mode : new SecureMessagePayloadMode[]{SecureMessagePayloadMode.PENDING, SecureMessagePayloadMode.UNAVAILABLE}) {
            var h = new MamTestHarness(); h.join();
            var row = new Message(h.f.room, "", 0); row.setServerMsgId("archive-1"); row.setSecureMessagePayloadMode(mode);
            h.service.rows.put("archive-1", row); h.result(0, 1); h.finish(0, true, "f", "l");
            assertNotNull(h.f.room.getMucRecoveryAnchor()); assertEquals(0, h.service.writes);
        }
    }
    @Test public void duplicateMetadataUpdateFailureIsNotErasedByValidPayload() throws Exception {
        var h = new MamTestHarness(); h.join(); h.result(0, 1);
        Message resident = h.service.rows.get("archive-1"); resident.setRoomStanzaId(null);
        h.service.failMessageUpdate = true;
        h.result(0, 1); h.finish(0, true, "f", "l");
        assertTrue(h.service.updates > 0); assertNotNull(h.f.room.getMucRecoveryAnchor());
        assertNull(resident.getRoomStanzaId());
    }
    @Test public void zeroRowAnchorWriteRestoresMemoryAndKeepsSavedFloor() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.zeroConversationWrite = true;
        h.service.mamCallbacks.get(0).accept(fin(true, "f", "l"));
        // The service contains the failed checkpoint-clear write and reports it through the
        // recovery result; it must not crash the catchup dispatcher.
        h.drain();
        assertEquals("floor:opaque:z", h.f.room.getMucRecoveryAnchor().getReference());
        assertEquals("floor:opaque:z",
                restore(h.service.saved, h.f.account).getMucRecoveryAnchor().getReference());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE,
                h.service.getMucHistoryRecoveryState(h.f.room));
    }
    @Test public void finDuringUnresolvedRequiredWriteMustKeepAnchor() throws Exception {
        var h = new MamTestHarness(); h.join(); var write = h.query(0).persistence.beginResult();
        h.finish(0, true, "f", "l");
        write.complete(true); write.close(); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void failedOldSplitRangeCannotBeHiddenByRecentComplete() throws Exception {
        var h = new MamTestHarness();
        h.f.room.setMucRecoveryAnchor(new MamReference(System.currentTimeMillis() - 8 * 86_400_000L, "old"));
        h.join();
        assertEquals("Only the older split range is sent first", 1, h.service.mamRequests.size());
        h.service.failureAt = 1; h.result(0, 1);
        // V3 serializes the two ranges: fail the old range, then let the recent range complete.
        h.finish(0, true, "old-first", "old-last");
        assertEquals("The recent range starts only after the old range terminates",
                2, h.service.mamRequests.size());
        h.finish(1, true, "recent-first", "recent-last");
        assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
}
