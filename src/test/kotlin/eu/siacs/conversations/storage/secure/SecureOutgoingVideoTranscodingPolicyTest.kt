package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOutgoingVideoTranscodingPolicyTest {
    @Test
    fun secureVideoTranscodingUsesPrivateStaging() {
        assertTrue(SecureOutgoingVideoTranscodingPolicy.usesPrivateStaging(true))
        assertFalse(SecureOutgoingVideoTranscodingPolicy.usesPrivateStaging(false))
    }

    @Test
    fun secureTranscodeFailureReentersOnlyGenericSecureIngress() {
        assertTrue(SecureOutgoingVideoTranscodingPolicy.fallbackUsesSecureGenericIngress(true))
        assertFalse(SecureOutgoingVideoTranscodingPolicy.fallbackUsesSecureGenericIngress(false))
    }
}
