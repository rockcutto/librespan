// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Process-local snapshot of already authenticated Secure Content metadata.
 *
 * This cache is not a persistence or authorization boundary. It is rebuilt only from records that
 * passed metadata AEAD verification and exact account/content identity binding. Mutations are
 * published here only after the encrypted persistent write/delete succeeds.
 */
internal class SecureContentAuthenticatedMetadataCache {
    private val lock = Any()
    private val recordsByIdentity =
        LinkedHashMap<Identity, SecureContentMetadataRecord>()

    fun rebuild(records: Iterable<SecureContentMetadataRecord>) {
        synchronized(lock) {
            recordsByIdentity.clear()
            records.forEach { record ->
                recordsByIdentity[Identity(record.accountUuid, record.contentId)] = record
            }
        }
    }

    fun find(
        accountUuid: String,
        contentId: String,
    ): SecureContentMetadataRecord? =
        synchronized(lock) {
            recordsByIdentity[Identity(accountUuid, contentId)]
        }

    fun put(record: SecureContentMetadataRecord): SecureContentMetadataRecord? =
        synchronized(lock) {
            recordsByIdentity.put(
                Identity(record.accountUuid, record.contentId),
                record,
            )
        }

    fun remove(
        accountUuid: String,
        contentId: String,
    ): SecureContentMetadataRecord? =
        synchronized(lock) {
            recordsByIdentity.remove(Identity(accountUuid, contentId))
        }

    fun findByAccount(accountUuid: String): List<SecureContentMetadataRecord> =
        synchronized(lock) {
            recordsByIdentity.values.filter { it.accountUuid == accountUuid }
        }

    fun allRecords(): List<SecureContentMetadataRecord> =
        synchronized(lock) {
            recordsByIdentity.values.toList()
        }

    private data class Identity(
        val accountUuid: String,
        val contentId: String,
    )
}
