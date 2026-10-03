// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class SecureContentAccountMigrationManifestEntryV1(
    val accountRecordKey: String,
    val candidateDigest: ByteArray,
) {
    init {
        require(accountRecordKey.startsWith("account."))
        require(candidateDigest.size == DIGEST_BYTES)
    }

    companion object {
        const val DIGEST_BYTES = 32
    }
}

internal class SecureContentAccountMigrationManifestV1(
    val transactionId: String,
    entries: Collection<SecureContentAccountMigrationManifestEntryV1>,
) {
    val entries: List<SecureContentAccountMigrationManifestEntryV1> =
        entries.sortedBy { it.accountRecordKey }

    init {
        require(transactionId.isNotBlank())
        require(this.entries.size <= MAX_ACCOUNTS)
        require(this.entries.map { it.accountRecordKey }.distinct().size == this.entries.size)
    }

    fun evidence(): ByteArray {
        val encoded = SecureContentAccountMigrationManifestCodecV1.encode(this)
        return try {
            MessageDigest.getInstance("SHA-256").digest(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    companion object {
        const val MAX_ACCOUNTS = 512
    }
}

internal object SecureContentAccountMigrationManifestCodecV1 {
    private const val MAGIC = 0x4e43414d // NCAM
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 256

    fun encode(manifest: SecureContentAccountMigrationManifestV1): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                writeString(output, manifest.transactionId)
                output.writeInt(manifest.entries.size)
                manifest.entries.forEach { entry ->
                    writeString(output, entry.accountRecordKey)
                    output.write(entry.candidateDigest)
                }
            }
            buffer.toByteArray()
        }

    fun decode(bytes: ByteArray): SecureContentAccountMigrationManifestV1 {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw IllegalArgumentException("Unsupported account migration manifest")
                }
                val transactionId = readString(input)
                val count = input.readInt()
                if (count !in 0..SecureContentAccountMigrationManifestV1.MAX_ACCOUNTS) {
                    throw IllegalArgumentException("Invalid account migration manifest size")
                }
                val entries =
                    ArrayList<SecureContentAccountMigrationManifestEntryV1>(count)
                repeat(count) {
                    val recordKey = readString(input)
                    val digest =
                        ByteArray(
                            SecureContentAccountMigrationManifestEntryV1.DIGEST_BYTES,
                        )
                    input.readFully(digest)
                    entries +=
                        SecureContentAccountMigrationManifestEntryV1(
                            recordKey,
                            digest,
                        )
                }
                if (input.available() != 0) {
                    throw IllegalArgumentException("Trailing account migration manifest data")
                }
                return SecureContentAccountMigrationManifestV1(transactionId, entries)
            }
        } catch (error: Exception) {
            if (error is IllegalArgumentException) throw error
            throw IllegalArgumentException("Malformed account migration manifest", error)
        }
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
    ) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        if (size !in 1..MAX_STRING_BYTES) {
            throw IllegalArgumentException("Invalid account migration string size")
        }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

internal enum class SecureContentAccountMigrationStateV1 {
    ABSENT,
    PREPARED,
    COMMITTED,
    ROLLED_BACK,
    CORRUPT,
}
