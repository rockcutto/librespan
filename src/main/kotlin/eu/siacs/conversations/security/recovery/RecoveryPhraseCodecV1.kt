// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.recovery

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * NeoCont Recovery Phrase v1.
 *
 * This uses the BIP-0039 English 2048-word dictionary and its 128-bit entropy + 4-bit SHA-256
 * checksum mnemonic packing. NeoCont does NOT use the BIP-0039 wallet-seed/PBKDF2 derivation.
 * The decoded 128-bit value is a NeoCont recovery secret and will feed a separately versioned
 * Recovery KEK KDF/wrapper.
 */
object RecoveryPhraseCodecV1 {
    const val FORMAT_VERSION: Int = 1
    const val DICTIONARY_ID: String = "bip39-en-v1"
    const val WORD_COUNT: Int = 12
    const val ENTROPY_BYTES: Int = 16
    const val ENTROPY_BITS: Int = ENTROPY_BYTES * 8
    const val CHECKSUM_BITS: Int = ENTROPY_BITS / 32

    private val wordToIndex: Map<String, Int> by lazy {
        val words = RecoveryPhraseEnglishWordListV1.words
        require(words.size == 2048) { "Recovery dictionary must contain exactly 2048 words" }
        require(words.toSet().size == words.size) { "Recovery dictionary words must be unique" }
        words.withIndex().associate { it.value to it.index }
    }

    fun generate(random: SecureRandom = SecureRandom()): GeneratedRecoveryPhrase {
        val entropy = ByteArray(ENTROPY_BYTES)
        random.nextBytes(entropy)
        val phrase = encode(entropy)
        return GeneratedRecoveryPhrase(
            phrase = phrase,
            secret = RecoverySecret.copyOf(entropy),
        ).also {
            entropy.fill(0)
        }
    }

    fun encode(secret: RecoverySecret): RecoveryPhrase =
        secret.useCopy(::encode)

    fun encode(entropy: ByteArray): RecoveryPhrase {
        require(entropy.size == ENTROPY_BYTES) {
            "Recovery entropy must be exactly $ENTROPY_BYTES bytes"
        }
        val checksumByte = sha256(entropy)[0].toInt() and 0xff
        val words = ArrayList<String>(WORD_COUNT)

        repeat(WORD_COUNT) { wordIndex ->
            var dictionaryIndex = 0
            repeat(11) { bitInWord ->
                val bitPosition = wordIndex * 11 + bitInWord
                val bit =
                    if (bitPosition < ENTROPY_BITS) {
                        readBit(entropy, bitPosition)
                    } else {
                        (checksumByte ushr (7 - (bitPosition - ENTROPY_BITS))) and 1
                    }
                dictionaryIndex = (dictionaryIndex shl 1) or bit
            }
            words += RecoveryPhraseEnglishWordListV1.words[dictionaryIndex]
        }
        return RecoveryPhrase(words, RecoveryPhraseFormats.BIP39_EN_V1)
    }

    @Throws(InvalidRecoveryPhraseException::class)
    fun decode(phrase: CharSequence): RecoverySecret =
        decode(normalizeWords(phrase))

    @Throws(InvalidRecoveryPhraseException::class)
    fun decode(words: List<String>): RecoverySecret {
        if (words.size != WORD_COUNT) {
            throw InvalidRecoveryPhraseException("Recovery phrase must contain exactly 12 words")
        }

        val indices =
            IntArray(WORD_COUNT) { position ->
                val word =
                    Normalizer.normalize(words[position].trim(), Normalizer.Form.NFKD)
                        .lowercase(Locale.ROOT)
                wordToIndex[word]
                    ?: throw InvalidRecoveryPhraseException(
                        "Recovery phrase contains an unknown word",
                    )
            }

        val entropy = ByteArray(ENTROPY_BYTES)
        var suppliedChecksum = 0
        repeat(WORD_COUNT * 11) { bitPosition ->
            val index = indices[bitPosition / 11]
            val bit = (index ushr (10 - (bitPosition % 11))) and 1
            if (bitPosition < ENTROPY_BITS) {
                writeBit(entropy, bitPosition, bit)
            } else {
                suppliedChecksum = (suppliedChecksum shl 1) or bit
            }
        }

        val expectedChecksum = (sha256(entropy)[0].toInt() ushr 4) and 0x0f
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

    private fun normalizeWords(phrase: CharSequence): List<String> {
        val words = ArrayList<String>(WORD_COUNT)
        val token = StringBuilder()
        fun flushToken() {
            if (token.isEmpty()) return
            words +=
                Normalizer.normalize(token, Normalizer.Form.NFKD)
                    .lowercase(Locale.ROOT)
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

    private fun readBit(bytes: ByteArray, bitPosition: Int): Int {
        val value = bytes[bitPosition / 8].toInt() and 0xff
        return (value ushr (7 - (bitPosition % 8))) and 1
    }

    private fun writeBit(bytes: ByteArray, bitPosition: Int, bit: Int) {
        if (bit == 0) return
        val byteIndex = bitPosition / 8
        val mask = 1 shl (7 - (bitPosition % 8))
        bytes[byteIndex] = (bytes[byteIndex].toInt() or mask).toByte()
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}

class InvalidRecoveryPhraseException(message: String) : Exception(message)

class RecoveryPhrase internal constructor(
    private val wordsValue: List<String>,
    val format: RecoveryPhraseFormat = RecoveryPhraseFormats.BIP39_EN_V1,
) {
    init {
        require(wordsValue.size == format.wordCount)
    }

    fun words(): List<String> = wordsValue.toList()

    fun displayUnits(): List<String> =
        if (format.pairedDisplay) wordsValue.chunked(2).map { it.joinToString(" ") }
        else wordsValue.toList()

    fun displayString(): String =
        displayUnits().joinToString(if (format.pairedDisplay) "\n" else " ")

    override fun toString(): String =
        "RecoveryPhrase(format=${format.formatVersion},dictionary=${format.dictionaryId},[REDACTED])"
}

class RecoverySecret private constructor(
    private val bytes: ByteArray,
) : AutoCloseable {

    fun copyBytes(): ByteArray = bytes.copyOf()

    internal inline fun <T> useCopy(block: (ByteArray) -> T): T {
        val copy = bytes.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun close() {
        bytes.fill(0)
    }

    override fun toString(): String = "RecoverySecret([REDACTED])"

    companion object {
        internal fun copyOf(bytes: ByteArray): RecoverySecret {
            require(bytes.size == RecoveryPhraseCodecV1.ENTROPY_BYTES)
            return RecoverySecret(bytes.copyOf())
        }

        internal fun takeOwnership(bytes: ByteArray): RecoverySecret {
            require(bytes.size == RecoveryPhraseCodecV1.ENTROPY_BYTES)
            return RecoverySecret(bytes)
        }
    }
}

class GeneratedRecoveryPhrase internal constructor(
    val phrase: RecoveryPhrase,
    val secret: RecoverySecret,
) : AutoCloseable {
    override fun close() {
        secret.close()
    }

    override fun toString(): String = "GeneratedRecoveryPhrase([REDACTED])"
}
