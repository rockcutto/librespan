package eu.siacs.conversations.storage.secure

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOutgoingAttachmentStagingRecoveryTest {
    @Test
    fun restartSweepRetiresOnlyStaleFilesInKnownPrivateStagingRoots() {
        val cache = Files.createTempDirectory("secure-staging-recovery").toFile()
        val now = 2 * SecureOutgoingAttachmentStagingRecovery.ORPHAN_TTL_MILLIS
        try {
            val stale = stagingFile(cache, "VoiceRecordings", "stale.ogg")
            val fresh = stagingFile(cache, "SecureOutgoingVideos", "fresh.mp4")
            val outside = stagingFile(cache, "unrelated", "legacy.bin")
            stale.setLastModified(now - SecureOutgoingAttachmentStagingRecovery.ORPHAN_TTL_MILLIS - 1)
            fresh.setLastModified(now)
            outside.setLastModified(now - SecureOutgoingAttachmentStagingRecovery.ORPHAN_TTL_MILLIS - 1)

            assertEquals(
                1,
                SecureOutgoingAttachmentStagingRecovery.sweepOrphans(cache, now),
            )
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
            assertTrue(outside.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun stagingFile(cache: File, directory: String, name: String): File =
        File(File(cache, directory).apply { mkdirs() }, name).apply { writeText("staging") }
}
