package eu.siacs.conversations.ui.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentStagingRetirer;

/**
 * Bounded app-private plaintext staging for outgoing image producers.
 *
 * <p>Camera and editor output live under app cache. Transformed images are retired only after
 * Secure Content publication commits; canceled drafts may retire their controlled source directly.
 * External/document-provider URIs are never considered owned by this helper.</p>
 */
public final class ImageAttachmentStaging {

    public static final String DIRECTORY_NAME = "SecureOutgoingImages";
    static final String FILE_PROVIDER_ROOT = "secure_outgoing_images";
    static final String CAMERA_PROVIDER_ROOT = "camera";

    private ImageAttachmentStaging() {}

    public static File createTransformedFile(
            final Context context, final String messageUuid, final String extension) {
        final String safeExtension =
                extension == null
                        ? "jpg"
                        : extension.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return new File(
                directory(context),
                messageUuid + "." + (safeExtension.isEmpty() ? "jpg" : safeExtension));
    }

    public static File createEditorOutputFile(final Context context, final String sourceName) {
        final String candidate = new File(sourceName == null ? "" : sourceName).getName();
        final String stem = candidate.isEmpty() ? java.util.UUID.randomUUID().toString() : candidate;
        File output = new File(directory(context), stem + ".jpg");
        int counter = 1;
        while (output.exists()) {
            output = new File(directory(context), stem + "(" + counter + ").jpg");
            counter++;
        }
        return output;
    }

    @Nullable
    public static SecureOutgoingAttachmentStagingRetirer retirerForUri(
            final Context context, final Uri uri) {
        final File file = fileForUri(context, uri);
        return file == null ? null : () -> retire(file);
    }

    @Nullable
    public static SecureOutgoingAttachmentStagingRetirer combine(
            @Nullable final SecureOutgoingAttachmentStagingRetirer first,
            @Nullable final SecureOutgoingAttachmentStagingRetirer second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return () -> first.retire() && second.retire();
    }

    /** A missing staging file is already retired; a deletion failure is reported to the caller. */
    public static boolean retire(final File file) {
        return !file.exists() || file.delete();
    }

    public static boolean retireControlledUri(final Context context, final Uri uri) {
        final File file = fileForUri(context, uri);
        return file == null || retire(file);
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
        final File root;
        if (FILE_PROVIDER_ROOT.equals(segments.get(0))) {
            root = directory(context);
        } else if (CAMERA_PROVIDER_ROOT.equals(segments.get(0))) {
            root = new File(context.getCacheDir(), "Camera");
        } else {
            return null;
        }
        final String filename = segments.get(1);
        try {
            final File directory = root.getCanonicalFile();
            final File file = new File(directory, filename).getCanonicalFile();
            return directory.equals(file.getParentFile()) ? file : null;
        } catch (final IOException e) {
            return null;
        }
    }

    static boolean isControlledProviderPath(final List<String> segments) {
        if (segments == null || segments.size() != 2) {
            return false;
        }
        final String root = segments.get(0);
        final String filename = segments.get(1);
        return (FILE_PROVIDER_ROOT.equals(root) || CAMERA_PROVIDER_ROOT.equals(root))
                && filename != null
                && !filename.isEmpty()
                && !".".equals(filename)
                && !"..".equals(filename);
    }

    private static File directory(final Context context) {
        return new File(context.getCacheDir(), DIRECTORY_NAME);
    }
}
