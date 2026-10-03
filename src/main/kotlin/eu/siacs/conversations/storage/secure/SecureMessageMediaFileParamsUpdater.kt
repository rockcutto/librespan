package eu.siacs.conversations.storage.secure

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import eu.siacs.conversations.Config
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.utils.MimeUtils
import java.io.IOException

/**
 * Rebuilds message file parameters from a committed secure media object without consulting the
 * attachment's legacy storage path.
 *
 * Android decoders that require seekable input receive a short-lived leased cache URI. The lease
 * is deleted immediately after metadata extraction and is never promoted to attachment storage.
 */
class SecureMessageMediaFileParamsUpdater(
    context: Context,
    store: SecureContentStore,
) {
    private val applicationContext = context.applicationContext
    private val readCache = AndroidSecureMessageMediaReadCache(applicationContext, store)
    private val coordinator = SecureMessageMediaCoordinator(store)

    @Throws(IOException::class)
    fun update(message: Message, url: String?) {
        val accountUuid = message.conversation.account.uuid
        val metadata =
            try {
                val metadataStarted = System.nanoTime()
                val resolved =
                    coordinator.resolveMetadata(accountUuid, message.uuid)
                        ?: throw IOException(
                            "Secure media relation is unavailable for message ${message.uuid}",
                        )
                SecureMediaPerfTrace.stage(
                    message.uuid,
                    "resolve_metadata",
                    System.nanoTime() - metadataStarted,
                )
                resolved
            } catch (error: Exception) {
                SecureMediaPerfTrace.fail(message.uuid)
                throw error
            }

        // Canonical authenticated Store metadata is sufficient for the stable file card and MIME
        // routing. Publish it before any decoder/cache work so a failed best-effort enrichment can
        // never erase the filename, extension, MIME, byte size or basic image classification.
        val presentationFileName =
            MimeUtils.resolvePresentationFileName(
                metadata.fileName,
                url?.takeIf { it.isNotBlank() },
            ) ?: MimeUtils.resolvePresentationFileName(null, message.body)
        val presentationMime =
            MimeUtils.resolvePresentationMime(metadata.mimeType, presentationFileName)
        applyCanonicalPresentation(
            message,
            url,
            metadata,
            presentationMime,
            presentationFileName,
        )

        if (!needsPayloadEnrichment(message, presentationMime)) {
            SecureMediaPerfTrace.finish(message.uuid)
            return
        }

        try {
            val decryptStarted = System.nanoTime()
            val lease =
                readCache.acquire(accountUuid, message.uuid)
                    ?: throw IOException("Secure media read cache is unavailable")
            SecureMediaPerfTrace.stage(
                message.uuid,
                "post_commit_decrypt",
                System.nanoTime() - decryptStarted,
            )
            try {
                val extractionStarted = System.nanoTime()
                enrichFromUri(message, url, lease.uri, metadata, presentationMime)
                SecureMediaPerfTrace.stage(
                    message.uuid,
                    "file_params_metadata",
                    System.nanoTime() - extractionStarted,
                )
            } finally {
                lease.close()
            }
        } catch (_: Exception) {
            // Width/height/runtime are presentation enrichment only. A committed object with
            // authenticated MIME/name/size remains a successfully received attachment.
            Log.w(
                Config.LOGTAG,
                "secure media presentation enrichment failed; using committed metadata",
            )
        } finally {
            SecureMediaPerfTrace.finish(message.uuid)
        }
    }

    private fun applyCanonicalPresentation(
        message: Message,
        url: String?,
        metadata: SecureContentMetadata,
        presentationMime: String?,
        presentationFileName: String?,
    ) {
        // Authenticated Secure Content MIME is authoritative once it is available. The message
        // may have been provisionally created as TYPE_IMAGE before attachment inspection. Only a
        // generic application/* value may be recovered from the authenticated canonical filename.
        val image =
            if (!presentationMime.isNullOrBlank()) {
                presentationMime.startsWith("image/")
            } else {
                message.type == Message.TYPE_IMAGE
            }
        val privateMessage = message.isPrivateMessage
        message.body = baseBody(url, metadata.sizeBytes).toString()
        message.setSecureMediaPresentationMetadata(
            metadata.mimeType,
            presentationFileName,
            metadata.sizeBytes,
        )
        message.isDeleted = false
        message.type =
            if (privateMessage) {
                Message.TYPE_PRIVATE_FILE
            } else if (image) {
                Message.TYPE_IMAGE
            } else {
                Message.TYPE_FILE
            }
    }

    private fun needsPayloadEnrichment(
        message: Message,
        presentationMime: String?,
    ): Boolean {
        return (presentationMime != null &&
            MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(presentationMime)) ||
            presentationMime?.startsWith("image/") == true ||
            presentationMime?.startsWith("video/") == true ||
            presentationMime?.startsWith("audio/") == true ||
            (presentationMime.isNullOrBlank() && message.type == Message.TYPE_IMAGE)
    }

    private fun enrichFromUri(
        message: Message,
        url: String?,
        uri: Uri,
        metadata: SecureContentMetadata,
        presentationMime: String?,
    ) {
        val image =
            if (!presentationMime.isNullOrBlank()) {
                presentationMime.startsWith("image/")
            } else {
                message.type == Message.TYPE_IMAGE
            }
        val body = baseBody(url, metadata.sizeBytes)
        val ambiguous =
            presentationMime != null &&
                MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(presentationMime)
        val video = presentationMime?.startsWith("video/") == true
        val audio = presentationMime?.startsWith("audio/") == true

        when {
            ambiguous -> {
                val dimensions = videoDimensions(uri)
                if (dimensions.valid()) {
                    body.append('|').append(dimensions.width).append('|').append(dimensions.height)
                } else {
                    body.append("|0|0|").append(mediaRuntime(uri))
                }
            }
            image || video -> {
                val dimensions = if (video) videoDimensions(uri) else imageDimensions(uri)
                if (dimensions.valid()) {
                    body.append('|').append(dimensions.width).append('|').append(dimensions.height)
                }
            }
            audio -> body.append("|0|0|").append(mediaRuntime(uri))
        }

        message.body = body.toString()
    }

    private fun baseBody(
        url: String?,
        sizeBytes: Long?,
    ): StringBuilder =
        StringBuilder().apply {
            if (url != null) {
                append(url)
            }
            append('|').append(sizeBytes ?: 0L)
        }

    private fun mediaRuntime(uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            // The short-lived secure cache is exposed through FileProvider. Supplying its opened
            // descriptor avoids device-specific URI handling in MediaMetadataRetriever while
            // keeping the verified lease lifetime bounded to this metadata read.
            applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toIntOrNull()
                    ?: 0
            } ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Best effort cleanup only.
            }
        }
    }

    private fun imageDimensions(uri: Uri): Dimensions {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        val rotation = applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            when (
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270 -> 90
                else -> 0
            }
        } ?: 0
        return if (rotation == 90) {
            Dimensions(options.outHeight, options.outWidth)
        } else {
            Dimensions(options.outWidth, options.outHeight)
        }
    }

    private fun videoDimensions(uri: Uri): Dimensions {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, uri)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) {
                return Dimensions.EMPTY
            }
            val frame = try {
                retriever.getFrameAtTime()
            } catch (_: Exception) {
                null
            }
            if (frame != null) {
                try {
                    return Dimensions(frame.width, frame.height)
                } finally {
                    frame.recycle()
                }
            }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: -1
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: -1
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                Dimensions(height, width)
            } else {
                Dimensions(width, height)
            }
        } catch (_: Exception) {
            Dimensions.EMPTY
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Best effort cleanup only.
            }
        }
    }

    private data class Dimensions(val width: Int, val height: Int) {
        fun valid(): Boolean = width > 0 && height > 0

        companion object {
            val EMPTY = Dimensions(0, 0)
        }
    }
}
