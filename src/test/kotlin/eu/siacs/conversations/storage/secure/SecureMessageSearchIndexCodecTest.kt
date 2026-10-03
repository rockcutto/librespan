package eu.siacs.conversations.storage.secure

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessageSearchIndexCodecTest {
    private val keyA = ByteArray(32) { index -> (index + 1).toByte() }
    private val keyB = ByteArray(32) { index -> (index + 33).toByte() }
    private val codec =
        SecureMessageSearchIndexCodec { accountUuid, input ->
            hmac(if (accountUuid == "account-a") keyA else keyB, input)
        }

    @Test
    fun indexContainsPrefixDigestWithoutPlaintext() {
        val terms = codec.indexTerms("account-a", "Привет World")
        val prefix = codec.prefixQueryTerm("account-a", "при")

        assertNotNull(prefix)
        assertTrue(terms.contains(prefix))
        assertFalse(terms.any { it.contains("привет", ignoreCase = true) })
        assertFalse(terms.any { it.contains("world", ignoreCase = true) })
    }

    @Test
    fun normalizationMakesCaseAndCompatibilityEquivalent() {
        assertEquals(
            codec.prefixQueryTerm("account-a", "WORLD"),
            codec.prefixQueryTerm("account-a", "world"),
        )
        assertEquals(
            codec.exactQueryTerm("account-a", "ＡＢＣ"),
            codec.exactQueryTerm("account-a", "abc"),
        )
    }

    @Test
    fun accountKeysPreventCrossAccountCorrelation() {
        assertNotEquals(
            codec.prefixQueryTerm("account-a", "hello"),
            codec.prefixQueryTerm("account-b", "hello"),
        )
    }

    @Test
    fun oneCharacterPrefixPreservesLegacyPrefixSearch() {
        val terms = codec.indexTerms("account-a", "alpha")
        val prefix = codec.prefixQueryTerm("account-a", "a")

        assertNotNull(prefix)
        assertTrue(terms.contains(prefix))
    }

    @Test
    fun punctuationSeparatedQueryUsesEveryNormalizedToken() {
        val indexed = codec.indexTerms("account-a", "foo bar baz")
        val query = codec.prefixQueryTerms("account-a", "foo-bar")

        assertEquals(2, query.size)
        assertTrue(indexed.containsAll(query))
        assertTrue(codec.matchesQueryGroup("foo bar baz", listOf("foo-bar"), emptyList()))
        assertFalse(codec.matchesQueryGroup("foo baz", listOf("foo-bar"), emptyList()))
    }

    @Test
    fun emailLikeQueryUsesTheSameTokensForIndexAndVerification() {
        val indexed = codec.indexTerms("account-a", "write to Name@example.com")
        val query = codec.prefixQueryTerms("account-a", "name@example.com")

        assertEquals(3, query.size)
        assertTrue(indexed.containsAll(query))
        assertTrue(
            codec.matchesQueryGroup(
                "write to Name@example.com",
                listOf("name@example.com"),
                emptyList(),
            ),
        )
    }

    @Test
    fun tokenlessPlaintextStillProducesCoverageMarker() {
        assertEquals(1, codec.indexTerms("account-a", "---").size)
        assertTrue(codec.prefixQueryTerms("account-a", "---").isEmpty())
        assertFalse(codec.matchesQueryGroup("---", listOf("---"), emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidHmacOutput() {
        SecureMessageSearchIndexCodec { _, _ -> ByteArray(16) }
            .indexTerms("account-a", "hello")
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
}
