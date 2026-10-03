package eu.siacs.conversations.security.applock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockStateMachineTest {
    @Test
    fun enabledProcessStartsLocked() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))

        assertTrue(state.onProtectedActivityStarted(1_000L))
        assertTrue(state.beginUnlockUi())
        state.markUnlocked(1_100L)

        assertFalse(state.snapshot().locked)
    }

    @Test
    fun shortBackgroundGraceDoesNotRelock() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))
        state.onProtectedActivityStarted(1_000L)
        state.beginUnlockUi()
        state.markUnlocked(1_100L)
        state.onProtectedActivityStopped(2_000L)

        assertFalse(state.onProtectedActivityStarted(31_999L))
        assertFalse(state.snapshot().locked)
    }

    @Test
    fun backgroundTimeoutRelocks() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))
        state.onProtectedActivityStarted(1_000L)
        state.beginUnlockUi()
        state.markUnlocked(1_100L)
        state.onProtectedActivityStopped(2_000L)

        assertTrue(state.onProtectedActivityStarted(32_000L))
        assertTrue(state.snapshot().locked)
    }

    @Test
    fun unlockUiDoesNotCountAsBackgroundInactivity() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))
        assertTrue(state.onProtectedActivityStarted(1_000L))
        assertTrue(state.beginUnlockUi())
        state.onProtectedActivityStopped(1_100L)
        state.markUnlocked(80_000L)

        assertFalse(state.onProtectedActivityStarted(80_001L))
        assertFalse(state.snapshot().locked)
    }

    @Test
    fun recoveryRequiredStaysFailClosed() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))
        state.onProtectedActivityStarted(1_000L)
        state.beginUnlockUi()

        state.forceLock(AppLockReason.RECOVERY_REQUIRED)

        assertTrue(state.snapshot().locked)
        assertTrue(state.snapshot().reason == AppLockReason.RECOVERY_REQUIRED)
    }

    @Test
    fun credentialUnavailableDoesNotDisablePolicy() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))
        state.forceLock(AppLockReason.DEVICE_CREDENTIAL_UNAVAILABLE)

        assertTrue(state.snapshot().enabled)
        assertTrue(state.snapshot().locked)
    }

    @Test
    fun dismissedUnlockUiRequiresAuthenticationAgain() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))
        assertTrue(state.onProtectedActivityStarted(1_000L))
        assertTrue(state.beginUnlockUi())
        state.onProtectedActivityStopped(1_100L)

        state.dismissUnlockUi(1_200L)

        assertTrue(state.onProtectedActivityStarted(1_300L))
        assertTrue(state.snapshot().locked)
    }

    @Test
    fun trustedExternalAuthenticationDoesNotRelockCaller() {
        val state = AppLockStateMachine(AppLockPolicy(true, 30_000L))
        state.onProtectedActivityStarted(1_000L)
        state.beginUnlockUi()
        state.markUnlocked(1_100L)

        state.beginTrustedExternalAuthentication()
        state.onProtectedActivityStopped(2_000L)
        state.onProtectedActivityStarted(80_000L)
        state.endTrustedExternalAuthentication(80_100L)

        assertFalse(state.snapshot().locked)
    }
}
