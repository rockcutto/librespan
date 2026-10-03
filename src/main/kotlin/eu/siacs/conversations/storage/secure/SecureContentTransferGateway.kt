// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Transport-neutral staging and verified-read boundary for one exact message/content relation.
 *
 * It accepts caller-provided streams only. It never accepts or exposes a path, File, URI,
 * BlobStore reference, key material, or CryptoEngine implementation.
 */
class SecureContentTransferGateway(
    private val store: SecureContentStore,
) {
    /**
     * Allocates a fresh recipient/local Store identity and stages [source] under that identity.
     *
     * This is the preferred ingress boundary for future received or transport-originated bytes:
     * a remote content identifier must never select a local Store object. The returned binding is
     * local-only; callers may separately retain an authenticated remote logical reference.
     */
    @Throws(IOException::class)
    fun stageNewContent(
        accountUuid: String,
        messageUuid: String,
        namespace: String = SecureContentMetadata.DEFAULT_NAMESPACE,
        mimeType: String? = null,
        fileName: String? = null,
        expectedSizeBytes: Long? = null,
        source: InputStream,
    ): SecureContentTransferBinding {
        val binding = SecureContentTransferBinding(
            accountUuid = accountUuid,
            messageUuid = messageUuid,
            contentId = UUID.randomUUID().toString(),
            namespace = namespace,
        )
        stage(
            binding = binding,
            namespace = namespace,
            mimeType = mimeType,
            fileName = fileName,
            expectedSizeBytes = expectedSizeBytes,
            source = source,
        )
        return binding
    }

    /**
     * Consumes and closes [source], then publishes one object only through the Store commit
     * transaction. A failed operation is aborted best-effort and never falls back to legacy
     * plaintext storage.
     *
     * Callers using transport-originated bytes should prefer [stageNewContent] so a remote
     * identifier cannot become local Store ownership.
     */
    @Throws(IOException::class)
    fun stage(
        binding: SecureContentTransferBinding,
        namespace: String = SecureContentMetadata.DEFAULT_NAMESPACE,
        mimeType: String? = null,
        fileName: String? = null,
        expectedSizeBytes: Long? = null,
        source: InputStream,
    ): SecureContentObject {
        require(namespace == binding.namespace) {
            "Transfer binding namespace does not match staged content"
        }
        require(expectedSizeBytes == null || expectedSizeBytes >= 0) {
            "expectedSizeBytes must not be negative"
        }

        return source.use { input ->
            val allocateStarted = System.nanoTime()
            val handle = store.allocate(
                SecureContentMetadata(
                    accountUuid = binding.accountUuid,
                    contentId = binding.contentId,
                    namespace = binding.namespace,
                    messageUuid = binding.messageUuid,
                    mimeType = mimeType,
                    fileName = fileName?.takeIf { it.isNotBlank() },
                    sizeBytes = expectedSizeBytes,
                ),
            )
            SecureMediaPerfTrace.stage(
                binding.messageUuid,
                "allocate",
                System.nanoTime() - allocateStarted,
            )
            val beginWriteStarted = System.nanoTime()
            val writer = store.beginWrite(handle)
            SecureMediaPerfTrace.stage(
                binding.messageUuid,
                "begin_write",
                System.nanoTime() - beginWriteStarted,
            )
            var transaction: SecureContentCommitTransaction? = null
            try {
                val secureWriteStarted = System.nanoTime()
                val written = writer.openPlaintextOutputStream().use { output ->
                    copy(input, output)
                }
                SecureMediaPerfTrace.stage(
                    binding.messageUuid,
                    "secure_write",
                    System.nanoTime() - secureWriteStarted,
                )
                if (expectedSizeBytes != null && written != expectedSizeBytes) {
                    throw IOException(
                        "Secure content staging size mismatch: received $written of $expectedSizeBytes bytes",
                    )
                }
                val cryptoFinishStarted = System.nanoTime()
                val preparedTransaction = writer.finishAndBeginCommit()
                SecureMediaPerfTrace.stage(
                    binding.messageUuid,
                    "crypto_finish_prepare",
                    System.nanoTime() - cryptoFinishStarted,
                )
                transaction = preparedTransaction
                val commitStarted = System.nanoTime()
                preparedTransaction.commit().also {
                    SecureMediaPerfTrace.stage(
                        binding.messageUuid,
                        "durable_commit",
                        System.nanoTime() - commitStarted,
                    )
                }
            } catch (error: Exception) {
                try {
                    transaction?.abort() ?: writer.abort()
                } catch (_: Exception) {
                    // The Store remains the recovery authority for a failed abort/finalization.
                }
                SecureMediaPerfTrace.fail(binding.messageUuid)
                throw error.asSecureContentFailure("Unable to stage secure content")
            }
        }
    }

    /**
     * Resolves a committed object only when its authenticated message relation matches [binding].
     *
     * The returned reader is provisional until its caller reaches EOF and invokes
     * [SecureContentReadSession.verifyTerminal].
     */
    @Throws(IOException::class)
    fun resolveForTransfer(binding: SecureContentTransferBinding): SecureContentObject {
        val objectSnapshot = store.find(binding.accountUuid, binding.contentId)
            ?: throw IOException("Secure content is unavailable")
        if (objectSnapshot.messageUuid != binding.messageUuid ||
            objectSnapshot.namespace != binding.namespace ||
            objectSnapshot.state != SecureContentState.COMMITTED
        ) {
            throw IOException("Secure content is unavailable")
        }
        return objectSnapshot
    }

    /**
     * Resolves at most one committed relation for a message/namespace pair.
     *
     * This is intentionally fail-closed for duplicates and incomplete objects. It exists so
     * restart/retry paths can reuse an already-published object instead of creating a second
     * relation and later falling back to legacy storage.
     */
    @Throws(IOException::class)
    fun resolveUniqueForMessage(
        accountUuid: String,
        messageUuid: String,
        namespace: String,
    ): SecureContentTransferBinding? {
        val candidates =
            store.findByMessage(accountUuid, messageUuid)
                .filter { it.namespace == namespace }
        if (candidates.size > 1) {
            throw IOException("Secure content relation is ambiguous")
        }
        val snapshot = candidates.singleOrNull() ?: return null
        if (snapshot.state != SecureContentState.COMMITTED ||
            snapshot.accountUuid != accountUuid ||
            snapshot.messageUuid != messageUuid
        ) {
            throw IOException("Secure content is unavailable")
        }
        return SecureContentTransferBinding(
            accountUuid = accountUuid,
            messageUuid = messageUuid,
            contentId = snapshot.contentId,
            namespace = namespace,
        )
    }

    @Throws(IOException::class)
    fun openForTransfer(binding: SecureContentTransferBinding): SecureContentReadSession {
        resolveForTransfer(binding)
        return store.open(binding.accountUuid, binding.contentId)
    }

    /**
     * Removes only the exact account/message/content relation. A mismatched message relation is
     * unavailable rather than a reason to delete by content ID alone.
     */
    @Throws(IOException::class)
    fun delete(binding: SecureContentTransferBinding) {
        val objectSnapshot = store.find(binding.accountUuid, binding.contentId)
            ?: return
        if (objectSnapshot.messageUuid != binding.messageUuid ||
            objectSnapshot.namespace != binding.namespace
        ) {
            throw IOException("Secure content is unavailable")
        }
        store.delete(binding.accountUuid, binding.contentId)
    }

    private fun copy(input: InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return total
            output.write(buffer, 0, count)
            total += count
        }
    }

    private fun Exception.asSecureContentFailure(message: String): IOException =
        if (this is IOException) this else IOException(message, this)

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
