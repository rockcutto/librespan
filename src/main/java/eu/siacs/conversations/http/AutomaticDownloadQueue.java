package eu.siacs.conversations.http;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Bounded automatic work only. Callbacks and HTTP work never run under the queue monitor. */
final class AutomaticDownloadQueue {
    private final Executor executor;
    private final int parallelism;
    private final int capacity;
    private final Map<Object, Job> jobs = new HashMap<>();
    private final ArrayDeque<Job> pending = new ArrayDeque<>();
    private int running;

    AutomaticDownloadQueue(Executor executor, int parallelism, int capacity) {
        if (parallelism <= 0 || capacity < 0) throw new IllegalArgumentException();
        this.executor = executor;
        this.parallelism = parallelism;
        this.capacity = capacity;
    }

    void submit(Object key, Runnable work, Runnable deferred) {
        final Job job = new Job(key, work, deferred);
        Job evicted = null;
        boolean dispatch = false;
        synchronized (this) {
            if (jobs.containsKey(key)) return;
            if (running < parallelism) {
                running++;
                job.running = true;
                jobs.put(key, job);
                dispatch = true;
            } else if (capacity == 0) {
                evicted = job;
            } else {
                if (pending.size() == capacity) {
                    evicted = pending.removeLast();
                    jobs.remove(evicted.key);
                }
                jobs.put(key, job);
                pending.addFirst(job);
            }
        }
        if (evicted != null) evicted.deferred.run();
        if (dispatch) dispatch(job);
    }

    /** Remove waiting work for manual promotion/cancel. Running work retains its slot until exit. */
    synchronized boolean removePending(Object key) {
        final Job job = jobs.get(key);
        if (job == null || job.running) return false;
        pending.remove(job);
        jobs.remove(key);
        return true;
    }

    synchronized int pendingCount() { return pending.size(); }
    synchronized int runningCount() { return running; }

    private void dispatch(Job job) {
        try {
            executor.execute(() -> {
                try {
                    job.work.run();
                } finally {
                    complete(job);
                }
            });
        } catch (RejectedExecutionException rejected) {
            try { job.deferred.run(); } finally { complete(job); }
        }
    }

    private void complete(Job finished) {
        final Job next;
        synchronized (this) {
            jobs.remove(finished.key);
            running--;
            next = pending.pollFirst();
            if (next != null) {
                next.running = true;
                running++;
            }
        }
        if (next != null) dispatch(next);
    }

    private static final class Job {
        final Object key;
        final Runnable work;
        final Runnable deferred;
        boolean running;
        Job(Object key, Runnable work, Runnable deferred) {
            this.key = key;
            this.work = work;
            this.deferred = deferred;
        }
    }
}
