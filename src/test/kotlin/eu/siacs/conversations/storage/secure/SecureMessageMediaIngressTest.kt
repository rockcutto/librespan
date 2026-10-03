package eu.siacs.conversations.storage.secure

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageMediaIngressTest {
    @Test
    fun ingressPublishesOnlyAfterClosedStreamAndCommit() {
        val store = FakeStore()
        val ingress = SecureMessageMediaIngress(store)
        val session = ingress.begin("account-a", "message-a", "image/jpeg", 7)

        session.openPlaintextOutputStream().use { it.write("payload".toByteArray()) }
        assertFalse(store.objectSnapshot!!.isReaderVisible)

        val committed = session.commit()
        assertTrue(committed.isReaderVisible)
        assertEquals(SecureMessageMediaCoordinator.NAMESPACE, committed.namespace)
        assertEquals("message-a", committed.messageUuid)
        assertEquals("payload", store.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun sizeMismatchAbortsWithoutReaderVisibleObject() {
        val store = FakeStore()
        val session = SecureMessageMediaIngress(store).begin(
            "account-a",
            "message-a",
            "image/jpeg",
            8,
        )
        session.openPlaintextOutputStream().use { it.write("payload".toByteArray()) }

        expectIOException { session.commit() }
        assertEquals(null, store.objectSnapshot)
    }

    @Test
    fun abortedIngressCanRetrySameMessage() {
        val store = FakeStore()
        val first = SecureMessageMediaIngress(store).begin(
            "account-a",
            "message-a",
            "image/jpeg",
            8,
        )
        first.openPlaintextOutputStream().use { it.write("payload".toByteArray()) }
        expectIOException { first.commit() }

        val retry = SecureMessageMediaIngress(store).begin(
            "account-a",
            "message-a",
            "image/jpeg",
            7,
        )
        retry.openPlaintextOutputStream().use { it.write("payload".toByteArray()) }
        assertTrue(retry.commit().isReaderVisible)
    }

    @Test
    fun existingCommittedRelationBlocksSecondIngress() {
        val store = FakeStore()
        val first = SecureMessageMediaIngress(store).begin("account-a", "message-a", null, 7)
        first.openPlaintextOutputStream().use { it.write("payload".toByteArray()) }
        first.commit()

        expectIOException {
            SecureMessageMediaIngress(store).begin("account-a", "message-a", null, 7)
        }
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
            // expected
        }
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
            objectSnapshot = allocated.copy(
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
                            objectSnapshot = current.copy(
                                metadata = current.metadata.copy(
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
                    objectSnapshot = current.copy(
                        metadata = current.metadata.copy(state = SecureContentState.ABORTED),
                    )
                }
            }
        }

        override fun find(accountUuid: String, contentId: String): SecureContentObject? =
            objectSnapshot?.takeIf { it.accountUuid == accountUuid && it.contentId == contentId }

        override fun findByMessage(accountUuid: String, messageUuid: String): List<SecureContentObject> =
            listOfNotNull(
                objectSnapshot?.takeIf {
                    it.accountUuid == accountUuid && it.messageUuid == messageUuid
                },
            )

        override fun open(accountUuid: String, contentId: String): SecureContentReadSession =
            throw UnsupportedOperationException()

        override fun delete(accountUuid: String, contentId: String) {
            if (objectSnapshot?.accountUuid == accountUuid && objectSnapshot?.contentId == contentId) {
                objectSnapshot = null
            }
        }
    }
}
