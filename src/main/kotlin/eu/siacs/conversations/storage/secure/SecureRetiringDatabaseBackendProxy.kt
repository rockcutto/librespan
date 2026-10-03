package eu.siacs.conversations.storage.secure

import android.content.Context
import eu.siacs.conversations.Config
import eu.siacs.conversations.Conversations
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.xmpp.Jid
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Defense-in-depth crypto-first delete boundary for secure message media.
 *
 * Existing service deletion already owns product semantics. This proxy protects the persistence
 * boundary itself when media rollout is enabled, including automatic retention expiry: secure media
 * keys/blobs are retired before the delegate can remove Message ownership rows. Any retirement
 * failure prevents the database delete and leaves ownership durable for retry/recovery.
 */
object SecureRetiringDatabaseBackendProxy {
    @JvmStatic
    fun wrap(context: Context, delegate: DatabaseBackend): DatabaseBackend {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) return delegate
        val application = context.applicationContext as? Conversations
            ?: throw IllegalStateException("Secure media rollout requires Conversations application")
        val handler = Handler(delegate, application)
        return Proxy.newProxyInstance(
            DatabaseBackend::class.java.classLoader,
            arrayOf(DatabaseBackend::class.java),
            handler,
        ) as DatabaseBackend
    }

    private class Handler(
        private val delegate: DatabaseBackend,
        private val application: Conversations,
    ) : InvocationHandler {
        private val boundary: SecureMessageRetirementBoundary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            SecureMessageRetirementBoundary(
                payloadCoordinator = null,
                store = application.secureContentStoreProvider.get(),
            )
        }

        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val actualArgs = args ?: emptyArray()
            when (method.name) {
                "deleteMessageInConversation" -> retireSingle(actualArgs)
                "deleteMessagesInConversation" -> retireConversation(actualArgs)
                "deleteConversation" -> retireDeletedConversation(actualArgs)
                "expireOldMessages" -> retireExpired(actualArgs)
            }
            return try {
                method.invoke(delegate, *actualArgs)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }

        private fun retireSingle(args: Array<out Any?>) {
            val conversation = args.getOrNull(0) as? Conversation ?: return
            val message = args.getOrNull(1) as? Message ?: return
            boundary.retireMessage(conversation.account.uuid, message.uuid)
        }

        private fun retireConversation(args: Array<out Any?>) {
            val conversation = args.getOrNull(0) as? Conversation ?: return
            retireConversation(conversation)
        }

        private fun retireDeletedConversation(args: Array<out Any?>) {
            val account = args.getOrNull(0) as? Account ?: return
            val contact = args.getOrNull(1) as? Jid ?: return
            val counterpart = args.getOrNull(2) as? Jid
            val conversation = delegate.findConversation(account, contact, counterpart) ?: return
            retireConversation(conversation)
        }

        private fun retireConversation(conversation: Conversation) {
            val accountUuid = conversation.account.uuid
            val conversationUuid = conversation.uuid
            val messageUuids =
                delegate.getMessageUuidsForConversation(accountUuid, conversationUuid)
            boundary.retireConversation(accountUuid, conversationUuid, messageUuids)
        }

        private fun retireExpired(args: Array<out Any?>) {
            val timestamp = (args.getOrNull(0) as? Number)?.toLong() ?: return
            val sql =
                "SELECT ${Message.TABLENAME}.${Message.UUID}," +
                    "${Conversation.TABLENAME}.${Conversation.ACCOUNT} " +
                    "FROM ${Message.TABLENAME} JOIN ${Conversation.TABLENAME} " +
                    "ON ${Message.TABLENAME}.${Message.CONVERSATION}=" +
                    "${Conversation.TABLENAME}.${Conversation.UUID} " +
                    "WHERE ${Message.TABLENAME}.${Message.TIME_SENT}<?"
            val candidates = mutableListOf<Pair<String, String>>()
            delegate.readableDatabase.rawQuery(sql, arrayOf(timestamp.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    candidates += cursor.getString(1) to cursor.getString(0)
                }
            }
            candidates.distinct().forEach { (accountUuid, messageUuid) ->
                boundary.retireMessage(accountUuid, messageUuid)
            }
        }
    }
}
