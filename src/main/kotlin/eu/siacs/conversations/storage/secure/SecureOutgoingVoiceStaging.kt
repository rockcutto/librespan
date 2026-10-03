package eu.siacs.conversations.storage.secure

import java.io.IOException

/** Owns retirement of the recorder-only plaintext staging artifact after Store publication. */
fun interface SecureOutgoingVoiceStagingRetirer : SecureOutgoingAttachmentStagingRetirer

object SecureOutgoingVoiceStaging {
    /**
     * A staging artifact is never retained as a fallback after a committed secure publication.
     * Failure to retire it prevents send dispatch; the caller retires the new binding instead.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun retireAfterCommittedPublication(retirer: SecureOutgoingVoiceStagingRetirer?) {
        SecureOutgoingAttachmentStaging.retireAfterCommittedPublication(retirer)
    }
}
