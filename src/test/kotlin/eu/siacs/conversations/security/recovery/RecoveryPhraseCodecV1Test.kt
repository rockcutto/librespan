package eu.siacs.conversations.security.recovery

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecoveryPhraseCodecV1Test {

    @Test
    fun dictionaryIsStableAndUnique() {
        assertEquals(2048, RecoveryPhraseEnglishWordListV1.words.size)
        assertEquals(2048, RecoveryPhraseEnglishWordListV1.words.toSet().size)
        assertEquals("abandon", RecoveryPhraseEnglishWordListV1.words.first())
        assertEquals("zoo", RecoveryPhraseEnglishWordListV1.words.last())
    }

    @Test
    fun zeroEntropyMatchesKnownTwelveWordVector() {
        val phrase = RecoveryPhraseCodecV1.encode(ByteArray(16))

        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            phrase.displayString(),
        )
    }

    @Test
    fun roundTripRestoresExactRecoverySecret() {
        val entropy = ByteArray(16) { it.toByte() }
        val phrase = RecoveryPhraseCodecV1.encode(entropy)

        RecoveryPhraseCodecV1.decode(phrase.displayString()).use { decoded ->
            assertTrue(entropy.contentEquals(decoded.copyBytes()))
        }
    }

    @Test
    fun checksumMismatchFailsClosed() {
        val invalid =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon ability"

        try {
            RecoveryPhraseCodecV1.decode(invalid).close()
            fail("Expected checksum failure")
        } catch (_: InvalidRecoveryPhraseException) {
        }
    }

    @Test
    fun unknownWordFailsClosed() {
        val invalid =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon neocont"

        assertFalse(RecoveryPhraseCodecV1.isValid(invalid))
    }

    @Test
    fun generatedPhraseHasTwelveWordsAndValidChecksum() {
        val deterministic =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    bytes.indices.forEach { bytes[it] = (it * 7).toByte() }
                }
            }

        RecoveryPhraseCodecV1.generate(deterministic).use { generated ->
            assertEquals(12, generated.phrase.words().size)
            assertTrue(RecoveryPhraseCodecV1.isValid(generated.phrase.displayString()))
        }
    }

    @Test
    fun setupChallengeChecksSelectedWords() {
        val phrase = RecoveryPhraseCodecV1.encode(ByteArray(16))
        val challenge = RecoveryPhraseChallenge(listOf(1, 7, 12))
        val words = phrase.words()

        assertTrue(
            challenge.matches(
                phrase,
                mapOf(1 to words[0], 7 to words[6], 12 to words[11]),
            ),
        )
        assertFalse(
            challenge.matches(
                phrase,
                mapOf(1 to words[0], 7 to "wrong", 12 to words[11]),
            ),
        )
    }
}
