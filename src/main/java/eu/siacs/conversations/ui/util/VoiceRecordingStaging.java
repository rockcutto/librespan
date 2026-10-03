package eu.siacs.conversations.ui.util;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;

import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.storage.secure.SecureOutgoingVoiceStagingRetirer;

/**
 * Bounded plaintext staging for MediaRecorder-backed outgoing voice recordings.
 *
 * <p>The file exists only until Secure Content publication commits and must never become a durable
 * attachment source. Both voice producers use this class so the controlled FileProvider identity
 * and retirement behaviour stay identical.</p>
 */
public final class VoiceRecordingStaging {

    private static final String DIRECTORY_NAME = "VoiceRecordings";
    private static final String FILE_PROVIDER_ROOT = "voice_recordings";
    private static final String FILE_PREFIX = "RECORDING_";

    private VoiceRecordingStaging() {}

    public static File createOutputFile(final Context context, final String filename) {
        return new File(directory(context), filename);
    }

    public static boolean isControlledUri(final Context context, final Uri uri) {
        return fileForUri(context, uri) != null;
    }

    @Nullable
    public static SecureOutgoingVoiceStagingRetirer retirerForUri(
            final Context context, final Uri uri) {
        final File file = fileForUri(context, uri);
        return file == null ? null : () -> retire(file);
    }

    /** A missing staging file is already retired; a deletion failure is reported to fail closed. */
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
        if (segments.size() != 2 || !FILE_PROVIDER_ROOT.equals(segments.get(0))) {
            return null;
        }
        final String filename = segments.get(1);
        if (!isExpectedFilename(filename)) {
            return null;
        }
        try {
            final File directory = directory(context).getCanonicalFile();
            final File file = new File(directory, filename).getCanonicalFile();
            return directory.equals(file.getParentFile()) ? file : null;
        } catch (final IOException e) {
            return null;
        }
    }

    private static boolean isExpectedFilename(final String filename) {
        return filename.startsWith(FILE_PREFIX)
                && (filename.endsWith(".m4a") || filename.endsWith(".oga"));
    }

    private static File directory(final Context context) {
        return new File(context.getCacheDir(), DIRECTORY_NAME);
    }
}
