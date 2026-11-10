package eu.siacs.conversations.services;

import android.os.SystemClock;
import android.util.Log;
import eu.siacs.conversations.BuildConfig;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Aggregate debug metrics only. Never accepts JIDs, archive IDs, payloads or exceptions. */
public final class MamDiagnostics {
    public static final String TAG = "LibreSpanMAM";
    public enum Reason { NEXT, COMPLETE, TIMEOUT, ERROR, CAP, BOUNDARY, CANCEL, RECONNECT, FALLBACK }
    public enum Event { DEFERRED, STALE, UNSOLICITED, SPLIT, RETRY, JOIN_CONFIRMED, RECOVERY_RETRY }
    private static final AtomicLong IDS = new AtomicLong();
    private static final Page OFF = new Page(null, 0, 0, 0);

    private MamDiagnostics() {}

    public static long now() {
        return BuildConfig.DEBUG ? SystemClock.elapsedRealtimeNanos() : 0L;
    }

    public static void action(XmppConnectionService.HistoryActionResult result) {
        if (BuildConfig.DEBUG) Log.d(TAG, "action=" + result + " t_us=" + now() / 1000);
    }

    public static void event(Event event) {
        if (BuildConfig.DEBUG) Log.d(TAG, "event=" + event + " t_us=" + now() / 1000);
    }

    /** Measures synchronous adapter notification only, not layout, draw or frame time. */
    public static void adapter(long started) {
        if (BuildConfig.DEBUG) Log.d(TAG, "event=UI_ADAPTER duration_us=" + (now() - started) / 1000);
    }

    static Page page() {
        return BuildConfig.DEBUG
                ? new Page(new Chain(SystemClock::elapsedRealtimeNanos, line -> Log.d(TAG, line)), 0, 1, 1)
                : OFF;
    }

    // Injectable only for deterministic JVM tests; no runtime switches or retained message data.
    static Page testing(LongSupplier clock, Consumer<String> sink) {
        return new Page(new Chain(clock, sink), 0, 1, 1);
    }

    private static final class Chain {
        final long id = IDS.incrementAndGet();
        final AtomicLong ranges = new AtomicLong();
        final LongSupplier clock;
        final Consumer<String> sink;
        Chain(LongSupplier clock, Consumer<String> sink) { this.clock = clock; this.sink = sink; }
    }

    public static final class Page {
        private final Chain chain;
        private final long range;
        private final int page, attempt;
        private long sent, iqToFin = -1, parse, persist, dispatch, duplicateLookup;
        private int received, duplicates, writes, failedWrites, sends, processing;
        private boolean reverse, ended;
        private int requested;
        private Page(Chain chain, long range, int page, int attempt) {
            this.chain = chain; this.range = range; this.page = page; this.attempt = attempt;
        }
        Page next() { return chain == null ? this : new Page(chain, range, page + 1, 1); }
        Page retry() { return chain == null ? this : new Page(chain, range, page, attempt + 1); }
        Page split() {
            if (chain == null) return this;
            event(Event.SPLIT);
            return new Page(chain, chain.ranges.incrementAndGet(), 1, 1);
        }
        public long begin() { return chain == null ? 0 : chain.clock.getAsLong(); }
        public void sent(boolean reverse, int requested) {
            if (chain == null) return;
            synchronized (this) {
                this.reverse = reverse;
                this.requested = requested;
                sent = begin();
                sends++;
                chain.sink.accept(prefix() + " event=SEND requested=" + requested + " sends=" + sends);
            }
        }
        public long resultStarted() {
            if (chain == null) return 0L;
            synchronized (this) {
                processing++;
                return begin();
            }
        }
        public void received() {
            if (chain == null) return;
            synchronized (this) { received++; }
        }
        public void duplicate() {
            if (chain == null) return;
            synchronized (this) { duplicates++; }
        }
        public void parsed(long started) {
            if (chain == null) return;
            synchronized (this) {
                parse += begin() - started;
                processing--;
            }
        }
        public void persisted(long started, boolean success) {
            if (chain == null) return;
            synchronized (this) {
                persist += begin() - started;
                writes++;
                if (!success) failedWrites++;
            }
        }
        public void duplicateLookup(long started) {
            if (chain == null) return;
            synchronized (this) { duplicateLookup += begin() - started; }
        }
        public void dispatched(long started) {
            if (chain == null) return;
            synchronized (this) { dispatch += begin() - started; }
        }
        void fin() {
            if (chain == null) return;
            synchronized (this) { iqToFin = begin() - sent; }
        }
        public void event(Event event) {
            if (chain == null) return;
            synchronized (this) { chain.sink.accept(prefix() + " event=" + event); }
        }
        void end(Reason reason) {
            if (chain == null) return;
            synchronized (this) {
                if (ended) return;
                ended = true;
                chain.sink.accept(prefix() + " event=END reason=" + reason
                        + " requested=" + requested + " received=" + received
                        + " duplicates=" + duplicates + " processing=" + processing
                        + " writes=" + writes + " failed_writes=" + failedWrites
                        + " iq_to_fin_us=" + (iqToFin < 0 ? -1 : iqToFin / 1000)
                        + " elapsed_us=" + (sends == 0 ? -1 : (begin() - sent) / 1000)
                        + " parse_us=" + parse / 1000 + " persist_us=" + persist / 1000
                        + " duplicate_lookup_us=" + duplicateLookup / 1000
                        + " publish_dispatch_us=" + dispatch / 1000);
            }
        }
        private String prefix() {
            return "chain=" + chain.id + " range=" + range + " page=" + page + " attempt=" + attempt
                    + " direction=" + (reverse ? "reverse" : "forward") + " t_us=" + begin() / 1000;
        }
    }
}
