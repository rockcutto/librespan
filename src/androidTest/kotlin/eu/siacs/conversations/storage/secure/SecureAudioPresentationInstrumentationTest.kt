package eu.siacs.conversations.storage.secure

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.service.AudioPlayer
import eu.siacs.conversations.utils.AudioAttachmentPresentation.Tracks
import eu.siacs.conversations.xmpp.Jid
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureAudioPresentationInstrumentationTest {
    @Test
    fun actualMp3MetadataIsReadFromDescriptorWithoutChangingOriginalBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = Base64.decode(TAGGED_MP3, Base64.DEFAULT)
        val file = File.createTempFile("p1a-tags-", ".mp3", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val result = SecureAudioPresentation.inspect(context, Uri.fromFile(file), "audio/mpeg")
            assertEquals(Tracks.AUDIO, result.tracks)
            assertEquals("P1A test title", result.title)
            assertEquals("P1A test artist", result.artist)
            assertTrue(result.durationMillis > 0)
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }

    @Test
    fun corruptOggIsNeverConfirmedAsAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("p1a-unknown-", ".ogg", context.cacheDir)
        try {
            file.writeText("unrecognized container")
            val result = SecureAudioPresentation.inspect(context, Uri.fromFile(file), "application/ogg")
            assertEquals(Tracks.UNKNOWN, result.tracks)
        } finally { file.delete() }
    }

    @Test
    fun bothRealLayoutsClearOldTagsOnRecyclingToAnotherMessage() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(appContext, R.style.Theme_Conversations3)
            val account = Account(Jid.of("me@example.test"), "")
            val conversation = Conversation("audio", account, Jid.of("peer@example.test"), Conversation.MODE_SINGLE, null)
            val first = Message(conversation, "first", Message.ENCRYPTION_NONE)
            val second = Message(conversation, "second", Message.ENCRYPTION_NONE)
            for (resource in listOf(R.layout.message_content, R.layout.item_message_content)) {
                val parent = FrameLayout(context)
                LayoutInflater.from(context).inflate(resource, parent, true)
                val row = parent.findViewById<RelativeLayout>(R.id.audio_player)
                val title = row.findViewById<TextView>(R.id.audio_title)
                val artist = row.findViewById<TextView>(R.id.audio_artist)
                row.tag = first
                title.text = "First title"
                artist.text = "First artist"
                row.findViewById<View>(R.id.audio_metadata).visibility = View.VISIBLE
                AudioPlayer.rebind(row, second)
                assertSame(second, row.tag)
                assertEquals("", title.text.toString())
                assertEquals("", artist.text.toString())
                assertEquals(View.GONE, row.findViewById<View>(R.id.audio_metadata).visibility)
            }
        }
    }

    companion object {
        // 0.1 s generated sine, MP3/ID3. No copyrighted media or binary patch entries.
        private const val TAGGED_MP3 = """
            SUQzBAAAAAAAWFRJVDIAAAAQAAADUDFBIHRlc3QgdGl0bGUAVFBFMQAAABEAAANQMUEgdGVzdCBhcnRpc3QAVFNTRQAAAA8AAANM
            YXZmNjAuMTYuMTAwAAAAAAAAAAAAAAD/+0DAAAAAAAAAAAAAAAAAAAAAAABJbmZvAAAADwAAAAUAAALAAGhoaGhoaGhoaGhoaGho
            aGhoaGiOjo6Ojo6Ojo6Ojo6Ojo6Ojo6OjrS0tLS0tLS0tLS0tLS0tLS0tLS02tra2tra2tra2tra2tra2tra2tr/////////////
            /////////////wAAAABMYXZjNjAuMzEAAAAAAAAAAAAAAAAkAwYAAAAAAAACwD1kwG0AAAAAAP/7EMQAAAR0E1VUkIAwpgmvNxog
            AgABrTlAAAFZOj1QUAgGCQHwfB8HygIAgGEQfB/UCDsTh/iDcAST9sBgOBwOAAAAAAAoiSqZFGQI6QJIFqP3hQHwExvwIpQvqBoS
            /CQNKgoAGDAA//sSxAKDxVgdIB3gACibA+NBr2hMzAkAvEAEhgDgeGfu9qZjA5ZhxBEmDAB+YEIGBgUgTGBeA8WatJWHmCCGZPnE
            tGF+KKar1KJqfiimGEDMc96Z9KZo8ZuOYkK4lOCbvqow0OMOHTHT//sQxAODxQQfGA37IkCthGKBv2xIgz6DMK8b41DOHDTrG0MJ
            4G01zAIGbqh1fmsGyaWhz9f0JEGJiJmxwbvGmJcO0cFfXBv8DuGJqE+cIyGeIxmZ+Zk8GLCzB4xT4B/6voowoRMPHDH/+xLEAwPF
            AB8YDfsiQLQEJAK8AAWjsz2GMKYck02+azS4G/MJcHA0kQEEbyZ2+GuEzWeDf6/odkQgCmA+AuYCwLhgxBtGlEwqaBI95iAhVmD6
            BeYCoIJgcgYmBqBiDQAYvbwUjQEiAQL/+xDEAoAE/DtIGaOAAJCFoMOeYAAYLwsKMgieFcHyZawxYyU2/ciKWccHY3m8mQBERZmF
            QV+WPCzAA4AVDGO0TU8RPgFovJPhNjKfp1DVbqSIBCQkDUGj31neIkxBTUUzLjEwMKqqqg==
        """
    }
}
