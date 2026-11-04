package eu.siacs.conversations.storage.secure

import android.content.Context
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.services.MucPasswordChange
import java.io.IOException
import java.security.MessageDigest

/** Uses existing account AEAD, record store and secret types. No plaintext persistence. */
class MucPasswordVault(context: Context, private val conversation: Conversation) : MucPasswordChange.Vault {
    private val context = context.applicationContext
    private val account = conversation.account.uuid
    private val room = conversation.jid.asBareJid().toString()
    private val scope = "muc-owner:" + MessageDigest.getInstance("SHA-256")
        .digest(room.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun ensureAvailable() {
        if (!ScopedAccountSecretVaultV1.isAvailable(context, account)) {
            throw IOException("Protected room password storage unavailable")
        }
    }

    override fun read(): MucPasswordChange.Record? {
        ensureAvailable()
        // Unlike readBytes(), preserve Failure vs absent: a corrupt/unavailable pending record
        // must never be mistaken for permission to overwrite an uncertain operation.
        return when (val result = PersistentAccountSecretVaultV1(context).open(
            AccountSecretKeyV1(account, AccountSecretTypeV1.MUC_PASSWORD, scope),
        )) {
            is AccountSecretVaultResultV1.Failure -> throw IOException("Protected room password storage unavailable")
            is AccountSecretVaultResultV1.Success -> result.value?.use { value ->
                value.useBytes { MucPasswordChange.Record.decode(it) }
            }
        }
    }

    override fun write(record: MucPasswordChange.Record?): Boolean {
        ensureAvailable()
        val bytes = record?.encode()
        return try {
            ScopedAccountSecretVaultV1.storeBytes(context, account, "MUC_PASSWORD", scope, bytes)
        } finally { bytes?.fill(0) }
    }

    override fun mucPassword(password: String?): Boolean {
        ensureAvailable()
        return ScopedAccountSecretVaultV1.storeString(context, account, "MUC_PASSWORD", conversation.uuid, password)
    }

    override fun bookmarkPassword(password: String?): Boolean {
        ensureAvailable()
        return ScopedAccountSecretVaultV1.storeString(context, account, "BOOKMARK_PASSWORD", room, password)
    }

    override fun previousPassword(): String? = readPassword(AccountSecretTypeV1.MUC_PASSWORD, conversation.uuid)
        ?: readPassword(AccountSecretTypeV1.BOOKMARK_PASSWORD, room)

    private fun readPassword(type: AccountSecretTypeV1, scope: String): String? {
        ensureAvailable()
        return when (val result = PersistentAccountSecretVaultV1(context).open(AccountSecretKeyV1(account, type, scope))) {
            is AccountSecretVaultResultV1.Failure -> throw IOException("Protected room password storage unavailable")
            is AccountSecretVaultResultV1.Success -> result.value?.use { value ->
                value.useBytes { String(it, Charsets.UTF_8) }
            }
        }
    }

    /** An explicit password prompt/invitation must remain usable after an external owner change. */
    fun acceptProvidedPassword(): Boolean {
        val record = read() ?: return true
        return if (record.disabledConfirmed) write(null) else write(record.manual())
    }

    /** Pending candidate survives a lost ACK; a disabled marker prevents stale bookmark fallback. */
    fun hydrate(): Boolean {
        val record = read() ?: return false
        if (record.disabledConfirmed || (record.pending() && !record.attempted && record.disabledBefore)) {
            conversation.mucOptions.setPassword(null)
            conversation.bookmark?.setPassword(null)
            if (record.disabledConfirmed) {
                if (!mucPassword(null) || !bookmarkPassword(null)) throw IOException("Room password cleanup unavailable")
            }
            return true
        } else if (record.pending() && record.attempted) {
            val selected = if (record.manualSelection) {
                readPassword(AccountSecretTypeV1.MUC_PASSWORD, conversation.uuid)
            } else if (record.enabled) record.password else null
            conversation.mucOptions.setPassword(selected)
            if (record.previousPassword != selected) conversation.mucOptions.setPasswordFallback(record.previousPassword)
            conversation.bookmark?.setPassword(null)
            return true
        }
        return false
    }
}
