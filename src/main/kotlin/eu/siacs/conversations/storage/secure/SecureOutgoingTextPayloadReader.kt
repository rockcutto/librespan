package eu.siacs.conversations.storage.secure

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * U4.2 no-fallback reader for the explicit protected outgoing-text class.
 *
 * It returns text only after the coordinator accepts the account-owned outgoing Message relation,
 * the Store reader reaches EOF and terminal verification succeeds, and the bytes decode as
 * canonical UTF-8 within the bounded text limit. Every failure has the same unavailable result.
 *
 * This class deliberately knows no Message.body, UI, renderer, path, URI, FileBackend object,
 * BlobStore, KeyMaterialStore, CryptoEngine, transport packet, notification, search or cache.
 */
class SecureOutgoingTextPayloadReader @JvmOverloads constructor(
    private val coordinator: SecureMessagePayloadCoordinator,
    private val maximumPayloadBytes: Int = DEFAULT_MAXIMUM_PAYLOAD_BYTES,
) {
    init {
        require(maximumPayloadBytes > 0) { "maximumPayloadBytes must be positive" }
    }

    /**
     * Reads one selected protected outgoing-text payload as a complete verified UTF-8 value.
     *
     * Unavailable never means that a caller may fall back to Message.body. The returned text has
     * not been inserted into any UI/cache/index/export surface by this component.
     */
    fun readFullyVerified(
        context: SecureOutgoingTextPayloadContext,
    ): SecureOutgoingTextPayloadReadResult {
        val session = try {
            coordinator.openOutgoingTextPayload(context)
        } catch (_: Exception) {
            return SecureOutgoingTextPayloadReadResult.Unavailable
        }
        var plaintextBytes: ByteArray? = null
        try {
            plaintextBytes = session.openPlaintextInputStream().use { input ->
                readBounded(input = input, maximumBytes = maximumPayloadBytes)
            }
            val bytes = plaintextBytes
                ?: return SecureOutgoingTextPayloadReadResult.Unavailable
            val snapshot = session.verifyTerminal()
            val text = decodeUtf8(bytes)
            return SecureOutgoingTextPayloadReadResult.Available(
                reference = SecureMessagePayloadReference(
                    accountUuid = context.accountUuid,
                    messageUuid = context.messageUuid,
                    contentId = snapshot.handle.contentId,
                    namespace = snapshot.metadata.namespace,
                ),
                text = text,
            )
        } catch (_: Exception) {
            return SecureOutgoingTextPayloadReadResult.Unavailable
        } finally {
            plaintextBytes?.fill(0)
            try {
                session.close()
            } catch (_: Exception) {
                // No successful result is produced after a failed/early close.
            }
        }
    }

    @Throws(IOException::class)
    private fun readBounded(
        input: java.io.InputStream,
        maximumBytes: Int,
    ): ByteArray {
        val output = WipingByteArrayOutputStream(minOf(maximumBytes, COPY_BUFFER_BYTES))
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        return try {
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > maximumBytes - total) {
                    throw IOException("Secure outgoing text payload exceeds its bounded text limit")
                }
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    @Throws(CharacterCodingException::class)
    private fun decodeUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private class WipingByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_PAYLOAD_BYTES: Int = 256 * 1024
        private const val COPY_BUFFER_BYTES: Int = 8 * 1024
    }
}

/**
 * Explicit outcome for the U4.2 renderer adapter boundary.
 *
 * Unavailable is intentionally non-diagnostic so callers do not distinguish missing metadata,
 * blob/key material, wrong ownership, interrupted publication, corruption or malformed text.
 */
sealed class SecureOutgoingTextPayloadReadResult {
    data class Available(
        val reference: SecureMessagePayloadReference,
        val text: String,
    ) : SecureOutgoingTextPayloadReadResult()

    object Unavailable : SecureOutgoingTextPayloadReadResult()
}
