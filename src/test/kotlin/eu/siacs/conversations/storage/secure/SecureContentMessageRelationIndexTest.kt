package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureContentMessageRelationIndexTest {
    @Test
    fun rebuildPreservesAccountScopeAndDuplicateCandidates() {
        val index = SecureContentMessageRelationIndex()
        index.rebuild(
            listOf(
                record("account-a", "content-1", "message-1"),
                record("account-a", "content-2", "message-1"),
                record("account-b", "content-3", "message-1"),
                record("account-a", "content-4", null),
            ),
        )

        assertEquals(
            listOf("content-1", "content-2"),
            index.contentIds("account-a", "message-1"),
        )
        assertEquals(
            listOf("content-3"),
            index.contentIds("account-b", "message-1"),
        )
        assertEquals(emptyList<String>(), index.contentIds("account-a", "missing"))
    }

    @Test
    fun addAndRemoveUpdateOnlyExactRelation() {
        val index = SecureContentMessageRelationIndex()
        val first = record("account-a", "content-1", "message-1")
        val second = record("account-a", "content-2", "message-1")
        val other = record("account-a", "content-3", "message-2")

        index.add(first)
        index.add(second)
        index.add(other)
        index.remove(first)

        assertEquals(listOf("content-2"), index.contentIds("account-a", "message-1"))
        assertEquals(listOf("content-3"), index.contentIds("account-a", "message-2"))

        index.remove("account-a", "message-1", "content-2")
        assertEquals(emptyList<String>(), index.contentIds("account-a", "message-1"))
    }

    @Test
    fun rebuildReplacesPreviousProcessSnapshot() {
        val index = SecureContentMessageRelationIndex()
        index.add(record("account-a", "old-content", "message-1"))

        index.rebuild(listOf(record("account-a", "new-content", "message-1")))

        assertEquals(listOf("new-content"), index.contentIds("account-a", "message-1"))
    }

    private fun record(
        accountUuid: String,
        contentId: String,
        messageUuid: String?,
    ): SecureContentMetadataRecord =
        SecureContentMetadataRecord(
            metadata =
                SecureContentMetadata(
                    accountUuid = accountUuid,
                    contentId = contentId,
                    namespace = SecureMessageMediaCoordinator.NAMESPACE,
                    messageUuid = messageUuid,
                    state = SecureContentState.COMMITTED,
                    cryptoVersion = 1,
                    createdAt = 1L,
                ),
            storageLocator = "blob-$contentId",
            updatedAt = 1L,
        )
}
