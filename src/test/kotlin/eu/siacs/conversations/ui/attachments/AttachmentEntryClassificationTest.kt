package eu.siacs.conversations.ui.attachments

import eu.siacs.conversations.entities.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentEntryClassificationTest {

    @Test
    fun pdfStaysFileEvenWithStaleImageMessageTypeAndDimensions() {
        assertEquals(
            AttachmentEntry.Category.FILE,
            AttachmentEntry.classify(
                "application/pdf",
                Message.TYPE_IMAGE,
                1200,
                1600,
                0,
            ),
        )
    }

    @Test
    fun officeDocumentsStayFiles() {
        assertEquals(
            AttachmentEntry.Category.FILE,
            AttachmentEntry.classify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Message.TYPE_FILE,
                0,
                0,
                0,
            ),
        )
        assertEquals(
            AttachmentEntry.Category.FILE,
            AttachmentEntry.classify(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Message.TYPE_FILE,
                0,
                0,
                0,
            ),
        )
    }


    @Test
    fun genericApplicationMimeRecoversJpegFromVisibleFilename() {
        val mime =
            AttachmentEntry.resolvePresentationMime(
                "application/octet-stream; charset=binary",
                "9956.jpg",
            )

        assertEquals("image/jpeg", mime)
        assertEquals(
            AttachmentEntry.Category.MEDIA,
            AttachmentEntry.classify(mime, Message.TYPE_FILE, 0, 0, 0),
        )
    }

    @Test
    fun genericApplicationMimeRecoversVideoFromCanonicalFilename() {
        val mime =
            AttachmentEntry.resolvePresentationMime(
                "application/octet-stream; charset=binary",
                "clip.mp4",
            )

        assertEquals("video/mp4", mime)
        assertEquals(
            AttachmentEntry.Category.MEDIA,
            AttachmentEntry.classify(mime, Message.TYPE_FILE, 0, 0, 0),
        )
    }

    @Test
    fun explicitPdfMimeWinsOverMisleadingImageFilename() {
        val mime =
            AttachmentEntry.resolvePresentationMime(
                "application/pdf",
                "report.jpg",
            )

        assertEquals("application/pdf", mime)
        assertEquals(
            AttachmentEntry.Category.FILE,
            AttachmentEntry.classify(mime, Message.TYPE_IMAGE, 1200, 1600, 0),
        )
    }

    @Test
    fun explicitPdfMimeWinsOverMisleadingVideoFilename() {
        val mime =
            AttachmentEntry.resolvePresentationMime(
                "application/pdf",
                "report.mp4",
            )

        assertEquals("application/pdf", mime)
        assertEquals(
            AttachmentEntry.Category.FILE,
            AttachmentEntry.classify(mime, Message.TYPE_FILE, 0, 0, 0),
        )
    }

    @Test
    fun nonstandardApplicationMimeCanRecoverFromMediaDimensions() {
        assertEquals(
            AttachmentEntry.Category.MEDIA,
            AttachmentEntry.classify(
                "application/x-unknown-media",
                Message.TYPE_FILE,
                1280,
                720,
                0,
            ),
        )
    }

    @Test
    fun imageVideoAndAudioStillUseTheirExpectedCategories() {
        assertEquals(
            AttachmentEntry.Category.MEDIA,
            AttachmentEntry.classify("image/jpeg", Message.TYPE_FILE, 0, 0, 0),
        )
        assertEquals(
            AttachmentEntry.Category.MEDIA,
            AttachmentEntry.classify("video/mp4", Message.TYPE_FILE, 0, 0, 0),
        )
        assertEquals(
            AttachmentEntry.Category.AUDIO,
            AttachmentEntry.classify("audio/ogg", Message.TYPE_FILE, 0, 0, 0),
        )
    }
}
