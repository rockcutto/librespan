package eu.siacs.conversations.ui.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.common.primitives.Ints;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.MediaPlayer;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaReadCache;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.adapter.MessageAdapter;
import eu.siacs.conversations.ui.attachments.AttachmentWaveformSeekBar;
import eu.siacs.conversations.ui.attachments.AttachmentAudioWaveformExtractor;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.WeakReferenceSet;

public class AudioPlayer implements View.OnClickListener, MediaPlayer.OnCompletionListener, SeekBar.OnSeekBarChangeListener, Runnable, SensorEventListener {

    private static final int REFRESH_INTERVAL = 250;
    private static final Object LOCK = new Object();
    private static MediaPlayer player = null;
    private static Message currentlyPlayingMessage = null;
    @Nullable private static AndroidSecureMessageMediaReadCache.Lease currentlyPlayingLease = null;
    private static PowerManager.WakeLock wakeLock;
    private final MessageAdapter messageAdapter;
    private final WeakReferenceSet<RelativeLayout> audioPlayerLayouts = new WeakReferenceSet<>();
    private final SensorManager sensorManager;
    private final Sensor proximitySensor;
    private final PendingItem<WeakReference<View>> pendingOnClickView = new PendingItem<>();

    private static final int MAX_WAVEFORM_CACHE_ENTRIES = 128;
    private static final Map<String, float[]> WAVEFORM_CACHE = new HashMap<>();
    private static final Set<String> WAVEFORM_IN_FLIGHT = new HashSet<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService waveformExecutor = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler();

    public AudioPlayer(MessageAdapter adapter) {
        final Context context = adapter.getContext();
        this.messageAdapter = adapter;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.proximitySensor = this.sensorManager == null ? null : this.sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        initializeProximityWakeLock(context);
        synchronized (AudioPlayer.LOCK) {
            if (AudioPlayer.player != null) {
                AudioPlayer.player.setOnCompletionListener(this);
                if (AudioPlayer.player.isPlaying() && sensorManager != null) {
                    sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        }
    }

    private static String formatTime(int ms) {
        return TimeFrameUtils.formatElapsedTime(ms,false);
    }

    private void initializeProximityWakeLock(Context context) {
        if (Build.VERSION.SDK_INT >= 21) {
            synchronized (AudioPlayer.LOCK) {
                if (AudioPlayer.wakeLock == null) {
                    final PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    AudioPlayer.wakeLock = powerManager == null ? null : powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, AudioPlayer.class.getSimpleName());
                    AudioPlayer.wakeLock.setReferenceCounted(false);
                }
            }
        } else {
            AudioPlayer.wakeLock = null;
        }
    }

    public void init(RelativeLayout audioPlayer, Message message) {
        synchronized (AudioPlayer.LOCK) {
            audioPlayer.setTag(message);
            bindWaveform(audioPlayer, message);
            if (init(ViewHolder.get(audioPlayer), message)) {
                this.audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
                executor.execute(()-> this.stopRefresher(true));
            } else {
                this.audioPlayerLayouts.removeWeakReferenceTo(audioPlayer);
            }
        }
    }

    private void bindWaveform(final RelativeLayout audioPlayer, final Message message) {
        final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
        final String key = waveformKey(message);
        final float[] cached;
        synchronized (WAVEFORM_CACHE) {
            cached = WAVEFORM_CACHE.get(key);
            if (cached == null && !WAVEFORM_IN_FLIGHT.add(key)) {
                viewHolder.progress.clearAmplitudes();
                return;
            }
        }
        if (cached != null) {
            viewHolder.progress.setAmplitudes(cached);
            return;
        }

        viewHolder.progress.clearAmplitudes();
        waveformExecutor.execute(
                () -> {
                    AndroidSecureMessageMediaReadCache.Lease lease = null;
                    try {
                        final float[] amplitudes;
                        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                            final Conversations application =
                                    (Conversations) messageAdapter.getActivity().getApplication();
                            final AndroidSecureMessageMediaReadCache cache =
                                    new AndroidSecureMessageMediaReadCache(
                                            messageAdapter.getActivity(),
                                            application.getSecureContentStoreProvider().get());
                            lease =
                                    cache.acquire(
                                            message.getConversation().getAccount().getUuid(),
                                            message.getUuid());
                        }
                        if (lease != null) {
                            amplitudes =
                                    AttachmentAudioWaveformExtractor.extract(
                                            messageAdapter.getActivity(),
                                            lease.getUri(),
                                            AttachmentAudioWaveformExtractor.DEFAULT_BUCKETS);
                        } else {
                            final File file = messageAdapter.getFileBackend().getFile(message);
                            if (!file.isFile()) {
                                throw new IllegalStateException("Audio file is unavailable");
                            }
                            amplitudes =
                                    AttachmentAudioWaveformExtractor.extract(
                                            file.getAbsolutePath(),
                                            AttachmentAudioWaveformExtractor.DEFAULT_BUCKETS);
                        }

                        synchronized (WAVEFORM_CACHE) {
                            if (WAVEFORM_CACHE.size() >= MAX_WAVEFORM_CACHE_ENTRIES) {
                                WAVEFORM_CACHE.clear();
                            }
                            WAVEFORM_CACHE.put(key, amplitudes);
                        }
                        messageAdapter.getActivity().runOnUiThread(
                                () -> {
                                    if (audioPlayer.getTag() == message) {
                                        ViewHolder.get(audioPlayer)
                                                .progress
                                                .setAmplitudes(amplitudes);
                                    }
                                });
                    } catch (final Exception error) {
                        Log.d(Config.LOGTAG, "unable to decode message audio waveform", error);
                    } finally {
                        closeLease(lease);
                        synchronized (WAVEFORM_CACHE) {
                            WAVEFORM_IN_FLIGHT.remove(key);
                        }
                    }
                });
    }

    private static String waveformKey(final Message message) {
        return message.getConversation().getAccount().getUuid() + ":" + message.getUuid();
    }

    private void showPlayIcon(final ViewHolder viewHolder) {
        // Play/pause controls are complete media markers, not small tinted glyphs inside
        // a second outlined button.
        viewHolder.playPause.setIconTint(null);
        viewHolder.playPause.setIconResource(R.drawable.ic_media_play_48dp);
    }

    private void showPauseIcon(final ViewHolder viewHolder) {
        viewHolder.playPause.setIconTint(null);
        viewHolder.playPause.setIconResource(R.drawable.ic_media_pause_48dp);
    }

    private boolean init(ViewHolder viewHolder, Message message) {
        MessageAdapter.setTextColor(viewHolder.runtime, viewHolder.bubbleColor);
        viewHolder.progress.setOnSeekBarChangeListener(this);
        final ColorStateList color =
                MessageAdapter.bubbleToOnSurfaceColorStateList(
                        viewHolder.progress, viewHolder.bubbleColor);
        viewHolder.progress.setThumbTintList(color);
        viewHolder.progress.setProgressTintList(color);
        viewHolder.playPause.setOnClickListener(this);
        final Context context = viewHolder.playPause.getContext();
        if (message == currentlyPlayingMessage) {
            if (AudioPlayer.player != null && AudioPlayer.player.isPlaying()) {
                showPauseIcon(viewHolder);
                viewHolder.playPause.setContentDescription(context.getString(R.string.pause_audio));
                viewHolder.progress.setEnabled(true);
            } else {
                viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
                showPlayIcon(viewHolder);
                viewHolder.progress.setEnabled(false);
            }
            return true;
        } else {
            showPlayIcon(viewHolder);
            viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
            viewHolder.runtime.setText(formatTime(message.getFileParams().runtime));
            viewHolder.progress.setProgress(0);
            viewHolder.progress.setEnabled(false);
            return false;
        }
    }

    @Override
    public synchronized void onClick(View v) {
        if (v.getId() == R.id.play_pause) {
            synchronized (LOCK) {
                startStop(v);
            }
        }
    }

    private void startStop(final View playPause) {
        initializeProximityWakeLock(playPause.getContext());
        final RelativeLayout audioPlayer = (RelativeLayout) playPause.getParent();
        final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
        final Message message = (Message) audioPlayer.getTag();

        if (message == currentlyPlayingMessage && player != null) {
            if (startStop(viewHolder, message, currentlyPlayingLease)) {
                this.audioPlayerLayouts.clear();
                this.audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
                stopRefresher(true);
            }
            return;
        }

        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            executor.execute(() -> prepareSecurePlayback(playPause, audioPlayer, viewHolder, message));
            return;
        }
        startStopLegacy(playPause, audioPlayer, viewHolder, message);
    }

    private void prepareSecurePlayback(
            final View playPause,
            final RelativeLayout audioPlayer,
            final ViewHolder viewHolder,
            final Message message) {
        final AndroidSecureMessageMediaReadCache.Lease lease;
        try {
            final Conversations application =
                    (Conversations) messageAdapter.getActivity().getApplication();
            final AndroidSecureMessageMediaReadCache cache =
                    new AndroidSecureMessageMediaReadCache(
                            messageAdapter.getActivity(),
                            application.getSecureContentStoreProvider().get());
            lease = cache.acquire(
                    message.getConversation().getAccount().getUuid(), message.getUuid());
        } catch (final Exception e) {
            Log.e(Config.LOGTAG, "unable to prepare secure audio playback", e);
            return;
        }

        messageAdapter.getActivity().runOnUiThread(
                () -> {
                    if (audioPlayer.getTag() != message) {
                        closeLease(lease);
                        return;
                    }
                    synchronized (LOCK) {
                        if (lease == null) {
                            // Only an absent secure relation is allowed to use the rollout legacy
                            // population. Inconsistent secure state throws before this point.
                            startStopLegacy(playPause, audioPlayer, viewHolder, message);
                            return;
                        }
                        if (AudioPlayer.player != null) {
                            stopCurrent();
                        }
                        if (play(viewHolder, message, false, lease)) {
                            audioPlayerLayouts.clear();
                            audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
                            stopRefresher(true);
                        }
                    }
                });
    }

    private void startStopLegacy(
            final View playPause,
            final RelativeLayout audioPlayer,
            final ViewHolder viewHolder,
            final Message message) {
        final File file = messageAdapter.getFileBackend().getFile(message);
        final boolean needsLegacyStoragePermission =
                !messageAdapter.getFileBackend().isAppPrivateMediaFile(file);
        if (needsLegacyStoragePermission
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                messageAdapter.getActivity(),
                                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingOnClickView.push(new WeakReference<>(playPause));
            ActivityCompat.requestPermissions(
                    messageAdapter.getActivity(),
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    ConversationsActivity.REQUEST_PLAY_PAUSE);
            return;
        }
        if (startStop(viewHolder, message, null)) {
            this.audioPlayerLayouts.clear();
            this.audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
            stopRefresher(true);
        }
    }

    private boolean playPauseCurrent(final ViewHolder viewHolder) {
        final Context context = viewHolder.playPause.getContext();
        if (player.isPlaying()) {
            viewHolder.progress.setEnabled(false);
            player.pause();
            messageAdapter.flagScreenOff();
            releaseProximityWakeLock();
            showPlayIcon(viewHolder);
            viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
        } else {
            viewHolder.progress.setEnabled(true);
            player.start();
            messageAdapter.flagScreenOn();
            acquireProximityWakeLock();
            this.stopRefresher(true);
            showPauseIcon(viewHolder);
            viewHolder.playPause.setContentDescription(context.getString(R.string.pause_audio));
        }
        return false;
    }

    private void play(
            ViewHolder viewHolder,
            Message message,
            boolean earpiece,
            double progress,
            @Nullable AndroidSecureMessageMediaReadCache.Lease lease) {
        if (play(viewHolder, message, earpiece, lease)) {
            AudioPlayer.player.seekTo((int) (AudioPlayer.player.getDuration() * progress));
        }
    }

    private boolean play(
            ViewHolder viewHolder,
            Message message,
            boolean earpiece,
            @Nullable AndroidSecureMessageMediaReadCache.Lease lease) {
        AudioPlayer.player = new MediaPlayer();
        try {
            AudioPlayer.currentlyPlayingMessage = message;
            AudioPlayer.player.setAudioStreamType(
                    earpiece ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
            if (lease != null) {
                AudioPlayer.player.setDataSource(messageAdapter.getActivity(), lease.getUri());
            } else {
                AudioPlayer.player.setDataSource(
                        messageAdapter.getFileBackend().getFile(message).getAbsolutePath());
            }
            AudioPlayer.player.setOnCompletionListener(this);
            AudioPlayer.player.prepare();
            AudioPlayer.player.start();
            AudioPlayer.currentlyPlayingLease = lease;
            messageAdapter.flagScreenOn();
            acquireProximityWakeLock();
            viewHolder.progress.setEnabled(true);
            showPauseIcon(viewHolder);
            viewHolder.playPause.setContentDescription(
                    viewHolder.playPause.getContext().getString(R.string.pause_audio));
            if (sensorManager != null && proximitySensor != null) {
                sensorManager.registerListener(
                        this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
            return true;
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "unable to start audio playback", e);
            messageAdapter.flagScreenOff();
            releaseProximityWakeLock();
            AudioPlayer.currentlyPlayingMessage = null;
            if (AudioPlayer.player != null) {
                AudioPlayer.player.release();
                AudioPlayer.player = null;
            }
            closeLease(lease);
            AudioPlayer.currentlyPlayingLease = null;
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            return false;
        }
    }

    public void startStopPending() {
        WeakReference<View> reference = pendingOnClickView.pop();
        if (reference != null) {
            View imageButton = reference.get();
            if (imageButton != null) {
                startStop(imageButton);
            }
        }
    }

    private boolean startStop(
            ViewHolder viewHolder,
            Message message,
            @Nullable AndroidSecureMessageMediaReadCache.Lease lease) {
        if (message == currentlyPlayingMessage && player != null) {
            return playPauseCurrent(viewHolder);
        }
        if (AudioPlayer.player != null) {
            stopCurrent();
        }
        return play(viewHolder, message, false, lease);
    }

    private void stopCurrent() {
        if (AudioPlayer.player != null) {
            if (AudioPlayer.player.isPlaying()) {
                AudioPlayer.player.stop();
            }
            AudioPlayer.player.release();
        }
        messageAdapter.flagScreenOff();
        releaseProximityWakeLock();
        AudioPlayer.player = null;
        closeLease(AudioPlayer.currentlyPlayingLease);
        AudioPlayer.currentlyPlayingLease = null;
        resetPlayerUi();
    }

    private static void closeLease(@Nullable final AndroidSecureMessageMediaReadCache.Lease lease) {
        if (lease != null) {
            try {
                lease.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private void resetPlayerUi() {
        for (WeakReference<RelativeLayout> audioPlayer : audioPlayerLayouts) {
            resetPlayerUi(audioPlayer.get());
        }
    }

    private void resetPlayerUi(RelativeLayout audioPlayer) {
        if (audioPlayer == null) {
            return;
        }
        final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
        final Message message = (Message) audioPlayer.getTag();
        viewHolder.playPause.setContentDescription(
                viewHolder.playPause.getContext().getString(R.string.play_audio));
        showPlayIcon(viewHolder);
        if (message != null) {
            viewHolder.runtime.setText(formatTime(message.getFileParams().runtime));
        }
        viewHolder.progress.setProgress(0);
        viewHolder.progress.setEnabled(false);
    }

    @Override
    public void onCompletion(android.media.MediaPlayer mediaPlayer) {
        synchronized (AudioPlayer.LOCK) {
            this.stopRefresher(false);
            if (AudioPlayer.player == mediaPlayer) {
                AudioPlayer.currentlyPlayingMessage = null;
                AudioPlayer.player = null;
                closeLease(AudioPlayer.currentlyPlayingLease);
                AudioPlayer.currentlyPlayingLease = null;
            }
            mediaPlayer.release();
            messageAdapter.flagScreenOff();
            releaseProximityWakeLock();
            resetPlayerUi();
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        synchronized (AudioPlayer.LOCK) {
            final RelativeLayout audioPlayer = (RelativeLayout) seekBar.getParent();
            final Message message = (Message) audioPlayer.getTag();
            if (fromUser && message == AudioPlayer.currentlyPlayingMessage && AudioPlayer.player != null) {
                float percent = progress / 100f;
                int duration = AudioPlayer.player.getDuration();
                int seekTo = Math.round(duration * percent);
                AudioPlayer.player.seekTo(seekTo);
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    public void stop() {
        synchronized (AudioPlayer.LOCK) {
            stopRefresher(false);
            if (AudioPlayer.player != null) {
                stopCurrent();
            } else {
                closeLease(AudioPlayer.currentlyPlayingLease);
                AudioPlayer.currentlyPlayingLease = null;
            }
            AudioPlayer.currentlyPlayingMessage = null;
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }

    private void stopRefresher(boolean runOnceMore) {
        this.handler.removeCallbacks(this);
        if (runOnceMore) {
            this.handler.post(this);
        }
    }

    public void unregisterListener() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void run() {
        synchronized (AudioPlayer.LOCK) {
            if (AudioPlayer.player != null) {
                boolean renew = false;
                final int current = player.getCurrentPosition();
                final int duration = player.getDuration();
                for (WeakReference<RelativeLayout> audioPlayer : audioPlayerLayouts) {
                    renew |= refreshAudioPlayer(audioPlayer.get(), current, duration);
                }
                if (renew && AudioPlayer.player.isPlaying()) {
                    handler.postDelayed(this, REFRESH_INTERVAL);
                }
            }
        }
    }

    private boolean refreshAudioPlayer(RelativeLayout audioPlayer, int current, int duration) {
        if (audioPlayer == null || audioPlayer.getVisibility() != View.VISIBLE) {
            return false;
        }
        final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
        if (duration <= 0) {
            viewHolder.progress.setProgress(100);
        } else {
            final var progress = current * 100L / duration;
            viewHolder.progress.setProgress(Math.min(Ints.saturatedCast(progress), 100));
        }
        viewHolder.runtime.setText(String.format("%s / %s", formatTime(current), formatTime(duration)));
        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) {
            return;
        }
        if (AudioPlayer.player == null || !AudioPlayer.player.isPlaying()) {
            return;
        }
        final int streamType;
        if (event.values[0] < 5f && event.values[0] != proximitySensor.getMaximumRange()) {
            streamType = AudioManager.STREAM_VOICE_CALL;
        } else {
            streamType = AudioManager.STREAM_MUSIC;
        }
        messageAdapter.setVolumeControl(streamType);
        double position = AudioPlayer.player.getCurrentPosition();
        double duration = AudioPlayer.player.getDuration();
        double progress = position / duration;
        if (AudioPlayer.player.getAudioStreamType() != streamType) {
            synchronized (AudioPlayer.LOCK) {
                final AndroidSecureMessageMediaReadCache.Lease lease = currentlyPlayingLease;
                AudioPlayer.player.stop();
                AudioPlayer.player.release();
                AudioPlayer.player = null;
                try {
                    ViewHolder currentViewHolder = getCurrentViewHolder();
                    if (currentViewHolder != null) {
                        play(
                                currentViewHolder,
                                currentlyPlayingMessage,
                                streamType == AudioManager.STREAM_VOICE_CALL,
                                progress,
                                lease);
                    }
                } catch (Exception e) {
                    Log.w(Config.LOGTAG, e);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }

    private void acquireProximityWakeLock() {
        synchronized (AudioPlayer.LOCK) {
            if (wakeLock != null) {
                wakeLock.acquire();
            }
        }
    }

    private void releaseProximityWakeLock() {
        synchronized (AudioPlayer.LOCK) {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
        messageAdapter.setVolumeControl(AudioManager.STREAM_MUSIC);
    }

    private ViewHolder getCurrentViewHolder() {
        for (WeakReference<RelativeLayout> audioPlayer : audioPlayerLayouts) {
            final RelativeLayout layout = audioPlayer.get();
            if (layout == null) {
                continue;
            }
            final Message message = (Message) layout.getTag();
            if (message == currentlyPlayingMessage) {
                return ViewHolder.get(layout);
            }
        }
        return null;
    }

    public static class ViewHolder {
        private TextView runtime;
        private AttachmentWaveformSeekBar progress;
        private MaterialButton playPause;
        private MessageAdapter.BubbleColor bubbleColor = MessageAdapter.BubbleColor.SURFACE;

        public static ViewHolder get(RelativeLayout audioPlayer) {
            ViewHolder viewHolder = (ViewHolder) audioPlayer.getTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER);
            if (viewHolder == null) {
                viewHolder = new ViewHolder();
                viewHolder.runtime = audioPlayer.findViewById(R.id.runtime);
                viewHolder.progress = audioPlayer.findViewById(R.id.progress);
                viewHolder.playPause = audioPlayer.findViewById(R.id.play_pause);
                audioPlayer.setTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER, viewHolder);
            }
            return viewHolder;
        }

        public void setBubbleColor(final MessageAdapter.BubbleColor bubbleColor) {
            this.bubbleColor = bubbleColor;
        }
    }
}
