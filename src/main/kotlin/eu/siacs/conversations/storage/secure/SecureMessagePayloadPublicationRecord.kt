package eu.siacs.conversations.storage.secure

/**
 * Durable non-plaintext evidence for one protected message-payload publication or replacement.
 *
 * [previousContentId] is present only for a replacement. It lets recovery prove the old exact
 * relation and retire the old object only after the new committed relation is authoritative.
 * The record contains no plaintext, path, URI, blob locator or key material.
 */
data class SecureMessagePayloadPublicationRecord(
    val publicationId: String,
    val accountUuid: String,
    val messageUuid: String,
    val contentId: String,
    val namespace: String,
    val phase: SecureMessagePayloadPublicationPhase,
    val createdAt: Long,
    val updatedAt: Long,
    val previousContentId: String? = null,
) {
    init {
        require(publicationId.isNotBlank()) { "publicationId must not be blank" }
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(previousContentId == null || previousContentId.isNotBlank()) {
            "previousContentId must be null or nonblank"
        }
        require(previousContentId != contentId) {
            "replacement must not point to the same content"
        }
        require(createdAt >= 0L) { "createdAt must not be negative" }
        require(updatedAt >= createdAt) { "updatedAt must not precede createdAt" }
    }
}

enum class SecureMessagePayloadPublicationPhase {
    PREPARED,
    STORE_COMMITTED,
    MESSAGE_LINKED,
    FINALIZED,
    FAILED,
}
