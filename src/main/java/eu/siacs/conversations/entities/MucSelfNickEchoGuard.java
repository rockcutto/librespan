package eu.siacs.conversations.entities;

import eu.siacs.conversations.xmpp.Jid;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived alias for the next self occupant JID, announced by a trusted MUC 110+303.
 * This is NOT proof of authorship on its own. Echo correlation also requires the exact
 * UUID of a message already sent locally in the same conversation.
 */
final class MucSelfNickEchoGuard {
    static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(20);

    private final LongSupplier clock;
    private Jid nextSelfFullJid;
    private long sinceNanos;

    MucSelfNickEchoGuard() {
        this(System::nanoTime);
    }

    // Injectable monotonic clock makes expiry testable without sleeping.
    MucSelfNickEchoGuard(final LongSupplier clock) {
        this.clock = clock;
    }

    synchronized void observe303(
            final Jid currentSelf, final Jid from, final Jid announcedNextSelf) {
        if (currentSelf == null || from == null || announcedNextSelf == null
                || !currentSelf.equals(from) || currentSelf.isBareJid()
                || announcedNextSelf.isBareJid() || announcedNextSelf.equals(currentSelf)
                || !announcedNextSelf.asBareJid().equals(currentSelf.asBareJid())) {
            return;
        }
        nextSelfFullJid = announcedNextSelf;
        sinceNanos = clock.getAsLong();
    }

    synchronized boolean matchesEcho(
            final Jid from, final String echoedId, final Predicate<String> isLocallySent) {
        if (nextSelfFullJid == null || from == null || echoedId == null
                || echoedId.isEmpty() || isLocallySent == null) {
            return false;
        }
        final long elapsed = clock.getAsLong() - sinceNanos;
        if (elapsed < 0 || elapsed > WINDOW_NANOS) {
            clear();
            return false;
        }
        return nextSelfFullJid.equals(from) && isLocallySent.test(echoedId);
    }

    synchronized void clear() {
        nextSelfFullJid = null;
        sinceNanos = 0L;
    }
}
