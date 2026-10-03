package eu.siacs.conversations.storage.secure

import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SecureTextPayloadTest {
    @Test
    fun preservesShortAsciiTextExactly() {
        assertPreserved("A secure short message.")
    }

    @Test
    fun preservesLargeAsciiTextExactly() {
        assertPreserved("a".repeat(128 * 1024))
    }

    @Test
    fun preservesLargeCyrillicTextExactlyAndCountsUtf8Bytes() {
        val text = "Привет, защищённый мир. ".repeat(4 * 1024)
        assertPreserved(text)
        assertEquals(text.toByteArray(StandardCharsets.UTF_8).size, SecureTextPayload.utf8ByteCount(text))
    }

    @Test
    fun preservesLargeEmojiTextExactlyAndCountsUtf8Bytes() {
        val text = "🛡️🔐🚀".repeat(16 * 1024)
        assertPreserved(text)
        assertEquals(text.toByteArray(StandardCharsets.UTF_8).size, SecureTextPayload.utf8ByteCount(text))
    }

    @Test
    fun findsAsciiCyrillicAndEmojiPassFailTransitionsInUtf8Bytes() {
        assertBoundary("a", 1)
        assertBoundary("Ж", 2)
        assertBoundary("😀", 4)
    }

    @Test
    fun rejectsMalformedUtf16BeforeStorePublication() {
        try {
            SecureTextPayload.encodeUtf8("invalid\uD800")
            fail("expected malformed UTF-16 to be rejected")
        } catch (_: IOException) {
            // The protected Store must never receive an ambiguous UTF-8 conversion.
        }
    }

    private fun assertBoundary(unit: String, unitBytes: Int) {
        val exact = unit.repeat(SecureTextPayload.MAXIMUM_BYTES / unitBytes)
        assertEquals(SecureTextPayload.MAXIMUM_BYTES, SecureTextPayload.utf8ByteCount(exact))
        try {
            SecureTextPayload.encodeUtf8(exact + unit)
            fail("expected oversized secure text to be rejected")
        } catch (error: SecureTextPayloadTooLargeException) {
            assertEquals(SecureTextPayload.MAXIMUM_BYTES + unitBytes, error.observedBytes)
            assertEquals(SecureTextPayload.MAXIMUM_BYTES, error.maximumBytes)
        }
    }

    private fun assertPreserved(text: String) {
        val encoded = SecureTextPayload.encodeUtf8(text)
        assertEquals(text.toByteArray(StandardCharsets.UTF_8).size, encoded.size)
        assertArrayEquals(text.toByteArray(StandardCharsets.UTF_8), encoded)
        assertEquals(text, encoded.toString(StandardCharsets.UTF_8))
    }
}
