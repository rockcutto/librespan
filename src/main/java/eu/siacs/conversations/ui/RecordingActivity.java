package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityRecordingBinding;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.util.VoiceRecordingStaging;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;

public class RecordingActivity extends BaseActivity implements View.OnClickListener {

    private ActivityRecordingBinding binding;

    private MediaRecorder mRecorder;
    private Stopwatch stopwatch;

    private final CountDownLatch outputFileWrittenLatch = new CountDownLatch(1);

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    tick();
                    mHandler.postDelayed(mTickExecutor, 100);
                }
            };

    private File mOutputFile;
    private String mOutputMimeType;

    private FileObserver mFileObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Integer override = ThemeHelper.findThemeOverrideStyle(this);
        if (override != null) {
            getTheme().applyStyle(override, true);
        }
        ThemeHelper.applyMaterialColors(this);
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_recording);
        this.binding.pauseButton.setOnClickListener(v -> onPauseContinue());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            this.binding.pauseButton.setVisibility(View.GONE);
        }
        this.binding.cancelButton.setOnClickListener(this);
        this.binding.shareButton.setOnClickListener(this);
        this.setFinishOnTouchOutside(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void onPauseContinue() {
        final var recorder = this.mRecorder;
        final var stopwatch = this.stopwatch;
        if (recorder == null
                || stopwatch == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        if (stopwatch.isRunning()) {
            try {
                recorder.pause();
                stopwatch.stop();
                updateRecordingUi(true);
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "could not pause recording", e);
            }
        } else {
            try {
                recorder.resume();
                stopwatch.start();
                updateRecordingUi(false);
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "could not resume recording", e);
            }
        }
    }

    private void updateRecordingUi(final boolean paused) {
        if (paused) {
            this.binding.pauseButton.setText(R.string.resume_recording);
            this.binding.pauseButton.setIconResource(R.drawable.ic_play_arrow_24dp);
            this.binding.recordingStatus.setText(R.string.recording_paused);
            this.binding.recordingIndicator.setVisibility(View.INVISIBLE);
        } else {
            this.binding.pauseButton.setText(R.string.pause_recording);
            this.binding.pauseButton.setIconResource(R.drawable.ic_pause_24dp);
            this.binding.recordingStatus.setText(R.string.recording_in_progress);
            this.binding.recordingIndicator.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!startRecording()) {
            this.binding.shareButton.setEnabled(false);
            this.binding.pauseButton.setEnabled(false);
            this.binding.recordingIndicator.setVisibility(View.GONE);
            this.binding.timer.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            this.binding.timer.setText(R.string.unable_to_start_recording);
            this.binding.recordingStatus.setText(R.string.recording_error);
        } else {
            updateRecordingUi(false);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mRecorder != null) {
            mHandler.removeCallbacks(mTickExecutor);
            stopRecording(false);
        }
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
        }
    }

    private static final Set<String> AAC_SENSITIVE_DEVICES =
            new ImmutableSet.Builder<String>()
                    .add("FP4") // Fairphone 4
                    // https://codeberg.org/monocles/monocles_chat/issues/133
                    .add("ONEPLUS A6000") // OnePlus 6
                    // https://github.com/iNPUTmice/Conversations/issues/4329
                    .add("ONEPLUS A6003") // OnePlus 6
                    // https://github.com/iNPUTmice/Conversations/issues/4329
                    .add("ONEPLUS A6010") // OnePlus 6T
                    // https://codeberg.org/monocles/monocles_chat/issues/133
                    .add("ONEPLUS A6013") // OnePlus 6T
                    // https://codeberg.org/monocles/monocles_chat/issues/133
                    .add("Pixel 4a") // Pixel 4a
                    // https://github.com/iNPUTmice/Conversations/issues/4223
                    .add("WP12 Pro") // Oukitel WP 12 Pro
                    // https://github.com/iNPUTmice/Conversations/issues/4223
                    .add("Volla Phone X") // Volla Phone X
                    // https://github.com/iNPUTmice/Conversations/issues/4223
                    .build();

    private boolean startRecording() {
        mRecorder = new MediaRecorder();
        stopwatch = Stopwatch.createUnstarted();
        try {
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "could not set audio source", e);
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mRecorder.setPrivacySensitive(true);
        }
        final int outputFormat;
        if (Config.USE_OPUS_VOICE_MESSAGES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputFormat = MediaRecorder.OutputFormat.OGG;
            mRecorder.setOutputFormat(outputFormat);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            mRecorder.setAudioEncodingBitRate(32_000);
        } else {
            outputFormat = MediaRecorder.OutputFormat.MPEG_4;
            mRecorder.setOutputFormat(outputFormat);
            if (AAC_SENSITIVE_DEVICES.contains(Build.MODEL)
                    && Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
                // Changing these three settings for AAC sensitive devices for Android<=13 might
                // lead to sporadically truncated (cut-off) voice messages.
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
                mRecorder.setAudioSamplingRate(24_000);
                mRecorder.setAudioEncodingBitRate(28_000);
            } else {
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mRecorder.setAudioSamplingRate(44_100);
                mRecorder.setAudioEncodingBitRate(64_000);
            }
        }
        setupOutputFile(outputFormat);
        mRecorder.setOutputFile(mOutputFile.getAbsolutePath());

        try {
            mRecorder.prepare();
            mRecorder.start();
            stopwatch.start();
            mHandler.postDelayed(mTickExecutor, 100);
            Log.d(Config.LOGTAG, "started recording to " + mOutputFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "prepare() failed ", e);
            if (mOutputFile != null) {
                VoiceRecordingStaging.retire(mOutputFile);
            }
            try {
                mRecorder.release();
            } catch (final Exception ignored) {
            }
            mRecorder = null;
            return false;
        }
    }

    protected void stopRecording(final boolean saveFile) {
        boolean finalized = false;
        try {
            mRecorder.stop();
            mRecorder.release();
            if (stopwatch.isRunning()) {
                stopwatch.stop();
            }
            finalized = true;
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not save recording", e);
            if (saveFile) {
                Toast.makeText(this, R.string.unable_to_save_recording, Toast.LENGTH_SHORT).show();
            }
        } finally {
            mRecorder = null;
        }
        if (!finalized || !saveFile) {
            if (mOutputFile != null && VoiceRecordingStaging.retire(mOutputFile)) {
                Log.d(Config.LOGTAG, "retired canceled or failed recording staging");
            }
            return;
        }
        new Thread(new Finisher(outputFileWrittenLatch, mOutputFile, mOutputMimeType, this)).start();
    }

    private static class Finisher implements Runnable {

        private final CountDownLatch latch;
        private final File outputFile;
        private final String mimeType;
        private final WeakReference<Activity> activityReference;

        private Finisher(
                CountDownLatch latch, File outputFile, String mimeType, Activity activity) {
            this.latch = latch;
            this.outputFile = outputFile;
            this.mimeType = mimeType;
            this.activityReference = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            try {
                if (!latch.await(8, TimeUnit.SECONDS)) {
                    Log.d(Config.LOGTAG, "time out waiting for output file to be written");
                }
            } catch (final InterruptedException e) {
                Log.d(Config.LOGTAG, "interrupted while waiting for output file to be written", e);
            }
            final Activity activity = activityReference.get();
            if (activity == null) {
                VoiceRecordingStaging.retire(outputFile);
                return;
            }
            activity.runOnUiThread(
                    () -> {
                        if (!outputFile.isFile() || outputFile.length() <= 0) {
                            VoiceRecordingStaging.retire(outputFile);
                            Toast.makeText(activity, R.string.unable_to_save_recording, Toast.LENGTH_SHORT)
                                    .show();
                            activity.setResult(Activity.RESULT_CANCELED);
                            activity.finish();
                            return;
                        }
                        try {
                            activity.setResult(
                                    Activity.RESULT_OK,
                                    new Intent()
                                            .setData(FileBackend.getUriForFile(activity, outputFile))
                                            .setType(mimeType));
                        } catch (final RuntimeException e) {
                            Log.e(Config.LOGTAG, "could not expose voice recording staging", e);
                            VoiceRecordingStaging.retire(outputFile);
                            activity.setResult(Activity.RESULT_CANCELED);
                        }
                        activity.finish();
                    });
        }
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
        return VoiceRecordingStaging.createOutputFile(this, filename);
    }

    private void setupOutputFile(final int outputFormat) {
        mOutputFile = generateOutputFilename(outputFormat);
        mOutputMimeType =
                outputFormat == MediaRecorder.OutputFormat.OGG ? "audio/ogg" : "audio/mp4";
        final File parentDirectory = mOutputFile.getParentFile();
        if (Objects.requireNonNull(parentDirectory).mkdirs()) {
            Log.d(Config.LOGTAG, "created " + parentDirectory.getAbsolutePath());
        }
        setupFileObserver(parentDirectory);
    }

    private void setupFileObserver(final File directory) {
        mFileObserver =
                new FileObserver(directory.getAbsolutePath()) {
                    @Override
                    public void onEvent(int event, String s) {
                        if (s != null
                                && s.equals(mOutputFile.getName())
                                && event == FileObserver.CLOSE_WRITE) {
                            outputFileWrittenLatch.countDown();
                        }
                    }
                };
        mFileObserver.startWatching();
    }

    private void tick() {
        this.binding.timer.setText(
                TimeFrameUtils.formatElapsedTime(stopwatch.elapsed(TimeUnit.MILLISECONDS), true));
    }

    @Override
    public void onClick(final View view) {
        if (view.getId() == R.id.cancel_button) {
            mHandler.removeCallbacks(mTickExecutor);
            stopRecording(false);
            setResult(RESULT_CANCELED);
            finish();
        } else if (view.getId() == R.id.share_button) {
            this.binding.pauseButton.setEnabled(false);
            this.binding.shareButton.setEnabled(false);
            this.binding.shareButton.setText(R.string.please_wait);
            this.binding.recordingStatus.setText(R.string.please_wait);
            this.binding.recordingIndicator.setVisibility(View.VISIBLE);
            mHandler.removeCallbacks(mTickExecutor);
            mHandler.postDelayed(() -> stopRecording(true), 500);
        }
    }
}
