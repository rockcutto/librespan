// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.recovery

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF-SHA256.
 *
 * Kept small and provider-neutral so recovery format v1 does not depend on Android Keystore or a
 * Tink keyset representation. The wrapper tests include the RFC 5869 test vector.
 */
internal object HkdfSha256 {
    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32

    fun derive(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        require(outputLength in 1..(255 * HASH_LENGTH))

        val extract = Mac.getInstance(ALGORITHM)
        extract.init(SecretKeySpec(salt, ALGORITHM))
        val pseudorandomKey = extract.doFinal(inputKeyMaterial)

        return try {
            val output = ByteArray(outputLength)
            var previous = ByteArray(0)
            var written = 0
            var counter = 1

            while (written < outputLength) {
                val expand = Mac.getInstance(ALGORITHM)
                expand.init(SecretKeySpec(pseudorandomKey, ALGORITHM))
                if (previous.isNotEmpty()) {
                    expand.update(previous)
                }
                expand.update(info)
                expand.update(counter.toByte())
                val block = expand.doFinal()

                previous.fill(0)
                previous = block

                val copyLength = minOf(block.size, outputLength - written)
                block.copyInto(output, written, 0, copyLength)
                written += copyLength
                counter++
            }

            previous.fill(0)
            output
        } finally {
            pseudorandomKey.fill(0)
        }
    }
}
