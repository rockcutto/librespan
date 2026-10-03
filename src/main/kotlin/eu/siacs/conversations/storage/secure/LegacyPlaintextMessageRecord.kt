package eu.siacs.conversations.storage.secure

/**
 * One short-lived migration input selected from historical Message.body storage.
 *
 * This is deliberately not a Secure Content model and must never be persisted outside the legacy
 * Message row. [rowId] exists only for bounded keyset pagination; [body] remains authoritative
 * until an exact committed SCS relation is atomically promoted to PROTECTED and the body is
 * retired.
 */
data class LegacyPlaintextMessageRecord(
    val rowId: Long,
    val accountUuid: String,
    val messageUuid: String,
    val body: String,
) {
    init {
        require(rowId > 0) { "rowId must be positive" }
        require(accountUuid.isNotBlank()) { "accountUuid must not be blank" }
        require(messageUuid.isNotBlank()) { "messageUuid must not be blank" }
        require(body.isNotEmpty()) { "legacy plaintext body must not be empty" }
    }
}
