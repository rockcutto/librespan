// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

/**
 * Store-private persistence boundary for protected bytes only.
 *
 * BlobStore does not receive account ownership, content identity, namespace, crypto version,
 * key material, plaintext, or lifecycle authority. SecureContentStore owns the mapping from an
 * account-scoped object to an opaque reference and decides reader publication.
 */
interface SecureContentBlobStore {
    /**
     * Allocates an invisible protected-byte staging session.
     *
     * The returned sink is supplied only to the matching CryptoEngine operation by Store
     * orchestration. Starting a session does not publish a reference or make an object readable.
     */
    fun put(): SecureContentBlobResult<SecureContentBlobWriteSession>

    /**
     * Opens Store-selected protected bytes through a controlled source.
     *
     * This method neither authorizes an account nor verifies/decrypts bytes. Callers must not use
     * it as a path/URI fallback or a publication check.
     */
    fun get(
        reference: SecureContentBlobReference,
    ): SecureContentBlobResult<SecureContentProtectedByteSource>

    /**
     * Retires a Store-selected protected-byte reference.
     *
     * Blob deletion neither invalidates key material nor transitions metadata; Store recovery
     * coordinates those decisions.
     */
    fun delete(
        reference: SecureContentBlobReference,
    ): SecureContentBlobResult<Unit>

    /** Physical/reference existence for Store recovery only; it never grants reader access. */
    fun exists(
        reference: SecureContentBlobReference,
    ): SecureContentBlobResult<Boolean>
}

/**
 * Store-private protected-byte staging lifecycle.
 *
 * [prepareCommit] returns only a non-visible candidate. It cannot publish a reference or make
 * content readable. [abort] ends an expected incomplete staging attempt; durable cleanup remains
 * a Store recovery concern.
 */
interface SecureContentBlobWriteSession {
    val protectedSink: SecureContentProtectedByteSink

    fun prepareCommit(): SecureContentBlobResult<SecureContentBlobCommitCandidate>

    fun abort()
}

/**
 * Opaque proof that staged protected bytes can participate in a Store-owned commit transaction.
 *
 * A candidate is not a committed reference and cannot be passed to [SecureContentBlobStore.get].
 */
interface SecureContentBlobCommitCandidate

/**
 * Opaque Store-private reference for committed protected bytes.
 *
 * It is not an account/content identity, path, URI, filename, File, FileBackend object, or
 * authorization token.
 */
interface SecureContentBlobReference

/** Non-secret storage result. Provider/path/URI/plaintext details must not cross this boundary. */
sealed interface SecureContentBlobResult<out T> {
    data class Success<T>(
        val value: T,
    ) : SecureContentBlobResult<T>

    data class Failure(
        val reason: SecureContentBlobFailure,
    ) : SecureContentBlobResult<Nothing>
}

enum class SecureContentBlobFailure {
    STAGING_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    REFERENCE_UNAVAILABLE,
    PREPARATION_FAILED,
    RETIREMENT_FAILED,
}
