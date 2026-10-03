package eu.siacs.conversations.storage.secure

/**
 * Completion visibility policy shared by HTTP and Jingle receive paths.
 *
 * Secure rollout completion is Store-only: it may notify UI consumers, but it must never publish
 * or media-scan a legacy plaintext attachment. The legacy scanner remains a compatibility action
 * only while the secure media rollout is disabled.
 */
object SecureIncomingMediaCompletionPolicy {
    enum class Visibility {
        SECURE_STORE_ONLY,
        LEGACY_MEDIA_SCAN,
    }

    @JvmStatic
    fun visibility(secureMediaRollout: Boolean): Visibility =
        if (secureMediaRollout) Visibility.SECURE_STORE_ONLY else Visibility.LEGACY_MEDIA_SCAN

    @JvmStatic
    fun shouldScanLegacyMedia(secureMediaRollout: Boolean): Boolean =
        visibility(secureMediaRollout) == Visibility.LEGACY_MEDIA_SCAN
}
