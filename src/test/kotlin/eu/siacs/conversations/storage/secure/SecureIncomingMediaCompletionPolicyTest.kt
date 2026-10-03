package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureIncomingMediaCompletionPolicyTest {
    @Test
    fun secureIncomingImageCompletionIsStoreOnlyAndNeverMediaScanned() {
        assertEquals(
            SecureIncomingMediaCompletionPolicy.Visibility.SECURE_STORE_ONLY,
            SecureIncomingMediaCompletionPolicy.visibility(true),
        )
        assertFalse(SecureIncomingMediaCompletionPolicy.shouldScanLegacyMedia(true))
    }

    @Test
    fun secureIncomingVideoCompletionIsStoreOnlyAndNeverMediaScanned() {
        assertEquals(
            SecureIncomingMediaCompletionPolicy.Visibility.SECURE_STORE_ONLY,
            SecureIncomingMediaCompletionPolicy.visibility(true),
        )
        assertFalse(SecureIncomingMediaCompletionPolicy.shouldScanLegacyMedia(true))
    }

    @Test
    fun disabledRolloutPreservesLegacyCompatibility() {
        assertEquals(
            SecureIncomingMediaCompletionPolicy.Visibility.LEGACY_MEDIA_SCAN,
            SecureIncomingMediaCompletionPolicy.visibility(false),
        )
        assertTrue(SecureIncomingMediaCompletionPolicy.shouldScanLegacyMedia(false))
    }
}
