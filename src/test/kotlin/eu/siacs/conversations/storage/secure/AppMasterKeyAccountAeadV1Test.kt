package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.security.cryptolock.AppMasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMasterKeyAccountAeadV1Test {

    @Test
    fun accountEnvelopeRoundTrips() {
        val master = AppMasterKey.generate(DeterministicSecureRandom())
        try {
            AppMasterKeyAccountAeadV1.fromMasterKey(
                master,
                "account-a",
                DeterministicSecureRandom(33),
            ).use { aead ->
                val plaintext = "account-key-material".toByteArray()
                val aad = SecureContentAssociatedData.forGatedAccountMaterial("account-a")
                val ciphertext = aead.encrypt(plaintext, aad)
                val recovered = aead.decrypt(ciphertext, aad)

                assertTrue(MessageDigest.isEqual(plaintext, recovered))
                plaintext.fill(0)
                aad.fill(0)
                ciphertext.fill(0)
                recovered.fill(0)
            }
        } finally {
            master.close()
        }
    }

    @Test
    fun differentAccountDerivesDifferentEnvelopeKey() {
        val master = AppMasterKey.generate(DeterministicSecureRandom())
        try {
            AppMasterKeyAccountAeadV1.fromMasterKey(
                master,
                "account-a",
                DeterministicSecureRandom(44),
            ).use { first ->
                AppMasterKeyAccountAeadV1.fromMasterKey(
                    master,
                    "account-b",
                    DeterministicSecureRandom(44),
                ).use { second ->
                    val plaintext = "same-material".toByteArray()
                    val aadA = SecureContentAssociatedData.forGatedAccountMaterial("account-a")
                    val ciphertext = first.encrypt(plaintext, aadA)
                    var rejected = false
                    try {
                        second.decrypt(ciphertext, aadA).fill(0)
                    } catch (_: Exception) {
                        rejected = true
                    }
                    assertTrue(rejected)
                    plaintext.fill(0)
                    aadA.fill(0)
                    ciphertext.fill(0)
                }
            }
        } finally {
            master.close()
        }
    }

    @Test
    fun manifestEvidenceChangesWithCandidateDigest() {
        val firstDigest = ByteArray(32) { 1 }
        val secondDigest = ByteArray(32) { 2 }
        val first =
            SecureContentAccountMigrationManifestV1(
                "tx",
                listOf(
                    SecureContentAccountMigrationManifestEntryV1(
                        "account.abc",
                        firstDigest,
                    ),
                ),
            )
        val second =
            SecureContentAccountMigrationManifestV1(
                "tx",
                listOf(
                    SecureContentAccountMigrationManifestEntryV1(
                        "account.abc",
                        secondDigest,
                    ),
                ),
            )

        val firstEvidence = first.evidence()
        val secondEvidence = second.evidence()
        try {
            assertFalse(MessageDigest.isEqual(firstEvidence, secondEvidence))
        } finally {
            firstEvidence.fill(0)
            secondEvidence.fill(0)
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
