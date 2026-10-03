// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.security.cryptolock.ActivationMigrationEvidence
import eu.siacs.conversations.security.cryptolock.ActivationMigrationParticipantV1
import eu.siacs.conversations.security.cryptolock.ActivationMigrationState
import eu.siacs.conversations.security.cryptolock.AppMasterKey

/**
 * Production migration participant for the current Secure Content account-key repository.
 *
 * Inventory comes from DatabaseBackend.getAccounts(), including disabled-but-retained accounts.
 * Runtime authorization remains a separate SecureContentAccountRegistry concern.
 */
internal class PersistentSecureContentAccountKeyMigrationParticipantV1(
    context: Context,
    private val databaseBackend: DatabaseBackend,
    private val keyMaterialStore: PersistentSecureContentKeyMaterialStore =
        PersistentSecureContentKeyMaterialStore(context.applicationContext),
) : ActivationMigrationParticipantV1, AutoCloseable {

    override fun prepareAndVerify(
        transactionId: String,
        appMasterKey: AppMasterKey,
    ): ActivationMigrationEvidence? {
        val evidence =
            keyMaterialStore.prepareAccountKeyMigration(
                transactionId,
                allLocalAccountUuids(),
                appMasterKey,
            ) ?: return null
        return ActivationMigrationEvidence.from(evidence).also {
            evidence.fill(0)
        }
    }

    override fun findPrepared(transactionId: String): ActivationMigrationEvidence? {
        val evidence =
            keyMaterialStore.findPreparedAccountKeyMigration(
                transactionId,
                allLocalAccountUuids(),
            ) ?: return null
        return ActivationMigrationEvidence.from(evidence).also {
            evidence.fill(0)
        }
    }

    override fun state(
        transactionId: String,
        evidence: ActivationMigrationEvidence,
    ): ActivationMigrationState {
        val binding = evidence.binding()
        return try {
            when (
                keyMaterialStore.accountKeyMigrationState(
                    transactionId,
                    binding,
                    allLocalAccountUuids(),
                )
            ) {
                SecureContentAccountMigrationStateV1.ABSENT ->
                    ActivationMigrationState.ABSENT
                SecureContentAccountMigrationStateV1.PREPARED ->
                    ActivationMigrationState.PREPARED
                SecureContentAccountMigrationStateV1.COMMITTED ->
                    ActivationMigrationState.COMMITTED
                SecureContentAccountMigrationStateV1.ROLLED_BACK ->
                    ActivationMigrationState.ROLLED_BACK
                SecureContentAccountMigrationStateV1.CORRUPT ->
                    ActivationMigrationState.CORRUPT
            }
        } finally {
            binding.fill(0)
        }
    }

    override fun commit(
        transactionId: String,
        evidence: ActivationMigrationEvidence,
    ): Boolean {
        val binding = evidence.binding()
        val committed =
            try {
                keyMaterialStore.commitAccountKeyMigration(
                    transactionId,
                    binding,
                    allLocalAccountUuids(),
                )
            } finally {
                binding.fill(0)
            }
        return committed
    }

    override fun rollback(
        transactionId: String,
        evidence: ActivationMigrationEvidence?,
    ): Boolean {
        val binding = evidence?.binding()
        return try {
            val rolledBack =
                keyMaterialStore.rollbackAccountKeyMigration(
                    transactionId,
                    binding,
                    allLocalAccountUuids(),
                )
            rolledBack
        } finally {
            binding?.fill(0)
        }
    }

    override fun diagnosticCode(): String? =
        keyMaterialStore.lastAccountMigrationDiagnosticCode()

    override fun close() = Unit

    private fun allLocalAccountUuids(): List<String> =
        databaseBackend.getAccounts()
            .map { it.uuid }
            .filter { it.isNotBlank() }
            .distinct()
}
