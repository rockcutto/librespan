package eu.siacs.conversations.persistance

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode
import eu.siacs.conversations.xmpp.Jid
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real SQLCipher boundary, including a closed/reopened DB and stale objects. */
@RunWith(AndroidJUnit4::class)
class AttachmentUploadPersistenceInstrumentationTest {
    private lateinit var context: Context
    private lateinit var backend: DatabaseBackendImpl

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        context.deleteDatabase("history")
        backend = DatabaseBackendImpl(context, "upload-persistence-test")
        backend.writableDatabase
    }

    @After fun tearDown() {
        backend.close()
        context.deleteDatabase("history")
    }

    @Test fun canonicalMediaSurvivesReloadAndDatabaseReopenInChatsAndMucs() {
        for (mode in listOf(Conversation.MODE_SINGLE, Conversation.MODE_MULTI)) {
            for ((type, params) in listOf(
                Message.TYPE_IMAGE to "|42|640|480",
                Message.TYPE_FILE to "|42|1920|1080",
                Message.TYPE_FILE to "|42|0|0|1700",
                Message.TYPE_FILE to "|42",
                Message.TYPE_PRIVATE_FILE to "|42",
            )) {
                val message = fixture(mode, type, params)
                assertFalse(backend.prepareOutgoingAttachmentMessage(message, false))
                assertTrue(backend.prepareOutgoingAttachmentMessage(message, true))
                assertTrue(backend.prepareOutgoingAttachmentMessage(message, false))
                assertFalse(backend.prepareOutgoingAttachmentMessage(message, true))
                val url = "https://upload.example.test/media/${message.uuid}"
                message.body = url + params
                assertTrue(backend.persistOutgoingAttachmentUpload(message, params, url))
                assertEquals(url + params, reload(message).body)
                // A stale status/echo update is deliberately not allowed to write BODY.
                message.body = params
                message.status = Message.STATUS_SEND_RECEIVED
                backend.updateMessage(message, false)
                assertEquals(url + params, reload(message).body)
                backend.close()
                backend = DatabaseBackendImpl(context, "upload-persistence-test")
                val restored = reload(message)
                assertEquals(url + params, restored.body)
                assertEquals(url, restored.fileParams.url.toString())
                assertEquals(type, restored.type)
            }
        }
    }

    @Test fun aesGcmFragmentSurvivesReopenWithoutRewriting() {
        val message = fixture()
        assertTrue(backend.prepareOutgoingAttachmentMessage(message, true))
        val url = "aesgcm://upload.example.test/media#" + "ab".repeat(44)
        message.body = "$url|42"
        assertTrue(backend.persistOutgoingAttachmentUpload(message, "|42", url))
        backend.close()
        backend = DatabaseBackendImpl(context, "upload-persistence-test")
        assertEquals("$url|42", reload(message).body)
    }

    @Test fun competingCallbacksMissingRowsAndInvalidLocatorsCannotOverwrite() {
        val message = fixture()
        val first = "https://upload.example.test/first"
        val second = "https://upload.example.test/second"
        message.body = "$first|42"
        assertFalse(backend.persistOutgoingAttachmentUpload(message, "|42", first))
        message.body = "|42"
        assertTrue(backend.prepareOutgoingAttachmentMessage(message, true))
        message.body = "$first|42"
        assertTrue(backend.persistOutgoingAttachmentUpload(message, "|42", first))
        message.body = "$second|42"
        assertFalse(backend.persistOutgoingAttachmentUpload(message, "|42", second))
        assertEquals("$first|42", reload(message).body)
        message.body = "secret text"
        assertFalse(backend.persistOutgoingAttachmentUpload(message, "|42", second))
        assertEquals("$first|42", reload(message).body)
    }

    @Test fun durableTombstonesRejectStaleUploadObjects() {
        for (column in listOf(Message.MODERATED, Message.RETRACTED, Message.DELETED)) {
            val message = fixture()
            assertTrue(backend.prepareOutgoingAttachmentMessage(message, true))
            backend.writableDatabase.update(Message.TABLENAME,
                ContentValues().apply { put(column, 1); put(Message.BODY, "") },
                Message.UUID + "=?", arrayOf(message.uuid))
            val url = "https://upload.example.test/stale"
            message.body = "$url|42"
            assertFalse(backend.persistOutgoingAttachmentUpload(message, "|42", url))
            assertEquals("", reload(message).body)
        }
    }

    @Test fun protectedTextModeAndPlaintextCannotUseMediaPersistence() {
        val message = fixture(type = Message.TYPE_TEXT, params = "protected-text-sentinel")
        assertTrue(backend.createProtectedTextMessage(message, message.conversation.account.uuid))
        assertEquals("", reload(message).body)
        backend.updateMessage(message, true)
        assertEquals("", reload(message).body)
        // Even a stale/misclassified Java object and a numeric DB body cannot bypass durable mode.
        backend.writableDatabase.update(Message.TABLENAME,
            ContentValues().apply { put(Message.TYPE, Message.TYPE_FILE); put(Message.BODY, "|42") },
            Message.UUID + "=?", arrayOf(message.uuid))
        message.type = Message.TYPE_FILE
        message.setSecureMessagePayloadMode(null)
        val url = "https://upload.example.test/forbidden"
        message.body = "$url|42"
        assertFalse(backend.persistOutgoingAttachmentUpload(message, "|42", url))
        assertEquals("|42", reload(message).body)
        assertEquals(SecureMessagePayloadMode.PENDING,
            backend.getSecureMessagePayloadMode(message.conversation.account.uuid, message.uuid))
        val text = fixture(type = Message.TYPE_TEXT, params = "plaintext-sentinel")
        assertFalse(backend.prepareOutgoingAttachmentMessage(text, true))
    }

    @Test fun incomingOrWrongConversationCannotUseOutgoingBoundary() {
        val message = fixture()
        message.status = Message.STATUS_RECEIVED
        assertFalse(backend.prepareOutgoingAttachmentMessage(message, true))
        message.status = Message.STATUS_WAITING
        backend.writableDatabase.delete(Conversation.TABLENAME, Conversation.UUID + "=?",
            arrayOf(message.conversationUuid))
        assertFalse(backend.prepareOutgoingAttachmentMessage(message, true))
    }

    private fun fixture(mode: Int = Conversation.MODE_SINGLE, type: Int = Message.TYPE_FILE,
                        params: String = "|42"): Message {
        val account = Account(Jid.of("sender@example.test"), "")
        val conversation = Conversation("test", account, Jid.of("peer@example.test"), mode, null)
        backend.writableDatabase.insertOrThrow(Account.TABLENAME, null,
            ContentValues().apply { put(Account.UUID, account.uuid) })
        backend.writableDatabase.insertOrThrow(Conversation.TABLENAME, null,
            ContentValues().apply {
                put(Conversation.UUID, conversation.uuid)
                put(Conversation.ACCOUNT, account.uuid)
            })
        return Message(conversation, params, Message.ENCRYPTION_NONE).apply {
            this.type = type
            status = Message.STATUS_WAITING
        }
    }

    private fun reload(message: Message): Message =
        backend.readableDatabase.query(Message.TABLENAME, null, Message.UUID + "=?",
            arrayOf(message.uuid), null, null, null).use {
            assertTrue(it.moveToFirst())
            Message.fromCursor(it, message.conversation as Conversation)
        }
}
