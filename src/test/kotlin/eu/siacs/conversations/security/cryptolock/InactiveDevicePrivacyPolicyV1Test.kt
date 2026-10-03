package eu.siacs.conversations.security.cryptolock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InactiveDevicePrivacyPolicyV1Test {

    @Test
    fun suspendsAtTwentyFourHours() {
        val start = 1_000_000L
        val record = InactiveDevicePrivacyPolicyV1.active(start)

        assertFalse(
            InactiveDevicePrivacyPolicyV1.shouldSuspend(
                record,
                start + InactiveDevicePrivacyPolicyV1.INACTIVITY_TIMEOUT_MILLIS - 1L,
                authenticatedForeground = false,
            ),
        )
        assertTrue(
            InactiveDevicePrivacyPolicyV1.shouldSuspend(
                record,
                start + InactiveDevicePrivacyPolicyV1.INACTIVITY_TIMEOUT_MILLIS,
                authenticatedForeground = false,
            ),
        )
    }

    @Test
    fun authenticatedForegroundDoesNotExpire() {
        val start = 1_000_000L
        val record = InactiveDevicePrivacyPolicyV1.active(start)

        assertFalse(
            InactiveDevicePrivacyPolicyV1.shouldSuspend(
                record,
                start + InactiveDevicePrivacyPolicyV1.INACTIVITY_TIMEOUT_MILLIS * 2L,
                authenticatedForeground = true,
            ),
        )
    }

    @Test
    fun largeClockRollbackFailsClosed() {
        val lastActivity = 10_000_000L
        val record = InactiveDevicePrivacyPolicyV1.active(lastActivity)

        assertTrue(
            InactiveDevicePrivacyPolicyV1.shouldSuspend(
                record,
                lastActivity -
                    InactiveDevicePrivacyPolicyV1.CLOCK_ROLLBACK_TOLERANCE_MILLIS -
                    1L,
                authenticatedForeground = false,
            ),
        )
    }

    @Test
    fun suspendedStateIsStickyUntilAuthenticatedResume() {
        val start = 1_000_000L
        val active = InactiveDevicePrivacyPolicyV1.active(start)
        val suspended =
            InactiveDevicePrivacyPolicyV1.suspended(
                active,
                start + InactiveDevicePrivacyPolicyV1.INACTIVITY_TIMEOUT_MILLIS,
            )

        assertTrue(
            InactiveDevicePrivacyPolicyV1.shouldSuspend(
                suspended,
                suspended.updatedAt + 1L,
                authenticatedForeground = true,
            ),
        )
    }
}
