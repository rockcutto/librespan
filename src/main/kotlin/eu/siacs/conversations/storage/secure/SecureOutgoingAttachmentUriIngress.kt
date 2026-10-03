package eu.siacs.conversations.storage.secure

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * Android URI adapter for the first outgoing attachment migration stage.
 *
 * This adapter converts an external content source into the Secure Content ingress seam.
 * It does not create a FileBackend attachment, relative path or permanent plaintext copy.
 */
class SecureOutgoingAttachmentUriIngress(
    private val resolver: ContentResolver,
    private val ingress: SecureOutgoingAttachmentIngress,
) {

    @Throws(IOException::class)
    fun ingest(
        accountUuid: String,
        messageUuid: String,
        uri: Uri,
        mimeType: String? = null,
        expectedSizeBytes: Long? = null,
    ): SecureContentTransferBinding {
        val sourceOpenStarted = System.nanoTime()
        val input =
            resolver.openInputStream(uri)
                ?: throw IOException("Unable to open attachment source URI")
        SecureMediaPerfTrace.stage(
            messageUuid,
            "source_open",
            System.nanoTime() - sourceOpenStarted,
        )
        return input.use {
            val sourceMetadataStarted = System.nanoTime()
            val fileName = resolveFileName(uri)
            SecureMediaPerfTrace.stage(
                messageUuid,
                "source_metadata",
                System.nanoTime() - sourceMetadataStarted,
            )
            ingress.ingest(
                accountUuid = accountUuid,
                messageUuid = messageUuid,
                source = it,
                mimeType = mimeType,
                fileName = fileName,
                expectedSizeBytes = expectedSizeBytes,
            )
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        var candidate: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        candidate = cursor.getString(index)
                    }
                }
            } catch (_: Exception) {
                // A provider name is optional metadata; the readable URI remains authoritative.
            }
        }
        if (candidate.isNullOrBlank()) {
            candidate = uri.lastPathSegment
        }
        val basename = candidate
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.replace(Regex("[\\x00-\\x1f\\x7f]"), "_")
            ?.trim()
        return basename?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }
}
