// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Canonical associated-data encoding for S5.5 cryptoVersion 1.
 *
 * The encoding intentionally has no path, URI, message relation, metadata locator, key material,
 * plaintext, or caller-provided fields.
 */
internal object SecureContentAssociatedData {
    private val marker = "NCS-SC-AAD".toByteArray(StandardCharsets.US_ASCII)
    private const val schemaVersion = 1

    fun forContext(context: SecureContentCryptoContext): ByteArray {
        require(context.cryptoVersion == 1) { "unsupported cryptoVersion" }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(marker)
                output.writeByte(schemaVersion)
                writeUtf8(output, context.namespace)
                output.writeInt(context.cryptoVersion)
                writeUtf8(output, context.accountUuid)
                writeUtf8(output, context.contentId)
            }
            bytes.toByteArray()
        }
    }

    fun forAccountMaterial(
        accountUuid: String,
        rootVersion: Int = 1,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-ROOT-1".toByteArray(StandardCharsets.US_ASCII))
                output.writeInt(rootVersion)
                writeUtf8(output, accountUuid)
            }
            bytes.toByteArray()
        }

    fun forGatedAccountMaterial(
        accountUuid: String,
        hierarchyVersion: Int = 1,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-GATED-ACCOUNT-1".toByteArray(StandardCharsets.US_ASCII))
                output.writeInt(hierarchyVersion)
                writeUtf8(output, accountUuid)
            }
            bytes.toByteArray()
        }

    fun forAppMasterAccountKek(accountUuid: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-APP-MASTER-ACCOUNT-KEK-1".toByteArray(StandardCharsets.US_ASCII))
                writeUtf8(output, accountUuid)
            }
            bytes.toByteArray()
        }

    fun forAccountMigrationManifest(transactionId: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-ACCOUNT-MIGRATION-MANIFEST-1".toByteArray(StandardCharsets.US_ASCII))
                writeUtf8(output, transactionId)
            }
            bytes.toByteArray()
        }

    fun forAccountMigrationProbe(
        transactionId: String,
        accountUuid: String,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-ACCOUNT-MIGRATION-PROBE-1".toByteArray(StandardCharsets.US_ASCII))
                writeUtf8(output, transactionId)
                writeUtf8(output, accountUuid)
            }
            bytes.toByteArray()
        }

    fun forAccountDeactivationManifest(transactionId: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-ACCOUNT-DEACTIVATION-MANIFEST-1".toByteArray(StandardCharsets.US_ASCII))
                writeUtf8(output, transactionId)
            }
            bytes.toByteArray()
        }

    fun forAccountDeactivationProbe(
        transactionId: String,
        accountUuid: String,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-ACCOUNT-DEACTIVATION-PROBE-1".toByteArray(StandardCharsets.US_ASCII))
                writeUtf8(output, transactionId)
                writeUtf8(output, accountUuid)
            }
            bytes.toByteArray()
        }

    fun forRecovery(
        accountUuid: String,
        transactionId: String,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-RECOVERY-1".toByteArray(StandardCharsets.US_ASCII))
                writeUtf8(output, accountUuid)
                writeUtf8(output, transactionId)
            }
            bytes.toByteArray()
        }

    fun forMetadata(
        accountUuid: String,
        contentId: String,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NCS-SC-METADATA-1".toByteArray(StandardCharsets.US_ASCII))
                writeUtf8(output, accountUuid)
                writeUtf8(output, contentId)
            }
            bytes.toByteArray()
        }

    private fun writeUtf8(
        output: DataOutputStream,
        value: String,
    ) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(encoded.size)
        output.write(encoded)
    }
}
