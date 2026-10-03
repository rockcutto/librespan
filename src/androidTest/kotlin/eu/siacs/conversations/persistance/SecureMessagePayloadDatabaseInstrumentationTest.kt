package eu.siacs.conversations.persistance

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.storage.secure.DatabaseSecureMessagePayloadCoordinator
import eu.siacs.conversations.storage.secure.MessagePayloadClassification
import eu.siacs.conversations.storage.secure.RuntimeSecureContentStore
import eu.siacs.conversations.storage.secure.SecureContentAccountRegistry
import eu.siacs.conversations.storage.secure.SecureContentMetadata
import eu.siacs.conversations.storage.secure.SecureContentState
import eu.siacs.conversations.storage.secure.SecureMessagePayloadContext
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode
import eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationPhase
import eu.siacs.conversations.storage.secure.SecureMessagePayloadPublicationRecord
import eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementPhase
import eu.siacs.conversations.storage.secure.SecureMessagePayloadRetirementRecord
import eu.siacs.conversations.storage.secure.SecureOutgoingTextPayloadContext
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLCipher DB + RuntimeSecureContentStore coverage for protected message payloads. */
@RunWith(AndroidJUnit4::class)
class SecureMessagePayloadDatabaseInstrumentationTest {
    private lateinit var context: Context
    private lateinit var databaseBackend: DatabaseBackendImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(DATABASE_NAME)
        databaseBackend = DatabaseBackendImpl(context, DATABASE_PASSWORD)
        databaseBackend.writableDatabase
    }

    @After
    fun tearDown() {
        databaseBackend.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun legacyBodyClassificationIsDerivedWithoutBackfill() {
        val fixture = insertOutgoingTextMessage()

        assertEquals(
            MessagePayloadClassification.LEGACY_PLAINTEXT,
            databaseBackend.getMessagePayloadClassification(
                fixture.accountUuid,
                fixture.messageUuid,
            ),
        )
        assertEquals(1L, databaseBackend.countLegacyPlaintextMessages(fixture.accountUuid))

        assertTrue(
            databaseBackend.claimSecureMessagePayloadMode(
                fixture.accountUuid,
                fixture.messageUuid,
                SecureMessagePayloadMode.PENDING,
            ),
        )
        assertEquals(
            MessagePayloadClassification.PENDING,
            databaseBackend.getMessagePayloadClassification(
                fixture.accountUuid,
                fixture.messageUuid,
            ),
        )
        assertEquals(0L, databaseBackend.countLegacyPlaintextMessages(fixture.accountUuid))

        // Classification alone does not scrub the historical body. Body retirement belongs to
        // the later migration checkpoint after a verified SCS commit.
        val cursor =
            databaseBackend.readableDatabase.query(
                Message.TABLENAME,
                arrayOf(Message.BODY),
                Message.UUID + "=?",
                arrayOf(fixture.messageUuid),
                null,
                null,
                null,
            )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("legacy-body-sentinel", it.getString(0))
        }
    }

    @Test
    fun sqliteResidueCleanupRefusesWhileLegacyBodyRemains() {
        val fixture = insertOutgoingTextMessage()

        val result = databaseBackend.cleanupMigratedLegacyPlaintextResidue()

        assertFalse(result.eligible)
        assertEquals(1L, result.plaintextBodyRowsBefore)
        assertFalse(result.completed)
        assertEquals(
            MessagePayloadClassification.LEGACY_PLAINTEXT,
            databaseBackend.getMessagePayloadClassification(
                fixture.accountUuid,
                fixture.messageUuid,
            ),
        )
    }

    @Test
    fun sqliteResidueCleanupRebuildsFtsTruncatesWalAndVacuumsAfterBodyRetirement() {
        val fixture = insertOutgoingTextMessage()
        val db = databaseBackend.writableDatabase
        db.execSQL(
            "UPDATE " + Message.TABLENAME + " SET " + Message.BODY + "='' WHERE " +
                Message.UUID + "=?",
            arrayOf(fixture.messageUuid),
        )

        val result = databaseBackend.cleanupMigratedLegacyPlaintextResidue()

        assertTrue(result.eligible)
        assertTrue(result.secureDeleteEnabled)
        assertTrue(result.ftsCanaryObservedBeforeRebuild)
        assertTrue(result.ftsCanaryAbsentAfterRebuild)
        assertTrue(result.ftsIntegrityVerified)
        assertEquals(0, result.walCheckpointBusy)
        assertEquals(0, result.walFramesRemaining)
        assertEquals(0L, result.freelistPagesAfterVacuum)
        assertEquals(0L, result.plaintextBodyRowsAfter)
        assertTrue(result.completed)
    }

    @Test
    fun outgoingTextPublishesReadsAndRetiresAcrossRealDatabaseBoundary() {
        val fixture = insertOutgoingTextMessage()
        val registry = registeredRegistry(fixture.accountUuid)
        val store = RuntimeSecureContentStore(context, registry)
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, databaseBackend)
        val outgoingContext = SecureOutgoingTextPayloadContext(
            accountUuid = fixture.accountUuid,
            messageUuid = fixture.messageUuid,
        )
        val payload = "protected outgoing text".toByteArray(Charsets.UTF_8)

        val writer = coordinator.beginOutgoingTextWrite(outgoingContext)
        assertEquals(
            SecureMessagePayloadMode.PENDING,
            coordinator.outgoingTextPayloadMode(outgoingContext),
        )
        writer.openPlaintextOutputStream().use { it.write(payload) }
        val reference = writer.commitAndPublish()

        assertEquals(
            SecureMessagePayloadMode.PROTECTED,
            coordinator.outgoingTextPayloadMode(outgoingContext),
        )
        assertEquals(
            reference,
            databaseBackend.findSecureMessagePayloadReference(
                fixture.accountUuid,
                fixture.messageUuid,
            ),
        )
        assertTrue(databaseBackend.secureMessagePayloadPublications.isEmpty())
        assertEquals(
            SecureContentState.COMMITTED,
            store.find(fixture.accountUuid, reference.contentId)?.state,
        )

        val session = coordinator.openOutgoingTextPayload(outgoingContext)
        try {
            val actual = session.openPlaintextInputStream().use { it.readBytes() }
            assertArrayEquals(payload, actual)
            assertEquals(SecureContentState.COMMITTED, session.verifyTerminal().state)
        } finally {
            session.close()
        }

        coordinator.retire(reference)
        assertNull(
            databaseBackend.findSecureMessagePayloadReference(
                fixture.accountUuid,
                fixture.messageUuid,
            ),
        )
        assertNull(store.find(fixture.accountUuid, reference.contentId))
        assertTrue(databaseBackend.secureMessagePayloadRetirements.isEmpty())
        expectIOException { coordinator.openOutgoingTextPayload(outgoingContext) }
    }

    @Test
    fun interruptedPublicationRecoversUsingRealDatabaseAndStoreFacts() {
        val fixture = insertOutgoingTextMessage()
        val registry = registeredRegistry(fixture.accountUuid)
        val store = RuntimeSecureContentStore(context, registry)
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, databaseBackend)
        val contentId = freshId("content")
        val payload = "recover publication".toByteArray(Charsets.UTF_8)

        publishStoreObject(
            store = store,
            accountUuid = fixture.accountUuid,
            messageUuid = fixture.messageUuid,
            contentId = contentId,
            payload = payload,
        )
        val now = System.currentTimeMillis()
        val publication = SecureMessagePayloadPublicationRecord(
            publicationId = freshId("publication"),
            accountUuid = fixture.accountUuid,
            messageUuid = fixture.messageUuid,
            contentId = contentId,
            namespace = SecureMessagePayloadContext.NAMESPACE,
            phase = SecureMessagePayloadPublicationPhase.STORE_COMMITTED,
            createdAt = now,
            updatedAt = now,
        )
        assertTrue(databaseBackend.createSecureMessagePayloadPublication(publication))

        coordinator.recoverInterruptedPublications()

        val reference = databaseBackend.findSecureMessagePayloadReference(
            fixture.accountUuid,
            fixture.messageUuid,
        )
        requireNotNull(reference)
        assertEquals(contentId, reference.contentId)
        assertTrue(databaseBackend.secureMessagePayloadPublications.isEmpty())

        val session = coordinator.open(reference)
        try {
            val actual = session.openPlaintextInputStream().use { it.readBytes() }
            assertArrayEquals(payload, actual)
            session.verifyTerminal()
        } finally {
            session.close()
        }

        coordinator.retire(reference)
    }

    @Test
    fun interruptedRetirementRecoversUsingRealDatabaseAndStoreFacts() {
        val fixture = insertOutgoingTextMessage()
        val registry = registeredRegistry(fixture.accountUuid)
        val store = RuntimeSecureContentStore(context, registry)
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, databaseBackend)
        val writer = coordinator.beginWrite(
            SecureMessagePayloadContext(
                accountUuid = fixture.accountUuid,
                messageUuid = fixture.messageUuid,
            ),
        )
        writer.openPlaintextOutputStream().use {
            it.write("retire after restart".toByteArray(Charsets.UTF_8))
        }
        val reference = writer.commitAndPublish()
        val now = System.currentTimeMillis()
        val retirement = SecureMessagePayloadRetirementRecord(
            retirementId = freshId("retirement"),
            accountUuid = reference.accountUuid,
            messageUuid = reference.messageUuid,
            contentId = reference.contentId,
            namespace = reference.namespace,
            phase = SecureMessagePayloadRetirementPhase.PREPARED,
            createdAt = now,
            updatedAt = now,
        )
        assertTrue(databaseBackend.createSecureMessagePayloadRetirement(retirement))

        coordinator.recoverInterruptedRetirements()

        assertNull(store.find(reference.accountUuid, reference.contentId))
        assertNull(
            databaseBackend.findSecureMessagePayloadReference(
                reference.accountUuid,
                reference.messageUuid,
            ),
        )
        assertFalse(
            databaseBackend.secureMessagePayloadRetirements.any {
                it.retirementId == retirement.retirementId
            },
        )
        expectIOException { coordinator.open(reference) }
    }

    private fun publishStoreObject(
        store: RuntimeSecureContentStore,
        accountUuid: String,
        messageUuid: String,
        contentId: String,
        payload: ByteArray,
    ) {
        val handle = store.allocate(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = contentId,
                namespace = SecureMessagePayloadContext.NAMESPACE,
                messageUuid = messageUuid,
                mimeType = "text/plain",
                sizeBytes = payload.size.toLong(),
            ),
        )
        val writer = store.beginWrite(handle)
        writer.openPlaintextOutputStream().use { it.write(payload) }
        assertEquals(
            SecureContentState.COMMITTED,
            writer.finishAndBeginCommit().commit().state,
        )
    }

    private fun insertOutgoingTextMessage(): MessageFixture {
        val accountUuid = freshId("account")
        val conversationUuid = freshId("conversation")
        val messageUuid = freshId("message")
        val db = databaseBackend.writableDatabase

        db.insertOrThrow(
            Account.TABLENAME,
            null,
            ContentValues().apply {
                put(Account.UUID, accountUuid)
            },
        )
        db.insertOrThrow(
            Conversation.TABLENAME,
            null,
            ContentValues().apply {
                put(Conversation.UUID, conversationUuid)
                put(Conversation.ACCOUNT, accountUuid)
            },
        )
        db.insertOrThrow(
            Message.TABLENAME,
            null,
            ContentValues().apply {
                put(Message.UUID, messageUuid)
                put(Message.CONVERSATION, conversationUuid)
                put(Message.TYPE, Message.TYPE_TEXT)
                put(Message.STATUS, Message.STATUS_UNSEND)
                put(Message.BODY, "legacy-body-sentinel")
            },
        )

        assertTrue(databaseBackend.hasMessageForAccount(accountUuid, messageUuid))
        assertTrue(databaseBackend.isOutgoingTextMessageForAccount(accountUuid, messageUuid))
        return MessageFixture(accountUuid, messageUuid)
    }

    private fun registeredRegistry(accountUuid: String): SecureContentAccountRegistry =
        SecureContentAccountRegistry().also {
            it.replaceRegisteredAccountUuids(listOf(accountUuid))
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

    private data class MessageFixture(
        val accountUuid: String,
        val messageUuid: String,
    )

    private companion object {
        const val DATABASE_NAME = "history"
        const val DATABASE_PASSWORD = "secure-message-payload-test"
    }
}
