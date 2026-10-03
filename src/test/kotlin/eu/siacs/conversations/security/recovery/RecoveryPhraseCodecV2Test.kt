package eu.siacs.conversations.security.recovery

import java.security.SecureRandom
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecoveryPhraseCodecV2Test {
    private val adjectives = deterministicDictionary('п').toMutableList().apply {
        this[0] = "синий"
    }
    private val nouns = deterministicDictionary('р').toMutableList().apply {
        this[0] = "маяк"
    }
    private val codec = RecoveryPhraseCodecV2Core(adjectives, nouns)

    @Test
    fun deterministicDictionariesHaveExactly2048CyrillicEntriesEach() {
        assertEquals(2048, adjectives.size)
        assertEquals(2048, nouns.size)
        assertTrue(adjectives.all(::isCyrillicLettersOnly))
        assertTrue(nouns.all(::isCyrillicLettersOnly))
        assertEquals(2048, adjectives.map(codec::canonicalize).toSet().size)
        assertEquals(2048, nouns.map(codec::canonicalize).toSet().size)
    }

    @Test
    fun zeroEntropyMapsToElevenZeroIndexesAndChecksumIndexThree() {
        val phrase = codec.encode(ByteArray(RecoveryPhraseCodecV2.ENTROPY_BYTES))
        val words = phrase.words()

        repeat(11) { position ->
            assertEquals(
                if (position % 2 == 0) adjectives[0] else nouns[0],
                words[position],
            )
        }

        // SHA-256(16 zero bytes) starts with 0x37, so the four checksum bits are 0011.
        // The last 11-bit component therefore contains seven zero entropy bits + checksum index 3.
        assertEquals(nouns[3], words[11])
        assertEquals(6, phrase.displayUnits().size)
        assertEquals(RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1, phrase.format)
    }

    @Test
    fun roundTripRestoresExact128BitSecretAcrossRepresentativeVectors() {
        val vectors =
            listOf(
                ByteArray(16),
                ByteArray(16) { it.toByte() },
                ByteArray(16) { (it * 17 + 3).toByte() },
                ByteArray(16) { 0xff.toByte() },
            )

        vectors.forEach { entropy ->
            val phrase = codec.encode(entropy)
            codec.decode(phrase.displayString()).use { decoded ->
                assertTrue(
                    "Decoded recovery secret must match the exact 128-bit input",
                    entropy.contentEquals(decoded.copyBytes()),
                )
            }
        }
    }

    @Test
    fun checksumBitCorruptionFailsClosedWithoutChangingEntropyBits() {
        val entropy = ByteArray(16) { (it * 9 + 5).toByte() }
        val words = codec.encode(entropy).words().toMutableList()
        val lastIndex = nouns.indexOf(words[11])
        assertTrue(lastIndex >= 0)

        // The least-significant bit of the final dictionary index is a checksum bit.
        words[11] = nouns[lastIndex xor 0x01]

        try {
            codec.decode(words).close()
            fail("A checksum-only bit flip must be rejected")
        } catch (_: InvalidRecoveryPhraseException) {
        }
    }

    @Test
    fun spacesTabsAndPairLineBreaksDecodeIdentically() {
        val entropy = ByteArray(16) { (it * 5 + 1).toByte() }
        val words = codec.encode(entropy).words()
        val input =
            buildString {
                append("  ")
                words.chunked(2).forEachIndexed { index, pair ->
                    if (index > 0) {
                        append(if (index % 2 == 0) "\r\n" else "\n")
                    }
                    append(pair[0])
                    append(if (index % 2 == 0) "\t" else "   ")
                    append(pair[1])
                }
                append("  ")
            }

        codec.decode(input).use { decoded ->
            assertTrue(entropy.contentEquals(decoded.copyBytes()))
        }
    }

    @Test
    fun nfkdAndLocaleIndependentCaseNormalizationAreAccepted() {
        val entropy = ByteArray(16)
        val words = codec.encode(entropy).words().toMutableList()

        // "Й" decomposes under NFKD to Cyrillic "И" + combining breve.
        // The dictionary entry and supplied token must resolve to the same canonical index.
        words[0] = Normalizer.normalize("СИНИЙ", Normalizer.Form.NFKD)
        val input = words.chunked(2).joinToString("\n") { it.joinToString(" ") }

        codec.decode(input).use { decoded ->
            assertTrue(entropy.contentEquals(decoded.copyBytes()))
        }
        assertEquals(
            codec.canonicalize("синий"),
            codec.canonicalize(words[0]),
        )
    }

    @Test
    fun adjectiveAndNounIndexesRemainPositionSeparated() {
        val words = codec.encode(ByteArray(16)).words().toMutableList()
        words[0] = nouns[0]

        assertFalse(codec.isValid(words.joinToString(" ")))
    }

    @Test
    fun generatedPhraseUsesExactlyRandom128Bits() {
        val expected = ByteArray(16) { (it * 7 + 2).toByte() }
        val random =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    expected.copyInto(bytes)
                }
            }

        codec.generate(random).use { generated ->
            assertEquals(12, generated.phrase.words().size)
            assertEquals(6, generated.phrase.displayUnits().size)
            assertTrue(expected.contentEquals(generated.secret.copyBytes()))
            codec.decode(generated.phrase.displayString()).use { decoded ->
                assertTrue(expected.contentEquals(decoded.copyBytes()))
            }
        }
    }

    @Test
    fun dictionarySizeAndCyrillicRulesFailFast() {
        try {
            RecoveryPhraseCodecV2Core(adjectives.dropLast(1), nouns)
            fail("A 2047-entry dictionary must be rejected")
        } catch (_: IllegalArgumentException) {
        }

        val nonCyrillic = adjectives.toMutableList().apply {
            this[17] = "test"
        }
        try {
            RecoveryPhraseCodecV2Core(nonCyrillic, nouns)
            fail("Non-Cyrillic dictionary entries must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun deterministicDictionary(prefix: Char): List<String> =
        List(2048) { index ->
            var value = index
            val suffix = CharArray(3)
            for (position in 2 downTo 0) {
                suffix[position] = TEST_ALPHABET[value % TEST_ALPHABET.length]
                value /= TEST_ALPHABET.length
            }
            prefix + String(suffix)
        }

    private fun isCyrillicLettersOnly(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (!Character.isLetter(codePoint) ||
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.CYRILLIC
            ) {
                return false
            }
            offset += Character.charCount(codePoint)
        }
        return value.isNotEmpty()
    }

    companion object {
        private const val TEST_ALPHABET = "абвгдежзиклмн"
    }
}
