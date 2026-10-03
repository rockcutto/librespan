// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import eu.siacs.conversations.security.recovery.RecoveryAppMasterKeyWrapperV1
import eu.siacs.conversations.security.recovery.RecoveryPhraseFormat
import eu.siacs.conversations.security.recovery.RecoveryPhraseFormats
import eu.siacs.conversations.security.recovery.RecoverySecret
import java.security.MessageDigest
import java.util.UUID

enum class ActivationFailure {
    UNSUPPORTED_PLATFORM,
    DEVICE_CREDENTIAL_UNAVAILABLE,
    EXISTING_TRANSACTION,
    JOURNAL_CORRUPT,
    NORMAL_KEY_CONFLICT,
    NORMAL_KEY_UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    RECOVERY_WRAP_FAILED,
    RECOVERY_SELF_CHECK_FAILED,
    NORMAL_WRAP_FAILED,
    NORMAL_VERIFY_FAILED,
    WRAPPER_VERIFICATION_FAILED,
    MIGRATION_FAILED,
    PERSISTENCE_FAILED,
    INVALID_STATE,
    ROLLBACK_FAILED,
}

sealed class ActivationResult<out T> {
    data class Success<T>(val value: T) : ActivationResult<T>()

    data class Failure(
        val reason: ActivationFailure,
        val diagnosticCode: String? = null,
    ) : ActivationResult<Nothing>()
}

enum class ActivationMigrationState {
    ABSENT,
    PREPARED,
    COMMITTED,
    ROLLED_BACK,
    CORRUPT,
}

data class ActivationMigrationEvidence internal constructor(
    private val bindingValue: ByteArray,
) {
    init {
        require(bindingValue.isNotEmpty())
        require(bindingValue.size <= 512)
    }

    fun binding(): ByteArray = bindingValue.copyOf()

    override fun toString(): String = "ActivationMigrationEvidence([REDACTED])"

    companion object {
        internal fun from(binding: ByteArray): ActivationMigrationEvidence =
            ActivationMigrationEvidence(binding.copyOf())
    }
}

/**
 * Future account-key hierarchy migration implementation plugs into this transaction boundary.
 *
 * prepareAndVerify must durably create and verify account-material candidates for transactionId
 * without changing the currently readable hierarchy. commit must be idempotent/recoverable.
 */
internal interface ActivationMigrationParticipantV1 {
    fun prepareAndVerify(
        transactionId: String,
        appMasterKey: AppMasterKey,
    ): ActivationMigrationEvidence?

    fun findPrepared(transactionId: String): ActivationMigrationEvidence?

    fun state(
        transactionId: String,
        evidence: ActivationMigrationEvidence,
    ): ActivationMigrationState

    fun commit(
        transactionId: String,
        evidence: ActivationMigrationEvidence,
    ): Boolean

    fun rollback(
        transactionId: String,
        evidence: ActivationMigrationEvidence?,
    ): Boolean

    /** Non-secret device-test diagnostic for the most recent migration operation. */
    fun diagnosticCode(): String? = null
}

/**
 * Authenticated setup session. The App Master Key exists only in this bounded runtime object.
 */
internal class ActivationSetupSessionV1(
    val transactionId: String,
    internal val appMasterKey: AppMasterKey,
) : AutoCloseable {
    private var closed = false

    internal fun requireOpen() {
        check(!closed) { "Activation setup session is closed" }
    }

    override fun close() {
        if (!closed) {
            appMasterKey.close()
            closed = true
        }
    }

    override fun toString(): String = "ActivationSetupSessionV1([REDACTED])"
}

internal data class PendingNormalWrapV1(
    val session: ActivationSetupSessionV1,
    val operation: AndroidAuthBoundAppMasterKeyWrapperV1.WrapOperation,
)

internal data class PendingNormalVerificationV1(
    val session: ActivationSetupSessionV1,
    val operation: AndroidAuthBoundAppMasterKeyWrapperV1.UnwrapOperation,
)

@RequiresApi(Build.VERSION_CODES.R)
internal class InactiveDeviceProtectionActivationTransactionV1(
    context: Context,
    private val store: InactiveDeviceProtectionActivationStoreV1 =
        InactiveDeviceProtectionActivationStoreV1(context),
    private val normalWrapper: AndroidAuthBoundAppMasterKeyWrapperV1 =
        AndroidAuthBoundAppMasterKeyWrapperV1(context),
) {
    private val applicationContext = context.applicationContext

    @Synchronized
    fun beginAfterRecoveryPhraseVerified(
        recoverySecret: RecoverySecret,
        recoveryPhraseFormat: RecoveryPhraseFormat = RecoveryPhraseFormats.BIP39_EN_V1,
    ): ActivationResult<PendingNormalWrapV1> {
        normalWrapper.supportStatus()?.let {
            return ActivationResult.Failure(mapNormalFailure(it))
        }
        when (store.read()) {
            ActivationStoreReadResult.Absent -> Unit
            ActivationStoreReadResult.Corrupt ->
                return ActivationResult.Failure(ActivationFailure.JOURNAL_CORRUPT)
            is ActivationStoreReadResult.Present ->
                return ActivationResult.Failure(ActivationFailure.EXISTING_TRANSACTION)
        }

        val appMasterKey = AppMasterKey.generate()
        val transactionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val recoveryRecord =
            try {
                RecoveryAppMasterKeyWrapperV1.wrap(
                    recoverySecret,
                    appMasterKey,
                    phraseFormat = recoveryPhraseFormat,
                )
            } catch (_: Exception) {
                appMasterKey.close()
                return ActivationResult.Failure(
                    ActivationFailure.RECOVERY_WRAP_FAILED,
                )
            }

        val recoveryVerified =
            try {
                RecoveryAppMasterKeyWrapperV1.unwrap(recoverySecret, recoveryRecord).use {
                    appMasterKey.matches(it)
                }
            } catch (_: Exception) {
                false
            }
        if (!recoveryVerified) {
            appMasterKey.close()
            return ActivationResult.Failure(ActivationFailure.RECOVERY_SELF_CHECK_FAILED)
        }

        val initial =
            InactiveDeviceActivationRecordV1(
                transactionId = transactionId,
                phase = InactiveDeviceActivationPhase.PREPARING,
                recoveryWrapper = recoveryRecord,
                normalWrapper = null,
                migrationEvidence = null,
                createdAt = now,
                updatedAt = now,
                activatedAt = null,
            )
        if (!store.write(initial)) {
            appMasterKey.close()
            return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
        }

        when (val create = normalWrapper.createFreshSetupKey()) {
            is NormalAppMasterKeyResult.Failure -> {
                appMasterKey.close()
                return ActivationResult.Failure(mapNormalFailure(create.reason))
            }
            is NormalAppMasterKeyResult.Success -> Unit
        }

        val operation =
            when (val prepared = normalWrapper.prepareWrap()) {
                is NormalAppMasterKeyResult.Failure -> {
                    appMasterKey.close()
                    return ActivationResult.Failure(mapNormalFailure(prepared.reason))
                }
                is NormalAppMasterKeyResult.Success -> prepared.value
            }

        return ActivationResult.Success(
            PendingNormalWrapV1(
                session = ActivationSetupSessionV1(transactionId, appMasterKey),
                operation = operation,
            ),
        )
    }

    @Synchronized
    fun completeNormalWrap(
        pending: PendingNormalWrapV1,
        authenticatedCryptoObject: android.hardware.biometrics.BiometricPrompt.CryptoObject?,
    ): ActivationResult<PendingNormalVerificationV1> {
        pending.session.requireOpen()
        val current = requireRecord(pending.session.transactionId)
            ?: return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
        if (current.phase != InactiveDeviceActivationPhase.PREPARING) {
            return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
        }

        val normalRecord =
            when (
                val completed =
                    pending.operation.completeAuthenticated(
                        pending.session.appMasterKey,
                        authenticatedCryptoObject,
                    )
            ) {
                is NormalAppMasterKeyResult.Failure ->
                    return ActivationResult.Failure(
                        mapNormalWrapFailure(completed.reason),
                        completed.diagnosticCode,
                    )
                is NormalAppMasterKeyResult.Success -> completed.value
            }

        val normalWrapped =
            transition(
                current = current,
                nextPhase = InactiveDeviceActivationPhase.NORMAL_WRAPPED,
                normalWrapper = normalRecord,
                migrationEvidence = null,
                activatedAt = null,
            ) ?: return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)

        val verify =
            when (val prepared = normalWrapper.prepareUnwrap(normalRecord)) {
                is NormalAppMasterKeyResult.Failure ->
                    return ActivationResult.Failure(mapNormalFailure(prepared.reason))
                is NormalAppMasterKeyResult.Success -> prepared.value
            }

        return ActivationResult.Success(
            PendingNormalVerificationV1(
                session = pending.session,
                operation = verify,
            ),
        )
    }

    @Synchronized
    fun completeNormalVerification(
        pending: PendingNormalVerificationV1,
        authenticatedCryptoObject: android.hardware.biometrics.BiometricPrompt.CryptoObject?,
    ): ActivationResult<ActivationSetupSessionV1> {
        pending.session.requireOpen()
        val current = requireRecord(pending.session.transactionId)
            ?: return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
        if (current.phase != InactiveDeviceActivationPhase.NORMAL_WRAPPED) {
            return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
        }

        val recovered =
            when (
                val completed =
                    pending.operation.completeAuthenticated(authenticatedCryptoObject)
            ) {
                is NormalAppMasterKeyResult.Failure ->
                    return ActivationResult.Failure(
                        mapNormalVerifyFailure(completed.reason),
                        completed.diagnosticCode,
                    )
                is NormalAppMasterKeyResult.Success -> completed.value
            }
        val verified =
            recovered.use {
                pending.session.appMasterKey.matches(it)
            }
        if (!verified) {
            markRollbackRequired(current)
            return ActivationResult.Failure(ActivationFailure.NORMAL_VERIFY_FAILED)
        }

        if (transition(
                current = current,
                nextPhase = InactiveDeviceActivationPhase.WRAPPERS_VERIFIED,
                normalWrapper = current.normalWrapper,
                migrationEvidence = null,
                activatedAt = null,
            ) == null
        ) {
            return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
        }

        return ActivationResult.Success(pending.session)
    }

    /**
     * Performs the migration barrier and commits ACTIVE only after the participant reports durable,
     * verified account-material migration.
     */
    @Synchronized
    fun migrateAndActivate(
        session: ActivationSetupSessionV1,
        participant: ActivationMigrationParticipantV1,
    ): ActivationResult<Unit> {
        session.requireOpen()
        var current = requireRecord(session.transactionId)
            ?: return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
        if (current.phase != InactiveDeviceActivationPhase.WRAPPERS_VERIFIED) {
            return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
        }

        val evidence =
            participant.prepareAndVerify(session.transactionId, session.appMasterKey)
                ?: run {
                    val diagnostic =
                        participant.diagnosticCode() ?: "MIGRATION_PREPARE_FAILED"
                    if (!rollbackIncompleteActivation(current, participant)) {
                        markRollbackRequired(current)
                    }
                    return ActivationResult.Failure(
                        ActivationFailure.MIGRATION_FAILED,
                        diagnostic,
                    )
                }
        val evidenceBytes = evidence.binding()
        current =
            transition(
                current = current,
                nextPhase = InactiveDeviceActivationPhase.MIGRATION_PREPARED,
                normalWrapper = current.normalWrapper,
                migrationEvidence = evidenceBytes,
                activatedAt = null,
            ) ?: run {
                evidenceBytes.fill(0)
                participant.rollback(session.transactionId, evidence)
                return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
            }
        evidenceBytes.fill(0)

        if (!participant.commit(session.transactionId, evidence)) {
            val diagnostic =
                participant.diagnosticCode() ?: "MIGRATION_COMMIT_FAILED"
            if (!rollbackIncompleteActivation(current, participant)) {
                markRollbackRequired(current)
            }
            return ActivationResult.Failure(
                ActivationFailure.MIGRATION_FAILED,
                diagnostic,
            )
        }

        current =
            transition(
                current = current,
                nextPhase = InactiveDeviceActivationPhase.MIGRATION_COMMITTED,
                normalWrapper = current.normalWrapper,
                migrationEvidence = evidence.binding(),
                activatedAt = null,
            ) ?: return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)

        val activated =
            transition(
                current = current,
                nextPhase = InactiveDeviceActivationPhase.ACTIVE,
                normalWrapper = current.normalWrapper,
                migrationEvidence = current.migrationEvidence(),
                activatedAt = System.currentTimeMillis(),
            ) ?: return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)

        val sessionActivated =
            activated.phase == InactiveDeviceActivationPhase.ACTIVE &&
                SecureContentCryptoSessionRuntimeV1.activateFromMigration(
                    applicationContext,
                    activated.transactionId,
                    session.appMasterKey,
                )
        session.close()
        return if (sessionActivated) {
            ActivationResult.Success(Unit)
        } else {
            ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
        }
    }

    /**
     * Crash recovery for the migration/commit boundary. Wrapper setup itself is intentionally left
     * resumable rather than silently discarded; the user may still possess the 12-word phrase.
     */
    @Synchronized
    fun recoverMigrationBoundary(
        participant: ActivationMigrationParticipantV1,
    ): ActivationResult<InactiveDeviceActivationPhase?> {
        val current =
            when (val read = store.read()) {
                ActivationStoreReadResult.Absent ->
                    return ActivationResult.Success(null)
                ActivationStoreReadResult.Corrupt ->
                    return ActivationResult.Failure(ActivationFailure.JOURNAL_CORRUPT)
                is ActivationStoreReadResult.Present -> read.record
            }

        when (current.phase) {
            InactiveDeviceActivationPhase.WRAPPERS_VERIFIED -> {
                val found = participant.findPrepared(current.transactionId)
                if (found == null) {
                    // The outer transaction has not crossed the migration boundary. A leftover
                    // participant PREPARED state is therefore non-authoritative and must not keep
                    // normal ROOT_V1 writes blocked forever after a process restart. Rollback is
                    // safe here: prepared candidates have never replaced the active account
                    // envelopes.
                    if (!participant.rollback(current.transactionId, null)) {
                        return ActivationResult.Failure(ActivationFailure.ROLLBACK_FAILED)
                    }
                    return ActivationResult.Success(current.phase)
                }
                val bytes = found.binding()
                val prepared =
                    transition(
                        current,
                        InactiveDeviceActivationPhase.MIGRATION_PREPARED,
                        current.normalWrapper,
                        bytes,
                        null,
                    ) ?: run {
                        bytes.fill(0)
                        return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
                    }
                bytes.fill(0)
                return reconcilePrepared(prepared, found, participant)
            }
            InactiveDeviceActivationPhase.MIGRATION_PREPARED -> {
                val bytes = current.migrationEvidence()
                    ?: return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
                val evidence = ActivationMigrationEvidence.from(bytes)
                bytes.fill(0)
                return reconcilePrepared(current, evidence, participant)
            }
            InactiveDeviceActivationPhase.MIGRATION_COMMITTED -> {
                val active =
                    transition(
                        current,
                        InactiveDeviceActivationPhase.ACTIVE,
                        current.normalWrapper,
                        current.migrationEvidence(),
                        System.currentTimeMillis(),
                    ) ?: return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
                return ActivationResult.Success(active.phase)
            }
            InactiveDeviceActivationPhase.ROLLBACK_REQUIRED -> {
                return if (rollbackIncompleteActivation(current, participant)) {
                    ActivationResult.Success(null)
                } else {
                    ActivationResult.Failure(ActivationFailure.ROLLBACK_FAILED)
                }
            }
            else -> return ActivationResult.Success(current.phase)
        }
    }

    @Synchronized
    fun abortBeforeMigration(): ActivationResult<Unit> {
        val current =
            when (val read = store.read()) {
                ActivationStoreReadResult.Absent ->
                    return ActivationResult.Success(Unit)
                ActivationStoreReadResult.Corrupt ->
                    return ActivationResult.Failure(ActivationFailure.JOURNAL_CORRUPT)
                is ActivationStoreReadResult.Present -> read.record
            }
        if (current.phase !in
            setOf(
                InactiveDeviceActivationPhase.PREPARING,
                InactiveDeviceActivationPhase.NORMAL_WRAPPED,
                InactiveDeviceActivationPhase.WRAPPERS_VERIFIED,
            )
        ) {
            return ActivationResult.Failure(ActivationFailure.INVALID_STATE)
        }
        return when (normalWrapper.deleteSetupKeyBeforeActivation()) {
            is NormalAppMasterKeyResult.Success ->
                if (store.clear()) ActivationResult.Success(Unit)
                else ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
            is NormalAppMasterKeyResult.Failure -> {
                markRollbackRequired(current)
                ActivationResult.Failure(ActivationFailure.ROLLBACK_FAILED)
            }
        }
    }

    private fun reconcilePrepared(
        current: InactiveDeviceActivationRecordV1,
        evidence: ActivationMigrationEvidence,
        participant: ActivationMigrationParticipantV1,
    ): ActivationResult<InactiveDeviceActivationPhase?> {
        return when (participant.state(current.transactionId, evidence)) {
            ActivationMigrationState.PREPARED -> {
                if (!participant.commit(current.transactionId, evidence)) {
                    return ActivationResult.Failure(ActivationFailure.MIGRATION_FAILED)
                }
                val committed =
                    transition(
                        current,
                        InactiveDeviceActivationPhase.MIGRATION_COMMITTED,
                        current.normalWrapper,
                        evidence.binding(),
                        null,
                    ) ?: return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
                val active =
                    transition(
                        committed,
                        InactiveDeviceActivationPhase.ACTIVE,
                        committed.normalWrapper,
                        committed.migrationEvidence(),
                        System.currentTimeMillis(),
                    ) ?: return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
                ActivationResult.Success(active.phase)
            }
            ActivationMigrationState.COMMITTED -> {
                val committed =
                    if (current.phase == InactiveDeviceActivationPhase.MIGRATION_COMMITTED) {
                        current
                    } else {
                        transition(
                            current,
                            InactiveDeviceActivationPhase.MIGRATION_COMMITTED,
                            current.normalWrapper,
                            evidence.binding(),
                            null,
                        ) ?: return ActivationResult.Failure(
                            ActivationFailure.PERSISTENCE_FAILED,
                        )
                    }
                val active =
                    transition(
                        committed,
                        InactiveDeviceActivationPhase.ACTIVE,
                        committed.normalWrapper,
                        committed.migrationEvidence(),
                        System.currentTimeMillis(),
                    ) ?: return ActivationResult.Failure(ActivationFailure.PERSISTENCE_FAILED)
                ActivationResult.Success(active.phase)
            }
            ActivationMigrationState.ABSENT,
            ActivationMigrationState.ROLLED_BACK,
            ActivationMigrationState.CORRUPT -> {
                markRollbackRequired(current)
                ActivationResult.Failure(ActivationFailure.MIGRATION_FAILED)
            }
        }
    }

    private fun requireRecord(transactionId: String): InactiveDeviceActivationRecordV1? =
        when (val read = store.read()) {
            is ActivationStoreReadResult.Present ->
                read.record.takeIf { it.transactionId == transactionId }
            else -> null
        }

    private fun transition(
        current: InactiveDeviceActivationRecordV1,
        nextPhase: InactiveDeviceActivationPhase,
        normalWrapper: NormalWrappedAppMasterKeyRecordV1?,
        migrationEvidence: ByteArray?,
        activatedAt: Long?,
    ): InactiveDeviceActivationRecordV1? {
        if (!InactiveDeviceActivationTransitionsV1.permits(current.phase, nextPhase)) {
            migrationEvidence?.fill(0)
            return null
        }
        val next =
            InactiveDeviceActivationRecordV1(
                transactionId = current.transactionId,
                phase = nextPhase,
                recoveryWrapper = current.recoveryWrapper,
                normalWrapper = normalWrapper,
                migrationEvidence = migrationEvidence,
                createdAt = current.createdAt,
                updatedAt = System.currentTimeMillis(),
                activatedAt = activatedAt,
            )
        val written = store.write(next)
        migrationEvidence?.fill(0)
        return if (written) next else null
    }

    /**
     * Converges an activation that has not safely crossed into the gated hierarchy back to OFF.
     *
     * The participant is authoritative: rollback returns false once GATED_V1 owns account
     * material, so this helper never deletes the normal wrapper or activation journal after the
     * authority switch.
     */
    private fun rollbackIncompleteActivation(
        current: InactiveDeviceActivationRecordV1,
        participant: ActivationMigrationParticipantV1,
    ): Boolean {
        if (!participant.rollback(current.transactionId, null)) {
            return false
        }
        when (normalWrapper.deleteSetupKeyBeforeActivation()) {
            is NormalAppMasterKeyResult.Failure -> return false
            is NormalAppMasterKeyResult.Success -> Unit
        }
        return store.clear()
    }

    private fun markRollbackRequired(current: InactiveDeviceActivationRecordV1) {
        if (InactiveDeviceActivationTransitionsV1.permits(
                current.phase,
                InactiveDeviceActivationPhase.ROLLBACK_REQUIRED,
            )
        ) {
            transition(
                current,
                InactiveDeviceActivationPhase.ROLLBACK_REQUIRED,
                current.normalWrapper,
                current.migrationEvidence(),
                null,
            )
        }
    }

    private fun mapNormalWrapFailure(failure: NormalAppMasterKeyFailure): ActivationFailure =
        when (failure) {
            NormalAppMasterKeyFailure.CRYPTO_FAILURE,
            NormalAppMasterKeyFailure.AUTHENTICATION_FAILED ->
                ActivationFailure.NORMAL_WRAP_FAILED
            else -> mapNormalFailure(failure)
        }

    private fun mapNormalVerifyFailure(failure: NormalAppMasterKeyFailure): ActivationFailure =
        when (failure) {
            NormalAppMasterKeyFailure.CRYPTO_FAILURE,
            NormalAppMasterKeyFailure.AUTHENTICATION_FAILED ->
                ActivationFailure.NORMAL_VERIFY_FAILED
            else -> mapNormalFailure(failure)
        }

    private fun mapNormalFailure(failure: NormalAppMasterKeyFailure): ActivationFailure =
        when (failure) {
            NormalAppMasterKeyFailure.UNSUPPORTED_PLATFORM ->
                ActivationFailure.UNSUPPORTED_PLATFORM
            NormalAppMasterKeyFailure.DEVICE_CREDENTIAL_UNAVAILABLE ->
                ActivationFailure.DEVICE_CREDENTIAL_UNAVAILABLE
            NormalAppMasterKeyFailure.KEY_ALREADY_EXISTS ->
                ActivationFailure.NORMAL_KEY_CONFLICT
            NormalAppMasterKeyFailure.KEY_MISSING,
            NormalAppMasterKeyFailure.KEY_INVALIDATED ->
                ActivationFailure.NORMAL_KEY_UNAVAILABLE
            NormalAppMasterKeyFailure.AUTHENTICATION_REQUIRED ->
                ActivationFailure.AUTHENTICATION_REQUIRED
            NormalAppMasterKeyFailure.AUTHENTICATION_FAILED,
            NormalAppMasterKeyFailure.CRYPTO_FAILURE ->
                ActivationFailure.WRAPPER_VERIFICATION_FAILED
        }
}
