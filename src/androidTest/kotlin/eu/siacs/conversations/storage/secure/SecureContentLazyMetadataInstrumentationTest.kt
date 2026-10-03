package eu.siacs.conversations.storage.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks the cold-start contract: exact Secure Content metadata reads are lazy and authenticated,
 * while full-store scans are reserved for explicit enumeration paths.
 */
@RunWith(AndroidJUnit4::class)
class SecureContentLazyMetadataInstrumentationTest {

    @Test
    fun constructorAndExactLookupStayOffGlobalMetadataScan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val keyStore = PersistentSecureContentKeyMaterialStore(context)
        val writer = AccountProtectedSecureContentMetadataStore(context, keyStore)

        writer.create(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = contentId,
                messageUuid = freshId("message"),
                cryptoVersion = 1,
            ),
            storageLocator = null,
        )

        try {
            SecureColdStartPerfTrace.start()
            val reader = AccountProtectedSecureContentMetadataStore(context, keyStore)

            assertFalse(SecureColdStartPerfTrace.report().contains("store_metadata_scan="))

            val record = reader.find(accountUuid, contentId)
            assertEquals(contentId, record?.contentId)
            assertEquals(accountUuid, record?.accountUuid)

            assertFalse(SecureColdStartPerfTrace.report().contains("store_metadata_scan="))
        } finally {
            SecureColdStartPerfTrace.clear()
            writer.deleteMetadata(accountUuid, contentId)
        }
    }

    @Test
    fun boundedExactLookupScopeReusesOneAccountMetadataAead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountUuid = freshId("account")
        val firstContentId = freshId("content")
        val secondContentId = freshId("content")
        val keyStore = PersistentSecureContentKeyMaterialStore(context)
        val writer = AccountProtectedSecureContentMetadataStore(context, keyStore)

        listOf(firstContentId, secondContentId).forEach { contentId ->
            writer.create(
                SecureContentMetadata(
                    accountUuid = accountUuid,
                    contentId = contentId,
                    messageUuid = freshId("message"),
                    cryptoVersion = 1,
                ),
                storageLocator = null,
            )
        }

        try {
            SecureColdStartPerfTrace.start()
            val reader = AccountProtectedSecureContentMetadataStore(context, keyStore)
            reader.beginReadScope(accountUuid).use { scope ->
                assertEquals(firstContentId, scope.find(firstContentId)?.contentId)
                assertEquals(secondContentId, scope.find(secondContentId)?.contentId)
            }

            val report = SecureColdStartPerfTrace.report()
            assertTrue(report.contains("store_metadata_scoped_reads=2"))
            assertTrue(report.contains("store_metadata_scoped_aead_resolves=1"))
            assertFalse(report.contains("store_metadata_scan="))
        } finally {
            SecureColdStartPerfTrace.clear()
            writer.deleteMetadata(accountUuid, firstContentId)
            writer.deleteMetadata(accountUuid, secondContentId)
        }
    }

    @Test
    fun messageRelationLookupUsesPersistentCandidatesWithoutGlobalScan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountUuid = freshId("account")
        val messageUuid = freshId("message")
        val firstContentId = freshId("content")
        val secondContentId = freshId("content")
        val keyStore = PersistentSecureContentKeyMaterialStore(context)
        val writer = AccountProtectedSecureContentMetadataStore(context, keyStore)

        listOf(firstContentId, secondContentId).forEach { contentId ->
            writer.create(
                SecureContentMetadata(
                    accountUuid = accountUuid,
                    contentId = contentId,
                    messageUuid = messageUuid,
                    cryptoVersion = 1,
                ),
                storageLocator = null,
            )
        }

        try {
            SecureColdStartPerfTrace.start()
            val reader = AccountProtectedSecureContentMetadataStore(context, keyStore)
            val records = reader.findByMessageUuid(accountUuid, messageUuid)

            assertEquals(setOf(firstContentId, secondContentId), records.map { it.contentId }.toSet())
            assertFalse(SecureColdStartPerfTrace.report().contains("store_metadata_scan="))
        } finally {
            SecureColdStartPerfTrace.clear()
            writer.deleteMetadata(accountUuid, firstContentId)
            writer.deleteMetadata(accountUuid, secondContentId)
        }
    }

    @Test
    fun legacyRelationBackfillRestoresRoutingWithoutLookupScan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountUuid = freshId("account")
        val messageUuid = freshId("message")
        val contentId = freshId("content")
        val keyStore = PersistentSecureContentKeyMaterialStore(context)
        val writer = AccountProtectedSecureContentMetadataStore(context, keyStore)

        writer.create(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = contentId,
                messageUuid = messageUuid,
                cryptoVersion = 1,
            ),
            storageLocator = null,
        )

        val preferences =
            context.getSharedPreferences("secure_content_metadata_v1", Context.MODE_PRIVATE)
        val editor = preferences.edit().remove("relation_index_version")
        preferences.all.keys
            .filter { it.startsWith("relation.") }
            .forEach { key -> editor.remove(key) }
        assertTrue(editor.commit())

        try {
            SecureColdStartPerfTrace.start()
            val reader = AccountProtectedSecureContentMetadataStore(context, keyStore)
            assertTrue(reader.findByMessageUuid(accountUuid, messageUuid).isEmpty())
            assertFalse(SecureColdStartPerfTrace.report().contains("store_metadata_scan="))

            assertTrue(reader.backfillMessageRelationIndexIfNeeded())
            assertEquals(
                contentId,
                reader.findByMessageUuid(accountUuid, messageUuid).single().contentId,
            )
        } finally {
            SecureColdStartPerfTrace.clear()
            writer.deleteMetadata(accountUuid, contentId)
        }
    }

    @Test
    fun explicitAccountEnumerationMayBuildFullAuthenticatedSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountUuid = freshId("account")
        val contentId = freshId("content")
        val keyStore = PersistentSecureContentKeyMaterialStore(context)
        val writer = AccountProtectedSecureContentMetadataStore(context, keyStore)

        writer.create(
            SecureContentMetadata(
                accountUuid = accountUuid,
                contentId = contentId,
                messageUuid = freshId("message"),
                cryptoVersion = 1,
            ),
            storageLocator = null,
        )

        try {
            SecureColdStartPerfTrace.start()
            val reader = AccountProtectedSecureContentMetadataStore(context, keyStore)

            assertTrue(reader.findByAccount(accountUuid).any { it.contentId == contentId })
            assertTrue(SecureColdStartPerfTrace.report().contains("store_metadata_scan="))
        } finally {
            SecureColdStartPerfTrace.clear()
            writer.deleteMetadata(accountUuid, contentId)
        }
    }

    private fun freshId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
