// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.recovery

import java.security.SecureRandom

data class RecoveryPhraseChallenge(
    val positions: List<Int>,
) {
    init {
        require(positions.size == 3)
        require(positions.distinct().size == positions.size)
        require(positions.all { it in 1..RecoveryPhraseCodecV1.WORD_COUNT })
    }

    fun matches(
        phrase: RecoveryPhrase,
        answers: Map<Int, String>,
    ): Boolean {
        val words = phrase.words()
        return positions.all { position ->
            val expected = words[position - 1]
            val supplied = answers[position]?.trim()?.lowercase()
            supplied != null && supplied == expected
        }
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): RecoveryPhraseChallenge {
            val positions = LinkedHashSet<Int>()
            while (positions.size < 3) {
                positions += random.nextInt(RecoveryPhraseCodecV1.WORD_COUNT) + 1
            }
            return RecoveryPhraseChallenge(positions.toList().sorted())
        }
    }
}
