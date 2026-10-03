// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.recovery

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * LibreSpan Recovery Phrase v2 facade.
 *
 * The bit/checksum/normalization rules live in [RecoveryPhraseCodecV2Core]. The concrete Russian
 * dictionaries wired here are the frozen production data for `librespan-ru-pairs-v1`.
 * Locale routing is still disabled; core correctness remains independently tested with deterministic
 * 2048+2048 Cyrillic dictionaries.
 */
object RecoveryPhraseCodecV2 {
    const val FORMAT_VERSION: Int = 2
    const val DICTIONARY_ID: String = "librespan-ru-pairs-v1"
    const val WORD_COUNT: Int = 12
    const val PAIR_COUNT: Int = 6
    const val ENTROPY_BYTES: Int = 16
    internal const val DICTIONARY_SIZE: Int = 2048
    internal const val INDEX_BITS: Int = 11
    internal const val ENTROPY_BITS: Int = ENTROPY_BYTES * 8
    internal const val CHECKSUM_BITS: Int = 4

    private val core: RecoveryPhraseCodecV2Core by lazy {
        RecoveryPhraseCodecV2Core(
            adjectives = RecoveryPhraseRussianAdjectiveListV1.words,
            nouns = RecoveryPhraseRussianNounListV1.words,
        )
    }

    fun generate(random: SecureRandom = SecureRandom()): GeneratedRecoveryPhrase =
        core.generate(random)

    fun encode(secret: RecoverySecret): RecoveryPhrase =
        core.encode(secret)

    fun encode(entropy: ByteArray): RecoveryPhrase =
        core.encode(entropy)

    @Throws(InvalidRecoveryPhraseException::class)
    fun decode(phrase: CharSequence): RecoverySecret =
        core.decode(phrase)

    @Throws(InvalidRecoveryPhraseException::class)
    fun decode(words: List<String>): RecoverySecret =
        core.decode(words)

    fun isValid(phrase: CharSequence): Boolean =
        core.isValid(phrase)
}

/**
 * Dictionary-injected mathematical codec for Recovery Phrase v2.
 *
 * This class intentionally knows nothing about locale selection, UI, morphology, provenance,
 * dictionary curation, or the future e/yo acceptance policy. Source dictionary entries are required
 * to be Cyrillic letters only; reverse lookup uses Unicode NFKD + Locale.ROOT lowercase.
 */
internal class RecoveryPhraseCodecV2Core(
    adjectives: List<String>,
    nouns: List<String>,
) {
    private val adjectiveWords: List<String> = adjectives.toList()
    private val nounWords: List<String> = nouns.toList()
    private val adjectiveToIndex: Map<String, Int> =
        buildIndex(adjectiveWords, "adjective")
    private val nounToIndex: Map<String, Int> =
        buildIndex(nounWords, "noun")

    fun generate(random: SecureRandom = SecureRandom()): GeneratedRecoveryPhrase {
        val entropy = ByteArray(RecoveryPhraseCodecV2.ENTROPY_BYTES)
        random.nextBytes(entropy)
        return try {
            GeneratedRecoveryPhrase(
                phrase = encode(entropy),
                secret = RecoverySecret.copyOf(entropy),
            )
        } finally {
            entropy.fill(0)
        }
    }

    fun encode(secret: RecoverySecret): RecoveryPhrase =
        secret.useCopy(::encode)

    fun encode(entropy: ByteArray): RecoveryPhrase {
        require(entropy.size == RecoveryPhraseCodecV2.ENTROPY_BYTES) {
            "Recovery entropy must be exactly ${RecoveryPhraseCodecV2.ENTROPY_BYTES} bytes"
        }

        val digest = sha256(entropy)
        val checksumNibble =
            try {
                (digest[0].toInt() ushr 4) and 0x0f
            } finally {
                digest.fill(0)
            }

        val words = ArrayList<String>(RecoveryPhraseCodecV2.WORD_COUNT)
        repeat(RecoveryPhraseCodecV2.WORD_COUNT) { component ->
            var dictionaryIndex = 0
            repeat(RecoveryPhraseCodecV2.INDEX_BITS) { offset ->
                val bitPosition = component * RecoveryPhraseCodecV2.INDEX_BITS + offset
                val bit =
                    if (bitPosition < RecoveryPhraseCodecV2.ENTROPY_BITS) {
                        readBit(entropy, bitPosition)
                    } else {
                        val checksumOffset = bitPosition - RecoveryPhraseCodecV2.ENTROPY_BITS
                        (checksumNibble ushr
                            (RecoveryPhraseCodecV2.CHECKSUM_BITS - 1 - checksumOffset)) and 1
                    }
                dictionaryIndex = (dictionaryIndex shl 1) or bit
            }
            words +=
                if (component % 2 == 0) {
                    adjectiveWords[dictionaryIndex]
                } else {
                    nounWords[dictionaryIndex]
                }
        }
        return RecoveryPhrase(words, RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1)
    }

    @Throws(InvalidRecoveryPhraseException::class)
    fun decode(phrase: CharSequence): RecoverySecret =
        decode(tokenize(phrase))

    @Throws(InvalidRecoveryPhraseException::class)
    fun decode(words: List<String>): RecoverySecret {
        if (words.size != RecoveryPhraseCodecV2.WORD_COUNT) {
            throw InvalidRecoveryPhraseException(
                "Recovery phrase must contain exactly six two-word pairs",
            )
        }

        val indices =
            IntArray(RecoveryPhraseCodecV2.WORD_COUNT) { position ->
                val dictionary =
                    if (position % 2 == 0) adjectiveToIndex else nounToIndex
                dictionary[canonicalize(words[position])]
                    ?: throw InvalidRecoveryPhraseException(
                        "Recovery phrase contains an unknown word",
                    )
            }

        val entropy = ByteArray(RecoveryPhraseCodecV2.ENTROPY_BYTES)
        var suppliedChecksum = 0
        repeat(RecoveryPhraseCodecV2.WORD_COUNT * RecoveryPhraseCodecV2.INDEX_BITS) { bitPosition ->
            val index = indices[bitPosition / RecoveryPhraseCodecV2.INDEX_BITS]
            val bit =
                (index ushr
                    (RecoveryPhraseCodecV2.INDEX_BITS - 1 -
                        (bitPosition % RecoveryPhraseCodecV2.INDEX_BITS))) and 1
            if (bitPosition < RecoveryPhraseCodecV2.ENTROPY_BITS) {
                writeBit(entropy, bitPosition, bit)
            } else {
                suppliedChecksum = (suppliedChecksum shl 1) or bit
            }
        }

        val digest = sha256(entropy)
        val expectedChecksum =
            try {
                (digest[0].toInt() ushr 4) and 0x0f
            } finally {
                digest.fill(0)
            }

        if (suppliedChecksum != expectedChecksum) {
            entropy.fill(0)
            throw InvalidRecoveryPhraseException("Recovery phrase checksum does not match")
        }

        return RecoverySecret.takeOwnership(entropy)
    }

    fun isValid(phrase: CharSequence): Boolean =
        try {
            decode(phrase).use { true }
        } catch (_: InvalidRecoveryPhraseException) {
            false
        }

    internal fun canonicalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)

    private fun buildIndex(
        words: List<String>,
        label: String,
    ): Map<String, Int> {
        require(words.size == RecoveryPhraseCodecV2.DICTIONARY_SIZE) {
            "Recovery $label dictionary must contain exactly " +
                "${RecoveryPhraseCodecV2.DICTIONARY_SIZE} entries"
        }
        require(words.all(::isCyrillicToken)) {
            "Recovery $label dictionary must contain Cyrillic letters only"
        }

        val canonical = words.map(::canonicalize)
        require(canonical.toSet().size == words.size) {
            "Recovery $label dictionary must be unique after NFKD normalization"
        }
        return canonical.withIndex().associate { it.value to it.index }
    }

    private fun tokenize(phrase: CharSequence): List<String> {
        val words = ArrayList<String>(RecoveryPhraseCodecV2.WORD_COUNT)
        val token = StringBuilder()

        fun flushToken() {
            if (token.isEmpty()) {
                return
            }
            words += canonicalize(token.toString())
            token.setLength(0)
        }

        for (index in 0 until phrase.length) {
            val ch = phrase[index]
            if (ch.isWhitespace()) {
                flushToken()
            } else {
                token.append(ch)
            }
        }
        flushToken()
        return words
    }

    private fun isCyrillicToken(value: String): Boolean {
        if (value.isEmpty()) {
            return false
        }
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
        return true
    }

    private fun readBit(
        bytes: ByteArray,
        bitPosition: Int,
    ): Int {
        val value = bytes[bitPosition / 8].toInt() and 0xff
        return (value ushr (7 - (bitPosition % 8))) and 1
    }

    private fun writeBit(
        bytes: ByteArray,
        bitPosition: Int,
        bit: Int,
    ) {
        if (bit == 0) {
            return
        }
        val byteIndex = bitPosition / 8
        val mask = 1 shl (7 - (bitPosition % 8))
        bytes[byteIndex] = (bytes[byteIndex].toInt() or mask).toByte()
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}
