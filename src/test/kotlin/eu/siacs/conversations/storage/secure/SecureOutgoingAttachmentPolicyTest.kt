package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOutgoingAttachmentPolicyTest {
    @Test
    fun externalAudioDoesNotRequireRecorderOwnership() {
        for (mime in listOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac",
            "audio/ogg", "audio/opus", " AUDIO/OGG ")) {
            assertTrue(SecureOutgoingAttachmentPolicy.isAudioAttachment(mime))
            assertFalse(SecureOutgoingAttachmentPolicy.isInlineVoiceRecording(mime, false))
        }
        // This ambiguous container already uses generic secure ingress; do not reclassify it.
        assertTrue(SecureOutgoingAttachmentPolicy.isSimpleFile("application/ogg"))
        for (mime in listOf(null, "image/jpeg", "video/mp4", "application/pdf", "application/ogg")) {
            assertFalse(SecureOutgoingAttachmentPolicy.isAudioAttachment(mime))
        }
    }

    @Test
    fun acceptsGenericFilesAndDocuments() {
        assertTrue(SecureOutgoingAttachmentPolicy.isSimpleFile(null))
        assertTrue(SecureOutgoingAttachmentPolicy.isSimpleFile("application/pdf"))
        assertTrue(SecureOutgoingAttachmentPolicy.isSimpleFile("application/octet-stream"))
        assertTrue(SecureOutgoingAttachmentPolicy.isSimpleFile("text/plain"))
    }

    @Test
    fun routesImagesThroughTheirSecureProducerCheckpoint() {
        assertFalse(SecureOutgoingAttachmentPolicy.isSimpleFile("image/jpeg"))
        assertTrue(SecureOutgoingAttachmentPolicy.isImageAttachment("image/jpeg"))
        assertTrue(SecureOutgoingAttachmentPolicy.isImageAttachment(" IMAGE/PNG "))
        assertFalse(SecureOutgoingAttachmentPolicy.isImageAttachment("video/mp4"))
        assertFalse(SecureOutgoingAttachmentPolicy.isImageAttachment(null))
    }

    @Test
    fun routesVideoThroughItsSecureProducerCheckpoint() {
        assertFalse(SecureOutgoingAttachmentPolicy.isSimpleFile("audio/ogg"))
        assertFalse(SecureOutgoingAttachmentPolicy.isSimpleFile("video/mp4"))
        assertTrue(SecureOutgoingAttachmentPolicy.isVideoAttachment("video/mp4"))
        assertTrue(SecureOutgoingAttachmentPolicy.isVideoAttachment(" VIDEO/WEBM "))
        assertFalse(SecureOutgoingAttachmentPolicy.isVideoAttachment("image/jpeg"))
        assertFalse(SecureOutgoingAttachmentPolicy.isVideoAttachment(null))
    }

    @Test
    fun acceptsOnlyAppOwnedCompletedVoiceRecordings() {
        assertTrue(SecureOutgoingAttachmentPolicy.isInlineVoiceRecording("audio/ogg", true))
        assertTrue(SecureOutgoingAttachmentPolicy.isInlineVoiceRecording(" AUDIO/MP4 ", true))
        assertFalse(SecureOutgoingAttachmentPolicy.isInlineVoiceRecording("audio/ogg", false))
        assertFalse(SecureOutgoingAttachmentPolicy.isInlineVoiceRecording("video/mp4", true))
        assertFalse(SecureOutgoingAttachmentPolicy.isInlineVoiceRecording(null, true))
    }
}
