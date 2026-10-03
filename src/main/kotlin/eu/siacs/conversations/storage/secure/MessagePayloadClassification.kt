package eu.siacs.conversations.storage.secure

/**
 * Explicit local content-protection classification for message text.
 *
 * LEGACY_PLAINTEXT is deliberately derived from durable database facts instead of being inserted
 * as a mode row for every historical message. This avoids a cold-start backfill while still making
 * the security state exact and queryable.
 */
enum class MessagePayloadClassification {
    LEGACY_PLAINTEXT,
    PENDING,
    PROTECTED,
    UNAVAILABLE,
    NOT_APPLICABLE;

    companion object {
        @JvmStatic
        fun classify(
            secureMode: SecureMessagePayloadMode?,
            isTextPayload: Boolean,
            hasDurablePlaintextBody: Boolean,
        ): MessagePayloadClassification =
            when (secureMode) {
                SecureMessagePayloadMode.PENDING -> PENDING
                SecureMessagePayloadMode.PROTECTED -> PROTECTED
                SecureMessagePayloadMode.UNAVAILABLE -> UNAVAILABLE
                null ->
                    if (isTextPayload && hasDurablePlaintextBody) {
                        LEGACY_PLAINTEXT
                    } else {
                        NOT_APPLICABLE
                    }
            }
    }
}
