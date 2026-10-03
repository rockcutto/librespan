// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.os.Build
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.storage.secure.PersistentSecureContentAccountKeyMigrationParticipantV1

/**
 * Recovers activation crash windows only after the application database is available.
 *
 * Application.onCreate intentionally does not open SQLCipher for this. XmppConnectionService calls
 * this after DatabaseBackend initialization, then the crypto session reconciles the resulting
 * durable phase. No recovery path installs an App Master Key into RAM.
 */
object InactiveDeviceActivationRecoveryCoordinatorV1 {
    @JvmStatic
    fun recoverIfNeeded(
        context: Context,
        databaseBackend: DatabaseBackend,
    ): Boolean =
        try {
            SecureContentCryptoSessionRuntimeV1.initialize(context)

            val read =
                InactiveDeviceProtectionActivationStoreV1(context.applicationContext).read()
            val phase =
                (read as? ActivationStoreReadResult.Present)?.record?.phase

            if (read is ActivationStoreReadResult.Corrupt) {
                SecureContentCryptoSessionRuntimeV1.reconcileDurableState(context)
                false
            } else {
                val needsRecovery =
                    phase == InactiveDeviceActivationPhase.WRAPPERS_VERIFIED ||
                        phase == InactiveDeviceActivationPhase.MIGRATION_PREPARED ||
                        phase == InactiveDeviceActivationPhase.MIGRATION_COMMITTED ||
                        phase == InactiveDeviceActivationPhase.ROLLBACK_REQUIRED

                if (!needsRecovery) {
                    SecureContentCryptoSessionRuntimeV1.reconcileDurableState(context)
                    true
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    SecureContentCryptoSessionRuntimeV1.reconcileDurableState(context)
                    false
                } else {
                    val participant =
                        PersistentSecureContentAccountKeyMigrationParticipantV1(
                            context.applicationContext,
                            databaseBackend,
                        )
                    try {
                        val result =
                            InactiveDeviceProtectionActivationTransactionV1(
                                context.applicationContext,
                            ).recoverMigrationBoundary(participant)
                        SecureContentCryptoSessionRuntimeV1.reconcileDurableState(context)
                        result is ActivationResult.Success<*>
                    } finally {
                        participant.close()
                    }
                }
            }
        } catch (_: Exception) {
            // Startup recovery is fail-closed. A damaged/incomplete security journal must not
            // terminate the application process; the runtime remains unavailable until the state
            // can be repaired or explicitly recovered.
            try {
                SecureContentCryptoSessionRuntimeV1.reconcileDurableState(
                    context.applicationContext,
                )
            } catch (_: Exception) {
                // Preserve fail-closed state without escalating a second recovery failure.
            }
            false
        }
}
