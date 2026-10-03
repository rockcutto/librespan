package eu.siacs.conversations.storage.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** High-value integration coverage for the real Secure Content runtime and Android Keystore root. */
@RunWith(AndroidJUnit4::class)
class SecureContentRuntimeInstrumentationTest {
    private lateinit var context: Context
    private lateinit var registry: SecureContentAccountRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = SecureContentAccountRegistry()
    }

    @Test
    fun committedPayloadSurvivesStoreRecreationAndTerminalVerification() {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        val payload = "persistent secure payload".toByteArray(Charsets.UTF_8)
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val firstStore = RuntimeSecureContentStore(context, registry)
        publish(firstStore, accountUuid, contentId, messageUuid, payload)

        val recreatedStore = RuntimeSecureContentStore(context, registry)
        val session = recreatedStore.open(accountUuid, contentId)
        try {
            val actual = session.openPlaintextInputStream().use { it.readBytes() }
            assertTrue(payload.contentEquals(actual))
            val verified = session.verifyTerminal()
            assertEquals(SecureContentState.COMMITTED, verified.state)
            assertEquals(accountUuid, verified.accountUuid)
            assertEquals(contentId, verified.contentId)
            assertEquals(messageUuid, verified.messageUuid)
            assertEquals(SecureContentReadState.TERMINAL_VERIFIED, session.state)
        } finally {
            session.close()
            recreatedStore.delete(accountUuid, contentId)
        }
    }

    @Test
    fun accountRevocationBeforeTerminalVerificationFailsClosed() {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        val payload = "ownership-bound payload".toByteArray(Charsets.UTF_8)
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val store = RuntimeSecureContentStore(context, registry)
        publish(store, accountUuid, contentId, messageUuid, payload)
        val session = store.open(accountUuid, contentId)
        try {
            session.openPlaintextInputStream().use { input ->
                val actual = input.readBytes()
                assertTrue(payload.contentEquals(actual))
            }

            registry.replaceRegisteredAccountUuids(emptyList())
            expectIOException { session.verifyTerminal() }
            assertEquals(SecureContentReadState.FAILED, session.state)
        } finally {
            session.close()
            registry.replaceRegisteredAccountUuids(listOf(accountUuid))
            store.delete(accountUuid, contentId)
        }
    }

    @Test
    fun deletedPayloadDoesNotReappearAfterStoreRecreation() {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val store = RuntimeSecureContentStore(context, registry)
        publish(
            store,
            accountUuid,
            contentId,
            messageUuid,
            "delete me".toByteArray(Charsets.UTF_8),
        )
        assertNotNull(store.find(accountUuid, contentId))

        store.delete(accountUuid, contentId)
        assertNull(store.find(accountUuid, contentId))

        val recreatedStore = RuntimeSecureContentStore(context, registry)
        assertNull(recreatedStore.find(accountUuid, contentId))
        expectIOException { recreatedStore.open(accountUuid, contentId) }
    }

    @Test
    fun corruptedCommittedCiphertextFailsClosed() {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        val payload = ByteArray(8192) { index -> (index and 0xff).toByte() }
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val store = RuntimeSecureContentStore(context, registry)
        val blob = publishAndLocateProtectedBlob(store, accountUuid, contentId, messageUuid, payload)
        try {
            RandomAccessFile(blob, "rw").use { file ->
                assertTrue(file.length() > 0L)
                val offset = file.length() - 1L
                file.seek(offset)
                val original = file.readUnsignedByte()
                file.seek(offset)
                file.writeByte(original xor 0x01)
            }

            assertProtectedReadRejected(store, accountUuid, contentId)
        } finally {
            store.delete(accountUuid, contentId)
        }
    }

    @Test
    fun truncatedCommittedCiphertextFailsClosed() {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        val payload = ByteArray(8192) { index -> ((index * 31) and 0xff).toByte() }
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val store = RuntimeSecureContentStore(context, registry)
        val blob = publishAndLocateProtectedBlob(store, accountUuid, contentId, messageUuid, payload)
        try {
            RandomAccessFile(blob, "rw").use { file ->
                assertTrue(file.length() > 1L)
                file.setLength(file.length() - 1L)
            }

            assertProtectedReadRejected(store, accountUuid, contentId)
        } finally {
            store.delete(accountUuid, contentId)
        }
    }

    private fun publishAndLocateProtectedBlob(
        store: RuntimeSecureContentStore,
        accountUuid: String,
        contentId: String,
        messageUuid: String,
        payload: ByteArray,
    ): File {
        val directory = committedBlobDirectory()
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        publish(store, accountUuid, contentId, messageUuid, payload)
        val created = directory.listFiles().orEmpty().filter { it.name !in before }
        assertEquals("exactly one protected blob must be committed", 1, created.size)
        return created.single()
    }

    private fun assertProtectedReadRejected(
        store: RuntimeSecureContentStore,
        accountUuid: String,
        contentId: String,
    ) {
        val session = store.open(accountUuid, contentId)
        try {
            var rejected = false
            try {
                session.openPlaintextInputStream().use { it.readBytes() }
            } catch (_: IOException) {
                rejected = true
            }
            if (!rejected) {
                try {
                    session.verifyTerminal()
                } catch (_: IOException) {
                    rejected = true
                }
            }
            assertTrue("tampered protected bytes must never verify", rejected)
            assertEquals(SecureContentReadState.FAILED, session.state)
        } finally {
            session.close()
        }
    }

    private fun committedBlobDirectory(): File =
        File(context.noBackupFilesDir, "secure-content-v1/committed")

    private fun publish(
        store: RuntimeSecureContentStore,
        accountUuid: String,
        contentId: String,
        messageUuid: String,
        payload: ByteArray,
    ) {
        val handle = store.allocate(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = contentId,
                messageUuid = messageUuid,
                mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(),
            ),
        )
        val writer = store.beginWrite(handle)
        writer.openPlaintextOutputStream().use { output -> output.write(payload) }
        val committed = writer.finishAndBeginCommit().commit()
        assertEquals(SecureContentState.COMMITTED, committed.state)
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    private fun freshId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
