package eu.siacs.conversations.ui.util;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ownership of one UI pagination gate, shared across fragment instances. */
public final class HistoryPageRequest<T> {
    // Values must not reference the request or the weak key. In particular, no UI object is
    // retained here while a query is waiting for the database or the server.
    private static final Map<AtomicBoolean, Object> OWNERS = new WeakHashMap<>();

    private final AtomicBoolean gate;
    private final Object token = new Object();
    private final WeakReference<T> target;
    private final WeakReference<Object> activity;
    private final WeakReference<Object> view;
    private final Object session;

    private HistoryPageRequest(
            AtomicBoolean gate, T target, Object activity, Object view, Object session) {
        this.gate = gate;
        this.target = new WeakReference<>(target);
        this.activity = new WeakReference<>(activity);
        this.view = new WeakReference<>(view);
        this.session = session;
    }

    /** session must be an opaque token, never an Activity, View or Fragment. */
    public static <T> HistoryPageRequest<T> tryStart(
            AtomicBoolean gate, T target, Object activity, Object view, Object session) {
        if (target == null || activity == null || view == null || session == null) {
            return null;
        }
        synchronized (OWNERS) {
            if (OWNERS.containsKey(gate) || !gate.compareAndSet(true, false)) {
                return null;
            }
            final HistoryPageRequest<T> request =
                    new HistoryPageRequest<>(gate, target, activity, view, session);
            OWNERS.put(gate, request.token);
            return request;
        }
    }

    /** Preserve an in-flight page when reInit runs on the same or a replacement fragment. */
    public static void resetIfIdle(AtomicBoolean gate) {
        synchronized (OWNERS) {
            if (!OWNERS.containsKey(gate)) {
                gate.set(true);
            }
        }
    }

    public T target() {
        return target.get();
    }

    public boolean matches(T target, Object activity, Object view, Object session) {
        return target != null
                && activity != null
                && view != null
                && this.target.get() == target
                && this.activity.get() == activity
                && this.view.get() == view
                && this.session == session
                && isCurrent();
    }

    public boolean isCurrent() {
        synchronized (OWNERS) {
            return OWNERS.get(gate) == token;
        }
    }

    /** Discarding/publishing an intermediate page must not complete the running MAM request. */
    public void finishPublication(boolean terminal) {
        if (terminal) finish();
    }

    /** Idempotent: an old or duplicate completion cannot release a newer page's gate. */
    public void finish() {
        synchronized (OWNERS) {
            if (OWNERS.get(gate) == token) {
                OWNERS.remove(gate);
                gate.set(true);
            }
        }
    }
}
