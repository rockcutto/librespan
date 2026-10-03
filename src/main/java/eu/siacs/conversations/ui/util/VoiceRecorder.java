package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.persistance.FileBackend;

/**
 * Lightweight in-composer voice recorder used for press-and-hold voice messages.
 *
 * <p>This intentionally mirrors the codec/device workarounds from RecordingActivity while keeping
 * recording inside the conversation screen.</p>
 */
public final class VoiceRecorder {

    public interface Callback {
        void onFinished(Uri uri, String mimeType, long durationMs);

        void onError();
    }

    private static final Set<String> AAC_SENSITIVE_DEVICES =
            new ImmutableSet.Builder<String>()
                    .add("FP4")
                    .add("ONEPLUS A6000")
                    .add("ONEPLUS A6003")
                    .add("ONEPLUS A6010")
                    .add("ONEPLUS A6013")
                    .add("Pixel 4a")
                    .add("WP12 Pro")
                    .add("Volla Phone X")
                    .build();

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaRecorder recorder;
    private File outputFile;
    private FileObserver fileObserver;
    private CountDownLatch outputClosedLatch;
    private long startedAt;
    private String mimeType;

    public VoiceRecorder(final Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean start() {
        if (recorder != null) {
            return false;
        }

        final MediaRecorder newRecorder = new MediaRecorder();
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                newRecorder.setPrivacySensitive(true);
            }

            final int outputFormat;
            if (Config.USE_OPUS_VOICE_MESSAGES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outputFormat = MediaRecorder.OutputFormat.OGG;
                mimeType = "audio/ogg";
                newRecorder.setOutputFormat(outputFormat);
                newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
                newRecorder.setAudioEncodingBitRate(32_000);
            } else {
                outputFormat = MediaRecorder.OutputFormat.MPEG_4;
                mimeType = "audio/mp4";
                newRecorder.setOutputFormat(outputFormat);
                if (AAC_SENSITIVE_DEVICES.contains(Build.MODEL)
                        && Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
                    newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
                    newRecorder.setAudioSamplingRate(24_000);
                    newRecorder.setAudioEncodingBitRate(28_000);
                } else {
                    newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    newRecorder.setAudioSamplingRate(44_100);
                    newRecorder.setAudioEncodingBitRate(64_000);
                }
            }

            outputFile = generateOutputFilename(outputFormat);
            final File parentDirectory = Objects.requireNonNull(outputFile.getParentFile());
            if (!parentDirectory.exists() && parentDirectory.mkdirs()) {
                Log.d(Config.LOGTAG, "created " + parentDirectory.getAbsolutePath());
            }
            setupFileObserver(parentDirectory);

            newRecorder.setOutputFile(outputFile.getAbsolutePath());
            newRecorder.prepare();
            newRecorder.start();

            recorder = newRecorder;
            startedAt = SystemClock.elapsedRealtime();
            Log.d(Config.LOGTAG, "started inline voice recording to " + outputFile.getAbsolutePath());
            return true;
        } catch (final Exception e) {
            Log.e(Config.LOGTAG, "could not start inline voice recording", e);
            try {
                newRecorder.release();
            } catch (final Exception ignored) {
            }
            cleanupObserver();
            deleteOutputFile();
            recorder = null;
            return false;
        }
    }

    public boolean isRecording() {
        return recorder != null;
    }

    public long getElapsedMillis() {
        return recorder == null ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
    }

    public void cancel() {
        final MediaRecorder activeRecorder = recorder;
        recorder = null;
        if (activeRecorder != null) {
            try {
                activeRecorder.stop();
            } catch (final Exception e) {
                Log.d(Config.LOGTAG, "could not stop canceled inline voice recording", e);
            }
            try {
                activeRecorder.release();
            } catch (final Exception ignored) {
            }
        }
        cleanupObserver();
        deleteOutputFile();
        startedAt = 0L;
    }

    public void finish(final Callback callback) {
        final MediaRecorder activeRecorder = recorder;
        final File finishedFile = outputFile;
        final CountDownLatch finishedLatch = outputClosedLatch;
        final String finishedMime = mimeType;
        final long duration = getElapsedMillis();
        recorder = null;

        if (activeRecorder == null || finishedFile == null) {
            callback.onError();
            return;
        }

        try {
            activeRecorder.stop();
            activeRecorder.release();
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not finish inline voice recording", e);
            try {
                activeRecorder.release();
            } catch (final Exception ignored) {
            }
            cleanupObserver();
            deleteOutputFile();
            mainHandler.post(callback::onError);
            return;
        }

        new Thread(
                        () -> {
                            if (finishedLatch != null) {
                                try {
                                    finishedLatch.await(2, TimeUnit.SECONDS);
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            cleanupObserver();
                            if (!finishedFile.exists() || !finishedFile.isFile() || finishedFile.length() <= 0) {
                                Log.e(
                                        Config.LOGTAG,
                                        "inline voice recording finished without a readable file: "
                                                + finishedFile.getAbsolutePath());
                                deleteOutputFile();
                                mainHandler.post(callback::onError);
                                return;
                            }
                            Log.d(
                                    Config.LOGTAG,
                                    "inline voice recording ready: "
                                            + finishedFile.length()
                                            + " bytes, mime="
                                            + finishedMime);
                            mainHandler.post(
                                    () -> {
                                        try {
                                            callback.onFinished(
                                                    FileBackend.getUriForFile(context, finishedFile),
                                                    finishedMime,
                                                    duration);
                                        } catch (final RuntimeException e) {
                                            Log.e(
                                                    Config.LOGTAG,
                                                    "could not expose inline voice recording",
                                                    e);
                                            deleteOutputFile();
                                            callback.onError();
                                        }
                                    });
                        },
                        "voice-recorder-finisher")
                .start();
        startedAt = 0L;
    }

    private File generateOutputFilename(final int outputFormat) {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US);
        final String extension;
        if (outputFormat == MediaRecorder.OutputFormat.MPEG_4) {
            extension = "m4a";
        } else if (outputFormat == MediaRecorder.OutputFormat.OGG) {
            extension = "oga";
        } else {
            throw new IllegalStateException("Unrecognized output format");
        }
        final String filename =
                String.format("RECORDING_%s.%s", dateFormat.format(new Date()), extension);
        return VoiceRecordingStaging.createOutputFile(context, filename);
    }

    private void setupFileObserver(final File directory) {
        outputClosedLatch = new CountDownLatch(1);
        final File expectedFile = outputFile;
        fileObserver =
                new FileObserver(directory.getAbsolutePath()) {
                    @Override
                    public void onEvent(final int event, final String path) {
                        if (path != null
                                && expectedFile != null
                                && path.equals(expectedFile.getName())
                                && (event & FileObserver.CLOSE_WRITE) != 0) {
                            outputClosedLatch.countDown();
                        }
                    }
                };
        fileObserver.startWatching();
    }

    private void cleanupObserver() {
        final FileObserver observer = fileObserver;
        fileObserver = null;
        if (observer != null) {
            observer.stopWatching();
        }
    }

    /** Retires the recorder-only staging file. A missing file is already retired. */
    public boolean deleteOutputFile() {
        final File file = outputFile;
        if (file == null) {
            return true;
        }
        final boolean deleted = VoiceRecordingStaging.retire(file);
        if (deleted) {
            outputFile = null;
            Log.d(Config.LOGTAG, "deleted inline voice recording source");
        } else {
            Log.w(Config.LOGTAG, "unable to delete inline voice recording source");
        }
        return deleted;
    }
}
