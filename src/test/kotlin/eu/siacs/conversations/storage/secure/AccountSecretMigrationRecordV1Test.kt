package eu.siacs.conversations.storage.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSecretMigrationRecordV1Test {

    @Test
    fun preparedRecordRoundTripsAuthorityAndEvidence() {
        val evidence = ByteArray(AccountSecretMigrationRecordV1.EVIDENCE_BYTES) { (it + 7).toByte() }
        val record =
            AccountSecretMigrationRecordV1(
                accountUuid = "account-a",
                phase = AccountSecretMigrationPhaseV1.PREPARED,
                expectedMask = 1 or 2 or 4,
                evidenceValue = evidence,
                updatedAt = 1234L,
            )

        val encoded = AccountSecretMigrationRecordCodecV1.encode(record)
        val decoded = AccountSecretMigrationRecordCodecV1.decode(encoded)
        try {
            assertEquals("account-a", decoded.accountUuid)
            assertEquals(AccountSecretMigrationPhaseV1.PREPARED, decoded.phase)
            assertEquals(7, decoded.expectedMask)
            assertArrayEquals(evidence, decoded.evidence())
            assertEquals(1234L, decoded.updatedAt)
        } finally {
            evidence.fill(0)
            encoded.fill(0)
        }
    }

    @Test
    fun committedRecordRoundTripsWithoutChangingEvidence() {
        val evidence = ByteArray(AccountSecretMigrationRecordV1.EVIDENCE_BYTES) { 0x5a.toByte() }
        val record =
            AccountSecretMigrationRecordV1(
                accountUuid = "account-b",
                phase = AccountSecretMigrationPhaseV1.COMMITTED,
                expectedMask = 1,
                evidenceValue = evidence,
                updatedAt = 9999L,
            )

        val encoded = AccountSecretMigrationRecordCodecV1.encode(record)
        val decoded = AccountSecretMigrationRecordCodecV1.decode(encoded)
        try {
            assertEquals(AccountSecretMigrationPhaseV1.COMMITTED, decoded.phase)
            assertArrayEquals(evidence, decoded.evidence())
        } finally {
            evidence.fill(0)
            encoded.fill(0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun recordRejectsInvalidEvidenceLength() {
        AccountSecretMigrationRecordV1(
            accountUuid = "account-a",
            phase = AccountSecretMigrationPhaseV1.PREPARED,
            expectedMask = 1,
            evidenceValue = byteArrayOf(1, 2, 3),
            updatedAt = 1L,
        )
    }
}
