package eu.siacs.conversations.security.recovery

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPhraseRussianDictionaryV1Test {
    @Test
    fun adjectiveDictionaryIsFrozenAndStructurallySafe() {
        assertDictionary(
            words = RecoveryPhraseRussianAdjectiveListV1.words,
            minLength = 4,
            maxLength = 12,
            expectedSha256 = "9be61055620f0f1ba6966625794a6e83a075766fa150554e0501f909ca58106d",
        )
    }

    @Test
    fun nounDictionaryIsFrozenAndStructurallySafe() {
        assertDictionary(
            words = RecoveryPhraseRussianNounListV1.words,
            minLength = 3,
            maxLength = 11,
            expectedSha256 = "7bdd95e366f12cd5401b75b80cdc62a795ed65c8e5de85c9441a7040e12945a7",
        )
    }

    @Test
    fun reviewedForbiddenVocabularyIsAbsent() {
        val all =
            (RecoveryPhraseRussianAdjectiveListV1.words +
                RecoveryPhraseRussianNounListV1.words).toSet()
        val forbidden =
            setOf(
                "чернокожий",
                "прыщавый",
                "помешанный",
                "полоумный",
                "пионерский",
                "полицейский",
                "советский",
                "партийный",
                "уголовник",
                "парторг",
                "комсорг",
                "президиум",
                "архангельск",
                "воронеж",
                "харьков",
                "мирон",
                "казимир",
                "фриц",
                "дезертир",
                "обрез",
                "заложник",
            )
        assertTrue(forbidden.intersect(all).isEmpty())
    }

    private fun assertDictionary(
        words: List<String>,
        minLength: Int,
        maxLength: Int,
        expectedSha256: String,
    ) {
        assertEquals(2048, words.size)
        assertEquals(2048, words.toSet().size)

        assertTrue(
            words.all { word ->
                word.length in minLength..maxLength &&
                    word.matches(Regex("^[а-я]+$")) &&
                    !word.contains('ё')
            },
        )

        val normalized =
            words.map {
                Normalizer.normalize(it, Normalizer.Form.NFKD)
                    .lowercase(Locale.ROOT)
            }
        assertEquals(2048, normalized.toSet().size)
        assertEquals(2048, normalized.map { it.take(6) }.toSet().size)

        assertFalse(words.any(String::isBlank))
        assertEquals(expectedSha256, sha256(words.joinToString("\n")))
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
