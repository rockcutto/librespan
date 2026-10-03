package eu.siacs.conversations.storage.secure

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SecureContentTransferGatewayTest {
    @Test
    fun exactBindingResolvesCommittedObject() {
        val snapshot = committedObject()
        val gateway = SecureContentTransferGateway(FakeStore(snapshot))
        val binding = SecureContentTransferBinding(
            accountUuid = snapshot.accountUuid,
            messageUuid = snapshot.messageUuid!!,
            contentId = snapshot.contentId,
            namespace = snapshot.namespace,
        )

        assertSame(snapshot, gateway.resolveForTransfer(binding))
    }

    @Test
    fun mismatchedMessageOrNamespaceFailsClosed() {
        val snapshot = committedObject()
        val gateway = SecureContentTransferGateway(FakeStore(snapshot))

        expectIOException {
            gateway.resolveForTransfer(
                SecureContentTransferBinding(
                    accountUuid = snapshot.accountUuid,
                    messageUuid = "other-message",
                    contentId = snapshot.contentId,
                    namespace = snapshot.namespace,
                ),
            )
        }
        expectIOException {
            gateway.resolveForTransfer(
                SecureContentTransferBinding(
                    accountUuid = snapshot.accountUuid,
                    messageUuid = snapshot.messageUuid!!,
                    contentId = snapshot.contentId,
                    namespace = "other-namespace",
                ),
            )
        }
    }

    @Test
    fun uniqueMessageRelationCanBeReusedAfterRestart() {
        val snapshot = committedObject()
        val gateway = SecureContentTransferGateway(FakeStore(snapshot))

        val binding = gateway.resolveUniqueForMessage(
            snapshot.accountUuid,
            snapshot.messageUuid!!,
            snapshot.namespace,
        )

        assertEquals(snapshot.contentId, binding!!.contentId)
        assertEquals(snapshot.namespace, binding.namespace)
    }

    @Test
    fun duplicateOrIncompleteMessageRelationFailsClosed() {
        val snapshot = committedObject()
        val duplicate = snapshot.copy(metadata = snapshot.metadata.copy(contentId = "content-b"))
        expectIOException {
            SecureContentTransferGateway(FakeStore(snapshot, duplicate))
                .resolveUniqueForMessage(
                    snapshot.accountUuid,
                    snapshot.messageUuid!!,
                    snapshot.namespace,
                )
        }

        val incomplete =
            snapshot.copy(metadata = snapshot.metadata.copy(state = SecureContentState.WRITING))
        expectIOException {
            SecureContentTransferGateway(FakeStore(incomplete))
                .resolveUniqueForMessage(
                    snapshot.accountUuid,
                    snapshot.messageUuid!!,
                    snapshot.namespace,
                )
        }
    }

    @Test
    fun deleteRequiresExactRelationBeforeDeletingByContentId() {
        val snapshot = committedObject()
        val store = FakeStore(snapshot)
        val gateway = SecureContentTransferGateway(store)

        expectIOException {
            gateway.delete(
                SecureContentTransferBinding(
                    accountUuid = snapshot.accountUuid,
                    messageUuid = "other-message",
                    contentId = snapshot.contentId,
                    namespace = snapshot.namespace,
                ),
            )
        }
        assertNull(store.deleted)

        gateway.delete(
            SecureContentTransferBinding(
                accountUuid = snapshot.accountUuid,
                messageUuid = snapshot.messageUuid!!,
                contentId = snapshot.contentId,
                namespace = snapshot.namespace,
            ),
        )
        assertEquals(snapshot.accountUuid to snapshot.contentId, store.deleted)
    }

    private fun committedObject(): SecureContentObject =
        SecureContentObject(
            SecureContentMetadata(
                accountUuid = "account-a",
                contentId = "content-a",
                namespace = "secure-content",
                messageUuid = "message-a",
                mimeType = "image/jpeg",
                sizeBytes = 128,
                state = SecureContentState.COMMITTED,
                cryptoVersion = 1,
            ),
        )

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    private class FakeStore(
        vararg snapshots: SecureContentObject,
    ) : SecureContentStore {
        private val snapshots = snapshots.toList()
        var deleted: Pair<String, String>? = null

        override fun allocate(metadata: SecureContentMetadata): SecureContentHandle =
            throw UnsupportedOperationException()

        override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession =
            throw UnsupportedOperationException()

        override fun find(accountUuid: String, contentId: String): SecureContentObject? =
            snapshots.firstOrNull {
                it.accountUuid == accountUuid && it.contentId == contentId
            }

        override fun findByMessage(accountUuid: String, messageUuid: String): List<SecureContentObject> =
            snapshots.filter {
                it.accountUuid == accountUuid && it.messageUuid == messageUuid
            }

        override fun open(accountUuid: String, contentId: String): SecureContentReadSession =
            throw UnsupportedOperationException()

        override fun delete(accountUuid: String, contentId: String) {
            deleted = accountUuid to contentId
        }
    }
}
