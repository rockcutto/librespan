package eu.siacs.conversations.storage.secure

import java.io.OutputStream

/**
 * Application-level identity for a future protected message payload.
 *
 * [messageUuid] identifies the logical Message DB row. [contentId] identifies the exact Secure
 * Content Store object. Neither identifier authorizes access alone: implementations validate the
 * Message DB relation and then use the Store's (accountUuid, contentId) boundary.
 */
data class SecureMessagePayloadReference(
    val accountUuid: String,
    val messageUuid: String,
    val contentId: String,
    val namespace: String = SecureMessagePayloadContext.NAMESPACE,
) {
    init {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(namespace == SecureMessagePayloadContext.NAMESPACE) {
            "secure message payloads require the message-payload namespace"
        }
    }
}

data class SecureMessageSearchRepairResult(
    val selected: Int,
    val repaired: Int,
    val failed: Int,
)

/**
 * Validated facts that the application repository supplies before allocating a message payload.
 *
 * This model contains no plaintext payload, Message body, path, URI, FileBackend object, blob
 * locator, raw key or crypto implementation detail.
 */
/**
 * Explicit opt-in class for an outgoing protected text payload.
 *
 * It names an already-persisted local outgoing text Message only. It has no plaintext field:
 * the producer supplies bytes through a controlled stream and must not derive a fallback from
 * Message.body. The class is deliberately separate from all legacy Message types so enabling the
 * payload rollout cannot silently change ordinary text, media, edits, incoming parsing or UI.
 */
data class SecureOutgoingTextPayloadContext(
    val accountUuid: String,
    val messageUuid: String,
) {
    init {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
    }

    internal fun asPayloadContext(): SecureMessagePayloadContext =
        SecureMessagePayloadContext(accountUuid = accountUuid, messageUuid = messageUuid)
}

data class SecureMessagePayloadContext(
    val accountUuid: String,
    val messageUuid: String,
    val namespace: String = NAMESPACE,
) {
    init {
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        require(namespace == NAMESPACE) {
            "secure message payloads require the message-payload namespace"
        }
    }

    companion object {
        const val NAMESPACE = "secure-message-payload"
    }
}

/**
 * Future application-layer owner of the protected-message publication boundary.
 *
 * An implementation coordinates SecureContentStore with Message DB reference persistence and
 * recovery evidence. It must never expose a path, URI, FileBackend object, raw key, blob store,
 * key-material store, CryptoEngine or legacy Message.body fallback.
 *
 * [commitAndPublish] succeeds only after the Store object is COMMITTED and the account-scoped
 * Message DB relation is durable. A direct SecureContentWriteSession is deliberately not exposed:
 * its Store-only commit would be insufficient to publish a message payload.
 */
interface SecureMessagePayloadCoordinator {
    /**
     * Allocates an invisible candidate for one validated local Message DB identity.
     */
    fun beginWrite(context: SecureMessagePayloadContext): SecureMessagePayloadWriteSession

    /**
     * Allocates an opt-in payload only for a persisted account-owned outgoing text Message.
     *
     * This is the U4 producer boundary. It deliberately does not inspect or copy Message.body,
     * select a legacy Message type, send an XMPP stanza, or make the payload renderable. The
     * caller must use the returned writer capability and later supply an explicit renderer policy.
     */
    fun beginOutgoingTextWrite(
        context: SecureOutgoingTextPayloadContext,
    ): SecureMessagePayloadWriteSession

    /**
     * Publishes or replaces a protected text payload for any persisted account-owned text message.
     *
     * The owning repository must have durably selected PENDING for an initial publication.
     * PROTECTED selects replacement semantics and preserves the previous committed object until
     * the new exact relation is durable.
     */
    fun beginProtectedTextWrite(
        context: SecureMessagePayloadContext,
    ): SecureMessagePayloadWriteSession

    /**
     * Returns the durable classification for the explicit outgoing-text class.
     *
     * A null value means no protected class was selected. Any non-null value means the message
     * was selected for protected handling; PENDING/UNAVAILABLE are not eligible for body fallback.
     */
    fun outgoingTextPayloadMode(
        context: SecureOutgoingTextPayloadContext,
    ): SecureMessagePayloadMode?

    fun protectedTextPayloadMode(
        context: SecureMessagePayloadContext,
    ): SecureMessagePayloadMode?

    /**
     * Opens only the exact protected payload selected by the explicit outgoing-text class.
     *
     * This is a no-fallback reader boundary: a non-outgoing or missing Message, unresolved
     * evidence, missing object/key, or verification failure is unavailable. It must never make
     * a caller inspect Message.body, a path, URI, FileBackend object or cache as a substitute.
     */
    fun openOutgoingTextPayload(
        context: SecureOutgoingTextPayloadContext,
    ): SecureMessagePayloadReadSession

    /** Opens any selected protected text payload; present non-PROTECTED modes fail closed. */
    fun openProtectedTextPayload(
        context: SecureMessagePayloadContext,
    ): SecureMessagePayloadReadSession

    /**
     * Opens a controlled verified-read session after validating that [reference] is the
     * account-scoped Message DB relation for the requested message.
     *
     * Missing/inconsistent records, wrong account, wrong namespace or a non-COMMITTED Store
     * object must fail closed. Returned bytes remain provisional until terminal verification.
     */
    fun open(reference: SecureMessagePayloadReference): SecureContentReadSession

    /**
     * Resolves the current account-owned Message DB relation and opens only its protected payload.
     *
     * A consumer supplies no content ID and must not derive one from a Message body, path, URI,
     * legacy attachment or cache. Missing or inconsistent relation/store state is unavailable.
     */
    fun open(
        accountUuid: String,
        messageUuid: String,
    ): SecureMessagePayloadReadSession

    /**
     * Retires one exact account-scoped payload relation. The Store object is retired before the
     * Message DB reference; durable retirement evidence keeps reader access fail closed across an
     * interrupted operation.
     */
    fun retire(reference: SecureMessagePayloadReference)

    /**
     * Resolves and retires the current account-owned Message DB relation.
     *
     * A normal deletion owner supplies no content ID. Absence of a relation is idempotent; an
     * existing relation must be retired before the owner removes its Message DB row.
     */
    fun retire(
        accountUuid: String,
        messageUuid: String,
    )

    /**
     * Retires every protected payload relation currently owned by one account-scoped conversation.
     *
     * The coordinator resolves durable relations itself. A failure leaves the remaining Message DB
     * rows for recovery; a caller must not bulk-delete the conversation first.
     */
    fun retireAllForConversation(
        accountUuid: String,
        conversationUuid: String,
    )

    /**
     * Performs conservative recovery of interrupted coordinator publications.
     *
     * Recovery must not infer a message relation from a path, URI, blob record, key material or
     * plaintext cache. It may publish only from validated durable coordinator evidence.
     */
    fun recoverInterruptedPublications()

    /**
     * Performs conservative recovery of interrupted payload retirement. It may retire only the
     * exact account/message/content/namespace evidence and must not infer a relation from a blob,
     * key record, path, URI or legacy body.
     */
    fun recoverInterruptedRetirements()

    /**
     * Repairs a bounded set of committed protected-text rows that have no blind-index entries.
     *
     * The default keeps non-production coordinators source-compatible. Production reads and
     * terminally verifies each selected SCS object before deriving opaque search tokens.
     */
    fun repairSearchIndexBatch(
        accountUuid: String,
        limit: Int,
    ): SecureMessageSearchRepairResult = SecureMessageSearchRepairResult(0, 0, 0)
}

/**
 * Coordinator-owned writer capability for exactly one future protected message payload.
 */
interface SecureMessagePayloadWriteSession {
    val context: SecureMessagePayloadContext

    /**
     * Opens the only plaintext writer capability. Closing it does not publish a payload.
     */
    fun openPlaintextOutputStream(): OutputStream

    /**
     * Commits the Store object and durably publishes its Message DB relation.
     *
     * The result is a reference, never content bytes or a storage locator. If this operation
     * fails, normal message readers must not obtain a partial or legacy plaintext payload.
     */
    fun commitAndPublish(): SecureMessagePayloadReference

    /**
     * Commits text while making its already-prepared bounded UTF-8 available only to the normal
     * in-process publication transaction for blind indexing. Implementations must not persist,
     * retain or place these bytes in recovery evidence. The default preserves compatibility for
     * non-indexing implementations; production overrides it to avoid an index-only Store read.
     */
    fun commitAndPublish(indexPlaintextUtf8: ByteArray): SecureMessagePayloadReference =
        commitAndPublish()

    /**
     * Cancels an incomplete candidate without normal reader visibility.
     */
    fun abort()
}

/**
 * Narrow protected-message reader capability. The coordinator returns this as a
 * [SecureContentReadSession]; this alias documents the intended reader boundary for future
 * message repositories without adding a second stream API.
 */
typealias SecureMessagePayloadReadSession = SecureContentReadSession
