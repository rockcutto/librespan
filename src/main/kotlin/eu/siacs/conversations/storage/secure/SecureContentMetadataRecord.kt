// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Persisted secure-content metadata plus an opaque Store-private locator.
 *
 * The locator is not a public or legacy attachment path. Identity fields delegate to
 * [metadata] so the persisted record cannot be addressed outside its account boundary.
 */
data class SecureContentMetadataRecord(
    val metadata: SecureContentMetadata,
    val storageLocator: String?,
    val updatedAt: Long,
) {
    val accountUuid: String
        get() = metadata.accountUuid

    val namespace: String
        get() = metadata.namespace

    val contentId: String
        get() = metadata.contentId

    val messageUuid: String?
        get() = metadata.messageUuid

    val createdAt: Long
        get() = metadata.createdAt

    init {
        require(updatedAt >= createdAt) { "updatedAt must not be before createdAt" }
    }

    /**
     * Java-source compatibility constructor for pre-S3 database adapters.
     *
     * The persisted creation time is folded into metadata because the current runtime record model
     * has one authoritative metadata creation timestamp plus an update timestamp.
     */
    @Deprecated("Legacy database compatibility only; use SecureContentMetadataRecord(metadata, locator, updatedAt)")
    constructor(
        metadata: SecureContentMetadata,
        storageLocator: String?,
        createdAt: Long,
        updatedAt: Long,
    ) : this(
        metadata = metadata.copy(createdAt = createdAt),
        storageLocator = storageLocator,
        updatedAt = updatedAt,
    )
}
