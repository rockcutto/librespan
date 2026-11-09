package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import org.junit.Test;

/** Tests the production accounting primitive, not a separately implemented checkpoint model. */
public class MamPersistenceBarrierTest {
    @Test public void allSuccessfulResultsAllowFin() {
        var page = new MamPersistenceBarrier();
        for (int n = 0; n < 3; n++) try (var result = page.beginResult()) { result.complete(true); }
        assertTrue(page.finishPage());
    }
    @Test public void firstMiddleAndLastFailureRemainSticky() {
        for (int failed = 0; failed < 3; failed++) {
            var page = new MamPersistenceBarrier();
            for (int n = 0; n < 3; n++) try (var r = page.beginResult()) { r.complete(n != failed); }
            assertFalse(page.finishPage());
        }
    }
    @Test public void unresolvedEarlyReturnOrExceptionFailsClosed() {
        var page = new MamPersistenceBarrier();
        page.beginResult().close();
        assertFalse(page.finishPage());
    }
    @Test public void finWhileWriterIsPendingCannotBeRepairedByItsLateSuccess() {
        var page = new MamPersistenceBarrier(); var result = page.beginResult();
        assertFalse(page.finishPage()); result.complete(true); result.close();
        assertFalse(page.finishPage());
    }
    @Test public void successfulMessageCannotEraseAnEarlierFailure() {
        var page = new MamPersistenceBarrier();
        try (var result = page.beginResult()) { result.complete(false); result.complete(true); }
        assertFalse(page.finishPage());
    }
    @Test public void pagesAndCursorFallbackShareFailureButIndependentChainsDoNot() {
        var page = new MamPersistenceBarrier();
        try (var r = page.beginResult()) { r.complete(false); }
        assertFalse(page.finishPage()); assertFalse(page.nextPage().finishPage());
        assertFalse(page.canFallback()); assertTrue(new MamPersistenceBarrier().finishPage());
    }
    @Test public void nextSuccessfulPageHasItsOwnPendingCounter() {
        var page = new MamPersistenceBarrier();
        try (var r = page.beginResult()) { r.complete(true); }
        assertTrue(page.finishPage()); var next = page.nextPage();
        try (var r = next.beginResult()) { r.complete(true); }
        assertTrue(next.finishPage());
    }
    @Test public void freshExplicitRetryDoesNotInheritFailedChain() {
        var old = new MamPersistenceBarrier(); old.beginResult().close(); assertFalse(old.finishPage());
        var retry = new MamPersistenceBarrier();
        try (var r = retry.beginResult()) { r.complete(true); }
        assertTrue(retry.finishPage());
    }
    @Test public void doubleCloseCannotUnderflowPendingOrPoisonADifferentResult() {
        var page = new MamPersistenceBarrier(); var a = page.beginResult(); var b = page.beginResult();
        a.complete(true); a.close(); a.close();
        assertFalse(page.canFallback()); b.complete(true); b.close(); assertTrue(page.finishPage());
    }
    @Test public void sealedPageRejectsLateWorkAndCompletionIsIdempotent() {
        var page = new MamPersistenceBarrier(); assertTrue(page.finishPage());
        assertNull(page.beginResult()); assertTrue(page.finishPage());
    }
}
