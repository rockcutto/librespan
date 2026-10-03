package eu.siacs.conversations.ui

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAlbumViewerMetadataPolicyTest {
    @Test
    fun unresolvedViewerMessageRequiresSecurePresentationHydration() {
        val message = message()
        assertTrue(
            MediaAlbumActivity.messagesNeedSecurePresentationMetadata(
                listOf(message),
            ),
        )
    }

    @Test
    fun canonicalSecureMetadataSkipsRedundantViewerHydration() {
        val message =
            message().apply {
                setSecureMediaPresentationMetadata(
                    "video/mp4",
                    "clip.mp4",
                    1024L,
                )
            }
        assertFalse(
            MediaAlbumActivity.messagesNeedSecurePresentationMetadata(
                listOf(message),
            ),
        )
    }

    @Test
    fun emptyViewerSequenceNeedsNoHydration() {
        assertFalse(
            MediaAlbumActivity.messagesNeedSecurePresentationMetadata(
                emptyList(),
            ),
        )
    }

    private fun message(): Message {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation =
            Conversation(
                "viewer-metadata",
                account,
                Jid.of("peer@example.test"),
                Conversation.MODE_SINGLE,
                null,
            )
        return Message(conversation, "", Message.ENCRYPTION_NONE)
    }
}
