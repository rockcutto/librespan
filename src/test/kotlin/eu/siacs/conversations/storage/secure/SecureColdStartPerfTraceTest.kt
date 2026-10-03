package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureColdStartPerfTraceTest {
    @Test
    fun finishedReportIgnoresBackgroundMaintenanceMetrics() {
        SecureColdStartPerfTrace.clear()
        SecureColdStartPerfTrace.start()
        SecureColdStartPerfTrace.increment("before_finish", 1)
        SecureColdStartPerfTrace.finish()
        SecureColdStartPerfTrace.increment("after_finish", 1)
        SecureColdStartPerfTrace.stage("after_finish_stage", 10_000_000L)

        val report = SecureColdStartPerfTrace.report()
        assertTrue(report.contains("before_finish=1"))
        assertFalse(report.contains("after_finish=1"))
        assertFalse(report.contains("after_finish_stage="))
    }

    @Test
    fun reportContainsOnlyAggregateDiagnostics() {
        SecureColdStartPerfTrace.clear()
        SecureColdStartPerfTrace.start()
        SecureColdStartPerfTrace.stage("message_db_read", 2_000_000L)
        SecureColdStartPerfTrace.increment("messages_loaded", 12)
        SecureColdStartPerfTrace.milestone("first_message_batch_visible")
        SecureColdStartPerfTrace.finish()

        val report = SecureColdStartPerfTrace.report()
        assertTrue(report.contains("message_db_read=2ms"))
        assertTrue(report.contains("messages_loaded=12"))
        assertTrue(report.contains("first_message_batch_visible="))
        assertTrue(report.contains("status=ready"))
        assertFalse(report.contains("example.test"))
    }
}
