package eu.siacs.conversations.storage.secure

import java.io.IOException

/**
 * Coordinates blind indexing and prefix search for protected message text.
 *
 * Plaintext is accepted only at publication/update time, converted immediately to keyed digests,
 * and is never persisted by this component. Search consumes the same user terms used by the legacy
 * FTS UI and returns message UUIDs; callers can then load/decrypt only the matched messages.
 */
class SecureMessageSearchCoordinator(
    private val codec: SecureMessageSearchIndexCodec,
    private val index: DatabaseSecureMessageSearchIndex,
) {
    @Throws(IOException::class)
    fun replaceMessage(
        accountUuid: String,
        messageUuid: String,
        plaintext: String,
    ) {
        val conversationUuid =
            index.resolveConversationUuid(accountUuid, messageUuid)
                ?: throw IOException("Secure search message ownership is unavailable")
        replaceMessage(accountUuid, conversationUuid, messageUuid, plaintext)
    }

    fun replaceMessage(
        accountUuid: String,
        conversationUuid: String,
        messageUuid: String,
        plaintext: String,
    ) {
        index.replaceMessage(
            accountUuid = accountUuid,
            conversationUuid = conversationUuid,
            messageUuid = messageUuid,
            tokens = codec.indexTerms(accountUuid, plaintext),
        )
    }

    fun deleteMessage(accountUuid: String, messageUuid: String) {
        index.deleteMessage(accountUuid, messageUuid)
    }

    fun deleteConversation(accountUuid: String, conversationUuid: String) {
        index.deleteConversation(accountUuid, conversationUuid)
    }

    fun deleteAccount(accountUuid: String) {
        index.deleteAccount(accountUuid)
    }

    fun resolveAccountUuid(conversationUuid: String): String? =
        index.resolveAccountUuid(conversationUuid)

    fun findUnindexedProtectedPayloads(
        accountUuid: String,
        limit: Int,
    ): List<SecureMessagePayloadReference> =
        index.findUnindexedProtectedPayloads(accountUuid, limit)

    fun searchCandidatePage(
        accountUuid: String,
        conversationUuid: String?,
        userTerms: List<String>,
        cursor: SecureMessageSearchCursor?,
        limit: Int,
    ): SecureMessageSearchCandidatePage {
        val queryGroups =
            parse(userTerms).mapNotNull { group ->
                if (!group.valid) return@mapNotNull null
                val required = linkedSetOf<String>()
                for (term in group.required) {
                    val digests = codec.prefixQueryTerms(accountUuid, term)
                    if (digests.isEmpty()) return@mapNotNull null
                    required += digests
                }
                val excluded = linkedSetOf<String>()
                for (term in group.excluded) {
                    val digests = codec.exactQueryTerms(accountUuid, term)
                    if (digests.isEmpty()) return@mapNotNull null
                    excluded += digests
                }
                if (required.isEmpty()) {
                    null
                } else {
                    SecureMessageSearchIndexQueryGroup(required, excluded)
                }
            }
        return index.findMessagePage(
            accountUuid = accountUuid,
            conversationUuid = conversationUuid,
            queryGroups = queryGroups,
            cursor = cursor,
            limit = limit,
        )
    }

    /** Applies the original normalized token-prefix semantics to a decrypted candidate. */
    fun matchesPlaintext(
        plaintext: String,
        userTerms: List<String>,
    ): Boolean =
        parse(userTerms).any { group ->
            group.valid &&
                codec.matchesQueryGroup(
                    plaintext,
                    group.required,
                    group.excluded,
                )
        }

    private fun parse(userTerms: List<String>): List<QueryGroup> {
        val groups = mutableListOf<QueryGroup>()
        var current = QueryGroup()
        fun flush() {
            if (current.required.isNotEmpty() || current.excluded.isNotEmpty() || !current.valid) {
                groups += current
            }
            current = QueryGroup()
        }
        userTerms.forEach { raw ->
            val term = raw.trim()
            if (term.isEmpty()) return@forEach
            when {
                term.equals("OR", ignoreCase = true) -> flush()
                term.equals("AND", ignoreCase = true) -> Unit
                term.startsWith("-") -> {
                    val value = normalizeSearchTerm(term.substring(1), allowTrailingWildcard = false)
                    if (value == null) current.valid = false else current.excluded += value
                }
                else -> {
                    val value = normalizeSearchTerm(term, allowTrailingWildcard = true)
                    if (value == null) current.valid = false else current.required += value
                }
            }
        }
        flush()
        return groups
    }

    private fun normalizeSearchTerm(raw: String, allowTrailingWildcard: Boolean): String? {
        if (raw.isBlank()) return null
        val starCount = raw.count { it == '*' }
        if (starCount == 0) return raw
        if (!allowTrailingWildcard || starCount != 1 || !raw.endsWith('*')) {
            return null
        }
        return raw.dropLast(1).takeIf { it.isNotBlank() }
    }

    private data class QueryGroup(
        val required: MutableList<String> = mutableListOf(),
        val excluded: MutableList<String> = mutableListOf(),
        var valid: Boolean = true,
    )
}
