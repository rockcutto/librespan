package eu.siacs.conversations.security.cryptolock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NormalWrappedAppMasterKeyRecordV1Test {

    @Test
    fun recordCodecRoundTripsExactly() {
        val iv = ByteArray(NormalWrappedAppMasterKeyRecordV1.IV_BYTES) { it.toByte() }
        val ciphertext =
            ByteArray(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES) {
                (it * 3).toByte()
            }
        val record = NormalWrappedAppMasterKeyRecordV1(iv, ciphertext)

        val encoded = NormalWrappedAppMasterKeyRecordCodecV1.encode(record)
        val decoded = NormalWrappedAppMasterKeyRecordCodecV1.decode(encoded)

        assertEquals(1, decoded.wrapperFormatVersion)
        assertEquals(1, decoded.keystoreKeyVersion)
        assertEquals(
            "BIOMETRIC_STRONG|DEVICE_CREDENTIAL",
            decoded.authPolicyId,
        )
        assertEquals("AES-256-GCM", decoded.aeadId)
        assertTrue(iv.contentEquals(decoded.iv()))
        assertTrue(ciphertext.contentEquals(decoded.ciphertext()))
        assertFalse(decoded.toString().contains(ciphertext.toHex()))
    }

    @Test
    fun wrongMagicFailsClosed() {
        val record =
            NormalWrappedAppMasterKeyRecordV1(
                ByteArray(NormalWrappedAppMasterKeyRecordV1.IV_BYTES),
                ByteArray(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES),
            )
        val encoded = NormalWrappedAppMasterKeyRecordCodecV1.encode(record)
        encoded[0] = (encoded[0].toInt() xor 0x01).toByte()

        try {
            NormalWrappedAppMasterKeyRecordCodecV1.decode(encoded)
            fail("Tampered record must fail closed")
        } catch (_: InvalidNormalWrappedRecordException) {
        }
    }

    @Test
    fun trailingDataFailsClosed() {
        val record =
            NormalWrappedAppMasterKeyRecordV1(
                ByteArray(NormalWrappedAppMasterKeyRecordV1.IV_BYTES),
                ByteArray(NormalWrappedAppMasterKeyRecordV1.CIPHERTEXT_BYTES),
            )
        val encoded =
            NormalWrappedAppMasterKeyRecordCodecV1.encode(record) + byteArrayOf(1)

        try {
            NormalWrappedAppMasterKeyRecordCodecV1.decode(encoded)
            fail("Trailing data must fail closed")
        } catch (_: InvalidNormalWrappedRecordException) {
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
