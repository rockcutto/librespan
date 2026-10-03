// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Process-local accelerator for message -> Secure Content candidate resolution.
 *
 * This index is never an authorization source. It is populated only from authenticated metadata
 * records, is never persisted, and callers must still read and validate the exact metadata record
 * selected by each returned content ID.
 *
 * Multiple content IDs are retained deliberately so duplicate relations remain visible to the
 * fail-closed caller instead of being silently collapsed.
 */
internal class SecureContentMessageRelationIndex {
    private val lock = Any()
    private val contentIdsByMessage =
        HashMap<MessageRelationKey, LinkedHashSet<String>>()

    fun rebuild(records: Iterable<SecureContentMetadataRecord>) {
        synchronized(lock) {
            contentIdsByMessage.clear()
            records.forEach(::addLocked)
        }
    }

    fun add(record: SecureContentMetadataRecord) {
        synchronized(lock) {
            addLocked(record)
        }
    }

    fun remove(record: SecureContentMetadataRecord) {
        val messageUuid = record.messageUuid ?: return
        remove(record.accountUuid, messageUuid, record.contentId)
    }

    fun remove(
        accountUuid: String,
        messageUuid: String,
        contentId: String,
    ) {
        synchronized(lock) {
            val key = MessageRelationKey(accountUuid, messageUuid)
            val contentIds = contentIdsByMessage[key] ?: return
            contentIds.remove(contentId)
            if (contentIds.isEmpty()) {
                contentIdsByMessage.remove(key)
            }
        }
    }

    fun contentIds(
        accountUuid: String,
        messageUuid: String,
    ): List<String> =
        synchronized(lock) {
            contentIdsByMessage[MessageRelationKey(accountUuid, messageUuid)]
                ?.toList()
                ?: emptyList()
        }

    private fun addLocked(record: SecureContentMetadataRecord) {
        val messageUuid = record.messageUuid ?: return
        val key = MessageRelationKey(record.accountUuid, messageUuid)
        contentIdsByMessage
            .getOrPut(key) { LinkedHashSet() }
            .add(record.contentId)
    }

    private data class MessageRelationKey(
        val accountUuid: String,
        val messageUuid: String,
    )
}
