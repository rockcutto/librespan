package eu.siacs.conversations.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDownloadConnectionAutoDownloadPolicyTest {
    @Test
    fun secureResumeRequiresExactContentRangeOffset() {
        assertTrue(
            SecureHttpDownloadConnection.isResumeContentRange(
                "bytes 1048576-9437183/9437184",
                1048576L,
            ),
        )
        assertFalse(
            SecureHttpDownloadConnection.isResumeContentRange(
                "bytes 0-9437183/9437184",
                1048576L,
            ),
        )
        assertFalse(
            SecureHttpDownloadConnection.isResumeContentRange(
                null,
                1048576L,
            ),
        )
    }

    @Test
    fun secureIncomingStagingDoesNotNeedLegacyStoragePermission() {
        assertTrue(
            HttpDownloadConnection.isAutoDownloadStorageReady(
                true,
                false,
                false,
            ),
        )
    }

    @Test
    fun plaintextPrivateReceiveDoesNotNeedLegacyStoragePermission() {
        assertTrue(
            HttpDownloadConnection.isAutoDownloadStorageReady(
                false,
                false,
                false,
            ),
        )
        assertTrue(
            HttpDownloadConnection.isAutoDownloadStorageReady(
                false,
                false,
                true,
            ),
        )
    }
}
