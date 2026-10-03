// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.cryptolock

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Installation App Master Key runtime holder.
 *
 * The key is never a content-encryption key. It is the future upper-level capability used to
 * protect account-scoped key-encryption material. This holder is process-memory only.
 */
class AppMasterKey private constructor(
    private val bytes: ByteArray,
) : AutoCloseable {

    internal inline fun <T> useCopy(block: (ByteArray) -> T): T {
        val copy = bytes.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    internal fun copyBytesForTest(): ByteArray = bytes.copyOf()

    internal fun matches(other: AppMasterKey): Boolean =
        useCopy { left ->
            other.useCopy { right ->
                MessageDigest.isEqual(left, right)
            }
        }

    override fun close() {
        bytes.fill(0)
    }

    override fun toString(): String = "AppMasterKey([REDACTED])"

    companion object {
        const val BYTE_LENGTH: Int = 32

        fun generate(random: SecureRandom = SecureRandom()): AppMasterKey {
            val bytes = ByteArray(BYTE_LENGTH)
            random.nextBytes(bytes)
            return takeOwnership(bytes)
        }

        internal fun copyOf(bytes: ByteArray): AppMasterKey {
            require(bytes.size == BYTE_LENGTH) { "App Master Key must be 256 bits" }
            return AppMasterKey(bytes.copyOf())
        }

        internal fun takeOwnership(bytes: ByteArray): AppMasterKey {
            require(bytes.size == BYTE_LENGTH) { "App Master Key must be 256 bits" }
            return AppMasterKey(bytes)
        }
    }
}
