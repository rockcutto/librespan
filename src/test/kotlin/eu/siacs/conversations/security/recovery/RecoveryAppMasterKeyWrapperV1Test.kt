package eu.siacs.conversations.security.recovery

import eu.siacs.conversations.security.cryptolock.AppMasterKey
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecoveryAppMasterKeyWrapperV1Test {

    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val okm = HkdfSha256.derive(ikm, salt, info, 42)

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex(),
        )
        ikm.fill(0)
        salt.fill(0)
        info.fill(0)
        okm.fill(0)
    }

    @Test
    fun wrapperRoundTripRestoresExactMasterKey() {
        val recoverySecret = RecoveryPhraseCodecV1.decode(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )
        val masterBytes = ByteArray(AppMasterKey.BYTE_LENGTH) { (it * 3 + 1).toByte() }
        val masterKey = AppMasterKey.copyOf(masterBytes)
        val random = DeterministicSecureRandom()

        try {
            val record = RecoveryAppMasterKeyWrapperV1.wrap(recoverySecret, masterKey, random)
            RecoveryAppMasterKeyWrapperV1.unwrap(recoverySecret, record).use { recovered ->
                assertTrue(masterBytes.contentEquals(recovered.copyBytesForTest()))
            }
        } finally {
            recoverySecret.close()
            masterKey.close()
            masterBytes.fill(0)
        }
    }

    @Test
    fun legacyV1SerializedRecordGoldenFixtureStillDecodesAndUnwraps() {
        // Produced by the pre-multiformat v1 implementation from:
        // phrase = "abandon" x11 + "about" (128 zero recovery bits),
        // AMK bytes = (index * 3 + 1), salt = 00..1f, nonce = 20..2b.
        //
        // This fixture freezes both the original serialized AAD/header and the legacy
        // "NeoCont|RecoveryKEK|v1|phrase=1|dictionary=bip39-en-v1|wrapper=1" KDF domain.
        val encoded =
            hex(
                "4e43525700000001000000010000000b62697033392d656e2d7631" +
                    "000000010000000b484b44462d534841323536" +
                    "00000020000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                    "000000010000000b4145532d3235362d47434d" +
                    "0000000c202122232425262728292a2b00000030" +
                    "db6a91bc407ffd72271252d1cab198a8f39b65d8506637e172e0537cb11c4535" +
                    "3c9a0950606eef0af01778882d8897b7",
            )
        val recoverySecret =
            RecoveryPhraseCodecV1.decode(
                "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon about",
            )
        val expectedMaster = ByteArray(AppMasterKey.BYTE_LENGTH) { (it * 3 + 1).toByte() }

        try {
            val record = RecoveryWrappedAppMasterKeyRecordCodecV1.decode(encoded)
            assertEquals(1, record.recoveryPhraseFormatVersion)
            assertEquals("bip39-en-v1", record.dictionaryId)
            assertEquals(
                RecoveryPhraseFormats.BIP39_EN_V1,
                record.recoveryPhraseFormat(),
            )

            val reencoded = RecoveryWrappedAppMasterKeyRecordCodecV1.encode(record)
            try {
                assertTrue(
                    "Legacy v1 recovery record serialization must remain byte-for-byte stable",
                    encoded.contentEquals(reencoded),
                )
            } finally {
                reencoded.fill(0)
            }

            RecoveryAppMasterKeyWrapperV1.unwrap(recoverySecret, record).use { recovered ->
                assertTrue(expectedMaster.contentEquals(recovered.copyBytesForTest()))
            }
        } finally {
            recoverySecret.close()
            expectedMaster.fill(0)
            encoded.fill(0)
        }
    }

    @Test
    fun recordCodecRoundTripsAndCarriesOnlyPublicMetadataAndCiphertext() {
        val recoverySecret = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom())
        val masterKey = AppMasterKey.generate(DeterministicSecureRandom())
        try {
            val record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    recoverySecret.secret,
                    masterKey,
                    DeterministicSecureRandom(),
                )
            val encoded = RecoveryWrappedAppMasterKeyRecordCodecV1.encode(record)
            val decoded = RecoveryWrappedAppMasterKeyRecordCodecV1.decode(encoded)

            assertEquals(RecoveryPhraseCodecV1.FORMAT_VERSION, decoded.recoveryPhraseFormatVersion)
            assertEquals(RecoveryPhraseCodecV1.DICTIONARY_ID, decoded.dictionaryId)
            assertEquals(RecoveryKekKdfV1.KDF_ID, decoded.kdfId)
            assertEquals(RecoveryWrappedAppMasterKeyRecordV1.AEAD_ID, decoded.aeadId)
            assertTrue(record.kdfSalt().contentEquals(decoded.kdfSalt()))
            assertTrue(record.nonce().contentEquals(decoded.nonce()))
            assertTrue(record.ciphertext().contentEquals(decoded.ciphertext()))
            assertFalse(record.toString().contains(record.ciphertext().toHex()))
            encoded.fill(0)
        } finally {
            recoverySecret.close()
            masterKey.close()
        }
    }

    @Test
    fun wrongRecoverySecretFailsWithoutReturningMasterKey() {
        val first = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom(1))
        val second = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom(33))
        val masterKey = AppMasterKey.generate(DeterministicSecureRandom(77))
        try {
            val record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    first.secret,
                    masterKey,
                    DeterministicSecureRandom(99),
                )
            try {
                RecoveryAppMasterKeyWrapperV1.unwrap(second.secret, record).close()
                fail("Wrong recovery secret must fail closed")
            } catch (_: RecoveryUnwrapException) {
            }
        } finally {
            first.close()
            second.close()
            masterKey.close()
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val generated = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom(4))
        val masterKey = AppMasterKey.generate(DeterministicSecureRandom(9))
        try {
            val record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    generated.secret,
                    masterKey,
                    DeterministicSecureRandom(15),
                )
            val tampered = record.ciphertext()
            tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
            val modified =
                RecoveryWrappedAppMasterKeyRecordV1(
                    kdfSalt = record.kdfSalt(),
                    nonce = record.nonce(),
                    ciphertext = tampered,
                )
            tampered.fill(0)

            try {
                RecoveryAppMasterKeyWrapperV1.unwrap(generated.secret, modified).close()
                fail("Tampered wrapper must fail closed")
            } catch (_: RecoveryUnwrapException) {
            }
        } finally {
            generated.close()
            masterKey.close()
        }
    }

    @Test
    fun tamperedAuthenticatedMetadataFailsAuthentication() {
        val generated = RecoveryPhraseCodecV1.generate(DeterministicSecureRandom(8))
        val masterKey = AppMasterKey.generate(DeterministicSecureRandom(11))
        try {
            val record =
                RecoveryAppMasterKeyWrapperV1.wrap(
                    generated.secret,
                    masterKey,
                    DeterministicSecureRandom(21),
                )
            val changedSalt = record.kdfSalt()
            changedSalt[0] = (changedSalt[0].toInt() xor 0x01).toByte()
            val modified =
                RecoveryWrappedAppMasterKeyRecordV1(
                    kdfSalt = changedSalt,
                    nonce = record.nonce(),
                    ciphertext = record.ciphertext(),
                )
            changedSalt.fill(0)

            try {
                RecoveryAppMasterKeyWrapperV1.unwrap(generated.secret, modified).close()
                fail("Authenticated metadata tampering must fail closed")
            } catch (_: RecoveryUnwrapException) {
            }
        } finally {
            generated.close()
            masterKey.close()
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

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
