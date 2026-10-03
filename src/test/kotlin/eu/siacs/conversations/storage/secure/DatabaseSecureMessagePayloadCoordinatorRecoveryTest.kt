package eu.siacs.conversations.storage.secure

import eu.siacs.conversations.persistance.DatabaseBackend
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Recovery-contract coverage for the Message DB <-> Secure Content Store publication boundary. */
class DatabaseSecureMessagePayloadCoordinatorRecoveryTest {

    @Test
    fun recoverPublicationPublishesMatchingCommittedObjectAndClearsEvidence() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val publication = publication()

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.publications[publication.publicationId] = publication
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        coordinator.recoverInterruptedPublications()

        assertEquals(reference(), database.references[key(ACCOUNT, MESSAGE)])
        assertFalse(publication.publicationId in database.publications)
        assertEquals(
            SecureContentState.COMMITTED,
            store.objects[key(ACCOUNT, CONTENT)]?.state,
        )
    }

    @Test
    fun recoverPublicationPromotesTrackedOutgoingTextMode() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val publication = publication(phase = SecureMessagePayloadPublicationPhase.STORE_COMMITTED)

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.modes[key(ACCOUNT, MESSAGE)] = SecureMessagePayloadMode.PENDING
        database.publications[publication.publicationId] = publication
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        coordinator.recoverInterruptedPublications()

        assertEquals(
            SecureMessagePayloadMode.PROTECTED,
            database.modes[key(ACCOUNT, MESSAGE)],
        )
        assertEquals(reference(), database.references[key(ACCOUNT, MESSAGE)])
        assertFalse(publication.publicationId in database.publications)
    }

    @Test
    fun recoverReplacementPublishesNewRelationBeforeRetiringPreviousObject() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val previous = reference(contentId = "content-previous")
        val replacement = reference()
        val publication = publication(previousContentId = previous.contentId)

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.modes[key(ACCOUNT, MESSAGE)] = SecureMessagePayloadMode.PROTECTED
        database.references[key(ACCOUNT, MESSAGE)] = previous
        database.publications[publication.publicationId] = publication
        store.objects[key(ACCOUNT, previous.contentId)] = committedObject(contentId = previous.contentId)
        store.objects[key(ACCOUNT, replacement.contentId)] = committedObject()

        coordinator.recoverInterruptedPublications()

        assertEquals(replacement, database.references[key(ACCOUNT, MESSAGE)])
        assertFalse(key(ACCOUNT, previous.contentId) in store.objects)
        assertTrue(key(ACCOUNT, replacement.contentId) in store.objects)
        assertFalse(publication.publicationId in database.publications)
    }

    @Test
    fun recoverPublicationFailsClosedWhenMessageOwnershipIsGone() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val publication = publication()

        database.publications[publication.publicationId] = publication
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        coordinator.recoverInterruptedPublications()

        assertEquals(
            SecureMessagePayloadPublicationPhase.FAILED,
            database.publications[publication.publicationId]?.phase,
        )
        assertNull(database.references[key(ACCOUNT, MESSAGE)])
        assertEquals(
            SecureContentState.COMMITTED,
            store.objects[key(ACCOUNT, CONTENT)]?.state,
        )
    }

    @Test
    fun recoverPublicationPreservesConflictingMessageRelation() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val publication = publication(phase = SecureMessagePayloadPublicationPhase.MESSAGE_LINKED)
        val conflicting = reference(contentId = "content-other")

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.references[key(ACCOUNT, MESSAGE)] = conflicting
        database.publications[publication.publicationId] = publication
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        coordinator.recoverInterruptedPublications()

        assertEquals(
            SecureMessagePayloadPublicationPhase.FAILED,
            database.publications[publication.publicationId]?.phase,
        )
        assertEquals(conflicting, database.references[key(ACCOUNT, MESSAGE)])
        assertTrue(key(ACCOUNT, CONTENT) in store.objects)
    }

    @Test
    fun missingProtectedTextRelationRepairsFromUniqueCommittedStoreCandidate() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.textMessages += key(ACCOUNT, MESSAGE)
        database.modes[key(ACCOUNT, MESSAGE)] = SecureMessagePayloadMode.PROTECTED
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        val plan = coordinator.beginProtectedTextReadPlan(ACCOUNT, listOf(MESSAGE))
        try {
            assertEquals(reference(), plan.referenceFor(MESSAGE))
            assertEquals(reference(), database.references[key(ACCOUNT, MESSAGE)])
        } finally {
            plan.close()
        }
    }

    @Test
    fun missingProtectedTextRelationRepairsFromOwnedLegacyCandidateWhenIndexMisses() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.textMessages += key(ACCOUNT, MESSAGE)
        database.modes[key(ACCOUNT, MESSAGE)] = SecureMessagePayloadMode.PROTECTED
        database.legacyCandidates[key(ACCOUNT, MESSAGE)] =
            SecureContentMetadataRecord(
                SecureContentMetadata(
                    accountUuid = ACCOUNT,
                    contentId = CONTENT,
                    messageUuid = MESSAGE,
                    state = SecureContentState.COMMITTED,
                    cryptoVersion = 1,
                ),
                null,
                1L,
            )
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()
        store.relationLookupEnabled = false

        val plan = coordinator.beginProtectedTextReadPlan(ACCOUNT, listOf(MESSAGE))
        try {
            assertEquals(reference(), plan.referenceFor(MESSAGE))
            assertEquals(reference(), database.references[key(ACCOUNT, MESSAGE)])
        } finally {
            plan.close()
        }
    }

    @Test
    fun missingProtectedTextRelationFailsClosedWhenStoreCandidatesAreAmbiguous() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.textMessages += key(ACCOUNT, MESSAGE)
        database.modes[key(ACCOUNT, MESSAGE)] = SecureMessagePayloadMode.PROTECTED
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()
        store.objects[key(ACCOUNT, "content-other")] =
            committedObject(contentId = "content-other")

        val plan = coordinator.beginProtectedTextReadPlan(ACCOUNT, listOf(MESSAGE))
        try {
            assertNull(plan.referenceFor(MESSAGE))
            assertNull(database.references[key(ACCOUNT, MESSAGE)])
        } finally {
            plan.close()
        }
    }

    @Test
    fun unresolvedPublicationBlocksReaderBeforeStoreOpen() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val publication = publication()
        val reference = reference()

        database.ownedMessages += key(ACCOUNT, MESSAGE)
        database.references[key(ACCOUNT, MESSAGE)] = reference
        database.publications[publication.publicationId] = publication
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        expectIOException { coordinator.open(reference) }

        assertEquals(0, store.openCalls)
    }

    @Test
    fun recoverRetirementFromPreparedRetiresStoreThenRelationAndEvidence() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val retirement = retirement()

        database.references[key(ACCOUNT, MESSAGE)] = reference()
        database.retirements[retirement.retirementId] = retirement
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        coordinator.recoverInterruptedRetirements()

        assertNull(store.objects[key(ACCOUNT, CONTENT)])
        assertNull(database.references[key(ACCOUNT, MESSAGE)])
        assertFalse(retirement.retirementId in database.retirements)
        assertEquals(listOf(key(ACCOUNT, CONTENT)), store.deleteCalls)
    }

    @Test
    fun recoverRetirementResumesWhenStoreIsAlreadyRetired() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val retirement = retirement(phase = SecureMessagePayloadRetirementPhase.STORE_RETIRED)

        database.references[key(ACCOUNT, MESSAGE)] = reference()
        database.retirements[retirement.retirementId] = retirement

        coordinator.recoverInterruptedRetirements()

        assertNull(database.references[key(ACCOUNT, MESSAGE)])
        assertFalse(retirement.retirementId in database.retirements)
        assertTrue(store.deleteCalls.isEmpty())
    }

    @Test
    fun recoverRetirementFinalizesWhenStoreAndRelationAreAlreadyGone() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val retirement = retirement(phase = SecureMessagePayloadRetirementPhase.REFERENCE_RETIRED)

        database.retirements[retirement.retirementId] = retirement

        coordinator.recoverInterruptedRetirements()

        assertFalse(retirement.retirementId in database.retirements)
        assertTrue(store.deleteCalls.isEmpty())
    }

    @Test
    fun recoverRetirementFailsClosedOnStoreIdentityMismatch() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val retirement = retirement()

        database.references[key(ACCOUNT, MESSAGE)] = reference()
        database.retirements[retirement.retirementId] = retirement
        store.objects[key(ACCOUNT, CONTENT)] = committedObject(messageUuid = "different-message")

        coordinator.recoverInterruptedRetirements()

        assertEquals(
            SecureMessagePayloadRetirementPhase.FAILED,
            database.retirements[retirement.retirementId]?.phase,
        )
        assertTrue(key(ACCOUNT, CONTENT) in store.objects)
        assertEquals(reference(), database.references[key(ACCOUNT, MESSAGE)])
        assertTrue(store.deleteCalls.isEmpty())
    }

    @Test
    fun recoverRetirementPreservesConflictingMessageRelation() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val retirement = retirement(phase = SecureMessagePayloadRetirementPhase.STORE_RETIRED)
        val conflicting = reference(contentId = "content-other")

        database.references[key(ACCOUNT, MESSAGE)] = conflicting
        database.retirements[retirement.retirementId] = retirement

        coordinator.recoverInterruptedRetirements()

        assertEquals(
            SecureMessagePayloadRetirementPhase.FAILED,
            database.retirements[retirement.retirementId]?.phase,
        )
        assertEquals(conflicting, database.references[key(ACCOUNT, MESSAGE)])
        assertTrue(store.deleteCalls.isEmpty())
    }

    @Test
    fun unresolvedRetirementBlocksReaderBeforeStoreOpen() {
        val store = FakeStore()
        val database = FakeDatabaseState()
        val coordinator = DatabaseSecureMessagePayloadCoordinator(store, database.backend)
        val retirement = retirement()
        val reference = reference()

        database.references[key(ACCOUNT, MESSAGE)] = reference
        database.retirements[retirement.retirementId] = retirement
        store.objects[key(ACCOUNT, CONTENT)] = committedObject()

        expectIOException { coordinator.open(reference) }

        assertEquals(0, store.openCalls)
    }

    private fun committedObject(
        accountUuid: String = ACCOUNT,
        messageUuid: String = MESSAGE,
        contentId: String = CONTENT,
        namespace: String = SecureMessagePayloadContext.NAMESPACE,
    ): SecureContentObject =
        SecureContentObject(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = contentId,
                namespace = namespace,
                messageUuid = messageUuid,
                state = SecureContentState.COMMITTED,
                cryptoVersion = 1,
            ),
        )

    private fun reference(
        accountUuid: String = ACCOUNT,
        messageUuid: String = MESSAGE,
        contentId: String = CONTENT,
    ): SecureMessagePayloadReference =
        SecureMessagePayloadReference(
            accountUuid = accountUuid,
            messageUuid = messageUuid,
            contentId = contentId,
        )

    private fun publication(
        phase: SecureMessagePayloadPublicationPhase = SecureMessagePayloadPublicationPhase.PREPARED,
        previousContentId: String? = null,
    ): SecureMessagePayloadPublicationRecord =
        SecureMessagePayloadPublicationRecord(
            publicationId = PUBLICATION_ID,
            accountUuid = ACCOUNT,
            messageUuid = MESSAGE,
            contentId = CONTENT,
            namespace = SecureMessagePayloadContext.NAMESPACE,
            phase = phase,
            createdAt = 1L,
            updatedAt = 1L,
            previousContentId = previousContentId,
        )

    private fun retirement(
        phase: SecureMessagePayloadRetirementPhase = SecureMessagePayloadRetirementPhase.PREPARED,
    ): SecureMessagePayloadRetirementRecord =
        SecureMessagePayloadRetirementRecord(
            retirementId = RETIREMENT_ID,
            accountUuid = ACCOUNT,
            messageUuid = MESSAGE,
            contentId = CONTENT,
            namespace = SecureMessagePayloadContext.NAMESPACE,
            phase = phase,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    private class FakeStore : SecureContentStore {
        val objects = linkedMapOf<Pair<String, String>, SecureContentObject>()
        val deleteCalls = mutableListOf<Pair<String, String>>()
        var openCalls = 0
        var relationLookupEnabled = true

        override fun allocate(metadata: SecureContentMetadata): SecureContentHandle =
            throw UnsupportedOperationException("allocate is not used by recovery tests")

        override fun beginWrite(handle: SecureContentHandle): SecureContentWriteSession =
            throw UnsupportedOperationException("beginWrite is not used by recovery tests")

        override fun find(accountUuid: String, contentId: String): SecureContentObject? =
            objects[key(accountUuid, contentId)]

        override fun findByMessage(
            accountUuid: String,
            messageUuid: String,
        ): List<SecureContentObject> =
            if (!relationLookupEnabled) {
                emptyList()
            } else {
                objects.values.filter {
                    it.accountUuid == accountUuid && it.messageUuid == messageUuid
                }
            }

        override fun open(accountUuid: String, contentId: String): SecureContentReadSession {
            openCalls += 1
            throw UnsupportedOperationException("open should not be reached by unresolved-evidence tests")
        }

        override fun delete(accountUuid: String, contentId: String) {
            val objectKey = key(accountUuid, contentId)
            deleteCalls += objectKey
            objects.remove(objectKey)
        }
    }

    private class FakeDatabaseState {
        val ownedMessages = mutableSetOf<Pair<String, String>>()
        val textMessages = mutableSetOf<Pair<String, String>>()
        val modes = linkedMapOf<Pair<String, String>, SecureMessagePayloadMode>()
        val references = linkedMapOf<Pair<String, String>, SecureMessagePayloadReference>()
        val legacyCandidates =
            linkedMapOf<Pair<String, String>, SecureContentMetadataRecord>()
        val publications = linkedMapOf<String, SecureMessagePayloadPublicationRecord>()
        val retirements = linkedMapOf<String, SecureMessagePayloadRetirementRecord>()

        val backend: DatabaseBackend = Proxy.newProxyInstance(
            DatabaseBackend::class.java.classLoader,
            arrayOf(DatabaseBackend::class.java),
            InvocationHandler { proxy, method, args ->
                val values = args ?: emptyArray<Any?>()
                when (method.name) {
                    "hasMessageForAccount" ->
                        key(values[0] as String, values[1] as String) in ownedMessages

                    "isTextMessageForAccount" ->
                        key(values[0] as String, values[1] as String) in textMessages

                    "getValidatedProtectedTextPayloadReferences" -> {
                        val accountUuid = values[0] as String
                        @Suppress("UNCHECKED_CAST")
                        val messageUuids = values[1] as Collection<String>
                        messageUuids.mapNotNull { messageUuid ->
                            val messageKey = key(accountUuid, messageUuid)
                            if (messageKey in textMessages &&
                                modes[messageKey] == SecureMessagePayloadMode.PROTECTED
                            ) {
                                references[messageKey]
                            } else {
                                null
                            }
                        }
                    }

                    "getSecureMessagePayloadMode" ->
                        modes[key(values[0] as String, values[1] as String)]

                    "updateSecureMessagePayloadMode" -> {
                        modes[key(values[0] as String, values[1] as String)] =
                            values[2] as SecureMessagePayloadMode
                        true
                    }

                    "findSecureMessagePayloadReference" ->
                        references[key(values[0] as String, values[1] as String)]

                    "findByMessageUuid" ->
                        legacyCandidates[key(values[0] as String, values[1] as String)]
                            ?.let { listOf(it) }
                            ?: emptyList<SecureContentMetadataRecord>()

                    "linkSecureMessagePayloadReference" -> {
                        val reference = values[0] as SecureMessagePayloadReference
                        val messageKey = key(reference.accountUuid, reference.messageUuid)
                        if (messageKey !in ownedMessages) {
                            false
                        } else {
                            val existing = references[messageKey]
                            if (existing == null) {
                                references[messageKey] = reference
                                true
                            } else {
                                existing == reference
                            }
                        }
                    }

                    "replaceSecureMessagePayloadReference" -> {
                        val expected = values[0] as SecureMessagePayloadReference
                        val replacement = values[1] as SecureMessagePayloadReference
                        val messageKey = key(expected.accountUuid, expected.messageUuid)
                        if (expected.accountUuid != replacement.accountUuid ||
                            expected.messageUuid != replacement.messageUuid ||
                            references[messageKey] != expected
                        ) {
                            false
                        } else {
                            references[messageKey] = replacement
                            true
                        }
                    }

                    "deleteSecureMessagePayloadReference" ->
                        references.remove(key(values[0] as String, values[1] as String)) != null

                    "createSecureMessagePayloadPublication" -> {
                        val record = values[0] as SecureMessagePayloadPublicationRecord
                        val messageKey = key(record.accountUuid, record.messageUuid)
                        if (messageKey !in ownedMessages || record.publicationId in publications) {
                            false
                        } else {
                            publications[record.publicationId] = record
                            true
                        }
                    }

                    "getSecureMessagePayloadPublications" -> publications.values.toList()

                    "updateSecureMessagePayloadPublication" -> {
                        val publicationId = values[0] as String
                        val phase = values[1] as SecureMessagePayloadPublicationPhase
                        val current = publications[publicationId]
                        if (current == null) {
                            false
                        } else {
                            publications[publicationId] = current.copy(
                                phase = phase,
                                updatedAt = current.updatedAt + 1,
                            )
                            true
                        }
                    }

                    "deleteSecureMessagePayloadPublication" ->
                        publications.remove(values[0] as String) != null

                    "createSecureMessagePayloadRetirement" -> {
                        val record = values[0] as SecureMessagePayloadRetirementRecord
                        if (record.retirementId in retirements) {
                            false
                        } else {
                            retirements[record.retirementId] = record
                            true
                        }
                    }

                    "getSecureMessagePayloadRetirements" -> retirements.values.toList()

                    "updateSecureMessagePayloadRetirement" -> {
                        val retirementId = values[0] as String
                        val phase = values[1] as SecureMessagePayloadRetirementPhase
                        val current = retirements[retirementId]
                        if (current == null) {
                            false
                        } else {
                            retirements[retirementId] = current.copy(
                                phase = phase,
                                updatedAt = current.updatedAt + 1,
                            )
                            true
                        }
                    }

                    "deleteSecureMessagePayloadRetirement" ->
                        retirements.remove(values[0] as String) != null

                    "toString" -> "FakeDatabaseBackend"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === values.firstOrNull()
                    else -> throw UnsupportedOperationException(
                        "Unexpected DatabaseBackend call: ${method.name}",
                    )
                }
            },
        ) as DatabaseBackend
    }

    private companion object {
        const val ACCOUNT = "account-a"
        const val MESSAGE = "message-a"
        const val CONTENT = "content-a"
        const val PUBLICATION_ID = "publication-a"
        const val RETIREMENT_ID = "retirement-a"

        fun key(accountUuid: String, value: String): Pair<String, String> = accountUuid to value
    }
}
