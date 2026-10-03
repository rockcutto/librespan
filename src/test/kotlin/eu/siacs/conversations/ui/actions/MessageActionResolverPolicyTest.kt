package eu.siacs.conversations.ui.actions

import eu.siacs.conversations.entities.Message
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionResolverPolicyTest {

    @Test
    fun imagePreviewDoesNotOfferSaveToDownloads() {
        assertFalse(
            shouldOfferSaveToDownloads(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_IMAGE,
                mime = "image/jpeg",
                width = 0,
                height = 0
            )
        )
    }

    @Test
    fun visualFileDoesNotOfferSaveToDownloads() {
        assertFalse(
            shouldOfferSaveToDownloads(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_FILE,
                mime = null,
                width = 1920,
                height = 1080
            )
        )
    }

    @Test
    fun visualMediaOffersSaveToGallery() {
        assertTrue(
            shouldOfferSaveToGallery(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_IMAGE,
                mime = "image/jpeg",
                width = 0,
                height = 0
            )
        )
        assertTrue(
            shouldOfferSaveToGallery(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_FILE,
                mime = "video/mp4",
                width = 1920,
                height = 1080
            )
        )
    }

    @Test
    fun nonVisualOrUnavailableMediaDoesNotOfferSaveToGallery() {
        assertFalse(
            shouldOfferSaveToGallery(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_FILE,
                mime = "application/pdf",
                width = 0,
                height = 0
            )
        )
        assertFalse(
            shouldOfferSaveToGallery(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = true,
                messageType = Message.TYPE_IMAGE,
                mime = "image/jpeg",
                width = 1920,
                height = 1080
            )
        )
    }

    @Test
    fun authenticatedPdfOverridesStaleImageHints() {
        assertFalse(
            shouldOfferSaveToGallery(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_IMAGE,
                mime = "application/pdf",
                width = 1920,
                height = 1080
            )
        )
        assertTrue(
            shouldOfferSaveToDownloads(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_IMAGE,
                mime = "application/pdf",
                width = 1920,
                height = 1080
            )
        )
    }

    @Test
    fun genericFileStillOffersSaveToDownloads() {
        assertTrue(
            shouldOfferSaveToDownloads(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = false,
                messageType = Message.TYPE_FILE,
                mime = "application/pdf",
                width = 0,
                height = 0
            )
        )
    }

    @Test
    fun unavailableFileDoesNotOfferSaveToDownloads() {
        assertFalse(
            shouldOfferSaveToDownloads(
                isFileOrImage = true,
                isDeleted = true,
                cancelable = false,
                messageType = Message.TYPE_FILE,
                mime = "application/pdf",
                width = 0,
                height = 0
            )
        )
        assertFalse(
            shouldOfferSaveToDownloads(
                isFileOrImage = true,
                isDeleted = false,
                cancelable = true,
                messageType = Message.TYPE_FILE,
                mime = "application/pdf",
                width = 0,
                height = 0
            )
        )
    }

    @Test
    fun openWithIsReservedForAudio() {
        assertTrue(shouldOfferOpenWith("audio/ogg"))
        assertFalse(shouldOfferOpenWith("image/jpeg"))
        assertFalse(shouldOfferOpenWith("application/pdf"))
        assertFalse(shouldOfferOpenWith(null))
    }
}
