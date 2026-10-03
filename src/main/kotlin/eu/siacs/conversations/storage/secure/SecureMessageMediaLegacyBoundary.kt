package eu.siacs.conversations.storage.secure

import java.io.IOException

/**
 * Explicit guard for transitional call sites that can still reach FileBackend.
 *
 * Legacy media access is permitted only when no Secure Content media relation exists for the
 * message. A committed relation blocks legacy access; ambiguous or incomplete relations are
 * propagated as failures by [SecureMessageMediaCoordinator] and must never be interpreted as a
 * reason to fall back to plaintext storage.
 */
class SecureMessageMediaLegacyBoundary(
    store: SecureContentStore,
) {
    private val coordinator = SecureMessageMediaCoordinator(store)

    @Throws(IOException::class)
    fun requireLegacyAllowed(accountUuid: String, messageUuid: String) {
        if (coordinator.resolve(accountUuid, messageUuid) != null) {
            throw IOException("Legacy FileBackend is forbidden for secure media relation")
        }
    }

    @Throws(IOException::class)
    fun isSecure(accountUuid: String, messageUuid: String): Boolean =
        coordinator.resolve(accountUuid, messageUuid) != null
}
