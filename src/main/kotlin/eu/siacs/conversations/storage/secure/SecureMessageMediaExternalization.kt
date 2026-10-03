package eu.siacs.conversations.storage.secure

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.IOException
import java.io.OutputStream

/**
 * Explicit VIEW/SAVE boundaries for committed secure message media.
 *
 * VIEW uses the already-controlled temporary FileProvider export. SAVE streams verified plaintext
 * directly to a caller-owned external sink; it never creates an attachment-storage plaintext file.
 * A null result means no secure relation exists and is the only state in which rollout code may
 * consult legacy storage. Secure relation/read failures throw and therefore fail closed.
 */
class SecureMessageMediaExternalization(
    context: Context,
    store: SecureContentStore,
) {
    private val applicationContext = context.applicationContext
    private val coordinator = SecureMessageMediaCoordinator(store)
    private val reader = SecureMessageMediaReader(coordinator)
    private val exporter = AndroidSecureMessageMediaExporter(applicationContext, store)

    @Throws(IOException::class)
    fun prepareViewIntent(
        accountUuid: String,
        messageUuid: String,
        mimeType: String?,
    ): Intent? {
        val resolvedMimeType =
            coordinator.resolveMetadata(accountUuid, messageUuid)?.mimeType ?: mimeType
        val export = exporter.prepare(
            accountUuid,
            messageUuid,
            ContentExportOperation.VIEW,
        ) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(export.uri, resolvedMimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Throws(IOException::class)
    fun metadata(
        accountUuid: String,
        messageUuid: String,
    ): SecureContentMetadata? = coordinator.resolveMetadata(accountUuid, messageUuid)

    /**
     * Copies one secure object to an already-authorized user-requested destination.
     * [openDestination] is invoked only after an exact committed secure relation is resolved.
     */
    @Throws(IOException::class)
    fun save(
        accountUuid: String,
        messageUuid: String,
        openDestination: () -> OutputStream,
    ): Boolean {
        val binding = coordinator.resolve(accountUuid, messageUuid) ?: return false
        try {
            openDestination().use { output -> reader.copyVerified(binding, output) }
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException("Secure media save failed", error)
        }
        return true
    }

    /** Explicit cleanup hook for expired VIEW/SHARE exports. */
    fun sweepExpiredExports() {
        exporter.sweepExpired()
    }
}
