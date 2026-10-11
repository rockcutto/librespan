package eu.siacs.conversations.storage.secure

import android.os.SystemClock
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.persistance.DatabaseBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.function.BooleanSupplier

data class SecureMessageSearchHydrationResult(
    val attempted: Int,
    val verified: Int,
    val failed: Int,
    val cancelled: Boolean,
)

/**
 * Production owner for protected text publication, verified presentation and bounded read cache.
 *
 * The Message DB body is cleared before initial publication. A protected Message receives text
 * only through [Message.setVerifiedProtectedBody] after a complete Store read and terminal
 * verification. Cache eviction drops only the read accelerator; it never invalidates an already
 * verified live Message presentation. RecyclerView binding never initializes or opens the Store.
 */
class SecureMessageTextRepository @JvmOverloads constructor(
    private val coordinator: SecureMessagePayloadCoordinator,
    private val databaseBackend: DatabaseBackend,
    private val maximumCachedMessages: Int = DEFAULT_CACHE_SIZE,
) {
    init {
        require(maximumCachedMessages > 0)
    }

    private data class CacheKey(
        val accountUuid: String,
        val messageUuid: String,
        val contentId: String,
    )

    private data class CacheEntry(
        val text: String,
        val message: WeakReference<Message>,
    )

    private val previewCache: LinkedHashMap<CacheKey, CacheEntry> =
        object : LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>,
            ): Boolean =
                size > DEFAULT_PREVIEW_CACHE_SIZE
        }

    private val cache: LinkedHashMap<CacheKey, CacheEntry> =
        object : LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>,
        ): Boolean {
            val remove = size > maximumCachedMessages
            if (remove) {
                val message = eldest.value.message.get()
                if (message != null && isConversationPreview(message)) {
                    previewCache[eldest.key] =
                        CacheEntry(eldest.value.text, WeakReference(message))
                }
                // Cache eviction is not presentation invalidation. A live Message owns its
                // already-verified transient plaintext until an explicit lifecycle/security
                // invalidation clears it or the Message itself becomes unreachable.
            }
            return remove
        }
    }

    private fun isConversationPreview(message: Message): Boolean {
        return try {
            val conversation = message.conversation as? Conversation
            conversation != null && conversation.getLatestMessage() === message
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Persists a new incoming or outgoing text Message without its plaintext body, then publishes
     * and verifies the exact protected payload. The caller may send/render only after success.
     */
    @Synchronized
    @Throws(IOException::class)
    fun publishInitial(message: Message): SecureMessagePayloadReference {
        check(!message.hasProtectedTextPayload()) {
            "Protected text was already selected"
        }
        val plaintext = message.bodyForSecurePublication
            ?: throw IOException("Protected text plaintext is unavailable")
        val utf8 = encodeForPublication(plaintext)
        val accountUuid = message.conversation.account.uuid
        try {
            check(databaseBackend.createProtectedTextMessage(message, accountUuid)) {
                "Unable to persist protected text metadata"
            }
            return try {
                publishAndVerify(message, utf8)
            } catch (error: Exception) {
                message.secureMessagePayloadMode =
                    databaseBackend.getSecureMessagePayloadMode(accountUuid, message.uuid)
                        ?: SecureMessagePayloadMode.UNAVAILABLE
                throw if (error is IOException) error else IOException(
                    "Unable to publish protected text",
                    error,
                )
            }
        } finally {
            // Covers failures before publishAndVerify() takes ownership of the mutable buffer.
            utf8.fill(0)
        }
    }

    /**
     * Replaces a protected payload for the same logical Message identity. The coordinator keeps
     * the old committed object until the new relation and secure search tokens are durable.
     */
    @Synchronized
    @Throws(IOException::class)
    fun replace(message: Message): SecureMessagePayloadReference {
        check(message.secureMessagePayloadMode == SecureMessagePayloadMode.PROTECTED) {
            "Protected text replacement requires a committed payload"
        }
        val plaintext = message.bodyForSecurePublication
            ?: throw IOException("Protected replacement plaintext is unavailable")
        val utf8 = encodeForPublication(plaintext)
        try {
            invalidate(message.conversation.account.uuid, message.uuid)
            return publishAndVerify(message, utf8)
        } finally {
            // Covers failures before publishAndVerify() takes ownership of the mutable buffer.
            utf8.fill(0)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun publishAndVerify(
        message: Message,
        plaintext: ByteArray,
    ): SecureMessagePayloadReference {
        val context = contextOf(message)
        val session = coordinator.beginProtectedTextWrite(context)
        val publishStartedAt = SystemClock.elapsedRealtime()
        var completed = false
        try {
            val writeStartedAt = SystemClock.elapsedRealtime()
            session.openPlaintextOutputStream().use { output ->
                // Avoid InputStream.copyTo(): it allocates an internal plaintext copy buffer that
                // the caller cannot zeroize. RuntimeSecureContentStore validates the crypto epoch
                // on write(ByteArray), so write the owned mutable buffer directly.
                output.write(plaintext)
            }
            SecureTextPayload.logStage(plaintext.size, "store_write", writeStartedAt)
            val commitStartedAt = SystemClock.elapsedRealtime()
            session.commitAndPublish(plaintext)
            SecureTextPayload.logStage(plaintext.size, "store_commit", commitStartedAt)
            completed = true
            message.secureMessagePayloadMode = SecureMessagePayloadMode.PROTECTED
            return readAndCache(message)
        } catch (error: Exception) {
            val category = if (error is IOException) "io" else "runtime"
            SecureTextPayload.logStage(plaintext.size, "store_publish", publishStartedAt, category)
            throw error
        } finally {
            if (!completed) {
                try {
                    session.abort()
                } catch (_: Exception) {
                    // Durable coordinator evidence remains authoritative.
                }
            }
            // This UTF-8 publication buffer is mutable and owned by this call. The resident
            // presentation String cannot be zeroized on ART, but no plaintext byte copy needs to
            // survive publication/verification.
            plaintext.fill(0)
        }
    }

    /**
     * Loads classification using one metadata query, then decrypts only the bounded loaded page.
     * Callers invoke this on a repository/database worker before publishing the page to UI.
     */
    fun hydrateLoadedPage(messages: List<Message>) {
        hydratePage(messages, pinPreview = false)
    }

    fun hydrateConversationPreviews(messages: List<Message>) {
        hydratePage(messages, pinPreview = true)
    }

    private fun hydratePage(
        messages: List<Message>,
        pinPreview: Boolean,
    ) {
        if (messages.isEmpty()) return
        SecureColdStartPerfTrace.increment("secure_text_pages", 1)
        SecureColdStartPerfTrace.increment("secure_text_candidates", messages.size.toLong())
        val byAccount = messages.groupBy { it.conversation.account.uuid }
        byAccount.forEach { (accountUuid, accountMessages) ->
            val modeLoadStarted = System.nanoTime()
            databaseBackend.loadSecureMessagePayloadModes(accountUuid, accountMessages)
            SecureColdStartPerfTrace.stage(
                "secure_text_mode_load",
                System.nanoTime() - modeLoadStarted,
            )
            val protectedMessages =
                accountMessages.filter {
                    !it.isRetracted &&
                        it.secureMessagePayloadMode == SecureMessagePayloadMode.PROTECTED
                }
            SecureColdStartPerfTrace.increment(
                "secure_text_protected",
                protectedMessages.size.toLong(),
            )

            // Preview-first cold start may hand this repository the exact same resident Message
            // again when the full conversation page is restored. Its body has already reached
            // authenticated terminal verification, so repeating the repository/coordinator path
            // adds DB/reference work without strengthening the security decision.
            val alreadyVerified =
                protectedMessages.count { it.hasVerifiedProtectedBody() }
            if (alreadyVerified > 0) {
                SecureColdStartPerfTrace.increment(
                    "secure_text_verified_reused",
                    alreadyVerified.toLong(),
                )
            }
            if (pinPreview) {
                protectedMessages
                    .asSequence()
                    .filter { it.hasVerifiedProtectedBody() }
                    .forEach { pinVerifiedPreview(it) }
            }
            val messagesToRead =
                protectedMessages.filterNot { it.hasVerifiedProtectedBody() }
            SecureColdStartPerfTrace.increment(
                "secure_text_reads",
                messagesToRead.size.toLong(),
            )

            if (messagesToRead.isNotEmpty()) {
                val readStarted = System.nanoTime()
                val planStarted = System.nanoTime()
                val readPlan =
                    (coordinator as? DatabaseSecureMessagePayloadCoordinator)
                        ?.beginProtectedTextReadPlan(
                            accountUuid,
                            messagesToRead.map { it.uuid },
                        )
                SecureColdStartPerfTrace.stage(
                    "secure_text_read_plan",
                    System.nanoTime() - planStarted,
                )
                if (readPlan != null) {
                    SecureColdStartPerfTrace.increment("secure_text_read_scopes", 1)
                }

                // Busy MUC catch-up can overlap with cache eviction and read-plan lifetime changes.
                // A single transient miss used to leave the resident Message body-less until the
                // conversation was reopened. Keep the first pass batched, then retry only the
                // failed identities once through a fresh per-message lookup/open path. This stays
                // fail-closed: no durable/plaintext fallback is introduced, and a second failure
                // still leaves the protected body unavailable.
                val failedReads = ArrayList<Message>()
                try {
                    messagesToRead.forEach {
                        try {
                            readAndCache(it, readPlan, pinPreview)
                        } catch (_: Exception) {
                            SecureColdStartPerfTrace.increment("secure_text_read_failures", 1)
                            it.clearVerifiedProtectedBody()
                            failedReads.add(it)
                        }
                    }
                } finally {
                    readPlan?.close()
                }

                if (failedReads.isNotEmpty()) {
                    SecureColdStartPerfTrace.increment(
                        "secure_text_read_retry_attempts",
                        failedReads.size.toLong(),
                    )
                    var recovered = 0L
                    var retryFailures = 0L
                    failedReads.forEach {
                        try {
                            // Deliberately bypass the old shared read plan. Re-resolve the durable
                            // relation and open a fresh verified session for this identity.
                            readAndCache(it, readPlan = null, pinPreview = pinPreview)
                            recovered += 1
                        } catch (_: Exception) {
                            retryFailures += 1
                            it.clearVerifiedProtectedBody()
                        }
                    }
                    if (recovered > 0) {
                        SecureColdStartPerfTrace.increment(
                            "secure_text_read_retry_recovered",
                            recovered,
                        )
                    }
                    if (retryFailures > 0) {
                        SecureColdStartPerfTrace.increment(
                            "secure_text_read_retry_failures",
                            retryFailures,
                        )
                    }
                }

                SecureColdStartPerfTrace.stage(
                    "secure_text_read_verify",
                    System.nanoTime() - readStarted,
                )
            }
        }
    }

    /**
     * Search verification keeps plaintext on the short-lived result Message only and does not add
     * those objects to the presentation LRU. This prevents one 300-result search from evicting its
     * own earlier results or unrelated resident conversation messages.
     */
    @JvmOverloads
    fun hydrateSearchPage(
        messages: List<Message>,
        cancelled: BooleanSupplier = BooleanSupplier { false },
    ): SecureMessageSearchHydrationResult {
        if (messages.isEmpty()) {
            return SecureMessageSearchHydrationResult(0, 0, 0, cancelled.getAsBoolean())
        }
        var attempted = 0
        var verified = 0
        var failed = 0
        val byAccount = messages.groupBy { it.conversation.account.uuid }
        byAccount.forEach accountLoop@{ (accountUuid, accountMessages) ->
            if (cancelled.getAsBoolean()) {
                return SecureMessageSearchHydrationResult(attempted, verified, failed, true)
            }
            try {
                databaseBackend.loadSecureMessagePayloadModes(accountUuid, accountMessages)
            } catch (_: Exception) {
                accountMessages.forEach { it.clearVerifiedProtectedBody() }
                attempted += accountMessages.size
                failed += accountMessages.size
                return@accountLoop
            }
            accountMessages
                .asSequence()
                .filter {
                    !it.isRetracted &&
                        it.secureMessagePayloadMode == SecureMessagePayloadMode.PROTECTED
                }
                .forEach {
                    if (cancelled.getAsBoolean()) {
                        return SecureMessageSearchHydrationResult(attempted, verified, failed, true)
                    }
                    attempted += 1
                    try {
                        readForSearch(it)
                        verified += 1
                    } catch (_: Exception) {
                        failed += 1
                        it.clearVerifiedProtectedBody()
                    }
                }
        }
        return SecureMessageSearchHydrationResult(attempted, verified, failed, false)
    }

    /**
     * Returns legacy text only for an unclassified message. A body stays available only while its
     * verified cache entry owns the Message; otherwise it is read and terminally verified again.
     */
    fun resolveForTransport(message: Message): String? {
        try {
            rejectDurablyRetracted(message)
        } catch (_: IOException) {
            return null
        }
        val mode = message.secureMessagePayloadMode
        if (mode == null) return message.body
        if (mode != SecureMessagePayloadMode.PROTECTED) return null
        if (message.hasVerifiedProtectedBody()) return message.body
        return try {
            readAndCache(message)
            message.body
        } catch (_: Exception) {
            message.clearVerifiedProtectedBody()
            null
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun hydrate(message: Message): Boolean {
        if (message.isRetracted ||
            message.secureMessagePayloadMode != SecureMessagePayloadMode.PROTECTED) {
            return false
        }
        readAndCache(message)
        return true
    }

    data class LegacyLoadedPageMigrationResult(
        val selected: Int,
        val migrated: Int,
        val skipped: Int,
        val failed: Int,
        val refreshed: Int,
    )

    /**
     * Migrates only legacy plaintext already present in this loaded page.
     *
     * No database sweep or OFFSET scan is performed. At most [maximumMessages] resident Messages
     * are attempted, and every successful durable migration is read back through terminal SCS
     * verification before the resident Message is atomically switched to protected presentation.
     */
    fun migrateLegacyLoadedPage(
        messages: List<Message>,
        maximumMessages: Int = DEFAULT_LAZY_MIGRATION_BATCH_SIZE,
    ): LegacyLoadedPageMigrationResult {
        require(maximumMessages in 1..MAXIMUM_LAZY_MIGRATION_BATCH_SIZE) {
            "legacy loaded-page migration batch is out of bounds"
        }
        val candidates =
            messages
                .asSequence()
                .filter { it.hasLegacyPlaintextBody() }
                .take(maximumMessages)
                .toList()
        if (candidates.isEmpty()) {
            return LegacyLoadedPageMigrationResult(0, 0, 0, 0, 0)
        }

        val migrator = LegacyPlaintextMessageMigrator(databaseBackend, coordinator)
        var migrated = 0
        var skipped = 0
        var failed = 0
        var refreshed = 0
        candidates.forEach { message ->
            val accountUuid = message.conversation.account.uuid
            val expectedBody = message.bodyForSecurePublication
            if (expectedBody.isNullOrEmpty()) {
                skipped += 1
                return@forEach
            }
            when (
                migrator.migrateLoadedMessage(
                    accountUuid = accountUuid,
                    messageUuid = message.uuid,
                    expectedBody = expectedBody,
                )
            ) {
                LegacyPlaintextMessageMigrator.ItemResult.MIGRATED -> migrated += 1
                LegacyPlaintextMessageMigrator.ItemResult.SKIPPED -> skipped += 1
                LegacyPlaintextMessageMigrator.ItemResult.FAILED -> {
                    failed += 1
                    return@forEach
                }
            }

            if (databaseBackend.getSecureMessagePayloadMode(accountUuid, message.uuid) ==
                SecureMessagePayloadMode.PROTECTED
            ) {
                // Durable classification is authoritative immediately. Clearing the resident
                // legacy body before read-back prevents any later includeBody update from
                // re-persisting plaintext if SCS verification temporarily fails.
                message.secureMessagePayloadMode = SecureMessagePayloadMode.PROTECTED
                try {
                    readVerified(
                        message = message,
                        cacheResult = true,
                        readPlan = null,
                        promoteLegacy = false,
                    )
                    refreshed += 1
                } catch (_: Exception) {
                    // Fail closed in-memory. A future normal page hydration retries the SCS read.
                    message.clearVerifiedProtectedBody()
                }
            }
        }
        return LegacyLoadedPageMigrationResult(
            selected = candidates.size,
            migrated = migrated,
            skipped = skipped,
            failed = failed,
            refreshed = refreshed,
        )
    }

    @Synchronized
    fun invalidate(accountUuid: String, messageUuid: String) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.accountUuid == accountUuid &&
                entry.key.messageUuid == messageUuid
            ) {
                entry.value.message.get()?.clearVerifiedProtectedBody()
                iterator.remove()
            }
        }
        val previewIterator = previewCache.entries.iterator()
        while (previewIterator.hasNext()) {
            val entry = previewIterator.next()
            if (entry.key.accountUuid == accountUuid &&
                entry.key.messageUuid == messageUuid
            ) {
                entry.value.message.get()?.clearVerifiedProtectedBody()
                previewIterator.remove()
            }
        }
    }

    @Synchronized
    fun invalidateConversation(accountUuid: String, messageUuids: Iterable<String>) {
        messageUuids.forEach { invalidate(accountUuid, it) }
    }

    @Synchronized
    fun invalidateAccount(accountUuid: String) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.accountUuid == accountUuid) {
                entry.value.message.get()?.clearVerifiedProtectedBody()
                iterator.remove()
            }
        }
        val previewIterator = previewCache.entries.iterator()
        while (previewIterator.hasNext()) {
            val entry = previewIterator.next()
            if (entry.key.accountUuid == accountUuid) {
                entry.value.message.get()?.clearVerifiedProtectedBody()
                previewIterator.remove()
            }
        }
    }

    /**
     * Drops every process-local plaintext presentation reference owned by this repository.
     *
     * High Security calls this after retiring the App Master Key. Resident conversations are
     * cleared separately, but cache entries hold strong references to their text Strings even when
     * their Message reference is weak, so both LRUs must be emptied explicitly.
     */
    @Synchronized
    fun clearTransientPlaintext() {
        cache.values.forEach { it.message.get()?.clearVerifiedProtectedBody() }
        previewCache.values.forEach { it.message.get()?.clearVerifiedProtectedBody() }
        cache.clear()
        previewCache.clear()
    }

    private fun encodeForPublication(plaintext: String): ByteArray {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            return SecureTextPayload.encodeUtf8(plaintext).also {
                SecureTextPayload.logStage(it.size, "utf8_encode", startedAt)
            }
        } catch (error: IOException) {
            val payloadBytes = (error as? SecureTextPayloadTooLargeException)?.observedBytes ?: 0
            val category =
                if (error is SecureTextPayloadTooLargeException) "oversize" else "invalid_utf8"
            SecureTextPayload.logStage(payloadBytes, "utf8_encode", startedAt, category)
            throw error
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun readAndCache(
        message: Message,
        readPlan: SecureMessagePayloadReadPlan? = null,
        pinPreview: Boolean = false,
    ): SecureMessagePayloadReference =
        readVerified(
            message,
            cacheResult = true,
            readPlan = readPlan,
            promoteLegacy = false,
            pinPreview = pinPreview,
        )

    @Synchronized
    @Throws(IOException::class)
    private fun readForSearch(message: Message): SecureMessagePayloadReference =
        readVerified(
            message,
            cacheResult = false,
            readPlan = null,
            promoteLegacy = false,
            pinPreview = false,
        )

    /**
     * A resident Message may predate an atomic SQLCipher retirement.
     * Consult durable state before using or publishing SCS plaintext.
     */
    @Throws(IOException::class)
    private fun rejectDurablyRetracted(message: Message) {
        val accountUuid = message.conversation.account.uuid
        val messageUuid = message.uuid

        if (message.isRetracted ||
            databaseBackend.isMessageRetracted(accountUuid, messageUuid)) {
            message.markRetracted()
            invalidate(accountUuid, messageUuid)
            throw IOException("Retracted message content is unavailable")
        }
    }

    @Throws(IOException::class)
    private fun readVerified(
        message: Message,
        cacheResult: Boolean,
        readPlan: SecureMessagePayloadReadPlan?,
        promoteLegacy: Boolean,
        pinPreview: Boolean = false,
    ): SecureMessagePayloadReference {
        rejectDurablyRetracted(message)
        val context = contextOf(message)
        val persistedReference =
            if (readPlan != null) {
                readPlan.referenceFor(context.messageUuid)
                    ?: run {
                        SecureColdStartPerfTrace.increment(
                            "secure_text_failure_plan_reference_missing",
                            1,
                        )
                        throw IOException("Secure protected text payload is unavailable")
                    }
            } else {
                databaseBackend.findSecureMessagePayloadReference(
                    context.accountUuid,
                    context.messageUuid,
                ).also { reference ->
                    if (reference == null) {
                        SecureColdStartPerfTrace.increment(
                            "secure_text_failure_retry_relation_missing",
                            1,
                        )
                    }
                }
            }
        if (persistedReference != null) {
            val key =
                CacheKey(
                    persistedReference.accountUuid,
                    persistedReference.messageUuid,
                    persistedReference.contentId,
                )
            val cached = cache[key] ?: previewCache[key]
            if (cached != null) {
                rejectDurablyRetracted(message)
                applyVerifiedBody(message, cached.text, promoteLegacy)
                rejectDurablyRetracted(message)
                val refreshedEntry = CacheEntry(cached.text, WeakReference(message))
                cache[key] = refreshedEntry
                if (pinPreview) {
                    previewCache[key] = refreshedEntry
                }
                return persistedReference
            }
        }
        val session =
            readPlan?.open(context)
                ?: coordinator.openProtectedTextPayload(context)
        val startedAt = SystemClock.elapsedRealtime()
        var payloadBytes = 0
        var plaintextBytes: ByteArray? = null
        try {
            plaintextBytes = session.openPlaintextInputStream().use { input ->
                val output = WipingByteArrayOutputStream(8 * 1024)
                val buffer = ByteArray(8 * 1024)
                try {
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        payloadBytes = total + count
                        if (count > SecureTextPayload.MAXIMUM_BYTES - total) {
                            throw IOException("Protected text exceeds bounded text limit")
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                    output.toByteArray()
                } finally {
                    buffer.fill(0)
                    output.wipe()
                }
            }
            val bytes = plaintextBytes
                ?: throw IOException("Protected text plaintext is unavailable")
            payloadBytes = bytes.size
            val snapshot = session.verifyTerminal()
            val text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            val reference = SecureMessagePayloadReference(
                context.accountUuid,
                context.messageUuid,
                snapshot.handle.contentId,
                snapshot.metadata.namespace,
            )
            rejectDurablyRetracted(message)
            applyVerifiedBody(message, text, promoteLegacy)
            rejectDurablyRetracted(message)
            if (cacheResult) {
                val key =
                    CacheKey(
                        reference.accountUuid,
                        reference.messageUuid,
                        reference.contentId,
                    )
                val entry = CacheEntry(text, WeakReference(message))
                cache[key] = entry
                if (pinPreview) {
                    previewCache[key] = entry
                }
            }
            SecureTextPayload.logStage(payloadBytes, "store_read_verify", startedAt)
            return reference
        } catch (error: Exception) {
            val category = if (error is IOException) "io" else "runtime"
            SecureTextPayload.logStage(payloadBytes, "store_read_verify", startedAt, category)
            throw if (error is IOException) error else IOException(
                "Protected text is unavailable",
                error,
            )
        } finally {
            plaintextBytes?.fill(0)
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    private fun pinVerifiedPreview(message: Message) {
        if (message.isRetracted) return
        val accountUuid = message.conversation.account.uuid
        val cached =
            cache.entries.firstOrNull {
                it.key.accountUuid == accountUuid && it.key.messageUuid == message.uuid
            } ?: previewCache.entries.firstOrNull {
                it.key.accountUuid == accountUuid && it.key.messageUuid == message.uuid
            }
        if (cached != null) {
            previewCache[cached.key] =
                CacheEntry(cached.value.text, WeakReference(message))
        }
    }

    private fun applyVerifiedBody(
        message: Message,
        text: String,
        promoteLegacy: Boolean,
    ) {
        if (message.isModerated || message.isRetracted) return
        if (promoteLegacy) {
            message.promoteLegacyToVerifiedProtectedBody(text)
        } else {
            message.setVerifiedProtectedBody(text)
        }
    }

    private fun contextOf(message: Message): SecureMessagePayloadContext =
        SecureMessagePayloadContext(
            accountUuid = message.conversation.account.uuid,
            messageUuid = message.uuid,
        )

    private class WipingByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    companion object {
        const val DEFAULT_CACHE_SIZE = 256
        const val DEFAULT_PREVIEW_CACHE_SIZE = 128
        const val DEFAULT_LAZY_MIGRATION_BATCH_SIZE = 4
        const val MAXIMUM_LAZY_MIGRATION_BATCH_SIZE = 8
    }
}
