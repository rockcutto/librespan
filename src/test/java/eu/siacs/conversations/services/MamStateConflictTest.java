package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import static eu.siacs.conversations.services.MamTestHarness.*;
import static eu.siacs.conversations.services.XmppConnectionService.HistoryActionResult.*;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Production service/query/parser/barrier transitions; persistence is modeled by MamTestHarness.
 * No assertion here establishes native SQLite durability or Android device behaviour. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamStateConflictTest {
    private static class Result implements XmppConnectionService.OnMoreMessagesLoaded {
        int callbacks, count;
        final List<XmppConnectionService.HistoryActionResult> reasons = new ArrayList<>();
        @Override public void onMoreMessagesLoaded(int count, Conversation room) {
            callbacks++; this.count += count;
        }
        @Override public void onHistoryActionResult(XmppConnectionService.HistoryActionResult reason, Conversation room) {
            reasons.add(reason);
        }
    }
    private static void inlineReader(MamTestHarness h) throws Exception {
        var field = XmppConnectionService.class.getDeclaredField("mDatabaseReaderExecutor");
        field.setAccessible(true);
        field.set(h.service, new SerialSingleThreadExecutor("mam-state-test") {
            @Override public void execute(Runnable work) { work.run(); }
        });
    }
    private static MamTestHarness failedEmpty() throws Exception {
        var h = initialBootstrap(); h.join(); h.timeout(0); inlineReader(h); return h;
    }
    private static void assertIncomplete(MamTestHarness h) {
        assertNotNull(h.f.room.getMucRecoveryAnchor());
        assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE,
                h.service.getMucHistoryRecoveryState(h.f.room));
    }
    @Test public void incompleteHistoryWithEmptyTimelineMustNotBecomeUnrecoverable() throws Exception {
        var h = failedEmpty(); assertIncomplete(h); assertEquals(0, h.f.room.countMessages());
        var floor = h.f.room.getMucRecoveryAnchor(); var r = new Result();
        h.service.loadMoreMessages(h.f.room, 0, false, r);
        assertEquals(2, h.service.mamRequests.size());
        assertEquals(MessageArchiveService.MucHistoryPurpose.MANUAL_PAGE, h.query(1).getMucHistoryPurpose());
        h.result(1, 1); h.finish(1, false, "archive-1", "archive-1");
        assertEquals(1, r.callbacks); assertEquals(1, r.count);
        assertEquals(1, h.f.room.countMessages()); assertEquals(2, h.service.mamRequests.size());
        assertEquals(floor.getTimestamp(), h.f.room.getMucRecoveryAnchor().getTimestamp());
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room));
    }
    @Test public void emptyServerArchiveProvidesTerminalResultWithoutErasingRecoveryFloor() throws Exception {
        var h = failedEmpty(); var r = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, r);
        h.finish(1, true, null, null);
        assertEquals(1, r.callbacks); assertTrue(r.reasons.contains(HISTORY_EXHAUSTED));
        assertIncomplete(h); assertEquals(2, h.service.mamRequests.size());
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room)); h.drain();
        h.finish(2, true, null, null); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void missingAnchorIsExplainedAndManualRecentPageRemainsAvailable() throws Exception {
        var h = failedEmpty(); h.f.room.setMucRecoveryAnchor(null);
        assertEquals(NO_ANCHOR, h.service.retryMucHistoryRecoveryResult(h.f.room));
        var r = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, r);
        h.result(1, 1); h.finish(1, false, "archive-1", "archive-1");
        assertEquals(1, r.callbacks); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void bootstrapAndRepeatedRepairRejectConcurrentPaginationExplicitly() throws Exception {
        var h = initialBootstrap(); h.join(); inlineReader(h); var r = new Result();
        h.service.loadMoreMessages(h.f.room, 0, false, r);
        assertEquals(1, r.callbacks); assertEquals(List.of(QUERY_IN_PROGRESS), r.reasons);
        assertEquals(QUERY_IN_PROGRESS, h.service.retryMucHistoryRecoveryResult(h.f.room));
        assertEquals(QUERY_IN_PROGRESS, h.service.retryMucHistoryRecoveryResult(h.f.room));
        assertEquals(1, h.service.mamRequests.size());
        assertNull(h.service.getMessageArchiveService().query(h.f.room, new MamReference(0), 0, false));
    }
    @Test public void timeoutAndIqErrorReleaseOwnersForSafeExplicitRetry() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            var h = new MamTestHarness(); h.join();
            if (timeout) h.timeout(0); else { h.service.mamCallbacks.get(0).accept(new Iq(Iq.Type.ERROR)); h.drain(); }
            assertIncomplete(h); assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room)); h.drain();
            assertEquals("floor:opaque:z", h.cursor(1, "after"));
            h.finish(1, true, null, null); assertNull(h.f.room.getMucRecoveryAnchor());
        }
    }
    @Test public void databaseFailureNeverAdvancesOrClearsCheckpoint() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failureAt = 1; h.result(0, 1);
        h.finish(0, true, "archive-1", "archive-1"); assertIncomplete(h);
        assertEquals("floor:opaque:z", restore(h.service.saved, h.f.account).getMucRecoveryAnchor().getReference());
    }
    @Test public void shortManualPageNeverAutomaticallyWalksOlderHistory() throws Exception {
        var h = failedEmpty(); var r = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, r);
        h.result(1, 1); h.result(1, 2); h.finish(1, false, "archive-1", "archive-2");
        assertEquals(2, r.count); assertEquals(1, r.callbacks); assertEquals(2, h.service.mamRequests.size());
        assertTrue(h.f.room.hasMessagesLeftOnServer()); assertIncomplete(h);
    }
    @Test public void restartRestoresUnfinishedFloorWithoutBootstrapExemption() throws Exception {
        var first = new MamTestHarness(); first.join(); first.timeout(0);
        var restarted = new MamTestHarness(first.service.saved, first.f.account, first.service.rows);
        restarted.join(); assertEquals(MessageArchiveService.MucHistoryPurpose.MISSING_RANGE_RECOVERY,
                restarted.query(0).getMucHistoryPurpose());
        assertEquals("floor:opaque:z", restarted.cursor(0, "after"));
        restarted.timeout(0); assertIncomplete(restarted);
    }
    @Test public void incompleteEmptyPageWithChangingCursorDoesNotLoop() throws Exception {
        var h = new MamTestHarness(); h.join(); h.finish(0, false, "new-first", "new-last");
        assertEquals(1, h.service.mamRequests.size()); assertIncomplete(h);
        assertFalse(h.service.getMessageArchiveService().queryInProgress(h.f.room));
    }
    @Test public void recoveredOverlapIsDeduplicatedAndSorted() throws Exception {
        var h = new MamTestHarness(); h.join(); h.parser.accept(h.live(2, h.floorTime + 2000));
        h.result(0, 3); h.result(0, 1); h.result(0, 2); h.finish(0, true, "archive-1", "archive-3");
        assertEquals(List.of("archive-1", "archive-2", "archive-3"),
                h.f.room.snapshotMessages().stream().map(Message::getServerMsgId).toList());
        assertEquals(3, h.service.writes); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void synchronousFinCannotBeatManualCallbackRegistration() throws Exception {
        var h = failedEmpty(); h.service.duringMamSend = cb -> cb.accept(fin(true, null, null));
        var r = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, r); h.drain();
        assertEquals(1, r.callbacks); assertTrue(r.reasons.contains(HISTORY_EXHAUSTED));
        assertFalse(h.service.getMessageArchiveService().queryInProgress(h.f.room));
    }
    @Test public void zeroForwardBoundaryAndOfflineHaveExplicitCompletion() throws Exception {
        var h = failedEmpty(); var forward = new Result();
        h.service.loadMoreMessages(h.f.room, 0, true, forward);
        assertEquals(1, forward.callbacks); assertEquals(List.of(NO_LOCAL_BOUNDARY), forward.reasons);
        h.f.account.setStatus(Account.State.OFFLINE); var offline = new Result();
        h.service.loadMoreMessages(h.f.room, 0, false, offline);
        assertEquals(1, offline.callbacks); assertEquals(List.of(OFFLINE), offline.reasons);
        assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void unconfirmedRoomCannotDispatchManualArchiveQuery() throws Exception {
        var h = failedEmpty(); h.f.options().setOffline(); var r = new Result();
        h.service.loadMoreMessages(h.f.room, 0, false, r);
        assertEquals(1, r.callbacks); assertEquals(List.of(MUC_NOT_JOINED), r.reasons);
        assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void cancellationCompletesManualCallerAndRetainsFloor() throws Exception {
        var h = failedEmpty(); var r = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, r);
        h.service.getMessageArchiveService().kill(h.f.room); h.drain();
        assertEquals(1, r.callbacks); assertIncomplete(h);
        h.service.mamCallbacks.get(1).accept(fin(true, null, null)); h.drain();
        assertEquals(1, r.callbacks); assertIncomplete(h);
    }
    @Test public void optionalPaginationCursorCannotAuthorizeStrictZeroFloorCompletion() throws Exception {
        var h = failedEmpty(); h.f.room.setFirstMamReference("optional-old-page");
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room)); h.drain();
        assertNull(h.cursor(1, "before")); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void splitRangesReserveOneOwnerButSendOnlyOneRequestAtATime() throws Exception {
        var h = new MamTestHarness(); h.f.room.setMucRecoveryAnchor(new MamReference(System.currentTimeMillis() - 8 * 86400000L, "old"));
        h.join(); assertEquals(1, h.service.mamRequests.size());
        assertEquals(QUERY_IN_PROGRESS, h.service.retryMucHistoryRecoveryResult(h.f.room));
        h.finish(0, true, null, null); assertEquals(2, h.service.mamRequests.size());
        assertNotNull(h.f.room.getMucRecoveryAnchor());
        h.finish(1, true, null, null); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void databaseReadFailureCompletesCallerWithoutQueryOrCheckpointChange() throws Exception {
        var h = failedEmpty(); h.service.throwHistoryProbe = true; var r = new Result();
        h.service.loadMoreMessages(h.f.room, 0, false, r);
        assertEquals(1, r.callbacks); assertEquals(List.of(PERSISTENCE_FAILURE), r.reasons);
        assertEquals(1, h.service.mamRequests.size()); assertIncomplete(h);
    }
    @Test public void exhaustedHistoryCompletesCallerWithoutNewRequest() throws Exception {
        var h = initialBootstrap(); h.join(); h.finish(0, true, null, null); inlineReader(h);
        var r = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, r);
        assertEquals(1, r.callbacks); assertEquals(List.of(HISTORY_EXHAUSTED), r.reasons);
        assertEquals(1, h.service.mamRequests.size()); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void replacedDurableFloorCannotBeClearedByAnOlderOwner() throws Exception {
        var h = new MamTestHarness(); h.join();
        h.f.room.setMucRecoveryAnchor(new MamReference(h.floorTime - 1000, "replacement"));
        h.service.writeMucRecoveryAnchorRow(h.f.room);
        h.finish(0, true, null, null);
        assertEquals("replacement", h.f.room.getMucRecoveryAnchor().getReference()); assertIncomplete(h);
    }
    @Test public void legacyIqAckWithoutFinHasFiniteFailureAndRetry() throws Exception {
        var h = new MamTestHarness(); h.join();
        var version = MessageArchiveService.Query.class.getDeclaredField("version");
        version.setAccessible(true); version.set(h.query(0), MessageArchiveService.Version.MAM_0);
        h.service.mamCallbacks.get(0).accept(new Iq(Iq.Type.RESULT));
        assertEquals(1, h.service.mamFinTimeouts.size());
        h.service.mamFinTimeouts.get(0).run(); h.drain(); assertIncomplete(h);
        assertFalse(h.service.getMessageArchiveService().queryInProgress(h.f.room));
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room));
    }
    @Test public void reconnectRetiresDispatchedOwnerWithoutLosingCheckpoint() throws Exception {
        var h = new MamTestHarness(); h.join();
        h.service.getMessageArchiveService().cancelQueriesForReconnect(h.f.account); h.drain();
        assertIncomplete(h); assertFalse(h.service.getMessageArchiveService().queryInProgress(h.f.room));
        assertEquals("floor:opaque:z", restore(h.service.saved, h.f.account).getMucRecoveryAnchor().getReference());
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room));
    }
    @Test public void unsupportedArchiveCompletesBothActionsWithExplanation() throws Exception {
        var h = failedEmpty();
        var disco = h.f.options().getClass().getDeclaredField("serviceDiscoveryResult");
        disco.setAccessible(true); disco.set(h.f.options(), null);
        var r = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, r);
        assertEquals(1, r.callbacks); assertEquals(List.of(MAM_UNSUPPORTED), r.reasons);
        assertEquals(MAM_UNSUPPORTED, h.service.retryMucHistoryRecoveryResult(h.f.room));
        assertEquals(1, h.service.mamRequests.size());
    }
    @Test public void reentrantManualClickDuringSendCannotCreateSecondQuery() throws Exception {
        var h = failedEmpty(); var concurrent = new Result();
        h.service.duringMamSend = cb -> h.service.loadMoreMessages(h.f.room, 0, false, concurrent);
        var first = new Result(); h.service.loadMoreMessages(h.f.room, 0, false, first);
        assertEquals(2, h.service.mamRequests.size());
        assertEquals(1, concurrent.callbacks); assertEquals(List.of(QUERY_IN_PROGRESS), concurrent.reasons);
        h.finish(1, true, null, null); assertEquals(1, first.callbacks);
    }
    @Test public void splitFirstTimeoutStillFetchesRecentAndReportsIncomplete() throws Exception {
        var h = new MamTestHarness();
        h.f.room.setMucRecoveryAnchor(new MamReference(System.currentTimeMillis() - 8 * 86400000L, "old"));
        h.join(); h.timeout(0); assertEquals(2, h.service.mamRequests.size());
        h.result(1, 3); h.finish(1, true, "archive-3", "archive-3");
        assertEquals(1, h.service.writes); assertIncomplete(h);
        var outcomes = new ArrayList<XmppConnectionService.HistoryActionResult>();
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room, outcomes::add)); h.drain();
        h.timeout(2); assertEquals(4, h.service.mamRequests.size());
        h.finish(3, true, null, null);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(List.of(RECOVERY_INCOMPLETE), outcomes); assertIncomplete(h);
    }
    @Test public void splitFirstWriteFailureStillFetchesRecentWithoutClearingFloor() throws Exception {
        var h = new MamTestHarness(); long old = System.currentTimeMillis() - 8 * 86400000L;
        h.f.room.setMucRecoveryAnchor(new MamReference(old, "old")); h.join(); h.service.failureAt = 1;
        h.parser.accept(h.envelope(h.id(0), 1, old + 1000)); h.finish(0, true, "f", "l");
        assertEquals(2, h.service.mamRequests.size()); h.result(1, 2); h.finish(1, true, "f", "l");
        assertIncomplete(h); assertEquals("old", h.f.room.getMucRecoveryAnchor().getReference());
        assertEquals(1, h.service.writes);
    }
    @Test public void forwardRecoveryCannotOverwriteOlderPaginationCursor() throws Exception {
        var h = new MamTestHarness(); h.join(); h.f.room.setFirstMamReference("older-cursor");
        h.result(0, 1); h.finish(0, true, "new-first", "new-last");
        assertEquals("older-cursor", h.f.room.getFirstMamReference());
    }
    @Test public void splitFirstIqErrorStillReceivesRecentButKeepsCheckpoint() throws Exception {
        var h = new MamTestHarness(); long floor = System.currentTimeMillis() - 8 * 86400000L;
        h.f.room.setMucRecoveryAnchor(new MamReference(floor, "old")); h.join();
        var error = new Iq(Iq.Type.ERROR);
        error.addChild("error").addChild("forbidden", eu.siacs.conversations.xml.Namespace.STANZAS);
        h.service.mamCallbacks.get(0).accept(error); h.drain();
        assertEquals(2, h.service.mamRequests.size()); h.result(1, 7);
        h.finish(1, true, "archive-7", "archive-7");
        assertEquals(1, h.service.writes); assertIncomplete(h);
        assertEquals("old", h.f.room.getMucRecoveryAnchor().getReference());
        assertEquals("old", restore(h.service.saved, h.f.account).getMucRecoveryAnchor().getReference());
    }
    @Test public void existingLocalPageLoadsWhileNetworkQueryRuns() throws Exception {
        var h = new MamTestHarness(); h.join(); inlineReader(h);
        var old = new Message(h.f.room, "old", 0); old.setTime(h.floorTime - 1000);
        h.service.localHistoryPage.add(old); var result = new Result();
        h.service.loadMoreMessages(h.f.room, h.floorTime, false, result);
        assertEquals(1, result.callbacks); assertEquals(1, result.count); assertTrue(result.reasons.isEmpty());
        assertEquals(1, h.service.mamRequests.size()); assertNotNull(h.query(0));
    }
    @Test public void sharedBoundaryExcludesStatusRowsInBothDirections() throws Exception {
        var h = new MamTestHarness();
        var earlier = new Message(h.f.room, "earlier", 0); earlier.setTime(1000);
        var later = new Message(h.f.room, "later", 0); later.setTime(2000);
        var status = Message.createStatusMessage(h.f.room, "status"); status.setTime(100);
        h.f.room.add(status); h.f.room.add(earlier); h.f.room.add(later);
        assertEquals(1000, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, false));
        assertEquals(2000, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, true));
        h.f.room.clearMessages(); h.f.room.add(status);
        assertEquals(0, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, false));
        assertEquals(0, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, true));
    }
    @Test public void manualRetryLeaveCompletesExactlyOnceAndKeepsFloor() throws Exception {
        var h = new MamTestHarness(); h.join(); h.timeout(0);
        var outcomes = new ArrayList<XmppConnectionService.HistoryActionResult>();
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room, outcomes::add));
        h.service.leaveMuc(h.f.room); h.drain();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(List.of(RECOVERY_INCOMPLETE), outcomes);
        assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void manualRetrySuccessfulCompletionDeliveredOnce() throws Exception {
        var h = new MamTestHarness(); h.join(); h.timeout(0);
        var outcomes = new ArrayList<XmppConnectionService.HistoryActionResult>();
        assertEquals(STARTED, h.service.retryMucHistoryRecoveryResult(h.f.room, outcomes::add)); h.drain();
        h.finish(1, true, null, null);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(List.of(RECOVERY_COMPLETED), outcomes); assertNull(h.f.room.getMucRecoveryAnchor());
    }

    @Test public void historyPartBoundaryUsesDisplayedPageNotRecentTimeline() throws Exception {
        var h = new MamTestHarness();
        var recentFirst = new Message(h.f.room, "recent-first", 0); recentFirst.setTime(5000);
        var recentLast = new Message(h.f.room, "recent-last", 0); recentLast.setTime(6000);
        h.f.room.add(recentFirst); h.f.room.add(recentLast);
        var olderFirst = new Message(h.f.room, "older-first", 0); olderFirst.setTime(1000);
        var olderLast = new Message(h.f.room, "older-last", 0); olderLast.setTime(2000);
        var status = Message.createStatusMessage(h.f.room, "status"); status.setTime(50);
        h.f.room.jumpToHistoryPart(List.of(status, olderFirst, olderLast));
        assertTrue(h.f.room.isInHistoryPart());
        assertEquals(1000, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, false));
        assertEquals(2000, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, true));
        h.f.room.jumpToLatest();
        assertEquals(5000, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, false));
        assertEquals(6000, eu.siacs.conversations.entities.HistoryPageBoundary.timestamp(h.f.room, true));
    }

    @Test public void singleChatCappedMamPageIsNotReportedAsServerError() throws Exception {
        var h = new MamTestHarness(); h.join();
        var single = new Conversation("peer", h.f.account,
                eu.siacs.conversations.xmpp.Jid.of("peer@example.org"), Conversation.MODE_SINGLE, null);
        var callback = new Result();
        var archive = h.service.getMessageArchiveService();
        var query = archive.queryHistoryPage(single, System.currentTimeMillis(), false, callback);
        assertNotNull(query);
        // Model a successfully received but not final limited page.
        // The pending message writes are covered separately by persistence-barrier tests.
        for (int n = 0; n < query.getRequestedPageSize(); n++) {
            query.incrementMessageCount();
            query.incrementActualMessageCount();
        }
        var fin = new Iq(Iq.Type.RESULT);
        var result = fin.addChild("fin", query.version.namespace).setAttribute("complete", "false");
        var rsm = result.addChild("set", MamTestHarness.RSM);
        rsm.addChild("first").setContent("page-first");
        rsm.addChild("last").setContent("page-last");
        h.service.mamCallbacks.get(h.service.mamCallbacks.size() - 1).accept(fin);
        assertEquals(1, callback.callbacks);
        assertEquals(query.getRequestedPageSize(), callback.count);
        assertTrue("normal capped page must not produce a failure Toast", callback.reasons.isEmpty());
        assertTrue(single.hasMessagesLeftOnServer());
        assertFalse(archive.queryInProgress(single));
    }

    @Test public void singleChatIqErrorStillReportsServerFailure() throws Exception {
        var h = new MamTestHarness(); h.join();
        var single = new Conversation("peer", h.f.account,
                eu.siacs.conversations.xmpp.Jid.of("peer@example.org"), Conversation.MODE_SINGLE, null);
        var callback = new Result();
        var archive = h.service.getMessageArchiveService();
        assertNotNull(archive.queryHistoryPage(single, System.currentTimeMillis(), false, callback));
        h.service.mamCallbacks.get(h.service.mamCallbacks.size() - 1).accept(new Iq(Iq.Type.ERROR));
        assertEquals(1, callback.callbacks);
        assertEquals(List.of(SERVER_ERROR), callback.reasons);
        assertFalse(archive.queryInProgress(single));
    }
}
