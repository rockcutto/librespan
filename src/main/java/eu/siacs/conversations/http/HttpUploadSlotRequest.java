package eu.siacs.conversations.http;

import androidx.annotation.Nullable;

/**
 * Protocol facts required to request an HTTP Upload slot.
 *
 * This is deliberately not a filesystem abstraction: filename is one wire-level presentation
 * component and expectedWireSizeBytes describes the bytes the request body will transmit.
 */
public final class HttpUploadSlotRequest {

    private final String displayFilename;
    @Nullable private final String mimeType;
    private final long expectedWireSizeBytes;

    public HttpUploadSlotRequest(
            final String displayFilename,
            @Nullable final String mimeType,
            final long expectedWireSizeBytes) {
        validateDisplayFilename(displayFilename);
        if (expectedWireSizeBytes < 0) {
            throw new IllegalArgumentException("expectedWireSizeBytes must not be negative");
        }
        this.displayFilename = displayFilename;
        this.mimeType = mimeType;
        this.expectedWireSizeBytes = expectedWireSizeBytes;
    }


    /**
     * Validates the one wire-level filename component used in HTTP Upload slot XML.
     *
     * This is shared by all public construction paths so a direct generator call cannot bypass
     * the no-path boundary.
     */
    public static void validateDisplayFilename(@Nullable final String displayFilename) {
        if (displayFilename == null || displayFilename.trim().isEmpty()) {
            throw new IllegalArgumentException("displayFilename must not be blank");
        }
        if (".".equals(displayFilename) || "..".equals(displayFilename)
                || displayFilename.indexOf('/') >= 0
                || displayFilename.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("displayFilename must not be a path");
        }
        for (int i = 0; i < displayFilename.length(); i++) {
            if (Character.isISOControl(displayFilename.charAt(i))) {
                throw new IllegalArgumentException("displayFilename must not contain control characters");
            }
        }
    }

    public String getDisplayFilename() {
        return displayFilename;
    }

    @Nullable
    public String getMimeType() {
        return mimeType;
    }

    public long getExpectedWireSizeBytes() {
        return expectedWireSizeBytes;
    }
}
