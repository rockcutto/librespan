package eu.siacs.conversations.storage.secure

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureMessageRetirementBoundaryTest {
    @Test
    fun messageRetirementRemovesMediaBeforePayloadGateReturns() {
        val media = committedMedia("message-a", "content-a")
        val store = FakeStore(media)
        val payload = FakePayloadCoordinator()
        val boundary = SecureMessageRetirementBoundary(payload, store)

        boundary.retireMessage("account-a", "message-a")

        assertNull(store.find("account-a", "content-a"))
        assertEquals(listOf("account-a" to "message-a"), payload.retiredMessages)
    }

    @Test
    fun mediaRetirementDoesNotRequireProtectedTextRollout() {
        val media = committedMedia("message-a", "content-a")
        val store = FakeStore(media)
        val boundary = SecureMessageRetirementBoundary(null, store)

        boundary.retireMessage("account-a", "message-a")

        assertNull(store.find("account-a", "content-a"))
    }

    @Test
    fun conversationRetirementUsesKnownMessageIdsBeforeBulkPayloadRetirement() {
        val first = committedMedia("message-a", "content-a")
        val second = committedMedia("message-b", "content-b")
        val store = FakeStore(first, second)
        val payload = FakePayloadCoordinator()
        val boundary = SecureMessageRetirementBoundary(payload, store)

        boundary.retireConversation(
            accountUuid = "account-a",
            conversationUuid = "conversation-a",
            mediaMessageUuids = listOf("message-a", "message-a", "message-b"),
        )

        assertNull(store.find("account-a", "content-a"))
        assertNull(store.find("account-a", "content-b"))
        assertEquals(listOf("account-a" to "conversation-a"), payload.retiredConversations)
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

    private class FakePayloadCoordinator : SecureMessagePayloadCoordinator {
        val retiredMessages = mutableListOf<Pair<String, String>>()
        val retiredConversations = mutableListOf<Pair<String, String>>()

        override fun beginWrite(context: SecureMessagePayloadContext): SecureMessagePayloadWriteSession =
            throw UnsupportedOperationException()

        override fun beginOutgoingTextWrite(
            context: SecureOutgoingTextPayloadContext,
        ): SecureMessagePayloadWriteSession = throw UnsupportedOperationException()

        override fun beginProtectedTextWrite(
            context: SecureMessagePayloadContext,
        ): SecureMessagePayloadWriteSession = throw UnsupportedOperationException()

        override fun outgoingTextPayloadMode(
            context: SecureOutgoingTextPayloadContext,
        ): SecureMessagePayloadMode? = null

        override fun protectedTextPayloadMode(
            context: SecureMessagePayloadContext,
        ): SecureMessagePayloadMode? = null

        override fun openOutgoingTextPayload(
            context: SecureOutgoingTextPayloadContext,
        ): SecureMessagePayloadReadSession = throw IOException("unavailable")

        override fun openProtectedTextPayload(
            context: SecureMessagePayloadContext,
        ): SecureMessagePayloadReadSession = throw IOException("unavailable")

        override fun open(reference: SecureMessagePayloadReference): SecureContentReadSession =
            throw IOException("unavailable")

        override fun open(accountUuid: String, messageUuid: String): SecureMessagePayloadReadSession =
            throw IOException("unavailable")

        override fun retire(reference: SecureMessagePayloadReference) {
            retiredMessages += reference.accountUuid to reference.messageUuid
        }

        override fun retire(accountUuid: String, messageUuid: String) {
            retiredMessages += accountUuid to messageUuid
        }

        override fun retireAllForConversation(accountUuid: String, conversationUuid: String) {
            retiredConversations += accountUuid to conversationUuid
        }

        override fun recoverInterruptedPublications() = Unit

        override fun recoverInterruptedRetirements() = Unit
    }
}
