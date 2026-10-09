package eu.siacs.conversations.services;

import eu.siacs.conversations.persistance.DatabaseBackend;
import java.util.List;

/**
 * XEP-0424 SCS recovery ordering.
 * Not connected to application startup yet.
 */
final class MucRetractionRetirement {

    interface ContentRetirer {
        void retire(String accountUuid, String messageUuid)
                throws Exception;
    }

    interface CacheInvalidator {
        void invalidate(String accountUuid, String messageUuid)
                throws Exception;
    }

    interface DurableMarker {
        boolean mark(
                DatabaseBackend.PendingRetractionRetirement job)
                throws Exception;
    }

    private MucRetractionRetirement() {}

    static int recoverPending(
            final List<DatabaseBackend.PendingRetractionRetirement> jobs,
            final ContentRetirer retirer,
            final CacheInvalidator invalidator,
            final DurableMarker marker) {
        int completed = 0;

        for (final DatabaseBackend.PendingRetractionRetirement job : jobs) {
            if (job == null) {
                continue;
            }

            try {
                // Must complete before updating the durable state.
                retirer.retire(job.accountUuid, job.messageUuid);
                invalidator.invalidate(
                        job.accountUuid, job.messageUuid);

                if (!marker.mark(job)) {
                    // Do not acknowledge incomplete SQL changes.
                    break;
                }

                completed++;
            } catch (final Exception failure) {
                // Leave the job pending for a later retry.
                break;
            }
        }

        return completed;
    }
}
