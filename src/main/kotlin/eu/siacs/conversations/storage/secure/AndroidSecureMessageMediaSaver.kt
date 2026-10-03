package eu.siacs.conversations.storage.secure

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI

/**
 * Service-level SAVE boundary for committed secure message media.
 *
 * Returns false only when no secure relation exists, which is the sole state where callers may
 * consult legacy attachment storage. A known secure relation always saves through verified reads
 * and fails closed on any read/write error.
 */
class AndroidSecureMessageMediaSaver(
    context: Context,
    store: SecureContentStore,
) {
    private val applicationContext = context.applicationContext
    private val externalization = SecureMessageMediaExternalization(applicationContext, store)

    enum class Destination {
        DOWNLOADS,
        GALLERY,
    }

    @Throws(IOException::class)
    fun save(message: Message): Boolean = save(message, Destination.DOWNLOADS)

    @Throws(IOException::class)
    fun saveToGallery(message: Message): Boolean = save(message, Destination.GALLERY)

    @Throws(IOException::class)
    private fun save(
        message: Message,
        destination: Destination,
    ): Boolean {
        val accountUuid = message.conversation.account.uuid
        val messageUuid = message.uuid
        val metadata = externalization.metadata(accountUuid, messageUuid)
        val mimeType = metadata?.mimeType ?: message.mimeType
        val filename = safeFilename(message, metadata?.fileName)
        if (destination == Destination.GALLERY && !isGalleryMime(mimeType)) {
            throw IOException("Attachment is not gallery media")
        }
        var destinationFile: File? = null
        var pendingMediaStoreUri: Uri? = null

        try {
            val saved =
                externalization.save(accountUuid, messageUuid) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val uri = createPendingDestination(filename, mimeType, destination)
                        pendingMediaStoreUri = uri
                        applicationContext.contentResolver.openOutputStream(uri, "w")
                            ?: throw IOException("Unable to open MediaStore destination")
                    } else {
                        val target = uniqueDestinationFile(filename, mimeType, destination)
                        destinationFile = target
                        target.parentFile?.mkdirs()
                        FileOutputStream(target)
                    }
                }
            if (!saved) {
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri =
                    pendingMediaStoreUri
                        ?: throw IOException("Secure media MediaStore destination missing")
                publishPendingDestination(uri)
                pendingMediaStoreUri = null
            } else {
                val output =
                    destinationFile ?: throw IOException("Secure media save destination missing")
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(output.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
            }
            return true
        } catch (error: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pendingMediaStoreUri?.let { uri ->
                    try {
                        applicationContext.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                        // Best effort cleanup of unpublished destination.
                    }
                }
            } else {
                destinationFile?.delete()
            }
            throw if (error is IOException) error else IOException("Secure media save failed", error)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createPendingDestination(
        filename: String,
        mimeType: String?,
        destination: Destination,
    ): Uri {
        val appName = applicationContext.getString(R.string.app_name)
        val (collection, relativePath) =
            when {
                destination == Destination.GALLERY && mimeType?.startsWith("image/") == true ->
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                        Environment.DIRECTORY_PICTURES + "/" + appName
                destination == Destination.GALLERY && mimeType?.startsWith("video/") == true ->
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
                        Environment.DIRECTORY_MOVIES + "/" + appName
                else ->
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                if (!mimeType.isNullOrBlank()) {
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return applicationContext.contentResolver.insert(collection, values)
            ?: throw IOException("Unable to create MediaStore destination")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishPendingDestination(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (applicationContext.contentResolver.update(uri, values, null, null) <= 0) {
            throw IOException("Unable to publish MediaStore destination")
        }
    }

    private fun uniqueDestinationFile(
        filename: String,
        mimeType: String?,
        destination: Destination,
    ): File {
        val publicDirectory =
            when {
                destination == Destination.GALLERY && mimeType?.startsWith("video/") == true ->
                    Environment.DIRECTORY_MOVIES
                destination == Destination.GALLERY ->
                    Environment.DIRECTORY_PICTURES
                else ->
                    Environment.DIRECTORY_DOWNLOADS
            }
        val root = Environment.getExternalStoragePublicDirectory(publicDirectory)
        val parent =
            if (destination == Destination.GALLERY) {
                File(root, applicationContext.getString(R.string.app_name))
            } else {
                root
            }
        var output = File(parent, filename)
        var counter = 1
        while (output.exists()) {
            output = File(parent, "($counter) $filename")
            counter++
        }
        return output
    }

    private fun isGalleryMime(mimeType: String?): Boolean =
        mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true

    private fun safeFilename(
        message: Message,
        secureFileName: String? = null,
    ): String {
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
