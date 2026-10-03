package eu.siacs.conversations.security.cryptolock

import eu.siacs.conversations.security.recovery.RecoveryAppMasterKeyWrapperV1
import eu.siacs.conversations.security.recovery.RecoveryPhraseCodecV1
import java.security.SecureRandom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InactiveDeviceActivationRecordV1Test {

    @Test
    fun recoveryRepairCommitsOnlyAWrapperForTheSameMasterKey() {
        val generated = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom())
        val original = AppMasterKey.generate(DeterministicSecureRandom(33))
        val same = original.useCopy { AppMasterKey.copyOf(it) }
        val other = AppMasterKey.generate(DeterministicSecureRandom(34))
        try {
            val recovery = RecoveryAppMasterKeyWrapperV1.wrap(
                generated.secret, original, DeterministicSecureRandom(67),
            )
            val oldNormal = NormalWrappedAppMasterKeyRecordV1(
                ByteArray(NormalWrappedAppMasterKeyRecordV1.IV_BYTES) { 1 },
                ByteArray(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES) { 2 },
            )
            val newNormal = NormalWrappedAppMasterKeyRecordV1(
                ByteArray(NormalWrappedAppMasterKeyRecordV1.IV_BYTES) { 3 },
                ByteArray(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES) { 4 },
            )
            val current = InactiveDeviceActivationRecordV1(
                transactionId = "tx-repair",
                phase = InactiveDeviceActivationPhase.ACTIVE,
                recoveryWrapper = recovery,
                normalWrapper = oldNormal,
                migrationEvidence = ByteArray(32) { 7 },
                createdAt = 100L,
                updatedAt = 200L,
                activatedAt = 200L,
            )

            assertEquals(null, verifiedRecoveryRepairRecordV1(
                current, newNormal, original, other, 300L,
            ))
            assertTrue(current.normalWrapper === oldNormal)
            // Cancel before verification has the same durable record: no write is authorized.
            assertTrue(current.recoveryWrapper === recovery)

            val repaired = verifiedRecoveryRepairRecordV1(
                current, newNormal, original, same, 300L,
            )!!
            assertTrue(repaired.normalWrapper === newNormal)
            assertTrue(repaired.recoveryWrapper === recovery)
            assertTrue(current.normalWrapper === oldNormal)
            assertEquals(SecureContentCryptoSessionStateV1.LOCKED,
                SecureContentCryptoSessionPolicyV1.stateForDurablePhase(repaired.phase))
        } finally {
            generated.close()
            original.close()
            same.close()
            other.close()
        }
    }

    @Test
    fun transitionGraphCannotSkipSecurityBarriers() {
        assertTrue(
            InactiveDeviceActivationTransitionsV1.permits(
                InactiveDeviceActivationPhase.PREPARING,
                InactiveDeviceActivationPhase.NORMAL_WRAPPED,
            ),
        )
        assertFalse(
            InactiveDeviceActivationTransitionsV1.permits(
                InactiveDeviceActivationPhase.PREPARING,
                InactiveDeviceActivationPhase.ACTIVE,
            ),
        )
        assertFalse(
            InactiveDeviceActivationTransitionsV1.permits(
                InactiveDeviceActivationPhase.WRAPPERS_VERIFIED,
                InactiveDeviceActivationPhase.ACTIVE,
            ),
        )
        assertTrue(
            InactiveDeviceActivationTransitionsV1.permits(
                InactiveDeviceActivationPhase.MIGRATION_COMMITTED,
                InactiveDeviceActivationPhase.ACTIVE,
            ),
        )
        assertFalse(
            InactiveDeviceActivationTransitionsV1.permits(
                InactiveDeviceActivationPhase.ACTIVE,
                InactiveDeviceActivationPhase.PREPARING,
            ),
        )
    }

    @Test
    fun activationRecordCodecRoundTripsNestedWrappers() {
        val generated = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom())
        val master = AppMasterKey.generate(DeterministicSecureRandom(33))
        try {
            val recovery =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    generated.secret,
                    master,
                    DeterministicSecureRandom(67),
                )
            val normal =
                NormalWrappedAppMasterKeyRecordV1(
                    ByteArray(NormalWrappedAppMasterKeyRecordV1.IV_BYTES) {
                        (it + 1).toByte()
                    },
                    ByteArray(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES) {
                        (it + 9).toByte()
                    },
                )
            val evidence = ByteArray(32) { (it + 17).toByte() }
            val record =
                InactiveDeviceActivationRecordV1(
                    transactionId = "tx-test",
                    phase = InactiveDeviceActivationPhase.MIGRATION_COMMITTED,
                    recoveryWrapper = recovery,
                    normalWrapper = normal,
                    migrationEvidence = evidence,
                    createdAt = 100L,
                    updatedAt = 200L,
                    activatedAt = null,
                )

            val encoded = InactiveDeviceActivationRecordCodecV1.encode(record)
            val decoded = InactiveDeviceActivationRecordCodecV1.decode(encoded)

            assertTrue(decoded.transactionId == "tx-test")
            assertTrue(decoded.phase == InactiveDeviceActivationPhase.MIGRATION_COMMITTED)
            assertTrue(
                normal.ciphertext().contentEquals(decoded.normalWrapper!!.ciphertext()),
            )
            assertTrue(evidence.contentEquals(decoded.migrationEvidence()))
            encoded.fill(0)
            evidence.fill(0)
        } finally {
            generated.close()
            master.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun activeRecordRequiresMigrationEvidence() {
        val generated = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom())
        val master = AppMasterKey.generate(DeterministicSecureRandom(22))
        try {
            val recovery =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    generated.secret,
                    master,
                    DeterministicSecureRandom(44),
                )
            val normal =
                NormalWrappedAppMasterKeyRecordV1(
                    ByteArray(NormalWrappedAppMasterKeyRecordV1.IV_BYTES),
                    ByteArray(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES),
                )
            InactiveDeviceActivationRecordV1(
                transactionId = "tx-invalid",
                phase = InactiveDeviceActivationPhase.ACTIVE,
                recoveryWrapper = recovery,
                normalWrapper = normal,
                migrationEvidence = null,
                createdAt = 100L,
                updatedAt = 200L,
                activatedAt = 200L,
            )
        } finally {
            generated.close()
            master.close()
        }
    }

    private class DeterministicSecureRandom(
        seedByte: Int = 0,
    ) : SecureRandom() {
        private var next = seedByte and 0xff

        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { index ->
                bytes[index] = next.toByte()
                next = (next + 1) and 0xff
            }
        }
    }
}
