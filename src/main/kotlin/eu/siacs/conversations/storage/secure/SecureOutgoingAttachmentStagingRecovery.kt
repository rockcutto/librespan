package eu.siacs.conversations.storage.secure

import android.content.Context
import java.io.File

/**
 * Best-effort process-restart cleanup for bounded app-private producer staging.
 *
 * These files are never attachment authority. Normal publication retires them immediately; this
 * sweeper only removes stale regular-file leftovers and never examines legacy/public storage or
 * an external provider URI. A failed delete leaves the artifact private and cannot enable a
 * plaintext send fallback.
 */
object SecureOutgoingAttachmentStagingRecovery {
    const val ORPHAN_TTL_MILLIS = 60L * 60L * 1000L

    private val directories = listOf(
        "VoiceRecordings",
        "SecureOutgoingImages",
        "SecureOutgoingVideos",
        "Camera",
    )

    @JvmStatic
    fun sweepOrphans(context: Context) {
        sweepOrphans(context.cacheDir)
    }

    @JvmStatic
    fun sweepOrphans(cacheDirectory: File, nowMillis: Long = System.currentTimeMillis()): Int =
        directories.sumOf { directoryName ->
            sweepDirectory(File(cacheDirectory, directoryName), nowMillis)
        }

    private fun sweepDirectory(directory: File, nowMillis: Long): Int =
        directory.listFiles()?.count { file ->
            file.isFile && nowMillis - file.lastModified() >= ORPHAN_TTL_MILLIS && file.delete()
        } ?: 0
}
