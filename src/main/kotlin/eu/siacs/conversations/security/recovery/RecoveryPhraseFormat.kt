// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.recovery

import java.security.SecureRandom

data class RecoveryPhraseFormat internal constructor(
    val formatVersion: Int,
    val dictionaryId: String,
    val wordCount: Int,
    val displayUnitCount: Int,
    val pairedDisplay: Boolean,
    internal val kdfDomain: String,
)

object RecoveryPhraseFormats {
    val BIP39_EN_V1 =
        RecoveryPhraseFormat(
            RecoveryPhraseCodecV1.FORMAT_VERSION,
            RecoveryPhraseCodecV1.DICTIONARY_ID,
            RecoveryPhraseCodecV1.WORD_COUNT,
            RecoveryPhraseCodecV1.WORD_COUNT,
            false,
            "NeoCont", // frozen legacy protocol domain
        )

    val LIBRESPAN_RU_PAIRS_V1 =
        RecoveryPhraseFormat(
            RecoveryPhraseCodecV2.FORMAT_VERSION,
            RecoveryPhraseCodecV2.DICTIONARY_ID,
            RecoveryPhraseCodecV2.WORD_COUNT,
            RecoveryPhraseCodecV2.PAIR_COUNT,
            true,
            "LibreSpan",
        )

    fun resolve(formatVersion: Int, dictionaryId: String): RecoveryPhraseFormat? =
        when {
            formatVersion == BIP39_EN_V1.formatVersion &&
                dictionaryId == BIP39_EN_V1.dictionaryId -> BIP39_EN_V1
            formatVersion == LIBRESPAN_RU_PAIRS_V1.formatVersion &&
                dictionaryId == LIBRESPAN_RU_PAIRS_V1.dictionaryId -> LIBRESPAN_RU_PAIRS_V1
            else -> null
        }
}

object RecoveryPhraseCodecs {
    fun generate(
        format: RecoveryPhraseFormat,
        random: SecureRandom = SecureRandom(),
    ): GeneratedRecoveryPhrase =
        when (format) {
            RecoveryPhraseFormats.BIP39_EN_V1 -> RecoveryPhraseCodecV1.generate(random)
            RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1 -> RecoveryPhraseCodecV2.generate(random)
            else -> error("Unsupported recovery phrase format")
        }

    @Throws(InvalidRecoveryPhraseException::class)
    fun decode(phrase: CharSequence, format: RecoveryPhraseFormat): RecoverySecret =
        when (format) {
            RecoveryPhraseFormats.BIP39_EN_V1 -> RecoveryPhraseCodecV1.decode(phrase)
            RecoveryPhraseFormats.LIBRESPAN_RU_PAIRS_V1 -> RecoveryPhraseCodecV2.decode(phrase)
            else -> throw InvalidRecoveryPhraseException("Unsupported recovery phrase format")
        }
}
