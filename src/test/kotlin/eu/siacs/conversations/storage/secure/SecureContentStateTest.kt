package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureContentStateTest {
    @Test
    fun lifecycleStatesUseStablePersistedValues() {
        assertEquals("allocated", SecureContentState.ALLOCATED.persistedValue)
        assertEquals("receiving", SecureContentState.RECEIVING.persistedValue)
        assertEquals("complete", SecureContentState.COMPLETE.persistedValue)
        assertEquals("available", SecureContentState.AVAILABLE.persistedValue)
        assertEquals("failed", SecureContentState.FAILED.persistedValue)
    }

    @Test
    fun unknownPersistedStateIsUnavailable() {
        assertEquals(SecureContentState.FAILED, SecureContentState.fromPersistedValue("future-state"))
        assertEquals(SecureContentState.FAILED, SecureContentState.fromPersistedValue(null))
    }
}
