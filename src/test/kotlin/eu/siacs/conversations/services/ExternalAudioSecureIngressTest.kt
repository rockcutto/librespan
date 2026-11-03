package eu.siacs.conversations.services

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import eu.siacs.conversations.Conversations
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.http.SecureContentUploadRoute
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.storage.secure.*
import eu.siacs.conversations.ui.util.VoiceRecordingStaging
import eu.siacs.conversations.ui.UiCallback
import eu.siacs.conversations.xmpp.Jid
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs the shared paperclip/share/forward/Saved Messages attachment worker, not a policy replica. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = ExternalAudioSecureIngressTest.TestApplication::class)
class ExternalAudioSecureIngressTest {
    @Test fun externalAudioPublishesWithoutLegacyPathAndRetriesFromCommittedContent() {
        for ((mime, name) in listOf("audio/mpeg" to "song.mp3", "audio/mp4" to "song.m4a",
            "audio/aac" to "song.aac", "audio/ogg" to "song.ogg", "audio/opus" to "song.opus",
            "application/ogg" to "ambiguous.ogg")) {
            val f = Fixture(mime, name)
            f.run()
            assertEquals(1, f.successes)
            assertEquals(1, f.service.sent)
            assertNull(f.message.relativeFilePath)
            assertFalse(f.message.isDeleted)
            assertEquals(Message.TYPE_FILE, f.message.type)
            assertEquals(mime, f.message.secureMediaMimeType)
            assertEquals(name, f.message.secureMediaFileName)
            val route = SecureContentUploadRoute(SecureContentTransferGateway(f.store))
            val descriptor = route.reuseExisting(f.account.uuid, f.message.uuid)!!
            assertEquals(mime, descriptor.mimeType)
            assertEquals(name, descriptor.displayFilename)
            // Retry after the source permission/file has disappeared uses the exact SCS binding.
            val missing = Uri.parse("content://audio.test/unavailable")
            val prepared = SecureOutgoingAttachmentPreparation.create(f.service.contentResolver, f.store)
                .prepareUriAttachment(f.account.uuid, f.message.uuid, missing, mime, null)
            assertFalse(prepared.created)
            assertEquals(descriptor.binding, prepared.binding)
            f.store.open(f.account.uuid, descriptor.binding.contentId).use {
                assertArrayEquals(f.bytes, it.openPlaintextInputStream().readBytes())
                it.verifyTerminal()
            }
        }
    }

    @Test fun actionSendAndExportedForwardUriUseTheSameWorker() {
        for (mode in listOf(Conversation.MODE_SINGLE, Conversation.MODE_MULTI)) {
            val f = Fixture("audio/mpeg", "shared.mp3", mode)
            val share = Intent(Intent.ACTION_SEND).setType("audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, f.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            f.run(share.getParcelableExtra(Intent.EXTRA_STREAM)!!, share.type)
            assertEquals(1, f.service.sent)
            assertNull(f.message.relativeFilePath)
            // Forward/Saved Messages exports are temporary URIs with a caller-owned retirer.
            val forwarded = Fixture("audio/mpeg", "export.mp3", mode)
            var retired = false
            forwarded.run(retirer = SecureOutgoingAttachmentStagingRetirer {
                assertNotNull(SecureMessageMediaCoordinator(forwarded.store)
                    .resolve(forwarded.account.uuid, forwarded.message.uuid))
                retired = true
                true
            })
            assertTrue(retired)
            assertEquals(1, forwarded.service.sent)
            assertFalse(forwarded.message.isDeleted)
            assertNull(forwarded.message.relativeFilePath)
        }
    }

    @Test fun failedCommitPreservesStagingAndDoesNotFallBackOrSend() {
        val f = Fixture("audio/mpeg", "retry.mp3")
        f.store.failCommit = true
        var retired = false
        f.run(retirer = SecureOutgoingAttachmentStagingRetirer { retired = true; true })
        assertEquals(1, f.errors)
        assertEquals(0, f.service.sent)
        assertFalse(retired)
        assertNull(f.message.relativeFilePath)
        assertNull(SecureMessageMediaCoordinator(f.store).resolve(f.account.uuid, f.message.uuid))
    }

    @Test fun recorderStyleStagingRetainsItsExistingSemantics() {
        val f = Fixture("audio/mp4", "recording.m4a")
        var retired = false
        val staging = VoiceRecordingStaging.createOutputFile(f.service, "RECORDING_test.m4a")
        staging.parentFile!!.mkdirs()
        staging.writeBytes(f.bytes)
        val uri = Uri.parse("content://${FileBackend.getAuthority(f.service)}/voice_recordings/RECORDING_test.m4a")
        assertTrue(VoiceRecordingStaging.isControlledUri(f.service, uri))
        shadowOf(f.service.contentResolver).registerInputStream(uri, ByteArrayInputStream(f.bytes))
        f.run(source = uri, retirer = SecureOutgoingAttachmentStagingRetirer {
            retired = true
            VoiceRecordingStaging.retire(staging)
        })
        assertTrue(retired)
        assertFalse(staging.exists())
        assertEquals(1, f.service.sent)
        assertNull(f.message.relativeFilePath)
    }

    @Test fun abortedTransportKeepsCommittedAudioForRetryAndMissingRelationFailsClosed() {
        val f = Fixture("audio/mpeg", "retry.mp3")
        f.run()
        val route = SecureContentUploadRoute(SecureContentTransferGateway(f.store))
        val descriptor = route.reuseExisting(f.account.uuid, f.message.uuid)!!
        val aborted = object : ForwardingSink(Buffer()) {
            override fun write(source: Buffer, byteCount: Long) { throw IOException("cancelled transport") }
        }.buffer()
        assertThrows(IOException::class.java) { route.requestBody(descriptor, null, null) {}.writeTo(aborted) }
        assertEquals(descriptor, route.reuseExisting(f.account.uuid, f.message.uuid))
        val retry = Buffer()
        route.requestBody(descriptor, null, null) {}.writeTo(retry)
        assertArrayEquals(f.bytes, retry.readByteArray())
        assertFalse(f.message.isDeleted)
        assertNull(f.message.relativeFilePath)
        f.store.delete(descriptor.binding.accountUuid, descriptor.binding.contentId)
        assertNull(route.reuseExisting(f.account.uuid, f.message.uuid))
        assertThrows(IOException::class.java) { route.requestBody(descriptor, null, null) {}.writeTo(Buffer()) }
    }

    class TestApplication : Conversations() {
        override fun onCreate() { /* isolate ingress from unrelated application startup */ }
    }

    class TestService : XmppConnectionService() {
        var sent = 0
        private val testResources = object : Resources(Resources.getSystem().assets, Resources.getSystem().displayMetrics,
            Resources.getSystem().configuration) {
            override fun getInteger(id: Int) = 1024 * 1024
            override fun getText(id: Int): CharSequence = "attachment error"
        }
        private val testBackend = object : FileBackend(this) {
            override fun copyFileToPrivateStorage(message: Message, uri: Uri, type: String?) {
                fail("external audio must not create a legacy plaintext attachment")
            }
            override fun copyVoiceRecordingToPrivateStorage(message: Message, uri: Uri, type: String?) {
                fail("secure recorder must not fall back to a legacy copy")
            }
        }
        override fun getResources() = testResources
        override fun getFileBackend() = testBackend
        override fun isSecureContentAccountAvailableForMutation(message: Message) = true
        override fun sendPreparedMediaMessage(message: Message, batchId: String?, captionId: String?) { sent++ }
        override fun failMediaSendBatchMessage(message: Message, batchId: String?, captionId: String?) {}
    }

    private class Fixture(val mime: String, name: String, mode: Int = Conversation.MODE_SINGLE) {
        val service = Robolectric.buildService(TestService::class.java).get()
        val store = SecureOutgoingAttachmentIngressTest.FakeStore()
        val account = Account(Jid.of("sender@example.test"), "")
        val message = Message(Conversation("test", account, Jid.of("peer@example.test"), mode, null),
            "", Message.ENCRYPTION_NONE).apply { type = Message.TYPE_FILE }
        val uri = Uri.parse("content://audio.test/$name")
        val bytes = byteArrayOf(1, 2, 3, 4)
        var successes = 0
        var errors = 0
        init {
            val provider = SecureContentStoreProvider(RuntimeEnvironment.getApplication(), SecureContentAccountRegistry())
            SecureContentStoreProvider::class.java.getDeclaredField("runtimeStore").apply {
                isAccessible = true; set(provider, store)
            }
            Conversations::class.java.getDeclaredField("secureContentStoreProvider").apply {
                isAccessible = true; set(RuntimeEnvironment.getApplication(), provider)
            }
            shadowOf(service.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        }
        fun run(source: Uri = uri, sourceMime: String? = mime,
                retirer: SecureOutgoingAttachmentStagingRetirer? = null) {
            AttachFileToConversationRunnable(service, source, sourceMime, message,
                object : UiCallback<Message> {
                    override fun success(result: Message) { successes++ }
                    override fun error(code: Int, result: Message) { errors++ }
                    override fun userInputRequired(pi: PendingIntent, result: Message) { fail("unexpected input") }
                }, null, null, retirer).run()
        }
    }
}
