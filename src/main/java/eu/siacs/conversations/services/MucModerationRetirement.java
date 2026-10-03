package eu.siacs.conversations.services;

import java.util.List;

/** Deterministic retirement/recovery semantics for XEP-0425 moderated content. */
final class MucModerationRetirement {

    interface ContentRetirer {
        void retire(String accountUuid, String messageUuid) throws Exception;
    }

    interface CacheInvalidator {
        void invalidate(String accountUuid, String messageUuid) throws Exception;
    }

    interface RetiredMarker {
        void mark(String accountUuid, String messageUuid);
    }

    private MucModerationRetirement() {}

    static boolean retireNow(
            final String accountUuid,
            final String messageUuid,
            final ContentRetirer retirer,
            final CacheInvalidator invalidator) {
        boolean contentRetired = false;
        boolean cacheInvalidated = false;
        try {
            retirer.retire(accountUuid, messageUuid);
            contentRetired = true;
        } catch (final Exception ignored) {
            // Keep the durable retirement pending, but still scrub any resident cache below.
        }
        try {
            invalidator.invalidate(accountUuid, messageUuid);
            cacheInvalidated = true;
        } catch (final Exception ignored) {
            // Recovery retries both idempotent boundaries before marking retirement complete.
        }
        return contentRetired && cacheInvalidated;
    }

    static int recoverPending(
            final List<String[]> pending,
            final ContentRetirer retirer,
            final CacheInvalidator invalidator,
            final RetiredMarker marker) {
        int recovered = 0;
        for (final String[] entry : pending) {
            if (entry == null || entry.length < 2) {
                continue;
            }
            try {
                retirer.retire(entry[0], entry[1]);
                invalidator.invalidate(entry[0], entry[1]);
                marker.mark(entry[0], entry[1]);
                recovered++;
            } catch (final Exception ignored) {
                break;
            }
        }
        return recovered;
    }
}
