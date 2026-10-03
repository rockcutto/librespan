package eu.siacs.conversations.storage.secure

import java.io.IOException
import java.io.InputStream

/**
 * Message-media boundary for Secure Content Store.
 *
 * Media objects use their own namespace so they can never be confused with protected text
 * payloads. Resolution is intentionally fail-closed: more than one candidate, an incomplete
 * candidate, or inconsistent ownership/relation metadata is treated as an error rather than a
 * reason to fall back to legacy FileBackend storage.
 */
class SecureMessageMediaCoordinator(
    private val store: SecureContentStore,
    private val gateway: SecureContentTransferGateway = SecureContentTransferGateway(store),
) {
    @Throws(IOException::class)
    fun resolve(accountUuid: String, messageUuid: String): SecureContentTransferBinding? {
        val snapshot = resolveSnapshot(accountUuid, messageUuid) ?: return null
        return SecureContentTransferBinding(
            accountUuid = snapshot.accountUuid,
            messageUuid = messageUuid,
            contentId = snapshot.contentId,
            namespace = NAMESPACE,
        )
    }

    @Throws(IOException::class)
    fun resolveMetadata(accountUuid: String, messageUuid: String): SecureContentMetadata? =
        resolveSnapshot(accountUuid, messageUuid)?.metadata

    @Throws(IOException::class)
    private fun resolveSnapshot(
        accountUuid: String,
        messageUuid: String,
    ): SecureContentObject? {
        val candidates =
            store.findByMessage(accountUuid, messageUuid)
                .filter {
                    it.namespace == NAMESPACE &&
                        it.state != SecureContentState.ABORTED &&
                        it.state != SecureContentState.FAILED
                }

        if (candidates.size > 1) {
            throw IOException("Ambiguous secure media relation for message $messageUuid")
        }

        val snapshot = candidates.singleOrNull() ?: return null
        if (snapshot.accountUuid != accountUuid || snapshot.messageUuid != messageUuid) {
            throw IOException("Secure media relation ownership mismatch")
        }
        if (!snapshot.isReaderVisible) {
            throw IOException("Secure media object is not committed")
        }

        return snapshot
    }

    @Throws(IOException::class)
    fun retireTerminalAttempts(accountUuid: String, messageUuid: String) {
        val terminal =
            store.findByMessage(accountUuid, messageUuid)
                .filter {
                    it.namespace == NAMESPACE &&
                        (it.state == SecureContentState.ABORTED ||
                            it.state == SecureContentState.FAILED)
                }
        for (snapshot in terminal) {
            try {
                store.delete(accountUuid, snapshot.contentId)
            } catch (error: Exception) {
                throw IOException(
                    "Unable to retire terminal secure media attempt for message $messageUuid",
                    error,
                )
            }
        }
    }

    @Throws(IOException::class)
    fun stageNew(
        accountUuid: String,
        messageUuid: String,
        mimeType: String? = null,
        fileName: String? = null,
        expectedSizeBytes: Long? = null,
        source: InputStream,
    ): SecureContentTransferBinding {
        retireTerminalAttempts(accountUuid, messageUuid)
        val resolveStarted = System.nanoTime()
        val existing =
            try {
                resolve(accountUuid, messageUuid)
            } finally {
                SecureMediaPerfTrace.stage(
                    messageUuid,
                    "stage_preflight_resolve",
                    System.nanoTime() - resolveStarted,
                )
            }
        if (existing != null) {
            throw IOException("Secure media object already exists for message $messageUuid")
        }
        return gateway.stageNewContent(
            accountUuid = accountUuid,
            messageUuid = messageUuid,
            namespace = NAMESPACE,
            mimeType = mimeType,
            fileName = fileName,
            expectedSizeBytes = expectedSizeBytes,
            source = source,
        )
    }

    @Throws(IOException::class)
    fun open(binding: SecureContentTransferBinding): SecureContentReadSession {
        requireMediaBinding(binding)
        return gateway.openForTransfer(binding)
    }

    @Throws(IOException::class)
    fun retire(binding: SecureContentTransferBinding) {
        requireMediaBinding(binding)
        gateway.delete(binding)
    }

    @Throws(IOException::class)
    fun retire(accountUuid: String, messageUuid: String): Boolean {
        val binding = resolve(accountUuid, messageUuid) ?: return false
        retire(binding)
        return true
    }

    @Throws(IOException::class)
    private fun requireMediaBinding(binding: SecureContentTransferBinding) {
        if (binding.namespace != NAMESPACE) {
            throw IOException("Refusing non-media Secure Content binding")
        }
    }

    companion object {
        const val NAMESPACE = "secure-message-media"
    }
}
