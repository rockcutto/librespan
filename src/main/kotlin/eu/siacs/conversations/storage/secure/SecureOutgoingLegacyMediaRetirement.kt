package eu.siacs.conversations.storage.secure

import java.io.File
import java.io.IOException

/**
 * Final legacy-plaintext retirement boundary for outgoing media.
 *
 * The legacy source may be removed only after an exact committed secure media relation is the
 * durable source of truth. No relation means rollout has not published this message yet and the
 * legacy file must remain. Duplicate/incomplete secure relations fail closed through the media
 * coordinator and are never interpreted as permission to delete either copy.
 */
class SecureOutgoingLegacyMediaRetirement(store: SecureContentStore) {
    private val mediaCoordinator = SecureMessageMediaCoordinator(store)

    /**
     * @return true when a committed secure relation authorized legacy retirement, false when no
     * secure relation exists and the caller must keep using the legacy source during transition.
     */
    @Throws(IOException::class)
    fun retireIfSecurePublished(
        accountUuid: String,
        messageUuid: String,
        legacyFile: File,
    ): Boolean {
        mediaCoordinator.resolve(accountUuid, messageUuid) ?: return false
        if (!legacyFile.exists()) {
            return true
        }
        if (!legacyFile.delete() && legacyFile.exists()) {
            throw IOException("Unable to retire legacy media after secure publication")
        }
        return true
    }
}
