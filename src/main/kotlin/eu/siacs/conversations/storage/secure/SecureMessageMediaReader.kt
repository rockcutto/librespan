package eu.siacs.conversations.storage.secure

import java.io.IOException
import java.io.OutputStream

/**
 * Verified plaintext read boundary for message-owned secure media.
 *
 * Callers provide a short-lived consumer-owned sink (decoder pipe, player pipe, temporary export
 * or similar). They never receive a Store path or blob locator. Bytes are provisional until the
 * Store reader reaches EOF and terminal authentication succeeds.
 */
class SecureMessageMediaReader(
    private val coordinator: SecureMessageMediaCoordinator,
) {
    @Throws(IOException::class)
    fun copyVerified(
        binding: SecureContentTransferBinding,
        sink: OutputStream,
    ): SecureContentObject {
        val session = coordinator.open(binding)
        try {
            val input = session.openPlaintextInputStream()
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    sink.write(buffer, 0, count)
                }
                sink.flush()
            } finally {
                input.close()
            }
            return session.verifyTerminal()
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException("Secure media read failed", error)
        } finally {
            session.close()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
