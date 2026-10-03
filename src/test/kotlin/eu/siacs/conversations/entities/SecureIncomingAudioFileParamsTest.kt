package eu.siacs.conversations.entities

import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureIncomingAudioFileParamsTest {
    @Test
    fun persistedAudioRuntimeSurvivesFileParamsReparse() {
        val account = Account(Jid.of("me@example.test"), "")
        val conversation = Conversation(
            "secure-audio",
            account,
            Jid.of("peer@example.test"),
            Conversation.MODE_SINGLE,
            null,
        )
        val message = Message(conversation, "https://files.example.test/voice.ogg|157286|0|0|42000", Message.ENCRYPTION_NONE)
        message.type = Message.TYPE_FILE

        message.resetFileParams()

        assertEquals(42000, message.fileParams.runtime)
    }
}
