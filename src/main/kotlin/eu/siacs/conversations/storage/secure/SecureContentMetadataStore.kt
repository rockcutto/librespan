// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Persistence boundary for secure-content mappings and lifecycle facts.
 *
 * Every current lookup or mutation is scoped by the local NeoCont account UUID and content ID.
 * contentId alone is never an authorization boundary. File I/O, cryptography, transport, viewer
 * access and export remain outside this API.
 *
 * The deprecated one-argument members exist only so the pre-S3 Java database adapters remain
 * source-compatible while runtime migration is deliberately deferred. Their default bridges
 * reject legacy-unscoped records for normal account-scoped operations.
 */
interface SecureContentMetadataStore {
    fun create(
        metadata: SecureContentMetadata,
        storageLocator: String?,
    ): SecureContentMetadataRecord

    @Suppress("DEPRECATION")
    fun find(
        accountUuid: String,
        contentId: String,
    ): SecureContentMetadataRecord? =
        findByContentId(contentId)?.takeIf { it.accountUuid == accountUuid }

    @Suppress("DEPRECATION")
    fun findByMessageUuid(
        accountUuid: String,
        messageUuid: String,
    ): List<SecureContentMetadataRecord> =
        listOfNotNull(findByMessageUuid(messageUuid))
            .filter { it.accountUuid == accountUuid }

    /** Authenticated account-owned records for explicit local account cleanup. */
    fun findByAccount(accountUuid: String): List<SecureContentMetadataRecord> = emptyList()

    @Suppress("DEPRECATION")
    fun updateState(
        accountUuid: String,
        contentId: String,
        state: SecureContentState,
    ): Boolean =
        find(accountUuid, contentId) != null && updateState(contentId, state)

    /**
     * Replaces the Store-private opaque blob reference without exposing a filesystem path or URI.
     *
     * This default preserves existing source implementations until their runtime migration.
     */
    fun updateStorageLocator(
        accountUuid: String,
        contentId: String,
        storageLocator: String?,
    ): Boolean = false

    @Suppress("DEPRECATION")
    fun deleteMetadata(
        accountUuid: String,
        contentId: String,
    ): Boolean =
        find(accountUuid, contentId) != null && deleteMetadata(contentId)

    /** Legacy compatibility only; it must not authorize account-scoped runtime access. */
    @Deprecated("Use account-scoped find(accountUuid, contentId)")
    fun findByContentId(contentId: String): SecureContentMetadataRecord? = null

    /** Legacy compatibility only; it must not authorize account-scoped runtime access. */
    @Deprecated("Use account-scoped findByMessageUuid(accountUuid, messageUuid)")
    fun findByMessageUuid(messageUuid: String): SecureContentMetadataRecord? = null

    /** Legacy compatibility only; it must not mutate account-scoped runtime content. */
    @Deprecated("Use account-scoped updateState(accountUuid, contentId, state)")
    fun updateState(
        contentId: String,
        state: SecureContentState,
    ): Boolean = false

    /** Legacy compatibility only; it must not mutate account-scoped runtime content. */
    @Deprecated("Use account-scoped deleteMetadata(accountUuid, contentId)")
    fun deleteMetadata(contentId: String): Boolean = false
}
