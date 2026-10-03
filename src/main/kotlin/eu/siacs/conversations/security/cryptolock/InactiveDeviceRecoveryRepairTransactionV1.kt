// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import androidx.annotation.RequiresApi
import eu.siacs.conversations.security.recovery.InvalidRecoveryPhraseException
import eu.siacs.conversations.security.recovery.RecoveryAppMasterKeyWrapperV1
import eu.siacs.conversations.security.recovery.RecoveryPhraseCodecs
import eu.siacs.conversations.security.recovery.RecoveryUnwrapException

internal enum class RecoveryRepairFailureV1 {
    PROFILE_NOT_ACTIVE,
    INVALID_PHRASE,
    RECOVERY_UNWRAP_FAILED,
    KEY_REPLACEMENT_FAILED,
    AUTHENTICATION_FAILED,
    WRAPPER_VERIFICATION_FAILED,
    PERSISTENCE_FAILED,
}

internal sealed class RecoveryRepairResultV1<out T> {
    data class Success<T>(val value: T) : RecoveryRepairResultV1<T>()
    data class Failure(
        val reason: RecoveryRepairFailureV1,
    ) : RecoveryRepairResultV1<Nothing>()
}

internal class RecoveryRepairSessionV1(
    val transactionId: String,
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

internal data class PendingRecoveryRepairWrapV1(
    val session: RecoveryRepairSessionV1,
    val operation: AndroidAuthBoundAppMasterKeyWrapperV1.WrapOperation,
)

internal data class PendingRecoveryRepairVerificationV1(
    val session: RecoveryRepairSessionV1,
    val normalWrapper: NormalWrappedAppMasterKeyRecordV1,
    val operation: AndroidAuthBoundAppMasterKeyWrapperV1.UnwrapOperation,
)

/** The durable normal wrapper changes only after the recovered AMK has been verified. */
internal fun verifiedRecoveryRepairRecordV1(
    current: InactiveDeviceActivationRecordV1,
    normalWrapper: NormalWrappedAppMasterKeyRecordV1,
    original: AppMasterKey,
    unwrapped: AppMasterKey,
    now: Long,
): InactiveDeviceActivationRecordV1? {
    if (current.phase != InactiveDeviceActivationPhase.ACTIVE ||
        !original.matches(unwrapped)
    ) return null
    val evidence = current.migrationEvidence() ?: return null
    return try {
        InactiveDeviceActivationRecordV1(
            transactionId = current.transactionId,
            phase = InactiveDeviceActivationPhase.ACTIVE,
            recoveryWrapper = current.recoveryWrapper,
            normalWrapper = normalWrapper,
            migrationEvidence = evidence,
            createdAt = current.createdAt,
            updatedAt = now.coerceAtLeast(current.updatedAt),
            activatedAt = current.activatedAt,
        )
    } finally {
        evidence.fill(0)
    }
}

@RequiresApi(Build.VERSION_CODES.R)
internal class InactiveDeviceRecoveryRepairTransactionV1(
    context: Context,
    private val store: InactiveDeviceProtectionActivationStoreV1 =
        InactiveDeviceProtectionActivationStoreV1(context),
    private val normalWrapper: AndroidAuthBoundAppMasterKeyWrapperV1 =
        AndroidAuthBoundAppMasterKeyWrapperV1(context),
) {
    private val applicationContext = context.applicationContext

    @Synchronized
    fun begin(phrase: CharSequence): RecoveryRepairResultV1<PendingRecoveryRepairWrapV1> {
        val active =
            (store.read() as? ActivationStoreReadResult.Present)?.record
                ?.takeIf { it.phase == InactiveDeviceActivationPhase.ACTIVE }
                ?: return RecoveryRepairResultV1.Failure(
                    RecoveryRepairFailureV1.PROFILE_NOT_ACTIVE,
                )


        val secret =
            try {
                RecoveryPhraseCodecs.decode(
                    phrase,
                    active.recoveryWrapper.recoveryPhraseFormat(),
                )
            } catch (_: InvalidRecoveryPhraseException) {
                return RecoveryRepairResultV1.Failure(
                    RecoveryRepairFailureV1.INVALID_PHRASE,
                )
            }

        val masterKey =
            try {
                RecoveryAppMasterKeyWrapperV1.unwrap(secret, active.recoveryWrapper)
            } catch (_: RecoveryUnwrapException) {
                return RecoveryRepairResultV1.Failure(
                    RecoveryRepairFailureV1.RECOVERY_UNWRAP_FAILED,
                )
            } finally {
                secret.close()
            }

        when (normalWrapper.replaceKeyForRecovery()) {
            is NormalAppMasterKeyResult.Failure -> {
                masterKey.close()
                return RecoveryRepairResultV1.Failure(
                    RecoveryRepairFailureV1.KEY_REPLACEMENT_FAILED,
                )
            }
            is NormalAppMasterKeyResult.Success -> Unit
        }

        val operation =
            when (val prepared = normalWrapper.prepareWrap()) {
                is NormalAppMasterKeyResult.Failure -> {
                    masterKey.close()
                    return RecoveryRepairResultV1.Failure(
                        RecoveryRepairFailureV1.KEY_REPLACEMENT_FAILED,
                    )
                }
                is NormalAppMasterKeyResult.Success -> prepared.value
            }
        return RecoveryRepairResultV1.Success(
            PendingRecoveryRepairWrapV1(
                RecoveryRepairSessionV1(active.transactionId, masterKey),
                operation,
            ),
        )
    }

    @Synchronized
    fun completeWrap(
        pending: PendingRecoveryRepairWrapV1,
        authenticatedCryptoObject: android.hardware.biometrics.BiometricPrompt.CryptoObject?,
    ): RecoveryRepairResultV1<PendingRecoveryRepairVerificationV1> {
        pending.session.requireOpen()
        val record =
            (store.read() as? ActivationStoreReadResult.Present)?.record
                ?.takeIf {
                    it.phase == InactiveDeviceActivationPhase.ACTIVE &&
                        it.transactionId == pending.session.transactionId
                }
                ?: return RecoveryRepairResultV1.Failure(
                    RecoveryRepairFailureV1.PROFILE_NOT_ACTIVE,
                )
        val wrapped =
            when (
                val result =
                    pending.operation.completeAuthenticated(
                        pending.session.appMasterKey,
                        authenticatedCryptoObject,
                    )
            ) {
                is NormalAppMasterKeyResult.Failure ->
                    return RecoveryRepairResultV1.Failure(
                        RecoveryRepairFailureV1.AUTHENTICATION_FAILED,
                    )
                is NormalAppMasterKeyResult.Success -> result.value
            }
        val verify =
            when (val prepared = normalWrapper.prepareUnwrap(wrapped)) {
                is NormalAppMasterKeyResult.Failure ->
                    return RecoveryRepairResultV1.Failure(
                        RecoveryRepairFailureV1.WRAPPER_VERIFICATION_FAILED,
                    )
                is NormalAppMasterKeyResult.Success -> prepared.value
            }
        return RecoveryRepairResultV1.Success(
            PendingRecoveryRepairVerificationV1(
                pending.session,
                wrapped,
                verify,
            ),
        )
    }

    @Synchronized
    fun completeVerification(
        pending: PendingRecoveryRepairVerificationV1,
        authenticatedCryptoObject: android.hardware.biometrics.BiometricPrompt.CryptoObject?,
    ): RecoveryRepairResultV1<Unit> {
        pending.session.requireOpen()
        val current =
            (store.read() as? ActivationStoreReadResult.Present)?.record
                ?.takeIf {
                    it.phase == InactiveDeviceActivationPhase.ACTIVE &&
                        it.transactionId == pending.session.transactionId
                }
                ?: return RecoveryRepairResultV1.Failure(
                    RecoveryRepairFailureV1.PROFILE_NOT_ACTIVE,
                )

        val recovered =
            when (
                val result =
                    pending.operation.completeAuthenticated(authenticatedCryptoObject)
            ) {
                is NormalAppMasterKeyResult.Failure ->
                    return RecoveryRepairResultV1.Failure(
                        RecoveryRepairFailureV1.WRAPPER_VERIFICATION_FAILED,
                    )
                is NormalAppMasterKeyResult.Success -> result.value
            }
        val repaired = recovered.use {
            verifiedRecoveryRepairRecordV1(
                current, pending.normalWrapper, pending.session.appMasterKey, it,
                System.currentTimeMillis(),
            )
        } ?: return RecoveryRepairResultV1.Failure(
            RecoveryRepairFailureV1.WRAPPER_VERIFICATION_FAILED,
        )
        if (!store.write(repaired)) {
            return RecoveryRepairResultV1.Failure(
                RecoveryRepairFailureV1.PERSISTENCE_FAILED,
            )
        }
        val activated =
            SecureContentCryptoSessionRuntimeV1.activateFromRecovery(
                applicationContext,
                repaired.transactionId,
                pending.session.appMasterKey,
            )
        pending.session.close()
        return if (activated) {
            RecoveryRepairResultV1.Success(Unit)
        } else {
            RecoveryRepairResultV1.Failure(RecoveryRepairFailureV1.PERSISTENCE_FAILED)
        }
    }

}
