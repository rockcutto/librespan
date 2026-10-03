// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Store-private boundary for protected key-material lifecycle.
 *
 * SecureContentStore validates account ownership and content lifecycle before invoking this
 * contract. The store is not an authorization service: a complete matching [context] is still
 * required so a cross-account, cross-object, or cross-version capability is rejected fail
 * closed.
 *
 * No method returns raw key bytes, a Tink keyset, Android Keystore entry or alias, persistence
 * record, path, URI, FileBackend object, plaintext, or BlobStore reference.
 */
interface SecureContentKeyMaterialStore {
    /**
     * Starts candidate material for one Store-authorized write attempt.
     *
     * Candidate material is never reader-visible. A retry must use a fresh write session rather
     * than reuse this session, its writer handle, or its future material.
     */
    fun beginWrite(
        context: SecureContentCryptoContext,
    ): SecureContentKeyMaterialResult<SecureContentKeyMaterialWriteSession>

    /**
     * Resolves an active capability for a Store-validated COMMITTED object only.
     *
     * Implementations must reject a non-active, invalidated, recovery-in-doubt, missing, or
     * mismatched record. The caller must not use this method as an authorization check.
     */
    fun resolveForRead(
        context: SecureContentCryptoContext,
    ): SecureContentKeyMaterialResult<SecureContentKeyMaterialHandle>

    /**
     * Invalidates access material only for the complete Store-authorized ownership context.
     *
     * Invalidation makes reader resolution fail closed. Physical material retirement and durable
     * recovery remain Store/coordinator responsibilities.
     */
    fun invalidate(
        context: SecureContentCryptoContext,
    ): SecureContentKeyMaterialResult<Unit>
}

/**
 * Store-owned candidate material transaction for one write operation.
 *
 * [writerHandle] may be passed only to the matching CryptoEngine write operation. Neither
 * [prepareCommit] nor [abort] publishes metadata, a protected blob, or key material.
 */
interface SecureContentKeyMaterialWriteSession {
    val context: SecureContentCryptoContext

    val state: SecureContentKeyMaterialState

    val writerHandle: SecureContentKeyMaterialHandle

    /**
     * Produces non-visible material evidence for the Store's logical commit transaction.
     *
     * The candidate cannot be used by a reader and cannot activate material independently.
     */
    fun prepareCommit(): SecureContentKeyMaterialResult<SecureContentKeyMaterialCommitCandidate>

    /** Ends an expected incomplete candidate. The Store records/reconciles durable cleanup. */
    fun abort()
}

/**
 * Opaque candidate that may participate in a Store-owned atomic publication transaction.
 *
 * It must match the same [context] as the write session and protected-byte candidate. It is not
 * a key-material reader handle and does not make an object COMMITTED.
 */
interface SecureContentKeyMaterialCommitCandidate {
    val context: SecureContentCryptoContext

    val state: SecureContentKeyMaterialState
}

/** Non-secret material lifecycle facts used for Store-owned recovery and reader eligibility. */
enum class SecureContentKeyMaterialState {
    CANDIDATE,
    READY_TO_COMMIT,
    ACTIVE,
    ABORTED,
    FAILED,
    INVALIDATED,
    RECOVERY_IN_DOUBT,
}

/**
 * Fail-closed key-material result.
 *
 * Failure reasons are control-flow categories only. They must not contain raw keys, wrapped
 * records, root aliases, provider details, filesystem data, or plaintext.
 */
sealed interface SecureContentKeyMaterialResult<out T> {
    data class Success<T>(
        val value: T,
    ) : SecureContentKeyMaterialResult<T>

    data class Failure(
        val reason: SecureContentKeyMaterialFailure,
    ) : SecureContentKeyMaterialResult<Nothing>
}

enum class SecureContentKeyMaterialFailure {
    CONTEXT_MISMATCH,
    UNSUPPORTED_VERSION,
    MATERIAL_UNAVAILABLE,
    MATERIAL_INVALIDATED,
    RECOVERY_IN_DOUBT,
    PREPARATION_FAILED,
}
