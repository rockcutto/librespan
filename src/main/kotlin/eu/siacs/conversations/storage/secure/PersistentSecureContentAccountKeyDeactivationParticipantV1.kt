// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.security.cryptolock.AppMasterKey
import eu.siacs.conversations.security.cryptolock.DeactivationMigrationEvidence
import eu.siacs.conversations.security.cryptolock.DeactivationMigrationParticipantV1
import eu.siacs.conversations.security.cryptolock.DeactivationMigrationState

internal class PersistentSecureContentAccountKeyDeactivationParticipantV1(
    context: Context,
    private val databaseBackend: DatabaseBackend,
    private val keyMaterialStore: PersistentSecureContentKeyMaterialStore =
        PersistentSecureContentKeyMaterialStore(context.applicationContext),
) : DeactivationMigrationParticipantV1, AutoCloseable {

    override fun prepareAndVerify(
        transactionId: String,
        appMasterKey: AppMasterKey,
    ): DeactivationMigrationEvidence? {
        val evidence =
            keyMaterialStore.prepareAccountKeyDeactivation(
                transactionId,
                allLocalAccountUuids(),
                appMasterKey,
            ) ?: return null
        return DeactivationMigrationEvidence.from(evidence).also { evidence.fill(0) }
    }

    override fun findPrepared(transactionId: String): DeactivationMigrationEvidence? {
        val evidence =
            keyMaterialStore.findPreparedAccountKeyDeactivation(
                transactionId,
                allLocalAccountUuids(),
            ) ?: return null
        return DeactivationMigrationEvidence.from(evidence).also { evidence.fill(0) }
    }

    override fun state(
        transactionId: String,
        evidence: DeactivationMigrationEvidence,
    ): DeactivationMigrationState {
        val binding = evidence.binding()
        return try {
            when (
                keyMaterialStore.accountKeyDeactivationState(
                    transactionId,
                    binding,
                    allLocalAccountUuids(),
                )
            ) {
                SecureContentAccountMigrationStateV1.ABSENT ->
                    DeactivationMigrationState.ABSENT
                SecureContentAccountMigrationStateV1.PREPARED ->
                    DeactivationMigrationState.PREPARED
                SecureContentAccountMigrationStateV1.COMMITTED ->
                    DeactivationMigrationState.COMMITTED
                SecureContentAccountMigrationStateV1.ROLLED_BACK,
                SecureContentAccountMigrationStateV1.CORRUPT ->
                    DeactivationMigrationState.CORRUPT
            }
        } finally {
            binding.fill(0)
        }
    }

    override fun commit(
        transactionId: String,
        evidence: DeactivationMigrationEvidence,
    ): Boolean {
        val binding = evidence.binding()
        return try {
            keyMaterialStore.commitAccountKeyDeactivation(
                transactionId,
                binding,
                allLocalAccountUuids(),
            )
        } finally {
            binding.fill(0)
        }
    }

    override fun rollback(
        transactionId: String,
        evidence: DeactivationMigrationEvidence?,
    ): Boolean {
        val binding = evidence?.binding()
        return try {
            keyMaterialStore.rollbackAccountKeyDeactivation(
                transactionId,
                binding,
                allLocalAccountUuids(),
            )
        } finally {
            binding?.fill(0)
        }
    }

    override fun finalizeCommitted(transactionId: String): Boolean =
        keyMaterialStore.finalizeAccountKeyDeactivation(transactionId)

    override fun isFinalized(transactionId: String): Boolean =
        keyMaterialStore.isAccountKeyDeactivationFinalized(allLocalAccountUuids())

    override fun close() = Unit

    private fun allLocalAccountUuids(): List<String> =
        databaseBackend.getAccounts()
            .map { it.uuid }
            .filter { it.isNotBlank() }
            .distinct()
}
