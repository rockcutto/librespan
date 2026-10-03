package eu.siacs.conversations.entities

import eu.siacs.conversations.utils.MimeUtils
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SecureMediaPresentationMetadataTest {
    @Test
    fun canonicalSecureMetadataOverridesLegacyMimeAndProvidesDisplayFacts() {
        val message = message("https://example.test/legacy.bin").apply {
            setSecureMediaPresentationMetadata(
                "application/pdf",
                "report.pdf",
                5120L,
            )
        }

        assertEquals("application/pdf", message.mimeType)
        assertEquals("report.pdf", message.secureMediaFileName)
        assertEquals(5120L, message.secureMediaSizeBytes)
    }

    @Test
    fun genericSecureMimeRecoversVideoFromCanonicalFileName() {
        val message = message("https://example.test/legacy.bin").apply {
            setSecureMediaPresentationMetadata(
                "application/octet-stream; charset=binary",
                "clip.mp4",
                716800L,
            )
        }

        assertEquals("video/mp4", message.mimeType)
    }

    @Test
    fun missingStoreFilenameRecoversVideoFromMessageBody() {
        val body = "https://example.test/media/clip.mp4|716800|0|0"
        val presentationFileName =
            MimeUtils.resolvePresentationFileName(
                null,
                body,
            )
        val message =
            message(body).apply {
                setSecureMediaPresentationMetadata(
                    "application/octet-stream; charset=binary",
                    presentationFileName,
                    716800L,
                )
            }

        assertEquals("clip.mp4", presentationFileName)
        assertEquals("clip.mp4", message.secureMediaFileName)
        assertEquals("video/mp4", message.mimeType)
    }

    @Test
    fun explicitDocumentMimeWinsOverMisleadingVideoFileName() {
        val message = message("https://example.test/legacy.bin").apply {
            setSecureMediaPresentationMetadata(
                "application/pdf",
                "report.mp4",
                5120L,
            )
        }

        assertEquals("application/pdf", message.mimeType)
    }

    private fun message(body: String): Message {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation(
            "secure-metadata",
            account,
            Jid.of("peer@example.test"),
            Conversation.MODE_SINGLE,
            null,
        )
        return Message(conversation, body, Message.ENCRYPTION_NONE)
    }
}
