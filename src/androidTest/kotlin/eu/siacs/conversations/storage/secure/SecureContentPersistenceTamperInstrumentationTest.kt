package eu.siacs.conversations.storage.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fail-closed coverage for authenticated durable Secure Content control records. */
@RunWith(AndroidJUnit4::class)
class SecureContentPersistenceTamperInstrumentationTest {
    private lateinit var context: Context
    private lateinit var registry: SecureContentAccountRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = SecureContentAccountRegistry()
    }

    @Test
    fun tamperedMetadataRecordBecomesUnavailableWithoutFallback() {
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val messageUuid = freshId("message")
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val preferences = context.getSharedPreferences(METADATA_PREFERENCES, Context.MODE_PRIVATE)
        val beforeKeys = preferences.all.keys.toSet()
        val store = RuntimeSecureContentStore(context, registry)
        publish(
            store,
            accountUuid,
            contentId,
            messageUuid,
            "metadata tamper target".toByteArray(Charsets.UTF_8),
        )

        val recordKey = singleNewRecordKey(preferences, beforeKeys)
        val original = preferences.getString(recordKey, null)
            ?: failWith("metadata record must exist")
        try {
            tamperProtectedPayload(preferences, recordKey, original)

            assertNull(store.find(accountUuid, contentId))
            expectIOException { store.open(accountUuid, contentId) }

            val recreatedStore = RuntimeSecureContentStore(context, registry)
            assertNull(recreatedStore.find(accountUuid, contentId))
            expectIOException { recreatedStore.open(accountUuid, contentId) }
        } finally {
            assertTrue(preferences.edit().putString(recordKey, original).commit())
            store.delete(accountUuid, contentId)
        }
    }

    @Test
    fun tamperedRecoveryRecordBlocksReadsForItsAccount() {
        val accountUuid = freshId("account")
        val committedContentId = freshId("committed")
        val pendingContentId = freshId("pending")
        registry.replaceRegisteredAccountUuids(listOf(accountUuid))

        val store = RuntimeSecureContentStore(context, registry)
        publish(
            store,
            accountUuid,
            committedContentId,
            freshId("message"),
            "known-good payload".toByteArray(Charsets.UTF_8),
        )

        val recoveryPreferences =
            context.getSharedPreferences(RECOVERY_PREFERENCES, Context.MODE_PRIVATE)
        val beforeKeys = recoveryPreferences.all.keys.toSet()
        val handle = store.allocate(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = pendingContentId,
                messageUuid = freshId("message"),
                mimeType = "application/octet-stream",
                sizeBytes = 7L,
            ),
        )
        val writer = store.beginWrite(handle)
        writer.openPlaintextOutputStream().use { it.write("pending".toByteArray(Charsets.UTF_8)) }
        val transaction = writer.finishAndBeginCommit()

        val recoveryKey = singleNewRecordKey(recoveryPreferences, beforeKeys)
        val original = recoveryPreferences.getString(recoveryKey, null)
            ?: failWith("recovery record must exist")
        try {
            tamperProtectedPayload(recoveryPreferences, recoveryKey, original)

            expectIOException { store.open(accountUuid, committedContentId) }
        } finally {
            assertTrue(recoveryPreferences.edit().putString(recoveryKey, original).commit())
            transaction.abort()
            store.delete(accountUuid, pendingContentId)
            store.delete(accountUuid, committedContentId)
        }
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
        writer.openPlaintextOutputStream().use { it.write(payload) }
        val committed = writer.finishAndBeginCommit().commit()
        assertEquals(SecureContentState.COMMITTED, committed.state)
    }

    private fun tamperProtectedPayload(
        preferences: SharedPreferences,
        recordKey: String,
        original: String,
    ) {
        val envelopeBytes = Base64.decode(original, Base64.NO_WRAP)
        val envelope = JSONObject(String(envelopeBytes, StandardCharsets.ISO_8859_1))
        val protected = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP)
        assertTrue("protected payload must not be empty", protected.isNotEmpty())
        protected[protected.lastIndex] = (protected.last().toInt() xor 0x01).toByte()
        envelope.put("payload", Base64.encodeToString(protected, Base64.NO_WRAP))
        val tampered = Base64.encodeToString(
            envelope.toString().toByteArray(StandardCharsets.ISO_8859_1),
            Base64.NO_WRAP,
        )
        assertTrue(preferences.edit().putString(recordKey, tampered).commit())
    }

    private fun singleNewRecordKey(
        preferences: SharedPreferences,
        beforeKeys: Set<String>,
    ): String {
        val created = preferences.all.keys.filter { it !in beforeKeys && it.startsWith("record.") }
        assertEquals("exactly one durable record must be created", 1, created.size)
        return created.single()
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    private fun failWith(message: String): Nothing {
        fail(message)
        throw AssertionError(message)
    }

    private fun freshId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private companion object {
        const val METADATA_PREFERENCES = "secure_content_metadata_v1"
        const val RECOVERY_PREFERENCES = "secure_content_recovery_v1"
    }
}
