package eu.siacs.conversations.storage.secure

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureOutgoingTextPayloadReaderTest {
    @Test
    fun verifiedUtf8PayloadBecomesAvailableOnlyAfterTerminalVerification() {
        val session = FakeReadSession("secure text".toByteArray(Charsets.UTF_8))
        val reader = SecureOutgoingTextPayloadReader(FakeCoordinator(session = session))

        val result = reader.readFullyVerified(CONTEXT)

        assertTrue(result is SecureOutgoingTextPayloadReadResult.Available)
        val available = result as SecureOutgoingTextPayloadReadResult.Available
        assertEquals("secure text", available.text)
        assertEquals(CONTEXT.accountUuid, available.reference.accountUuid)
        assertEquals(CONTEXT.messageUuid, available.reference.messageUuid)
        assertEquals(CONTENT_ID, available.reference.contentId)
        assertEquals(SecureMessagePayloadContext.NAMESPACE, available.reference.namespace)
        assertTrue(session.verified)
        assertTrue(session.closed)
    }

    @Test
    fun malformedUtf8FailsClosedWithoutReturningPartialText() {
        val session = FakeReadSession(byteArrayOf(0xC3.toByte(), 0x28))
        val reader = SecureOutgoingTextPayloadReader(FakeCoordinator(session = session))

        val result = reader.readFullyVerified(CONTEXT)

        assertSame(SecureOutgoingTextPayloadReadResult.Unavailable, result)
        assertTrue(session.verified)
        assertTrue(session.closed)
    }

    @Test
    fun oversizedPayloadFailsBeforeTerminalVerification() {
        val session = FakeReadSession("12345".toByteArray(Charsets.UTF_8))
        val reader = SecureOutgoingTextPayloadReader(
            coordinator = FakeCoordinator(session = session),
            maximumPayloadBytes = 4,
        )

        val result = reader.readFullyVerified(CONTEXT)

        assertSame(SecureOutgoingTextPayloadReadResult.Unavailable, result)
        assertFalse(session.verified)
        assertTrue(session.closed)
    }

    @Test
    fun terminalVerificationFailureIsUnavailable() {
        val session = FakeReadSession(
            bytes = "secure text".toByteArray(Charsets.UTF_8),
            verificationFailure = IOException("integrity failure"),
        )
        val reader = SecureOutgoingTextPayloadReader(FakeCoordinator(session = session))

        val result = reader.readFullyVerified(CONTEXT)

        assertSame(SecureOutgoingTextPayloadReadResult.Unavailable, result)
        assertTrue(session.verified)
        assertTrue(session.closed)
    }

    @Test
    fun coordinatorRejectionHasSingleUnavailableOutcome() {
        val reader = SecureOutgoingTextPayloadReader(
            FakeCoordinator(openFailure = IOException("wrong account or missing relation")),
        )

        assertSame(
            SecureOutgoingTextPayloadReadResult.Unavailable,
            reader.readFullyVerified(CONTEXT),
        )
    }

    private class FakeCoordinator(
        private val session: SecureMessagePayloadReadSession? = null,
        private val openFailure: Exception? = null,
    ) : SecureMessagePayloadCoordinator {
        override fun beginWrite(context: SecureMessagePayloadContext): SecureMessagePayloadWriteSession =
            throw UnsupportedOperationException()

        override fun beginOutgoingTextWrite(
            context: SecureOutgoingTextPayloadContext,
        ): SecureMessagePayloadWriteSession = throw UnsupportedOperationException()

        override fun beginProtectedTextWrite(
            context: SecureMessagePayloadContext,
        ): SecureMessagePayloadWriteSession = throw UnsupportedOperationException()

        override fun outgoingTextPayloadMode(
            context: SecureOutgoingTextPayloadContext,
        ): SecureMessagePayloadMode? = null

        override fun protectedTextPayloadMode(
            context: SecureMessagePayloadContext,
        ): SecureMessagePayloadMode? = null

        override fun openOutgoingTextPayload(
            context: SecureOutgoingTextPayloadContext,
        ): SecureMessagePayloadReadSession {
            openFailure?.let { throw it }
            return checkNotNull(session)
        }

        override fun openProtectedTextPayload(
            context: SecureMessagePayloadContext,
        ): SecureMessagePayloadReadSession = throw UnsupportedOperationException()

        override fun open(reference: SecureMessagePayloadReference): SecureContentReadSession =
            throw UnsupportedOperationException()

        override fun open(
            accountUuid: String,
            messageUuid: String,
        ): SecureMessagePayloadReadSession = throw UnsupportedOperationException()

        override fun retire(reference: SecureMessagePayloadReference) = Unit

        override fun retire(accountUuid: String, messageUuid: String) = Unit

        override fun retireAllForConversation(accountUuid: String, conversationUuid: String) = Unit

        override fun recoverInterruptedPublications() = Unit

        override fun recoverInterruptedRetirements() = Unit
    }

    private class FakeReadSession(
        private val bytes: ByteArray,
        private val verificationFailure: Exception? = null,
    ) : SecureContentReadSession {
        override val handle = SecureContentHandle(ACCOUNT_ID, CONTENT_ID, "text/plain")
        override var state: SecureContentReadState = SecureContentReadState.OPEN
            private set

        var verified = false
            private set
        var closed = false
            private set

        override fun openPlaintextInputStream() = ByteArrayInputStream(bytes)

        override fun verifyTerminal(): SecureContentObject {
            verified = true
            verificationFailure?.let { throw it }
            state = SecureContentReadState.TERMINAL_VERIFIED
            return SecureContentObject(
                SecureContentMetadata(
                    accountUuid = ACCOUNT_ID,
                    contentId = CONTENT_ID,
                    namespace = SecureMessagePayloadContext.NAMESPACE,
                    messageUuid = MESSAGE_ID,
                    mimeType = "text/plain",
                    sizeBytes = bytes.size.toLong(),
                    state = SecureContentState.COMMITTED,
                    cryptoVersion = 1,
                ),
            )
        }

        override fun close() {
            closed = true
            if (state != SecureContentReadState.TERMINAL_VERIFIED) {
                state = SecureContentReadState.CLOSED
            }
        }
    }

    private companion object {
        const val ACCOUNT_ID = "account-a"
        const val MESSAGE_ID = "message-a"
        const val CONTENT_ID = "content-a"
        val CONTEXT = SecureOutgoingTextPayloadContext(ACCOUNT_ID, MESSAGE_ID)
    }
}
