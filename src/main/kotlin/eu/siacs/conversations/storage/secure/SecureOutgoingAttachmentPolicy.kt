package eu.siacs.conversations.storage.secure

import java.util.Locale

/**
 * Scope gate for the first producer migration.
 *
 * Generic files/documents, images, and completed app-owned inline voice recordings use Secure
 * Content first. Video/transcoding remains owned by its bounded checkpoint.
 */
object SecureOutgoingAttachmentPolicy {
    @JvmStatic
    fun isSimpleFile(mimeType: String?): Boolean {
        val normalized = mimeType?.trim()?.lowercase(Locale.ROOT)
        return normalized.isNullOrEmpty() ||
            !(normalized.startsWith("image/") ||
                normalized.startsWith("audio/") ||
                normalized.startsWith("video/"))
    }

    @JvmStatic
    fun isImageAttachment(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase(Locale.ROOT)?.startsWith("image/") == true

    /** Video is secure once producer staging has completed or generic ingress is selected. */
    @JvmStatic
    fun isVideoAttachment(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase(Locale.ROOT)?.startsWith("video/") == true

    /** Only a completed recorder staging URI controlled by the app may take the voice route. */
    @JvmStatic
    fun isInlineVoiceRecording(mimeType: String?, controlledVoiceStagingUri: Boolean): Boolean =
        controlledVoiceStagingUri &&
            mimeType?.trim()?.lowercase(Locale.ROOT)?.startsWith("audio/") == true
}
