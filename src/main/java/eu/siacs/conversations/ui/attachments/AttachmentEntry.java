package eu.siacs.conversations.ui.attachments;

import androidx.annotation.Nullable;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.utils.MimeUtils;

/** Path-free presentation identity for one message-owned attachment. */
public final class AttachmentEntry {

    public enum Category {
        MEDIA,
        FILE,
        AUDIO
    }

    public enum Source {
        SECURE,
        LEGACY
    }

    public final String accountUuid;
    public final String conversationUuid;
    public final String messageUuid;
    public final Category category;
    public final Source source;
    @Nullable public final String mimeType;
    @Nullable public final String fileName;
    @Nullable public final String legacyPath;
    public final long sizeBytes;
    public final int durationMillis;
    public final int width;
    public final int height;
    public final long timeSent;

    public AttachmentEntry(
            final String accountUuid,
            final String conversationUuid,
            final String messageUuid,
            final Category category,
            final Source source,
            @Nullable final String mimeType,
            @Nullable final String fileName,
            @Nullable final String legacyPath,
            final long sizeBytes,
            final int durationMillis,
            final int width,
            final int height,
            final long timeSent) {
        this.accountUuid = accountUuid;
        this.conversationUuid = conversationUuid;
        this.messageUuid = messageUuid;
        this.category = category;
        this.source = source;
        this.mimeType = mimeType;
        this.fileName = fileName;
        this.legacyPath = legacyPath;
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.durationMillis = Math.max(0, durationMillis);
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.timeSent = timeSent;
    }

    public boolean isSecure() {
        return source == Source.SECURE;
    }

    public boolean isImage() {
        return mimeType != null
                && mimeType.startsWith("image/")
                && !"image/svg+xml".equals(mimeType);
    }

    public boolean isVideo() {
        return mimeType != null && mimeType.startsWith("video/");
    }

    @Nullable
    public static String resolvePresentationMime(
            @Nullable final String mimeType, @Nullable final String fileName) {
        return MimeUtils.resolvePresentationMime(mimeType, fileName);
    }

    public static Category classify(
            @Nullable final String mimeType,
            final int messageType,
            final int width,
            final int height,
            final int durationMillis) {
        final String normalized = MimeUtils.normalizeMimeType(mimeType);
        if (normalized != null) {
                if (normalized.startsWith("image/") || normalized.startsWith("video/")) {
                    return Category.MEDIA;
                }
                if (normalized.startsWith("audio/")) {
                    return Category.AUDIO;
                }
                if (MimeUtils.isExplicitDocumentMime(normalized)) {
                    // Strong document identity wins over stale provisional image metadata.
                    return Category.FILE;
                }
                if (MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(normalized)) {
                    if (width > 0 && height > 0) {
                        return Category.MEDIA;
                    }
                    if (durationMillis > 0) {
                        return Category.AUDIO;
                    }
                }
        }
        // Width/height and the legacy image message type remain useful recovery signals for
        // media whose server/provider MIME is generic or simply wrong (often application/*).
        if (messageType == Message.TYPE_IMAGE || (width > 0 && height > 0)) {
            return Category.MEDIA;
        }
        if (durationMillis > 0 && width <= 0 && height <= 0) {
            return Category.AUDIO;
        }
        return Category.FILE;
    }
}
