package eu.siacs.conversations.storage.secure

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOutgoingAttachmentIngressTest {

    @Test
    fun publishesCommittedInputWithExactMetadataBinding() {
        val expected = "secure attachment".toByteArray()
        val store = FakeStore()
        val ingress =
            SecureOutgoingAttachmentIngress(
                SecureMessageMediaCoordinator(
                    store,
                    SecureContentTransferGateway(store),
                ),
            )

        val binding =
            ingress.ingest(
                accountUuid = "account",
                messageUuid = "message",
                source = ByteArrayInputStream(expected),
                mimeType = "application/pdf",
                fileName = "report.pdf",
                expectedSizeBytes = expected.size.toLong(),
            )

        val committed = store.objectSnapshot!!
        assertEquals("account", binding.accountUuid)
        assertEquals("message", binding.messageUuid)
        assertEquals(committed.contentId, binding.contentId)
        assertEquals(SecureContentState.COMMITTED, committed.state)
        assertEquals("application/pdf", committed.metadata.mimeType)
        assertEquals("report.pdf", committed.metadata.fileName)
        assertArrayEquals(expected, store.payload)
    }

    @Test
    fun failedStorePublicationPropagatesWithoutFallback() {
        val store = FailingStore()
        val ingress =
            SecureOutgoingAttachmentIngress(
                SecureMessageMediaCoordinator(
                    store,
                    SecureContentTransferGateway(store),
                ),
            )

        assertThrows(IOException::class.java) {
            ingress.ingest(
                accountUuid = "account",
                messageUuid = "message",
                source = ByteArrayInputStream(byteArrayOf(1)),
            )
        }
        assertTrue(store.allocateAttempted)
    }

    private class FakeStore : SecureContentStore {
        var objectSnapshot: SecureContentObject? = null
        var payload = ByteArray(0)

        override fun allocate(metadata: SecureContentMetadata): SecureContentHandle {
            objectSnapshot = SecureContentObject(metadata)
            return objectSnapshot!!.handle
        }

        override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession {
            val allocated = objectSnapshot ?: error("missing object")
            objectSnapshot =
                allocated.copy(
                    metadata = allocated.metadata.copy(state = SecureContentState.WRITING),
                )
            return object : SecureContentWriteSession {
                override val handle = handle
                override val state: SecureContentState
                    get() = objectSnapshot!!.state
                private val bytes = ByteArrayOutputStream()

                override fun openPlaintextOutputStream() = bytes

                override fun finishAndBeginCommit(): SecureContentCommitTransaction =
                    object : SecureContentCommitTransaction {
                        override val handle = handle
                        override val state: SecureContentState
                            get() = objectSnapshot!!.state

                        override fun commit(): SecureContentObject {
                            payload = bytes.toByteArray()
                            val current = objectSnapshot ?: error("missing object")
                            objectSnapshot =
                                current.copy(
                                    metadata =
                                        current.metadata.copy(
                                            state = SecureContentState.COMMITTED,
                                            sizeBytes = payload.size.toLong(),
                                            cryptoVersion = 1,
                                        ),
                                )
                            return objectSnapshot!!
                        }

                        override fun abort() {
                            abortWriter()
                        }
                    }

                override fun beginCommit(
                    payload: SecureContentCommitPayload,
                ): SecureContentCommitTransaction = finishAndBeginCommit()

                override fun abort() {
                    abortWriter()
                }

                private fun abortWriter() {
                    val current = objectSnapshot ?: return
                    objectSnapshot =
                        current.copy(
                            metadata = current.metadata.copy(state = SecureContentState.ABORTED),
                        )
                }
            }
        }

        override fun find(accountUuid: String, contentId: String): SecureContentObject? =
            objectSnapshot?.takeIf {
                it.accountUuid == accountUuid && it.contentId == contentId
            }

        override fun findByMessage(
            accountUuid: String,
            messageUuid: String,
        ): List<SecureContentObject> =
            listOfNotNull(
                objectSnapshot?.takeIf {
                    it.accountUuid == accountUuid && it.messageUuid == messageUuid
                },
            )

        override fun open(accountUuid: String, contentId: String): SecureContentReadSession =
            throw UnsupportedOperationException()

        override fun delete(accountUuid: String, contentId: String) {
            if (objectSnapshot?.accountUuid == accountUuid &&
                objectSnapshot?.contentId == contentId
            ) {
                objectSnapshot = null
            }
        }
    }

    private class FailingStore : SecureContentStore {
        var allocateAttempted = false

        override fun allocate(metadata: SecureContentMetadata): SecureContentHandle {
            allocateAttempted = true
            throw IOException("ingress failed")
        }

        override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession =
            throw UnsupportedOperationException()

        override fun find(accountUuid: String, contentId: String): SecureContentObject? = null

        override fun findByMessage(
            accountUuid: String,
            messageUuid: String,
        ): List<SecureContentObject> = emptyList()

        override fun open(accountUuid: String, contentId: String): SecureContentReadSession =
            throw UnsupportedOperationException()

        override fun delete(accountUuid: String, contentId: String) = Unit
    }
}
