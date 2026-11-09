package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import static eu.siacs.conversations.services.MamTestHarness.*;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real Query/parser/completion/anchor serialization; persistence success is a controlled seam. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class MamLifecycleRegressionTest {
    @Test public void oneTwoAndManyPagesKeepOpaqueFloorUntilTheWholeChainCompletes() throws Exception {
        for (int pages : new int[]{1, 2, 7}) {
            var h = new MamTestHarness(); h.join();
            for (int p = 0; p < pages; p++) {
                assertNotNull(h.query(p)); h.result(p, p + 1);
                assertEquals("floor:opaque:z", h.f.room.getMucRecoveryAnchor().getReference());
                String cursor = p % 2 == 0 ? "opaque:z:" + p : "opaque:a:" + p;
                h.finish(p, p == pages - 1, "first:" + p, cursor);
                if (p < pages - 1) assertEquals(cursor, h.cursor(p + 1, "after"));
            }
            assertNull(h.f.room.getMucRecoveryAnchor());
            assertEquals(pages, h.service.writes);
            assertFalse(h.service.getMessageArchiveService().queryInProgress(h.f.room));
        }
    }
    @Test public void restartBetweenPagesReplaysFloorAndDeduplicatesDurableRows() throws Exception {
        for (int pages : new int[]{1, 2, 5}) {
            var first = new MamTestHarness(); first.join();
            for (int p = 0; p < pages; p++) { first.result(p, p + 1); first.finish(p, false, "f", "last:" + p); }
            var restarted = new MamTestHarness(first.service.saved, first.f.account, first.service.rows);
            restarted.join(); assertEquals("floor:opaque:z", restarted.cursor(0, "after"));
            for (int n = 1; n <= pages; n++) restarted.result(0, n);
            assertEquals(0, restarted.service.writes); // a repeat does not republish protected payloads
            restarted.result(0, pages + 1); assertEquals(1, restarted.service.writes);
            restarted.finish(0, true, "f", "l"); assertNull(restarted.f.room.getMucRecoveryAnchor());
        }
    }
    @Test public void crashBeforeAndAfterTerminalFloorWriteUsesTheLastSerializedState() throws Exception {
        var first = new MamTestHarness(); first.join(); first.result(0, 1);
        var before = new android.content.ContentValues(first.service.saved);
        first.finish(0, true, "f", "l"); var after = first.service.saved;
        assertNotNull(restore(before, first.f.account).getMucRecoveryAnchor());
        assertNull(restore(after, first.f.account).getMucRecoveryAnchor());
        // There is deliberately NO per-page disk checkpoint to simulate in this patch.
    }
    @Test public void duplicateResultsAndLiveOverlapKeepStableChronologicalOrder() throws Exception {
        var h = new MamTestHarness(); h.join(); long t = h.floorTime + 1000;
        h.parser.accept(h.live(90, t + 10_000));
        h.parser.accept(h.envelope(h.id(0), 1, t));
        h.parser.accept(h.envelope(h.id(0), 2, t));
        h.parser.accept(h.envelope(h.id(0), 1, t));
        assertEquals(3, h.service.writes);
        assertEquals(List.of("archive-1", "archive-2", "archive-90"),
                h.f.room.snapshotMessages().stream().map(Message::getServerMsgId).toList());
        h.finish(0, true, "f", "l"); assertEquals(3, h.f.room.countMessages());
    }
    @Test public void timeoutAndErrorLeaveAnExplicitlyRetryableFloorWithoutRejoin() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            var h = new MamTestHarness(); h.service.password = "test-only-password"; h.join();
            int sent = h.service.sent.size(); long generation = h.f.options().getJoinAttempt().getMembershipGeneration();
            h.service.mamCallbacks.get(0).accept(timeout ? Iq.TIMEOUT : new Iq(Iq.Type.ERROR)); h.drain();
            assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE, h.service.getMucHistoryRecoveryState(h.f.room));
            assertTrue(h.service.retryMucHistoryRecovery(h.f.room)); h.drain();
            assertEquals("floor:opaque:z", h.cursor(1, "after"));
            assertEquals(sent, h.service.sent.size());
            assertEquals(generation, h.f.options().getJoinAttempt().getMembershipGeneration());
            h.f.selfPresence(); h.drain(); assertEquals(2, h.service.mamRequests.size());
        }
    }
    @Test public void itemNotFoundFallsBackToTimeOnceAndNeverLexicallyComparesIds() throws Exception {
        var h = new MamTestHarness(); h.join();
        h.service.mamCallbacks.get(0).accept(itemNotFound());
        assertEquals(2, h.service.mamRequests.size()); assertNull(h.cursor(1, "after"));
        assertEquals(h.floorTime, h.query(1).getStart());
        h.service.mamCallbacks.get(1).accept(itemNotFound()); h.drain();
        assertEquals(2, h.service.mamRequests.size()); assertNotNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void reverseCursorFallbackRetainsItsTimeBoundAndDirection() throws Exception {
        var h = new MamTestHarness(); h.join(); h.timeout(0);
        var q = h.service.getMessageArchiveService().query(h.f.room, new MamReference(0), 123_000L, false);
        var previous = q.prev("expired:opaque"); previous.recordResultTimestamp(70_000L);
        // Exercise the real reverse Query cursor contract, including its retry eligibility.
        var next = previous.prev("expired:next");
        assertEquals(MessageArchiveService.PagingOrder.REVERSE, next.getPagingOrder());
        assertTrue(next.canRetryWithoutReference());
        assertEquals(123_000L, next.getEnd()); assertEquals("expired:next", next.getReference());
        h.service.getMessageArchiveService().kill(h.f.room);
    }
    @Test public void splitRangesRequireBothOutcomesInEitherCompletionOrder() throws Exception {
        for (boolean reverseFirst : new boolean[]{false, true}) for (boolean success : new boolean[]{false, true}) {
            var h = new MamTestHarness();
            h.f.room.setMucRecoveryAnchor(new MamReference(System.currentTimeMillis() - 8 * 86_400_000L, "old")); h.join();
            assertEquals(2, h.service.mamRequests.size());
            int first = reverseFirst ? 0 : 1, second = reverseFirst ? 1 : 0;
            h.service.mamCallbacks.get(first).accept(first == 0 && !success ? Iq.TIMEOUT : fin(true, "f", "l")); h.drain();
            assertNotNull(h.f.room.getMucRecoveryAnchor());
            assertEquals(XmppConnectionService.MucHistoryRecoveryState.SYNCING, h.service.getMucHistoryRecoveryState(h.f.room));
            h.service.mamCallbacks.get(second).accept(second == 0 && !success ? Iq.TIMEOUT : fin(true, "f", "l")); h.drain();
            if (success) assertNull(h.f.room.getMucRecoveryAnchor()); else assertNotNull(h.f.room.getMucRecoveryAnchor());
        }
    }
    @Test public void missingBoundaryAndEmptyIncompletePageDoNotClaimCompletion() throws Exception {
        for (boolean empty : new boolean[]{false, true}) {
            var h = new MamTestHarness(); h.join(); if (!empty) h.result(0, 1);
            h.finish(0, false, null, null);
            assertNotNull(h.f.room.getMucRecoveryAnchor()); assertEquals(1, h.service.mamRequests.size());
            assertEquals(XmppConnectionService.MucHistoryRecoveryState.INCOMPLETE, h.service.getMucHistoryRecoveryState(h.f.room));
        }
    }
    @Test public void limitsAt3000AndBeyondRetainFloorAndManualRetryRepeatsItsPrefix() throws Exception {
        for (int count : new int[]{2999, 3000, 3001}) {
            var h = new MamTestHarness(); h.join();
            for (int i = 0; i < count; i++) h.query(0).incrementMessageCount();
            h.finish(0, false, "first", "last");
            assertNotNull(h.f.room.getMucRecoveryAnchor());
            assertEquals(count < 3000 ? 2 : 1, h.service.mamRequests.size());
            if (count < 3000) h.timeout(1);
            assertTrue(h.service.retryMucHistoryRecovery(h.f.room)); h.drain();
            assertEquals("floor:opaque:z", h.cursor(h.service.mamRequests.size() - 1, "after"));
        }
    }
    @Test public void startZeroUsesReversePageRatherThanUnboundedForwardCatchup() throws Exception {
        var h = new MamTestHarness(); h.f.room.setMucRecoveryAnchor(new MamReference(0)); h.join();
        assertEquals(MessageArchiveService.PagingOrder.REVERSE, h.query(0).getPagingOrder());
        assertEquals(10, h.query(0).getRequestedPageSize());
        for (int i = 0; i < 10; i++) h.query(0).incrementMessageCount();
        h.finish(0, false, "older:first", "newer:last");
        assertNotNull(h.f.room.getMucRecoveryAnchor()); assertEquals(1, h.service.mamRequests.size());
        assertTrue(h.service.retryMucHistoryRecovery(h.f.room)); h.drain();
        assertEquals("older:first", h.cursor(1, "before")); // documents history-walking UX, not approval
    }
    @Test public void wrongQueryIdAndWrongRoomDoNotReachThePersistenceBoundary() throws Exception {
        var h = new MamTestHarness(); h.join();
        h.parser.accept(h.envelope("unknown-query", 1, h.floorTime + 1000));
        var foreign = h.envelope(h.id(0), 2, h.floorTime + 2000);
        foreign.setFrom(Jid.of("other@conference.example")); h.parser.accept(foreign);
        assertEquals(0, h.service.calls); assertEquals(0, h.query(0).getTotalCount());
    }
    @Test public void staleTerminalAfterKillOrMembershipResetCannotClearTheFloor() throws Exception {
        for (boolean reset : new boolean[]{false, true}) {
            var h = new MamTestHarness(); h.join();
            if (reset) h.f.room.resetMucOptions(); else h.service.getMessageArchiveService().kill(h.f.room);
            h.service.mamCallbacks.get(0).accept(fin(true, "f", "l")); h.drain();
            assertNotNull(h.f.room.getMucRecoveryAnchor());
        }
    }
    @Test public void reconnectStartsFromFrozenFloorAndStaleCallbackCannotClearNewWork() throws Exception {
        var h = new MamTestHarness(); h.join(); h.result(0, 1); h.timeout(0);
        h.f.options().setOffline(); h.join();
        assertEquals("floor:opaque:z", h.cursor(1, "after"));
        h.service.mamCallbacks.get(0).accept(fin(true, "f", "l")); h.drain();
        assertNotNull(h.f.room.getMucRecoveryAnchor()); assertNotNull(h.query(1));
        h.finish(1, true, "f", "l"); assertNull(h.f.room.getMucRecoveryAnchor());
    }
    @Test public void accountAndRoomMismatchInvalidateSerializedFloor() throws Exception {
        var h = new MamTestHarness(); h.join();
        var other = new Account(Jid.of("other@example.test"), "");
        assertNull(restore(h.service.saved, other).getMucRecoveryAnchor());
        var row = new android.content.ContentValues(h.service.saved);
        row.put(eu.siacs.conversations.entities.Conversation.CONTACTJID, "other@conference.example");
        assertNull(restore(row, h.f.account).getMucRecoveryAnchor());
    }
    @Test public void explicitClearBoundsTheNextRetryWithoutAdvancingTheFrozenFloor() throws Exception {
        var h = new MamTestHarness(); h.join(); h.timeout(0);
        h.f.room.clearMessages(); h.f.room.setLastClearHistory(h.floorTime + 10_000, "clear:id");
        assertTrue(h.service.retryMucHistoryRecovery(h.f.room)); h.drain();
        assertEquals("clear:id", h.cursor(1, "after"));
    }
    @Test public void dbFailureWhileClearingFloorRestoresInMemoryFloor() throws Exception {
        var h = new MamTestHarness(); h.join(); h.service.failConversationWrite = true;
        h.service.mamCallbacks.get(0).accept(fin(true, "f", "l"));
        assertThrows(IllegalStateException.class, h::drain);
        assertNotNull(h.f.room.getMucRecoveryAnchor());
        assertNotNull(restore(h.service.saved, h.f.account).getMucRecoveryAnchor());
    }
}
