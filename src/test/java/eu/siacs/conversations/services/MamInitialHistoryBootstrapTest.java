package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import static eu.siacs.conversations.services.MamTestHarness.*;
import static eu.siacs.conversations.services.MessageArchiveService.MucHistoryPurpose.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real join/Query/parser/barrier/completion and loadMoreMessages transitions.
 * The inherited fixture explicitly models successful writes; this is not native SQLite/crash QA. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamInitialHistoryBootstrapTest {
    private MamTestHarness fresh() throws Exception { return MamTestHarness.initialBootstrap(); }
    private void recentPage(MamTestHarness h) { for (int n = 1; n <= 10; n++) h.result(0, n); }
    private void finishRecent(MamTestHarness h) { h.finish(0, false, "archive-1", "archive-10"); }
    private void inlineReader(MamTestHarness h) throws Exception {
        var field = XmppConnectionService.class.getDeclaredField("mDatabaseReaderExecutor");
        field.setAccessible(true);
        field.set(h.service, new SerialSingleThreadExecutor("bootstrap-test") {
            @Override public void execute(Runnable work) { work.run(); }
        });
    }
    private void assertIncomplete(MamTestHarness h) {
        assertNotNull(h.f.room.getMucRecoveryAnchor());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE,
                h.service.getMucHistoryRecoveryState(h.f.room));
    }

    @Test public void freshRecentPageCompletesBoundedBootstrapWithoutWalkingArchive() throws Exception {
        var h = fresh(); h.join();
        assertEquals(INITIAL_RECENT_BOOTSTRAP, h.query(0).getMucHistoryPurpose());
        assertEquals(MessageArchiveService.PagingOrder.REVERSE, h.query(0).getPagingOrder());
        assertEquals(10, h.query(0).getRequestedPageSize());
        assertEquals(1, h.service.historyProbeReads); assertNotNull(h.service.saved);
        recentPage(h); finishRecent(h);
        assertNull(h.f.room.getMucRecoveryAnchor()); assertTrue(h.f.room.hasMessagesLeftOnServer());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, h.service.getMucHistoryRecoveryState(h.f.room));
        assertEquals(1, h.service.mamRequests.size()); assertEquals(10, h.service.writes);
        assertFalse(h.service.retryMucHistoryRecovery(h.f.room));
    }
    @Test public void freshBootstrapAndItsFailedRetryCannotReuseStaleScrollCursor() throws Exception {
        var h = fresh(); h.f.room.setFirstMamReference("stale:scroll"); h.join();
        assertEquals(INITIAL_RECENT_BOOTSTRAP, h.query(0).getMucHistoryPurpose());
        assertNull(h.query(0).getReference()); assertNull(h.f.room.getFirstMamReference());
        h.timeout(0); assertTrue(h.service.retryMucHistoryRecovery(h.f.room)); h.drain();
        assertEquals(MISSING_RANGE_RECOVERY, h.query(1).getMucHistoryPurpose());
        assertNull(h.query(1).getReference()); h.timeout(1); assertIncomplete(h);
    }
    @Test public void completeRecentArchiveMarksOlderHistoryExhausted() throws Exception {
        var h = fresh(); h.join(); h.result(0, 1); h.result(0, 2);
        h.finish(0, true, "archive-1", "archive-2");
        assertNull(h.f.room.getMucRecoveryAnchor()); assertFalse(h.f.room.hasMessagesLeftOnServer());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, h.service.getMucHistoryRecoveryState(h.f.room));
        assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void emptyCompleteArchiveIsAValidBootstrap() throws Exception {
        var h = fresh(); h.join(); h.finish(0, true, null, null);
        assertNull(h.f.room.getMucRecoveryAnchor()); assertFalse(h.f.room.hasMessagesLeftOnServer());
        assertEquals(0, h.f.room.countMessages()); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void shortServerLimitedPageCompletesWithoutAutomaticPaging() throws Exception {
        var h = fresh(); h.join(); h.result(0, 1); h.result(0, 2);
        h.finish(0, false, "archive-1", "archive-2");
        assertNull(h.f.room.getMucRecoveryAnchor()); assertTrue(h.f.room.hasMessagesLeftOnServer());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, h.service.getMucHistoryRecoveryState(h.f.room));
        assertEquals(2, h.service.writes); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void serverMayReturnFiveDurableResultsForRequestedMaxTen() throws Exception {
        var h = fresh(); h.join(); assertEquals(10, h.query(0).getRequestedPageSize());
        for (int n = 1; n <= 5; n++) h.result(0, n);
        h.finish(0, false, "archive-1", "archive-5");
        assertNull(h.f.room.getMucRecoveryAnchor()); assertTrue(h.f.room.hasMessagesLeftOnServer());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, h.service.getMucHistoryRecoveryState(h.f.room));
        assertEquals("archive-1", h.f.room.getFirstMamReference());
        assertEquals(5, h.service.writes); assertEquals(5, h.service.rows.size());
        assertEquals(1, h.service.mamRequests.size()); assertFalse(h.service.retryMucHistoryRecovery(h.f.room));
    }
    @Test public void emptyIncompletePageCannotConfirmBootstrap() throws Exception {
        var h = fresh(); h.join(); h.finish(0, false, "archive-1", "archive-5");
        assertIncomplete(h); assertEquals(0, h.service.writes); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void serverLimitedPageStillRequiresEveryWriteToSucceed() throws Exception {
        for (int failure : new int[]{1, 3, 5}) {
            var h = fresh(); h.join(); h.service.failureAt = failure; h.service.realScsFailure = true;
            for (int n = 1; n <= 5; n++) h.result(0, n);
            h.finish(0, false, "archive-1", "archive-5");
            assertIncomplete(h); assertEquals(4, h.service.rows.size());
            assertEquals(1, h.service.failures); assertEquals(1, h.service.mamRequests.size());
        }
    }
    @Test public void foreignQueryResultsCannotAuthorizeAnEmptyBootstrapPage() throws Exception {
        var h = fresh(); h.join();
        for (int n = 1; n <= 5; n++) h.parser.accept(h.envelope("different-query", n, h.floorTime + n * 1000L));
        h.finish(0, false, "archive-1", "archive-5");
        assertIncomplete(h); assertEquals(0, h.service.writes); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void mismatchedFinQueryIdCannotConfirmServerLimitedPage() throws Exception {
        var h = fresh(); h.join(); for (int n = 1; n <= 5; n++) h.result(0, n);
        var response = fin(false, "archive-1", "archive-5");
        response.findChild("fin", MAM).setAttribute("queryid", "different-query");
        h.service.mamCallbacks.get(0).accept(response); h.drain();
        assertIncomplete(h); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void overLimitPageCannotClaimNormalBoundedCompletion() throws Exception {
        var h = fresh(); h.join(); for (int n = 1; n <= 11; n++) h.result(0, n);
        h.finish(0, false, "archive-1", "archive-11");
        assertIncomplete(h); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void missingOrEmptyBoundariesDoNotConfirmBootstrap() throws Exception {
        for (String[] boundary : new String[][]{{null, "l"}, {"", "l"}, {"f", null}, {"f", ""}}) {
            var h = fresh(); h.join(); recentPage(h); h.finish(0, false, boundary[0], boundary[1]);
            assertIncomplete(h); assertEquals(1, h.service.mamRequests.size());
        }
    }
    @Test public void invalidBoundaryCannotConfirmEvenACompleteBootstrap() throws Exception {
        var h = fresh(); h.join(); h.result(0, 1); h.finish(0, true, "archive-1", "");
        assertIncomplete(h); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void invalidBoundaryCannotConfirmAnExistingRecovery() throws Exception {
        var h = new MamTestHarness(); h.join(); h.result(0, 1); h.finish(0, true, "", "archive-1");
        assertIncomplete(h); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void failureAtFirstMiddleOrLastWriteCannotBeHiddenByBoundedSuccess() throws Exception {
        for (int failure : new int[]{1, 5, 10}) {
            var h = fresh(); h.join(); h.service.failureAt = failure; h.service.realScsFailure = true;
            recentPage(h); finishRecent(h); assertIncomplete(h);
            assertEquals(1, h.service.failures); assertEquals(9, h.service.rows.size());
            assertEquals(1, h.service.mamRequests.size());
        }
    }
    @Test public void exceptionBeforeFinKeepsFloorForRecovery() throws Exception {
        var h = fresh(); h.join(); h.service.failureAt = 1; h.service.throwWrite = true;
        assertThrows(IllegalStateException.class, () -> h.result(0, 1));
        h.finish(0, true, "f", "l"); assertIncomplete(h);
    }
    @Test public void timeoutAndIqErrorAreNotNormalPageLimits() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            var h = fresh(); h.join(); h.result(0, 1);
            h.service.mamCallbacks.get(0).accept(timeout ? Iq.TIMEOUT : new Iq(Iq.Type.ERROR)); h.drain();
            assertIncomplete(h); assertEquals(1, h.service.mamRequests.size());
        }
    }
    @Test public void explicitRetryOfFailedInitialRequestUsesStrictRecovery() throws Exception {
        var h = fresh(); h.join(); h.timeout(0);
        assertTrue(h.service.retryMucHistoryRecovery(h.f.room)); h.drain();
        assertEquals(MISSING_RANGE_RECOVERY, h.query(1).getMucHistoryPurpose());
        for (int n = 1; n <= 10; n++) h.result(1, n);
        h.finish(1, false, "f", "l"); assertIncomplete(h);
        assertEquals(2, h.service.mamRequests.size());
    }
    @Test public void serializedZeroAnchorAfterInterruptedBootstrapIsNeverFresh() throws Exception {
        var first = fresh(); first.join();
        var restarted = new MamTestHarness(first.service.saved, first.f.account, first.service.rows);
        restarted.join(); assertEquals(MISSING_RANGE_RECOVERY, restarted.query(0).getMucHistoryPurpose());
        recentPage(restarted); finishRecent(restarted); assertIncomplete(restarted);
    }
    @Test public void preexistingZeroFloorInSameProcessIsStrictRecovery() throws Exception {
        var h = new MamTestHarness(); h.f.room.setMucRecoveryAnchor(new MamReference(0)); h.join();
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        recentPage(h); finishRecent(h); assertIncomplete(h);
    }
    @Test public void existingResidentHistoryAndFrozenFloorKeepForwardRecovery() throws Exception {
        var h = new MamTestHarness(); h.join();
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        assertEquals(MessageArchiveService.PagingOrder.NORMAL, h.query(0).getPagingOrder());
        assertEquals("floor:opaque:z", h.cursor(0, "after"));
        h.result(0, 1); h.finish(0, true, "f", "l"); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void unloadedDurableRowsCannotBeMisclassifiedAsNewHistory() throws Exception {
        var h = fresh(); var row = new Message(h.f.room, "previously stored", 0);
        row.setServerMsgId("stored"); row.setTime(h.floorTime - 1000);
        assertTrue(h.service.persistIncomingMessage(row)); assertEquals(0, h.f.room.countMessages());
        h.join(); assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        h.timeout(0); assertIncomplete(h);
    }
    @Test public void unknownHistoryProbeCannotAuthorizeBootstrap() throws Exception {
        var h = fresh(); h.service.unknownHistoryProbe = true; h.join();
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        h.timeout(0); assertIncomplete(h);
    }
    @Test public void historyProbeRunsOutsideBothJoinMonitorsAfterFloorIsSaved() throws Exception {
        var h = fresh();
        var field = XmppConnectionService.class.getDeclaredField("pendingMucCatchups"); field.setAccessible(true);
        Object pendingMonitor = field.get(h.service);
        var probes = new AtomicInteger();
        h.service.duringHistoryProbe = () -> {
            assertFalse(Thread.holdsLock(h.f.room)); assertFalse(Thread.holdsLock(pendingMonitor));
            assertNotNull(h.f.room.getMucRecoveryAnchor());
            assertNotNull(restore(h.service.saved, h.f.account).getMucRecoveryAnchor());
            assertEquals(0, h.f.room.getMucRecoveryAnchor().getTimestamp()); probes.incrementAndGet();
        };
        h.join(); assertEquals(1, probes.get());
        assertEquals(INITIAL_RECENT_BOOTSTRAP, h.query(0).getMucHistoryPurpose());
        h.timeout(0); assertIncomplete(h);
    }
    @Test public void databaseProbeExceptionFallsBackToStrictRecoveryWithFrozenZeroFloor() throws Exception {
        var h = fresh(); h.service.throwHistoryProbe = true; h.join();
        assertEquals(1, h.service.historyProbeReads);
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        assertEquals(0, h.f.room.getMucRecoveryAnchor().getTimestamp());
        assertNotNull(restore(h.service.saved, h.f.account).getMucRecoveryAnchor());
        h.timeout(0); assertIncomplete(h); assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void liveArrivalAfterProbeSnapshotKeepsOriginalFloorAndStrictRecovery() throws Exception {
        var h = fresh(); h.service.duringHistoryProbe = () -> h.parser.accept(h.live(5, h.floorTime + 5000));
        h.join(); assertEquals(1, h.f.room.countMessages()); assertEquals(1, h.service.writes);
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        assertEquals(MessageArchiveService.PagingOrder.REVERSE, h.query(0).getPagingOrder());
        assertEquals(0, h.f.room.getMucRecoveryAnchor().getTimestamp()); assertNull(h.cursor(0, "after"));
        h.timeout(0); assertIncomplete(h);
        assertEquals(0, restore(h.service.saved, h.f.account).getMucRecoveryAnchor().getTimestamp());
    }
    @Test public void residentChangeDuringProbeRemainsStrictEvenIfRoomBecomesEmptyAgain() throws Exception {
        var h = fresh(); h.service.duringHistoryProbe = () -> {
            h.f.room.add(new Message(h.f.room, "resident change", 0)); h.f.room.clearMessages();
        };
        h.join(); assertEquals(0, h.f.room.countMessages());
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        h.timeout(0); assertIncomplete(h);
    }
    @Test public void changedMembershipDuringProbeCannotPromoteOldRequest() throws Exception {
        var h = fresh(); h.service.duringHistoryProbe = () -> h.f.options().setOffline();
        h.join(); assertTrue(h.service.mamRequests.isEmpty()); assertNotNull(h.f.room.getMucRecoveryAnchor());
        h.service.duringHistoryProbe = null; h.join();
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        assertEquals(1, h.service.historyProbeReads); h.timeout(0); assertIncomplete(h);
    }
    @Test public void leaveAccountOrRoomReplacementDuringProbeRejectsStaleWork() throws Exception {
        for (int action = 0; action < 3; action++) {
            var h = fresh(); final int change = action; String[] afterChange = new String[1];
            h.service.duringHistoryProbe = () -> {
                if (change == 0) h.service.leaveMuc(h.f.room);
                else if (change == 1) h.f.room.setAccount(new Account(Jid.of("replacement@example.test"), ""));
                else h.f.room.setContactJid(Jid.of("replacement@conference.example"));
                afterChange[0] = h.f.room.getAttributes().toString();
            };
            h.join(); assertTrue(h.service.mamRequests.isEmpty());
            assertEquals(afterChange[0], h.f.room.getAttributes().toString());
        }
    }
    @Test public void changedRecoveryAnchorDuringProbeCannotBePromotedToInitial() throws Exception {
        var h = fresh(); h.service.duringHistoryProbe = () -> {
            h.f.room.setMucRecoveryAnchor(new MamReference(h.floorTime, "changed-floor"));
            h.service.writeMucRecoveryAnchorRow(h.f.room);
        };
        h.join(); assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        assertEquals("changed-floor", h.f.room.getMucRecoveryAnchor().getReference());
        h.timeout(0); assertIncomplete(h);
        assertEquals("changed-floor", restore(h.service.saved, h.f.account).getMucRecoveryAnchor().getReference());
    }
    @Test public void historyClearDuringProbeCannotAuthorizeFreshBootstrap() throws Exception {
        var h = fresh(); h.service.duringHistoryProbe = () -> {
            h.f.room.setLastClearHistory(h.floorTime, "cleared-history");
            h.service.writeMucRecoveryAnchorRow(h.f.room);
        };
        h.join(); assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        assertEquals(MessageArchiveService.PagingOrder.NORMAL, h.query(0).getPagingOrder());
        assertEquals("cleared-history", h.cursor(0, "after"));
        h.timeout(0); assertIncomplete(h);
    }
    @Test public void concurrentStrictDispatchDuringProbeCannotBeUpgradedOrDuplicated() throws Exception {
        var h = fresh(); h.service.duringHistoryProbe = () -> {
            h.service.onMucJoinConfirmed(h.f.options()); h.drain();
            assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        };
        h.join(); assertEquals(1, h.service.mamRequests.size());
        assertEquals(MISSING_RANGE_RECOVERY, h.query(0).getMucHistoryPurpose());
        h.timeout(0); assertIncomplete(h); assertEquals(1, h.service.historyProbeReads);
    }
    @Test public void successfulBootstrapRestartRestoresLatestRowAndCatchesUpFromIt() throws Exception {
        var first = fresh(); first.join(); recentPage(first); finishRecent(first);
        var restarted = new MamTestHarness(first.service.saved, first.f.account, first.service.rows);
        // Production startup restores the latest DB row/page before account connection is released.
        var latest = first.service.rows.get("archive-10");
        var restored = new Message(restarted.f.room, "restored latest", 0);
        restored.setUuid(latest.getUuid()); restored.setServerMsgId(latest.getServerMsgId()); restored.setTime(latest.getTimeSent());
        restarted.f.room.add(restored); restarted.join();
        assertEquals(MISSING_RANGE_RECOVERY, restarted.query(0).getMucHistoryPurpose());
        assertEquals(MessageArchiveService.PagingOrder.NORMAL, restarted.query(0).getPagingOrder());
        assertEquals("archive-10", restarted.cursor(0, "after"));
        restarted.finish(0, true, null, null);
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, restarted.service.getMucHistoryRecoveryState(restarted.f.room));
        assertEquals(1, restarted.service.mamRequests.size());
    }
    @Test public void repeatedSelfPresenceAfterBootstrapCannotRestartIt() throws Exception {
        var h = fresh(); h.join(); recentPage(h); finishRecent(h);
        h.f.selfPresence(); h.service.onMucJoinConfirmed(h.f.options()); h.drain();
        assertEquals(1, h.service.mamRequests.size()); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void changedMembershipKeepsNewRecoveryWhenOldBootstrapFinArrives() throws Exception {
        var h = fresh(); h.join(); recentPage(h); h.f.options().setOffline(); h.join();
        assertEquals(1, h.service.mamRequests.size());
        finishRecent(h); assertEquals(2, h.service.mamRequests.size());
        assertNotNull(h.f.room.getMucRecoveryAnchor());
        assertEquals(MISSING_RANGE_RECOVERY, h.query(1).getMucHistoryPurpose());
        h.timeout(1); assertIncomplete(h);
    }
    @Test public void leaveAndAccountReplacementRejectStaleBootstrapCleanup() throws Exception {
        for (boolean leave : new boolean[]{false, true}) {
            var h = fresh(); h.join(); recentPage(h);
            if (leave) h.service.leaveMuc(h.f.room);
            else h.f.room.setAccount(new Account(Jid.of("replacement@example.test"), ""));
            String before = h.f.room.getAttributes().toString();
            h.service.mamCallbacks.get(0).accept(fin(true, "f", "l")); h.drain();
            assertEquals(before, h.f.room.getAttributes().toString()); assertEquals(1, h.service.mamRequests.size());
        }
    }
    @Test public void liveOverlapAndDeferredCleanupPreserveOrderingAndNoDuplicates() throws Exception {
        var h = fresh(); h.join(); h.parser.accept(h.live(5, h.floorTime + 5000));
        recentPage(h); h.service.mamCallbacks.get(0).accept(fin(false, "archive-1", "archive-10"));
        h.parser.accept(h.live(11, h.floorTime + 11000)); h.drain();
        assertNull(h.f.room.getMucRecoveryAnchor()); assertEquals(11, h.service.writes);
        assertEquals(11, h.f.room.countMessages());
        assertEquals(List.of("archive-1", "archive-2", "archive-3", "archive-4", "archive-5", "archive-6",
                "archive-7", "archive-8", "archive-9", "archive-10", "archive-11"),
                h.f.room.snapshotMessages().stream().map(Message::getServerMsgId).toList());
    }
    @Test public void splitRangeFailureStillPreventsOverallRecoveryCompletion() throws Exception {
        var h = new MamTestHarness(); long oldStart = System.currentTimeMillis() - 8 * 86_400_000L;
        h.f.room.setMucRecoveryAnchor(new MamReference(oldStart, "old")); h.join(); h.service.failureAt = 1;
        assertEquals(1, h.service.mamRequests.size());
        h.parser.accept(h.envelope(h.id(0), 1, oldStart + 1000));
        h.finish(0, true, "f", "l");
        assertEquals(2, h.service.mamRequests.size());
        h.result(1, 2); h.finish(1, true, "f", "l"); assertIncomplete(h);
    }
    @Test public void lazyScrollReadsLocalPageBeforeOneBoundedServerPage() throws Exception {
        var h = fresh(); h.join(); recentPage(h); finishRecent(h); inlineReader(h);
        var olderLocal = new Message(h.f.room, "https://example.test/old.ogg", 0);
        olderLocal.setType(Message.TYPE_FILE); olderLocal.setTime(h.floorTime - 1000); h.service.localHistoryPage.add(olderLocal);
        var loaded = new AtomicInteger();
        h.service.loadMoreMessages(h.f.room, h.floorTime + 1000, false, (count, room) -> loaded.addAndGet(count));
        assertEquals(1, loaded.get()); assertEquals(1, h.service.mamRequests.size());
        h.service.loadMoreMessages(h.f.room, olderLocal.getTimeSent(), false, (count, room) -> loaded.addAndGet(count));
        assertEquals(2, h.service.historyPageReads); assertEquals(2, h.service.mamRequests.size());
        assertEquals(MANUAL_PAGE, h.query(1).getMucHistoryPurpose());
        assertEquals(MessageArchiveService.PagingOrder.REVERSE, h.query(1).getPagingOrder());
        assertEquals(10, h.query(1).getRequestedPageSize()); assertEquals("archive-1", h.cursor(1, "before"));
        for (int n = -10; n < 0; n++) h.result(1, n);
        h.finish(1, false, "archive--10", "archive--1");
        assertEquals(2, h.service.mamRequests.size()); assertNull(h.f.room.getMucRecoveryAnchor());
        assertEquals(11, loaded.get());
    }
    @Test public void optionalOlderQueryCannotTurnSuccessfulInitialPageIntoIncompleteRecovery() throws Exception {
        var h = fresh(); h.join(); recentPage(h);
        h.service.mamCallbacks.get(0).accept(fin(false, "archive-1", "archive-10"));
        var older = h.service.getMessageArchiveService().query(h.f.room, new MamReference(0), h.floorTime + 1000, false);
        assertNotNull(older); h.drain();
        assertNull(h.f.room.getMucRecoveryAnchor());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.NONE, h.service.getMucHistoryRecoveryState(h.f.room));
        h.finish(1, true, null, null); assertFalse(h.f.room.hasMessagesLeftOnServer());
    }
    @Test public void lazyArchiveExhaustionStopsFurtherServerPaging() throws Exception {
        var h = fresh(); h.join(); recentPage(h); finishRecent(h); inlineReader(h);
        h.service.loadMoreMessages(h.f.room, h.floorTime + 1000, false, (count, room) -> {});
        h.finish(1, true, null, null); assertFalse(h.f.room.hasMessagesLeftOnServer());
        h.service.loadMoreMessages(h.f.room, h.floorTime, false, (count, room) -> {});
        assertEquals(2, h.service.mamRequests.size()); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void failedAnchorCleanupDoesNotTurnBootstrapIntoSuccess() throws Exception {
        var h = fresh(); h.join(); recentPage(h); h.service.zeroConversationWrite = true;
        h.service.mamCallbacks.get(0).accept(fin(false, "archive-1", "archive-10"));
        h.drain();
        assertIncomplete(h); assertNotNull(restore(h.service.saved, h.f.account).getMucRecoveryAnchor());
    }
}
