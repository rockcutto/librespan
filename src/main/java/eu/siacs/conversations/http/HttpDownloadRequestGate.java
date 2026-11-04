package eu.siacs.conversations.http;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Re-evaluated at submission and every network boundary, including redirect/retry requests. */
final class HttpDownloadRequestGate {
    private final BooleanSupplier active;
    private final BooleanSupplier automaticAllowed;

    HttpDownloadRequestGate(BooleanSupplier active, BooleanSupplier automaticAllowed) {
        this.active = active;
        this.automaticAllowed = automaticAllowed;
    }

    void check(boolean interactive) throws Deferred {
        if (!active.getAsBoolean() || (!interactive && !automaticAllowed.getAsBoolean())) {
            throw new Deferred();
        }
    }

    <T> T execute(boolean interactive, Request<T> request) throws IOException {
        check(interactive);
        return request.execute();
    }

    static boolean withinAutoLimit(long wireSize, long configuredLimit, boolean audio) {
        return wireSize >= 0 && ((configuredLimit > 0 && wireSize <= configuredLimit)
                || (audio && wireSize <= 16L * 1024L * 1024L));
    }

    interface Request<T> { T execute() throws IOException; }
    static final class Deferred extends IOException {
        private static final long serialVersionUID = 1L;
        Deferred() { super("HTTP download deferred by lifecycle or automatic network policy"); }
    }
}
