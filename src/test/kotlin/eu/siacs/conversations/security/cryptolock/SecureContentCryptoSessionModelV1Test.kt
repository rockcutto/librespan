package eu.siacs.conversations.security.cryptolock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

class SecureContentCryptoSessionModelV1Test {

    @Test
    fun wrappedKeystoreInvalidationRequiresRecoveryWithoutOpeningBackgroundCrypto() {
        for (error in listOf(
            ProviderException("Keystore operation failed", UnrecoverableKeyException()),
            ProviderException("Keystore operation failed", InvalidKeyException()),
        )) {
            val failure = classifyNormalKeyFailure(error)
            assertEquals(NormalAppMasterKeyFailure.KEY_INVALIDATED, failure)
            val state = SecureContentCryptoSessionPolicyV1.stateAfterNormalUnlockFailure(failure)
            assertEquals(SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED, state)
            assertTrue(SecureContentCryptoSessionPolicyV1.requiresAuthentication(state))
            assertFalse(SecureContentCryptoSessionPolicyV1.allowsBackgroundCrypto(
                state, ActivationStoreReadResult.Absent, hasActiveMasterKey = false,
            ))
        }
    }

    @Test
    fun temporaryAuthenticationFailureRemainsLocked() {
        assertEquals(
            SecureContentCryptoSessionStateV1.LOCKED,
            SecureContentCryptoSessionPolicyV1.stateAfterNormalUnlockFailure(
                NormalAppMasterKeyFailure.AUTHENTICATION_REQUIRED,
            ),
        )
        assertEquals(
            NormalAppMasterKeyFailure.CRYPTO_FAILURE,
            classifyNormalKeyFailure(ProviderException("temporary provider failure")),
        )
    }

    @Test
    fun activeSnapshotRequiresDistinctStateFromUiLock() {
        assertTrue(
            SecureContentCryptoSessionStateV1.ACTIVE !=
                SecureContentCryptoSessionStateV1.LOCKED,
        )
        assertTrue(
            SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED !=
                SecureContentCryptoSessionStateV1.LOCKED,
        )
    }

    @Test
    fun durableActiveRestartsLocked() {
        assertEquals(
            SecureContentCryptoSessionStateV1.LOCKED,
            SecureContentCryptoSessionPolicyV1.stateForDurablePhase(
                InactiveDeviceActivationPhase.ACTIVE,
            ),
        )
    }

    @Test
    fun migrationCrashWindowRequiresActivationRecovery() {
        assertEquals(
            SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED,
            SecureContentCryptoSessionPolicyV1.stateForDurablePhase(
                InactiveDeviceActivationPhase.MIGRATION_PREPARED,
            ),
        )
        assertEquals(
            SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED,
            SecureContentCryptoSessionPolicyV1.stateForDurablePhase(
                InactiveDeviceActivationPhase.MIGRATION_COMMITTED,
            ),
        )
    }

    @Test
    fun backgroundCryptoAllowsNormalRootModeWithoutActivationJournal() {
        assertTrue(
            SecureContentCryptoSessionPolicyV1.allowsBackgroundCrypto(
                SecureContentCryptoSessionStateV1.INACTIVE,
                ActivationStoreReadResult.Absent,
                hasActiveMasterKey = false,
            ),
        )
    }

    @Test
    fun backgroundCryptoRejectsLockedOrCorruptState() {
        assertFalse(
            SecureContentCryptoSessionPolicyV1.allowsBackgroundCrypto(
                SecureContentCryptoSessionStateV1.LOCKED,
                ActivationStoreReadResult.Absent,
                hasActiveMasterKey = false,
            ),
        )
        assertFalse(
            SecureContentCryptoSessionPolicyV1.allowsBackgroundCrypto(
                SecureContentCryptoSessionStateV1.INACTIVE,
                ActivationStoreReadResult.Corrupt,
                hasActiveMasterKey = false,
            ),
        )
    }

    @Test
    fun highSecurityGateIsIndependentFromUiAppLock() {
        assertTrue(
            SecureContentCryptoSessionPolicyV1.requiresAuthentication(
                SecureContentCryptoSessionStateV1.LOCKED,
            ),
        )
        assertTrue(
            SecureContentCryptoSessionPolicyV1.requiresAuthentication(
                SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED,
            ),
        )
        assertFalse(
            SecureContentCryptoSessionPolicyV1.requiresAuthentication(
                SecureContentCryptoSessionStateV1.ACTIVE,
            ),
        )
    }

    @Test
    fun privacySuspendPreservesTerminalStates() {
        assertEquals(
            SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED,
            SecureContentCryptoSessionPolicyV1.stateAfterPrivacySuspend(
                SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED,
                hasActiveRecord = true,
            ),
        )
        assertEquals(
            SecureContentCryptoSessionStateV1.CRYPTO_ERASED,
            SecureContentCryptoSessionPolicyV1.stateAfterPrivacySuspend(
                SecureContentCryptoSessionStateV1.CRYPTO_ERASED,
                hasActiveRecord = true,
            ),
        )
        assertEquals(
            SecureContentCryptoSessionStateV1.CORRUPT,
            SecureContentCryptoSessionPolicyV1.stateAfterPrivacySuspend(
                SecureContentCryptoSessionStateV1.CORRUPT,
                hasActiveRecord = true,
            ),
        )
    }

    @Test
    fun activePrivacySuspendBecomesLocked() {
        assertEquals(
            SecureContentCryptoSessionStateV1.LOCKED,
            SecureContentCryptoSessionPolicyV1.stateAfterPrivacySuspend(
                SecureContentCryptoSessionStateV1.ACTIVE,
                hasActiveRecord = true,
            ),
        )
    }

    @Test
    fun sessionStatesRemainExplicitAndStable() {
        assertEquals(
            listOf(
                "INACTIVE",
                "ACTIVATION_RECOVERY_REQUIRED",
                "LOCKED",
                "ACTIVE",
                "RECOVERY_REQUIRED",
                "CRYPTO_ERASED",
                "CORRUPT",
            ),
            SecureContentCryptoSessionStateV1.entries.map { it.name },
        )
    }
}
