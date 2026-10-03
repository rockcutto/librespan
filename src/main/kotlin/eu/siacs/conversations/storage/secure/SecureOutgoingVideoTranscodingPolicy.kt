package eu.siacs.conversations.storage.secure

/**
 * Keeps transcoder compatibility output separate from attachment authority.
 *
 * When secure media rollout is enabled, transcode failure may only re-enter the generic Secure
 * Content ingress path. The legacy message file is never a fallback destination.
 */
object SecureOutgoingVideoTranscodingPolicy {
    @JvmStatic
    fun usesPrivateStaging(secureMediaRollout: Boolean): Boolean = secureMediaRollout

    @JvmStatic
    fun fallbackUsesSecureGenericIngress(secureMediaRollout: Boolean): Boolean = secureMediaRollout
}
