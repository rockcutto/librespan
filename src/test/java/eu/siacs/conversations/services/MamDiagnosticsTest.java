package eu.siacs.conversations.services;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Deterministic monotonic-clock arithmetic, lineage, bounded output and no opaque identifiers. */
public class MamDiagnosticsTest {
    @Test public void reportsNestedDurationsAndRealWriteFailuresWithoutInferringDuplicates() {
        AtomicLong clock = new AtomicLong(1_000_000);
        List<String> logs = new ArrayList<>();
        var page = MamDiagnostics.testing(clock::get, logs::add);
        page.sent(false, 50);
        clock.addAndGet(1_000_000); long parse = page.resultStarted(); page.received();
        clock.addAndGet(1_000_000); long persist = page.begin();
        clock.addAndGet(3_000_000); page.persisted(persist, false);
        clock.addAndGet(2_000_000); page.parsed(parse);
        page.duplicate(); page.fin(); long dispatch = page.begin();
        clock.addAndGet(1_000_000); page.dispatched(dispatch); page.end(MamDiagnostics.Reason.COMPLETE);
        assertEquals(2, logs.size()); String end = logs.get(1);
        assertTrue(end.contains("iq_to_fin_us=7000")); assertTrue(end.contains("elapsed_us=8000"));
        assertTrue(end.contains("parse_us=6000")); assertTrue(end.contains("persist_us=3000"));
        assertTrue(end.contains("publish_dispatch_us=1000")); assertTrue(end.contains("failed_writes=1"));
        assertTrue(end.contains("duplicates=1")); assertTrue(end.contains("processing=0"));
    }
    @Test public void splitAndRetryPreserveChainButSeparateRangesAndAttempts() {
        List<String> logs = new ArrayList<>(); var page = MamDiagnostics.testing(() -> 1000L, logs::add);
        page.sent(false, 50); var retry = page.retry(); retry.sent(false, 50);
        var reverse = page.split(); reverse.sent(true, 10); page.next().sent(false, 50);
        String chain = logs.get(0).split(" ")[0];
        assertTrue(logs.stream().allMatch(line -> line.startsWith(chain)));
        assertTrue(logs.get(1).contains("page=1 attempt=2"));
        assertTrue(logs.get(3).contains("range=1 page=1 attempt=1 direction=reverse"));
        assertTrue(logs.get(4).contains("range=0 page=2 attempt=1 direction=forward"));
    }
    @Test public void everyTerminalReasonIsFiniteAndHasNoFinForTimeout() {
        for (var reason : MamDiagnostics.Reason.values()) {
            List<String> logs = new ArrayList<>(); var page = MamDiagnostics.testing(() -> 1000L, logs::add);
            page.sent(false, 50); page.end(reason); page.end(reason);
            assertEquals(2, logs.size()); assertTrue(logs.get(1).contains("iq_to_fin_us=-1"));
            assertTrue(logs.get(1).contains("reason=" + reason));
        }
    }
    @Test public void timeoutDuringProcessingExposesAnUnknownLocalOutcome() {
        List<String> logs = new ArrayList<>(); var page = MamDiagnostics.testing(() -> 1000L, logs::add);
        page.sent(false, 50); long start = page.resultStarted();
        page.end(MamDiagnostics.Reason.TIMEOUT); page.parsed(start);
        assertTrue(logs.get(1).contains("processing=1"));
    }
    @Test public void releaseFactoryDoesNotTouchClockOrEmitLogs() {
        // Covers the explicit disabled instance without depending on this JVM build's DEBUG value.
        try {
            var field = MamDiagnostics.class.getDeclaredField("OFF"); field.setAccessible(true);
            var off = (MamDiagnostics.Page) field.get(null);
            assertEquals(0, off.resultStarted()); off.sent(true, 10); off.received(); off.duplicate();
            off.persisted(0, false); off.parsed(0); off.fin(); off.end(MamDiagnostics.Reason.ERROR);
            if (!eu.siacs.conversations.BuildConfig.DEBUG) {
                assertSame(off, MamDiagnostics.page());
                assertEquals(0, MamDiagnostics.now());
                MamDiagnostics.event(MamDiagnostics.Event.STALE);
                MamDiagnostics.adapter(0);
            }
            assertSame(off, off.next()); assertSame(off, off.retry()); assertSame(off, off.split());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Test public void outputSchemaContainsOnlyCountersDirectionsEnumsAndMonotonicTime() {
        List<String> logs = new ArrayList<>(); var page = MamDiagnostics.testing(() -> 1000L, logs::add);
        page.sent(true, 10); page.event(MamDiagnostics.Event.RETRY); page.end(MamDiagnostics.Reason.FALLBACK);
        for (String line : logs) {
            assertFalse(line.contains("@")); assertFalse(line.contains("queryid")); assertFalse(line.contains("archive-"));
            for (String pair : line.split(" ")) assertTrue(pair.matches("[a-z_]+=(?:[0-9-]+|[A-Z_]+|forward|reverse)"));
        }
    }
}
