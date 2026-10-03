package eu.siacs.conversations.storage.secure

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageMediaLegacyBoundaryTest {
    @Test
    fun legacyAllowedOnlyWhenNoSecureRelationExists() {
        val boundary = SecureMessageMediaLegacyBoundary(FakeStore())
        boundary.requireLegacyAllowed("account-a", "message-a")
        assertFalse(boundary.isSecure("account-a", "message-a"))
    }

    @Test
    fun committedSecureRelationBlocksLegacyAccess() {
        val snapshot = committedObject()
        val boundary = SecureMessageMediaLegacyBoundary(FakeStore(snapshot))

        assertTrue(boundary.isSecure(snapshot.accountUuid, snapshot.messageUuid!!))
        expectIOException {
            boundary.requireLegacyAllowed(snapshot.accountUuid, snapshot.messageUuid!!)
        }
    }

    @Test
    fun incompleteOrAmbiguousSecureRelationCannotFallbackToLegacy() {
        val committed = committedObject()
        val incomplete =
            committed.copy(metadata = committed.metadata.copy(state = SecureContentState.WRITING))
        expectIOException {
            SecureMessageMediaLegacyBoundary(FakeStore(incomplete))
                .requireLegacyAllowed(incomplete.accountUuid, incomplete.messageUuid!!)
        }

        val duplicate = committed.copy(metadata = committed.metadata.copy(contentId = "content-b"))
        expectIOException {
            SecureMessageMediaLegacyBoundary(FakeStore(committed, duplicate))
                .requireLegacyAllowed(committed.accountUuid, committed.messageUuid!!)
        }
    }

    private fun committedObject(): SecureContentObject =
        SecureContentObject(
            SecureContentMetadata(
                accountUuid = "account-a",
                contentId = "content-a",
                namespace = SecureMessageMediaCoordinator.NAMESPACE,
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

        override fun allocate(metadata: SecureContentMetadata): SecureContentHandle =
            throw UnsupportedOperationException()

        override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession =
            throw UnsupportedOperationException()

        override fun find(accountUuid: String, contentId: String): SecureContentObject? =
            snapshots.firstOrNull { it.accountUuid == accountUuid && it.contentId == contentId }

        override fun findByMessage(accountUuid: String, messageUuid: String): List<SecureContentObject> =
            snapshots.filter { it.accountUuid == accountUuid && it.messageUuid == messageUuid }

        override fun open(accountUuid: String, contentId: String): SecureContentReadSession =
            throw UnsupportedOperationException()

        override fun delete(accountUuid: String, contentId: String) = Unit
    }
}
