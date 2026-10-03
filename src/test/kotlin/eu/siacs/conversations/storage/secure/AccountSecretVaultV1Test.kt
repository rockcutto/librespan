package eu.siacs.conversations.storage.secure

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSecretVaultV1Test {

    init {
        AeadConfig.register()
    }

    @Test
    fun roundTripReturnsMutableCloseableSecret() {
        val aead = aead()
        val records = MemoryRecords()
        val vault = AccountSecretVaultV1(SameAeadProvider(aead), records)
        val key = AccountSecretKeyV1("account-a", AccountSecretTypeV1.XMPP_PASSWORD)
        val secret = "correct horse battery staple".toByteArray()

        assertTrue(vault.put(key, secret) is AccountSecretVaultResultV1.Success)

        val opened = vault.open(key) as AccountSecretVaultResultV1.Success
        val value = opened.value!!
        value.use {
            it.useBytes { bytes -> assertArrayEquals(secret, bytes) }
        }
        secret.fill(0)
    }

    @Test
    fun associatedDataRejectsCrossAccountCiphertextTransplant() {
        assertTransplantRejected(
            AccountSecretKeyV1("account-a", AccountSecretTypeV1.XMPP_PASSWORD),
            AccountSecretKeyV1("account-b", AccountSecretTypeV1.XMPP_PASSWORD),
        )
    }

    @Test
    fun associatedDataRejectsCrossTypeCiphertextTransplant() {
        assertTransplantRejected(
            AccountSecretKeyV1("account-a", AccountSecretTypeV1.XMPP_PASSWORD),
            AccountSecretKeyV1("account-a", AccountSecretTypeV1.FAST_TOKEN),
        )
    }

    @Test
    fun associatedDataRejectsCrossScopeCiphertextTransplant() {
        assertTransplantRejected(
            AccountSecretKeyV1(
                "account-a",
                AccountSecretTypeV1.MUC_PASSWORD,
                "conversation-a",
            ),
            AccountSecretKeyV1(
                "account-a",
                AccountSecretTypeV1.MUC_PASSWORD,
                "conversation-b",
            ),
        )
    }

    @Test
    fun privateCryptoTypesAreScopeBoundAndRoundTrip() {
        val aead = aead()
        val records = MemoryRecords()
        val vault = AccountSecretVaultV1(SameAeadProvider(aead), records)
        val types =
            listOf(
                AccountSecretTypeV1.OTR_KEYPAIR to "account",
                AccountSecretTypeV1.OMEMO_IDENTITY to "identity",
                AccountSecretTypeV1.OMEMO_SIGNED_PREKEY to "signed|7",
                AccountSecretTypeV1.OMEMO_PREKEY to "prekey|8",
                AccountSecretTypeV1.OMEMO_SESSION to "session|romeo@example.test|9",
                AccountSecretTypeV1.DRAFT_TEXT to "conversation-a",
            )

        for ((type, scope) in types) {
            val key = AccountSecretKeyV1("account-a", type, scope)
            val secret = ("secret-" + type.wireName).toByteArray()
            assertTrue(vault.put(key, secret) is AccountSecretVaultResultV1.Success)
            val opened = vault.open(key) as AccountSecretVaultResultV1.Success
            opened.value!!.use { value ->
                value.useBytes { bytes -> assertArrayEquals(secret, bytes) }
            }
            secret.fill(0)
        }
    }

    @Test
    fun lockedVaultRejectsPrivateCryptoDeletion() {
        val aead = aead()
        val records = MemoryRecords()
        val key =
            AccountSecretKeyV1(
                "account-a",
                AccountSecretTypeV1.OMEMO_IDENTITY,
                "identity",
            )
        val writer = AccountSecretVaultV1(SameAeadProvider(aead), records)
        val secret = "identity-private-state".toByteArray()
        assertTrue(writer.put(key, secret) is AccountSecretVaultResultV1.Success)
        secret.fill(0)

        val locked =
            AccountSecretVaultV1(
                object : AccountSecretVaultAeadProviderV1 {
                    override fun forWrite(accountUuid: String): Aead? = null
                    override fun forRead(accountUuid: String): Aead? = null
                },
                records,
            )

        val result = locked.delete(key)
        assertTrue(result is AccountSecretVaultResultV1.Failure)
        assertTrue(
            (result as AccountSecretVaultResultV1.Failure).reason ==
                AccountSecretVaultFailureV1.UNAVAILABLE,
        )
        assertTrue(writer.open(key) is AccountSecretVaultResultV1.Success)
    }

    @Test
    fun recordIdentityDoesNotExposeRoutingValues() {
        val key =
            AccountSecretKeyV1(
                "very-visible-account-uuid",
                AccountSecretTypeV1.MUC_PASSWORD,
                "very-visible-conversation-uuid",
            )
        val recordId = AccountSecretVaultRecordIdentityV1.forKey(key)

        assertFalse(recordId.contains(key.accountUuid))
        assertFalse(recordId.contains(key.scopeId))
        assertFalse(recordId.contains(key.type.wireName))
    }

    @Test
    fun existingCiphertextWithUnavailableAeadDoesNotLookMissing() {
        val aead = aead()
        val records = MemoryRecords()
        val key = AccountSecretKeyV1("account-a", AccountSecretTypeV1.XMPP_PASSWORD)
        val writer = AccountSecretVaultV1(SameAeadProvider(aead), records)
        val secret = "secret".toByteArray()
        writer.put(key, secret)
        secret.fill(0)

        val locked =
            AccountSecretVaultV1(
                object : AccountSecretVaultAeadProviderV1 {
                    override fun forWrite(accountUuid: String): Aead? = null
                    override fun forRead(accountUuid: String): Aead? = null
                },
                records,
            )

        val result = locked.open(key)
        assertTrue(result is AccountSecretVaultResultV1.Failure)
        assertTrue(
            (result as AccountSecretVaultResultV1.Failure).reason ==
                AccountSecretVaultFailureV1.UNAVAILABLE,
        )
    }

    private fun assertTransplantRejected(
        sourceKey: AccountSecretKeyV1,
        targetKey: AccountSecretKeyV1,
    ) {
        val aead = aead()
        val records = MemoryRecords()
        val vault = AccountSecretVaultV1(SameAeadProvider(aead), records)
        val secret = "secret-value".toByteArray()
        vault.put(sourceKey, secret)
        secret.fill(0)

        records.transplant(
            AccountSecretVaultRecordIdentityV1.forKey(sourceKey),
            AccountSecretVaultRecordIdentityV1.forKey(targetKey),
        )

        val result = vault.open(targetKey)
        assertTrue(result is AccountSecretVaultResultV1.Failure)
        assertTrue(
            (result as AccountSecretVaultResultV1.Failure).reason ==
                AccountSecretVaultFailureV1.CORRUPT,
        )
    }

    private fun aead(): Aead =
        KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    private class SameAeadProvider(
        private val aead: Aead,
    ) : AccountSecretVaultAeadProviderV1 {
        override fun forWrite(accountUuid: String): Aead = aead
        override fun forRead(accountUuid: String): Aead = aead
    }

    private class MemoryRecords : AccountSecretVaultRecordStoreV1 {
        private val values = linkedMapOf<String, ByteArray>()

        override fun read(recordId: String): ByteArray? = values[recordId]?.copyOf()

        override fun write(recordId: String, ciphertext: ByteArray): Boolean {
            values[recordId]?.fill(0)
            values[recordId] = ciphertext.copyOf()
            return true
        }

        override fun delete(recordId: String): Boolean {
            values.remove(recordId)?.fill(0)
            return true
        }

        fun transplant(source: String, target: String) {
            values[target]?.fill(0)
            values[target] = values[source]!!.copyOf()
        }
    }
}
