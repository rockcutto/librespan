package eu.siacs.conversations.storage.secure

/**
 * Keeps message deletion coupled to every enabled Secure Content namespace, not just protected
 * text. A media-only rollout must retire its exact Store relation before the owning DB row goes.
 */
object SecureMessageMediaLifecyclePolicy {
    @JvmStatic
    fun requiresRetirement(
        secureMediaRollout: Boolean,
        secureMessagePayloadRollout: Boolean,
    ): Boolean = secureMediaRollout || secureMessagePayloadRollout

    @JvmStatic
    fun usesMediaOnlyRetirement(
        secureMediaRollout: Boolean,
        secureMessagePayloadRollout: Boolean,
    ): Boolean = secureMediaRollout && !secureMessagePayloadRollout
}
