package eu.siacs.conversations.storage.secure

/**
 * Durable non-plaintext evidence for retiring one protected message payload.
 *
 * This record coordinates only the ordered relation retirement outside the Store. It contains no
 * payload, path, URI, blob locator, Store key material or crypto state. Store-private key/blob/
 * metadata deletion remains authoritative in the Secure Content Store recovery journal.
 */
data class SecureMessagePayloadRetirementRecord(
    val retirementId: String,
    val accountUuid: String,
    val messageUuid: String,
    val contentId: String,
    val namespace: String,
    val phase: SecureMessagePayloadRetirementPhase,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(retirementId.isNotBlank()) { "retirementId must not be blank" }
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(namespace == SecureMessagePayloadContext.NAMESPACE) {
            "secure message payload retirement requires the message-payload namespace"
        }
        require(createdAt >= 0L) { "createdAt must not be negative" }
        require(updatedAt >= createdAt) { "updatedAt must not precede createdAt" }
    }
}

enum class SecureMessagePayloadRetirementPhase {
    PREPARED,
    STORE_RETIRED,
    REFERENCE_RETIRED,
    FINALIZED,
    FAILED,
}
