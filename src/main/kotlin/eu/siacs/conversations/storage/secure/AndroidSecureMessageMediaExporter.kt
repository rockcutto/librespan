package eu.siacs.conversations.storage.secure

import android.content.Context
import android.net.Uri
import eu.siacs.conversations.persistance.FileBackend
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Android adapter for explicit temporary externalization of message-owned secure media.
 *
 * Plaintext exists only in the app cache export directory and is exposed through FileProvider.
 * Files are never treated as attachment storage and are opportunistically retired after a short
 * lease. New exports also sweep expired predecessors, including leftovers from process death.
 */
class AndroidSecureMessageMediaExporter @JvmOverloads constructor(
    context: Context,
    store: SecureContentStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val applicationContext = context.applicationContext
    private val coordinator = SecureMessageMediaCoordinator(store)
    private val reader = SecureMessageMediaReader(coordinator)
    private val exportDirectory = File(applicationContext.cacheDir, EXPORT_DIRECTORY)

    @Throws(IOException::class)
    fun prepare(
        accountUuid: String,
        messageUuid: String,
        operation: ContentExportOperation,
    ): AndroidSecureContentExport? {
        val binding = coordinator.resolve(accountUuid, messageUuid) ?: return null
        val metadata =
            coordinator.resolveMetadata(accountUuid, messageUuid)
                ?: throw IOException("Secure media metadata is unavailable")
        sweepExpired()
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            throw IOException("Unable to create secure content export directory")
        }

        val exportScope = File(exportDirectory, UUID.randomUUID().toString())
        if (!exportScope.mkdirs()) {
            throw IOException("Unable to create secure content export scope")
        }
        val exportFile =
            File(
                exportScope,
                SecureContentExportFileNaming.resolve(metadata, messageUuid),
            )
        try {
            FileOutputStream(exportFile).use { output ->
                reader.copyVerified(binding, output)
            }
            val now = clock()
            if (!exportFile.setLastModified(now)) {
                // Timestamp is only cleanup metadata; inability to set it is not an auth failure.
            }
            if (!exportScope.setLastModified(now)) {
                // The scope timestamp drives opportunistic cleanup after process death.
            }
            return AndroidSecureContentExport(
                operation = operation,
                uri = FileBackend.getUriForFile(applicationContext, exportFile),
                expiresAtMillis = now + EXPORT_TTL_MILLIS,
            )
        } catch (error: Exception) {
            exportScope.deleteRecursively()
            throw if (error is IOException) error else IOException("Secure media export failed", error)
        }
    }

    fun sweepExpired() {
        val now = clock()
        exportDirectory.listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                if (now - entry.lastModified() >= EXPORT_TTL_MILLIS) {
                    entry.deleteRecursively()
                }
            } else if (!entry.isFile || now - entry.lastModified() >= EXPORT_TTL_MILLIS) {
                // Compatibility cleanup for exports created before per-export scopes were added.
                entry.delete()
            }
        }
    }

    companion object {
        const val EXPORT_DIRECTORY = "SecureContentExports"
        const val EXPORT_TTL_MILLIS = 15L * 60L * 1000L
    }
}

data class AndroidSecureContentExport(
    override val operation: ContentExportOperation,
    val uri: Uri,
    val expiresAtMillis: Long,
) : ContentExport
