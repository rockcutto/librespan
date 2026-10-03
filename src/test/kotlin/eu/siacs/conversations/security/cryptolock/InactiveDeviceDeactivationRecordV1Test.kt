package eu.siacs.conversations.security.cryptolock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InactiveDeviceDeactivationRecordV1Test {

    @Test
    fun preparingRecordHasNoMigrationEvidence() {
        val record =
            InactiveDeviceDeactivationRecordV1(
                transactionId = "deactivate-tx",
                activationTransactionId = "activate-tx",
                phase = InactiveDeviceDeactivationPhase.PREPARING,
                migrationEvidence = null,
                createdAt = 100L,
                updatedAt = 100L,
            )

        assertEquals(InactiveDeviceDeactivationPhase.PREPARING, record.phase)
        assertEquals(null, record.migrationEvidence())
    }

    @Test(expected = IllegalArgumentException::class)
    fun preparedRecordRequiresMigrationEvidence() {
        InactiveDeviceDeactivationRecordV1(
            transactionId = "deactivate-tx",
            activationTransactionId = "activate-tx",
            phase = InactiveDeviceDeactivationPhase.MIGRATION_PREPARED,
            migrationEvidence = null,
            createdAt = 100L,
            updatedAt = 101L,
        )
    }

    @Test
    fun committedRecordCodecRoundTripsCrashRecoveryEvidence() {
        val evidence = ByteArray(32) { (it + 3).toByte() }
        val record =
            InactiveDeviceDeactivationRecordV1(
                transactionId = "deactivate-tx",
                activationTransactionId = "activate-tx",
                phase = InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED,
                migrationEvidence = evidence,
                createdAt = 100L,
                updatedAt = 200L,
            )

        val encoded = InactiveDeviceDeactivationRecordCodecV1.encode(record)
        val decoded = InactiveDeviceDeactivationRecordCodecV1.decode(encoded)
        try {
            assertEquals("deactivate-tx", decoded.transactionId)
            assertEquals("activate-tx", decoded.activationTransactionId)
            assertEquals(InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED, decoded.phase)
            assertArrayEquals(evidence, decoded.migrationEvidence())
        } finally {
            encoded.fill(0)
            evidence.fill(0)
        }
    }
}
