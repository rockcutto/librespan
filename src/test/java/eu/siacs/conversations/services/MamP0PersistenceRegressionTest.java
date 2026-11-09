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
    @Test public void concurrentIndependentQueryInSameServiceDoesNotInheritFailure() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1; h.result(0, 1);
        var independent = h.service.getMessageArchiveService().query(
                h.f.room, new MamReference(0), h.floorTime, false);
        assertNotNull(independent);
        h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
        h.finish(1, true, "f", "l");
        assertTrue(independent.persistence.finishPage());
        assertFalse(h.service.getMessageArchiveService().queryInProgress(h.f.room));
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
        assertThrows(IllegalStateException.class, h::drain);
        assertNotNull(h.f.room.getMucRecoveryAnchor());
        assertNotNull(restore(h.service.saved, h.f.account).getMucRecoveryAnchor());
    }
    @Test public void finDuringUnresolvedRequiredWriteMustKeepAnchor() throws Exception {
        var h = new MamTestHarness(); h.join(); var write = h.query(0).persistence.beginResult();
        h.finish(0, true, "f", "l");
        write.complete(true); write.close(); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void failedOldSplitRangeCannotBeHiddenByRecentComplete() throws Exception {
        var h = new MamTestHarness();
        h.f.room.setMucRecoveryAnchor(new MamReference(System.currentTimeMillis() - 8 * 86_400_000L, "old"));
        h.join(); h.service.failureAt = 1; h.result(0, 1);
        h.finish(1, true, "f", "l"); h.finish(0, true, "f", "l");
        assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
}
