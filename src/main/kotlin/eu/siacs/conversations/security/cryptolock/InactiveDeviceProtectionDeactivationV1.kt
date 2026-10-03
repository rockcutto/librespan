// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import eu.siacs.conversations.storage.secure.PersistentSecureContentAccountKeyDeactivationParticipantV1
import eu.siacs.conversations.security.cryptolock.NormalAppMasterKeyResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

internal enum class InactiveDeviceDeactivationPhase {
    PREPARING,
    MIGRATION_PREPARED,
    MIGRATION_COMMITTED,
}

internal class InactiveDeviceDeactivationRecordV1(
    val transactionId: String,
    val activationTransactionId: String,
    val phase: InactiveDeviceDeactivationPhase,
    migrationEvidence: ByteArray?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    private val migrationEvidenceValue = migrationEvidence?.copyOf()

    init {
        require(transactionId.isNotBlank())
        require(activationTransactionId.isNotBlank())
        require(createdAt > 0L)
        require(updatedAt >= createdAt)
        when (phase) {
            InactiveDeviceDeactivationPhase.PREPARING -> require(migrationEvidenceValue == null)
            InactiveDeviceDeactivationPhase.MIGRATION_PREPARED,
            InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED ->
                require(migrationEvidenceValue != null)
        }
    }

    fun migrationEvidence(): ByteArray? = migrationEvidenceValue?.copyOf()
}

internal object InactiveDeviceDeactivationRecordCodecV1 {
    private const val MAGIC = 0x4e434454 // NCDT
    private const val VERSION = 1
    private const val MAX_RECORD_BYTES = 4096
    private const val MAX_STRING_BYTES = 128
    private const val MAX_EVIDENCE_BYTES = 512

    fun encode(record: InactiveDeviceDeactivationRecordV1): ByteArray {
        val evidence = record.migrationEvidence()
        return try {
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    writeString(out, record.transactionId)
                    writeString(out, record.activationTransactionId)
                    writeString(out, record.phase.name)
                    out.writeBoolean(evidence != null)
                    evidence?.let {
                        out.writeInt(it.size)
                        out.write(it)
                    }
                    out.writeLong(record.createdAt)
                    out.writeLong(record.updatedAt)
                }
                buffer.toByteArray()
            }
        } finally {
            evidence?.fill(0)
        }
    }

    fun decode(bytes: ByteArray): InactiveDeviceDeactivationRecordV1 {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val transactionId = readString(input)
            val activationTransactionId = readString(input)
            val phase = InactiveDeviceDeactivationPhase.valueOf(readString(input))
            val evidence =
                if (input.readBoolean()) {
                    val size = input.readInt()
                    require(size in 1..MAX_EVIDENCE_BYTES)
                    ByteArray(size).also(input::readFully)
                } else {
                    null
                }
            val createdAt = input.readLong()
            val updatedAt = input.readLong()
            require(input.available() == 0)
            return try {
                InactiveDeviceDeactivationRecordV1(
                    transactionId,
                    activationTransactionId,
                    phase,
                    evidence,
                    createdAt,
                    updatedAt,
                )
            } finally {
                evidence?.fill(0)
            }
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..MAX_STRING_BYTES)
        return ByteArray(size).also(input::readFully).toString(StandardCharsets.UTF_8)
    }
}

internal sealed class DeactivationStoreReadResultV1 {
    data object Absent : DeactivationStoreReadResultV1()
    data object Corrupt : DeactivationStoreReadResultV1()
    data class Present(val record: InactiveDeviceDeactivationRecordV1) :
        DeactivationStoreReadResultV1()
}

internal class InactiveDeviceProtectionDeactivationStoreV1(
    context: Context,
    private val aead: ActivationJournalAeadV1 = ActivationJournalAeadV1(),
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): DeactivationStoreReadResultV1 {
        val encoded = preferences.getString(RECORD_KEY, null)
            ?: return DeactivationStoreReadResultV1.Absent
        return try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val plaintext = aead.decrypt(envelope, ASSOCIATED_DATA)
            try {
                DeactivationStoreReadResultV1.Present(
                    InactiveDeviceDeactivationRecordCodecV1.decode(plaintext),
                )
            } finally {
                envelope.fill(0)
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            DeactivationStoreReadResultV1.Corrupt
        }
    }

    @Synchronized
    fun write(record: InactiveDeviceDeactivationRecordV1): Boolean =
        try {
            val plaintext = InactiveDeviceDeactivationRecordCodecV1.encode(record)
            val envelope = aead.encrypt(plaintext, ASSOCIATED_DATA)
            try {
                preferences.edit()
                    .putString(RECORD_KEY, Base64.encodeToString(envelope, Base64.NO_WRAP))
                    .commit()
            } finally {
                plaintext.fill(0)
                envelope.fill(0)
            }
        } catch (_: Exception) {
            false
        }

    @Synchronized
    fun clear(): Boolean = preferences.edit().remove(RECORD_KEY).commit()

    private companion object {
        const val PREFERENCES_NAME = "inactive_device_deactivation_v1"
        const val RECORD_KEY = "deactivation"
        val ASSOCIATED_DATA =
            "NeoCont|InactiveDeviceDeactivationJournal|v1"
                .toByteArray(StandardCharsets.UTF_8)
    }
}

internal enum class DeactivationMigrationState {
    ABSENT,
    PREPARED,
    COMMITTED,
    CORRUPT,
}

internal data class DeactivationMigrationEvidence(
    private val value: ByteArray,
) {
    fun binding(): ByteArray = value.copyOf()

    companion object {
        fun from(value: ByteArray): DeactivationMigrationEvidence =
            DeactivationMigrationEvidence(value.copyOf())
    }
}

internal interface DeactivationMigrationParticipantV1 {
    fun prepareAndVerify(
        transactionId: String,
        appMasterKey: AppMasterKey,
    ): DeactivationMigrationEvidence?

    fun findPrepared(transactionId: String): DeactivationMigrationEvidence?

    fun state(
        transactionId: String,
        evidence: DeactivationMigrationEvidence,
    ): DeactivationMigrationState

    fun commit(
        transactionId: String,
        evidence: DeactivationMigrationEvidence,
    ): Boolean

    fun rollback(
        transactionId: String,
        evidence: DeactivationMigrationEvidence?,
    ): Boolean

    fun finalizeCommitted(transactionId: String): Boolean

    fun isFinalized(transactionId: String): Boolean
}

internal enum class DeactivationFailure {
    PROFILE_NOT_ACTIVE,
    AUTHENTICATION_REQUIRED,
    RECOVERY_REQUIRED,
    JOURNAL_CORRUPT,
    EXISTING_TRANSACTION,
    INVALID_STATE,
    MIGRATION_FAILED,
    PERSISTENCE_FAILED,
    CLEANUP_FAILED,
}

internal sealed class DeactivationResult<out T> {
    data class Success<T>(val value: T) : DeactivationResult<T>()
    data class Failure(val reason: DeactivationFailure) : DeactivationResult<Nothing>()
}

internal class DeactivationSetupSessionV1(
    val transactionId: String,
    val activationTransactionId: String,
    internal val appMasterKey: AppMasterKey,
) : AutoCloseable {
    private var closed = false
    internal fun requireOpen() = check(!closed)
    override fun close() {
        if (!closed) {
            appMasterKey.close()
            closed = true
        }
    }
}

internal data class PendingDeactivationAuthenticationV1(
    val transactionId: String,
    val activationTransactionId: String,
    val operation: AndroidAuthBoundAppMasterKeyWrapperV1.UnwrapOperation,
)

@RequiresApi(Build.VERSION_CODES.R)
internal class InactiveDeviceProtectionDeactivationTransactionV1(
    context: Context,
    private val store: InactiveDeviceProtectionDeactivationStoreV1 =
        InactiveDeviceProtectionDeactivationStoreV1(context),
    private val activationStore: InactiveDeviceProtectionActivationStoreV1 =
        InactiveDeviceProtectionActivationStoreV1(context),
    private val privacyStore: InactiveDevicePrivacyStoreV1 =
        InactiveDevicePrivacyStoreV1(context),
    private val normalWrapper: AndroidAuthBoundAppMasterKeyWrapperV1 =
        AndroidAuthBoundAppMasterKeyWrapperV1(context),
) {
    private val applicationContext = context.applicationContext

    @Synchronized
    fun begin(): DeactivationResult<PendingDeactivationAuthenticationV1> {
        when (store.read()) {
            DeactivationStoreReadResultV1.Absent -> Unit
            DeactivationStoreReadResultV1.Corrupt ->
                return DeactivationResult.Failure(DeactivationFailure.JOURNAL_CORRUPT)
            is DeactivationStoreReadResultV1.Present ->
                return DeactivationResult.Failure(DeactivationFailure.EXISTING_TRANSACTION)
        }
        val active =
            when (val read = activationStore.read()) {
                is ActivationStoreReadResult.Present ->
                    read.record.takeIf { it.phase == InactiveDeviceActivationPhase.ACTIVE }
                else -> null
            } ?: return DeactivationResult.Failure(DeactivationFailure.PROFILE_NOT_ACTIVE)
        val normalRecord =
            active.normalWrapper
                ?: return DeactivationResult.Failure(DeactivationFailure.RECOVERY_REQUIRED)
        val operation =
            when (val prepared = normalWrapper.prepareUnwrap(normalRecord)) {
                is NormalAppMasterKeyResult.Success -> prepared.value
                is NormalAppMasterKeyResult.Failure ->
                    return DeactivationResult.Failure(
                        if (
                            prepared.reason == NormalAppMasterKeyFailure.KEY_MISSING ||
                            prepared.reason == NormalAppMasterKeyFailure.KEY_INVALIDATED
                        ) DeactivationFailure.RECOVERY_REQUIRED
                        else DeactivationFailure.AUTHENTICATION_REQUIRED,
                    )
            }
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val transactionId = UUID.randomUUID().toString()
        if (!store.write(
                InactiveDeviceDeactivationRecordV1(
                    transactionId,
                    active.transactionId,
                    InactiveDeviceDeactivationPhase.PREPARING,
                    null,
                    now,
                    now,
                ),
            )
        ) {
            return DeactivationResult.Failure(DeactivationFailure.PERSISTENCE_FAILED)
        }
        return DeactivationResult.Success(
            PendingDeactivationAuthenticationV1(
                transactionId,
                active.transactionId,
                operation,
            ),
        )
    }

    @Synchronized
    fun completeAuthentication(
        pending: PendingDeactivationAuthenticationV1,
        authenticatedCryptoObject: android.hardware.biometrics.BiometricPrompt.CryptoObject?,
    ): DeactivationResult<DeactivationSetupSessionV1> {
        val current =
            (store.read() as? DeactivationStoreReadResultV1.Present)?.record
                ?: return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
        if (
            current.phase != InactiveDeviceDeactivationPhase.PREPARING ||
            current.transactionId != pending.transactionId ||
            current.activationTransactionId != pending.activationTransactionId
        ) {
            return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
        }
        val active =
            (activationStore.read() as? ActivationStoreReadResult.Present)?.record
                ?: return DeactivationResult.Failure(DeactivationFailure.PROFILE_NOT_ACTIVE)
        if (
            active.phase != InactiveDeviceActivationPhase.ACTIVE ||
            active.transactionId != pending.activationTransactionId
        ) {
            return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
        }
        return when (
            val result =
                pending.operation.completeAuthenticated(authenticatedCryptoObject)
        ) {
            is NormalAppMasterKeyResult.Success ->
                DeactivationResult.Success(
                    DeactivationSetupSessionV1(
                        pending.transactionId,
                        pending.activationTransactionId,
                        result.value,
                    ),
                )
            is NormalAppMasterKeyResult.Failure ->
                DeactivationResult.Failure(
                    if (
                        result.reason == NormalAppMasterKeyFailure.KEY_MISSING ||
                        result.reason == NormalAppMasterKeyFailure.KEY_INVALIDATED
                    ) DeactivationFailure.RECOVERY_REQUIRED
                    else DeactivationFailure.AUTHENTICATION_REQUIRED,
                )
        }
    }

    @Synchronized
    fun migrateAndDeactivate(
        session: DeactivationSetupSessionV1,
        participant: DeactivationMigrationParticipantV1,
    ): DeactivationResult<Unit> {
        session.requireOpen()
        var current =
            (store.read() as? DeactivationStoreReadResultV1.Present)?.record
                ?: return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
        if (
            current.phase != InactiveDeviceDeactivationPhase.PREPARING ||
            current.transactionId != session.transactionId ||
            current.activationTransactionId != session.activationTransactionId
        ) {
            return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
        }

        val evidence =
            participant.prepareAndVerify(session.transactionId, session.appMasterKey)
                ?: return DeactivationResult.Failure(DeactivationFailure.MIGRATION_FAILED)
        val binding = evidence.binding()
        current =
            InactiveDeviceDeactivationRecordV1(
                current.transactionId,
                current.activationTransactionId,
                InactiveDeviceDeactivationPhase.MIGRATION_PREPARED,
                binding,
                current.createdAt,
                System.currentTimeMillis().coerceAtLeast(current.createdAt),
            )
        binding.fill(0)
        if (!store.write(current)) {
            participant.rollback(session.transactionId, evidence)
            return DeactivationResult.Failure(DeactivationFailure.PERSISTENCE_FAILED)
        }
        if (!participant.commit(session.transactionId, evidence)) {
            return DeactivationResult.Failure(DeactivationFailure.MIGRATION_FAILED)
        }
        SecureContentCryptoSessionRuntimeV1.suspendForHierarchyTransition(applicationContext)
        val committed =
            InactiveDeviceDeactivationRecordV1(
                current.transactionId,
                current.activationTransactionId,
                InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED,
                evidence.binding(),
                current.createdAt,
                System.currentTimeMillis().coerceAtLeast(current.createdAt),
            )
        if (!store.write(committed)) {
            session.close()
            return DeactivationResult.Failure(DeactivationFailure.PERSISTENCE_FAILED)
        }
        session.close()
        return finalizeCommitted(committed, participant)
    }

    @Synchronized
    fun recover(
        participant: DeactivationMigrationParticipantV1,
    ): DeactivationResult<InactiveDeviceDeactivationPhase?> {
        val current =
            when (val read = store.read()) {
                DeactivationStoreReadResultV1.Absent ->
                    return DeactivationResult.Success(null)
                DeactivationStoreReadResultV1.Corrupt ->
                    return DeactivationResult.Failure(DeactivationFailure.JOURNAL_CORRUPT)
                is DeactivationStoreReadResultV1.Present -> read.record
            }
        return when (current.phase) {
            InactiveDeviceDeactivationPhase.PREPARING -> {
                val found = participant.findPrepared(current.transactionId)
                if (found == null) {
                    return if (store.clear()) {
                        DeactivationResult.Success(null)
                    } else {
                        DeactivationResult.Failure(DeactivationFailure.PERSISTENCE_FAILED)
                    }
                }
                val binding = found.binding()
                val prepared =
                    InactiveDeviceDeactivationRecordV1(
                        current.transactionId,
                        current.activationTransactionId,
                        InactiveDeviceDeactivationPhase.MIGRATION_PREPARED,
                        binding,
                        current.createdAt,
                        System.currentTimeMillis().coerceAtLeast(current.createdAt),
                    )
                binding.fill(0)
                if (!store.write(prepared)) {
                    return DeactivationResult.Failure(DeactivationFailure.PERSISTENCE_FAILED)
                }
                reconcilePrepared(prepared, found, participant)
            }
            InactiveDeviceDeactivationPhase.MIGRATION_PREPARED -> {
                val bytes =
                    current.migrationEvidence()
                        ?: return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
                val evidence = DeactivationMigrationEvidence.from(bytes)
                bytes.fill(0)
                reconcilePrepared(current, evidence, participant)
            }
            InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED ->
                when (val finalized = finalizeCommitted(current, participant)) {
                    is DeactivationResult.Success ->
                        DeactivationResult.Success(InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED)
                    is DeactivationResult.Failure -> finalized
                }
        }
    }

    private fun reconcilePrepared(
        current: InactiveDeviceDeactivationRecordV1,
        evidence: DeactivationMigrationEvidence,
        participant: DeactivationMigrationParticipantV1,
    ): DeactivationResult<InactiveDeviceDeactivationPhase?> {
        when (participant.state(current.transactionId, evidence)) {
            DeactivationMigrationState.PREPARED -> {
                if (!participant.commit(current.transactionId, evidence)) {
                    return DeactivationResult.Failure(DeactivationFailure.MIGRATION_FAILED)
                }
                SecureContentCryptoSessionRuntimeV1.suspendForHierarchyTransition(applicationContext)
            }
            DeactivationMigrationState.COMMITTED -> {
                SecureContentCryptoSessionRuntimeV1.suspendForHierarchyTransition(applicationContext)
            }
            else ->
                return DeactivationResult.Failure(DeactivationFailure.MIGRATION_FAILED)
        }
        val committed =
            InactiveDeviceDeactivationRecordV1(
                current.transactionId,
                current.activationTransactionId,
                InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED,
                evidence.binding(),
                current.createdAt,
                System.currentTimeMillis().coerceAtLeast(current.createdAt),
            )
        if (!store.write(committed)) {
            return DeactivationResult.Failure(DeactivationFailure.PERSISTENCE_FAILED)
        }
        return when (val finalized = finalizeCommitted(committed, participant)) {
            is DeactivationResult.Success ->
                DeactivationResult.Success(InactiveDeviceDeactivationPhase.MIGRATION_COMMITTED)
            is DeactivationResult.Failure -> finalized
        }
    }

    @Synchronized
    fun abortBeforeMigration(): DeactivationResult<Unit> {
        val current =
            when (val read = store.read()) {
                DeactivationStoreReadResultV1.Absent ->
                    return DeactivationResult.Success(Unit)
                DeactivationStoreReadResultV1.Corrupt ->
                    return DeactivationResult.Failure(DeactivationFailure.JOURNAL_CORRUPT)
                is DeactivationStoreReadResultV1.Present -> read.record
            }
        if (current.phase != InactiveDeviceDeactivationPhase.PREPARING) {
            return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
        }
        return if (store.clear()) {
            DeactivationResult.Success(Unit)
        } else {
            DeactivationResult.Failure(DeactivationFailure.PERSISTENCE_FAILED)
        }
    }

    private fun finalizeCommitted(
        current: InactiveDeviceDeactivationRecordV1,
        participant: DeactivationMigrationParticipantV1,
    ): DeactivationResult<Unit> {
        val evidenceBytes =
            current.migrationEvidence()
                ?: return DeactivationResult.Failure(DeactivationFailure.INVALID_STATE)
        val evidence = DeactivationMigrationEvidence.from(evidenceBytes)
        evidenceBytes.fill(0)
        val participantState = participant.state(current.transactionId, evidence)
        val alreadyFinalized =
            participantState == DeactivationMigrationState.ABSENT &&
                participant.isFinalized(current.transactionId)
        if (
            participantState != DeactivationMigrationState.COMMITTED &&
            !alreadyFinalized
        ) {
            return DeactivationResult.Failure(DeactivationFailure.MIGRATION_FAILED)
        }

        if (!activationStore.clear()) {
            return DeactivationResult.Failure(DeactivationFailure.CLEANUP_FAILED)
        }
        if (!privacyStore.clear()) {
            return DeactivationResult.Failure(DeactivationFailure.CLEANUP_FAILED)
        }
        if (!SecureContentCryptoSessionRuntimeV1.deactivateAfterReverseMigration(applicationContext)) {
            return DeactivationResult.Failure(DeactivationFailure.CLEANUP_FAILED)
        }
        if (normalWrapper.deleteActiveKeyAfterDeactivation() !is NormalAppMasterKeyResult.Success) {
            return DeactivationResult.Failure(DeactivationFailure.CLEANUP_FAILED)
        }
        if (!alreadyFinalized && !participant.finalizeCommitted(current.transactionId)) {
            return DeactivationResult.Failure(DeactivationFailure.CLEANUP_FAILED)
        }
        if (!store.clear()) {
            return DeactivationResult.Failure(DeactivationFailure.CLEANUP_FAILED)
        }
        return DeactivationResult.Success(Unit)
    }
}
