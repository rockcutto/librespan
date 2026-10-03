package eu.siacs.conversations.storage.secure

import com.google.common.io.BaseEncoding
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Keyed blind-index codec for protected message text.
 *
 * The index never contains recoverable message plaintext. Exact tokens and token prefixes are
 * HMACed with an account-scoped search key so the existing search UX can retain its default prefix
 * semantics without storing Message.body as a second plaintext copy.
 *
 * Security boundary: blind indexes still reveal equality/frequency and token/prefix counts within
 * one account. The search key therefore must be independent from content-encryption keys and must
 * never be stored in the index database itself. The codec consumes only an HMAC capability; it
 * never requests or exports the raw key.
 */
class SecureMessageSearchIndexCodec(
    private val macProvider: SecureMessageSearchMacProvider,
) {
    private data class IndexPreimage(
        val domain: String,
        val token: String,
    )

    fun indexTerms(accountUuid: String, plaintext: String): Set<String> {
        val preimages = linkedSetOf(IndexPreimage(COVERAGE_DOMAIN, COVERAGE_TOKEN))
        tokenize(plaintext).toCollection(linkedSetOf()).forEach { token ->
            preimages += IndexPreimage(EXACT_DOMAIN, token)
            val codePointCount = token.codePointCount(0, token.length)
            for (prefixLength in PREFIX_CODE_POINT_LENGTHS) {
                if (prefixLength > codePointCount) break
                preimages += IndexPreimage(
                    PREFIX_DOMAIN,
                    prefix(token, prefixLength),
                )
            }
        }
        return macProvider.withAccountMac(accountUuid) { mac ->
            preimages.mapTo(linkedSetOf()) { preimage ->
                digest(mac, preimage.domain, preimage.token)
            }
        }
    }

    /**
     * Verifies blind-index candidates with the same normalized token-prefix semantics as indexing.
     */
    internal fun matchesQueryGroup(
        plaintext: String,
        requiredTerms: Collection<String>,
        excludedTerms: Collection<String>,
    ): Boolean {
        if (requiredTerms.isEmpty()) return false
        val required = requiredTerms.flatMap(::queryTokens)
        if (required.isEmpty()) return false
        val excluded = excludedTerms.flatMap(::queryTokens)
        val tokens = tokenize(plaintext)
        return required.all { query -> tokens.any { token -> token.startsWith(query) } } &&
            excluded.none { excludedToken -> tokens.any { token -> token == excludedToken } }
    }

    /** Digests every normalized token in a user term using prefix semantics. */
    fun prefixQueryTerms(accountUuid: String, userTerm: String): Set<String> =
        queryTokens(userTerm).mapTo(linkedSetOf()) { normalized ->
            prefixQueryToken(accountUuid, normalized)
        }

    /** Digests every normalized token in a user term using exact semantics. */
    fun exactQueryTerms(accountUuid: String, userTerm: String): Set<String> =
        queryTokens(userTerm).mapTo(linkedSetOf()) { normalized ->
            digest(accountUuid, EXACT_DOMAIN, normalized)
        }

    /** Digest used for the normal NeoCont search behavior where a typed term matches prefixes. */
    fun prefixQueryTerm(accountUuid: String, userTerm: String): String? {
        val normalized = normalizeToken(userTerm) ?: return null
        return prefixQueryToken(accountUuid, normalized)
    }

    private fun prefixQueryToken(accountUuid: String, normalized: String): String {
        val codePointCount = normalized.codePointCount(0, normalized.length)
        require(codePointCount >= MIN_PREFIX_CODE_POINTS)
        val anchorLength = PREFIX_CODE_POINT_LENGTHS.last { it <= codePointCount }
        return digest(
            accountUuid,
            PREFIX_DOMAIN,
            prefix(normalized, anchorLength),
        )
    }

    /** Digest for an explicit whole-token lookup when a future caller requires exact semantics. */
    fun exactQueryTerm(accountUuid: String, userTerm: String): String? {
        val normalized = normalizeToken(userTerm) ?: return null
        return digest(accountUuid, EXACT_DOMAIN, normalized)
    }

    private fun digest(accountUuid: String, domain: String, token: String): String =
        digest(
            SecureMessageSearchMac { input -> macProvider.hmacSha256(accountUuid, input) },
            domain,
            token,
        )

    private fun digest(mac: SecureMessageSearchMac, domain: String, token: String): String {
        val input = "$VERSION|$domain|$token".toByteArray(StandardCharsets.UTF_8)
        val digest = mac.hmacSha256(input)
        require(digest.size == SHA256_BYTES) { "Secure search HMAC must return SHA-256 output" }
        return BaseEncoding.base64Url().omitPadding().encode(digest)
    }

    private fun tokenize(plaintext: String): List<String> =
        TOKEN_PATTERN.findAll(normalize(plaintext)).map { it.value }.filter { it.isNotBlank() }.toList()

    private fun normalizeToken(value: String): String? {
        return queryTokens(value).singleOrNull()
    }

    private fun queryTokens(value: String): List<String> = tokenize(value).distinct()

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun prefix(value: String, codePointCount: Int): String {
        val end = value.offsetByCodePoints(0, codePointCount)
        return value.substring(0, end)
    }

    companion object {
        private const val VERSION = "v1"
        private const val COVERAGE_DOMAIN = "coverage"
        private const val COVERAGE_TOKEN = "indexed"
        private const val EXACT_DOMAIN = "exact"
        private const val PREFIX_DOMAIN = "prefix"
        private const val SHA256_BYTES = 32
        private const val MIN_PREFIX_CODE_POINTS = 1
        private val PREFIX_CODE_POINT_LENGTHS = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16)
        private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
    }
}

/** Operation-scoped HMAC capability whose raw account key is never exposed. */
fun interface SecureMessageSearchMac {
    fun hmacSha256(input: ByteArray): ByteArray
}

/** Account-scoped HMAC capability whose raw key is never exposed to the index codec. */
fun interface SecureMessageSearchMacProvider {
    fun hmacSha256(accountUuid: String, input: ByteArray): ByteArray

    fun <T> withAccountMac(
        accountUuid: String,
        operation: (SecureMessageSearchMac) -> T,
    ): T = operation(SecureMessageSearchMac { input -> hmacSha256(accountUuid, input) })
}
