package eu.siacs.conversations.entities;

import java.util.UUID;
import java.util.concurrent.Future;

/** Transient correlation/lifetime of one room join. Contains no password or durable state. */
public final class MucJoinAttempt {
    private static final String PREFIX = "muc-join-";
    private final String presenceId = PREFIX + UUID.randomUUID();
    private boolean started;
    private boolean pending;
    private boolean sent;
    private boolean passwordSupplied;
    private boolean confirmed;
    private boolean catchupClaimed;
    private Future<?> timeout;

    public synchronized void begin() {
        started = true;
        pending = true;
    }

    public synchronized boolean isPending() { return pending; }
    public synchronized boolean hasStarted() { return started; }
    public String getPresenceId() { return presenceId; }

    public synchronized boolean markPresenceSent(final boolean passwordSupplied) {
        if (!pending || sent) return false;
        sent = true;
        this.passwordSupplied = passwordSupplied;
        return true;
    }

    public synchronized boolean acceptsResponse(final String id) {
        return pending && sent && presenceId.equals(id);
    }

    public synchronized boolean wasPasswordSupplied() { return passwordSupplied; }

    public synchronized void setTimeout(final Future<?> timeout) {
        if (pending) this.timeout = timeout;
        else timeout.cancel(false);
    }

    /** Only a server-confirmed self presence can authorize automatic room history. */
    public synchronized void confirm() {
        final boolean accepted = confirmed || (pending && sent);
        finish();
        confirmed = accepted;
    }

    public synchronized boolean claimCatchup() {
        if (!confirmed || catchupClaimed) return false;
        catchupClaimed = true;
        return true;
    }

    public synchronized void finish() {
        confirmed = false;
        pending = false;
        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
    }

    public static boolean isJoinPresenceId(final String id) {
        return id != null && id.startsWith(PREFIX);
    }
}
