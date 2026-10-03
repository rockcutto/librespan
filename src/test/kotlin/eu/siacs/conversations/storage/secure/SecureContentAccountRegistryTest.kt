package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureContentAccountRegistryTest {
    @Test
    fun emptyRegistryFailsClosedUntilAuthoritativeSnapshotArrives() {
        val registry = SecureContentAccountRegistry()

        assertFalse(registry.isRegisteredAccount("account-a"))
        assertFalse(registry.isRegisteredAccount(""))

        registry.replaceRegisteredAccountUuids(listOf("account-a"))

        assertTrue(registry.isRegisteredAccount("account-a"))
        assertFalse(registry.isRegisteredAccount("account-b"))
    }

    @Test
    fun replacingSnapshotRevokesAccountsThatAreNoLongerRegistered() {
        val registry = SecureContentAccountRegistry()
        registry.replaceRegisteredAccountUuids(listOf("account-a", "account-b"))

        registry.replaceRegisteredAccountUuids(listOf("account-b"))

        assertFalse(registry.isRegisteredAccount("account-a"))
        assertTrue(registry.isRegisteredAccount("account-b"))
    }

    @Test
    fun invalidReplacementDoesNotPartiallyReplaceLastKnownGoodSnapshot() {
        val registry = SecureContentAccountRegistry()
        registry.replaceRegisteredAccountUuids(listOf("account-a"))

        try {
            registry.replaceRegisteredAccountUuids(listOf("account-b", ""))
            throw AssertionError("blank account UUID must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue(registry.isRegisteredAccount("account-a"))
        assertFalse(registry.isRegisteredAccount("account-b"))
    }
}
