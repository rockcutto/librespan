package eu.siacs.conversations.storage.secure

import android.content.Context
import android.net.Uri
import eu.siacs.conversations.persistance.FileBackend
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Short-lived app-internal plaintext cache for consumers that require seekable Android media.
 *
 * The cache is explicit and leased: it is populated only through terminally verified Store reads,
 * is never treated as attachment storage, and is deleted when the consumer closes the lease.
 * Leftovers from process death are swept opportunistically before every new lease.
 */
class AndroidSecureMessageMediaReadCache @JvmOverloads constructor(
    context: Context,
    store: SecureContentStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val applicationContext = context.applicationContext
    private val coordinator = SecureMessageMediaCoordinator(store)
    private val reader = SecureMessageMediaReader(coordinator)
    private val cacheDirectory = File(applicationContext.cacheDir, CACHE_DIRECTORY)

    @Throws(IOException::class)
    fun acquire(accountUuid: String, messageUuid: String): Lease? {
        val binding = coordinator.resolve(accountUuid, messageUuid) ?: return null
        sweepOrphans()
        val accountDirectory = accountDirectory(accountUuid)
        if (!accountDirectory.exists() && !accountDirectory.mkdirs()) {
            throw IOException("Unable to create secure media read cache")
        }
        val file = File(accountDirectory, UUID.randomUUID().toString())
        try {
            FileOutputStream(file).use { output -> reader.copyVerified(binding, output) }
            val now = clock()
            file.setLastModified(now)
            return Lease(FileBackend.getUriForFile(applicationContext, file), file)
        } catch (error: Exception) {
            file.delete()
            throw if (error is IOException) error else IOException("Secure media cache read failed", error)
        }
    }

    fun sweepOrphans() {
        val now = clock()
        cacheDirectory.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                child.listFiles()?.forEach { file ->
                    if (!file.isFile || now - file.lastModified() >= ORPHAN_TTL_MILLIS) {
                        file.delete()
                    }
                }
                child.delete()
            } else if (now - child.lastModified() >= ORPHAN_TTL_MILLIS) {
                // Legacy unscoped entries cannot safely be attributed to one account.
                child.delete()
            }
        }
    }

    fun clearAccount(accountUuid: String) {
        val directory = accountDirectory(accountUuid)
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    private fun accountDirectory(accountUuid: String): File {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountUuid.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
        return File(cacheDirectory, digest)
    }

    class Lease internal constructor(
        val uri: Uri,
        private val file: File,
    ) : AutoCloseable {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            file.delete()
        }
    }

    companion object {
        const val CACHE_DIRECTORY = "SecureMediaReadCache"
        const val ORPHAN_TTL_MILLIS = 60L * 60L * 1000L
    }
}
