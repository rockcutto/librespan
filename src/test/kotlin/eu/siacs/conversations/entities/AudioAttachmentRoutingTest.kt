package eu.siacs.conversations.entities

import eu.siacs.conversations.ui.attachments.AttachmentEntry
import eu.siacs.conversations.utils.AudioAttachmentPresentation.Tracks
import eu.siacs.conversations.utils.MimeUtils
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.*
import org.junit.Test

class AudioAttachmentRoutingTest {
    @Test
    fun zeroDurationFilesUseSameAudioIdentityInBothDirectionsAndConversationModes() {
        val formats = mapOf(
            "track.mp3" to "audio/mpeg", "track.m4a" to "audio/mp4",
            "track.aac" to "audio/aac", "track.wav" to "audio/x-wav",
            "track.flac" to "audio/flac", "track.opus" to "audio/opus",
            "track.oga" to "audio/ogg", "track.amr" to "audio/amr", "track.awb" to "audio/amr-wb",
        )
        for (mode in listOf(Conversation.MODE_SINGLE, Conversation.MODE_MULTI)) {
            for (status in listOf(Message.STATUS_RECEIVED, Message.STATUS_SEND)) {
                for ((name, mime) in formats) {
                    val message = message(mode, status, name, mime)
                    assertEquals(0, message.fileParams.runtime)
                    assertTrue("$name/$mode/$status", message.isAudioAttachment(Tracks.UNKNOWN))
                    assertEquals(Message.TYPE_FILE, message.type)
                    assertEquals(AttachmentEntry.Category.AUDIO,
                        AttachmentEntry.classify(message.mimeType, message.type, 0, 0, 0))
                }
            }
        }
    }

    @Test
    fun octetStreamRecoversOnlyKnownExtensionAndExplicitDocumentWins() {
        val mp3 = message(Conversation.MODE_MULTI, Message.STATUS_RECEIVED,
            "Song.MP3", "application/octet-stream; charset=binary")
        assertEquals("audio/mpeg", mp3.mimeType)
        assertTrue(mp3.isAudioAttachment(Tracks.UNKNOWN))
        assertEquals("audio/flac", MimeUtils.resolvePresentationMime("application/octet-stream", "track.flac"))
        assertEquals("audio/flac", MimeUtils.resolvePresentationMime("application/x-flac", "track.flac"))
        val pdf = message(Conversation.MODE_SINGLE, Message.STATUS_RECEIVED, "fake.mp3", "application/pdf")
        assertFalse(pdf.isAudioAttachment(Tracks.AUDIO))
        assertEquals("application/pdf", pdf.mimeType)
    }

    @Test
    fun oggRequiresTrackEvidenceRegardlessOfPersistedDuration() {
        val ogg = message(Conversation.MODE_MULTI, Message.STATUS_RECEIVED,
            "track.ogg", "application/octet-stream")
        assertEquals("application/ogg", ogg.mimeType)
        assertFalse(ogg.isAudioAttachment(Tracks.UNKNOWN))
        assertTrue(ogg.isAudioAttachment(Tracks.AUDIO))
        assertFalse(ogg.isAudioAttachment(Tracks.VIDEO))
        ogg.body = "https://example.test/track.ogg|100|0|0|45000"
        assertFalse(ogg.isAudioAttachment(Tracks.UNKNOWN))
        assertEquals(AttachmentEntry.Category.FILE,
            AttachmentEntry.classify(ogg.mimeType, ogg.type, 0, 0, 45000))
    }

    @Test
    fun audioOggWithZeroDurationAndDocumentsWithStaleRuntimeAreHandled() {
        assertTrue(message(Conversation.MODE_SINGLE, Message.STATUS_SEND,
            "voice.oga", "audio/ogg").isAudioAttachment(Tracks.UNKNOWN))
        for (mime in listOf("audio/midi", "audio/x-scpls", "audio/mpegurl", "application/pdf", "application/x-unknown")) {
            val document = message(Conversation.MODE_SINGLE, Message.STATUS_SEND, "document.dat", mime)
            document.body = "https://example.test/document.dat|100|0|0|45000"
            assertFalse(document.isAudioAttachment(Tracks.UNKNOWN))
            assertEquals(AttachmentEntry.Category.FILE,
                AttachmentEntry.classify(document.mimeType, document.type, 0, 0, 45000))
        }
    }

    @Test
    fun persistedHistoryRehydratesCanonicalIdentityWithoutChangingTheMessageType() {
        val restored = message(Conversation.MODE_SINGLE, Message.STATUS_RECEIVED, "opaque.bin", "application/octet-stream")
        restored.body = "https://example.test/opaque.bin|123|0|0|0"
        restored.resetFileParams()
        assertFalse(restored.isAudioAttachment(Tracks.UNKNOWN))
        // Same canonical metadata hydration used by SecureMessageMediaUiBridge after restart.
        restored.setSecureMediaPresentationMetadata("audio/mpeg", "original.mp3", 123L)
        assertTrue(restored.isAudioAttachment(Tracks.UNKNOWN))
        assertEquals(0, restored.fileParams.runtime)
        assertEquals(Message.TYPE_FILE, restored.type)
    }

    private fun message(mode: Int, status: Int, name: String, mime: String): Message {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation("audio", account, Jid.of("peer@example.test"), mode, null)
        return Message(conversation, "https://example.test/$name|100|0|0|0", Message.ENCRYPTION_NONE).apply {
            type = Message.TYPE_FILE
            this.status = status
            setSecureMediaPresentationMetadata(mime, name, 100L)
        }
    }
}
