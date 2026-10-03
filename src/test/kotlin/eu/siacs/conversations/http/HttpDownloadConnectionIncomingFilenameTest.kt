package eu.siacs.conversations.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HttpDownloadConnectionIncomingFilenameTest {
    @Test
    fun protocolFilenameHasPriorityOverHttpAndUrl() {
        assertEquals(
            "protocol.docx",
            HttpDownloadConnection.resolveIncomingFileName(
                "protocol.docx",
                "attachment; filename=header.docx",
                "https://files.example.test/url.docx",
            ),
        )
    }

    @Test
    fun contentDispositionFilenameIsPersistableAndDoesNotExposePath() {
        assertEquals(
            "quarter report.docx",
            HttpDownloadConnection.resolveIncomingFileName(
                null,
                "attachment; filename*=UTF-8''quarter%20report.docx",
                "https://files.example.test/generated-id",
            ),
        )
        assertEquals(
            "report.docx",
            HttpDownloadConnection.resolveIncomingFileName(
                null,
                "attachment; filename=\"C:\\private\\report.docx\"",
                null,
            ),
        )
    }

    @Test
    fun urlBasenameIsLastFallback() {
        assertEquals(
            "archive.zip",
            HttpDownloadConnection.resolveIncomingFileName(
                null,
                null,
                "https://files.example.test/uploads/archive.zip",
            ),
        )
        assertNull(HttpDownloadConnection.resolveIncomingFileName(null, null, null))
    }
}
