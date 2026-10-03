package eu.siacs.conversations.http

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureContentAesGcmWireProtectionTest {
    @Test
    fun protectsStorePlaintextWithIndependentWireBytes() {
        val plaintext = "secure document".toByteArray()
        val protection =
            SecureContentAesGcmWireProtection(
                ByteArray(32) { (it + 1).toByte() },
                ByteArray(12) { (it + 33).toByte() },
            )

        val encrypted =
            protection.protect(ByteArrayInputStream(plaintext)).use { it.readBytes() }

        assertEquals(plaintext.size + 16, encrypted.size)
        assertEquals(encrypted.size.toLong(), protection.expectedWireSize(plaintext.size.toLong()))
        assertFalse(plaintext.contentEquals(encrypted.copyOf(plaintext.size)))
    }

    @Test
    fun rejectsNonXep0454KeyMaterial() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureContentAesGcmWireProtection(ByteArray(16), ByteArray(12))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureContentAesGcmWireProtection(ByteArray(32), ByteArray(16))
        }
    }
}
