package eu.siacs.conversations.storage.secure

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import eu.siacs.conversations.Config
import eu.siacs.conversations.Conversations
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.XmppActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * UI-facing SAVE boundary for secure message media.
 *
 * Secure relation resolution and verified copying happen on an I/O worker. Legacy saving is only
 * allowed when no secure relation exists. Any secure relation/read/write failure fails closed.
 */
object SecureMessageMediaSaveBridge {
    private val ioExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "secure-media-save").apply { isDaemon = true }
        }

    interface Callback {
        fun success()

        fun error(@StringRes errorCode: Int)
    }

    @JvmStatic
    fun saveWithStandardFeedbackOrFallback(
        activity: XmppActivity,
        message: Message,
        legacyFallback: Runnable,
    ) {
        saveOrFallback(
            activity,
            message,
            legacyFallback,
            object : Callback {
                override fun success() {
                    Toast.makeText(
                            activity,
                            R.string.save_to_downloads_success,
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }

                override fun error(errorCode: Int) {
                    Toast.makeText(activity, errorCode, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    @JvmStatic
    fun saveOrFallback(
        activity: XmppActivity,
        message: Message,
        legacyFallback: Runnable,
        callback: Callback,
    ) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            legacyFallback.run()
            return
        }
        val accountUuid = message.conversation.account.uuid
        val messageUuid = message.uuid
        ioExecutor.execute {
            var destinationFile: File? = null
            var pendingMediaStoreUri: Uri? = null
            try {
                val application = activity.application as Conversations
                val externalization =
                    SecureMessageMediaExternalization(
                        activity,
                        application.secureContentStoreProvider.get(),
                    )
                val metadata = externalization.metadata(accountUuid, messageUuid)
                val mimeType = metadata?.mimeType ?: message.mimeType
                val filename = safeFilename(message, metadata?.fileName)
                val saved =
                    externalization.save(accountUuid, messageUuid) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val uri = createPendingDownload(activity, filename, mimeType)
                            pendingMediaStoreUri = uri
                            activity.contentResolver.openOutputStream(uri, "w")
                                ?: throw IOException("Unable to open MediaStore download destination")
                        } else {
                            val target = uniqueDownloadFile(filename)
                            destinationFile = target
                            target.parentFile?.mkdirs()
                            FileOutputStream(target)
                        }
                    }
                if (!saved) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            legacyFallback.run()
                        }
                    }
                    return@execute
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri =
                        pendingMediaStoreUri
                            ?: throw IOException("Secure media MediaStore destination missing")
                    publishPendingDownload(activity, uri)
                    pendingMediaStoreUri = null
                } else {
                    val output =
                        destinationFile ?: throw IOException("Secure media save destination missing")
                    MediaScannerConnection.scanFile(
                        activity.applicationContext,
                        arrayOf(output.absolutePath),
                        arrayOf(mimeType),
                        null,
                    )
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        callback.success()
                    }
                }
            } catch (error: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    pendingMediaStoreUri?.let { uri ->
                        try {
                            activity.contentResolver.delete(uri, null, null)
                        } catch (cleanupError: Exception) {
                            Log.w(Config.LOGTAG, "unable to clean failed secure MediaStore save", cleanupError)
                        }
                    }
                } else {
                    destinationFile?.delete()
                }
                Log.w(Config.LOGTAG, "unable to save secure media", error)
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        callback.error(R.string.error_io_exception)
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPendingDownload(
        activity: XmppActivity,
        filename: String,
        mimeType: String?,
    ): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                if (!mimeType.isNullOrBlank()) {
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create MediaStore download destination")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishPendingDownload(activity: XmppActivity, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (activity.contentResolver.update(uri, values, null, null) <= 0) {
            throw IOException("Unable to publish MediaStore download")
        }
    }

    private fun uniqueDownloadFile(filename: String): File {
        val parent = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var output = File(parent, filename)
        var counter = 1
        while (output.exists()) {
            output = File(parent, "($counter) $filename")
            counter++
        }
        return output
    }

    private fun safeFilename(message: Message, secureFileName: String?): String {
        val fromSecure =
            secureFileName
                ?.takeIf { it.isNotBlank() }
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
        val fromLocal =
            message.relativeFilePath
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.name
                ?.takeIf { it.isNotBlank() }
        val fromRemote =
            message.fileParams.url
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    try {
                        URI(it).path?.substringAfterLast('/')
                    } catch (_: Exception) {
                        null
                    }
                }
                ?.takeIf { it.isNotBlank() }
        val candidate = fromSecure ?: fromLocal ?: fromRemote ?: message.uuid
        return candidate
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "_")
            .takeIf { it.isNotBlank() }
            ?: message.uuid
    }
}
