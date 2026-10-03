// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import eu.siacs.conversations.ui.util.VideoThumbnailOverlay
import java.io.IOException

/**
 * Android bitmap adapter over the verified secure-media read boundary.
 *
 * A null return means that no secure media relation exists for the message and the rollout
 * transition layer may consult legacy storage. Any relation/read/decode failure throws so a known
 * secure relation can never silently fall back to FileBackend.
 */
class AndroidSecureMessageMediaThumbnailReader(
    context: Context,
    store: SecureContentStore,
) {
    private val applicationContext = context.applicationContext
    private val readCache = AndroidSecureMessageMediaReadCache(applicationContext, store)
    private val coordinator = SecureMessageMediaCoordinator(store)

    @Throws(IOException::class)
    fun load(
        accountUuid: String,
        messageUuid: String,
        targetSize: Int,
        cropSquare: Boolean = false,
    ): Bitmap? {
        require(targetSize > 0) { "targetSize must be positive" }
        val metadata = coordinator.resolveMetadata(accountUuid, messageUuid) ?: return null
        val lease = readCache.acquire(accountUuid, messageUuid) ?: return null
        lease.use {
            if (metadata.mimeType?.startsWith("video/") == true) {
                return loadVideoPreview(lease.uri, targetSize, cropSquare)
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            applicationContext.contentResolver.openInputStream(lease.uri).use { input ->
                if (input == null) throw IOException("Secure media read cache is unavailable")
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("Secure media is not a decodable bitmap")
            }

            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= targetSize) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = applicationContext.contentResolver.openInputStream(lease.uri).use { input ->
                if (input == null) throw IOException("Secure media read cache is unavailable")
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw IOException("Unable to decode secure media")

            return if (cropSquare) {
                cropAndScale(decoded, targetSize)
            } else {
                scaleLongestSide(decoded, targetSize)
            }
        }
    }

    private fun loadVideoPreview(
        uri: Uri,
        targetSize: Int,
        cropSquare: Boolean,
    ): Bitmap {
        val retriever = MediaMetadataRetriever()
        val frame =
            try {
                applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    retriever.setDataSource(descriptor.fileDescriptor)
                    retriever.getFrameAtTime(0)
                }
            } catch (error: Exception) {
                throw IOException("Unable to decode secure video preview", error)
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                    // Best effort cleanup only.
                }
            } ?: throw IOException("Secure video has no decodable preview frame")

        val scaled =
            if (cropSquare) {
                cropAndScale(frame, targetSize)
            } else {
                scaleLongestSide(frame, targetSize)
            }
        return drawVideoOverlay(scaled)
    }

    private fun drawVideoOverlay(bitmap: Bitmap): Bitmap {
        val mutable =
            if (bitmap.isMutable) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            }
        VideoThumbnailOverlay.draw(mutable, applicationContext.resources)
        return mutable
    }

    private fun scaleLongestSide(bitmap: Bitmap, targetSize: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= targetSize) return bitmap
        val scale = targetSize.toFloat() / longest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun cropAndScale(bitmap: Bitmap, targetSize: Int): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val square = Bitmap.createBitmap(bitmap, left, top, side, side)
        if (square !== bitmap) bitmap.recycle()
        if (side == targetSize) return square
        val scaled = Bitmap.createScaledBitmap(square, targetSize, targetSize, true)
        if (scaled !== square) square.recycle()
        return scaled
    }
}
