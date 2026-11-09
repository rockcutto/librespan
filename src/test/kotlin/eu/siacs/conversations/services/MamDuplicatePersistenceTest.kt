package eu.siacs.conversations.services

import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.persistance.DatabaseBackendStub
import eu.siacs.conversations.storage.secure.*
import eu.siacs.conversations.xmpp.Jid
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises real service + repository validation; only DB and Store read capability are controlled.
 * No native SQLCipher, cryptographic integrity or power-loss guarantee is claimed by this fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MamDuplicatePersistenceTest {
    private class Fixture {
        val account = Account(Jid.of("test@example.test"), "")
        val room = Conversation("test", account, Jid.of("room@conference.example"), Conversation.MODE_MULTI, null)
        val persisted = Message(room, "", Message.ENCRYPTION_NONE)
        val resident = Message(room, "transient body cannot prove storage", Message.ENCRYPTION_NONE)
        var mode: SecureMessagePayloadMode? = SecureMessagePayloadMode.PROTECTED
        var relation = true
        var rowExists = true
        var failTerminal = false
        var reads = 0
        val ref = SecureMessagePayloadReference(account.uuid, persisted.uuid, "synthetic-content")
        val backend = object : DatabaseBackendStub() {
            override fun getMessagesByLocalUuids(c: Conversational, uuids: Collection<String>): ArrayList<Message> =
                if (rowExists && c.uuid == room.uuid && uuids.contains(persisted.uuid)) arrayListOf(persisted) else arrayListOf()
            override fun getSecureMessagePayloadMode(accountUuid: String, messageUuid: String): SecureMessagePayloadMode? = mode
            override fun findSecureMessagePayloadReference(accountUuid: String, messageUuid: String): SecureMessagePayloadReference? =
                if (relation) ref else null
        }
        val coordinator = Proxy.newProxyInstance(
            SecureMessagePayloadCoordinator::class.java.classLoader,
            arrayOf(SecureMessagePayloadCoordinator::class.java),
        ) { _, method, _ ->
            if (method.name != "openProtectedTextPayload") throw IOException("unexpected producer access")
            if (!relation) throw IOException("missing durable relation")
            reads++
            object : SecureContentReadSession {
                private val snapshot = SecureContentObject(SecureContentMetadata(
                    accountUuid = account.uuid, contentId = ref.contentId,
                    namespace = SecureMessagePayloadContext.NAMESPACE, messageUuid = persisted.uuid,
                    state = SecureContentState.COMMITTED,
                ))
                override val handle = snapshot.handle
                override val state = SecureContentReadState.OPEN
                override fun openPlaintextInputStream() = ByteArrayInputStream("verified synthetic body".toByteArray())
                override fun verifyTerminal(): SecureContentObject {
                    if (failTerminal) throw IOException("injected terminal failure")
                    return snapshot
                }
                override fun close() {}
            }
        } as SecureMessagePayloadCoordinator
        val repository = SecureMessageTextRepository(coordinator, backend)
        val service = object : XmppConnectionService() {
            init { attachBaseContext(RuntimeEnvironment.getApplication()) }
            override fun isSecureContentAccountAvailableForMutation(message: Message) = true
            override fun getSecureMessageTextRepository() = repository
            override fun updateConversationUi() {}
        }
        init { resident.uuid = persisted.uuid; service.databaseBackend = backend }
    }

    @Test fun fullyPersistedDuplicateRequiresRealRepositoryReadBeforeAcknowledgement() {
        val f = Fixture()
        assertTrue(f.service.isMamMessagePersisted(f.resident)); assertEquals(1, f.reads)
        assertEquals(0, f.room.countMessages()) // duplicate validation never appends a bubble
    }
    @Test fun repeatedVerifiedDuplicateUsesExistingVerifiedRepositoryContract() {
        val f = Fixture()
        assertTrue(f.service.isMamMessagePersisted(f.resident)); assertTrue(f.service.isMamMessagePersisted(f.resident))
        assertEquals(1, f.reads) // same exact relation may reuse a previously terminally verified read
    }
    @Test fun pendingUnavailableAndUnclassifiedTextCannotBeAcknowledged() {
        for (mode in arrayOf(SecureMessagePayloadMode.PENDING, SecureMessagePayloadMode.UNAVAILABLE, null)) {
            val f = Fixture(); f.mode = mode
            f.resident.setSecureMessagePayloadMode(SecureMessagePayloadMode.PROTECTED)
            f.resident.setVerifiedProtectedBody("stale resident plaintext")
            assertFalse(f.service.isMamMessagePersisted(f.resident)); assertEquals(0, f.reads)
        }
    }
    @Test fun committedModeWithoutRelationOrTerminalVerificationFailsClosed() {
        val missing = Fixture(); missing.relation = false
        assertFalse(missing.service.isMamMessagePersisted(missing.resident))
        val failed = Fixture(); failed.failTerminal = true
        assertFalse(failed.service.isMamMessagePersisted(failed.resident))
    }
    @Test fun residentBodyWithoutOwnedPersistedRowIsNotEnough() {
        val f = Fixture(); f.rowExists = false
        assertFalse(f.service.isMamMessagePersisted(f.resident)); assertEquals(0, f.reads)
    }
    @Test fun durablyRetractedOrModeratedRowsArePolicyOutcomesRatherThanMissingText() {
        val retracted = Fixture(); retracted.mode = SecureMessagePayloadMode.UNAVAILABLE
        retracted.persisted.markRetracted()
        assertTrue(retracted.service.isMamMessagePersisted(retracted.resident)); assertEquals(0, retracted.reads)
        val moderated = Fixture(); moderated.mode = SecureMessagePayloadMode.UNAVAILABLE
        moderated.persisted.markModerated("service", null, 123L)
        assertTrue(moderated.service.isMamMessagePersisted(moderated.resident)); assertEquals(0, moderated.reads)
    }
}
