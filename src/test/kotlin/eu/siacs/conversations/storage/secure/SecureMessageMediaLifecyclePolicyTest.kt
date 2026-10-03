package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageMediaLifecyclePolicyTest {
    @Test
    fun mediaOnlyRolloutRequiresSecureRetirementBeforeMessageDeletion() {
        assertTrue(SecureMessageMediaLifecyclePolicy.requiresRetirement(true, false))
        assertTrue(SecureMessageMediaLifecyclePolicy.usesMediaOnlyRetirement(true, false))
    }

    @Test
    fun protectedTextRolloutRetainsItsExistingCoordinatorPath() {
        assertTrue(SecureMessageMediaLifecyclePolicy.requiresRetirement(false, true))
        assertFalse(SecureMessageMediaLifecyclePolicy.usesMediaOnlyRetirement(false, true))
    }

    @Test
    fun disabledRolloutsKeepLegacyDeletionCompatibility() {
        assertFalse(SecureMessageMediaLifecyclePolicy.requiresRetirement(false, false))
        assertFalse(SecureMessageMediaLifecyclePolicy.usesMediaOnlyRetirement(false, false))
    }
}
