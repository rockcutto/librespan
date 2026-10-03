// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build

enum class SecureContentCryptoSessionStateV1 {
    INACTIVE,
    ACTIVATION_RECOVERY_REQUIRED,
    LOCKED,
    ACTIVE,
    RECOVERY_REQUIRED,
    CRYPTO_ERASED,
    CORRUPT,
}

data class SecureContentCryptoSessionSnapshotV1(
    val state: SecureContentCryptoSessionStateV1,
    val epoch: Long,
    val activationTransactionId: String?,
)

sealed class SecureContentNormalUnlockPreparationV1 {
    data object NotRequired : SecureContentNormalUnlockPreparationV1()
    data object AlreadyActive : SecureContentNormalUnlockPreparationV1()
    data object ActivationRecoveryRequired : SecureContentNormalUnlockPreparationV1()
    data object RecoveryRequired : SecureContentNormalUnlockPreparationV1()
    data object CryptoErased : SecureContentNormalUnlockPreparationV1()
    data object Corrupt : SecureContentNormalUnlockPreparationV1()
    data class Failure(val reason: NormalAppMasterKeyFailure) :
        SecureContentNormalUnlockPreparationV1()
    data class Pending(val operation: PendingSecureContentNormalUnlockV1) :
        SecureContentNormalUnlockPreparationV1()
}

class PendingSecureContentNormalUnlockV1 internal constructor(
    internal val transactionId: String,
    internal val operation: AndroidAuthBoundAppMasterKeyWrapperV1.UnwrapOperation,
) {
    fun cryptoObject(): BiometricPrompt.CryptoObject = operation.cryptoObject()

    override fun toString(): String = "PendingSecureContentNormalUnlockV1([REDACTED])"
}

internal object SecureContentCryptoSessionPolicyV1 {
    fun stateAfterNormalUnlockFailure(
        failure: NormalAppMasterKeyFailure,
    ): SecureContentCryptoSessionStateV1 =
        when (failure) {
            NormalAppMasterKeyFailure.KEY_INVALIDATED,
            NormalAppMasterKeyFailure.KEY_MISSING,
            NormalAppMasterKeyFailure.AUTHENTICATION_FAILED,
            NormalAppMasterKeyFailure.DEVICE_CREDENTIAL_UNAVAILABLE ->
                SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED
            NormalAppMasterKeyFailure.AUTHENTICATION_REQUIRED,
            NormalAppMasterKeyFailure.UNSUPPORTED_PLATFORM,
            NormalAppMasterKeyFailure.KEY_ALREADY_EXISTS,
            NormalAppMasterKeyFailure.CRYPTO_FAILURE ->
                SecureContentCryptoSessionStateV1.LOCKED
        }

    fun allowsBackgroundCrypto(
        state: SecureContentCryptoSessionStateV1,
        activationRead: ActivationStoreReadResult?,
        hasActiveMasterKey: Boolean,
    ): Boolean =
        when (state) {
            SecureContentCryptoSessionStateV1.INACTIVE ->
                activationRead == ActivationStoreReadResult.Absent
            SecureContentCryptoSessionStateV1.ACTIVE ->
                hasActiveMasterKey &&
                    activationRead is ActivationStoreReadResult.Present &&
                    activationRead.record.phase == InactiveDeviceActivationPhase.ACTIVE
            SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED,
            SecureContentCryptoSessionStateV1.LOCKED,
            SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED,
            SecureContentCryptoSessionStateV1.CRYPTO_ERASED,
            SecureContentCryptoSessionStateV1.CORRUPT -> false
        }

    fun requiresAuthentication(state: SecureContentCryptoSessionStateV1): Boolean =
        when (state) {
            SecureContentCryptoSessionStateV1.INACTIVE,
            SecureContentCryptoSessionStateV1.ACTIVE -> false
            SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED,
            SecureContentCryptoSessionStateV1.LOCKED,
            SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED,
            SecureContentCryptoSessionStateV1.CRYPTO_ERASED,
            SecureContentCryptoSessionStateV1.CORRUPT -> true
        }

    fun stateForDurablePhase(
        phase: InactiveDeviceActivationPhase?,
    ): SecureContentCryptoSessionStateV1 =
        when (phase) {
            null,
            InactiveDeviceActivationPhase.PREPARING,
            InactiveDeviceActivationPhase.NORMAL_WRAPPED,
            InactiveDeviceActivationPhase.WRAPPERS_VERIFIED ->
                SecureContentCryptoSessionStateV1.INACTIVE
            InactiveDeviceActivationPhase.MIGRATION_PREPARED,
            InactiveDeviceActivationPhase.MIGRATION_COMMITTED ->
                SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED
            InactiveDeviceActivationPhase.ACTIVE ->
                SecureContentCryptoSessionStateV1.LOCKED
            InactiveDeviceActivationPhase.ROLLBACK_REQUIRED ->
                SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED
        }

    fun stateAfterPrivacySuspend(
        current: SecureContentCryptoSessionStateV1,
        hasActiveRecord: Boolean,
    ): SecureContentCryptoSessionStateV1 =
        when (current) {
            SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED,
            SecureContentCryptoSessionStateV1.CRYPTO_ERASED,
            SecureContentCryptoSessionStateV1.CORRUPT,
            SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED -> current
            SecureContentCryptoSessionStateV1.ACTIVE,
            SecureContentCryptoSessionStateV1.LOCKED ->
                if (hasActiveRecord) {
                    SecureContentCryptoSessionStateV1.LOCKED
                } else {
                    SecureContentCryptoSessionStateV1.INACTIVE
                }
            SecureContentCryptoSessionStateV1.INACTIVE ->
                SecureContentCryptoSessionStateV1.INACTIVE
        }
}

/**
 * Sole process owner of the unwrapped App Master Key for the gated Secure Content hierarchy.
 *
 * Process restart intentionally reconstructs an ACTIVE durable profile as LOCKED. The App Master
 * Key is never persisted by this runtime and is installed only after a verified activation,
 * auth-bound normal unwrap, or a future verified recovery-wrapper repair.
 */
object SecureContentCryptoSessionRuntimeV1 {
    private val monitor = Any()

    @Volatile
    private var initialized = false

    private var activationStore: InactiveDeviceProtectionActivationStoreV1? = null
    private var activeMasterKey: AppMasterKey? = null
    private var state: SecureContentCryptoSessionStateV1 =
        SecureContentCryptoSessionStateV1.INACTIVE
    private var epoch: Long = 1L
    private var activationTransactionId: String? = null

    @JvmStatic
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(monitor) {
            if (initialized) return
            activationStore =
                InactiveDeviceProtectionActivationStoreV1(context.applicationContext)
            reconcileDurableStateLocked()
            initialized = true
        }
    }

    @JvmStatic
    fun snapshot(context: Context): SecureContentCryptoSessionSnapshotV1 {
        initialize(context)
        synchronized(monitor) {
            return SecureContentCryptoSessionSnapshotV1(
                state = state,
                epoch = epoch,
                activationTransactionId = activationTransactionId,
            )
        }
    }

    @JvmStatic
    fun requiresAuthentication(context: Context): Boolean =
        SecureContentCryptoSessionPolicyV1.requiresAuthentication(snapshot(context).state)

    @JvmStatic
    fun isBackgroundCryptoAvailable(context: Context): Boolean {
        initialize(context)
        synchronized(monitor) {
            return SecureContentCryptoSessionPolicyV1.allowsBackgroundCrypto(
                state = state,
                activationRead = activationStore?.read(),
                hasActiveMasterKey = activeMasterKey != null,
            )
        }
    }

    /**
     * Reconciles process state after activation crash recovery. This never restores the App Master
     * Key: a durable ACTIVE profile without a current runtime key becomes LOCKED.
     */
    @JvmStatic
    fun reconcileDurableState(context: Context) {
        initialize(context)
        synchronized(monitor) {
            reconcileDurableStateLocked()
        }
    }

    /**
     * Called only after the outer activation record has durably reached ACTIVE.
     */
    internal fun activateFromMigration(
        context: Context,
        transactionId: String,
        source: AppMasterKey,
    ): Boolean {
        initialize(context)
        val activated =
            synchronized(monitor) {
                val record = activeRecordLocked()
                    ?: return@synchronized false
                if (record.transactionId != transactionId) {
                    return@synchronized false
                }
                installMasterKeyLocked(source)
                activationTransactionId = transactionId
                state = SecureContentCryptoSessionStateV1.ACTIVE
                true
            }
        if (activated) {
            // Privacy runtime can call back into this crypto session. Keep the lock order one-way:
            // privacy -> crypto, never crypto -> privacy.
            InactiveDevicePrivacyRuntimeV1.onCryptoSessionActivated(
                context.applicationContext,
            )
        }
        return activated
    }

    internal fun activateFromRecovery(
        context: Context,
        transactionId: String,
        source: AppMasterKey,
    ): Boolean {
        initialize(context)
        val activated =
            synchronized(monitor) {
                val record = activeRecordLocked()
                    ?: return@synchronized false
                if (record.transactionId != transactionId) {
                    return@synchronized false
                }
                installMasterKeyLocked(source)
                activationTransactionId = transactionId
                state = SecureContentCryptoSessionStateV1.ACTIVE
                true
            }
        if (activated) {
            InactiveDevicePrivacyRuntimeV1.onCryptoSessionUnlocked(
                context.applicationContext,
            )
        }
        return activated
    }

    /**
     * Prepare an auth-bound normal unwrap. The returned CryptoObject must be supplied to
     * BiometricPrompt and the same pending operation must be completed after authentication.
     */
    @JvmStatic
    fun prepareNormalUnlock(context: Context): SecureContentNormalUnlockPreparationV1 {
        initialize(context)
        synchronized(monitor) {
            when (state) {
                SecureContentCryptoSessionStateV1.INACTIVE ->
                    return SecureContentNormalUnlockPreparationV1.NotRequired
                SecureContentCryptoSessionStateV1.ACTIVE ->
                    return SecureContentNormalUnlockPreparationV1.AlreadyActive
                SecureContentCryptoSessionStateV1.ACTIVATION_RECOVERY_REQUIRED ->
                    return SecureContentNormalUnlockPreparationV1.ActivationRecoveryRequired
                SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED ->
                    return SecureContentNormalUnlockPreparationV1.RecoveryRequired
                SecureContentCryptoSessionStateV1.CRYPTO_ERASED ->
                    return SecureContentNormalUnlockPreparationV1.CryptoErased
                SecureContentCryptoSessionStateV1.CORRUPT ->
                    return SecureContentNormalUnlockPreparationV1.Corrupt
                SecureContentCryptoSessionStateV1.LOCKED -> Unit
            }

            val record = activeRecordLocked()
                ?: run {
                    state = SecureContentCryptoSessionStateV1.CORRUPT
                    bumpEpochLocked()
                    return SecureContentNormalUnlockPreparationV1.Corrupt
                }
            val normalRecord = record.normalWrapper
                ?: run {
                    state = SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED
                    bumpEpochLocked()
                    return SecureContentNormalUnlockPreparationV1.RecoveryRequired
                }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return SecureContentNormalUnlockPreparationV1.Failure(
                    NormalAppMasterKeyFailure.UNSUPPORTED_PLATFORM,
                )
            }

            val wrapper = AndroidAuthBoundAppMasterKeyWrapperV1(context.applicationContext)
            return when (val prepared = wrapper.prepareUnwrap(normalRecord)) {
                is NormalAppMasterKeyResult.Success ->
                    SecureContentNormalUnlockPreparationV1.Pending(
                        PendingSecureContentNormalUnlockV1(
                            transactionId = record.transactionId,
                            operation = prepared.value,
                        ),
                    )
                is NormalAppMasterKeyResult.Failure -> {
                    if (SecureContentCryptoSessionPolicyV1.stateAfterNormalUnlockFailure(
                            prepared.reason,
                        ) == SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED
                    ) {
                        retireMasterKeyLocked()
                        activationTransactionId = record.transactionId
                        state = SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED
                        SecureContentNormalUnlockPreparationV1.RecoveryRequired
                    } else {
                        SecureContentNormalUnlockPreparationV1.Failure(prepared.reason)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun completeNormalUnlock(
        context: Context,
        pending: PendingSecureContentNormalUnlockV1,
        authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
    ): SecureContentCryptoSessionStateV1 {
        initialize(context)
        val result =
            synchronized(monitor) {
                if (state != SecureContentCryptoSessionStateV1.LOCKED) {
                    return@synchronized state
                }
                val record = activeRecordLocked()
                if (record == null || record.transactionId != pending.transactionId) {
                    retireMasterKeyLocked()
                    state = SecureContentCryptoSessionStateV1.CORRUPT
                    return@synchronized state
                }

                when (
                    val completed =
                        pending.operation.completeAuthenticated(authenticatedCryptoObject)
                ) {
                    is NormalAppMasterKeyResult.Success -> {
                        activeMasterKey?.close()
                        activeMasterKey = completed.value
                        activationTransactionId = record.transactionId
                        bumpEpochLocked()
                        state = SecureContentCryptoSessionStateV1.ACTIVE
                        state
                    }
                    is NormalAppMasterKeyResult.Failure -> {
                        retireMasterKeyLocked()
                        state = SecureContentCryptoSessionPolicyV1.stateAfterNormalUnlockFailure(
                            completed.reason,
                        )
                        state
                    }
                }
            }
        if (result == SecureContentCryptoSessionStateV1.ACTIVE) {
            // See activateFromMigration(): privacy owns the outer lock order.
            InactiveDevicePrivacyRuntimeV1.onCryptoSessionUnlocked(
                context.applicationContext,
            )
        }
        return result
    }

    /**
     * High-security inactivity transition. Ordinary UI_LOCKED does not call this.
     */
    @JvmStatic
    fun suspendForPrivacy(context: Context) {
        initialize(context)
        synchronized(monitor) {
            val previous = state
            retireMasterKeyLocked()
            val record = activeRecordLocked()
            activationTransactionId = record?.transactionId ?: activationTransactionId
            state =
                SecureContentCryptoSessionPolicyV1.stateAfterPrivacySuspend(
                    previous,
                    record != null,
                )
        }
    }

    internal fun suspendForHierarchyTransition(context: Context) {
        initialize(context)
        synchronized(monitor) {
            retireMasterKeyLocked()
            val record = activeRecordLocked()
            activationTransactionId = record?.transactionId ?: activationTransactionId
            if (
                state == SecureContentCryptoSessionStateV1.ACTIVE ||
                state == SecureContentCryptoSessionStateV1.LOCKED
            ) {
                state =
                    if (record != null) {
                        SecureContentCryptoSessionStateV1.LOCKED
                    } else {
                        SecureContentCryptoSessionStateV1.INACTIVE
                    }
            }
        }
    }

    /**
     * Completes the in-memory side of a successful reverse migration. The durable ACTIVE record
     * must already be gone; otherwise deactivation is refused.
     */
    internal fun deactivateAfterReverseMigration(context: Context): Boolean {
        initialize(context)
        synchronized(monitor) {
            if (activationStore?.read() !is ActivationStoreReadResult.Absent) {
                return false
            }
            retireMasterKeyLocked()
            activationTransactionId = null
            state = SecureContentCryptoSessionStateV1.INACTIVE
            return true
        }
    }

    @JvmStatic
    fun markRecoveryRequired(context: Context) {
        initialize(context)
        synchronized(monitor) {
            if (state == SecureContentCryptoSessionStateV1.CRYPTO_ERASED ||
                state == SecureContentCryptoSessionStateV1.CORRUPT
            ) {
                return
            }
            retireMasterKeyLocked()
            state = SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED
        }
    }

    @JvmStatic
    fun markCryptoErased(context: Context) {
        initialize(context)
        synchronized(monitor) {
            retireMasterKeyLocked()
            activationTransactionId = null
            state = SecureContentCryptoSessionStateV1.CRYPTO_ERASED
        }
    }

    internal fun activeEpoch(): Long? =
        synchronized(monitor) {
            if (state == SecureContentCryptoSessionStateV1.ACTIVE &&
                activeMasterKey != null
            ) {
                epoch
            } else {
                null
            }
        }

    internal fun isEpochActive(candidate: Long): Boolean =
        synchronized(monitor) {
            state == SecureContentCryptoSessionStateV1.ACTIVE &&
                activeMasterKey != null &&
                epoch == candidate
        }

    internal fun <T> withActiveMasterKey(block: (AppMasterKey) -> T): T? =
        synchronized(monitor) {
            if (state != SecureContentCryptoSessionStateV1.ACTIVE) {
                null
            } else {
                activeMasterKey?.let(block)
            }
        }

    private fun activeRecordLocked(): InactiveDeviceActivationRecordV1? =
        when (val read = activationStore?.read()) {
            is ActivationStoreReadResult.Present ->
                read.record.takeIf { it.phase == InactiveDeviceActivationPhase.ACTIVE }
            else -> null
        }

    private fun reconcileDurableStateLocked() {
        val read = activationStore?.read()

        if (state == SecureContentCryptoSessionStateV1.CRYPTO_ERASED ||
            state == SecureContentCryptoSessionStateV1.RECOVERY_REQUIRED ||
            state == SecureContentCryptoSessionStateV1.CORRUPT
        ) {
            return
        }

        if (read is ActivationStoreReadResult.Present &&
            read.record.phase == InactiveDeviceActivationPhase.ACTIVE &&
            state == SecureContentCryptoSessionStateV1.ACTIVE &&
            activeMasterKey != null &&
            activationTransactionId == read.record.transactionId
        ) {
            return
        }

        retireMasterKeyLocked()
        when (read) {
            null,
            ActivationStoreReadResult.Absent -> {
                activationTransactionId = null
                state = SecureContentCryptoSessionStateV1.INACTIVE
            }
            ActivationStoreReadResult.Corrupt -> {
                activationTransactionId = null
                state = SecureContentCryptoSessionStateV1.CORRUPT
            }
            is ActivationStoreReadResult.Present -> {
                activationTransactionId = read.record.transactionId
                state =
                    SecureContentCryptoSessionPolicyV1.stateForDurablePhase(
                        read.record.phase,
                    )
            }
        }
    }

    private fun installMasterKeyLocked(source: AppMasterKey) {
        activeMasterKey?.close()
        activeMasterKey = source.useCopy { AppMasterKey.copyOf(it) }
        bumpEpochLocked()
    }

    private fun retireMasterKeyLocked() {
        val hadKey = activeMasterKey != null
        activeMasterKey?.close()
        activeMasterKey = null
        if (hadKey) {
            bumpEpochLocked()
        }
    }

    private fun bumpEpochLocked() {
        epoch =
            if (epoch == Long.MAX_VALUE) {
                1L
            } else {
                epoch + 1L
            }
    }
}
