package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureContentExportFileNamingTest {
    @Test
    fun preservesAuthenticatedOriginalFileName() {
        assertEquals(
            "quarterly report.pdf",
            SecureContentExportFileNaming.resolve(
                metadata(fileName = "quarterly report.pdf", mimeType = "application/pdf"),
                "message-id",
            ),
        )
    }

    @Test
    fun stripsProviderPathComponentsFromStoredName() {
        assertEquals(
            "report.docx",
            SecureContentExportFileNaming.resolve(
                metadata(fileName = "../private/report.docx", mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                "message-id",
            ),
        )
    }

    @Test
    fun fallsBackToMessageIdWithMimeExtension() {
        assertEquals(
            "message-id.pdf",
            SecureContentExportFileNaming.resolve(
                metadata(fileName = null, mimeType = "application/pdf"),
                "message-id",
            ),
        )
    }

    @Test
    fun fallsBackToMessageIdWhenMimeHasNoKnownExtension() {
        assertEquals(
            "message-id",
            SecureContentExportFileNaming.resolve(
                metadata(fileName = null, mimeType = "application/x-neocont-unknown"),
                "message-id",
            ),
        )
    }

    private fun metadata(
        fileName: String?,
        mimeType: String?,
    ): SecureContentMetadata =
        SecureContentMetadata(
            accountUuid = "account-id",
            contentId = "content-id",
            namespace = SecureMessageMediaCoordinator.NAMESPACE,
            messageUuid = "message-id",
            mimeType = mimeType,
            fileName = fileName,
            state = SecureContentState.COMMITTED,
            cryptoVersion = 1,
            createdAt = 1L,
        )
}
