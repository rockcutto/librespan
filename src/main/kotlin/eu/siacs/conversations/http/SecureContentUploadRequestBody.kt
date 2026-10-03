package eu.siacs.conversations.http

import eu.siacs.conversations.storage.secure.SecureContentTransferBinding
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway
import eu.siacs.conversations.storage.secure.SecureMediaPerfTrace
import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Logical HTTP upload request body sourced from a committed Secure Content object.
 *
 * The complete immutable Store object is authenticated before the network receives a byte. A
 * separate optional adapter then applies XEP-0454 wire protection to the fresh verified reader.
 */
class SecureContentUploadRequestBody(
    private val gateway: SecureContentTransferGateway,
    private val descriptor: SecureContentUploadDescriptor,
    private val wireProtection: SecureContentAesGcmWireProtection? = null,
) : RequestBody() {
    override fun contentLength(): Long =
        wireProtection?.expectedWireSize(descriptor.plaintextSizeBytes)
            ?: descriptor.plaintextSizeBytes

    override fun contentType(): MediaType? = descriptor.mimeType?.toMediaTypeOrNull()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val messageUuid = descriptor.binding.messageUuid
        try {
            SecureMediaPerfTrace.gapSinceLastEvent(messageUuid, "upload_body_dispatch_wait")
            val descriptorStarted = System.nanoTime()
            verifyDescriptor()
            SecureMediaPerfTrace.stage(
                messageUuid,
                "upload_descriptor_verify",
                System.nanoTime() - descriptorStarted,
            )
            val preflightStarted = System.nanoTime()
            verifyBeforeNetworkPublication()
            SecureMediaPerfTrace.stage(
                messageUuid,
                "upload_preflight",
                System.nanoTime() - preflightStarted,
            )
            val readerOpenStarted = System.nanoTime()
            val reader = gateway.openForTransfer(descriptor.binding)
            SecureMediaPerfTrace.stage(
                messageUuid,
                "upload_reader_open",
                System.nanoTime() - readerOpenStarted,
            )
            try {
                val uploadStarted = System.nanoTime()
                val plaintext = reader.openPlaintextInputStream()
                val source = wireProtection?.protect(plaintext) ?: plaintext
                val written = source.use { copy(it, sink) }
                if (written != contentLength()) {
                    throw IOException(
                        "Secure content upload size mismatch: wrote " +
                            written + " of " + contentLength() + " bytes",
                    )
                }
                reader.verifyTerminal()
                SecureMediaPerfTrace.stage(
                    messageUuid,
                    "upload_stream",
                    System.nanoTime() - uploadStarted,
                )
                SecureMediaPerfTrace.finish(messageUuid)
            } finally {
                reader.close()
            }
        } catch (error: Exception) {
            SecureMediaPerfTrace.fail(messageUuid)
            throw error
        }
    }

    private fun verifyDescriptor() {
        val objectSnapshot = gateway.resolveForTransfer(descriptor.binding)
        if (objectSnapshot.metadata.sizeBytes != descriptor.plaintextSizeBytes ||
            objectSnapshot.metadata.mimeType != descriptor.mimeType
        ) {
            throw IOException("Secure content upload descriptor does not match authenticated metadata")
        }
    }

    private fun verifyBeforeNetworkPublication() {
        val reader = gateway.openForTransfer(descriptor.binding)
        try {
            val verifiedBytes = reader.openPlaintextInputStream().use(::discard)
            if (verifiedBytes != descriptor.plaintextSizeBytes) {
                throw IOException(
                    "Secure content preflight size mismatch: read $verifiedBytes of " +
                        "${descriptor.plaintextSizeBytes} bytes",
                )
            }
            reader.verifyTerminal()
        } finally {
            reader.close()
        }
    }

    private fun discard(input: java.io.InputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return total
            total += count
        }
    }

    private fun copy(input: java.io.InputStream, sink: BufferedSink): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return total
            sink.write(buffer, 0, count)
            total += count
        }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}

data class SecureContentUploadDescriptor(
    val binding: SecureContentTransferBinding,
    val displayFilename: String,
    val mimeType: String?,
    val plaintextSizeBytes: Long,
) {
    init {
        HttpUploadSlotRequest.validateDisplayFilename(displayFilename)
        require(plaintextSizeBytes >= 0) { "plaintextSizeBytes must not be negative" }
    }
}
