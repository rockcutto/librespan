package eu.siacs.conversations.medialib.activities;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import com.otaliastudios.transcoder.source.ClipDataSource;
import com.otaliastudios.transcoder.source.DataSource;
import com.otaliastudios.transcoder.source.UriDataSource;
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityVideoEditBinding;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AttachFileToConversationRunnable;
import eu.siacs.conversations.ui.BaseActivity;
import eu.siacs.conversations.ui.util.SettingsUtils;
import eu.siacs.conversations.ui.util.VideoAttachmentStaging;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.TranscoderStrategies;

public class VideoEditActivity extends BaseActivity implements TranscoderListener {

    public static final String KEY_CHAT_NAME = "videoEditActivity_chatName";
    public static final String KEY_EDITED_URI = "videoEditActivity_edited_uri";
    public static final String KEY_SEND_NOW = "videoEditActivity_send_now";

    private static final String QUALITY_360 = "360";
    private static final String QUALITY_720 = "720";
    private static final String QUALITY_ORIGINAL = "uncompressed";
    private static final long MIN_TRIM_MS = 500L;
    private static final long INITIAL_LOADING_SPINNER_DELAY_MS = 180L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ActivityVideoEditBinding binding;
    @Nullable private Uri inputUri;
    @Nullable private File outputFile;
    @Nullable private Future<Void> transcodeFuture;
    private long durationMs;
    private long trimStartMs;
    private long trimEndMs;
    private String quality = QUALITY_720;
    private boolean videoPrepared;
    private boolean resultDelivered;
    private boolean preparingOutput;
    private int trimThumbnailGeneration;
    private final List<Bitmap> trimThumbnailBitmaps = new ArrayList<>();

    private final Runnable showInitialLoadingSpinner =
            () -> {
                if (binding != null
                        && binding.videoEditorLoadingOverlay.getVisibility() == View.VISIBLE) {
                    binding.videoEditorLoadingSpinner.setVisibility(View.VISIBLE);
                }
            };

    private final Runnable previewBoundaryCheck =
            new Runnable() {
                @Override
                public void run() {
                    if (binding == null || !binding.videoEditorPreview.isPlaying()) {
                        return;
                    }
                    final int position = binding.videoEditorPreview.getCurrentPosition();
                    updatePreviewSeek(position);
                    if (position >= trimEndMs) {
                        binding.videoEditorPreview.pause();
                        binding.videoEditorPreview.seekTo((int) trimStartMs);
                        updatePreviewSeek(trimStartMs);
                        updatePreviewPlayButton();
                        return;
                    }
                    handler.postDelayed(this, 80L);
                }
            };

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        setTheme(ThemeHelper.find(this));
        final Integer override = ThemeHelper.findThemeOverrideStyle(this);
        if (override != null) {
            getTheme().applyStyle(override, true);
        }
        super.onCreate(savedInstanceState);
        SettingsUtils.applyScreenshotSetting(this);

        binding = ActivityVideoEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.videoEditorLoadingOverlay.setAlpha(1f);
        binding.videoEditorLoadingOverlay.setVisibility(View.VISIBLE);
        binding.videoEditorLoadingSpinner.setVisibility(View.INVISIBLE);
        handler.postDelayed(showInitialLoadingSpinner, INITIAL_LOADING_SPINNER_DELAY_MS);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        inputUri = getIntent() == null ? null : getIntent().getData();
        if (inputUri == null) {
            Toast.makeText(this, R.string.video_editor_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        quality = normalizeQuality(AttachFileToConversationRunnable.getVideoCompression(this));
        setupToolbar();
        setupControls();
        setupPreview();
        refreshQualityButton();
        setPreparing(false);
    }

    private void setupToolbar() {
        final int onSurface =
                MaterialColors.getColor(
                        binding.videoEditorToolbar,
                        com.google.android.material.R.attr.colorOnSurface);
        binding.videoEditorToolbar.setTitle(R.string.video_editor_title);
        binding.videoEditorToolbar.setTitleTextColor(onSurface);
        binding.videoEditorToolbar.setNavigationIconTint(onSurface);
        binding.videoEditorToolbar.setNavigationOnClickListener(view -> cancelAndFinish());
    }

    private void setupControls() {
        binding.videoEditorPlay.setOnClickListener(view -> togglePreviewPlayback());
        binding.videoEditorPlayTarget.setOnClickListener(view -> togglePreviewPlayback());
        binding.videoEditorPreview.setOnClickListener(view -> togglePreviewPlayback());
        binding.videoEditorQuality.setOnClickListener(view -> showQualityDialog());
        binding.videoEditorSend.setOnClickListener(view -> prepareAndSend());

        binding.videoPreviewSeek.setMax(1000);
        binding.videoPreviewSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar,
                            final int progress,
                            final boolean fromUser) {
                        if (!fromUser || !videoPrepared || durationMs <= 0 || preparingOutput) {
                            return;
                        }
                        binding.videoEditorPreview.pause();
                        handler.removeCallbacks(previewBoundaryCheck);
                        final long target =
                                Math.max(
                                        0L,
                                        Math.min(
                                                durationMs,
                                                (durationMs * (long) progress) / 1000L));
                        binding.videoEditorPreview.seekTo((int) target);
                        updatePreviewPlayButton();
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar seekBar) {
                        if (videoPrepared && !preparingOutput) {
                            binding.videoEditorPreview.pause();
                            handler.removeCallbacks(previewBoundaryCheck);
                            updatePreviewPlayButton();
                        }
                    }

                    @Override
                    public void onStopTrackingTouch(final SeekBar seekBar) {}
                });

        binding.videoTrimRange.addOnChangeListener(
                (slider, value, fromUser) -> {
                    if (!fromUser || durationMs <= 0) {
                        return;
                    }
                    final List<Float> values = slider.getValues();
                    if (values.size() < 2) {
                        return;
                    }
                    trimStartMs = Math.max(0L, Math.round(values.get(0) * 1000f));
                    trimEndMs =
                            Math.min(durationMs, Math.round(values.get(1) * 1000f));
                    updateTrimLabels();
                    updateTrimSelectionOverlay();
                    updateSendEnabled();
                    binding.videoEditorPreview.pause();
                    final long seekTarget =
                            Math.abs(Math.round(value * 1000f) - trimStartMs)
                                            <= Math.abs(Math.round(value * 1000f) - trimEndMs)
                                    ? trimStartMs
                                    : trimEndMs;
                    binding.videoEditorPreview.seekTo((int) seekTarget);
                    updatePreviewSeek(seekTarget);
                    updatePreviewPlayButton();
                });
        binding.videoTrimRange.setLabelFormatter(
                value -> formatTime(Math.round(value * 1000f)));
    }

    private void setupPreview() {
        binding.videoEditorPlay.setEnabled(false);
        binding.videoPreviewSeek.setEnabled(false);
        binding.videoEditorPreview.setVideoURI(inputUri);
        binding.videoEditorPreview.setOnPreparedListener(
                player -> {
                    durationMs = Math.max(0L, player.getDuration());
                    if (durationMs <= 0) {
                        showPreparationError();
                        return;
                    }
                    trimStartMs = 0L;
                    trimEndMs = durationMs;
                    final float durationSeconds = Math.max(0.5f, durationMs / 1000f);
                    binding.videoTrimRange.setValueFrom(0f);
                    binding.videoTrimRange.setValueTo(durationSeconds);
                    binding.videoTrimRange.setValues(0f, durationSeconds);
                    binding.videoEditorPlay.setEnabled(true);
                    videoPrepared = true;
                    binding.videoPreviewSeek.setEnabled(true);
                    updateTrimLabels();
                    updateTrimSelectionOverlay();
                    updatePreviewSeek(0L);
                    updateSendEnabled();
                    binding.videoEditorPreview.seekTo(1);
                    binding.videoTrimTimeline.post(this::loadTrimThumbnails);
                    binding.videoEditorStage.post(this::revealPreparedEditor);
                });
        binding.videoEditorPreview.setOnCompletionListener(
                player -> {
                    handler.removeCallbacks(previewBoundaryCheck);
                    binding.videoEditorPreview.seekTo((int) trimStartMs);
                    updatePreviewSeek(trimStartMs);
                    updatePreviewPlayButton();
                });
        binding.videoEditorPreview.setOnErrorListener(
                (player, what, extra) -> {
                    videoPrepared = false;
                    binding.videoEditorPlay.setEnabled(false);
                    binding.videoPreviewSeek.setEnabled(false);
                    revealPreparedEditor();
                    return true;
                });
    }

    private void revealPreparedEditor() {
        if (binding == null) {
            return;
        }
        handler.removeCallbacks(showInitialLoadingSpinner);
        binding.videoEditorLoadingSpinner.setVisibility(View.INVISIBLE);
        binding.videoEditorLoadingOverlay.animate().cancel();
        binding.videoEditorLoadingOverlay
                .animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction(
                        () -> {
                            if (binding != null) {
                                binding.videoEditorLoadingOverlay.setVisibility(View.GONE);
                                binding.videoEditorLoadingOverlay.setAlpha(1f);
                            }
                        })
                .start();
    }

    private void togglePreviewPlayback() {
        if (!videoPrepared || preparingOutput) {
            return;
        }
        if (binding.videoEditorPreview.isPlaying()) {
            binding.videoEditorPreview.pause();
            handler.removeCallbacks(previewBoundaryCheck);
        } else {
            final int position = binding.videoEditorPreview.getCurrentPosition();
            if (position < trimStartMs || position >= trimEndMs) {
                binding.videoEditorPreview.seekTo((int) trimStartMs);
                updatePreviewSeek(trimStartMs);
            }
            binding.videoEditorPreview.start();
            handler.removeCallbacks(previewBoundaryCheck);
            handler.post(previewBoundaryCheck);
        }
        updatePreviewPlayButton();
    }

    private void updatePreviewSeek(final long positionMs) {
        if (binding == null || durationMs <= 0) {
            return;
        }
        final int progress =
                (int)
                        Math.max(
                                0L,
                                Math.min(
                                        1000L,
                                        (Math.max(0L, Math.min(durationMs, positionMs)) * 1000L)
                                                / durationMs));
        binding.videoPreviewSeek.setProgress(progress);
    }

    private void updatePreviewPlayButton() {
        if (binding.videoEditorPreview.isPlaying()) {
            binding.videoEditorPlay.setIconResource(R.drawable.ic_media_pause_48dp);
            binding.videoEditorPlay.setContentDescription(getString(R.string.pause_video));
        } else {
            binding.videoEditorPlay.setIconResource(R.drawable.ic_media_play_48dp);
            binding.videoEditorPlay.setContentDescription(getString(R.string.play_video));
        }
    }

    private void updateTrimLabels() {
        binding.videoTrimStartValue.setText(formatTime(trimStartMs));
        binding.videoTrimEndValue.setText(formatTime(trimEndMs));
    }

    private void updateTrimSelectionOverlay() {
        if (durationMs <= 0 || binding == null) {
            return;
        }
        binding.videoTrimTimeline.post(
                () -> {
                    if (binding == null || durationMs <= 0) {
                        return;
                    }
                    final int width = binding.videoTrimTimeline.getWidth();
                    if (width <= 0) {
                        return;
                    }
                    final int leftWidth =
                            (int)
                                    Math.round(
                                            width
                                                    * Math.max(
                                                            0d,
                                                            Math.min(
                                                                    1d,
                                                                    trimStartMs
                                                                            / (double)
                                                                                    durationMs)));
                    final int rightWidth =
                            (int)
                                    Math.round(
                                            width
                                                    * Math.max(
                                                            0d,
                                                            Math.min(
                                                                    1d,
                                                                    1d
                                                                            - trimEndMs
                                                                                    / (double)
                                                                                            durationMs)));
                    final android.widget.FrameLayout.LayoutParams leftParams =
                            (android.widget.FrameLayout.LayoutParams)
                                    binding.videoTrimLeftScrim.getLayoutParams();
                    leftParams.width = leftWidth;
                    leftParams.gravity = android.view.Gravity.START;
                    binding.videoTrimLeftScrim.setLayoutParams(leftParams);

                    final android.widget.FrameLayout.LayoutParams rightParams =
                            (android.widget.FrameLayout.LayoutParams)
                                    binding.videoTrimRightScrim.getLayoutParams();
                    rightParams.width = rightWidth;
                    rightParams.gravity = android.view.Gravity.END;
                    binding.videoTrimRightScrim.setLayoutParams(rightParams);
                });
    }

    private void loadTrimThumbnails() {
        final Uri source = inputUri;
        if (source == null || durationMs <= 0 || binding == null) {
            return;
        }
        final int timelineWidth = binding.videoTrimTimeline.getWidth();
        if (timelineWidth <= 0) {
            binding.videoTrimTimeline.post(this::loadTrimThumbnails);
            return;
        }

        final int generation = ++trimThumbnailGeneration;
        final int tileHeight = Math.max(1, binding.videoTrimTimeline.getHeight());
        final int preferredTileWidth =
                Math.max(1, Math.round(48f * getResources().getDisplayMetrics().density));
        final int frameCount =
                Math.max(6, Math.min(10, Math.max(1, timelineWidth / preferredTileWidth)));
        final int tileWidth = Math.max(1, (int) Math.ceil(timelineWidth / (double) frameCount));
        final long sourceDurationMs = durationMs;

        ioExecutor.execute(
                () -> {
                    final List<Bitmap> frames = new ArrayList<>();
                    final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try (AssetFileDescriptor descriptor =
                            getContentResolver().openAssetFileDescriptor(source, "r")) {
                        if (descriptor == null) {
                            throw new IllegalStateException("Video source is unavailable");
                        }
                        final long declaredLength = descriptor.getDeclaredLength();
                        if (declaredLength < 0) {
                            retriever.setDataSource(descriptor.getFileDescriptor());
                        } else {
                            retriever.setDataSource(
                                    descriptor.getFileDescriptor(),
                                    descriptor.getStartOffset(),
                                    declaredLength);
                        }

                        for (int index = 0; index < frameCount; index++) {
                            final long timeUs =
                                    Math.max(
                                            0L,
                                            Math.min(
                                                    sourceDurationMs * 1000L,
                                                    Math.round(
                                                            ((index + 0.5d) / frameCount)
                                                                    * sourceDurationMs
                                                                    * 1000d)));
                            Bitmap frame;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                frame =
                                        retriever.getScaledFrameAtTime(
                                                timeUs,
                                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                                tileWidth,
                                                tileHeight);
                            } else {
                                final Bitmap raw =
                                        retriever.getFrameAtTime(
                                                timeUs,
                                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                                frame = centerCropThumbnail(raw, tileWidth, tileHeight);
                                if (raw != null && raw != frame && !raw.isRecycled()) {
                                    raw.recycle();
                                }
                            }
                            if (frame != null) {
                                frames.add(frame);
                            }
                        }
                    } catch (final Exception ignored) {
                    } finally {
                        try {
                            retriever.release();
                        } catch (final IOException | RuntimeException ignored) {
                        }
                    }

                    runOnUiThread(
                            () -> {
                                if (binding == null
                                        || isFinishing()
                                        || isDestroyed()
                                        || generation != trimThumbnailGeneration) {
                                    recycleBitmaps(frames);
                                    return;
                                }
                                renderTrimThumbnails(frames);
                            });
                });
    }

    @Nullable
    private static Bitmap centerCropThumbnail(
            @Nullable final Bitmap source, final int targetWidth, final int targetHeight) {
        if (source == null || targetWidth <= 0 || targetHeight <= 0) {
            return source;
        }
        final float scale =
                Math.max(
                        targetWidth / (float) source.getWidth(),
                        targetHeight / (float) source.getHeight());
        final int scaledWidth = Math.max(1, Math.round(source.getWidth() * scale));
        final int scaledHeight = Math.max(1, Math.round(source.getHeight() * scale));
        final Bitmap scaled =
                Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
        final int left = Math.max(0, (scaledWidth - targetWidth) / 2);
        final int top = Math.max(0, (scaledHeight - targetHeight) / 2);
        final Bitmap cropped =
                Bitmap.createBitmap(
                        scaled,
                        left,
                        top,
                        Math.min(targetWidth, scaled.getWidth() - left),
                        Math.min(targetHeight, scaled.getHeight() - top));
        if (scaled != source && scaled != cropped && !scaled.isRecycled()) {
            scaled.recycle();
        }
        return cropped;
    }

    private void renderTrimThumbnails(@NonNull final List<Bitmap> frames) {
        clearTrimThumbnails();
        if (frames.isEmpty()) {
            return;
        }
        trimThumbnailBitmaps.addAll(frames);
        binding.videoTrimThumbnails.removeAllViews();
        for (final Bitmap frame : frames) {
            final ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(frame);
            binding.videoTrimThumbnails.addView(
                    image,
                    new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f));
        }
        updateTrimSelectionOverlay();
    }

    private void clearTrimThumbnails() {
        if (binding != null) {
            binding.videoTrimThumbnails.removeAllViews();
        }
        recycleBitmaps(trimThumbnailBitmaps);
        trimThumbnailBitmaps.clear();
    }

    private static void recycleBitmaps(@NonNull final List<Bitmap> bitmaps) {
        for (final Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private String formatTime(final long milliseconds) {
        return DateUtils.formatElapsedTime(Math.max(0L, milliseconds) / 1000L);
    }

    private void showQualityDialog() {
        if (preparingOutput) {
            return;
        }
        final String[] values = {QUALITY_360, QUALITY_720, QUALITY_ORIGINAL};
        final CharSequence[] labels = {
            getString(R.string.video_360p),
            getString(R.string.video_720p),
            getString(R.string.video_original)
        };
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(quality)) {
                checked = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pref_video_compression)
                .setSingleChoiceItems(
                        labels,
                        checked,
                        (dialog, which) -> {
                            quality = values[which];
                            refreshQualityButton();
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void refreshQualityButton() {
        final int label;
        switch (quality) {
            case QUALITY_360:
                label = R.string.video_quality_360_short;
                break;
            case QUALITY_ORIGINAL:
                label = R.string.video_quality_original_short;
                break;
            case QUALITY_720:
            default:
                label = R.string.video_quality_720_short;
                break;
        }
        binding.videoEditorQuality.setText(getString(label));
    }

    private String normalizeQuality(@Nullable final String value) {
        if (QUALITY_360.equals(value)
                || QUALITY_720.equals(value)
                || QUALITY_ORIGINAL.equals(value)) {
            return value;
        }
        return QUALITY_720;
    }

    private void updateSendEnabled() {
        binding.videoEditorSend.setEnabled(
                videoPrepared
                        && !preparingOutput
                        && trimEndMs - trimStartMs >= MIN_TRIM_MS);
    }

    private void prepareAndSend() {
        if (!videoPrepared || preparingOutput) {
            return;
        }
        if (trimEndMs - trimStartMs < MIN_TRIM_MS) {
            Toast.makeText(this, R.string.video_editor_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri source = inputUri;
        if (source == null) {
            showPreparationError();
            return;
        }

        binding.videoEditorPreview.pause();
        handler.removeCallbacks(previewBoundaryCheck);
        updatePreviewPlayButton();
        setPreparing(true);

        outputFile = VideoAttachmentStaging.createEditorOutputFile(this);
        final boolean fullRange =
                trimStartMs <= 50L && Math.abs(durationMs - trimEndMs) <= 50L;
        final String sourceMime = MimeUtils.guessMimeTypeFromUri(this, source);
        if (QUALITY_ORIGINAL.equals(quality)
                && fullRange
                && "video/mp4".equals(sourceMime)) {
            copyOriginalToStaging(source);
            return;
        }
        transcodeToStaging(source);
    }

    private void copyOriginalToStaging(@NonNull final Uri source) {
        final File target = outputFile;
        if (target == null) {
            showPreparationError();
            return;
        }
        ioExecutor.execute(
                () -> {
                    try {
                        final File parent = target.getParentFile();
                        if (parent != null) {
                            parent.mkdirs();
                        }
                        try (InputStream input = getContentResolver().openInputStream(source);
                                FileOutputStream output = new FileOutputStream(target)) {
                            if (input == null) {
                                throw new IllegalStateException("Video source is unavailable");
                            }
                            final byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = input.read(buffer)) >= 0) {
                                if (read > 0) {
                                    output.write(buffer, 0, read);
                                }
                            }
                            output.getFD().sync();
                        }
                        runOnUiThread(() -> finishWithOutput(target));
                    } catch (final Exception error) {
                        VideoAttachmentStaging.retire(target);
                        runOnUiThread(this::showPreparationError);
                    }
                });
    }

    private void transcodeToStaging(@NonNull final Uri sourceUri) {
        final File target = outputFile;
        if (target == null) {
            showPreparationError();
            return;
        }
        final File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        final DataSource rawSource = new UriDataSource(this, sourceUri);
        final boolean trimChanged =
                trimStartMs > 50L || Math.abs(durationMs - trimEndMs) > 50L;
        final DataSource source =
                trimChanged
                        ? new ClipDataSource(
                                rawSource,
                                trimStartMs * 1000L,
                                trimEndMs * 1000L)
                        : rawSource;

        try {
            if (QUALITY_360.equals(quality)) {
                transcodeFuture =
                        Transcoder.into(target.getAbsolutePath())
                                .addDataSource(source)
                                .setVideoTrackStrategy(TranscoderStrategies.VIDEO_360P)
                                .setAudioTrackStrategy(TranscoderStrategies.AUDIO_MQ)
                                .setListener(this)
                                .transcode();
            } else if (QUALITY_720.equals(quality)) {
                transcodeFuture =
                        Transcoder.into(target.getAbsolutePath())
                                .addDataSource(source)
                                .setVideoTrackStrategy(TranscoderStrategies.VIDEO_720P)
                                .setAudioTrackStrategy(TranscoderStrategies.AUDIO_HQ)
                                .setListener(this)
                                .transcode();
            } else {
                transcodeFuture =
                        Transcoder.into(target.getAbsolutePath())
                                .addDataSource(source)
                                .setVideoTrackStrategy(
                                        DefaultVideoStrategy.fraction(1F).build())
                                .setAudioTrackStrategy(
                                        DefaultAudioStrategy.builder().build())
                                .setListener(this)
                                .transcode();
            }
        } catch (final RuntimeException error) {
            VideoAttachmentStaging.retire(target);
            showPreparationError();
        }
    }

    @Override
    public void onTranscodeProgress(final double progress) {
        runOnUiThread(
                () -> {
                    if (!preparingOutput || binding == null) {
                        return;
                    }
                    binding.videoEditorProgress.setIndeterminate(false);
                    binding.videoEditorProgress.setProgressCompat(
                            (int) Math.max(0, Math.min(100, Math.round(progress * 100d))),
                            true);
                });
    }

    @Override
    public void onTranscodeCompleted(final int successCode) {
        final File target = outputFile;
        runOnUiThread(
                () -> {
                    transcodeFuture = null;
                    if (target == null || !target.isFile() || target.length() <= 0) {
                        showPreparationError();
                        return;
                    }
                    finishWithOutput(target);
                });
    }

    @Override
    public void onTranscodeCanceled() {
        transcodeFuture = null;
    }

    @Override
    public void onTranscodeFailed(@NonNull final Throwable exception) {
        final File target = outputFile;
        if (target != null) {
            VideoAttachmentStaging.retire(target);
        }
        runOnUiThread(
                () -> {
                    transcodeFuture = null;
                    showPreparationError();
                });
    }

    private void finishWithOutput(@NonNull final File file) {
        if (isFinishing() || isDestroyed()) {
            VideoAttachmentStaging.retire(file);
            return;
        }
        final Uri uri = FileBackend.getUriForFile(this, file);
        final Intent result = new Intent();
        result.putExtra(KEY_EDITED_URI, uri);
        result.putExtra(KEY_SEND_NOW, false);
        result.setData(uri);
        result.setType("video/mp4");
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        resultDelivered = true;
        setResult(Activity.RESULT_OK, result);
        finish();
        overridePendingTransition(0, 0);
    }

    private void setPreparing(final boolean preparing) {
        preparingOutput = preparing;
        binding.videoEditorProgress.setVisibility(preparing ? View.VISIBLE : View.GONE);
        binding.videoEditorSend.setText(R.string.done);
        binding.videoEditorQuality.setEnabled(!preparing);
        binding.videoTrimRange.setEnabled(!preparing);
        final boolean playbackEnabled = !preparing && videoPrepared;
        binding.videoEditorPlay.setEnabled(playbackEnabled);
        binding.videoEditorPlayTarget.setEnabled(playbackEnabled);
        binding.videoPreviewSeek.setEnabled(playbackEnabled);
        updateSendEnabled();
    }

    private void showPreparationError() {
        setPreparing(false);
        final File target = outputFile;
        outputFile = null;
        if (target != null) {
            VideoAttachmentStaging.retire(target);
        }
        Toast.makeText(this, R.string.video_editor_failed, Toast.LENGTH_SHORT).show();
    }

    private void cancelAndFinish() {
        final Future<Void> future = transcodeFuture;
        transcodeFuture = null;
        if (future != null) {
            future.cancel(true);
        }
        final File target = outputFile;
        outputFile = null;
        if (target != null) {
            VideoAttachmentStaging.retire(target);
        }
        resultDelivered = false;
        setResult(Activity.RESULT_CANCELED);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        cancelAndFinish();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(previewBoundaryCheck);
        if (binding != null) {
            binding.videoEditorPreview.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(previewBoundaryCheck);
        handler.removeCallbacks(showInitialLoadingSpinner);
        trimThumbnailGeneration++;
        clearTrimThumbnails();
        if (binding != null) {
            try {
                binding.videoEditorPreview.stopPlayback();
            } catch (final RuntimeException ignored) {
            }
        }
        ioExecutor.shutdownNow();
        if (!resultDelivered && transcodeFuture == null) {
            final File target = outputFile;
            if (target != null) {
                VideoAttachmentStaging.retire(target);
            }
        }
        super.onDestroy();
    }
}
