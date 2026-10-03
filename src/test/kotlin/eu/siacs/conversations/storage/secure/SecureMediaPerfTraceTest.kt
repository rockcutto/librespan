package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMediaPerfTraceTest {

    @Test
    fun reportOmitsCorrelationIdentityAndSensitiveMetadata() {
        val messageUuid = "message-secret-uuid"
        SecureMediaPerfTrace.clear()

        SecureMediaPerfTrace.start(
            direction = "outgoing",
            messageUuid = messageUuid,
            mimeType = "image/jpeg",
            sizeBytes = 2_621_440L,
        )
        SecureMediaPerfTrace.stage(messageUuid, "secure_write", 125_000_000L)
        SecureMediaPerfTrace.finish(messageUuid)

        val report = SecureMediaPerfTrace.report()

        assertTrue(report.contains("outgoing image"))
        assertTrue(report.contains("secure_write=125ms"))
        assertFalse(report.contains(messageUuid))
        assertFalse(report.contains("image/jpeg"))
        assertFalse(report.contains("content://"))
    }
}
