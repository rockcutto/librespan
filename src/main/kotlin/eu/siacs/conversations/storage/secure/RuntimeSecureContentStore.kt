// Copyright (c) 2026 rockcutto
// SPDX-License-Identifier: MPL-2.0
// Provenance: NATIVE-ANDROID

package eu.siacs.conversations.storage.secure

import android.content.Context
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * First private runtime implementation of the Secure Content Store.
 *
 * It is intentionally not wired into transport, Jingle, viewers, UI, FileBackend, MediaStore, or
 * FileProvider. All publication decisions stay here: the CryptoEngine sees controlled streams,
 * BlobStore sees opaque protected-byte references, and KeyMaterialStore never authorizes callers.
 */
class RuntimeSecureContentStore private constructor(
    context: Context,
    private val keyMaterialStore: PersistentSecureContentKeyMaterialStore,
    private val blobStore: InternalSecureContentBlobStore,
    private val cryptoEngine: SecureContentCryptoEngine,
    private val accountAuthority: SecureContentAccountAuthority,
) : SecureContentStore {
    constructor(
        context: Context,
        accountAuthority: SecureContentAccountAuthority,
    ) : this(
        context = context,
        keyMaterialStore = PersistentSecureContentKeyMaterialStore(context),
        blobStore = InternalSecureContentBlobStore(context),
        cryptoEngine = TinkSecureContentCryptoEngine(),
        accountAuthority = accountAuthority,
    )
    private val metadataStore = AccountProtectedSecureContentMetadataStore(context, keyMaterialStore)
    private val recoveryStore = AccountProtectedSecureContentRecoveryStore(context, keyMaterialStore)
    private val runtimeCreatedAtMillis = System.currentTimeMillis()
    private var loadedCryptoEpoch: Long? = null
    private var rootEpochPrepared = false

    init {
        try {
            ensureCryptoReady()
        } catch (_: Exception) {
            // Gated hierarchy starts locked after process death. Recovery is deferred until the
            // authenticated crypto session is restored; Store operations remain fail closed.
        }
    }

    /**
     * Single cache-reset/recovery barrier for a crypto-session epoch.
     *
     * Multiple consumers can arrive immediately after unlock. Serialize the epoch check, clear any
     * process-local authenticated metadata from the previous epoch, and recover only journaled
     * interrupted writes so one thread performs transition work for a given epoch.
     */
    @Synchronized
    private fun ensureCryptoReady(): Long? {
        val epoch = keyMaterialStore.cryptoEpochForOperation()
        val needsReload =
            if (epoch == null) {
                !rootEpochPrepared
            } else {
                loadedCryptoEpoch != epoch
            }
        if (needsReload) {
            metadataStore.resetAuthenticatedState()
            val recoveryStarted = System.nanoTime()
            recoverInterruptedWrites()
            SecureColdStartPerfTrace.stage(
                "store_recovery",
                System.nanoTime() - recoveryStarted,
            )
            loadedCryptoEpoch = epoch
            rootEpochPrepared = epoch == null
        }
        return epoch
    }

    @Synchronized
    override fun allocate(metadata: SecureContentMetadata): SecureContentHandle {
        ensureCryptoReady()
        requireRegisteredAccount(metadata.accountUuid)
        require(metadata.state == SecureContentState.ALLOCATED) {
            "Secure content allocation must start in ALLOCATED"
        }
        require(metadata.cryptoVersion == null || metadata.cryptoVersion == CRYPTO_VERSION) {
            "Unsupported crypto version"
        }
        check(metadataStore.find(metadata.accountUuid, metadata.contentId) == null) {
            "Secure content identity is already allocated"
        }
        val record = metadataStore.create(
            metadata.copy(cryptoVersion = CRYPTO_VERSION),
            storageLocator = null,
        )
        return SecureContentHandle(record.accountUuid, record.contentId, record.metadata.mimeType)
    }

    @Synchronized
    override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession {
        val cryptoEpoch = ensureCryptoReady()
        requireRegisteredAccount(handle.accountUuid)
        val record = requireRecord(handle)
        check(record.metadata.state == SecureContentState.ALLOCATED) {
            "Secure content is not allocated for writing"
        }
        check(metadataStore.updateState(handle.accountUuid, handle.contentId, SecureContentState.WRITING)) {
            "Unable to transition secure content to WRITING"
        }
        val context = contextFor(record.metadata)
        val keySession = keyMaterialStore.beginWrite(context).orThrow("Unable to allocate key material")
        try {
            val blobSession = blobStore.put().orThrow("Unable to allocate protected storage")
            try {
                val writer = cryptoEngine.beginWrite(
                    context,
                    keySession.writerHandle,
                    blobSession.protectedSink,
                ).orThrow("Unable to start protected write")
                return WriteSession(
                    handle,
                    context,
                    keySession,
                    blobSession,
                    writer,
                    cryptoEpoch,
                )
            } catch (error: Exception) {
                blobSession.abort()
                throw error
            }
        } catch (error: Exception) {
            keySession.abort()
            metadataStore.updateState(handle.accountUuid, handle.contentId, SecureContentState.FAILED)
            throw error
        }
    }

    override fun find(accountUuid: String, contentId: String): SecureContentObject? {
        try {
            ensureCryptoReady()
        } catch (_: Exception) {
            return null
        }
        if (!isRegisteredAccount(accountUuid)) return null
        return metadataStore.find(accountUuid, contentId)?.let { SecureContentObject(it.metadata) }
    }

    override fun findByMessage(
        accountUuid: String,
        messageUuid: String,
    ): List<SecureContentObject> {
        try {
            ensureCryptoReady()
        } catch (_: Exception) {
            return emptyList()
        }
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        if (!isRegisteredAccount(accountUuid)) return emptyList()
        return metadataStore.findByMessageUuid(accountUuid, messageUuid)
            .map { SecureContentObject(it.metadata) }
    }

    override fun findByAccount(accountUuid: String): List<SecureContentObject> {
        try {
            ensureCryptoReady()
        } catch (_: Exception) {
            return emptyList()
        }
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        if (!isRegisteredAccount(accountUuid)) return emptyList()
        return metadataStore.findByAccount(accountUuid).map { SecureContentObject(it.metadata) }
    }

    override fun committedStorageSizeBytes(accountUuid: String): Long? {
        try {
            ensureCryptoReady()
        } catch (_: Exception) {
            return null
        }
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        if (!isRegisteredAccount(accountUuid)) return null

        var total = 0L
        for (record in metadataStore.findByAccount(accountUuid)) {
            if (record.metadata.state != SecureContentState.COMMITTED) continue
            val locator = record.storageLocator ?: continue
            val size = blobStore.committedSize(blobStore.reference(locator)) ?: continue
            total = if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
        }
        return total
    }

    override fun open(accountUuid: String, contentId: String): SecureContentReadSession =
        openInternal(accountUuid, contentId, keyReadScope = null)

    internal fun beginReadScope(accountUuid: String): SecureContentScopedRead {
        val cryptoEpoch = ensureCryptoReady()
        requireRegisteredAccount(accountUuid)
        return ScopedRead(
            accountUuid = accountUuid,
            cryptoEpoch = cryptoEpoch,
            keyReadScope = keyMaterialStore.beginReadScope(accountUuid),
            metadataReadScope = metadataStore.beginReadScope(accountUuid),
        )
    }

    private fun openInternal(
        accountUuid: String,
        contentId: String,
        keyReadScope: PersistentSecureContentKeyMaterialStore.ContentReadScope?,
        metadataReadScope: AccountProtectedSecureContentMetadataStore.MetadataReadScope? = null,
    ): SecureContentReadSession {
        val cryptoEpoch = ensureCryptoReady()
        if (!isRegisteredAccount(accountUuid)) {
            throw IOException("Secure content is unavailable")
        }
        val record =
            if (metadataReadScope != null) {
                metadataReadScope.find(contentId)
            } else {
                metadataStore.find(accountUuid, contentId)
            } ?: throw IOException("Secure content is unavailable")
        if (!record.metadata.state.isConsumerVisible || record.storageLocator == null) {
            throw IOException("Secure content is unavailable")
        }
        val context = contextFor(record.metadata)
        if (recoveryStore.hasUnresolved(context)) {
            throw IOException("Secure content is unavailable")
        }
        val keyResult =
            keyReadScope?.resolveForRead(context)
                ?: keyMaterialStore.resolveForRead(context)
        val key = keyResult.orThrow("Secure content is unavailable")
        val reference = blobStore.reference(record.storageLocator)
        val source = blobStore.get(reference).orThrow("Secure content is unavailable")
        val reader = cryptoEngine.openRead(context, key, source).orThrow("Secure content is unavailable")
        return ReadSession(
            handle = SecureContentHandle(accountUuid, contentId, record.metadata.mimeType),
            context = context,
            reader = reader,
            cryptoEpoch = cryptoEpoch,
        )
    }

    @Synchronized
    override fun delete(accountUuid: String, contentId: String) {
        try {
            ensureCryptoReady()
        } catch (_: Exception) {
            return
        }
        if (!isRegisteredAccount(accountUuid)) return
        val record = metadataStore.find(accountUuid, contentId) ?: return
        val context = contextFor(record.metadata)
        var recovery = recoveryStore.create(
            SecureContentRecoveryOperation.DELETE,
            context,
            record.storageLocator,
        )
        keyMaterialStore.invalidate(context)
            .orThrow("Unable to invalidate secure content key material")
        recovery = recoveryStore.advance(recovery, SecureContentRecoveryPhase.KEY_INVALIDATED)
        record.storageLocator?.let {
            val reference = blobStore.reference(it)
            blobStore.discardStaged(reference)
            blobStore.delete(reference).orThrow("Unable to retire protected bytes")
        }
        recovery = recoveryStore.advance(recovery, SecureContentRecoveryPhase.BLOB_RETIRED)
        check(metadataStore.deleteMetadata(accountUuid, contentId)) {
            "Unable to retire secure content metadata"
        }
        recovery = recoveryStore.advance(recovery, SecureContentRecoveryPhase.METADATA_RETIRED)
        check(recoveryStore.remove(recovery)) { "Unable to retire secure content recovery record" }
    }

    /**
     * Fail-closed v1 recovery for explicitly journaled transactions only.
     *
     * Normal cold start must not enumerate every committed object. Unjournaled/non-terminal
     * metadata remains consumer-invisible, while committed objects are revalidated when opened.
     */
    @Synchronized
    fun recoverInterruptedWrites() {
        val recoveryRecords = recoveryStore.allRecords()
        SecureColdStartPerfTrace.increment(
            "store_recovery_records",
            recoveryRecords.size.toLong(),
        )
        recoveryRecords.forEach(::recoverRecord)

        val incompleteRecords = metadataStore.incompleteRecords()
        SecureColdStartPerfTrace.increment(
            "store_recovery_incomplete_records",
            incompleteRecords.size.toLong(),
        )
        incompleteRecords.forEach(::recoverUnjournaledRecord)
    }

    /**
     * Compatibility maintenance for pre-routing-index stores. It is deliberately invoked by the
     * service after the critical cold-start trace, never by normal lookup.
     */
    @Synchronized
    internal fun backfillMessageRelationIndexIfNeeded(): Boolean {
        ensureCryptoReady()
        val rebuilt = metadataStore.backfillMessageRelationIndexIfNeeded()
        if (rebuilt) {
            // Pre-index stores could contain ALLOCATED/WRITING attempts that had no incomplete
            // marker. Recover only objects allocated before this Runtime instance existed; current
            // process writes must never be mistaken for crash leftovers.
            metadataStore
                .incompleteRecords(createdBeforeExclusive = runtimeCreatedAtMillis)
                .forEach(::recoverUnjournaledRecord)
        }
        return rebuilt
    }

    private fun recoverUnjournaledRecord(record: SecureContentMetadataRecord) {
        if (!record.metadata.state.isConsumerVisible) {
            val context =
                try {
                    contextFor(record.metadata)
                } catch (_: Exception) {
                    null
                }
            val keyRetired =
                context == null ||
                    keyMaterialStore.stateFor(context) == null ||
                    keyMaterialStore.invalidate(context).isSuccess()
            val blobRetired =
                retireForRecovery(record.storageLocator?.let(blobStore::reference))
            if (keyRetired && blobRetired) {
                metadataStore.updateState(
                    record.accountUuid,
                    record.contentId,
                    SecureContentState.FAILED,
                )
            }
        }
    }

    private fun recoverRecord(record: SecureContentRecoveryRecord) {
        val context = record.context
        val metadata = metadataStore.find(context.accountUuid, context.contentId)
        val reference = record.blobIdentifier?.let(blobStore::reference)
        if (record.operation == SecureContentRecoveryOperation.COMMIT) {
            val provenCommitted = metadata?.metadata?.state == SecureContentState.COMMITTED &&
                metadata.storageLocator == record.blobIdentifier &&
                reference?.let { blobStore.exists(it).valueOrNull() } == true &&
                keyMaterialStore.stateFor(context) == SecureContentKeyMaterialState.ACTIVE
            if (provenCommitted) {
                recoveryStore.remove(record)
                return
            }
            val keyInvalidated = keyMaterialStore.invalidate(context).isSuccess()
            val blobRetired = retireForRecovery(reference)
            val metadataFailed = metadata == null || metadataStore.updateState(
                context.accountUuid,
                context.contentId,
                SecureContentState.FAILED,
            )
            if (keyInvalidated && blobRetired && metadataFailed) {
                recoveryStore.remove(record)
            }
            return
        }
        val keyInvalidated = keyMaterialStore.invalidate(context).isSuccess()
        val blobRetired = retireForRecovery(reference)
        val metadataRetired = metadata == null ||
            metadataStore.deleteMetadata(context.accountUuid, context.contentId)
        if (keyInvalidated && blobRetired && metadataRetired) {
            recoveryStore.remove(record)
        }
    }

    private fun retireForRecovery(reference: SecureContentBlobReference?): Boolean {
        if (reference == null) return true
        blobStore.discardStaged(reference)
        return blobStore.delete(reference).isSuccess()
    }

    private fun requireRecord(handle: SecureContentHandle): SecureContentMetadataRecord {
        requireRegisteredAccount(handle.accountUuid)
        return metadataStore.find(handle.accountUuid, handle.contentId)
            ?: throw IllegalArgumentException("Unknown secure content handle")
    }

    private fun isRegisteredAccount(accountUuid: String): Boolean =
        accountUuid.isNotBlank() && accountAuthority.isRegisteredAccount(accountUuid)

    private fun requireRegisteredAccount(accountUuid: String) {
        require(isRegisteredAccount(accountUuid)) {
            "Secure content account is unavailable"
        }
    }

    private fun contextFor(metadata: SecureContentMetadata): SecureContentCryptoContext =
        SecureContentCryptoContext(
            namespace = metadata.namespace,
            cryptoVersion = metadata.cryptoVersion ?: throw IllegalStateException(
                "Secure content crypto version is unavailable",
            ),
            accountUuid = metadata.accountUuid,
            contentId = metadata.contentId,
        )

    private inner class ScopedRead(
        private val accountUuid: String,
        private val cryptoEpoch: Long?,
        private val keyReadScope: PersistentSecureContentKeyMaterialStore.ContentReadScope,
        private val metadataReadScope: AccountProtectedSecureContentMetadataStore.MetadataReadScope,
    ) : SecureContentScopedRead {
        private var closed = false

        override fun find(contentId: String): SecureContentObject? =
            synchronized(this@RuntimeSecureContentStore) {
                check(!closed) { "Secure content read scope is closed" }
                if (!keyMaterialStore.isCryptoEpochValid(cryptoEpoch)
                    || !isRegisteredAccount(accountUuid)
                ) {
                    return@synchronized null
                }
                metadataReadScope.find(contentId)?.let { SecureContentObject(it.metadata) }
            }

        override fun open(contentId: String): SecureContentReadSession =
            synchronized(this@RuntimeSecureContentStore) {
                check(!closed) { "Secure content read scope is closed" }
                openInternal(
                    accountUuid,
                    contentId,
                    keyReadScope,
                    metadataReadScope,
                )
            }

        override fun close() {
            synchronized(this@RuntimeSecureContentStore) {
                if (!closed) {
                    metadataReadScope.close()
                    keyReadScope.close()
                    closed = true
                }
            }
        }
    }

    private inner class ReadSession(
        override val handle: SecureContentHandle,
        private val context: SecureContentCryptoContext,
        private val reader: SecureContentCryptoReader,
        private val cryptoEpoch: Long?,
    ) : SecureContentReadSession {
        override var state: SecureContentReadState = SecureContentReadState.OPEN
            private set

        private var streamOpened = false
        private var terminalReached = false
        private var terminalFailure: IOException? = null

        override fun openPlaintextInputStream(): InputStream {
            check(state == SecureContentReadState.OPEN) { "Secure content reader is not active" }
            check(!streamOpened) { "Secure content plaintext stream was already opened" }
            streamOpened = true
            return object : FilterInputStream(reader.openPlaintextInputStream()) {
                override fun read(): Int = observeTerminal { super.read() }

                override fun read(buffer: ByteArray): Int =
                    observeTerminal { super.read(buffer) }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    observeTerminal { super.read(buffer, offset, length) }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        reader.close()
                        if (!terminalReached && state == SecureContentReadState.OPEN) {
                            state = SecureContentReadState.FAILED
                        }
                    }
                }

                private fun observeTerminal(read: () -> Int): Int =
                    try {
                        if (!keyMaterialStore.isCryptoEpochValid(cryptoEpoch)) {
                            state = SecureContentReadState.FAILED
                            throw IOException("Secure content crypto session is locked")
                        }
                        read().also {
                            if (it == -1) {
                                terminalReached = true
                            }
                        }
                    } catch (error: IOException) {
                        terminalFailure = error
                        state = SecureContentReadState.FAILED
                        throw error
                    }
            }
        }

        override fun verifyTerminal(): SecureContentObject {
            check(streamOpened) { "Secure content plaintext stream was not opened" }
            terminalFailure?.let { throw IOException("Secure content verification failed", it) }
            if (!terminalReached) {
                state = SecureContentReadState.FAILED
                reader.close()
                throw IOException("Secure content stream did not reach authenticated terminal")
            }
            if (state == SecureContentReadState.FAILED) {
                throw IOException("Secure content verification failed")
            }
            val objectSnapshot = synchronized(this@RuntimeSecureContentStore) {
                if (!keyMaterialStore.isCryptoEpochValid(cryptoEpoch) ||
                    !isRegisteredAccount(handle.accountUuid) ||
                    recoveryStore.hasUnresolved(context) ||
                    keyMaterialStore.stateFor(context) != SecureContentKeyMaterialState.ACTIVE
                ) {
                    null
                } else {
                    find(handle.accountUuid, handle.contentId)
                        ?.takeIf { it.state == SecureContentState.COMMITTED }
                }
            } ?: run {
                state = SecureContentReadState.FAILED
                reader.close()
                throw IOException("Secure content is unavailable")
            }
            reader.close()
            state = SecureContentReadState.TERMINAL_VERIFIED
            return objectSnapshot
        }

        override fun close() {
            try {
                reader.close()
            } finally {
                if (state == SecureContentReadState.OPEN) {
                    state = SecureContentReadState.CLOSED
                }
            }
        }
    }

    private inner class WriteSession(
        override val handle: SecureContentHandle,
        private val context: SecureContentCryptoContext,
        private val keySession: SecureContentKeyMaterialWriteSession,
        private val blobSession: SecureContentBlobWriteSession,
        private val cryptoWriter: SecureContentCryptoWriter,
        private val cryptoEpoch: Long?,
    ) : SecureContentWriteSession {
        override var state: SecureContentState = SecureContentState.WRITING
            private set
        private var plaintextOutputOpened = false

        override fun openPlaintextOutputStream(): OutputStream {
            check(state == SecureContentState.WRITING) { "Secure content writer is not active" }
            check(keyMaterialStore.isCryptoEpochValid(cryptoEpoch)) {
                "Secure content crypto session is locked"
            }
            check(!plaintextOutputOpened) { "Secure content plaintext stream was already opened" }
            plaintextOutputOpened = true
            val delegate = cryptoWriter.openPlaintextOutputStream()
            return object : java.io.FilterOutputStream(delegate) {
                override fun write(value: Int) {
                    checkEpoch()
                    super.write(value)
                }

                override fun write(buffer: ByteArray) {
                    checkEpoch()
                    super.write(buffer)
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    checkEpoch()
                    super.write(buffer, offset, length)
                }

                private fun checkEpoch() {
                    if (!keyMaterialStore.isCryptoEpochValid(cryptoEpoch)) {
                        throw IOException("Secure content crypto session is locked")
                    }
                }
            }
        }

        override fun finishAndBeginCommit(): SecureContentCommitTransaction {
            check(state == SecureContentState.WRITING) { "Secure content writer is not active" }
            check(keyMaterialStore.isCryptoEpochValid(cryptoEpoch)) {
                "Secure content crypto session is locked"
            }
            val cryptoCandidate = cryptoWriter.finish().orThrow("Unable to finalize protected write")
            val blobCandidate = blobSession.prepareCommit().orThrow("Unable to prepare protected bytes")
            val keyCandidate = keySession.prepareCommit().orThrow("Unable to prepare key material")
            return beginCommit(
                SecureContentCommitPayload(
                    cryptoCandidate = cryptoCandidate,
                    blobCandidate = blobCandidate,
                    keyMaterialCandidate = keyCandidate,
                ),
            )
        }

        override fun beginCommit(payload: SecureContentCommitPayload): SecureContentCommitTransaction {
            check(state == SecureContentState.WRITING) { "Secure content writer is not active" }
            check(keyMaterialStore.isCryptoEpochValid(cryptoEpoch)) {
                "Secure content crypto session is locked"
            }
            check(payload.context == context) { "Secure content candidates do not match this write" }
            val reference = blobStore.referenceFor(payload.blobCandidate)
                ?: throw IllegalArgumentException("Secure content blob candidate is not recognized")
            val locator = blobStore.identifier(reference)
                ?: throw IllegalArgumentException("Secure content blob reference is not recognized")
            val recovery = recoveryStore.create(
                SecureContentRecoveryOperation.COMMIT,
                context,
                locator,
            )
            check(metadataStore.updateStorageLocator(handle.accountUuid, handle.contentId, locator)) {
                "Unable to prepare secure content metadata"
            }
            check(metadataStore.updateState(handle.accountUuid, handle.contentId, SecureContentState.READY_TO_COMMIT)) {
                "Unable to transition secure content to READY_TO_COMMIT"
            }
            state = SecureContentState.READY_TO_COMMIT
            return CommitTransaction(
                handle,
                context,
                payload,
                reference,
                recovery,
                cryptoEpoch,
            )
        }

        override fun abort() {
            if (state == SecureContentState.COMMITTED || state == SecureContentState.ABORTED) return
            cryptoWriter.abort()
            blobSession.abort()
            keySession.abort()
            metadataStore.updateState(handle.accountUuid, handle.contentId, SecureContentState.ABORTED)
            state = SecureContentState.ABORTED
        }
    }

    private inner class CommitTransaction(
        override val handle: SecureContentHandle,
        private val context: SecureContentCryptoContext,
        private val payload: SecureContentCommitPayload,
        private val reference: SecureContentBlobReference,
        private var recovery: SecureContentRecoveryRecord,
        private val cryptoEpoch: Long?,
    ) : SecureContentCommitTransaction {
        override var state: SecureContentState = SecureContentState.READY_TO_COMMIT
            private set

        override fun commit(): SecureContentObject {
            check(state == SecureContentState.READY_TO_COMMIT) {
                "Secure content commit transaction is not active"
            }
            if (!keyMaterialStore.isCryptoEpochValid(cryptoEpoch)) {
                abort()
                throw IOException("Secure content crypto session is locked")
            }
            if (!isRegisteredAccount(handle.accountUuid)) {
                abort()
                throw IOException("Secure content account is unavailable")
            }
            try {
                blobStore.commit(payload.blobCandidate).orThrow("Unable to commit protected bytes")
                recovery = recoveryStore.advance(recovery, SecureContentRecoveryPhase.BLOB_COMMITTED)
                keyMaterialStore.activate(payload.keyMaterialCandidate)
                    .orThrow("Unable to activate key material")
                recovery = recoveryStore.advance(recovery, SecureContentRecoveryPhase.KEY_ACTIVATED)
                check(metadataStore.updateState(
                    handle.accountUuid,
                    handle.contentId,
                    SecureContentState.COMMITTED,
                )) {
                    "Unable to publish secure content metadata"
                }
                recovery = recoveryStore.advance(recovery, SecureContentRecoveryPhase.METADATA_COMMITTED)
                check(recoveryStore.remove(recovery)) { "Unable to retire secure content recovery record" }
                state = SecureContentState.COMMITTED
                return find(handle.accountUuid, handle.contentId)
                    ?: throw IllegalStateException("Committed secure content is unavailable")
            } catch (error: Exception) {
                keyMaterialStore.invalidate(context)
                metadataStore.updateState(handle.accountUuid, handle.contentId, SecureContentState.FAILED)
                state = SecureContentState.FAILED
                throw error
            }
        }

        override fun abort() {
            if (state != SecureContentState.READY_TO_COMMIT) return
            val keyInvalidated = keyMaterialStore.invalidate(context).isSuccess()
            val blobRetired = retireForRecovery(reference)
            val metadataAborted = metadataStore.updateState(
                handle.accountUuid,
                handle.contentId,
                SecureContentState.ABORTED,
            )
            if (keyInvalidated && blobRetired && metadataAborted) {
                recoveryStore.remove(recovery)
                state = SecureContentState.ABORTED
            } else {
                state = SecureContentState.FAILED
            }
        }
    }

    private fun <T> SecureContentCryptoResult<T>.orThrow(message: String): T = when (this) {
        is SecureContentCryptoResult.Success -> value
        is SecureContentCryptoResult.Failure -> throw IOException(message)
    }

    private fun <T> SecureContentKeyMaterialResult<T>.orThrow(message: String): T = when (this) {
        is SecureContentKeyMaterialResult.Success -> value
        is SecureContentKeyMaterialResult.Failure -> throw IOException(message)
    }

    private fun <T> SecureContentKeyMaterialResult<T>.isSuccess(): Boolean =
        this is SecureContentKeyMaterialResult.Success

    private fun <T> SecureContentBlobResult<T>.orThrow(message: String): T = when (this) {
        is SecureContentBlobResult.Success -> value
        is SecureContentBlobResult.Failure -> throw IOException(message)
    }

    private fun <T> SecureContentBlobResult<T>.valueOrNull(): T? = when (this) {
        is SecureContentBlobResult.Success -> value
        is SecureContentBlobResult.Failure -> null
    }

    private fun <T> SecureContentBlobResult<T>.isSuccess(): Boolean =
        this is SecureContentBlobResult.Success

    private companion object {
        const val CRYPTO_VERSION = 1
    }
}
