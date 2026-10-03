package eu.siacs.conversations.storage.secure

import java.io.IOException

/** Retires bounded app-owned plaintext staging after a committed Secure Content publication. */
fun interface SecureOutgoingAttachmentStagingRetirer {
    fun retire(): Boolean
}

object SecureOutgoingAttachmentStaging {
    /**
     * Staging is never retained as a fallback after secure publication. Retirement failure blocks
     * dispatch so the caller can retire a newly-created binding and preserve fail-closed behavior.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun retireAfterCommittedPublication(retirer: SecureOutgoingAttachmentStagingRetirer?) {
        if (retirer != null && !retirer.retire()) {
            throw IOException("Unable to retire outgoing attachment staging artifact")
        }
    }
}
