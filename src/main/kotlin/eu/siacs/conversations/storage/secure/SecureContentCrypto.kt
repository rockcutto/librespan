// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Immutable Store-validated context for one protected-content operation.
 *
 * This is the canonical authenticated binding. Constructing it does not authorize account
 * access: [SecureContentStore] must validate ownership before it invokes a crypto adapter.
 */
data class SecureContentCryptoContext(
    val namespace: String,
    val cryptoVersion: Int,
    val accountUuid: String,
    val contentId: String,
) {
    init {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(cryptoVersion >= 0) { "cryptoVersion must not be negative" }
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
    }
}

/**
 * Opaque, already-resolved key-material capability.
 *
 * It deliberately exposes no raw key, wrapping reference, Android provider, path, or storage
 * representation. The [context] lets the adapter reject cross-object or cross-account use.
 */
interface SecureContentKeyMaterialHandle {
    val context: SecureContentCryptoContext
}

/**
 * Store-controlled source of protected bytes.
 *
 * The stream is not a path, URI, File, FileBackend API, public export, or authorization token.
 * Callers receive it only after the Store selected a committed opaque blob reference.
 */
interface SecureContentProtectedByteSource {
    @Throws(IOException::class)
    fun openProtectedInputStream(): InputStream
}

/**
 * Store-controlled sink for protected bytes.
 *
 * The stream is not a path, URI, File, FileBackend API, public output, or authorization token.
 * A Store-created write operation owns the sink and supplies it to exactly one crypto writer.
 */
interface SecureContentProtectedByteSink {
    @Throws(IOException::class)
    fun openProtectedOutputStream(): OutputStream
}

/**
 * Adapter boundary for the selected streaming authenticated-protection construction.
 *
 * The engine receives only a Store-validated context plus opaque capabilities. It does not
 * resolve ownership, manage key records, persist blobs, change lifecycle state, or publish
 * content. Implementations report controlled failures rather than falling back to plaintext
 * or legacy path-based sources.
 */
interface SecureContentCryptoEngine {
    fun beginWrite(
        context: SecureContentCryptoContext,
        keyMaterial: SecureContentKeyMaterialHandle,
        protectedSink: SecureContentProtectedByteSink,
    ): SecureContentCryptoResult<SecureContentCryptoWriter>

    fun openRead(
        context: SecureContentCryptoContext,
        keyMaterial: SecureContentKeyMaterialHandle,
        protectedSource: SecureContentProtectedByteSource,
    ): SecureContentCryptoResult<SecureContentCryptoReader>
}

/**
 * Active Store-authorized protection operation.
 *
 * [openPlaintextOutputStream] is a controlled one-operation stream: bytes are protected as they
 * are written to the Store-owned sink. [finish] produces only an opaque crypto-complete candidate.
 * Neither operation can commit metadata, publish a BlobStore object, or make content visible.
 */
interface SecureContentCryptoWriter {
    val context: SecureContentCryptoContext

    @Throws(IOException::class)
    fun openPlaintextOutputStream(): OutputStream

    fun finish(): SecureContentCryptoResult<SecureContentCryptoWriteCandidate>

    fun abort()
}

/**
 * Opaque proof that a crypto writer completed its own finalization.
 *
 * A candidate is still non-visible. The Store must coordinate matching BlobStore and
 * KeyMaterialStore facts before its commit transaction publishes COMMITTED state.
 */
interface SecureContentCryptoWriteCandidate {
    val context: SecureContentCryptoContext
}

/**
 * Controlled verified-read capability for an already committed Store object.
 *
 * The stream is not a public export, path, URI, or reader authorization token. The caller must
 * treat successful terminal completion as the only successful whole-object result.
 */
interface SecureContentCryptoReader : AutoCloseable {
    val context: SecureContentCryptoContext

    @Throws(IOException::class)
    fun openPlaintextInputStream(): InputStream

    override fun close()
}

/** Non-secret adapter result for Store-owned failure routing. */
sealed interface SecureContentCryptoResult<out T> {
    data class Success<T>(
        val value: T,
    ) : SecureContentCryptoResult<T>

    data class Failure(
        val reason: SecureContentCryptoFailure,
    ) : SecureContentCryptoResult<Nothing>
}

/**
 * Stable fail-closed categories. A library adapter may map internal errors here without leaking
 * raw key, provider, path, URI, or plaintext details.
 */
enum class SecureContentCryptoFailure {
    CONTEXT_MISMATCH,
    UNSUPPORTED_VERSION,
    INTEGRITY_REJECTED,
    SOURCE_UNAVAILABLE,
    FINALIZATION_FAILED,
}
