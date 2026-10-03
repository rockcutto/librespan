package eu.siacs.conversations.ui.attachments;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.MediaPlayer;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaReadCache;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.TimeFrameUtils;

/** One-player controller for the Audio tab of the attachment browser. */
public final class AttachmentAudioPlayer {

    private static final int REFRESH_INTERVAL_MS = 250;

    private static final int MAX_WAVEFORM_CACHE_ENTRIES = 96;

    private final XmppActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService waveformExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, float[]> waveformCache =
            new LinkedHashMap<String, float[]>(MAX_WAVEFORM_CACHE_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, float[]> eldest) {
                    return size() > MAX_WAVEFORM_CACHE_ENTRIES;
                }
            };
    private final Set<String> waveformInFlight = new HashSet<>();

    @Nullable private MediaPlayer player;
    @Nullable private AndroidSecureMessageMediaReadCache.Lease secureLease;
    @Nullable private String currentMessageUuid;
    @Nullable private String preparingMessageUuid;
    @Nullable private WeakReference<Controls> currentControls;
    private int generation;

    private final Runnable progressUpdater =
            new Runnable() {
                @Override
                public void run() {
                    final MediaPlayer current = player;
                    if (current == null || currentMessageUuid == null) {
                        return;
                    }
                    refreshCurrentControls();
                    if (current.isPlaying()) {
                        handler.postDelayed(this, REFRESH_INTERVAL_MS);
                    }
                }
            };

    public AttachmentAudioPlayer(final XmppActivity activity) {
        this.activity = activity;
    }

    public void bind(
            final AttachmentEntry entry,
            final View row,
            final MaterialButton playPause,
            final AttachmentWaveformSeekBar progress,
            final TextView runtime) {
        final Controls controls =
                new Controls(entry.messageUuid, row, playPause, progress, runtime);
        row.setTag(entry.messageUuid);
        progress.setMax(100);

        final View.OnClickListener toggle = ignored -> toggle(entry, controls);
        playPause.setOnClickListener(toggle);
        row.setOnClickListener(toggle);

        progress.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar, final int value, final boolean fromUser) {
                        if (!fromUser
                                || player == null
                                || !entry.messageUuid.equals(currentMessageUuid)) {
                            return;
                        }
                        final int duration = player.getDuration();
                        if (duration > 0) {
                            player.seekTo(Math.round(duration * (value / 100f)));
                            refreshCurrentControls();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(final SeekBar seekBar) {}
                });

        if (entry.messageUuid.equals(currentMessageUuid)
                || entry.messageUuid.equals(preparingMessageUuid)) {
            currentControls = new WeakReference<>(controls);
        }
        bindWaveform(entry, controls);
        render(controls, entry);
    }

    public void stop() {
        generation++;
        preparingMessageUuid = null;
        stopPlayer();
        currentMessageUuid = null;
        renderIdleCurrentControls();
        currentControls = null;
    }

    public void release() {
        stop();
        executor.shutdownNow();
        waveformExecutor.shutdownNow();
        synchronized (waveformCache) {
            waveformCache.clear();
            waveformInFlight.clear();
        }
    }

    private void bindWaveform(
            final AttachmentEntry entry, final Controls controls) {
        final String waveformKey = waveformKey(entry);
        final float[] cached;
        synchronized (waveformCache) {
            cached = waveformCache.get(waveformKey);
        }
        if (cached != null) {
            controls.progress.setAmplitudes(cached);
            return;
        }

        controls.progress.clearAmplitudes();
        synchronized (waveformCache) {
            if (!waveformInFlight.add(waveformKey)) {
                return;
            }
        }

        waveformExecutor.execute(
                () -> {
                    AndroidSecureMessageMediaReadCache.Lease lease = null;
                    try {
                        final float[] amplitudes;
                        if (entry.isSecure()) {
                            final Conversations application =
                                    (Conversations) activity.getApplication();
                            lease =
                                    new AndroidSecureMessageMediaReadCache(
                                                    activity,
                                                    application
                                                            .getSecureContentStoreProvider()
                                                            .get())
                                            .acquire(entry.accountUuid, entry.messageUuid);
                            if (lease == null) {
                                throw new IllegalStateException(
                                        "Secure waveform relation is unavailable");
                            }
                            amplitudes =
                                    AttachmentAudioWaveformExtractor.extract(
                                            activity,
                                            lease.getUri(),
                                            AttachmentAudioWaveformExtractor.DEFAULT_BUCKETS);
                        } else {
                            if (entry.legacyPath == null) {
                                throw new IllegalStateException(
                                        "Plaintext waveform path is unavailable");
                            }
                            final File file =
                                    activity.xmppConnectionService
                                            .getFileBackend()
                                            .getFileForPath(entry.legacyPath);
                            if (activity.xmppConnectionService
                                            .getFileBackend()
                                            .isInsidePlaintextMediaNamespace(file)
                                    && !activity.xmppConnectionService
                                            .getFileBackend()
                                            .isAccountScopedPlaintextMediaFile(
                                                    entry.accountUuid, file)) {
                                throw new SecurityException(
                                        "Cross-account plaintext waveform path");
                            }
                            if (!file.isFile()) {
                                throw new IllegalStateException(
                                        "Plaintext waveform file is unavailable");
                            }
                            amplitudes =
                                    AttachmentAudioWaveformExtractor.extract(
                                            file.getAbsolutePath(),
                                            AttachmentAudioWaveformExtractor.DEFAULT_BUCKETS);
                        }

                        synchronized (waveformCache) {
                            waveformCache.put(waveformKey, amplitudes);
                        }
                        activity.runOnUiThread(
                                () -> {
                                    if (isBound(controls, entry.messageUuid)) {
                                        controls.progress.setAmplitudes(amplitudes);
                                    }
                                });
                    } catch (final Exception ignored) {
                        // Playback remains available even when waveform decoding is unsupported.
                    } finally {
                        closeLease(lease);
                        synchronized (waveformCache) {
                            waveformInFlight.remove(waveformKey);
                        }
                    }
                });
    }

    private void toggle(final AttachmentEntry entry, final Controls controls) {
        if (entry.messageUuid.equals(preparingMessageUuid)) {
            return;
        }
        final MediaPlayer current = player;
        if (entry.messageUuid.equals(currentMessageUuid) && current != null) {
            currentControls = new WeakReference<>(controls);
            if (current.isPlaying()) {
                current.pause();
                handler.removeCallbacks(progressUpdater);
            } else {
                current.start();
                handler.removeCallbacks(progressUpdater);
                handler.post(progressUpdater);
            }
            render(controls, entry);
            return;
        }
        prepare(entry, controls);
    }

    private void prepare(final AttachmentEntry entry, final Controls controls) {
        generation++;
        final int requestedGeneration = generation;
        stopPlayer();
        renderIdleCurrentControls();

        currentMessageUuid = null;
        preparingMessageUuid = entry.messageUuid;
        currentControls = new WeakReference<>(controls);
        controls.playPause.setEnabled(false);
        controls.progress.setEnabled(false);

        executor.execute(
                () -> {
                    AndroidSecureMessageMediaReadCache.Lease lease = null;
                    String legacyPath = null;
                    try {
                        if (entry.isSecure()) {
                            final Conversations application =
                                    (Conversations) activity.getApplication();
                            lease =
                                    new AndroidSecureMessageMediaReadCache(
                                                    activity,
                                                    application
                                                            .getSecureContentStoreProvider()
                                                            .get())
                                            .acquire(entry.accountUuid, entry.messageUuid);
                            if (lease == null) {
                                throw new IllegalStateException(
                                        "Secure audio relation is unavailable");
                            }
                        } else {
                            if (entry.legacyPath == null) {
                                throw new IllegalStateException("Plaintext audio path is unavailable");
                            }
                            final File file =
                                    activity.xmppConnectionService
                                            .getFileBackend()
                                            .getFileForPath(entry.legacyPath);
                            if (activity.xmppConnectionService
                                            .getFileBackend()
                                            .isInsidePlaintextMediaNamespace(file)
                                    && !activity.xmppConnectionService
                                            .getFileBackend()
                                            .isAccountScopedPlaintextMediaFile(
                                                    entry.accountUuid, file)) {
                                throw new SecurityException(
                                        "Cross-account plaintext audio path");
                            }
                            if (!file.isFile()) {
                                throw new IllegalStateException("Plaintext audio file is unavailable");
                            }
                            legacyPath = file.getAbsolutePath();
                        }

                        final AndroidSecureMessageMediaReadCache.Lease resolvedLease = lease;
                        final String resolvedLegacyPath = legacyPath;
                        activity.runOnUiThread(
                                () ->
                                        preparePlayer(
                                                entry,
                                                controls,
                                                requestedGeneration,
                                                resolvedLease,
                                                resolvedLegacyPath));
                    } catch (final Exception error) {
                        closeLease(lease);
                        activity.runOnUiThread(
                                () -> failPreparation(entry, controls, requestedGeneration));
                    }
                });
    }

    private void preparePlayer(
            final AttachmentEntry entry,
            final Controls controls,
            final int requestedGeneration,
            @Nullable final AndroidSecureMessageMediaReadCache.Lease lease,
            @Nullable final String legacyPath) {
        if (requestedGeneration != generation
                || !entry.messageUuid.equals(preparingMessageUuid)
                || activity.isFinishing()
                || activity.isDestroyed()) {
            closeLease(lease);
            return;
        }

        final MediaPlayer prepared = new MediaPlayer();
        try {
            prepared.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (lease != null) {
                prepared.setDataSource(activity, lease.getUri());
            } else if (legacyPath != null) {
                prepared.setDataSource(legacyPath);
            } else {
                throw new IllegalStateException("Audio source is unavailable");
            }

            player = prepared;
            secureLease = lease;
            currentMessageUuid = entry.messageUuid;
            prepared.setOnPreparedListener(
                    mediaPlayer -> {
                        if (prepared != player || requestedGeneration != generation) {
                            prepared.release();
                            return;
                        }
                        preparingMessageUuid = null;
                        mediaPlayer.start();
                        if (isBound(controls, entry.messageUuid)) {
                            currentControls = new WeakReference<>(controls);
                            render(controls, entry);
                        } else {
                            currentControls = null;
                        }
                        handler.removeCallbacks(progressUpdater);
                        handler.post(progressUpdater);
                    });
            prepared.setOnCompletionListener(
                    mediaPlayer -> {
                        if (prepared == player) {
                            handler.removeCallbacks(progressUpdater);
                            player = null;
                            currentMessageUuid = null;
                            preparingMessageUuid = null;
                            closeLease(secureLease);
                            secureLease = null;
                            mediaPlayer.release();
                            if (isBound(controls, entry.messageUuid)) {
                                renderIdle(controls, entry);
                            }
                        } else {
                            mediaPlayer.release();
                        }
                    });
            prepared.setOnErrorListener(
                    (mediaPlayer, what, extra) -> {
                        if (prepared == player) {
                            failPreparation(entry, controls, requestedGeneration);
                        } else {
                            mediaPlayer.release();
                        }
                        return true;
                    });
            prepared.prepareAsync();
        } catch (final Exception error) {
            if (player == prepared) {
                player = null;
            }
            prepared.release();
            closeLease(lease);
            secureLease = null;
            failPreparation(entry, controls, requestedGeneration);
        }
    }

    private void failPreparation(
            final AttachmentEntry entry,
            final Controls controls,
            final int requestedGeneration) {
        if (requestedGeneration != generation) {
            return;
        }
        preparingMessageUuid = null;
        currentMessageUuid = null;
        stopPlayer();
        if (isBound(controls, entry.messageUuid)) {
            renderIdle(controls, entry);
        }
        Toast.makeText(
                        activity,
                        R.string.attachments_audio_playback_error,
                        Toast.LENGTH_SHORT)
                .show();
    }

    private void stopPlayer() {
        handler.removeCallbacks(progressUpdater);
        final MediaPlayer current = player;
        player = null;
        if (current != null) {
            try {
                if (current.isPlaying()) {
                    current.stop();
                }
            } catch (final IllegalStateException ignored) {
            }
            current.release();
        }
        closeLease(secureLease);
        secureLease = null;
    }

    private void render(final Controls controls, final AttachmentEntry entry) {
        if (entry.messageUuid.equals(preparingMessageUuid)) {
            controls.playPause.setEnabled(false);
            controls.playPause.setIconResource(R.drawable.ic_media_play_48dp);
            controls.playPause.setContentDescription(
                    activity.getString(R.string.play_audio));
            controls.progress.setEnabled(false);
            controls.progress.setProgress(0);
            controls.runtime.setText(formatDuration(entry.durationMillis));
            return;
        }

        final MediaPlayer current = player;
        if (entry.messageUuid.equals(currentMessageUuid) && current != null) {
            controls.playPause.setEnabled(true);
            controls.playPause.setIconResource(
                    current.isPlaying()
                            ? R.drawable.ic_media_pause_48dp
                            : R.drawable.ic_media_play_48dp);
            controls.playPause.setContentDescription(
                    activity.getString(
                            current.isPlaying() ? R.string.pause_audio : R.string.play_audio));
            controls.progress.setEnabled(true);
            refreshControls(controls, current);
            return;
        }
        renderIdle(controls, entry);
    }

    private void renderIdleCurrentControls() {
        final Controls controls =
                currentControls == null ? null : currentControls.get();
        if (controls == null) {
            return;
        }
        controls.playPause.setEnabled(true);
        controls.playPause.setIconResource(R.drawable.ic_media_play_48dp);
        controls.playPause.setContentDescription(activity.getString(R.string.play_audio));
        controls.progress.setEnabled(false);
        controls.progress.setProgress(0);
    }

    private void renderIdle(final Controls controls, final AttachmentEntry entry) {
        controls.playPause.setEnabled(true);
        controls.playPause.setIconResource(R.drawable.ic_media_play_48dp);
        controls.playPause.setContentDescription(activity.getString(R.string.play_audio));
        controls.progress.setEnabled(false);
        controls.progress.setProgress(0);
        controls.runtime.setText(formatDuration(entry.durationMillis));
    }

    private void refreshCurrentControls() {
        final MediaPlayer current = player;
        final Controls controls =
                currentControls == null ? null : currentControls.get();
        if (current == null
                || controls == null
                || currentMessageUuid == null
                || !currentMessageUuid.equals(controls.messageUuid)
                || !currentMessageUuid.equals(controls.row.getTag())) {
            return;
        }
        refreshControls(controls, current);
    }

    private void refreshControls(final Controls controls, final MediaPlayer current) {
        try {
            final int position = current.getCurrentPosition();
            final int duration = current.getDuration();
            controls.progress.setProgress(
                    duration <= 0 ? 0 : Math.min(100, Math.round(position * 100f / duration)));
            controls.runtime.setText(
                    formatDuration(position) + " / " + formatDuration(duration));
        } catch (final IllegalStateException ignored) {
        }
    }

    private static String waveformKey(final AttachmentEntry entry) {
        return entry.accountUuid + ":" + entry.messageUuid;
    }

    private static boolean isBound(final Controls controls, final String messageUuid) {
        return messageUuid.equals(controls.row.getTag());
    }

    private static String formatDuration(final int millis) {
        return TimeFrameUtils.formatElapsedTime(Math.max(0, millis), false);
    }

    private static void closeLease(
            @Nullable final AndroidSecureMessageMediaReadCache.Lease lease) {
        if (lease != null) {
            try {
                lease.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private static final class Controls {
        private final String messageUuid;
        private final View row;
        private final MaterialButton playPause;
        private final AttachmentWaveformSeekBar progress;
        private final TextView runtime;

        private Controls(
                final String messageUuid,
                final View row,
                final MaterialButton playPause,
                final AttachmentWaveformSeekBar progress,
                final TextView runtime) {
            this.messageUuid = messageUuid;
            this.row = row;
            this.playPause = playPause;
            this.progress = progress;
            this.runtime = runtime;
        }
    }
}
