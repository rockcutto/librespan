package eu.siacs.conversations.ui.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentStagingRetirer;

/** Bounded app-private plaintext staging required by video editor/transcoder output APIs. */
public final class VideoAttachmentStaging {

    public static final String DIRECTORY_NAME = "SecureOutgoingVideos";
    static final String FILE_PROVIDER_ROOT = "secure_outgoing_videos";

    private VideoAttachmentStaging() {}

    public static File createTranscodedFile(final Context context, final String messageUuid) {
        return new File(directory(context), messageUuid + ".mp4");
    }

    public static File createEditorOutputFile(final Context context) {
        return new File(directory(context), "editor-" + UUID.randomUUID() + ".mp4");
    }

    public static boolean isControlledUri(final Context context, final Uri uri) {
        return fileForUri(context, uri) != null;
    }

    @Nullable
    public static SecureOutgoingAttachmentStagingRetirer retirerForUri(
            final Context context, final Uri uri) {
        final File file = fileForUri(context, uri);
        return file == null ? null : () -> retire(file);
    }

    public static boolean retireControlledUri(final Context context, final Uri uri) {
        final File file = fileForUri(context, uri);
        return file == null || retire(file);
    }

    /** A missing staging file is already retired; a deletion failure is reported to the caller. */
    public static boolean retire(final File file) {
        return !file.exists() || file.delete();
    }

    @Nullable
    private static File fileForUri(final Context context, final Uri uri) {
        if (uri == null
                || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !FileBackend.getAuthority(context).equals(uri.getAuthority())) {
            return null;
        }
        final List<String> segments = uri.getPathSegments();
        if (!isControlledProviderPath(segments)) {
            return null;
        }
        try {
            final File root = directory(context).getCanonicalFile();
            final File file = new File(root, segments.get(1)).getCanonicalFile();
            return root.equals(file.getParentFile()) ? file : null;
        } catch (final IOException e) {
            return null;
        }
    }

    static boolean isControlledProviderPath(final List<String> segments) {
        return segments != null
                && segments.size() == 2
                && FILE_PROVIDER_ROOT.equals(segments.get(0))
                && isSafeFileName(segments.get(1));
    }

    private static boolean isSafeFileName(final String filename) {
        return filename != null
                && !filename.isEmpty()
                && !".".equals(filename)
                && !"..".equals(filename)
                && filename.indexOf('/') < 0
                && filename.indexOf('\\') < 0;
    }

    private static File directory(final Context context) {
        final File directory = new File(context.getCacheDir(), DIRECTORY_NAME);
        directory.mkdirs();
        return directory;
    }
}
