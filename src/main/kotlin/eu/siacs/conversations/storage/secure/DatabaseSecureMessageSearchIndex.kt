package eu.siacs.conversations.storage.secure

import android.content.ContentValues
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.persistance.DatabaseBackend
import java.io.IOException

data class SecureMessageSearchCandidate(
    val accountUuid: String,
    val conversationUuid: String,
    val messageUuid: String,
    val timeSent: Long,
    val contactJid: String,
    val conversationMode: Int,
    val nextCounterpart: String?,
)

data class SecureMessageSearchCursor(
    val timeSent: Long,
    val messageUuid: String,
)

data class SecureMessageSearchCandidatePage(
    val candidates: List<SecureMessageSearchCandidate>,
    val nextCursor: SecureMessageSearchCursor?,
    val exhausted: Boolean,
)

internal data class SecureMessageSearchIndexQueryGroup(
    val requiredTokens: Set<String>,
    val excludedTokens: Set<String>,
)


internal data class SecureMessageSearchWhere(
    val sql: String,
    val args: List<String>,
)

internal fun secureMessageSearchWhere(
    accountUuid: String,
    conversationUuid: String?,
    cursor: SecureMessageSearchCursor?,
): SecureMessageSearchWhere {
    val args = mutableListOf(accountUuid)
    val where =
        StringBuilder(
            "conversation.${Conversation.ACCOUNT}=? AND message.${Message.MODERATED}=0",
        )
    if (conversationUuid != null) {
        where.append(" AND message.${Message.CONVERSATION}=?")
        args += conversationUuid
    }
    if (cursor != null) {
        where.append(
            " AND (message.${Message.TIME_SENT}<? OR " +
                "(message.${Message.TIME_SENT}=? AND message.${Message.UUID}<?))",
        )
        args += cursor.timeSent.toString()
        args += cursor.timeSent.toString()
        args += cursor.messageUuid
    }
    return SecureMessageSearchWhere(where.toString(), args)
}

/**
 * SQLCipher-backed persistence for opaque secure-message blind-index tokens.
 *
 * The table deliberately contains only ownership identifiers plus keyed HMAC digests. No message
 * body, normalized word, snippet, path, URI or content key is stored here. The version is encoded
 * in the table name so a future tokenization/HMAC format can be rebuilt without interpreting old
 * digests as compatible.
 */
class DatabaseSecureMessageSearchIndex(
    private val databaseBackend: DatabaseBackend,
) {
    init {
        ensureSchema()
    }

    fun resolveConversationUuid(accountUuid: String, messageUuid: String): String? {
        val sql =
            "SELECT ${Message.TABLENAME}.${Message.CONVERSATION} " +
                "FROM ${Message.TABLENAME} JOIN ${Conversation.TABLENAME} " +
                "ON ${Message.TABLENAME}.${Message.CONVERSATION}=${Conversation.TABLENAME}.${Conversation.UUID} " +
                "WHERE ${Message.TABLENAME}.${Message.UUID}=? " +
                "AND ${Conversation.TABLENAME}.${Conversation.ACCOUNT}=? LIMIT 1"
        databaseBackend.readableDatabase.rawQuery(sql, arrayOf(messageUuid, accountUuid)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun resolveAccountUuid(conversationUuid: String): String? {
        val sql =
            "SELECT ${Conversation.ACCOUNT} FROM ${Conversation.TABLENAME} " +
                "WHERE ${Conversation.UUID}=? LIMIT 1"
        databaseBackend.readableDatabase.rawQuery(sql, arrayOf(conversationUuid)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun replaceMessage(
        accountUuid: String,
        conversationUuid: String,
        messageUuid: String,
        tokens: Set<String>,
    ) {
        require(accountUuid.isNotBlank())
        require(conversationUuid.isNotBlank())
        require(messageUuid.isNotBlank())
        val database = databaseBackend.writableDatabase
        database.beginTransaction()
        try {
            database.delete(
                TABLE,
                "$ACCOUNT_UUID=? AND $MESSAGE_UUID=?",
                arrayOf(accountUuid, messageUuid),
            )
            tokens.forEach { token ->
                val values = ContentValues().apply {
                    put(ACCOUNT_UUID, accountUuid)
                    put(CONVERSATION_UUID, conversationUuid)
                    put(MESSAGE_UUID, messageUuid)
                    put(TOKEN, token)
                }
                val rowId =
                    database.insertWithOnConflict(
                        TABLE,
                        null,
                        values,
                        net.zetetic.database.sqlcipher.SQLiteDatabase.CONFLICT_IGNORE,
                    )
                if (rowId == -1L) {
                    throw IOException("Unable to persist secure message search token")
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun deleteMessage(accountUuid: String, messageUuid: String) {
        databaseBackend.writableDatabase.delete(
            TABLE,
            "$ACCOUNT_UUID=? AND $MESSAGE_UUID=?",
            arrayOf(accountUuid, messageUuid),
        )
    }

    fun deleteConversation(accountUuid: String, conversationUuid: String) {
        databaseBackend.writableDatabase.delete(
            TABLE,
            "$ACCOUNT_UUID=? AND $CONVERSATION_UUID=?",
            arrayOf(accountUuid, conversationUuid),
        )
    }

    fun deleteAccount(accountUuid: String) {
        databaseBackend.writableDatabase.delete(
            TABLE,
            "$ACCOUNT_UUID=?",
            arrayOf(accountUuid),
        )
    }

    fun findUnindexedProtectedPayloads(
        accountUuid: String,
        limit: Int,
    ): List<SecureMessagePayloadReference> {
        require(accountUuid.isNotBlank())
        require(limit > 0)
        val sql =
            "SELECT reference.message_uuid,reference.content_id " +
                "FROM secure_message_payload_reference reference " +
                "JOIN secure_message_payload_mode mode " +
                "ON mode.account_uuid=reference.account_uuid " +
                "AND mode.message_uuid=reference.message_uuid " +
                "JOIN ${Message.TABLENAME} message " +
                "ON message.${Message.UUID}=reference.message_uuid " +
                "JOIN ${Conversation.TABLENAME} conversation " +
                "ON conversation.${Conversation.UUID}=message.${Message.CONVERSATION} " +
                "WHERE reference.account_uuid=? AND mode.mode=? " +
                "AND conversation.${Conversation.ACCOUNT}=reference.account_uuid " +
                "AND message.${Message.TYPE} IN (?,?) " +
                "AND NOT EXISTS (SELECT 1 FROM $TABLE search " +
                "WHERE search.$ACCOUNT_UUID=reference.account_uuid " +
                "AND search.$MESSAGE_UUID=reference.message_uuid) " +
                "ORDER BY message.${Message.TIME_SENT} DESC,message.${Message.UUID} DESC LIMIT ?"
        val args =
            arrayOf(
                accountUuid,
                SecureMessagePayloadMode.PROTECTED.persistedValue,
                Message.TYPE_TEXT.toString(),
                Message.TYPE_PRIVATE.toString(),
                limit.toString(),
            )
        val references = mutableListOf<SecureMessagePayloadReference>()
        databaseBackend.readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                references +=
                    SecureMessagePayloadReference(
                        accountUuid = accountUuid,
                        messageUuid = cursor.getString(0),
                        contentId = cursor.getString(1),
                    )
            }
        }
        return references
    }

    /**
     * Returns one deterministic newest-first page after SQL has applied all blind-index query
     * semantics. The cursor is a strict (timeSent, messageUuid) keyset boundary.
     */
    internal fun findMessagePage(
        accountUuid: String,
        conversationUuid: String?,
        queryGroups: List<SecureMessageSearchIndexQueryGroup>,
        cursor: SecureMessageSearchCursor?,
        limit: Int,
    ): SecureMessageSearchCandidatePage {
        require(limit > 0)
        val validGroups = queryGroups.filter { it.requiredTokens.isNotEmpty() }
        if (validGroups.isEmpty()) {
            return SecureMessageSearchCandidatePage(emptyList(), null, true)
        }

        val candidateArgs = mutableListOf<String>()
        val candidateSql =
            validGroups.joinToString(" UNION ") { group ->
                val sql =
                    StringBuilder(
                        "SELECT candidate.$MESSAGE_UUID AS $MESSAGE_UUID FROM $TABLE candidate " +
                            "WHERE candidate.$ACCOUNT_UUID=?",
                    )
                candidateArgs += accountUuid
                if (conversationUuid != null) {
                    sql.append(" AND candidate.$CONVERSATION_UUID=?")
                    candidateArgs += conversationUuid
                }
                sql.append(" AND candidate.$TOKEN IN (")
                sql.append(group.requiredTokens.joinToString(",") { "?" })
                sql.append(')')
                candidateArgs += group.requiredTokens
                if (group.excludedTokens.isNotEmpty()) {
                    sql.append(
                        " AND NOT EXISTS (SELECT 1 FROM $TABLE excluded " +
                            "WHERE excluded.$ACCOUNT_UUID=? " +
                            "AND excluded.$MESSAGE_UUID=candidate.$MESSAGE_UUID " +
                            "AND excluded.$TOKEN IN (",
                    )
                    candidateArgs += accountUuid
                    sql.append(group.excludedTokens.joinToString(",") { "?" })
                    sql.append("))")
                    candidateArgs += group.excludedTokens
                }
                sql.append(" GROUP BY candidate.$MESSAGE_UUID")
                sql.append(" HAVING COUNT(DISTINCT candidate.$TOKEN)=?")
                candidateArgs += group.requiredTokens.size.toString()
                sql.toString()
            }

        val searchWhere = secureMessageSearchWhere(accountUuid, conversationUuid, cursor)
        val args = candidateArgs.toMutableList()
        args += searchWhere.args
        args += limit.toString()

        val sql =
            "SELECT message.${Message.UUID},message.${Message.CONVERSATION}," +
                "message.${Message.TIME_SENT},conversation.${Conversation.CONTACTJID}," +
                "conversation.${Conversation.MODE},conversation.${Conversation.NEXT_COUNTERPART} " +
                "FROM ${Message.TABLENAME} message " +
                "JOIN ${Conversation.TABLENAME} conversation " +
                "ON message.${Message.CONVERSATION}=conversation.${Conversation.UUID} " +
                "JOIN ($candidateSql) candidate_page " +
                "ON candidate_page.$MESSAGE_UUID=message.${Message.UUID} " +
                "WHERE ${searchWhere.sql} " +
                "ORDER BY message.${Message.TIME_SENT} DESC,message.${Message.UUID} DESC LIMIT ?"

        val candidates = mutableListOf<SecureMessageSearchCandidate>()
        databaseBackend.readableDatabase.rawQuery(sql, args.toTypedArray()).use { result ->
            while (result.moveToNext()) {
                candidates +=
                    SecureMessageSearchCandidate(
                        accountUuid = accountUuid,
                        messageUuid = result.getString(0),
                        conversationUuid = result.getString(1),
                        timeSent = result.getLong(2),
                        contactJid = result.getString(3),
                        conversationMode = result.getInt(4),
                        nextCounterpart =
                            if (result.isNull(5)) null else result.getString(5),
                    )
            }
        }
        val nextCursor =
            candidates.lastOrNull()?.let { candidate ->
                SecureMessageSearchCursor(candidate.timeSent, candidate.messageUuid)
            }
        return SecureMessageSearchCandidatePage(
            candidates = candidates,
            nextCursor = nextCursor,
            exhausted = candidates.size < limit,
        )
    }

    private fun ensureSchema() {
        databaseBackend.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$ACCOUNT_UUID TEXT NOT NULL," +
                "$CONVERSATION_UUID TEXT NOT NULL," +
                "$MESSAGE_UUID TEXT NOT NULL," +
                "$TOKEN TEXT NOT NULL," +
                "PRIMARY KEY($ACCOUNT_UUID,$MESSAGE_UUID,$TOKEN))",
        )
        databaseBackend.writableDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS ${TABLE}_token ON $TABLE($ACCOUNT_UUID,$TOKEN)",
        )
        databaseBackend.writableDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS ${TABLE}_conversation_token " +
                "ON $TABLE($ACCOUNT_UUID,$CONVERSATION_UUID,$TOKEN)",
        )
    }

    companion object {
        const val TABLE = "secure_message_search_v1"
        private const val ACCOUNT_UUID = "account_uuid"
        private const val CONVERSATION_UUID = "conversation_uuid"
        private const val MESSAGE_UUID = "message_uuid"
        private const val TOKEN = "token_digest"
    }
}
