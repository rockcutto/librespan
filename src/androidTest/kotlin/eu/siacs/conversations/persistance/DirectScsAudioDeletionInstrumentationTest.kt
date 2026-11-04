package eu.siacs.conversations.persistance

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.xmpp.Jid
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual observer/startup SQL selectors; no suppression of legitimate missing legacy files. */
@RunWith(AndroidJUnit4::class)
class DirectScsAudioDeletionInstrumentationTest {
    private lateinit var context: Context
    private lateinit var backend: DatabaseBackendImpl

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        context.deleteDatabase("history")
        backend = DatabaseBackendImpl(context, "audio-deletion-test")
        backend.writableDatabase
    }
    @After fun tearDown() { backend.close(); context.deleteDatabase("history") }

    @Test fun retiredStagingDoesNotMatchPathlessSecureAudioButRealLegacyDeletionStillMatches() {
        val account = Account(Jid.of("sender@example.test"), "")
        val conversation = Conversation("test", account, Jid.of("peer@example.test"), Conversation.MODE_SINGLE, null)
        backend.writableDatabase.insertOrThrow(Account.TABLENAME, null,
            ContentValues().apply { put(Account.UUID, account.uuid) })
        backend.writableDatabase.insertOrThrow(Conversation.TABLENAME, null,
            ContentValues().apply {
                put(Conversation.UUID, conversation.uuid); put(Conversation.ACCOUNT, account.uuid)
            })
        val staging = File(context.cacheDir, "retired-audio.mp3")
        val secure = Message(conversation, "https://upload.example.test/audio.mp3|4|0|0|900", Message.ENCRYPTION_NONE)
            .apply { type = Message.TYPE_FILE; relativeFilePath = null }
        val legacy = Message(conversation, "|4", Message.ENCRYPTION_NONE)
            .apply { type = Message.TYPE_FILE; relativeFilePath = staging.absolutePath }
        backend.createMessage(secure)
        backend.createMessage(legacy)
        assertEquals(listOf(legacy.uuid), backend.filePathInfo.map { it.uuid.toString() })
        assertEquals(listOf(legacy.uuid), backend.markFileAsDeleted(staging, false))
        assertTrue(deleted(legacy))
        assertFalse(deleted(secure))
        // Explicit deletion remains effective for a protected, pathless message as well.
        backend.markFileAsDeleted(listOf(secure.uuid))
        assertTrue(deleted(secure))
    }

    private fun deleted(message: Message): Boolean = backend.readableDatabase.query(
        Message.TABLENAME, arrayOf(Message.DELETED), Message.UUID + "=?", arrayOf(message.uuid),
        null, null, null).use { assertTrue(it.moveToFirst()); it.getInt(0) == 1 }
}
