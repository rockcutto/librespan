package eu.siacs.conversations.security.recovery

import eu.siacs.conversations.security.cryptolock.AppMasterKey
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecoveryWrapperPhraseFormatCompatibilityTest {
    @Test
    fun sameSecretAndSaltUseDistinctV1AndLibreSpanV2KekDomains() {
        val secret =
            RecoveryPhraseCodecV1.decode(
                "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon about",
            )
        val salt = ByteArray(RecoveryKekKdfV1.SALT_BYTES) { it.toByte() }
        val legacyDefault = RecoveryKekKdfV1.derive(secret, salt)
        val explicitV1 =
            RecoveryKekKdfV1.derive(
                secret,
                salt,
                RecoveryPhraseFormats.BIP39_EN_V1,
            )
        val libreSpanV2 =
            RecoveryKekKdfV1.derive(
                secret,
                salt,
                RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1,
            )

        try {
            legacyDefault.useCopy { legacyBytes ->
                explicitV1.useCopy { explicitBytes ->
                    assertTrue(
                        "Explicit v1 derivation must remain identical to the frozen legacy default",
                        legacyBytes.contentEquals(explicitBytes),
                    )
                }
                libreSpanV2.useCopy { v2Bytes ->
                    assertFalse(
                        "LibreSpan v2 must be domain-separated from the legacy v1 KEK",
                        legacyBytes.contentEquals(v2Bytes),
                    )
                }
            }
        } finally {
            legacyDefault.close()
            explicitV1.close()
            libreSpanV2.close()
            secret.close()
            salt.fill(0)
        }
    }

    @Test
    fun relabelingV1CiphertextAsLibreSpanV2FailsClosed() {
        val secret =
            RecoveryPhraseCodecV1.decode(
                "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon about",
            )
        val masterKey = AppMasterKey.generate(DeterministicSecureRandom(17))
        try {
            val v1Record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    secret,
                    masterKey,
                    DeterministicSecureRandom(41),
                    RecoveryPhraseFormats.BIP39_EN_V1,
                )
            val relabeled =
                RecoveryWrappedAppMasterKeyRecordV1(
                    kdfSalt = v1Record.kdfSalt(),
                    nonce = v1Record.nonce(),
                    ciphertext = v1Record.ciphertext(),
                    recoveryPhraseFormat = RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1,
                )

            expectUnwrapFailure {
                RecoveryAppMasterKeyWrapperV1.unwrap(secret, relabeled).close()
            }
        } finally {
            secret.close()
            masterKey.close()
        }
    }

    @Test
    fun relabelingLibreSpanV2CiphertextAsV1FailsClosed() {
        val secret =
            RecoveryPhraseCodecV1.decode(
                "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon about",
            )
        val masterKey = AppMasterKey.generate(DeterministicSecureRandom(23))
        try {
            val v2Record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    secret,
                    masterKey,
                    DeterministicSecureRandom(53),
                    RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1,
                )
            val relabeled =
                RecoveryWrappedAppMasterKeyRecordV1(
                    kdfSalt = v2Record.kdfSalt(),
                    nonce = v2Record.nonce(),
                    ciphertext = v2Record.ciphertext(),
                    recoveryPhraseFormat = RecoveryPhraseFormats.BIP39_EN_V1,
                )

            expectUnwrapFailure {
                RecoveryAppMasterKeyWrapperV1.unwrap(secret, relabeled).close()
            }
        } finally {
            secret.close()
            masterKey.close()
        }
    }

    @Test
    fun unknownPhraseFormatVersionAndDictionaryAreRejectedWithoutFallback() {
        val generated = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom(7))
        val masterKey = AppMasterKey.generate(DeterministicSecureRandom(13))
        try {
            val record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    generated.secret,
                    masterKey,
                    DeterministicSecureRandom(29),
                    RecoveryPhraseFormats.BIP39_EN_V1,
                )
            val encoded = RecoveryWrappedAppMasterKeyRecordCodecV1.encode(record)
            try {
                val unknownVersion = encoded.copyOf()
                // magic [0..3], wrapperVersion [4..7], phraseFormatVersion [8..11].
                unknownVersion[11] = 0x7f
                try {
                    expectInvalidRecord {
                        RecoveryWrappedAppMasterKeyRecordCodecV1.decode(unknownVersion)
                    }
                } finally {
                    unknownVersion.fill(0)
                }

                val unknownDictionary = encoded.copyOf()
                // dictionary length is [12..15], then "bip39-en-v1" occupies [16..26].
                unknownDictionary[26] = '2'.code.toByte()
                try {
                    expectInvalidRecord {
                        RecoveryWrappedAppMasterKeyRecordCodecV1.decode(unknownDictionary)
                    }
                } finally {
                    unknownDictionary.fill(0)
                }
            } finally {
                encoded.fill(0)
            }
        } finally {
            generated.close()
            masterKey.close()
        }
    }

    @Test
    fun libreSpanV2MetadataRoundTripsAndUnwrapsSameMasterKey() {
        val secretBytes = ByteArray(RecoveryPhraseCodecV2.ENTROPY_BYTES) { (it * 11 + 5).toByte() }
        val secret = RecoverySecret.copyOf(secretBytes)
        val masterBytes = ByteArray(AppMasterKey.BYTE_LENGTH) { (it * 5 + 3).toByte() }
        val masterKey = AppMasterKey.copyOf(masterBytes)
        try {
            val record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    secret,
                    masterKey,
                    DeterministicSecureRandom(31),
                    RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1,
                )
            val encoded = RecoveryWrappedAppMasterKeyRecordCodecV1.encode(record)
            val decoded = RecoveryWrappedAppMasterKeyRecordCodecV1.decode(encoded)
            assertEquals(2, decoded.recoveryPhraseFormatVersion)
            assertEquals("librespan-ru-pairs-v1", decoded.dictionaryId)
            RecoveryAppMasterKeyWrapperV1.unwrap(secret, decoded).use {
                assertTrue(masterBytes.contentEquals(it.copyBytesForTest()))
            }
            encoded.fill(0)
        } finally {
            secret.close()
            secretBytes.fill(0)
            masterKey.close()
            masterBytes.fill(0)
        }
    }

    private fun expectUnwrapFailure(block: () -> Unit) {
        try {
            block()
            fail("Recovery unwrap must fail closed for mismatched phrase metadata")
        } catch (_: RecoveryUnwrapException) {
        }
    }

    private fun expectInvalidRecord(block: () -> Unit) {
        try {
            block()
            fail("Unsupported recovery phrase metadata must be rejected")
        } catch (_: InvalidRecoveryWrappedRecordException) {
        }
    }

    private class DeterministicSecureRandom(seed: Int) : SecureRandom() {
        private var next = seed and 0xff
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach {
                bytes[it] = next.toByte()
                next = (next + 1) and 0xff
            }
        }
    }
}
