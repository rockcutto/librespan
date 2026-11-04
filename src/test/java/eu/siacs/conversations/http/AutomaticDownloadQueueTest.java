package eu.siacs.conversations.http;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AutomaticDownloadQueueTest {
    static final class PausedExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void runOne() { tasks.removeFirst().run(); }
        void drain() { while (!tasks.isEmpty()) runOne(); }
    }

    @Test public void fixedThreadPoolBaselineActuallyQueues496Of500Tasks() throws Exception {
        ThreadPoolExecutor baseline = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 500; i++) baseline.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(4, baseline.getActiveCount());
            assertEquals(496, baseline.getQueue().size());
        } finally {
            release.countDown();
            baseline.shutdownNow();
            assertTrue(baseline.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void burstsOf100And500AreBoundedAndDiscardOldestWaitingWork() {
        for (int count : new int[] {100, 500}) {
            PausedExecutor executor = new PausedExecutor();
            AutomaticDownloadQueue queue = new AutomaticDownloadQueue(executor, 4, 16);
            AtomicInteger deferred = new AtomicInteger();
            List<Integer> executed = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int message = i;
                queue.submit(message, () -> executed.add(message), deferred::incrementAndGet);
                assertTrue(queue.pendingCount() <= 16);
                assertTrue(queue.runningCount() <= 4);
                assertTrue(executor.tasks.size() <= 4);
            }
            assertEquals(4, queue.runningCount());
            assertEquals(16, queue.pendingCount());
            assertEquals(count - 20, deferred.get());
            executor.runOne();
            executor.runOne();
            executor.runOne();
            executor.runOne();
            executor.runOne();
            assertEquals(Integer.valueOf(count - 1), executed.get(4));
            executor.drain();
            assertEquals(20, executed.size());
            assertEquals(0, queue.runningCount());
            assertEquals(0, queue.pendingCount());
            queue.submit("new live photo", () -> executed.add(-1), deferred::incrementAndGet);
            executor.drain();
            assertEquals(Integer.valueOf(-1), executed.get(20));
        }
    }

    @Test public void oneOperationContainsPreflightAndDownloadWithoutSecondAdmission() {
        PausedExecutor executor = new PausedExecutor();
        AutomaticDownloadQueue queue = new AutomaticDownloadQueue(executor, 1, 1);
        List<String> requests = new ArrayList<>();
        queue.submit("photo", () -> { requests.add("HEAD photo"); requests.add("GET photo"); }, () -> fail());
        queue.submit("audio", () -> { requests.add("HEAD audio"); requests.add("GET audio"); }, () -> fail());
        executor.drain();
        assertEquals(java.util.Arrays.asList("HEAD photo", "GET photo", "HEAD audio", "GET audio"), requests);
        assertEquals(0, queue.runningCount());
    }

    @Test public void duplicateActiveAndWaitingKeysNeverRunInParallelOrRepeat() {
        PausedExecutor executor = new PausedExecutor();
        AutomaticDownloadQueue queue = new AutomaticDownloadQueue(executor, 1, 2);
        AtomicInteger calls = new AtomicInteger();
        queue.submit("active", calls::incrementAndGet, () -> fail());
        queue.submit("active", calls::incrementAndGet, () -> fail());
        queue.submit("waiting", calls::incrementAndGet, () -> fail());
        queue.submit("waiting", calls::incrementAndGet, () -> fail());
        executor.drain();
        assertEquals(2, calls.get());
    }

    @Test public void cleanupRemovesOnlyAccountWaitingWorkAndDoesNotReleaseActiveSlotEarly() {
        PausedExecutor executor = new PausedExecutor();
        AutomaticDownloadQueue queue = new AutomaticDownloadQueue(executor, 1, 3);
        List<String> calls = new ArrayList<>();
        queue.submit("a:running", () -> calls.add("a:running"), () -> fail());
        queue.submit("a:waiting", () -> fail(), () -> fail());
        queue.submit("b:waiting", () -> calls.add("b:waiting"), () -> fail());
        assertFalse(queue.removePending("a:running"));
        assertTrue(queue.removePending("a:waiting"));
        assertEquals(1, queue.runningCount());
        assertEquals(1, queue.pendingCount());
        executor.drain();
        assertEquals(java.util.Arrays.asList("a:running", "b:waiting"), calls);
    }

    @Test public void manualPromotionRemovesWaitingWorkExactlyOnce() {
        PausedExecutor automatic = new PausedExecutor();
        PausedExecutor manual = new PausedExecutor();
        AutomaticDownloadQueue queue = new AutomaticDownloadQueue(automatic, 1, 1);
        AtomicInteger calls = new AtomicInteger();
        queue.submit("archive", () -> {}, () -> fail());
        queue.submit("manual", calls::incrementAndGet, () -> fail());
        assertTrue(queue.removePending("manual"));
        assertFalse(queue.removePending("manual"));
        manual.execute(calls::incrementAndGet);
        manual.drain();
        assertEquals(1, calls.get());
        assertEquals(1, automatic.tasks.size());
        automatic.drain();
        assertEquals(1, calls.get());
    }

    @Test public void failedOperationAndExecutorRejectionReleaseCapacity() {
        PausedExecutor executor = new PausedExecutor();
        AutomaticDownloadQueue queue = new AutomaticDownloadQueue(executor, 1, 1);
        AtomicInteger calls = new AtomicInteger();
        queue.submit("error", () -> { throw new IllegalStateException("network task failed"); }, () -> fail());
        queue.submit("next", calls::incrementAndGet, () -> fail());
        assertThrows(IllegalStateException.class, executor::runOne);
        executor.drain();
        assertEquals(1, calls.get());
        assertEquals(0, queue.runningCount());
        AutomaticDownloadQueue rejected = new AutomaticDownloadQueue(task -> { throw new RejectedExecutionException(); }, 1, 1);
        rejected.submit("offer", () -> fail(), calls::incrementAndGet);
        assertEquals(2, calls.get());
        assertEquals(0, rejected.runningCount());
    }
}
