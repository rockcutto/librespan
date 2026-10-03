package eu.siacs.conversations.storage.secure

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Streaming ingress boundary for received/generated message media.
 *
 * Network and encoder code may write plaintext only through the returned Store writer. No
 * DownloadableFile, path, URI or persistent plaintext staging file is part of this contract.
 */
class SecureMessageMediaIngress(
    private val store: SecureContentStore,
) {
    private val coordinator = SecureMessageMediaCoordinator(store)

    @Throws(IOException::class)
    fun begin(
        accountUuid: String,
        messageUuid: String,
        mimeType: String? = null,
        expectedSizeBytes: Long? = null,
    ): Session =
        begin(
            accountUuid,
            messageUuid,
            mimeType,
            expectedSizeBytes,
            null,
        )

    @Throws(IOException::class)
    fun begin(
        accountUuid: String,
        messageUuid: String,
        mimeType: String?,
        expectedSizeBytes: Long?,
        fileName: String?,
    ): Session {
        SecureMediaPerfTrace.start(
            direction = "incoming",
            messageUuid = messageUuid,
            mimeType = mimeType,
            sizeBytes = expectedSizeBytes,
        )
        coordinator.retireTerminalAttempts(accountUuid, messageUuid)
        val resolveStarted = System.nanoTime()
        if (coordinator.resolve(accountUuid, messageUuid) != null) {
            SecureMediaPerfTrace.stage(
                messageUuid,
                "resolve_existing",
                System.nanoTime() - resolveStarted,
            )
            throw IOException("Secure media already exists for message $messageUuid")
        }
        SecureMediaPerfTrace.stage(
            messageUuid,
            "resolve_existing",
            System.nanoTime() - resolveStarted,
        )
        val allocateStarted = System.nanoTime()
        val handle = store.allocate(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = UUID.randomUUID().toString(),
                namespace = SecureMessageMediaCoordinator.NAMESPACE,
                messageUuid = messageUuid,
                mimeType = mimeType,
                fileName = fileName?.takeIf { it.isNotBlank() },
                sizeBytes = expectedSizeBytes,
            ),
        )
        SecureMediaPerfTrace.stage(
            messageUuid,
            "allocate",
            System.nanoTime() - allocateStarted,
        )
        val beginWriteStarted = System.nanoTime()
        val writer = try {
            store.beginWrite(handle)
        } catch (error: Exception) {
            SecureMediaPerfTrace.fail(messageUuid)
            try {
                store.delete(handle)
            } catch (_: Exception) {
                // Store recovery remains authoritative when cleanup is interrupted.
            }
            throw error.asIo("Unable to begin secure media ingress")
        }
        SecureMediaPerfTrace.stage(
            messageUuid,
            "begin_write",
            System.nanoTime() - beginWriteStarted,
        )
        return Session(store, writer, expectedSizeBytes, messageUuid)
    }

    class Session internal constructor(
        private val store: SecureContentStore,
        private val writer: SecureContentWriteSession,
        private val expectedSizeBytes: Long?,
        private val messageUuid: String,
    ) : AutoCloseable {
        private var streamOpened = false
        private var streamClosed = false
        private var terminal = false
        private var byteCount = 0L
        private var writeStartedNanos = 0L

        val handle: SecureContentHandle
            get() = writer.handle

        fun openPlaintextOutputStream(): OutputStream {
            check(!terminal) { "Secure media ingress is terminal" }
            check(!streamOpened) { "Secure media ingress stream is already opened" }
            streamOpened = true
            writeStartedNanos = System.nanoTime()
            return object : FilterOutputStream(writer.openPlaintextOutputStream()) {
                override fun write(value: Int) {
                    out.write(value)
                    byteCount += 1
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    out.write(buffer, offset, length)
                    byteCount += length.toLong()
                }

                override fun close() {
                    if (streamClosed) return
                    try {
                        super.close()
                    } finally {
                        streamClosed = true
                        SecureMediaPerfTrace.stage(
                            messageUuid,
                            "secure_write",
                            System.nanoTime() - writeStartedNanos,
                        )
                    }
                }
            }
        }

        @Throws(IOException::class)
        fun commit(): SecureContentObject {
            check(!terminal) { "Secure media ingress is terminal" }
            check(streamOpened && streamClosed) {
                "Secure media ingress stream must be closed before commit"
            }
            if (expectedSizeBytes != null && byteCount != expectedSizeBytes) {
                abort()
                throw IOException(
                    "Secure media ingress size mismatch: received $byteCount of $expectedSizeBytes bytes",
                )
            }
            val transaction = try {
                val finishStarted = System.nanoTime()
                writer.finishAndBeginCommit().also {
                    SecureMediaPerfTrace.stage(
                        messageUuid,
                        "crypto_finish_prepare",
                        System.nanoTime() - finishStarted,
                    )
                }
            } catch (error: Exception) {
                abortBestEffort()
                terminal = true
                SecureMediaPerfTrace.fail(messageUuid)
                throw error.asIo("Unable to prepare secure media commit")
            }
            return try {
                val commitStarted = System.nanoTime()
                val committed = transaction.commit()
                SecureMediaPerfTrace.stage(
                    messageUuid,
                    "durable_commit",
                    System.nanoTime() - commitStarted,
                )
                terminal = true
                committed
            } catch (error: Exception) {
                try {
                    transaction.abort()
                } catch (_: Exception) {
                    // Store recovery remains authoritative for interrupted finalization.
                }
                deleteAttemptBestEffort()
                terminal = true
                SecureMediaPerfTrace.fail(messageUuid)
                throw error.asIo("Unable to commit secure media ingress")
            }
        }

        fun abort() {
            if (terminal) return
            try {
                writer.abort()
            } finally {
                deleteAttemptBestEffort()
                terminal = true
            }
        }

        override fun close() {
            if (!terminal) abortBestEffort()
            terminal = true
        }

        private fun abortBestEffort() {
            try {
                writer.abort()
            } catch (_: Exception) {
                // Store deletion below remains the final cleanup boundary for this attempt.
            }
            deleteAttemptBestEffort()
        }

        private fun deleteAttemptBestEffort() {
            try {
                store.delete(writer.handle)
            } catch (_: Exception) {
                // Store recovery remains authoritative when immediate retirement is interrupted.
            }
        }
    }
}

private fun Exception.asIo(message: String): IOException =
    if (this is IOException) this else IOException(message, this)
