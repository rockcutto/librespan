package eu.siacs.conversations.storage.secure

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Bookmark
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.services.MucPasswordChange
import eu.siacs.conversations.xmpp.Jid
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real existing vault/Android Keystore. Run on an unlocked, disposable debug installation. */
@RunWith(AndroidJUnit4::class)
class MucPasswordVaultInstrumentationTest {
    @Test
    fun protectedCandidateSurvivesAdapterRecreationWithoutPlaintextPreferencesOrMessageAttributes() = fixture { context, room, vault ->
        val secret = "P1C-sensitive-candidate-${UUID.randomUUID()}"
        val transaction = MucPasswordChange(vault) { true }
        assertTrue(transaction.prepare(true, secret, false))
        assertNotNull(transaction.markSent())
        assertEquals(MucPasswordChange.Outcome.UNCERTAIN, transaction.finish(MucPasswordChange.Reply.TIMEOUT))
        val restarted = MucPasswordVault(context, room)
        assertEquals(secret, restarted.read()!!.password)
        assertTrue(restarted.hydrate())
        assertEquals(secret, room.mucOptions.password)
        assertFalse(room.attributes.toString().contains(secret))
        assertFalse(context.getSharedPreferences("account_secret_vault_v1", Context.MODE_PRIVATE)
            .all.values.any { it.toString().contains(secret) })
        val retry = MucPasswordChange(restarted) { true }
        assertTrue(retry.prepare(false, null, true))
        retry.markSent()
        assertEquals(MucPasswordChange.Outcome.SUCCESS, retry.finish(MucPasswordChange.Reply.RESULT))
        assertEquals(secret, ScopedAccountSecretVaultV1.readString(context, room.account.uuid, "MUC_PASSWORD", room.uuid))
        assertEquals(secret, ScopedAccountSecretVaultV1.readString(context, room.account.uuid, "BOOKMARK_PASSWORD", room.jid.toString()))
        assertNull(restarted.read())
    }

    @Test
    fun disableDeletesSecretsAndStaleBookmarkCannotResurrectThem() = fixture { context, room, vault ->
        assertTrue(vault.mucPassword("old-room-secret"))
        assertTrue(vault.bookmarkPassword("old-room-secret"))
        val transaction = MucPasswordChange(vault) { true }
        assertTrue(transaction.prepare(false, null, false))
        transaction.markSent()
        assertEquals(MucPasswordChange.Outcome.SUCCESS, transaction.finish(MucPasswordChange.Reply.RESULT))
        assertNull(ScopedAccountSecretVaultV1.readString(context, room.account.uuid, "MUC_PASSWORD", room.uuid))
        assertNull(ScopedAccountSecretVaultV1.readString(context, room.account.uuid, "BOOKMARK_PASSWORD", room.jid.toString()))
        val stale = Bookmark(room.account, room.jid)
        stale.setPassword("old-room-secret")
        room.account.putBookmark(stale)
        assertTrue(vault.bookmarkPassword("old-room-secret"))
        assertTrue(MucPasswordVault(context, room).hydrate())
        assertNull(room.mucOptions.password)
        assertNull(stale.password)
        assertNull(ScopedAccountSecretVaultV1.readString(context, room.account.uuid, "BOOKMARK_PASSWORD", room.jid.toString()))
        assertTrue(vault.read()!!.disabledConfirmed)
        // A later external owner may protect the room again; the existing explicit prompt still works.
        assertTrue(vault.mucPassword("externally-changed-secret"))
        assertTrue(vault.acceptProvidedPassword())
        assertNull(vault.read())
    }

    @Test
    fun timedOutChangeKeepsPreviousCredentialForExistingPasswordRequiredPrompt() = fixture { context, room, vault ->
        assertTrue(vault.mucPassword("previous-access-secret"))
        val transaction = MucPasswordChange(vault) { true }
        assertTrue(transaction.prepare(true, "candidate-access-secret", false))
        transaction.markSent()
        transaction.finish(MucPasswordChange.Reply.TIMEOUT)
        val restarted = MucPasswordVault(context, room)
        assertTrue(restarted.hydrate())
        assertEquals("candidate-access-secret", room.mucOptions.password)
        room.mucOptions.setError(MucOptions.Error.PASSWORD_REQUIRED)
        assertEquals("previous-access-secret", room.mucOptions.password)
        assertTrue(restarted.mucPassword("previous-access-secret"))
        assertTrue(restarted.acceptProvidedPassword())
        room.resetMucOptions()
        assertTrue(restarted.hydrate())
        assertEquals("previous-access-secret", room.mucOptions.password)
        assertEquals("candidate-access-secret", restarted.read()!!.password)
    }

    private fun fixture(block: (Context, Conversation, MucPasswordVault) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = Account(Jid.of("p1c-test@example.invalid"), "")
        val room = Conversation("p1c", account, Jid.of("p1c-${UUID.randomUUID()}@conference.example.invalid"), Conversation.MODE_MULTI, null)
        assumeTrue(ScopedAccountSecretVaultV1.isAvailable(context, account.uuid))
        val vault = MucPasswordVault(context, room)
        try { block(context, room, vault) }
        finally {
            vault.write(null)
            vault.mucPassword(null)
            vault.bookmarkPassword(null)
            PersistentSecureContentKeyMaterialStore(context).retireDeletedAccountKeyMaterial(account.uuid)
        }
    }
}
