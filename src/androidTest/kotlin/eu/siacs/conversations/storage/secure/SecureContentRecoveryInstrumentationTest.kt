package eu.siacs.conversations.storage.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Durable recovery coverage for interrupted Secure Content commit and delete transactions. */
@RunWith(AndroidJUnit4::class)
class SecureContentRecoveryInstrumentationTest {
    private lateinit var context: Context
    private lateinit var registry: SecureContentAccountRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = SecureContentAccountRegistry()
    }

    @Test
    fun commitRecoveryFromPreparedFailsClosed() {
        verifyInterruptedCommitRecovery(SecureContentRecoveryPhase.PREPARED, expectCommitted = false)
    }

    @Test
    fun commitRecoveryFromBlobCommittedFailsClosed() {
        verifyInterruptedCommitRecovery(
            SecureContentRecoveryPhase.BLOB_COMMITTED,
            expectCommitted = false,
        )
    }

    @Test
    fun commitRecoveryFromKeyActivatedFailsClosed() {
        verifyInterruptedCommitRecovery(
            SecureContentRecoveryPhase.KEY_ACTIVATED,
            expectCommitted = false,
        )
    }

    @Test
    fun commitRecoveryFromMetadataCommittedPreservesProvenCommit() {
        verifyInterruptedCommitRecovery(
            SecureContentRecoveryPhase.METADATA_COMMITTED,
            expectCommitted = true,
        )
    }

    @Test
    fun unjournaledWritingAttemptFailsClosedWithoutGlobalMetadataScan() {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val firstStore = RuntimeSecureContentStore(context, registry)
        val handle =
            firstStore.allocate(
                SecureContentMetadata(
                    accountUuid = accountUuid,
                    contentId = contentId,
                    messageUuid = messageUuid,
                    mimeType = "text/plain",
                ),
            )
        firstStore.beginWrite(handle)

        SecureColdStartPerfTrace.start()
        val recoveredStore = RuntimeSecureContentStore(context, registry)
        try {
            val record =
                AccountProtectedSecureContentMetadataStore(
                    context,
                    PersistentSecureContentKeyMaterialStore(context),
                ).find(accountUuid, contentId)
            assertEquals(SecureContentState.FAILED, record?.metadata?.state)
            assertNull(recoveredStore.find(accountUuid, contentId)?.takeIf { it.isReaderVisible })
            val report = SecureColdStartPerfTrace.report()
            assertTrue(report.contains("store_recovery_incomplete_records=1"))
            assertFalse(report.contains("store_metadata_scan="))
        } finally {
            SecureColdStartPerfTrace.clear()
            AccountProtectedSecureContentMetadataStore(
                context,
                PersistentSecureContentKeyMaterialStore(context),
            ).deleteMetadata(accountUuid, contentId)
        }
    }

    @Test
    fun deleteRecoveryFromPreparedCompletesRetirement() {
        verifyInterruptedDeleteRecovery(SecureContentRecoveryPhase.PREPARED)
    }

    @Test
    fun deleteRecoveryFromKeyInvalidatedCompletesRetirement() {
        verifyInterruptedDeleteRecovery(SecureContentRecoveryPhase.KEY_INVALIDATED)
    }

    @Test
    fun deleteRecoveryFromBlobRetiredCompletesRetirement() {
        verifyInterruptedDeleteRecovery(SecureContentRecoveryPhase.BLOB_RETIRED)
    }

    @Test
    fun deleteRecoveryFromMetadataRetiredCompletesRetirement() {
        verifyInterruptedDeleteRecovery(SecureContentRecoveryPhase.METADATA_RETIRED)
    }

    private fun verifyInterruptedCommitRecovery(
        phase: SecureContentRecoveryPhase,
        expectCommitted: Boolean,
    ) {
        val fixture = prepareInterruptedCommit()
        registry.replaceRegisteredAccountUuids(listOf(fixture.accountUuid))
        advanceCommitTo(fixture, phase)

        val recoveredStore = RuntimeSecureContentStore(context, registry)
        try {
            assertFalse(
                fixture.recoveryStore.allRecords().any { it.context == fixture.cryptoContext },
            )

            if (expectCommitted) {
                val record = fixture.metadataStore.find(fixture.accountUuid, fixture.contentId)
                assertEquals(SecureContentState.COMMITTED, record?.metadata?.state)
                assertEquals(
                    SecureContentKeyMaterialState.ACTIVE,
                    fixture.keyStore.stateFor(fixture.cryptoContext),
                )
                assertTrue(requireBlobSuccess(fixture.blobStore.exists(fixture.reference)))

                val session = recoveredStore.open(fixture.accountUuid, fixture.contentId)
                try {
                    val actual = session.openPlaintextInputStream().use { it.readBytes() }
                    assertTrue(fixture.payload.contentEquals(actual))
                    assertEquals(
                        SecureContentState.COMMITTED,
                        session.verifyTerminal().state,
                    )
                    assertEquals(
                        SecureContentReadState.TERMINAL_VERIFIED,
                        session.state,
                    )
                } finally {
                    session.close()
                }
            } else {
                val record = fixture.metadataStore.find(fixture.accountUuid, fixture.contentId)
                assertEquals(SecureContentState.FAILED, record?.metadata?.state)
                assertEquals(
                    SecureContentKeyMaterialState.INVALIDATED,
                    fixture.keyStore.stateFor(fixture.cryptoContext),
                )
                assertFalse(requireBlobSuccess(fixture.blobStore.exists(fixture.reference)))
                expectIOException { recoveredStore.open(fixture.accountUuid, fixture.contentId) }
            }
        } finally {
            if (expectCommitted) {
                recoveredStore.delete(fixture.accountUuid, fixture.contentId)
            } else {
                fixture.metadataStore.deleteMetadata(fixture.accountUuid, fixture.contentId)
                fixture.blobStore.delete(fixture.reference)
                fixture.keyStore.invalidate(fixture.cryptoContext)
            }
        }
    }

    private fun prepareInterruptedCommit(): CommitFixture {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        val payload = ByteArray(8192) { index -> ((index * 17) and 0xff).toByte() }
        val cryptoContext = SecureContentCryptoContext(
            namespace = SecureContentMetadata.DEFAULT_NAMESPACE,
            cryptoVersion = 1,
            accountUuid = accountUuid,
            contentId = contentId,
        )
        val keyStore = PersistentSecureContentKeyMaterialStore(context)
        val blobStore = InternalSecureContentBlobStore(context)
        val metadataStore = AccountProtectedSecureContentMetadataStore(context, keyStore)
        val recoveryStore = AccountProtectedSecureContentRecoveryStore(context, keyStore)
        metadataStore.create(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = contentId,
                messageUuid = messageUuid,
                mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(),
                cryptoVersion = 1,
            ),
            storageLocator = null,
        )
        assertTrue(metadataStore.updateState(accountUuid, contentId, SecureContentState.WRITING))

        val keySession = requireKeySuccess(keyStore.beginWrite(cryptoContext))
        val blobSession = requireBlobSuccess(blobStore.put())
        val cryptoWriter = requireCryptoSuccess(
            TinkSecureContentCryptoEngine().beginWrite(
                cryptoContext,
                keySession.writerHandle,
                blobSession.protectedSink,
            ),
        )
        cryptoWriter.openPlaintextOutputStream().use { output -> output.write(payload) }
        requireCryptoSuccess(cryptoWriter.finish())
        val blobCandidate = requireBlobSuccess(blobSession.prepareCommit())
        val keyCandidate = requireKeySuccess(keySession.prepareCommit())
        val reference = blobStore.referenceFor(blobCandidate)
            ?: throw AssertionError("blob candidate reference unavailable")
        val locator = blobStore.identifier(reference)
            ?: throw AssertionError("blob candidate locator unavailable")
        val recovery = recoveryStore.create(
            SecureContentRecoveryOperation.COMMIT,
            cryptoContext,
            locator,
        )
        assertTrue(metadataStore.updateStorageLocator(accountUuid, contentId, locator))
        assertTrue(
            metadataStore.updateState(
                accountUuid,
                contentId,
                SecureContentState.READY_TO_COMMIT,
            ),
        )

        return CommitFixture(
            accountUuid = accountUuid,
            contentId = contentId,
            payload = payload,
            cryptoContext = cryptoContext,
            keyStore = keyStore,
            blobStore = blobStore,
            metadataStore = metadataStore,
            recoveryStore = recoveryStore,
            blobCandidate = blobCandidate,
            keyCandidate = keyCandidate,
            reference = reference,
            recovery = recovery,
        )
    }

    private fun advanceCommitTo(
        fixture: CommitFixture,
        target: SecureContentRecoveryPhase,
    ) {
        if (target == SecureContentRecoveryPhase.PREPARED) return

        requireBlobSuccess(fixture.blobStore.commit(fixture.blobCandidate))
        fixture.recovery = fixture.recoveryStore.advance(
            fixture.recovery,
            SecureContentRecoveryPhase.BLOB_COMMITTED,
        )
        if (target == SecureContentRecoveryPhase.BLOB_COMMITTED) return

        requireKeySuccess(fixture.keyStore.activate(fixture.keyCandidate))
        fixture.recovery = fixture.recoveryStore.advance(
            fixture.recovery,
            SecureContentRecoveryPhase.KEY_ACTIVATED,
        )
        if (target == SecureContentRecoveryPhase.KEY_ACTIVATED) return

        if (target != SecureContentRecoveryPhase.METADATA_COMMITTED) {
            throw AssertionError("unsupported commit recovery phase: $target")
        }
        assertTrue(
            fixture.metadataStore.updateState(
                fixture.accountUuid,
                fixture.contentId,
                SecureContentState.COMMITTED,
            ),
        )
        fixture.recovery = fixture.recoveryStore.advance(
            fixture.recovery,
            SecureContentRecoveryPhase.METADATA_COMMITTED,
        )
    }

    private fun verifyInterruptedDeleteRecovery(phase: SecureContentRecoveryPhase) {
        val fixture = prepareInterruptedDelete()
        advanceDeleteTo(fixture, phase)

        val recoveredStore = RuntimeSecureContentStore(context, registry)
        assertNull(fixture.metadataStore.find(fixture.accountUuid, fixture.contentId))
        assertNull(recoveredStore.find(fixture.accountUuid, fixture.contentId))
        assertEquals(
            SecureContentKeyMaterialState.INVALIDATED,
            fixture.keyStore.stateFor(fixture.cryptoContext),
        )
        assertFalse(requireBlobSuccess(fixture.blobStore.exists(fixture.reference)))
        assertFalse(
            fixture.recoveryStore.allRecords().any { it.context == fixture.cryptoContext },
        )
        expectIOException { recoveredStore.open(fixture.accountUuid, fixture.contentId) }
    }

    private fun prepareInterruptedDelete(): DeleteFixture {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val store = RuntimeSecureContentStore(context, registry)
        publish(
            store = store,
            accountUuid = accountUuid,
            contentId = contentId,
            messageUuid = messageUuid,
            payload = "retire durable content".toByteArray(Charsets.UTF_8),
        )

        val keyStore = PersistentSecureContentKeyMaterialStore(context)
        val blobStore = InternalSecureContentBlobStore(context)
        val metadataStore = AccountProtectedSecureContentMetadataStore(context, keyStore)
        val recoveryStore = AccountProtectedSecureContentRecoveryStore(context, keyStore)
        val record = metadataStore.find(accountUuid, contentId)
            ?: throw AssertionError("committed metadata unavailable")
        val locator = record.storageLocator
            ?: throw AssertionError("committed blob locator unavailable")
        val cryptoContext = SecureContentCryptoContext(
            namespace = record.metadata.namespace,
            cryptoVersion = record.metadata.cryptoVersion
                ?: throw AssertionError("committed crypto version unavailable"),
            accountUuid = accountUuid,
            contentId = contentId,
        )
        val reference = blobStore.reference(locator)
        val recovery = recoveryStore.create(
            SecureContentRecoveryOperation.DELETE,
            cryptoContext,
            locator,
        )

        return DeleteFixture(
            accountUuid = accountUuid,
            contentId = contentId,
            cryptoContext = cryptoContext,
            keyStore = keyStore,
            blobStore = blobStore,
            metadataStore = metadataStore,
            recoveryStore = recoveryStore,
            reference = reference,
            recovery = recovery,
        )
    }

    private fun advanceDeleteTo(
        fixture: DeleteFixture,
        target: SecureContentRecoveryPhase,
    ) {
        if (target == SecureContentRecoveryPhase.PREPARED) return

        requireKeySuccess(fixture.keyStore.invalidate(fixture.cryptoContext))
        fixture.recovery = fixture.recoveryStore.advance(
            fixture.recovery,
            SecureContentRecoveryPhase.KEY_INVALIDATED,
        )
        if (target == SecureContentRecoveryPhase.KEY_INVALIDATED) return

        requireBlobSuccess(fixture.blobStore.delete(fixture.reference))
        fixture.recovery = fixture.recoveryStore.advance(
            fixture.recovery,
            SecureContentRecoveryPhase.BLOB_RETIRED,
        )
        if (target == SecureContentRecoveryPhase.BLOB_RETIRED) return

        if (target != SecureContentRecoveryPhase.METADATA_RETIRED) {
            throw AssertionError("unsupported delete recovery phase: $target")
        }
        assertTrue(fixture.metadataStore.deleteMetadata(fixture.accountUuid, fixture.contentId))
        fixture.recovery = fixture.recoveryStore.advance(
            fixture.recovery,
            SecureContentRecoveryPhase.METADATA_RETIRED,
        )
    }

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
        assertEquals(
            SecureContentState.COMMITTED,
            writer.finishAndBeginCommit().commit().state,
        )
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    private fun <T> requireKeySuccess(result: SecureContentKeyMaterialResult<T>): T =
        when (result) {
            is SecureContentKeyMaterialResult.Success -> result.value
            is SecureContentKeyMaterialResult.Failure ->
                throw AssertionError("unexpected key-material failure: ${result.reason}")
        }

    private fun <T> requireBlobSuccess(result: SecureContentBlobResult<T>): T =
        when (result) {
            is SecureContentBlobResult.Success -> result.value
            is SecureContentBlobResult.Failure ->
                throw AssertionError("unexpected blob failure: ${result.reason}")
        }

    private fun <T> requireCryptoSuccess(result: SecureContentCryptoResult<T>): T =
        when (result) {
            is SecureContentCryptoResult.Success -> result.value
            is SecureContentCryptoResult.Failure ->
                throw AssertionError("unexpected crypto failure: ${result.reason}")
        }

    private fun freshId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private data class CommitFixture(
        val accountUuid: String,
        val contentId: String,
        val payload: ByteArray,
        val cryptoContext: SecureContentCryptoContext,
        val keyStore: PersistentSecureContentKeyMaterialStore,
        val blobStore: InternalSecureContentBlobStore,
        val metadataStore: AccountProtectedSecureContentMetadataStore,
        val recoveryStore: AccountProtectedSecureContentRecoveryStore,
        val blobCandidate: SecureContentBlobCommitCandidate,
        val keyCandidate: SecureContentKeyMaterialCommitCandidate,
        val reference: SecureContentBlobReference,
        var recovery: SecureContentRecoveryRecord,
    )

    private data class DeleteFixture(
        val accountUuid: String,
        val contentId: String,
        val cryptoContext: SecureContentCryptoContext,
        val keyStore: PersistentSecureContentKeyMaterialStore,
        val blobStore: InternalSecureContentBlobStore,
        val metadataStore: AccountProtectedSecureContentMetadataStore,
        val recoveryStore: AccountProtectedSecureContentRecoveryStore,
        val reference: SecureContentBlobReference,
        var recovery: SecureContentRecoveryRecord,
    )
}
