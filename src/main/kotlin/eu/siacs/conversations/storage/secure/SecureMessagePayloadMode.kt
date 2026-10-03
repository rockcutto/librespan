package eu.siacs.conversations.storage.secure

/**
 * Durable local classification for the one U4 selected protected outgoing-text class.
 *
 * A non-null record prevents a future renderer from treating the Message DB body as an implicit
 * substitute. Only PROTECTED may be read through SecureOutgoingTextPayloadReader; PENDING and
 * UNAVAILABLE fail closed. This is neither an authorization token nor a transport state.
 */
enum class SecureMessagePayloadMode(
    val persistedValue: String,
) {
    PENDING("pending"),
    PROTECTED("protected"),
    UNAVAILABLE("unavailable");

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): SecureMessagePayloadMode =
            entries.firstOrNull { it.persistedValue == value } ?: UNAVAILABLE
    }
}
