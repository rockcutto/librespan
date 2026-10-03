package eu.siacs.conversations.storage.secure

import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Transitional publication boundary for completed incoming media.
 *
 * HTTP/Jingle receive paths may still finish into a legacy plaintext file while rollout is being
 * connected. This boundary turns that completed file into the durable secure message-media
 * relation and retires the plaintext only after the secure commit is reader-visible.
 *
 * Publication and retirement are intentionally separate so callers can rebuild metadata or run
 * other verified secure-read consumers between the durable commit and plaintext deletion.
 *
 * The operation is restart-safe: if publication committed before a crash but plaintext retirement
 * did not, a later retry observes the committed relation and only completes retirement. A failed
 * secure publication never authorizes deletion of the legacy source.
 */
class SecureIncomingLegacyMediaPublication(
    store: SecureContentStore,
) {
    private val coordinator = SecureMessageMediaCoordinator(store)

    /**
     * Publishes [legacyFile] when no secure relation exists and leaves the legacy plaintext in
     * place until [retireIfPublished] is called.
     */
    @Throws(IOException::class)
    fun publish(
        accountUuid: String,
        messageUuid: String,
        mimeType: String?,
        legacyFile: File,
    ) = publish(accountUuid, messageUuid, mimeType, null, legacyFile)

    @Throws(IOException::class)
    fun publish(
        accountUuid: String,
        messageUuid: String,
        mimeType: String?,
        fileName: String?,
        legacyFile: File,
    ) {
        if (coordinator.resolve(accountUuid, messageUuid) != null) {
            return
        }
        if (!legacyFile.exists() || !legacyFile.isFile) {
            throw IOException("Incoming legacy media source is unavailable")
        }
        val size = legacyFile.length()
        FileInputStream(legacyFile).use { source ->
            coordinator.stageNew(
                accountUuid = accountUuid,
                messageUuid = messageUuid,
                mimeType = mimeType,
                fileName = fileName,
                expectedSizeBytes = size,
                source = source,
            )
        }
        if (coordinator.resolve(accountUuid, messageUuid) == null) {
            throw IOException("Secure incoming media publication did not commit")
        }
    }

    /**
     * Compatibility helper for callers that do not require secure-read work before retirement.
     */
    @Throws(IOException::class)
    fun publishAndRetire(
        accountUuid: String,
        messageUuid: String,
        mimeType: String?,
        legacyFile: File,
    ) {
        publish(accountUuid, messageUuid, mimeType, legacyFile)
        if (!retireIfPublished(accountUuid, messageUuid, legacyFile)) {
            throw IOException("Secure incoming media publication did not commit")
        }
    }

    /**
     * Completes plaintext retirement for an already committed relation.
     *
     * @return true when a committed relation exists, false when the caller must still receive and
     * publish the media.
     */
    @Throws(IOException::class)
    fun retireIfPublished(
        accountUuid: String,
        messageUuid: String,
        legacyFile: File,
    ): Boolean {
        coordinator.resolve(accountUuid, messageUuid) ?: return false
        if (!legacyFile.exists()) {
            return true
        }
        if (!legacyFile.delete() && legacyFile.exists()) {
            throw IOException("Unable to retire incoming legacy media after secure publication")
        }
        return true
    }
}
