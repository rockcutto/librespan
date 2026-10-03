// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.security.cryptolock

import eu.siacs.conversations.security.recovery.RecoveryWrappedAppMasterKeyRecordCodecV1
import eu.siacs.conversations.security.recovery.RecoveryWrappedAppMasterKeyRecordV1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

enum class InactiveDeviceActivationPhase {
    PREPARING,
    NORMAL_WRAPPED,
    WRAPPERS_VERIFIED,
    MIGRATION_PREPARED,
    MIGRATION_COMMITTED,
    ACTIVE,
    ROLLBACK_REQUIRED,
}

class InactiveDeviceActivationRecordV1 internal constructor(
    val transactionId: String,
    val phase: InactiveDeviceActivationPhase,
    val recoveryWrapper: RecoveryWrappedAppMasterKeyRecordV1,
    val normalWrapper: NormalWrappedAppMasterKeyRecordV1?,
    migrationEvidence: ByteArray?,
    val createdAt: Long,
    val updatedAt: Long,
    val activatedAt: Long?,
) {
    private val migrationEvidenceValue = migrationEvidence?.copyOf()

    init {
        require(transactionId.isNotBlank())
        require(createdAt > 0L)
        require(updatedAt >= createdAt)
        when (phase) {
            InactiveDeviceActivationPhase.PREPARING ->
                require(normalWrapper == null && migrationEvidenceValue == null && activatedAt == null)
            InactiveDeviceActivationPhase.NORMAL_WRAPPED,
            InactiveDeviceActivationPhase.WRAPPERS_VERIFIED ->
                require(normalWrapper != null && migrationEvidenceValue == null && activatedAt == null)
            InactiveDeviceActivationPhase.MIGRATION_PREPARED,
            InactiveDeviceActivationPhase.MIGRATION_COMMITTED ->
                require(normalWrapper != null && migrationEvidenceValue != null && activatedAt == null)
            InactiveDeviceActivationPhase.ACTIVE ->
                require(normalWrapper != null && migrationEvidenceValue != null && activatedAt != null)
            InactiveDeviceActivationPhase.ROLLBACK_REQUIRED -> Unit
        }
    }

    fun migrationEvidence(): ByteArray? = migrationEvidenceValue?.copyOf()

    override fun toString(): String =
        "InactiveDeviceActivationRecordV1(transaction=[REDACTED],phase=$phase)"
}

internal object InactiveDeviceActivationTransitionsV1 {
    fun permits(
        from: InactiveDeviceActivationPhase,
        to: InactiveDeviceActivationPhase,
    ): Boolean =
        when (from) {
            InactiveDeviceActivationPhase.PREPARING ->
                to == InactiveDeviceActivationPhase.NORMAL_WRAPPED ||
                    to == InactiveDeviceActivationPhase.ROLLBACK_REQUIRED
            InactiveDeviceActivationPhase.NORMAL_WRAPPED ->
                to == InactiveDeviceActivationPhase.WRAPPERS_VERIFIED ||
                    to == InactiveDeviceActivationPhase.ROLLBACK_REQUIRED
            InactiveDeviceActivationPhase.WRAPPERS_VERIFIED ->
                to == InactiveDeviceActivationPhase.MIGRATION_PREPARED ||
                    to == InactiveDeviceActivationPhase.ROLLBACK_REQUIRED
            InactiveDeviceActivationPhase.MIGRATION_PREPARED ->
                to == InactiveDeviceActivationPhase.MIGRATION_COMMITTED ||
                    to == InactiveDeviceActivationPhase.ROLLBACK_REQUIRED
            InactiveDeviceActivationPhase.MIGRATION_COMMITTED ->
                to == InactiveDeviceActivationPhase.ACTIVE ||
                    to == InactiveDeviceActivationPhase.ROLLBACK_REQUIRED
            InactiveDeviceActivationPhase.ACTIVE -> false
            InactiveDeviceActivationPhase.ROLLBACK_REQUIRED -> false
        }
}

object InactiveDeviceActivationRecordCodecV1 {
    private const val MAGIC: Int = 0x4e434154 // "NCAT"
    private const val FORMAT_VERSION: Int = 1
    private const val MAX_RECORD_BYTES: Int = 16 * 1024
    private const val MAX_STRING_BYTES: Int = 128
    private const val MAX_NESTED_RECORD_BYTES: Int = 4096
    private const val MAX_MIGRATION_EVIDENCE_BYTES: Int = 512

    fun encode(record: InactiveDeviceActivationRecordV1): ByteArray {
        val recovery = RecoveryWrappedAppMasterKeyRecordCodecV1.encode(record.recoveryWrapper)
        val normal = record.normalWrapper?.let(NormalWrappedAppMasterKeyRecordCodecV1::encode)
        val evidence = record.migrationEvidence()
        return try {
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    writeString(output, record.transactionId)
                    writeString(output, record.phase.name)
                    writeBytes(output, recovery)
                    writeNullableBytes(output, normal)
                    writeNullableBytes(output, evidence)
                    output.writeLong(record.createdAt)
                    output.writeLong(record.updatedAt)
                    output.writeBoolean(record.activatedAt != null)
                    record.activatedAt?.let(output::writeLong)
                }
                buffer.toByteArray()
            }
        } finally {
            recovery.fill(0)
            normal?.fill(0)
            evidence?.fill(0)
        }
    }

    fun decode(encoded: ByteArray): InactiveDeviceActivationRecordV1 {
        if (encoded.isEmpty() || encoded.size > MAX_RECORD_BYTES) {
            throw InvalidInactiveDeviceActivationRecordException("Activation record size is invalid")
        }
        try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                    throw InvalidInactiveDeviceActivationRecordException(
                        "Unsupported activation record",
                    )
                }
                val transactionId = readString(input)
                val phase =
                    try {
                        InactiveDeviceActivationPhase.valueOf(readString(input))
                    } catch (_: IllegalArgumentException) {
                        throw InvalidInactiveDeviceActivationRecordException(
                            "Unknown activation phase",
                        )
                    }
                val recoveryBytes = readBytes(input, MAX_NESTED_RECORD_BYTES)
                val normalBytes = readNullableBytes(input, MAX_NESTED_RECORD_BYTES)
                val evidence = readNullableBytes(input, MAX_MIGRATION_EVIDENCE_BYTES)
                val createdAt = input.readLong()
                val updatedAt = input.readLong()
                val activatedAt = if (input.readBoolean()) input.readLong() else null
                if (input.available() != 0) {
                    throw InvalidInactiveDeviceActivationRecordException(
                        "Activation record has trailing data",
                    )
                }

                return try {
                    InactiveDeviceActivationRecordV1(
                        transactionId = transactionId,
                        phase = phase,
                        recoveryWrapper =
                            RecoveryWrappedAppMasterKeyRecordCodecV1.decode(recoveryBytes),
                        normalWrapper =
                            normalBytes?.let(NormalWrappedAppMasterKeyRecordCodecV1::decode),
                        migrationEvidence = evidence,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        activatedAt = activatedAt,
                    )
                } finally {
                    recoveryBytes.fill(0)
                    normalBytes?.fill(0)
                    evidence?.fill(0)
                }
            }
        } catch (error: InvalidInactiveDeviceActivationRecordException) {
            throw error
        } catch (error: Exception) {
            throw InvalidInactiveDeviceActivationRecordException(
                "Activation record is malformed",
                error,
            )
        }
    }

    private fun writeString(output: DataOutputStream, value: String) =
        writeBytes(output, value.toByteArray(StandardCharsets.UTF_8))

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }

    private fun writeNullableBytes(output: DataOutputStream, value: ByteArray?) {
        output.writeBoolean(value != null)
        value?.let { writeBytes(output, it) }
    }

    private fun readString(input: DataInputStream): String {
        val bytes = readBytes(input, MAX_STRING_BYTES)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
        val length = input.readInt()
        if (length !in 1..maximum) {
            throw InvalidInactiveDeviceActivationRecordException(
                "Activation record field size is invalid",
            )
        }
        return ByteArray(length).also(input::readFully)
    }

    private fun readNullableBytes(input: DataInputStream, maximum: Int): ByteArray? =
        if (!input.readBoolean()) null else readBytes(input, maximum)
}

class InvalidInactiveDeviceActivationRecordException : Exception {
    constructor(message: String) : super(message)

    constructor(message: String, cause: Throwable) : super(message, cause)
}
