package eu.siacs.conversations.storage.secure

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SecureContentMetadataAeadReadScopeTest {
    @Test
    fun resolvesEachAvailableAccountOnlyOncePerScope() {
        var calls = 0
        val aead = FakeAead()
        val scope =
            SecureContentMetadataAeadReadScope {
                calls++
                aead
            }

        assertSame(aead, scope.forAccount("account-a"))
        assertSame(aead, scope.forAccount("account-a"))
        assertEquals(1, calls)
        assertEquals(1, scope.resolveCount)
        assertEquals(1, scope.resolvedAccountCount)
    }

    @Test
    fun memoizesUnavailableAccountWithinScope() {
        var calls = 0
        val scope =
            SecureContentMetadataAeadReadScope {
                calls++
                null
            }

        assertNull(scope.forAccount("missing"))
        assertNull(scope.forAccount("missing"))
        assertEquals(1, calls)
        assertEquals(1, scope.resolveCount)
        assertEquals(0, scope.resolvedAccountCount)
    }

    @Test
    fun differentAccountsRemainIndependent() {
        var calls = 0
        val scope =
            SecureContentMetadataAeadReadScope {
                calls++
                FakeAead()
            }

        scope.forAccount("account-a")
        scope.forAccount("account-b")
        scope.forAccount("account-a")

        assertEquals(2, calls)
        assertEquals(2, scope.resolveCount)
        assertEquals(2, scope.resolvedAccountCount)
    }

    private class FakeAead : Aead {
        @Throws(GeneralSecurityException::class)
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext

        @Throws(GeneralSecurityException::class)
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = ciphertext
    }
}
