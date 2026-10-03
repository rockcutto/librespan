package eu.siacs.conversations.storage.secure

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOutgoingLegacyMediaRetirementTest {
    @Test
    fun keepsLegacyFileWhenSecureRelationDoesNotExist() {
        val file = Files.createTempFile("legacy-media", ".bin").toFile()
        try {
            val retired =
                SecureOutgoingLegacyMediaRetirement(FakeStore())
                    .retireIfSecurePublished("account-a", "message-a", file)

            assertFalse(retired)
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun removesLegacyFileOnlyAfterCommittedSecurePublication() {
        val file = Files.createTempFile("legacy-media", ".bin").toFile()
        val media =
            SecureContentObject(
                SecureContentMetadata(
                    accountUuid = "account-a",
                    contentId = "content-a",
                    namespace = SecureMessageMediaCoordinator.NAMESPACE,
                    messageUuid = "message-a",
                    state = SecureContentState.COMMITTED,
                    cryptoVersion = 1,
                ),
            )

        val retired =
            SecureOutgoingLegacyMediaRetirement(FakeStore(media))
                .retireIfSecurePublished("account-a", "message-a", file)

        assertTrue(retired)
        assertFalse(file.exists())
    }

    private class FakeStore(vararg initial: SecureContentObject) : SecureContentStore {
        private val objects = initial.associateBy { it.contentId }.toMutableMap()

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
        }
    }
}
