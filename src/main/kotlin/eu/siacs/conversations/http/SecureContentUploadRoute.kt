package eu.siacs.conversations.http

import eu.siacs.conversations.storage.secure.SecureContentMetadata
import eu.siacs.conversations.storage.secure.SecureContentTransferBinding
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator
import eu.siacs.conversations.utils.MimeUtils
import java.io.IOException
import java.io.InputStream
import okhttp3.RequestBody

/**
 * HTTP-specific controlled route for one already-authorized Secure Content upload.
 *
 * Store at-rest protection and HTTP wire protection remain separate. The route opens only verified
 * Store readers; an optional per-message XEP-0454 adapter transforms their plaintext in memory and
 * never reuses Store key material.
 */
class SecureContentUploadRoute(
    private val gateway: SecureContentTransferGateway,
) {
    @Throws(IOException::class)
    fun stage(
        accountUuid: String,
        messageUuid: String,
        displayFilename: String,
        mimeType: String?,
        plaintextSizeBytes: Long,
        source: InputStream,
    ): SecureContentUploadDescriptor {
        HttpUploadSlotRequest.validateDisplayFilename(displayFilename)
        val existing =
            gateway.resolveUniqueForMessage(
                accountUuid = accountUuid,
                messageUuid = messageUuid,
                namespace = SecureMessageMediaCoordinator.NAMESPACE,
            )
        val binding =
            if (existing != null) {
                val snapshot = gateway.resolveForTransfer(existing)
                val existingSize = snapshot.metadata.sizeBytes
                if (existingSize != null && existingSize != plaintextSizeBytes) {
                    throw IOException("Existing secure media size does not match upload source")
                }
                val existingMime = snapshot.metadata.mimeType
                if (existingMime != null && mimeType != null && existingMime != mimeType) {
                    throw IOException("Existing secure media type does not match upload source")
                }
                existing
            } else {
                gateway.stageNewContent(
                    accountUuid = accountUuid,
                    messageUuid = messageUuid,
                    namespace = SecureMessageMediaCoordinator.NAMESPACE,
                    mimeType = mimeType,
                    fileName = displayFilename,
                    expectedSizeBytes = plaintextSizeBytes,
                    source = source,
                )
            }
        return descriptor(binding)
    }

    /**
     * Reuses one committed relation without consulting a FileBackend filename. Authenticated Store
     * metadata supplies the wire filename, MIME type and plaintext size.
     */
    @Throws(IOException::class)
    fun reuseExisting(
        accountUuid: String,
        messageUuid: String,
    ): SecureContentUploadDescriptor? {
        val binding =
            gateway.resolveUniqueForMessage(
                accountUuid = accountUuid,
                messageUuid = messageUuid,
                namespace = SecureMessageMediaCoordinator.NAMESPACE,
            ) ?: return null
        return descriptor(binding)
    }

    fun slotRequest(
        descriptor: SecureContentUploadDescriptor,
        expectedWireSizeBytes: Long,
    ): HttpUploadSlotRequest =
        HttpUploadSlotRequest(
            descriptor.displayFilename,
            descriptor.mimeType,
            expectedWireSizeBytes,
        )

    fun requestBody(
        descriptor: SecureContentUploadDescriptor,
        wireKey: ByteArray?,
        wireIv: ByteArray?,
        progressListener: SecureContentWireProgressListener,
    ): RequestBody {
        val protection =
            if (wireKey == null && wireIv == null) {
                null
            } else {
                SecureContentAesGcmWireProtection(
                    wireKey ?: throw IOException("Wire key is unavailable"),
                    wireIv ?: throw IOException("Wire IV is unavailable"),
                )
            }
        return SecureContentProgressRequestBody(
            SecureContentUploadRequestBody(gateway, descriptor, protection),
            progressListener,
        )
    }

    @Throws(IOException::class)
    private fun descriptor(binding: SecureContentTransferBinding): SecureContentUploadDescriptor {
        val snapshot = gateway.resolveForTransfer(binding)
        val plaintextSizeBytes =
            snapshot.metadata.sizeBytes
                ?: throw IOException("Existing secure media size is unavailable")
        if (plaintextSizeBytes <= 0L) {
            throw IOException("Existing secure media is empty")
        }
        return SecureContentUploadDescriptor(
            binding = binding,
            displayFilename = displayFilename(snapshot.metadata, binding.messageUuid),
            mimeType = snapshot.metadata.mimeType,
            plaintextSizeBytes = plaintextSizeBytes,
        )
    }

    private fun displayFilename(metadata: SecureContentMetadata, messageUuid: String): String {
        val stored =
            metadata.fileName
                ?.replace('\\', '/')
                ?.substringAfterLast('/')
                ?.replace(Regex("[\\x00-\\x1f\\x7f]"), "_")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        if (stored != null) {
            HttpUploadSlotRequest.validateDisplayFilename(stored)
            return stored
        }
        val extension = MimeUtils.guessExtensionFromMimeType(metadata.mimeType)
        val fallback =
            if (extension.isNullOrEmpty()) messageUuid else "$messageUuid.$extension"
        HttpUploadSlotRequest.validateDisplayFilename(fallback)
        return fallback
    }
}
