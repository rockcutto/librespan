// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.security.cryptolock

import android.content.Context
import android.os.Build
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.storage.secure.PersistentSecureContentAccountKeyDeactivationParticipantV1

/**
 * Reconciles reverse-migration crash windows before normal activation/session recovery.
 *
 * A deactivation transaction may have durable key-store candidates or a fail-closed transitional
 * authority even when the outer journal still reports PREPARING/MIGRATION_PREPARED. Recovery is
 * therefore driven from service startup as soon as the account database is available.
 */
object InactiveDeviceDeactivationRecoveryCoordinatorV1 {
    @JvmStatic
    fun recoverIfNeeded(
        context: Context,
        databaseBackend: DatabaseBackend,
    ): Boolean =
        try {
            val applicationContext = context.applicationContext
            val read = InactiveDeviceProtectionDeactivationStoreV1(applicationContext).read()
            if (read is DeactivationStoreReadResultV1.Absent) {
                true
            } else if (read is DeactivationStoreReadResultV1.Corrupt) {
                SecureContentCryptoSessionRuntimeV1.reconcileDurableState(applicationContext)
                false
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                SecureContentCryptoSessionRuntimeV1.reconcileDurableState(applicationContext)
                false
            } else {
                val participant =
                    PersistentSecureContentAccountKeyDeactivationParticipantV1(
                        applicationContext,
                        databaseBackend,
                    )
                try {
                    val result =
                        InactiveDeviceProtectionDeactivationTransactionV1(
                            applicationContext,
                        ).recover(participant)
                    SecureContentCryptoSessionRuntimeV1.reconcileDurableState(applicationContext)
                    result is DeactivationResult.Success<*>
                } finally {
                    participant.close()
                }
            }
        } catch (_: Exception) {
            // Reverse-migration recovery is also fail-closed. Never let a stale/corrupt recovery
            // transaction kill the process during service startup.
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
