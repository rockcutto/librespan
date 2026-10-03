// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-CORE

package eu.siacs.conversations.storage.secure

import java.io.InputStream
import java.io.OutputStream

/**
 * Runtime owner of the account-scoped Secure Content Store lifecycle.
 *
 * Operations expose logical identity and controlled readable access, never filesystem paths,
 * Files, public URIs, blob bytes, or key material. contentId alone never addresses or
 * authorizes an object.
 */
interface SecureContentStore {
    /**
     * Reserves an invisible object in [SecureContentState.ALLOCATED].
     *
     * Allocation does not start a write and does not publish content to readers.
     */
    fun allocate(metadata: SecureContentMetadata): SecureContentHandle

    /**
     * Moves one allocated account-scoped object into [SecureContentState.WRITING].
     *
     * The session is the only writer capability. It deliberately contains no path, stream,
     * transport, or crypto implementation contract.
     */
    fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession

    /**
     * Resolves a runtime object snapshot by its complete ownership identity.
     *
     * A cross-account lookup must fail closed. A missing record returns null; it must not fall
     * back to a locator, path, URI, or legacy attachment.
     */
    fun find(
        accountUuid: String,
        contentId: String,
    ): SecureContentObject?

    /**
     * Returns authenticated logical snapshots related to one account-scoped message UUID.
     *
     * A message relation is not an authorization capability and is deliberately a collection:
     * callers must select one exact [SecureContentTransferBinding.contentId] before opening it.
     * Implementations must not consult a path, URI, FileBackend object, or legacy attachment.
     */
    fun findByMessage(
        accountUuid: String,
        messageUuid: String,
    ): List<SecureContentObject> = emptyList()

    /** Returns authenticated ownership snapshots for explicit local account cleanup. */
    fun findByAccount(accountUuid: String): List<SecureContentObject> = emptyList()

    /**
     * Approximate protected blob bytes currently occupied by COMMITTED content for one account.
     *
     * This aggregate deliberately exposes no path, blob reference, ciphertext, key material, or
     * plaintext and may return null while the secure runtime is unavailable.
     */
    fun committedStorageSizeBytes(accountUuid: String): Long? = null

    /**
     * Opens a controlled, provisional plaintext read session only when the object is COMMITTED.
     *
     * Missing metadata/blob, an ownership mismatch, a non-COMMITTED state, integrity failure or
     * close before EOF must fail closed. A caller must not treat bytes as a successful whole
     * object until [SecureContentReadSession.verifyTerminal] succeeds. This is intentionally not
     * an open(path) contract.
     */
    fun open(
        accountUuid: String,
        contentId: String,
    ): SecureContentReadSession

    /**
     * Handle convenience form. The account context remains explicit in the handle.
     */
    fun open(handle: SecureContentHandle): SecureContentReadSession =
        open(handle.accountUuid, handle.contentId)

    fun metadata(handle: SecureContentHandle): SecureContentMetadata? =
        find(handle.accountUuid, handle.contentId)?.metadata

    /**
     * Removes Store-owned state only within the account ownership boundary.
     */
    fun delete(
        accountUuid: String,
        contentId: String,
    )

    fun delete(handle: SecureContentHandle) =
        delete(handle.accountUuid, handle.contentId)
}

/**
 * Store-validated non-visible facts required for one logical publication transaction.
 *
 * All candidates must belong to the same account-owned write attempt. This type contains no
 * raw key, protected bytes, path, URI, FileBackend object, or reader/export handle.
 */
data class SecureContentCommitPayload(
    val cryptoCandidate: SecureContentCryptoWriteCandidate,
    val blobCandidate: SecureContentBlobCommitCandidate,
    val keyMaterialCandidate: SecureContentKeyMaterialCommitCandidate,
) {
    init {
        require(cryptoCandidate.context == keyMaterialCandidate.context) {
            "crypto and key material candidates must use the same context"
        }
    }

    val context: SecureContentCryptoContext
        get() = cryptoCandidate.context
}

/**
 * Store-owned writer capability for one object in [SecureContentState.WRITING].
 *
 * The Store creates this session and gives it only to its authorised writer collaborator.
 * Closing a future underlying resource cannot publish content. The writer must provide a
 * complete Store-validated payload to begin a commit transaction or explicitly abort.
 */
interface SecureContentWriteSession {
    val handle: SecureContentHandle

    val state: SecureContentState

    /**
     * Opens the only plaintext writer capability for this session.
     *
     * The returned stream feeds the Store-selected CryptoEngine. Closing it only completes
     * encryption; it does not make content readable or publish any lifecycle state.
     */
    fun openPlaintextOutputStream(): OutputStream

    /**
     * Completes controlled writing and prepares a matching Store-owned publication payload.
     *
     * This convenience operation creates the three opaque candidates internally; callers cannot
     * combine candidates from separate sessions. It does not make the object readable.
     */
    fun finishAndBeginCommit(): SecureContentCommitTransaction

    /**
     * Prepares the Store-owned publication boundary for a complete candidate set.
     *
     * Implementations must validate that [payload] belongs to [handle] before transitioning to
     * READY_TO_COMMIT. Preparing does not make content visible. The returned transaction
     * conceptually joins metadata publication, the protected-blob fact, the key-material
     * activation fact, and the content-state transition; a later backend supplies real
     * atomicity and recovery.
     */
    fun beginCommit(
        payload: SecureContentCommitPayload,
    ): SecureContentCommitTransaction

    /**
     * Cancels an expected incomplete write. The Store transitions it to ABORTED without
     * normal-reader visibility. It is not a failed finalization.
     */
    fun abort()
}

/**
 * Opaque Store-owned publication boundary for one write session in
 * [SecureContentState.READY_TO_COMMIT].
 *
 * [commit] is the only normal publication operation. Its successful result represents the
 * same account-scoped object with metadata, protected-blob/key-material facts, and content
 * state published together as COMMITTED. This contract forbids partial publication; a later
 * backend supplies the durable atomicity/recovery mechanism.
 */
interface SecureContentCommitTransaction {
    val handle: SecureContentHandle

    val state: SecureContentState

    fun commit(): SecureContentObject

    /**
     * Cancels this prepared transaction without reader visibility. The Store transitions the
     * object to ABORTED; failed finalization is recorded as FAILED by the Store instead.
     */
    fun abort()
}


/**
 * Controlled verified-read capability for a committed Secure Content object.
 *
 * [openPlaintextInputStream] provides provisional streaming bytes. The caller must consume it to
 * EOF and then call [verifyTerminal] before treating the object as successfully displayed,
 * downloaded, played, exported, cached as plaintext, or otherwise consumed. Closing before EOF
 * makes verification fail. This explicit boundary prevents a truncated authenticated stream from
 * being mistaken for a complete object.
 */
interface SecureContentReadSession : AutoCloseable {
    val handle: SecureContentHandle

    val state: SecureContentReadState

    /**
     * Opens the only controlled plaintext stream for this session.
     *
     * It may be opened once. Bytes read before terminal verification remain provisional.
     */
    fun openPlaintextInputStream(): InputStream

    /**
     * Confirms clean authenticated terminal completion after the stream reached EOF.
     *
     * Returns the current COMMITTED object snapshot only when EOF was observed with no reader
     * error. This does not authorize export or create any plaintext derivative by itself.
     */
    fun verifyTerminal(): SecureContentObject

    /**
     * Releases the reader capability. If EOF was not observed and verified first, the session
     * remains non-successful.
     */
    override fun close()
}

enum class SecureContentReadState {
    OPEN,
    TERMINAL_VERIFIED,
    FAILED,
    CLOSED,
}
