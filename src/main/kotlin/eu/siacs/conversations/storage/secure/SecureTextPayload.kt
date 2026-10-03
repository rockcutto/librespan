package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.Config
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Canonical bounds and UTF-8 conversion for protected text.
 *
 * The bound is shared with verified readers. Size is determined without allocating a complete
 * byte array first, so an oversized draft is rejected before it reaches Message DB or Store.
 */
object SecureTextPayload {
    const val MAXIMUM_BYTES: Int = 256 * 1024

    @JvmStatic
    @Throws(IOException::class)
    fun utf8ByteCount(text: String, maximumBytes: Int = MAXIMUM_BYTES): Int {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            bytes += when {
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) {
                        throw IOException("Protected text contains an invalid UTF-16 surrogate")
                    }
                    index += 1
                    4
                }
                Character.isLowSurrogate(character) ->
                    throw IOException("Protected text contains an invalid UTF-16 surrogate")
                else -> 3
            }
            if (bytes > maximumBytes) {
                throw SecureTextPayloadTooLargeException(bytes, maximumBytes)
            }
            index += 1
        }
        return bytes
    }

    @JvmStatic
    @Throws(IOException::class)
    fun encodeUtf8(text: String, maximumBytes: Int = MAXIMUM_BYTES): ByteArray {
        val expectedSize = utf8ByteCount(text, maximumBytes)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size != expectedSize) {
            bytes.fill(0)
            throw IOException("Protected text UTF-8 conversion changed its byte size")
        }
        return bytes
    }

    @JvmStatic
    fun logStage(
        payloadBytes: Int,
        stage: String,
        startedAtMillis: Long,
        failureCategory: String? = null,
    ) {
        val durationMillis = SystemClock.elapsedRealtime() - startedAtMillis
        val category = failureCategory ?: "ok"
        Log.i(
            Config.LOGTAG,
            "secure_text payload_bytes=$payloadBytes stage=$stage duration_ms=$durationMillis category=$category",
        )
    }
}

class SecureTextPayloadTooLargeException(
    val observedBytes: Int,
    val maximumBytes: Int,
) : IOException("Protected text payload exceeds $maximumBytes UTF-8 bytes (observed=$observedBytes)")
