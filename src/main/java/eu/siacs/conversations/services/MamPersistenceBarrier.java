package eu.siacs.conversations.services;

/**
 * One owner per MAM page, with sticky failure shared only by its paging/fallback chain.
 * Split ranges have independent chains; a new explicit retry/reconnect gets a fresh chain.
 * No I/O, retained payload, waiting, automatic retry or durable checkpoint is introduced.
 */
public final class MamPersistenceBarrier {
    private static final class Chain {
        boolean failed;
    }

    private final Chain chain;
    private int pending;
    private boolean sealed;

    public MamPersistenceBarrier() {
        this(new Chain());
    }

    private MamPersistenceBarrier(final Chain chain) {
        this.chain = chain;
    }

    public Result beginResult() {
        synchronized (chain) {
            if (sealed) return null; // Stale lookup may not start work after fin/cancel.
            ++pending;
            return new Result();
        }
    }

    public MamPersistenceBarrier nextPage() {
        return new MamPersistenceBarrier(chain);
    }

    /** Fin cannot confirm a page with unresolved work, even if a late writer later succeeds. */
    public boolean finishPage() {
        synchronized (chain) {
            sealed = true;
            if (pending != 0) chain.failed = true;
            return !chain.failed;
        }
    }

    public boolean canFallback() {
        synchronized (chain) {
            return pending == 0 && !chain.failed;
        }
    }

    public final class Result implements AutoCloseable {
        private boolean acknowledged;
        private boolean closed;

        /** true means a checked write, a verified duplicate, or an explicit policy/control skip. */
        public void complete(final boolean durableOrPolicySkip) {
            synchronized (chain) {
                if (closed) return;
                if (!durableOrPolicySkip) chain.failed = true;
                acknowledged |= durableOrPolicySkip;
            }
        }

        @Override
        public void close() {
            synchronized (chain) {
                if (closed) return;
                closed = true;
                if (!acknowledged) chain.failed = true;
                --pending;
            }
        }
    }
}
