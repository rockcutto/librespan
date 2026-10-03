package eu.siacs.conversations.storage.secure

import java.io.IOException
import java.io.InputStream

/**
 * Controlled producer for the single U4 opt-in class: an already-persisted local outgoing text
 * message selected by [SecureOutgoingTextPayloadContext].
 *
 * The producer receives canonical UTF-8 text bytes only as a caller-owned stream and writes them
 * exactly once through the coordinator capability. It owns neither account authorization nor a
 * Store, BlobStore,
 * KeyMaterialStore, CryptoEngine, path, URI, FileBackend object, Message.body, transport packet or
 * renderer. A successful result means the Store object is committed and its Message DB relation is
 * durable; it does not send, display, index, notify or otherwise publish the text.
 *
 * The source stream is intentionally not closed here: its caller retains that resource ownership.
 */
class SecureOutgoingTextPayloadPublisher(
    private val coordinator: SecureMessagePayloadCoordinator,
) {
    /**
     * Publishes one explicit outgoing protected-text payload without a legacy-body fallback.
     *
     * If copying or publication fails, the writer is aborted when it is still eligible for abort.
     * Store/coordinator recovery remains authoritative if a commit was interrupted after Store
     * persistence. The returned reference contains logical identities only.
     */
    @Throws(IOException::class)
    fun publish(
        context: SecureOutgoingTextPayloadContext,
        source: InputStream,
    ): SecureMessagePayloadReference {
        val session = coordinator.beginOutgoingTextWrite(context)
        var completed = false
        try {
            session.openPlaintextOutputStream().use { destination ->
                source.copyTo(destination)
            }
            val reference = session.commitAndPublish()
            completed = true
            return reference
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException(
                "Unable to publish secure outgoing text payload",
                error,
            )
        } finally {
            if (!completed) {
                try {
                    session.abort()
                } catch (_: Exception) {
                    // Recovery evidence and the Store lifecycle remain authoritative.
                }
            }
        }
    }
}
