// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Store metadata required to describe an account-owned logical content object.
 *
 * This is an API model, not a database entity or a schema commitment. The fields reserve
 * the context required for a future authenticated binding:
 * namespace + cryptoVersion + accountUuid + contentId.
 */
data class SecureContentMetadata(
    val accountUuid: String,
    val contentId: String,
    val namespace: String = DEFAULT_NAMESPACE,
    val messageUuid: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val state: SecureContentState = SecureContentState.ALLOCATED,
    val cryptoVersion: Int? = null,
    /**
     * Logical allocation time. A future store implementation assigns the real value when
     * it allocates content; zero keeps this source-only contract non-migrating.
     */
    val createdAt: Long = 0L,
) {
    init {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(sizeBytes == null || sizeBytes >= 0) { "sizeBytes must not be negative" }
        require(cryptoVersion == null || cryptoVersion >= 0) {
            "cryptoVersion must not be negative"
        }
        require(createdAt >= 0) { "createdAt must not be negative" }
    }

    /**
     * Java-source compatibility constructor for the pre-S3 database adapters.
     *
     * It intentionally assigns a sentinel owner. Account-scoped runtime operations reject that
     * sentinel unless a future explicit migration supplies a real account UUID.
     */
    @Deprecated("Legacy database compatibility only; use the account-scoped constructor")
    constructor(
        contentId: String,
        messageUuid: String?,
        mimeType: String?,
        sizeBytes: Long?,
        state: SecureContentState,
        cryptoVersion: Int?,
    ) : this(
        accountUuid = LEGACY_UNSCOPED_ACCOUNT_UUID,
        contentId = contentId,
        messageUuid = messageUuid,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        state = state,
        cryptoVersion = cryptoVersion,
    )

    companion object {
        const val DEFAULT_NAMESPACE = "secure-content"
        const val LEGACY_UNSCOPED_ACCOUNT_UUID = "legacy-unscoped"
    }
}

/**
 * Lifecycle states understood by future Secure Content Store persistence.
 *
 * New S4.2 writes use ALLOCATED, WRITING, READY_TO_COMMIT, COMMITTED, ABORTED, and FAILED.
 * The deprecated receive states are retained solely to decode source-compatible legacy
 * persisted values; a new write must never target them.
 */
enum class SecureContentState(
    val persistedValue: String,
) {
    ALLOCATED("allocated"),
    WRITING("writing"),
    READY_TO_COMMIT("ready_to_commit"),
    COMMITTED("committed"),
    ABORTED("aborted"),
    FAILED("failed"),

    @Deprecated("Legacy persisted-value compatibility only; new writes use WRITING")
    RECEIVING("receiving"),

    @Deprecated("Legacy persisted-value compatibility only; new writes use COMMITTED")
    COMPLETE("complete"),

    @Deprecated("Legacy persisted-value compatibility only; new writes use COMMITTED")
    AVAILABLE("available");

    /** Normal readers may consume only an object published as COMMITTED. */
    val isConsumerVisible: Boolean
        get() = this == COMMITTED

    companion object {
        /**
         * Unknown persisted values are treated as unavailable rather than opened optimistically.
         */
        @JvmStatic
        fun fromPersistedValue(value: String?): SecureContentState =
            entries.firstOrNull { it.persistedValue == value } ?: FAILED
    }
}
