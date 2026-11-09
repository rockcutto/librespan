package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import static eu.siacs.conversations.services.MamTestHarness.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** RED acceptance tests, intentionally outside Gradle's normal source set.
 * Copy into src/test/java/.../services explicitly using WSL_VERIFICATION.md.
 * These must pass before any durable page cursor is considered safe. No @Ignore. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamRequiredSafetyTest {
    @Test public void scsFailureAtFirstMiddleOrLastMustKeepFloor() throws Exception {
        for (int failure : new int[]{1, 2, 3}) {
            var h = new MamTestHarness(); h.join(); h.service.failureAt = failure; h.service.realScsFailure = true;
            for (int n = 1; n <= 3; n++) h.result(0, n);
            h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
        }
    }
    @Test public void failedPageMustNotAdvanceItsRsmCursor() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1;
        h.result(0, 1); h.finish(0, false, "f", "last");
        assertEquals(1, h.service.mamRequests.size()); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void mandatoryDbExceptionMustPreventSuccessfulCompletion() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1; h.service.throwWrite = true;
        assertThrows(IllegalStateException.class, () -> h.result(0, 1));
        h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void invalidEmptyBoundaryMustNotStartAnotherPage() throws Exception {
        var h = new MamTestHarness(); h.join(); h.result(0, 1); h.finish(0, false, "f", "");
        assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void reentrantNextPageFinMustFindItsQueryAlreadyRegistered() throws Exception {
        var h = new MamTestHarness(); h.join();
        h.service.duringMamSend = callback -> callback.accept(fin(true, "f", "l"));
        h.result(0, 1); h.finish(0, false, "f", "l");
        assertFalse(h.service.getMessageArchiveService().queryInProgress(h.f.room));
        assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void initialRecentPageMustNotLeaveMandatoryWholeArchiveRecovery() throws Exception {
        // A serialized/preset zero anchor denotes unfinished recovery, not a fresh room.
        // Exercise the actual initial join and parser writes without changing either assertion.
        var h = MamTestHarness.initialBootstrap();
        h.join(); for (int i = 1; i <= 10; i++) h.result(0, i);
        h.finish(0, false, "f", "l");
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, h.service.getMucHistoryRecoveryState(h.f.room));
        assertTrue(h.f.room.hasMessagesLeftOnServer());
    }
    @Test public void failedOldSplitRangeMustNotBeHiddenBySuccessfulRecentRange() throws Exception {
        var h = new MamTestHarness();
        h.f.room.setMucRecoveryAnchor(new eu.siacs.conversations.xmpp.mam.MamReference(System.currentTimeMillis() - 8 * 86_400_000L, "old"));
        h.join(); h.service.failureAt = 1; h.service.realScsFailure = true;
        h.result(0, 1); h.finish(0, true, "f", "l"); h.finish(1, true, "f", "l");
        assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void optionalFinQueryIdWhenPresentMustMatchItsRegisteredQuery() throws Exception {
        var h = new MamTestHarness(); h.join();
        var wrong = fin(true, "f", "l"); wrong.findChild("fin", MAM).setAttribute("queryid", "unrelated-query");
        h.service.mamCallbacks.get(0).accept(wrong); h.drain();
        assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void clearDuringActiveQueryMustRejectLateOlderPayload() throws Exception {
        var h = new MamTestHarness(); h.join();
        h.f.room.clearMessages(); h.f.room.setLastClearHistory(h.floorTime + 10_000, "clear:id");
        h.result(0, 1); assertEquals(0, h.f.room.countMessages());
    }

    @Test public void metadataOnlyDuplicateMustNotProvePageDurability() throws Exception {
        var h = new MamTestHarness(); h.join();
        var metadataOnly = new eu.siacs.conversations.entities.Message(h.f.room, "", 0);
        metadataOnly.setServerMsgId("archive-1");
        metadataOnly.setSecureMessagePayloadMode(eu.siacs.conversations.storage.secure.SecureMessagePayloadMode.UNAVAILABLE);
        h.service.rows.put("archive-1", metadataOnly); h.result(0, 1);
        h.finish(0, true, "f", "l"); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }

}
