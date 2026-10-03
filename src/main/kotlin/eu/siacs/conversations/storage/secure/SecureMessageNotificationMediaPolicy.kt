package eu.siacs.conversations.storage.secure

import java.io.IOException

/**
 * Fail-closed notification policy for message-owned media during secure-storage rollout.
 *
 * Notifications are allowed to use the legacy media URI only when no secure relation exists. A
 * committed secure relation must never be reopened through FileBackend merely to attach a rich
 * notification image. Until NotificationService has a dedicated verified bitmap/URI consumer,
 * secure media is intentionally represented by the normal text/file preview instead of plaintext
 * attachment storage.
 */
class SecureMessageNotificationMediaPolicy(store: SecureContentStore) {
    private val coordinator = SecureMessageMediaCoordinator(store)

    enum class Decision {
        LEGACY_ALLOWED,
        SUPPRESS_LEGACY_MEDIA,
    }

    @Throws(IOException::class)
    fun decide(accountUuid: String, messageUuid: String): Decision =
        if (coordinator.resolve(accountUuid, messageUuid) == null) {
            Decision.LEGACY_ALLOWED
        } else {
            Decision.SUPPRESS_LEGACY_MEDIA
        }
}
