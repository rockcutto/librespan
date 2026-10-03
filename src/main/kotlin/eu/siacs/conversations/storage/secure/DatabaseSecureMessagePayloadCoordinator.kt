package eu.siacs.conversations.storage.secure

import android.util.Log
import eu.siacs.conversations.Config
import eu.siacs.conversations.persistance.DatabaseBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Database-backed coordinator for future protected message payloads.
 *
 * It is deliberately unreferenced by the legacy parser/generator/UI. The coordinator creates a
 * Store-private object and records non-plaintext publication evidence before Store commit. A
 * Message DB relation is published only after that exact object is COMMITTED.
 */
class DatabaseSecureMessagePayloadCoordinator @JvmOverloads constructor(
    private val store: SecureContentStore,
    private val databaseBackend: DatabaseBackend,
    private val searchCoordinator: SecureMessageSearchCoordinator? = null,
) : SecureMessagePayloadCoordinator {

    private val mediaCoordinator = SecureMessageMediaCoordinator(store)

    @Synchronized
    override fun beginWrite(context: SecureMessagePayloadContext): SecureMessagePayloadWriteSession =
        beginWriteInternal(
            context,
            outgoingTextModeTracked = false,
            previousReference = null,
        )

    @Synchronized
    override fun beginOutgoingTextWrite(
        context: SecureOutgoingTextPayloadContext,
    ): SecureMessagePayloadWriteSession {
        require(
            databaseBackend.isOutgoingTextMessageForAccount(
                context.accountUuid,
                context.messageUuid,
            ),
        ) {
            "Secure outgoing text payload requires a persisted account-owned outgoing text message"
        }
        check(
            databaseBackend.claimSecureMessagePayloadMode(
                context.accountUuid,
                context.messageUuid,
                SecureMessagePayloadMode.PENDING,
            ),
        ) {
            "Secure outgoing text payload already has a protected classification"
        }
        return try {
            beginWriteInternal(
                context.asPayloadContext(),
                outgoingTextModeTracked = true,
                previousReference = null,
            )
        } catch (error: Exception) {
            databaseBackend.updateSecureMessagePayloadMode(
                context.accountUuid,
                context.messageUuid,
                SecureMessagePayloadMode.UNAVAILABLE,
            )
            throw error
        }
    }

    @Synchronized
    override fun beginProtectedTextWrite(
        context: SecureMessagePayloadContext,
    ): SecureMessagePayloadWriteSession {
        require(databaseBackend.isTextMessageForAccount(context.accountUuid, context.messageUuid)) {
            "Secure protected text payload requires an account-owned text message"
        }
        val mode = databaseBackend.getSecureMessagePayloadMode(
            context.accountUuid,
            context.messageUuid,
        )
        check(mode == SecureMessagePayloadMode.PENDING || mode == SecureMessagePayloadMode.PROTECTED) {
            "Secure protected text payload is not durably selected"
        }
        val previous = databaseBackend.findSecureMessagePayloadReference(
            context.accountUuid,
            context.messageUuid,
        )
        check((mode == SecureMessagePayloadMode.PENDING) == (previous == null)) {
            "Secure protected text classification conflicts with its relation"
        }
        return beginWriteInternal(
            context,
            outgoingTextModeTracked = true,
            previousReference = previous,
        )
    }

    @Synchronized
    override fun outgoingTextPayloadMode(
        context: SecureOutgoingTextPayloadContext,
    ): SecureMessagePayloadMode? {
        if (!databaseBackend.isOutgoingTextMessageForAccount(
                context.accountUuid,
                context.messageUuid,
            )
        ) {
            return null
        }
        return databaseBackend.getSecureMessagePayloadMode(
            context.accountUuid,
            context.messageUuid,
        )
    }

    @Synchronized
    override fun protectedTextPayloadMode(
        context: SecureMessagePayloadContext,
    ): SecureMessagePayloadMode? {
        if (!databaseBackend.isTextMessageForAccount(context.accountUuid, context.messageUuid)) {
            return null
        }
        return databaseBackend.getSecureMessagePayloadMode(
            context.accountUuid,
            context.messageUuid,
        )
    }

    private fun beginWriteInternal(
        context: SecureMessagePayloadContext,
        outgoingTextModeTracked: Boolean,
        previousReference: SecureMessagePayloadReference?,
    ): SecureMessagePayloadWriteSession {
        require(databaseBackend.hasMessageForAccount(context.accountUuid, context.messageUuid)) {
            "Secure message payload requires a persisted account-owned message"
        }
        val handle = store.allocate(
            SecureContentMetadata(
                accountUuid = context.accountUuid,
                contentId = UUID.randomUUID().toString(),
                namespace = context.namespace,
                messageUuid = context.messageUuid,
            ),
        )
        val storeWriter = try {
            store.beginWrite(handle)
        } catch (error: Exception) {
            store.delete(handle)
            throw error
        }
        val now = System.currentTimeMillis()
        val publication = SecureMessagePayloadPublicationRecord(
            publicationId = UUID.randomUUID().toString(),
            accountUuid = context.accountUuid,
            messageUuid = context.messageUuid,
            contentId = handle.contentId,
            namespace = context.namespace,
            phase = SecureMessagePayloadPublicationPhase.PREPARED,
            createdAt = now,
            updatedAt = now,
            previousContentId = previousReference?.contentId,
        )
        if (!databaseBackend.createSecureMessagePayloadPublication(publication)) {
            storeWriter.abort()
            throw IOException("Unable to persist secure message publication evidence")
        }
        return WriteSession(
            context = context,
            handle = handle,
            storeWriter = storeWriter,
            publication = publication,
            outgoingTextModeTracked = outgoingTextModeTracked,
            previousReference = previousReference,
        )
    }

    @Synchronized
    override fun openOutgoingTextPayload(
        context: SecureOutgoingTextPayloadContext,
    ): SecureMessagePayloadReadSession {
        if (!databaseBackend.isOutgoingTextMessageForAccount(
                context.accountUuid,
                context.messageUuid,
            ) ||
            databaseBackend.getSecureMessagePayloadMode(
                context.accountUuid,
                context.messageUuid,
            ) != SecureMessagePayloadMode.PROTECTED
        ) {
            throw IOException("Secure outgoing text payload is unavailable")
        }
        return open(context.accountUuid, context.messageUuid)
    }

    @Synchronized
    override fun openProtectedTextPayload(
        context: SecureMessagePayloadContext,
    ): SecureMessagePayloadReadSession =
        openProtectedTextPayloadInternal(context, scopedStore = null)

    @Synchronized
    internal fun beginProtectedTextReadPlan(
        accountUuid: String,
        messageUuids: Collection<String>,
    ): SecureMessagePayloadReadPlan {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        val requested =
            messageUuids
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        require(requested.size <= MAX_PROTECTED_TEXT_READ_PLAN_MESSAGES) {
            "Secure protected text read plan exceeds bounded page size"
        }

        val validated =
            try {
                databaseBackend.getValidatedProtectedTextPayloadReferences(
                    accountUuid,
                    requested,
                ).toMutableList()
            } catch (error: Exception) {
                throw IOException("Secure protected text read plan is unavailable", error)
            }
        val requestedSet = requested.toHashSet()
        val publicationBlocked =
            try {
                databaseBackend.getSecureMessagePayloadPublications()
                    .asSequence()
                    .filter {
                        it.accountUuid == accountUuid &&
                            it.messageUuid in requestedSet
                    }
                    .map { it.messageUuid }
                    .toHashSet()
            } catch (error: Exception) {
                throw IOException("Secure protected text read plan is unavailable", error)
            }
        val retirementBlocked =
            try {
                databaseBackend.getSecureMessagePayloadRetirements()
                    .asSequence()
                    .filter {
                        it.accountUuid == accountUuid &&
                            it.messageUuid in requestedSet
                    }
                    .map { Pair(it.messageUuid, it.contentId) }
                    .toHashSet()
            } catch (error: Exception) {
                throw IOException("Secure protected text read plan is unavailable", error)
            }

        val initiallyValidatedMessageUuids =
            validated.asSequence().map { it.messageUuid }.toHashSet()
        requested
            .asSequence()
            .filter { it !in initiallyValidatedMessageUuids }
            .filter { it !in publicationBlocked }
            .filter { messageUuid ->
                retirementBlocked.none { blocked -> blocked.first == messageUuid }
            }
            .forEach { messageUuid ->
                repairMissingProtectedReference(accountUuid, messageUuid)?.let(validated::add)
            }

        val validatedMessageUuids =
            validated
                .asSequence()
                .filter {
                    it.accountUuid == accountUuid &&
                        it.messageUuid in requestedSet &&
                        it.namespace == SecureMessagePayloadContext.NAMESPACE
                }
                .map { it.messageUuid }
                .toHashSet()
        val missingRelationOrMode =
            requested.count { messageUuid -> messageUuid !in validatedMessageUuids }
        if (missingRelationOrMode > 0) {
            SecureColdStartPerfTrace.increment(
                "secure_text_plan_missing_relation_or_mode",
                missingRelationOrMode.toLong(),
            )
        }
        val publicationBlockedMessages = publicationBlocked.intersect(requestedSet)
        if (publicationBlockedMessages.isNotEmpty()) {
            SecureColdStartPerfTrace.increment(
                "secure_text_plan_publication_blocked",
                publicationBlockedMessages.size.toLong(),
            )
        }
        val retirementBlockedMessages =
            retirementBlocked.asSequence().map { it.first }.toSet().intersect(requestedSet)
        if (retirementBlockedMessages.isNotEmpty()) {
            SecureColdStartPerfTrace.increment(
                "secure_text_plan_retirement_blocked",
                retirementBlockedMessages.size.toLong(),
            )
        }

        val referencesByMessage = LinkedHashMap<String, SecureMessagePayloadReference>()
        val conflicts = HashSet<String>()
        validated.forEach { reference ->
            if (reference.accountUuid != accountUuid ||
                reference.messageUuid !in requestedSet ||
                reference.namespace != SecureMessagePayloadContext.NAMESPACE
            ) {
                return@forEach
            }
            if (reference.messageUuid in publicationBlocked ||
                Pair(reference.messageUuid, reference.contentId) in retirementBlocked
            ) {
                return@forEach
            }
            val previous = referencesByMessage.putIfAbsent(reference.messageUuid, reference)
            if (previous != null && previous != reference) {
                conflicts.add(reference.messageUuid)
            }
        }
        conflicts.forEach(referencesByMessage::remove)
        if (conflicts.isNotEmpty()) {
            SecureColdStartPerfTrace.increment(
                "secure_text_plan_conflicts",
                conflicts.size.toLong(),
            )
        }

        SecureColdStartPerfTrace.increment("secure_text_read_plan_requested", requested.size.toLong())
        SecureColdStartPerfTrace.increment(
            "secure_text_read_plan_validated",
            referencesByMessage.size.toLong(),
        )
        SecureColdStartPerfTrace.increment(
            "secure_text_read_plan_blocked",
            (requested.size - referencesByMessage.size).toLong(),
        )

        val scopedStore =
            (store as? RuntimeSecureContentStore)?.beginReadScope(accountUuid)
        return object : SecureMessagePayloadReadPlan {
            private var closed = false

            override fun referenceFor(messageUuid: String): SecureMessagePayloadReference? =
                synchronized(this@DatabaseSecureMessagePayloadCoordinator) {
                    if (closed) null else referencesByMessage[messageUuid]
                }

            override fun open(
                context: SecureMessagePayloadContext,
            ): SecureMessagePayloadReadSession =
                synchronized(this@DatabaseSecureMessagePayloadCoordinator) {
                    check(!closed) { "Secure message payload read plan is closed" }
                    if (context.accountUuid != accountUuid) {
                        throw IOException("Secure protected text payload is unavailable")
                    }
                    val reference =
                        referencesByMessage[context.messageUuid]
                            ?: throw IOException("Secure protected text payload is unavailable")
                    openValidatedReference(reference, scopedStore)
                }

            override fun close() {
                synchronized(this@DatabaseSecureMessagePayloadCoordinator) {
                    if (!closed) {
                        scopedStore?.close()
                        referencesByMessage.clear()
                        closed = true
                    }
                }
            }
        }
    }

    private fun openProtectedTextPayloadInternal(
        context: SecureMessagePayloadContext,
        scopedStore: SecureContentScopedRead?,
    ): SecureMessagePayloadReadSession {
        if (!databaseBackend.isTextMessageForAccount(context.accountUuid, context.messageUuid) ||
            databaseBackend.getSecureMessagePayloadMode(
                context.accountUuid,
                context.messageUuid,
            ) != SecureMessagePayloadMode.PROTECTED
        ) {
            throw IOException("Secure protected text payload is unavailable")
        }
        return openInternal(context.accountUuid, context.messageUuid, scopedStore)
    }

    @Synchronized
    override fun open(reference: SecureMessagePayloadReference): SecureContentReadSession =
        openReferenceInternal(reference, scopedStore = null)

    private fun openReferenceInternal(
        reference: SecureMessagePayloadReference,
        scopedStore: SecureContentScopedRead?,
    ): SecureContentReadSession {
        val hasUnresolvedOperation = try {
            databaseBackend.getSecureMessagePayloadPublications().any {
                it.accountUuid == reference.accountUuid &&
                    it.messageUuid == reference.messageUuid
            } || databaseBackend.getSecureMessagePayloadRetirements().any {
                it.accountUuid == reference.accountUuid &&
                    it.messageUuid == reference.messageUuid &&
                    it.contentId == reference.contentId
            }
        } catch (error: Exception) {
            throw IOException("Secure message payload is unavailable", error)
        }
        if (hasUnresolvedOperation) {
            throw IOException("Secure message payload is unavailable")
        }
        val persisted = databaseBackend.findSecureMessagePayloadReference(
            reference.accountUuid,
            reference.messageUuid,
        ) ?: throw IOException("Secure message payload is unavailable")
        if (persisted != reference) {
            throw IOException("Secure message payload is unavailable")
        }
        return openValidatedReference(reference, scopedStore)
    }

    private fun openValidatedReference(
        reference: SecureMessagePayloadReference,
        scopedStore: SecureContentScopedRead?,
    ): SecureContentReadSession {
        val objectSnapshot =
            if (scopedStore != null) {
                scopedStore.find(reference.contentId)
            } else {
                store.find(reference.accountUuid, reference.contentId)
            }
        if (objectSnapshot == null) {
            SecureColdStartPerfTrace.increment("secure_text_open_metadata_missing", 1)
            throw IOException("Secure message payload is unavailable")
        }
        if (objectSnapshot.namespace != reference.namespace ||
            objectSnapshot.messageUuid != reference.messageUuid
        ) {
            SecureColdStartPerfTrace.increment("secure_text_open_relation_mismatch", 1)
            throw IOException("Secure message payload is unavailable")
        }
        if (objectSnapshot.state != SecureContentState.COMMITTED) {
            SecureColdStartPerfTrace.increment("secure_text_open_state_unavailable", 1)
            throw IOException("Secure message payload is unavailable")
        }
        return try {
            scopedStore?.open(objectSnapshot.handle.contentId)
                ?: store.open(objectSnapshot.handle)
        } catch (error: IOException) {
            SecureColdStartPerfTrace.increment("secure_text_open_session_failure", 1)
            throw error
        }
    }

    @Synchronized
    override fun open(
        accountUuid: String,
        messageUuid: String,
    ): SecureMessagePayloadReadSession =
        openInternal(accountUuid, messageUuid, scopedStore = null)

    private fun openInternal(
        accountUuid: String,
        messageUuid: String,
        scopedStore: SecureContentScopedRead?,
    ): SecureMessagePayloadReadSession {
        if (accountUuid.isBlank() || messageUuid.isBlank()) {
            throw IOException("Secure message payload is unavailable")
        }
        val reference =
            databaseBackend.findSecureMessagePayloadReference(accountUuid, messageUuid)
                ?: repairMissingProtectedReference(accountUuid, messageUuid)
                ?: run {
                    SecureColdStartPerfTrace.increment("secure_text_open_relation_missing", 1)
                    throw IOException("Secure message payload is unavailable")
                }
        return openReferenceInternal(reference, scopedStore)
    }

    /**
     * Repairs only a missing durable message -> content relation.
     *
     * The Store relation index supplies candidates, never authority. A repair is published only
     * after the exact authenticated Store object is uniquely identified as COMMITTED, uses the
     * protected-text namespace, and is bound to the same account/message. Ambiguity and unresolved
     * publication/retirement evidence remain fail-closed.
     */
    private fun repairMissingProtectedReference(
        accountUuid: String,
        messageUuid: String,
    ): SecureMessagePayloadReference? {
        if (accountUuid.isBlank() || messageUuid.isBlank()) return null
        if (!databaseBackend.isTextMessageForAccount(accountUuid, messageUuid) ||
            databaseBackend.getSecureMessagePayloadMode(accountUuid, messageUuid) !=
                SecureMessagePayloadMode.PROTECTED
        ) {
            SecureColdStartPerfTrace.increment("secure_text_relation_repair_not_protected", 1)
            return null
        }

        val unresolved =
            try {
                databaseBackend.getSecureMessagePayloadPublications().any {
                    it.accountUuid == accountUuid && it.messageUuid == messageUuid
                } || databaseBackend.getSecureMessagePayloadRetirements().any {
                    it.accountUuid == accountUuid && it.messageUuid == messageUuid
                }
            } catch (_: Exception) {
                SecureColdStartPerfTrace.increment("secure_text_relation_repair_evidence_error", 1)
                return null
            }
        if (unresolved) {
            SecureColdStartPerfTrace.increment("secure_text_relation_repair_blocked", 1)
            return null
        }

        var candidates =
            try {
                store.findByMessage(accountUuid, messageUuid)
                    .filter {
                        it.accountUuid == accountUuid &&
                            it.messageUuid == messageUuid &&
                            it.namespace == SecureMessagePayloadContext.NAMESPACE &&
                            it.state == SecureContentState.COMMITTED
                    }
            } catch (_: Exception) {
                SecureColdStartPerfTrace.increment("secure_text_relation_repair_store_error", 1)
                return null
            }

        if (candidates.isEmpty()) {
            val legacyCandidate =
                try {
                    databaseBackend.findByMessageUuid(accountUuid, messageUuid).singleOrNull()
                } catch (_: Exception) {
                    null
                }
            if (legacyCandidate != null) {
                val authenticated =
                    store.find(accountUuid, legacyCandidate.contentId)
                        ?.takeIf {
                            it.accountUuid == accountUuid &&
                                it.messageUuid == messageUuid &&
                                it.namespace == SecureMessagePayloadContext.NAMESPACE &&
                                it.state == SecureContentState.COMMITTED
                        }
                if (authenticated != null) {
                    candidates = listOf(authenticated)
                    SecureColdStartPerfTrace.increment(
                        "secure_text_relation_repair_legacy_candidate",
                        1,
                    )
                }
            }
        }

        if (candidates.isEmpty()) {
            SecureColdStartPerfTrace.increment("secure_text_relation_repair_no_candidate", 1)
            return null
        }
        if (candidates.size != 1) {
            SecureColdStartPerfTrace.increment("secure_text_relation_repair_ambiguous", 1)
            return null
        }

        val candidate = candidates.single()
        val repaired =
            SecureMessagePayloadReference(
                accountUuid = accountUuid,
                messageUuid = messageUuid,
                contentId = candidate.contentId,
                namespace = SecureMessagePayloadContext.NAMESPACE,
            )

        val current = databaseBackend.findSecureMessagePayloadReference(accountUuid, messageUuid)
        if (current != null) {
            return current.takeIf { it == repaired }
        }
        if (!databaseBackend.linkSecureMessagePayloadReference(repaired)) {
            val concurrent =
                databaseBackend.findSecureMessagePayloadReference(accountUuid, messageUuid)
            if (concurrent != repaired) {
                SecureColdStartPerfTrace.increment("secure_text_relation_repair_link_failed", 1)
                return null
            }
        }

        SecureColdStartPerfTrace.increment("secure_text_relation_repairs", 1)
        return repaired
    }

    @Synchronized
    override fun recoverInterruptedPublications() {
        databaseBackend.getSecureMessagePayloadPublications().forEach { publication ->
            try {
                completePublicationRecovery(publication)
            } catch (_: Exception) {
                databaseBackend.updateSecureMessagePayloadPublication(
                    publication.publicationId,
                    SecureMessagePayloadPublicationPhase.FAILED,
                )
            }
        }
    }

    private fun completePublicationRecovery(
        publication: SecureMessagePayloadPublicationRecord,
    ) {
        check(
            databaseBackend.hasMessageForAccount(
                publication.accountUuid,
                publication.messageUuid,
            ),
        ) {
            "Secure message publication owner is unavailable"
        }
        val newReference = SecureMessagePayloadReference(
            publication.accountUuid,
            publication.messageUuid,
            publication.contentId,
            publication.namespace,
        )
        val objectSnapshot = store.find(publication.accountUuid, publication.contentId)
            ?.takeIf {
                it.state == SecureContentState.COMMITTED &&
                    it.namespace == publication.namespace &&
                    it.messageUuid == publication.messageUuid
            }
            ?: error("Secure message publication object is unavailable")
        check(objectSnapshot.handle.contentId == newReference.contentId)

        val current = databaseBackend.findSecureMessagePayloadReference(
            publication.accountUuid,
            publication.messageUuid,
        )
        val previous = publication.previousContentId?.let {
            SecureMessagePayloadReference(
                publication.accountUuid,
                publication.messageUuid,
                it,
                publication.namespace,
            )
        }
        val linked = when {
            current == newReference -> true
            previous == null && current == null ->
                databaseBackend.linkSecureMessagePayloadReference(newReference)
            previous != null && current == previous ->
                databaseBackend.replaceSecureMessagePayloadReference(previous, newReference)
            else -> false
        }
        check(linked) { "Secure message publication relation conflicts with recovery evidence" }
        check(
            databaseBackend.updateSecureMessagePayloadPublication(
                publication.publicationId,
                SecureMessagePayloadPublicationPhase.MESSAGE_LINKED,
            ),
        )
        indexCommittedPayload(newReference)
        check(promoteOutgoingTextModeIfTracked(publication))
        if (previous != null) {
            store.find(previous.accountUuid, previous.contentId)?.let { old ->
                check(
                    old.namespace == previous.namespace &&
                        old.messageUuid == previous.messageUuid
                ) {
                    "Secure message replacement conflicts with previous Store object"
                }
                store.delete(previous.accountUuid, previous.contentId)
            }
            check(store.find(previous.accountUuid, previous.contentId) == null) {
                "Previous secure message payload remains available"
            }
        }
        check(
            databaseBackend.updateSecureMessagePayloadPublication(
                publication.publicationId,
                SecureMessagePayloadPublicationPhase.FINALIZED,
            ),
        )
        check(databaseBackend.deleteSecureMessagePayloadPublication(publication.publicationId))
    }

    /**
     * Existing service deletion call sites already route through this coordinator before removing
     * Message ownership. Retire message-owned media first so those call sites become a unified
     * crypto-first gate without exposing Store details to the service.
     */
    @Synchronized
    override fun retire(
        accountUuid: String,
        messageUuid: String,
    ) {
        if (accountUuid.isBlank() || messageUuid.isBlank()) return
        mediaCoordinator.retire(accountUuid, messageUuid)
        val reference = databaseBackend.findSecureMessagePayloadReference(accountUuid, messageUuid)
            ?: return
        retire(reference)
    }

    /**
     * Capture every account-owned Message UUID before the service bulk-deletes the conversation.
     * Media retirement happens before payload relation retirement; any failure propagates so the
     * existing service gate leaves all owner rows intact for retry/recovery.
     */
    @Synchronized
    override fun retireAllForConversation(
        accountUuid: String,
        conversationUuid: String,
    ) {
        if (accountUuid.isBlank() || conversationUuid.isBlank()) return
        databaseBackend.getMessageUuidsForConversation(accountUuid, conversationUuid)
            .distinct()
            .forEach { messageUuid -> mediaCoordinator.retire(accountUuid, messageUuid) }
        databaseBackend.getSecureMessagePayloadReferencesForConversation(
            accountUuid,
            conversationUuid,
        ).forEach(::retire)
    }

    @Synchronized
    override fun retire(reference: SecureMessagePayloadReference) {
        val persisted = databaseBackend.findSecureMessagePayloadReference(
            reference.accountUuid,
            reference.messageUuid,
        ) ?: return
        if (persisted != reference) {
            throw IOException("Secure message payload is unavailable")
        }
        val now = System.currentTimeMillis()
        val retirement = SecureMessagePayloadRetirementRecord(
            retirementId = UUID.randomUUID().toString(),
            accountUuid = reference.accountUuid,
            messageUuid = reference.messageUuid,
            contentId = reference.contentId,
            namespace = reference.namespace,
            phase = SecureMessagePayloadRetirementPhase.PREPARED,
            createdAt = now,
            updatedAt = now,
        )
        check(databaseBackend.createSecureMessagePayloadRetirement(retirement)) {
            "Unable to persist secure message retirement evidence"
        }
        try {
            completeRetirement(retirement)
        } catch (error: Exception) {
            databaseBackend.updateSecureMessagePayloadRetirement(
                retirement.retirementId,
                SecureMessagePayloadRetirementPhase.FAILED,
            )
            throw if (error is IOException) error else IOException(
                "Unable to retire secure message payload",
                error,
            )
        }
    }

    @Synchronized
    override fun recoverInterruptedRetirements() {
        databaseBackend.getSecureMessagePayloadRetirements().forEach { retirement ->
            try {
                completeRetirement(retirement)
            } catch (_: Exception) {
                databaseBackend.updateSecureMessagePayloadRetirement(
                    retirement.retirementId,
                    SecureMessagePayloadRetirementPhase.FAILED,
                )
            }
        }
    }

    @Synchronized
    override fun repairSearchIndexBatch(
        accountUuid: String,
        limit: Int,
    ): SecureMessageSearchRepairResult {
        require(accountUuid.isNotBlank())
        require(limit in 1..MAXIMUM_SEARCH_REPAIR_BATCH)
        val search = searchCoordinator ?: return SecureMessageSearchRepairResult(0, 0, 0)
        val candidates = search.findUnindexedProtectedPayloads(accountUuid, limit)
        var repaired = 0
        var failed = 0
        candidates.forEach { reference ->
            try {
                indexCommittedPayload(reference)
                repaired += 1
            } catch (error: Exception) {
                failed += 1
                Log.w(
                    Config.LOGTAG,
                    "secure search index repair failed for one message",
                    error,
                )
            }
        }
        return SecureMessageSearchRepairResult(candidates.size, repaired, failed)
    }

    private fun completeRetirement(retirement: SecureMessagePayloadRetirementRecord) {
        val expected = SecureMessagePayloadReference(
            accountUuid = retirement.accountUuid,
            messageUuid = retirement.messageUuid,
            contentId = retirement.contentId,
            namespace = retirement.namespace,
        )
        val objectSnapshot = store.find(retirement.accountUuid, retirement.contentId)
        if (objectSnapshot != null) {
            if (objectSnapshot.namespace != retirement.namespace ||
                objectSnapshot.messageUuid != retirement.messageUuid
            ) {
                throw IOException("Secure message retirement evidence conflicts with Store object")
            }
            store.delete(retirement.accountUuid, retirement.contentId)
            if (store.find(retirement.accountUuid, retirement.contentId) != null) {
                throw IOException("Secure message payload remains available after retirement")
            }
        }
        searchCoordinator?.deleteMessage(retirement.accountUuid, retirement.messageUuid)
        check(
            databaseBackend.updateSecureMessagePayloadRetirement(
                retirement.retirementId,
                SecureMessagePayloadRetirementPhase.STORE_RETIRED,
            ),
        ) {
            "Unable to advance secure message retirement evidence"
        }

        val persisted = databaseBackend.findSecureMessagePayloadReference(
            retirement.accountUuid,
            retirement.messageUuid,
        )
        when {
            persisted == null -> Unit
            persisted == expected -> check(
                databaseBackend.deleteSecureMessagePayloadReference(
                    retirement.accountUuid,
                    retirement.messageUuid,
                ),
            ) {
                "Unable to retire secure message payload relation"
            }

            else -> throw IOException("Secure message retirement evidence conflicts with relation")
        }
        check(
            databaseBackend.updateSecureMessagePayloadRetirement(
                retirement.retirementId,
                SecureMessagePayloadRetirementPhase.REFERENCE_RETIRED,
            ),
        ) {
            "Unable to record secure message payload relation retirement"
        }
        check(
            databaseBackend.updateSecureMessagePayloadRetirement(
                retirement.retirementId,
                SecureMessagePayloadRetirementPhase.FINALIZED,
            ),
        ) {
            "Unable to finalize secure message payload retirement"
        }
        check(databaseBackend.deleteSecureMessagePayloadRetirement(retirement.retirementId)) {
            "Unable to retire secure message retirement evidence"
        }
    }

    /** Exceptional recovery path: runtime plaintext is unavailable after process death. */
    private fun indexCommittedPayload(reference: SecureMessagePayloadReference) {
        if (searchCoordinator == null) return
        val snapshot = store.find(reference.accountUuid, reference.contentId)
            ?.takeIf {
                it.state == SecureContentState.COMMITTED &&
                    it.namespace == reference.namespace &&
                    it.messageUuid == reference.messageUuid
            }
            ?: throw IOException("Secure message payload is unavailable for indexing")
        val session = store.open(snapshot.handle)
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
                        if (count > MAXIMUM_TEXT_BYTES - total) {
                            throw IOException("Secure message payload exceeds text limit")
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
                ?: throw IOException("Secure message payload plaintext is unavailable")
            session.verifyTerminal()
            indexPublishedPlaintext(reference, bytes)
        } finally {
            plaintextBytes?.fill(0)
            session.close()
        }
    }

    /** Normal publication path: indexes only after Store commit and durable relation publication. */
    private fun indexPublishedPlaintext(
        reference: SecureMessagePayloadReference,
        plaintextUtf8: ByteArray,
    ) {
        val search = searchCoordinator ?: return
        if (plaintextUtf8.size > MAXIMUM_TEXT_BYTES) {
            throw IOException("Secure message payload exceeds text limit")
        }
        val text = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(plaintextUtf8))
            .toString()
        search.replaceMessage(reference.accountUuid, reference.messageUuid, text)
    }

    private fun promoteOutgoingTextModeIfTracked(
        publication: SecureMessagePayloadPublicationRecord,
    ): Boolean = when (
        databaseBackend.getSecureMessagePayloadMode(
            publication.accountUuid,
            publication.messageUuid,
        )
    ) {
        null, SecureMessagePayloadMode.PROTECTED -> true
        SecureMessagePayloadMode.PENDING -> databaseBackend.updateSecureMessagePayloadMode(
            publication.accountUuid,
            publication.messageUuid,
            SecureMessagePayloadMode.PROTECTED,
        )
        SecureMessagePayloadMode.UNAVAILABLE -> false
    }

    private class WipingByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private companion object {
        const val MAXIMUM_TEXT_BYTES: Int = 256 * 1024
        const val MAX_PROTECTED_TEXT_READ_PLAN_MESSAGES: Int = 512
        const val MAXIMUM_SEARCH_REPAIR_BATCH: Int = 50
    }

    private inner class WriteSession(
        override val context: SecureMessagePayloadContext,
        private val handle: SecureContentHandle,
        private val storeWriter: SecureContentWriteSession,
        private val publication: SecureMessagePayloadPublicationRecord,
        private val outgoingTextModeTracked: Boolean,
        private val previousReference: SecureMessagePayloadReference?,
    ) : SecureMessagePayloadWriteSession {
        private var terminal = false

        override fun openPlaintextOutputStream(): OutputStream {
            check(!terminal) { "Secure message payload writer is terminal" }
            return storeWriter.openPlaintextOutputStream()
        }

        override fun commitAndPublish(): SecureMessagePayloadReference =
            commitAndPublishInternal(null)

        override fun commitAndPublish(
            indexPlaintextUtf8: ByteArray,
        ): SecureMessagePayloadReference = commitAndPublishInternal(indexPlaintextUtf8)

        private fun commitAndPublishInternal(
            indexPlaintextUtf8: ByteArray?,
        ): SecureMessagePayloadReference {
            check(!terminal) { "Secure message payload writer is terminal" }
            val transaction = storeWriter.finishAndBeginCommit()
            var storeCommitted = false
            try {
                transaction.commit()
                storeCommitted = true
                check(
                    databaseBackend.updateSecureMessagePayloadPublication(
                        publication.publicationId,
                        SecureMessagePayloadPublicationPhase.STORE_COMMITTED,
                    ),
                ) {
                    "Unable to advance secure message publication evidence"
                }
                val reference = SecureMessagePayloadReference(
                    accountUuid = context.accountUuid,
                    messageUuid = context.messageUuid,
                    contentId = handle.contentId,
                    namespace = context.namespace,
                )
                val linked = if (previousReference == null) {
                    databaseBackend.linkSecureMessagePayloadReference(reference)
                } else {
                    databaseBackend.replaceSecureMessagePayloadReference(
                        previousReference,
                        reference,
                    )
                }
                check(linked) {
                    "Unable to publish secure message payload relation"
                }
                check(
                    databaseBackend.updateSecureMessagePayloadPublication(
                        publication.publicationId,
                        SecureMessagePayloadPublicationPhase.MESSAGE_LINKED,
                    ),
                ) {
                    "Unable to record secure message payload relation"
                }
                if (indexPlaintextUtf8 == null) {
                    indexCommittedPayload(reference)
                } else {
                    indexPublishedPlaintext(reference, indexPlaintextUtf8)
                }
                if (outgoingTextModeTracked) {
                    check(promoteOutgoingTextModeIfTracked(publication)) {
                        "Unable to publish secure outgoing text payload classification"
                    }
                }
                if (previousReference != null) {
                    store.delete(previousReference.accountUuid, previousReference.contentId)
                    check(
                        store.find(previousReference.accountUuid, previousReference.contentId) == null,
                    ) {
                        "Previous secure message payload remains available"
                    }
                }
                check(
                    databaseBackend.updateSecureMessagePayloadPublication(
                        publication.publicationId,
                        SecureMessagePayloadPublicationPhase.FINALIZED,
                    ),
                ) {
                    "Unable to finalize secure message publication"
                }
                check(databaseBackend.deleteSecureMessagePayloadPublication(publication.publicationId)) {
                    "Unable to retire secure message publication evidence"
                }
                terminal = true
                return reference
            } catch (error: Exception) {
                if (!storeCommitted) {
                    try {
                        transaction.abort()
                    } catch (_: Exception) {
                    }
                    databaseBackend.updateSecureMessagePayloadPublication(
                        publication.publicationId,
                        SecureMessagePayloadPublicationPhase.FAILED,
                    )
                    if (outgoingTextModeTracked && previousReference == null) {
                        databaseBackend.updateSecureMessagePayloadMode(
                            context.accountUuid,
                            context.messageUuid,
                            SecureMessagePayloadMode.UNAVAILABLE,
                        )
                    }
                }
                terminal = true
                throw error
            }
        }

        override fun abort() {
            if (terminal) return
            var storeAborted = false
            try {
                storeWriter.abort()
                storeAborted = store.find(handle.accountUuid, handle.contentId)
                    ?.state == SecureContentState.ABORTED
            } finally {
                databaseBackend.updateSecureMessagePayloadPublication(
                    publication.publicationId,
                    SecureMessagePayloadPublicationPhase.FAILED,
                )
                if (storeAborted) {
                    databaseBackend.deleteSecureMessagePayloadPublication(publication.publicationId)
                }
                if (outgoingTextModeTracked && previousReference == null) {
                    databaseBackend.updateSecureMessagePayloadMode(
                        context.accountUuid,
                        context.messageUuid,
                        SecureMessagePayloadMode.UNAVAILABLE,
                    )
                }
                terminal = true
            }
        }
    }
}
