package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureMessageNotificationMediaPolicyTest {
    @Test
    fun legacyIsAllowedOnlyWhenNoSecureRelationExists() {
        val policy = SecureMessageNotificationMediaPolicy(FakeStore())

        assertEquals(
            SecureMessageNotificationMediaPolicy.Decision.LEGACY_ALLOWED,
            policy.decide("account-a", "message-a"),
        )
    }

    @Test
    fun committedSecureMediaSuppressesLegacyNotificationUri() {
        val media = committedMedia("message-a", "content-a")
        val policy = SecureMessageNotificationMediaPolicy(FakeStore(media))

        assertEquals(
            SecureMessageNotificationMediaPolicy.Decision.SUPPRESS_LEGACY_MEDIA,
            policy.decide("account-a", "message-a"),
        )
    }

    private fun committedMedia(messageUuid: String, contentId: String) =
        SecureContentObject(
            SecureContentMetadata(
                accountUuid = "account-a",
                contentId = contentId,
                namespace = SecureMessageMediaCoordinator.NAMESPACE,
                messageUuid = messageUuid,
                state = SecureContentState.COMMITTED,
                cryptoVersion = 1,
            ),
        )

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
