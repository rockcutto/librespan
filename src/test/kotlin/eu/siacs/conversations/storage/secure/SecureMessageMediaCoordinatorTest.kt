package eu.siacs.conversations.storage.secure

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageMediaCoordinatorTest {
    @Test
    fun missingRelationReturnsNullWithoutLegacyFallback() {
        val coordinator = SecureMessageMediaCoordinator(FakeStore())
        assertNull(coordinator.resolve("account-a", "message-a"))
    }

    @Test
    fun committedMediaResolvesExactBinding() {
        val snapshot = committedObject()
        val coordinator = SecureMessageMediaCoordinator(FakeStore(snapshot))

        val binding = coordinator.resolve(snapshot.accountUuid, snapshot.messageUuid!!)

        assertNotNull(binding)
        assertEquals(snapshot.accountUuid, binding!!.accountUuid)
        assertEquals(snapshot.messageUuid, binding.messageUuid)
        assertEquals(snapshot.contentId, binding.contentId)
        assertEquals(SecureMessageMediaCoordinator.NAMESPACE, binding.namespace)
    }

    @Test
    fun terminalAttemptDoesNotBlockResolution() {
        val committed = committedObject()
        val aborted =
            committed.copy(
                metadata =
                    committed.metadata.copy(
                        contentId = "content-aborted",
                        state = SecureContentState.ABORTED,
                    ),
            )
        val failed =
            committed.copy(
                metadata =
                    committed.metadata.copy(
                        contentId = "content-failed",
                        state = SecureContentState.FAILED,
                    ),
            )

        assertNull(
            SecureMessageMediaCoordinator(FakeStore(aborted))
                .resolve(aborted.accountUuid, aborted.messageUuid!!),
        )
        val resolved =
            SecureMessageMediaCoordinator(FakeStore(aborted, failed, committed))
                .resolve(committed.accountUuid, committed.messageUuid!!)
        assertEquals(committed.contentId, resolved!!.contentId)
    }

    @Test
    fun ambiguousOrIncompleteRelationFailsClosed() {
        val committed = committedObject()
        val duplicate = committed.copy(metadata = committed.metadata.copy(contentId = "content-b"))
        expectIOException {
            SecureMessageMediaCoordinator(FakeStore(committed, duplicate))
                .resolve(committed.accountUuid, committed.messageUuid!!)
        }

        val incomplete =
            committed.copy(metadata = committed.metadata.copy(state = SecureContentState.WRITING))
        expectIOException {
            SecureMessageMediaCoordinator(FakeStore(incomplete))
                .resolve(incomplete.accountUuid, incomplete.messageUuid!!)
        }
    }

    @Test
    fun retireTerminalAttemptsDeletesOldFailedReceive() {
        val aborted =
            committedObject().copy(
                metadata =
                    committedObject().metadata.copy(
                        contentId = "content-aborted",
                        state = SecureContentState.ABORTED,
                    ),
            )
        val store = FakeStore(aborted)
        val coordinator = SecureMessageMediaCoordinator(store)

        coordinator.retireTerminalAttempts(aborted.accountUuid, aborted.messageUuid!!)

        assertNull(store.find(aborted.accountUuid, aborted.contentId))
    }

    @Test
    fun retireDeletesOnlyResolvedSecureMediaRelation() {
        val snapshot = committedObject()
        val store = FakeStore(snapshot)
        val coordinator = SecureMessageMediaCoordinator(store)

        assertTrue(coordinator.retire(snapshot.accountUuid, snapshot.messageUuid!!))
        assertEquals(snapshot.accountUuid to snapshot.contentId, store.deleted)
        assertFalse(coordinator.retire(snapshot.accountUuid, snapshot.messageUuid!!))
    }

    private fun committedObject(): SecureContentObject =
        SecureContentObject(
            SecureContentMetadata(
                accountUuid = "account-a",
                contentId = "content-a",
                namespace = SecureMessageMediaCoordinator.NAMESPACE,
                messageUuid = "message-a",
                mimeType = "image/jpeg",
                sizeBytes = 7,
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
        vararg initial: SecureContentObject,
    ) : SecureContentStore {
        private val objects = initial.associateBy { it.contentId }.toMutableMap()
        var deleted: Pair<String, String>? = null

        override fun allocate(metadata: SecureContentMetadata): SecureContentHandle =
            throw UnsupportedOperationException()

        override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession =
            throw UnsupportedOperationException()

        override fun find(accountUuid: String, contentId: String): SecureContentObject? =
            objects[contentId]?.takeIf { it.accountUuid == accountUuid }

        override fun findByMessage(accountUuid: String, messageUuid: String): List<SecureContentObject> =
            objects.values.filter { it.accountUuid == accountUuid && it.messageUuid == messageUuid }

        override fun open(accountUuid: String, contentId: String): SecureContentReadSession =
            throw UnsupportedOperationException()

        override fun delete(accountUuid: String, contentId: String) {
            objects.remove(contentId)
            deleted = accountUuid to contentId
        }
    }
}
