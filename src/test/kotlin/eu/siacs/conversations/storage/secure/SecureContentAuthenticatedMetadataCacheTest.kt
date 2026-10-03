package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureContentAuthenticatedMetadataCacheTest {
    @Test
    fun rebuildProvidesExactAccountScopedLookup() {
        val cache = SecureContentAuthenticatedMetadataCache()
        cache.rebuild(
            listOf(
                record("account-a", "content-1", "message-1"),
                record("account-b", "content-1", "message-2"),
            ),
        )

        assertEquals(
            "message-1",
            cache.find("account-a", "content-1")?.messageUuid,
        )
        assertEquals(
            "message-2",
            cache.find("account-b", "content-1")?.messageUuid,
        )
        assertNull(cache.find("account-c", "content-1"))
    }

    @Test
    fun putReplacesOnlyExactIdentityAndSnapshotsAccountRows() {
        val cache = SecureContentAuthenticatedMetadataCache()
        cache.rebuild(
            listOf(
                record("account-a", "content-1", "message-1"),
                record("account-a", "content-2", "message-2"),
            ),
        )

        cache.put(
            record(
                "account-a",
                "content-1",
                "message-1",
                SecureContentState.FAILED,
            ),
        )

        assertEquals(
            SecureContentState.FAILED,
            cache.find("account-a", "content-1")?.metadata?.state,
        )
        assertEquals(
            listOf("content-1", "content-2"),
            cache.findByAccount("account-a").map { it.contentId },
        )
    }

    @Test
    fun removeAndRebuildDoNotRetainStaleRecords() {
        val cache = SecureContentAuthenticatedMetadataCache()
        cache.rebuild(listOf(record("account-a", "old", "message-1")))
        cache.remove("account-a", "old")
        assertEquals(emptyList<SecureContentMetadataRecord>(), cache.allRecords())

        cache.put(record("account-a", "stale", "message-2"))
        cache.rebuild(listOf(record("account-a", "fresh", "message-3")))

        assertNull(cache.find("account-a", "stale"))
        assertEquals(
            listOf("fresh"),
            cache.allRecords().map { it.contentId },
        )
    }

    private fun record(
        accountUuid: String,
        contentId: String,
        messageUuid: String?,
        state: SecureContentState = SecureContentState.COMMITTED,
    ): SecureContentMetadataRecord =
        SecureContentMetadataRecord(
            metadata =
                SecureContentMetadata(
                    accountUuid = accountUuid,
                    contentId = contentId,
                    namespace = SecureMessageMediaCoordinator.NAMESPACE,
                    messageUuid = messageUuid,
                    state = state,
                    cryptoVersion = 1,
                    createdAt = 1L,
                ),
            storageLocator = "blob-$contentId",
            updatedAt = 1L,
        )
}
