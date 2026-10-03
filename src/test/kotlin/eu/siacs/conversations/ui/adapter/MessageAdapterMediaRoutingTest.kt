package eu.siacs.conversations.ui.adapter

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAdapterMediaRoutingTest {
    @Test
    fun singleImageUsesInternalMediaViewerWithoutLegacyMimeGuessing() {
        val image = message().apply { type = Message.TYPE_IMAGE }
        assertTrue(MessageAdapter.shouldOpenInMediaViewer(image))
    }

    @Test
    fun securePdfDoesNotUseImagePreviewEvenWithStaleImageType() {
        val pdf =
            message().apply {
                type = Message.TYPE_IMAGE
                setSecureMediaPresentationMetadata("application/pdf", "report.pdf", 1234L)
            }

        assertFalse(MessageAdapter.shouldDisplayMediaPreview(pdf))
        assertFalse(MessageAdapter.shouldOpenInMediaViewer(pdf))
    }

    @Test
    fun secureVideoUsesPreviewAndInternalMediaViewer() {
        val video =
            message().apply {
                type = Message.TYPE_FILE
                setSecureMediaPresentationMetadata("video/mp4", "clip.mp4", 1234L)
            }

        assertTrue(MessageAdapter.shouldDisplayMediaPreview(video))
        assertTrue(MessageAdapter.shouldOpenInMediaViewer(video))
    }

    @Test
    fun secureVideoCanRecoverMimeFromCanonicalFileName() {
        val video =
            message().apply {
                type = Message.TYPE_FILE
                setSecureMediaPresentationMetadata(null, "clip.mp4", 1234L)
            }

        assertEquals("video/mp4", video.mimeType)
        assertTrue(MessageAdapter.shouldDisplayMediaPreview(video))
        assertTrue(MessageAdapter.shouldOpenInMediaViewer(video))
    }

    @Test
    fun secureVideoCanRecoverFromGenericMimeAndCanonicalFileName() {
        val video =
            message().apply {
                type = Message.TYPE_FILE
                setSecureMediaPresentationMetadata(
                    "application/octet-stream; charset=binary",
                    "clip.mp4",
                    1234L,
                )
            }

        assertEquals("video/mp4", video.mimeType)
        assertTrue(MessageAdapter.shouldDisplayMediaPreview(video))
        assertTrue(MessageAdapter.shouldOpenInMediaViewer(video))
    }

    @Test
    fun secureImageUsesPreviewAndInternalViewer() {
        val image =
            message().apply {
                type = Message.TYPE_IMAGE
                setSecureMediaPresentationMetadata("image/jpeg", "photo.jpg", 1234L)
            }

        assertTrue(MessageAdapter.shouldDisplayMediaPreview(image))
        assertTrue(MessageAdapter.shouldOpenInMediaViewer(image))
    }

    @Test
    fun outgoingCaptionKeepsStatusInsideFooter() {
        assertTrue(MessageAdapter.shouldOverlayStatusOnMedia(true, false, true))
        assertFalse(MessageAdapter.shouldOverlayStatusOnMedia(true, true, true))
        assertTrue(MessageAdapter.shouldOverlayStatusOnMedia(true, true, false))
        assertFalse(MessageAdapter.shouldOverlayStatusOnMedia(false, false, true))
    }

    @Test
    fun narrowMediaStacksReactionRailAboveStatusInsteadOfOverlapping() {
        assertTrue(MessageAdapter.shouldStackMediaOverlays(120, 62, 58, 6))
        assertFalse(MessageAdapter.shouldStackMediaOverlays(180, 62, 58, 6))
    }

    @Test
    fun shortTextBubbleUsesMetadataAsMinimumWidth() {
        assertEquals(74, MessageAdapter.requiredMetadataWidth(58, 16))
        assertEquals(58, MessageAdapter.requiredMetadataWidth(58, 0))
        assertEquals(0, MessageAdapter.requiredMetadataWidth(0, 0))
    }

    @Test
    fun narrowPortraitPreviewIsClampedToNineBySixteenViewport() {
        assertEquals(158, MessageAdapter.clampPortraitPreviewWidth(90, 280))
        assertEquals(180, MessageAdapter.clampPortraitPreviewWidth(180, 280))
        assertEquals(280, MessageAdapter.clampPortraitPreviewWidth(280, 280))
        assertEquals(320, MessageAdapter.clampPortraitPreviewWidth(320, 180))
    }

    @Test
    fun secureImageWithoutDimensionsStillRendersMediaPreview() {
        val image =
            message("https://example.test/photo.jpg|716800").apply {
                type = Message.TYPE_IMAGE
                setSecureMediaPresentationMetadata("image/jpeg", "photo.jpg", 716800L)
            }

        assertTrue(MessageAdapter.shouldDisplayMediaPreview(image))
        assertTrue(MessageAdapter.shouldRenderMediaPreview(image))
    }

    @Test
    fun secureVideoWithoutDimensionsStillRendersMediaPreview() {
        val video =
            message("https://example.test/clip.mp4|716800").apply {
                type = Message.TYPE_FILE
                setSecureMediaPresentationMetadata("video/mp4", "clip.mp4", 716800L)
            }

        assertTrue(MessageAdapter.shouldDisplayMediaPreview(video))
        assertTrue(MessageAdapter.shouldRenderMediaPreview(video))
    }

    private fun message(body: String = ""): Message {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation(
            "media-routing",
            account,
            Jid.of("peer@example.test"),
            Conversation.MODE_SINGLE,
            null,
        )
        return Message(conversation, body, Message.ENCRYPTION_NONE)
    }
}
