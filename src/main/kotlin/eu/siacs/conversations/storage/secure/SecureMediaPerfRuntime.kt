package eu.siacs.conversations.storage.secure

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Android persistence bridge for Secure Media performance traces.
 *
 * The cached report contains only the sanitized text emitted by SecureMediaPerfTrace.
 */
object SecureMediaPerfRuntime {
    private const val DIRECTORY = "secure_media_perf"
    private const val FILE_NAME = "latest.txt"

    private val persistenceExecutor = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun initialize(context: Context) {
        val app = context.applicationContext
        SecureMediaPerfTrace.installSnapshotListener { snapshot ->
            persistenceExecutor.execute {
                persist(app, snapshot)
            }
        }
    }

    @JvmStatic
    fun report(context: Context): String {
        if (SecureMediaPerfTrace.hasRecords()) {
            return SecureMediaPerfTrace.report()
        }
        return try {
            reportFile(context).takeIf { it.isFile }?.readText()
                ?: SecureMediaPerfTrace.report()
        } catch (_: IOException) {
            SecureMediaPerfTrace.report()
        }
    }

    @JvmStatic
    fun clear(context: Context) {
        SecureMediaPerfTrace.clear()
        try {
            reportFile(context).delete()
        } catch (_: Exception) {
            // Cache cleanup is best effort only.
        }
    }

    private fun persist(context: Context, snapshot: String) {
        try {
            val file = reportFile(context)
            file.parentFile?.mkdirs()
            file.writeText(snapshot)
        } catch (_: IOException) {
            // Diagnostics must never affect message/media behavior.
        }
    }

    private fun reportFile(context: Context): File =
        File(File(context.applicationContext.cacheDir, DIRECTORY), FILE_NAME)
}
