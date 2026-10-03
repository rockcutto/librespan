package eu.siacs.conversations.storage.secure

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException

/**
 * Application-facing preparation boundary for outgoing attachment migration.
 *
 * Producers move URI content through this boundary without learning Secure Content internals.
 * A retry reuses the exact committed message binding and never reopens a legacy plaintext source.
 */
class SecureOutgoingAttachmentPreparation(
    private val uriIngress: SecureOutgoingAttachmentUriIngress,
    private val mediaCoordinator: SecureMessageMediaCoordinator,
) {

    data class PreparedAttachment(
        val binding: SecureContentTransferBinding,
        val created: Boolean,
    )

    @Throws(IOException::class)
    fun prepareUriAttachment(
        accountUuid: String,
        messageUuid: String,
        uri: Uri,
        mimeType: String? = null,
        expectedSizeBytes: Long? = null,
    ): PreparedAttachment {
        SecureMediaPerfTrace.start(
            direction = "outgoing",
            messageUuid = messageUuid,
            mimeType = mimeType,
            sizeBytes = expectedSizeBytes,
        )
        val resolveStarted = System.nanoTime()
        mediaCoordinator.resolve(accountUuid, messageUuid)?.let {
            SecureMediaPerfTrace.stage(
                messageUuid,
                "resolve_existing",
                System.nanoTime() - resolveStarted,
            )
            return PreparedAttachment(it, created = false)
        }
        SecureMediaPerfTrace.stage(
            messageUuid,
            "resolve_existing",
            System.nanoTime() - resolveStarted,
        )
        return try {
            PreparedAttachment(
                uriIngress.ingest(
                    accountUuid = accountUuid,
                    messageUuid = messageUuid,
                    uri = uri,
                    mimeType = mimeType,
                    expectedSizeBytes = expectedSizeBytes,
                ),
                created = true,
            )
        } catch (error: IOException) {
            val committed = mediaCoordinator.resolve(accountUuid, messageUuid)
            if (committed != null) {
                PreparedAttachment(committed, created = false)
            } else {
                SecureMediaPerfTrace.fail(messageUuid)
                throw error
            }
        }
    }

    companion object {
        @JvmStatic
        fun create(
            resolver: ContentResolver,
            store: SecureContentStore,
        ): SecureOutgoingAttachmentPreparation {
            val coordinator = SecureMessageMediaCoordinator(store)
            return SecureOutgoingAttachmentPreparation(
                SecureOutgoingAttachmentUriIngress(
                    resolver,
                    SecureOutgoingAttachmentIngress(coordinator),
                ),
                coordinator,
            )
        }
    }
}
