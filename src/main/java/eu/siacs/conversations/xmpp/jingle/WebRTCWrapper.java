package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CandidatePairChangeEvent;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.XmppConnectionService;

public class WebRTCWrapper {

    private static final String EXTENDED_LOGGING_TAG = WebRTCWrapper.class.getSimpleName();
    private static final Object GLOBAL_INIT_LOCK = new Object();
    private static volatile boolean globalFactoryInitialized = false;
    private static final long LINK_QUALITY_SAMPLE_INTERVAL_MS = 2_000L;
    private static final long WEBRTC_OPERATION_TIMEOUT_SECONDS = 8L;
    private static final ScheduledExecutorService LINK_QUALITY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        final Thread thread = new Thread(runnable, "CallLinkQuality");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final ExecutorService WEBRTC_TEARDOWN_EXECUTOR =
            Executors.newCachedThreadPool(
                    runnable -> {
                        final Thread thread = new Thread(runnable, "WebRTCTeardown");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService localDescriptionExecutorService =
            Executors.newSingleThreadExecutor();

    private static final int TONE_DURATION = 500;
    private static final Map<String,Integer> TONE_CODES;
    static {
        ImmutableMap.Builder<String,Integer> builder = new ImmutableMap.Builder<>();
        builder.put("0", ToneGenerator.TONE_DTMF_0);
        builder.put("1", ToneGenerator.TONE_DTMF_1);
        builder.put("2", ToneGenerator.TONE_DTMF_2);
        builder.put("3", ToneGenerator.TONE_DTMF_3);
        builder.put("4", ToneGenerator.TONE_DTMF_4);
        builder.put("5", ToneGenerator.TONE_DTMF_5);
        builder.put("6", ToneGenerator.TONE_DTMF_6);
        builder.put("7", ToneGenerator.TONE_DTMF_7);
        builder.put("8", ToneGenerator.TONE_DTMF_8);
        builder.put("9", ToneGenerator.TONE_DTMF_9);
        builder.put("*", ToneGenerator.TONE_DTMF_S);
        builder.put("#", ToneGenerator.TONE_DTMF_P);
        TONE_CODES = builder.build();
    }

    private static final Set<String> HARDWARE_AEC_BLACKLIST =
            new ImmutableSet.Builder<String>()
                    .add("Pixel")
                    .add("Pixel XL")
                    .add("Moto G5")
                    .add("Moto G (5S) Plus")
                    .add("Moto G4")
                    .add("TA-1053")
                    .add("Mi A1")
                    .add("Mi A2")
                    .add("E5823") // Sony z5 compact
                    .add("Redmi Note 5")
                    .add("FP2") // Fairphone FP2
                    .add("FP4") // Fairphone FP4
                    .add("MI 5")
                    .add("GT-I9515") // Samsung Galaxy S4 Value Edition (jfvelte)
                    .add("GT-I9515L") // Samsung Galaxy S4 Value Edition (jfvelte)
                    .add("GT-I9505") // Samsung Galaxy S4 (jfltexx)
                    .build();

    private final EventCallback eventCallback;
    private final AtomicBoolean readyToReceivedIceCandidates = new AtomicBoolean(false);
    private final Queue<IceCandidate> iceCandidates = new ConcurrentLinkedQueue<>();
    private volatile TrackWrapper<AudioTrack> localAudioTrack = null;
    private volatile TrackWrapper<VideoTrack> localVideoTrack = null;
    private volatile VideoTrack remoteVideoTrack = null;
    private final CallLinkQualityPolicy callLinkQualityPolicy = new CallLinkQualityPolicy();
    private final Object linkQualityLock = new Object();
    private volatile CallLinkQualityPolicy.Level appliedAudioQualityLevel =
            CallLinkQualityPolicy.Level.GOOD;
    private volatile CallLinkQualityPolicy.Level appliedVideoQualityLevel =
            CallLinkQualityPolicy.Level.GOOD;
    @Nullable private ScheduledFuture<?> linkQualityFuture;

    private final Object iceGatheringLock = new Object();
    private SettableFuture<Void> iceGatheringComplete = SettableFuture.create();
    private final PeerConnection.Observer peerConnectionObserver =
            new PeerConnection.Observer() {
                @Override
                public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                    Log.d(EXTENDED_LOGGING_TAG, "onSignalingChange(" + signalingState + ")");
                    // this is called after removeTrack or addTrack
                    // and should then trigger a content-add or content-remove or something
                    // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/removeTrack
                }

                @Override
                public void onConnectionChange(final PeerConnection.PeerConnectionState newState) {
                    CallDiagnosticsRuntime.record("webrtc state=" + newState);
                    updateLinkQualityMonitoring(newState);

                    // Never enter the Jingle state machine synchronously from WebRTC's native
                    // signaling callback. XMPP packet delivery may hold JingleRtpConnection's
                    // monitor while calling a native PeerConnection method (for example while
                    // adding a trickled ICE candidate). If the native signaling thread calls
                    // back into Jingle at the same time, both threads can wait on each other and
                    // leave the call UI stuck in CONNECTING even though WebRTC is CONNECTED.
                    //
                    // executorService is single-threaded, so connection-state callbacks keep
                    // their order while the native signaling thread is released immediately.
                    executorService.execute(
                            () -> {
                                CallDiagnosticsRuntime.record(
                                        "webrtc state-dispatch begin=" + newState);
                                eventCallback.onConnectionChange(newState);
                                CallDiagnosticsRuntime.record(
                                        "webrtc state-dispatch end=" + newState);
                            });
                }

                @Override
                public void onIceConnectionChange(
                        PeerConnection.IceConnectionState iceConnectionState) {
                    CallDiagnosticsRuntime.record("webrtc ice=" + iceConnectionState);
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onIceConnectionChange(" + iceConnectionState + ")");
                }

                @Override
                public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {
                    Log.d(Config.LOGTAG, "remote candidate selected: " + event.remote);
                    Log.d(Config.LOGTAG, "local candidate selected: " + event.local);
                }

                @Override
                public void onIceConnectionReceivingChange(boolean b) {}

                @Override
                public void onIceGatheringChange(
                        final PeerConnection.IceGatheringState iceGatheringState) {
                    Log.d(EXTENDED_LOGGING_TAG, "onIceGatheringChange(" + iceGatheringState + ")");
                    if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                        signalIceGatheringComplete();
                    }
                }

                @Override
                public void onIceCandidate(IceCandidate iceCandidate) {
                    if (readyToReceivedIceCandidates.get()) {
                        eventCallback.onIceCandidate(iceCandidate);
                    } else {
                        iceCandidates.add(iceCandidate);
                    }
                }

                @Override
                public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

                @Override
                public void onAddStream(MediaStream mediaStream) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddStream(numAudioTracks="
                                    + mediaStream.audioTracks.size()
                                    + ",numVideoTracks="
                                    + mediaStream.videoTracks.size()
                                    + ")");
                }

                @Override
                public void onRemoveStream(MediaStream mediaStream) {}

                @Override
                public void onDataChannel(DataChannel dataChannel) {}

                @Override
                public void onRenegotiationNeeded() {
                    CallDiagnosticsRuntime.record("webrtc renegotiation-needed");
                    Log.d(EXTENDED_LOGGING_TAG, "onRenegotiationNeeded()");
                    final PeerConnection currentPeerConnection = peerConnection;
                    if (currentPeerConnection == null) {
                        return;
                    }
                    final PeerConnection.PeerConnectionState currentState;
                    try {
                        currentState = currentPeerConnection.connectionState();
                    } catch (final IllegalStateException e) {
                        Log.d(
                                EXTENDED_LOGGING_TAG,
                                "ignoring renegotiation callback during WebRTC teardown");
                        return;
                    }
                    if (currentState != PeerConnection.PeerConnectionState.NEW) {
                        eventCallback.onRenegotiationNeeded();
                    }
                }

                @Override
                public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                    final MediaStreamTrack track = rtpReceiver.track();
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddTrack(kind="
                                    + (track == null ? "null" : track.kind())
                                    + ",numMediaStreams="
                                    + mediaStreams.length
                                    + ")");
                    if (track instanceof VideoTrack) {
                        remoteVideoTrack = (VideoTrack) track;
                        CallDiagnosticsRuntime.record("webrtc remote-video-track added");
                    }
                }

                @Override
                public void onTrack(final RtpTransceiver transceiver) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onTrack(mid="
                                    + transceiver.getMid()
                                    + ",media="
                                    + transceiver.getMediaType()
                                    + ",direction="
                                    + transceiver.getDirection()
                                    + ")");
                }

                @Override
                public void onRemoveTrack(final RtpReceiver receiver) {
                    Log.d(EXTENDED_LOGGING_TAG, "onRemoveTrack(" + receiver.id() + ")");
                }
            };
    @Nullable private volatile PeerConnectionFactory peerConnectionFactory = null;
    @Nullable private volatile PeerConnection peerConnection = null;
    private Context context = null;
    private volatile EglBase eglBase = null;
    private volatile VideoSourceWrapper videoSourceWrapper;

    WebRTCWrapper(final EventCallback eventCallback) {
        this.eventCallback = eventCallback;
    }

    private static void dispose(final PeerConnection peerConnection) {
        try {
            peerConnection.dispose();
        } catch (final IllegalStateException e) {
            Log.e(Config.LOGTAG, "unable to dispose of peer connection", e);
        }
    }

    static void warmUp(final XmppConnectionService service) {
        final long started = SystemClock.elapsedRealtime();
        try {
            ensureGlobalFactoryInitialized(service);
            Log.d(
                    Config.LOGTAG,
                    "WebRTC global warm-up ready in "
                            + (SystemClock.elapsedRealtime() - started)
                            + " ms");
        } catch (final InitializationException e) {
            // A failed speculative warm-up must not make the account unusable. The actual call
            // path retries initialization and will surface a call error if it still fails.
            Log.w(Config.LOGTAG, "WebRTC global warm-up failed", e);
        }
    }

    private static void ensureGlobalFactoryInitialized(final Context context)
            throws InitializationException {
        if (globalFactoryInitialized) {
            return;
        }
        synchronized (GLOBAL_INIT_LOCK) {
            if (globalFactoryInitialized) {
                return;
            }
            try {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context)
                                .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                                .createInitializationOptions());
            } catch (final UnsatisfiedLinkError e) {
                throw new InitializationException("Unable to initialize PeerConnectionFactory", e);
            }
            globalFactoryInitialized = true;
        }
    }

    public void setup(final XmppConnectionService service) throws InitializationException {
        final long started = SystemClock.elapsedRealtime();
        ensureGlobalFactoryInitialized(service);
        try {
            this.eglBase = EglBase.create();
        } catch (final RuntimeException e) {
            throw new InitializationException("Unable to create EGL base", e);
        }
        this.context = service;
        Log.d(
                Config.LOGTAG,
                "WebRTC wrapper setup in "
                        + (SystemClock.elapsedRealtime() - started)
                        + " ms");
    }

    synchronized void initializePeerConnection(
            final Set<Media> media,
            final List<PeerConnection.IceServer> iceServers,
            final boolean trickle)
            throws InitializationException {
        final long started = SystemClock.elapsedRealtime();
        Preconditions.checkState(this.eglBase != null);
        Preconditions.checkNotNull(media);
        Preconditions.checkArgument(
                media.size() > 0, "media can not be empty when initializing peer connection");
        final boolean setUseHardwareAcousticEchoCanceler =
                !HARDWARE_AEC_BLACKLIST.contains(Build.MODEL);
        Log.d(
                Config.LOGTAG,
                String.format(
                        "setUseHardwareAcousticEchoCanceler(%s) model=%s",
                        setUseHardwareAcousticEchoCanceler, Build.MODEL));
        this.peerConnectionFactory =
                PeerConnectionFactory.builder()
                        .setVideoDecoderFactory(
                                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                        .setVideoEncoderFactory(
                                new DefaultVideoEncoderFactory(
                                        eglBase.getEglBaseContext(), true, true))
                        .setAudioDeviceModule(
                                JavaAudioDeviceModule.builder(requireContext())
                                        .setUseHardwareAcousticEchoCanceler(
                                                setUseHardwareAcousticEchoCanceler)
                                        .createAudioDeviceModule())
                        .createPeerConnectionFactory();

        final PeerConnection.RTCConfiguration rtcConfig = buildConfiguration(iceServers, trickle);
        final PeerConnection peerConnection =
                requirePeerConnectionFactory()
                        .createPeerConnection(rtcConfig, peerConnectionObserver);
        if (peerConnection == null) {
            throw new InitializationException("Unable to create PeerConnection");
        }

        if (media.contains(Media.VIDEO)) {
            addVideoTrack(peerConnection);
        }

        if (media.contains(Media.AUDIO)) {
            addAudioTrack(peerConnection);
        }
        peerConnection.setAudioPlayout(true);
        peerConnection.setAudioRecording(true);

        this.peerConnection = peerConnection;
        Log.d(
                Config.LOGTAG,
                "WebRTC peer initialized media="
                        + media
                        + " iceServers="
                        + iceServers.size()
                        + " trickle="
                        + trickle
                        + " in "
                        + (SystemClock.elapsedRealtime() - started)
                        + " ms");
    }

    private VideoSourceWrapper initializeVideoSourceWrapper() {
        final VideoSourceWrapper existingVideoSourceWrapper = this.videoSourceWrapper;
        if (existingVideoSourceWrapper != null) {
            existingVideoSourceWrapper.startCapture();
            return existingVideoSourceWrapper;
        }
        final VideoSourceWrapper videoSourceWrapper =
                new VideoSourceWrapper.Factory(requireContext()).create();
        if (videoSourceWrapper == null) {
            throw new IllegalStateException("Could not instantiate VideoSourceWrapper");
        }
        videoSourceWrapper.initialize(
                requirePeerConnectionFactory(), requireContext(), eglBase.getEglBaseContext());
        videoSourceWrapper.startCapture();
        this.videoSourceWrapper = videoSourceWrapper;
        return videoSourceWrapper;
    }

    public synchronized boolean addTrack(final Media media) {
        if (media == Media.VIDEO) {
            return addVideoTrack(requirePeerConnection());
        } else if (media == Media.AUDIO) {
            return addAudioTrack(requirePeerConnection());
        }
        throw new IllegalStateException(String.format("Could not add track for %s", media));
    }

    public synchronized void removeTrack(final Media media) {
        if (media == Media.VIDEO) {
            removeVideoTrack(requirePeerConnection());
        }
    }

    private boolean addAudioTrack(final PeerConnection peerConnection) {
        final AudioSource audioSource =
                requirePeerConnectionFactory().createAudioSource(new MediaConstraints());
        final AudioTrack audioTrack =
                requirePeerConnectionFactory()
                        .createAudioTrack(TrackWrapper.id(AudioTrack.class), audioSource);
        this.localAudioTrack = TrackWrapper.addTrack(peerConnection, audioTrack);
        return true;
    }

    private boolean addVideoTrack(final PeerConnection peerConnection) {
        final TrackWrapper<VideoTrack> existing = this.localVideoTrack;
        if (existing != null) {
            final RtpTransceiver transceiver =
                    TrackWrapper.getTransceiver(peerConnection, existing);
            if (transceiver == null) {
                Log.w(EXTENDED_LOGGING_TAG, "unable to restart video transceiver");
                return false;
            }
            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
            this.videoSourceWrapper.startCapture();
            return true;
        }
        final VideoSourceWrapper videoSourceWrapper;
        try {
            videoSourceWrapper = initializeVideoSourceWrapper();
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "could not add video track", e);
            return false;
        }
        final VideoTrack videoTrack =
                requirePeerConnectionFactory()
                        .createVideoTrack(
                                TrackWrapper.id(VideoTrack.class),
                                videoSourceWrapper.getVideoSource());
        this.localVideoTrack = TrackWrapper.addTrack(peerConnection, videoTrack);
        return true;
    }

    private void removeVideoTrack(final PeerConnection peerConnection) {
        final TrackWrapper<VideoTrack> localVideoTrack = this.localVideoTrack;
        if (localVideoTrack != null) {

            final RtpTransceiver exactTransceiver =
                    TrackWrapper.getTransceiver(peerConnection, localVideoTrack);
            if (exactTransceiver == null) {
                throw new IllegalStateException();
            }
            exactTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE);
        }
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        if (videoSourceWrapper != null) {
            try {
                videoSourceWrapper.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static PeerConnection.RTCConfiguration buildConfiguration(
            final List<PeerConnection.IceServer> iceServers, final boolean trickle) {
        final PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.tcpCandidatePolicy =
                PeerConnection.TcpCandidatePolicy.DISABLED; // XEP-0176 doesn't support tcp
        if (trickle) {
            rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        } else {
            rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        }
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE;
        rtcConfig.enableImplicitRollback = true;
        return rtcConfig;
    }

    void reconfigurePeerConnection(
            final List<PeerConnection.IceServer> iceServers, final boolean trickle) {
        requirePeerConnection().setConfiguration(buildConfiguration(iceServers, trickle));
    }

    void reconfigureAndRestartIceAsync(
            final List<PeerConnection.IceServer> iceServers, final boolean trickle) {
        this.execute(
                () -> {
                    final PeerConnection peerConnection;
                    try {
                        peerConnection = requirePeerConnection();
                    } catch (final PeerConnectionNotInitialized e) {
                        Log.w(
                                EXTENDED_LOGGING_TAG,
                                "PeerConnection vanished before initial ICE recovery");
                        return;
                    }
                    peerConnection.setConfiguration(buildConfiguration(iceServers, trickle));
                    setIsReadyToReceiveIceCandidates(false);
                    resetIceGatheringComplete();
                    peerConnection.restartIce();
                });
    }

    void restartIceAsync() {
        this.execute(this::restartIce);
    }

    private void restartIce() {
        final PeerConnection peerConnection;
        try {
            peerConnection = requirePeerConnection();
        } catch (final PeerConnectionNotInitialized e) {
            Log.w(EXTENDED_LOGGING_TAG, "PeerConnection vanished before we could execute restart");
            return;
        }
        setIsReadyToReceiveIceCandidates(false);
        resetIceGatheringComplete();
        peerConnection.restartIce();
    }

    void onNetworkHandover() {
        executorService.execute(
                () -> {
                    final CallLinkQualityPolicy.Level level =
                            callLinkQualityPolicy.onNetworkHandover(
                                    SystemClock.elapsedRealtime());
                    if (level != appliedAudioQualityLevel) {
                        applyOutgoingAudioQuality(level);
                    }
                    if (level != appliedVideoQualityLevel) {
                        applyOutgoingVideoQuality(level);
                    }
                });
    }

    private void updateLinkQualityMonitoring(
            final PeerConnection.PeerConnectionState state) {
        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
            startLinkQualityMonitoring();
        } else if (state == PeerConnection.PeerConnectionState.CLOSED) {
            stopLinkQualityMonitoring();
        }
    }

    private void startLinkQualityMonitoring() {
        synchronized (linkQualityLock) {
            if (linkQualityFuture != null && !linkQualityFuture.isCancelled()) {
                return;
            }
            linkQualityFuture =
                    LINK_QUALITY_EXECUTOR.scheduleWithFixedDelay(
                            this::requestLinkQualitySample,
                            LINK_QUALITY_SAMPLE_INTERVAL_MS,
                            LINK_QUALITY_SAMPLE_INTERVAL_MS,
                            TimeUnit.MILLISECONDS);
        }
    }

    private void stopLinkQualityMonitoring() {
        final ScheduledFuture<?> future;
        synchronized (linkQualityLock) {
            future = linkQualityFuture;
            linkQualityFuture = null;
        }
        if (future != null) {
            future.cancel(false);
        }
    }

    private void requestLinkQualitySample() {
        final PeerConnection currentPeerConnection = this.peerConnection;
        if (currentPeerConnection == null) {
            return;
        }
        executorService.execute(() -> collectLinkQualitySample(currentPeerConnection));
    }

    private void collectLinkQualitySample(final PeerConnection currentPeerConnection) {
        if (currentPeerConnection != this.peerConnection
                || !hasActiveLocalMedia(currentPeerConnection)) {
            return;
        }
        try {
            currentPeerConnection.getStats(
                    report ->
                            executorService.execute(
                                    () ->
                                            processLinkQualityReport(
                                                    currentPeerConnection, report)));
        } catch (final IllegalStateException e) {
            Log.d(EXTENDED_LOGGING_TAG, "unable to collect call link stats", e);
        }
    }

    private void processLinkQualityReport(
            final PeerConnection currentPeerConnection, final RTCStatsReport report) {
        if (currentPeerConnection != this.peerConnection
                || !hasActiveLocalMedia(currentPeerConnection)) {
            return;
        }
        final CallLinkQualityPolicy.Sample sample = parseLinkQualitySample(report);
        final CallLinkQualityPolicy.Level level =
                callLinkQualityPolicy.update(sample, SystemClock.elapsedRealtime());
        if (level != appliedAudioQualityLevel) {
            applyOutgoingAudioQuality(level);
        }
        if (level != appliedVideoQualityLevel) {
            applyOutgoingVideoQuality(level);
        }
    }

    private boolean hasActiveLocalMedia(final PeerConnection currentPeerConnection) {
        return hasActiveLocalAudio(currentPeerConnection)
                || hasActiveLocalVideo(currentPeerConnection);
    }

    private boolean hasActiveLocalAudio(final PeerConnection currentPeerConnection) {
        final TrackWrapper<AudioTrack> audio = this.localAudioTrack;
        if (audio == null) {
            return false;
        }
        final RtpTransceiver transceiver =
                TrackWrapper.getTransceiver(currentPeerConnection, audio);
        if (transceiver == null) {
            return false;
        }
        try {
            final RtpTransceiver.RtpTransceiverDirection direction = transceiver.getDirection();
            return direction == RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                    || direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV;
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    private boolean hasActiveLocalVideo(final PeerConnection currentPeerConnection) {
        final TrackWrapper<VideoTrack> video = this.localVideoTrack;
        if (video == null) {
            return false;
        }
        final RtpTransceiver transceiver =
                TrackWrapper.getTransceiver(currentPeerConnection, video);
        if (transceiver == null) {
            return false;
        }
        try {
            final RtpTransceiver.RtpTransceiverDirection direction = transceiver.getDirection();
            return direction == RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                    || direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV;
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    private boolean applyOutgoingAudioQuality(final CallLinkQualityPolicy.Level level) {
        final PeerConnection currentPeerConnection = this.peerConnection;
        final TrackWrapper<AudioTrack> audio = this.localAudioTrack;
        if (currentPeerConnection == null
                || audio == null
                || !hasActiveLocalAudio(currentPeerConnection)) {
            return false;
        }
        try {
            final RtpParameters parameters = audio.rtpSender.getParameters();
            if (parameters == null || parameters.encodings.isEmpty()) {
                return false;
            }
            for (final RtpParameters.Encoding encoding : parameters.encodings) {
                encoding.maxBitrateBps = level.maxAudioBitrateBps;
                encoding.adaptiveAudioPacketTime = level.adaptiveAudioPacketTime;
            }
            if (!audio.rtpSender.setParameters(parameters)) {
                Log.w(
                        EXTENDED_LOGGING_TAG,
                        "failed to apply adaptive audio quality " + level);
                return false;
            }
            appliedAudioQualityLevel = level;
            Log.d(
                    Config.LOGTAG,
                    "adaptive call audio quality="
                            + level
                            + " maxBitrate="
                            + level.maxAudioBitrateBps
                            + " adaptivePtime="
                            + level.adaptiveAudioPacketTime);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(EXTENDED_LOGGING_TAG, "audio sender vanished while applying link quality", e);
            return false;
        }
    }

    private boolean applyOutgoingVideoQuality(final CallLinkQualityPolicy.Level level) {
        final PeerConnection currentPeerConnection = this.peerConnection;
        final TrackWrapper<VideoTrack> video = this.localVideoTrack;
        if (currentPeerConnection == null
                || video == null
                || !hasActiveLocalVideo(currentPeerConnection)) {
            return false;
        }
        try {
            final RtpParameters parameters = video.rtpSender.getParameters();
            if (parameters == null || parameters.encodings.isEmpty()) {
                return false;
            }
            for (final RtpParameters.Encoding encoding : parameters.encodings) {
                encoding.maxBitrateBps = level.maxVideoBitrateBps;
                encoding.maxFramerate = level.maxVideoFramerate;
                encoding.scaleResolutionDownBy = level.scaleResolutionDownBy;
            }
            if (!video.rtpSender.setParameters(parameters)) {
                Log.w(
                        EXTENDED_LOGGING_TAG,
                        "failed to apply adaptive video quality " + level);
                return false;
            }
            appliedVideoQualityLevel = level;
            Log.d(
                    Config.LOGTAG,
                    "adaptive call video quality="
                            + level
                            + " maxBitrate="
                            + level.maxVideoBitrateBps
                            + " maxFps="
                            + level.maxVideoFramerate
                            + " scale="
                            + level.scaleResolutionDownBy);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(EXTENDED_LOGGING_TAG, "video sender vanished while applying link quality", e);
            return false;
        }
    }

    private static CallLinkQualityPolicy.Sample parseLinkQualitySample(
            final RTCStatsReport report) {
        Double availableOutgoingBitrateBps = null;
        Double roundTripTimeSeconds = null;
        Double packetLossFraction = null;
        Double jitterSeconds = null;
        int candidatePairRank = -1;

        for (final RTCStats stats : report.getStatsMap().values()) {
            final Map<String, Object> members = stats.getMembers();
            if ("candidate-pair".equals(stats.getType())) {
                final boolean selected = Boolean.TRUE.equals(members.get("selected"));
                final boolean nominated = Boolean.TRUE.equals(members.get("nominated"));
                final boolean succeeded = "succeeded".equals(members.get("state"));
                final int rank = selected ? 2 : (nominated && succeeded ? 1 : -1);
                if (rank >= 0 && rank >= candidatePairRank) {
                    candidatePairRank = rank;
                    availableOutgoingBitrateBps =
                            numberMember(members, "availableOutgoingBitrate");
                    roundTripTimeSeconds =
                            maxMetric(
                                    roundTripTimeSeconds,
                                    numberMember(members, "currentRoundTripTime"));
                }
            } else if ("remote-inbound-rtp".equals(stats.getType())
                    && isAudioOrVideoStats(members)) {
                packetLossFraction =
                        maxMetric(packetLossFraction, numberMember(members, "fractionLost"));
                roundTripTimeSeconds =
                        maxMetric(
                                roundTripTimeSeconds,
                                numberMember(members, "roundTripTime"));
                jitterSeconds =
                        maxMetric(jitterSeconds, numberMember(members, "jitter"));
            }
        }

        return new CallLinkQualityPolicy.Sample(
                availableOutgoingBitrateBps,
                roundTripTimeSeconds,
                packetLossFraction,
                jitterSeconds);
    }

    private static boolean isAudioOrVideoStats(final Map<String, Object> members) {
        final Object kind =
                members.containsKey("kind") ? members.get("kind") : members.get("mediaType");
        return "audio".equals(kind) || "video".equals(kind);
    }

    @Nullable
    private static Double numberMember(
            final Map<String, Object> members, final String key) {
        final Object value = members.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    @Nullable
    private static Double maxMetric(
            @Nullable final Double left, @Nullable final Double right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    public void setIsReadyToReceiveIceCandidates(final boolean ready) {
        readyToReceivedIceCandidates.set(ready);
        final int was = iceCandidates.size();
        while (ready && iceCandidates.peek() != null) {
            eventCallback.onIceCandidate(iceCandidates.poll());
        }
        final int is = iceCandidates.size();
        Log.d(
                EXTENDED_LOGGING_TAG,
                "setIsReadyToReceiveCandidates(" + ready + ") was=" + was + " is=" + is);
    }

    void close() {
        CallDiagnosticsRuntime.record("webrtc close detach");
        stopLinkQualityMonitoring();
        final PeerConnection peerConnection;
        final PeerConnectionFactory peerConnectionFactory;
        final VideoSourceWrapper videoSourceWrapper;
        final EglBase eglBase;
        synchronized (this) {
            peerConnection = this.peerConnection;
            peerConnectionFactory = this.peerConnectionFactory;
            videoSourceWrapper = this.videoSourceWrapper;
            eglBase = this.eglBase;

            // Make teardown immediately visible. Callers often hold JingleRtpConnection's monitor,
            // so no native WebRTC teardown may execute synchronously on the caller thread.
            this.peerConnection = null;
            this.peerConnectionFactory = null;
            this.videoSourceWrapper = null;
            this.eglBase = null;
            this.localAudioTrack = null;
            this.localVideoTrack = null;
            this.remoteVideoTrack = null;
            this.appliedAudioQualityLevel = CallLinkQualityPolicy.Level.GOOD;
            this.appliedVideoQualityLevel = CallLinkQualityPolicy.Level.GOOD;
        }

        if (peerConnection == null
                && videoSourceWrapper == null
                && eglBase == null
                && peerConnectionFactory == null) {
            return;
        }

        WEBRTC_TEARDOWN_EXECUTOR.execute(
                () -> {
                    // PeerConnection.dispose() can wait for callbacks into JingleRtpConnection.
                    // Keeping native teardown off the signaling/UI caller avoids lock inversion.
                    if (peerConnection != null) {
                        dispose(peerConnection);
                    }
                    if (videoSourceWrapper != null) {
                        try {
                            videoSourceWrapper.stopCapture();
                        } catch (final InterruptedException e) {
                            Log.e(Config.LOGTAG, "unable to stop capturing");
                            Thread.currentThread().interrupt();
                        } catch (final IllegalStateException e) {
                            Log.d(Config.LOGTAG, "video capture already stopped during teardown");
                        }
                        try {
                            videoSourceWrapper.dispose();
                        } catch (final IllegalStateException e) {
                            Log.d(Config.LOGTAG, "video source already disposed");
                        }
                    }
                    if (eglBase != null) {
                        try {
                            eglBase.release();
                        } catch (final RuntimeException e) {
                            Log.d(Config.LOGTAG, "EGL already released during teardown");
                        }
                    }
                    if (peerConnectionFactory != null) {
                        try {
                            peerConnectionFactory.dispose();
                        } catch (final IllegalStateException e) {
                            Log.d(Config.LOGTAG, "peer connection factory already disposed");
                        }
                    }
                    CallDiagnosticsRuntime.record("webrtc teardown complete");
                });
    }

    synchronized void verifyClosed() {
        if (this.peerConnection != null
                || this.eglBase != null
                || this.localVideoTrack != null
                || this.remoteVideoTrack != null) {
            final AssertionError e =
                    new AssertionError("WebRTCWrapper hasn't been closed properly");
            Log.e(Config.LOGTAG, "verifyClosed() failed. Going to throw", e);
            throw e;
        }
    }

    boolean isCameraSwitchable() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        return videoSourceWrapper != null && videoSourceWrapper.isCameraSwitchable();
    }

    boolean isFrontCamera() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        return videoSourceWrapper == null || videoSourceWrapper.isFrontCamera();
    }

    ListenableFuture<Boolean> switchCamera() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        if (videoSourceWrapper == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("VideoSourceWrapper has not been initialized"));
        }
        return videoSourceWrapper.switchCamera();
    }

    // Track/UI reads must stay non-blocking. Volatile ownership plus teardown-safe native access
    // lets close() detach resources without making lifecycle callbacks wait on the wrapper monitor.
    boolean isMicrophoneEnabled() {
        Optional<AudioTrack> audioTrack = null;
        try {
            audioTrack = TrackWrapper.get(peerConnection, this.localAudioTrack);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to check microphone", e);
            // ignoring race condition in case sender has been disposed
            return false;
        }
        if (audioTrack.isPresent()) {
            try {
                return audioTrack.get().enabled();
            } catch (final IllegalStateException e) {
                // sometimes UI might still be rendering the buttons when a background thread has
                // already ended the call
                return false;
            }
        } else {
            return false;
        }
    }

    boolean setMicrophoneEnabledOrThrow(final boolean enabled) {
        Optional<AudioTrack> audioTrack = null;
        try {
            audioTrack = TrackWrapper.get(peerConnection, this.localAudioTrack);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle microphone", e);
            // ignoring race condition in case sender has been disposed
            return false;
        }
        if (audioTrack.isPresent()) {
            return setEnabled(audioTrack.get(), enabled);

        } else {
            throw new IllegalStateException("Local audio track does not exist (yet)");
        }
    }

    private static boolean setEnabled(final AudioTrack audioTrack, final boolean enabled) {
        try {
            audioTrack.setEnabled(enabled);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle audio track", e);
            // ignoring race condition in case MediaStreamTrack has been disposed
            return false;
        }
    }

    void setMicrophoneEnabled(final boolean enabled) {
        final Optional<AudioTrack> audioTrack =
                TrackWrapper.get(peerConnection, this.localAudioTrack);
        if (audioTrack.isPresent()) {
            setEnabled(audioTrack.get(), enabled);
        }
    }

    boolean isVideoEnabled() {
        final Optional<VideoTrack> videoTrack =
                TrackWrapper.get(peerConnection, this.localVideoTrack);
        if (videoTrack.isPresent()) {
            try {
                return videoTrack.get().enabled();
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "video track became unavailable during teardown");
            }
        }
        return false;
    }

    void setVideoEnabled(final boolean enabled) {
        final Optional<VideoTrack> videoTrack =
                TrackWrapper.get(peerConnection, this.localVideoTrack);
        if (videoTrack.isPresent()) {
            try {
                videoTrack.get().setEnabled(enabled);
                return;
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "video track became unavailable while toggling");
            }
        }
        throw new IllegalStateException("Local video track does not exist");
    }

    synchronized ListenableFuture<SessionDescription> setLocalDescription(
            final boolean waitForCandidates) {
        this.setIsReadyToReceiveIceCandidates(false);
        return Futures.withTimeout(
                Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<SessionDescription> future = SettableFuture.create();
                    peerConnection.setLocalDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    if (waitForCandidates) {
                                        final var delay = getIceGatheringCompleteOrTimeout();
                                        final var delayedSessionDescription =
                                                Futures.transformAsync(
                                                        delay,
                                                        v -> {
                                                            iceCandidates.clear();
                                                            return getLocalDescriptionFuture();
                                                        },
                                                        MoreExecutors.directExecutor());
                                        future.setFuture(delayedSessionDescription);
                                    } else {
                                        future.setFuture(getLocalDescriptionFuture());
                                    }
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            });
                    return future;
                },
                        MoreExecutors.directExecutor()),
                WEBRTC_OPERATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE);
    }

    private ListenableFuture<Void> getIceGatheringCompleteOrTimeout() {
        final SettableFuture<Void> current;
        synchronized (iceGatheringLock) {
            current = iceGatheringComplete;
        }
        return Futures.catching(
                Futures.withTimeout(
                        current,
                        2,
                        TimeUnit.SECONDS,
                        JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE),
                TimeoutException.class,
                ex -> {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "timeout while waiting for ICE gathering to complete");
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    private void resetIceGatheringComplete() {
        synchronized (iceGatheringLock) {
            iceGatheringComplete = SettableFuture.create();
        }
    }

    private void signalIceGatheringComplete() {
        synchronized (iceGatheringLock) {
            if (!iceGatheringComplete.isDone()) {
                iceGatheringComplete.set(null);
            }
        }
    }

    private ListenableFuture<SessionDescription> getLocalDescriptionFuture() {
        return Futures.submit(
                () -> {
                    final SessionDescription description =
                            requirePeerConnection().getLocalDescription();
                    Log.d(EXTENDED_LOGGING_TAG, "local description:");
                    logDescription(description);
                    return description;
                },
                localDescriptionExecutorService);
    }

    public static void logDescription(final SessionDescription sessionDescription) {
        for (final String line :
                sessionDescription.description.split(
                        eu.siacs.conversations.xmpp.jingle.SessionDescription.LINE_DIVIDER)) {
            Log.d(EXTENDED_LOGGING_TAG, line);
        }
    }

    synchronized ListenableFuture<Void> setRemoteDescription(
            final SessionDescription sessionDescription) {
        Log.d(EXTENDED_LOGGING_TAG, "setting remote description:");
        logDescription(sessionDescription);
        return Futures.withTimeout(
                Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<Void> future = SettableFuture.create();
                    peerConnection.setRemoteDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    future.set(null);
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            },
                            sessionDescription);
                    return future;
                },
                        MoreExecutors.directExecutor()),
                WEBRTC_OPERATION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE);
    }

    @Nonnull
    private ListenableFuture<PeerConnection> getPeerConnectionFuture() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            return Futures.immediateFailedFuture(new PeerConnectionNotInitialized());
        } else {
            return Futures.immediateFuture(peerConnection);
        }
    }

    @Nonnull
    private PeerConnection requirePeerConnection() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            throw new PeerConnectionNotInitialized();
        }
        return peerConnection;
    }

    public boolean applyDtmfTone(String tone) {
        final TrackWrapper<AudioTrack> audio = localAudioTrack;
        if (peerConnection == null || audio == null) {
            return false;
        }
        try {
            audio.rtpSender.dtmf().insertDtmf(tone, TONE_DURATION, 100);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(EXTENDED_LOGGING_TAG, "audio sender vanished while applying DTMF");
            return false;
        }
    }

    @Nonnull
    private PeerConnectionFactory requirePeerConnectionFactory() {
        final PeerConnectionFactory peerConnectionFactory = this.peerConnectionFactory;
        if (peerConnectionFactory == null) {
            throw new IllegalStateException("Make sure PeerConnectionFactory is initialized");
        }
        return peerConnectionFactory;
    }

    void addIceCandidate(IceCandidate iceCandidate) {
        final PeerConnection currentPeerConnection = this.peerConnection;
        if (currentPeerConnection == null) {
            return;
        }
        try {
            currentPeerConnection.addIceCandidate(iceCandidate);
        } catch (final IllegalStateException e) {
            Log.d(EXTENDED_LOGGING_TAG, "ignoring late ICE candidate during teardown");
        }
    }

    PeerConnection.PeerConnectionState getState() {
        final PeerConnection currentPeerConnection = this.peerConnection;
        if (currentPeerConnection == null) {
            throw new PeerConnectionNotInitialized();
        }
        try {
            return currentPeerConnection.connectionState();
        } catch (final IllegalStateException e) {
            throw new PeerConnectionNotInitialized();
        }
    }

    public PeerConnection.SignalingState getSignalingState() {
        try {
            return requirePeerConnection().signalingState();
        } catch (final IllegalStateException e) {
            return PeerConnection.SignalingState.CLOSED;
        }
    }

    EglBase.Context getEglBaseContext() {
        return this.eglBase.getEglBaseContext();
    }

    Optional<VideoTrack> getLocalVideoTrack() {
        return TrackWrapper.get(peerConnection, this.localVideoTrack);
    }

    Optional<VideoTrack> getRemoteVideoTrack() {
        return Optional.fromNullable(this.remoteVideoTrack);
    }

    private Context requireContext() {
        final Context context = this.context;
        if (context == null) {
            throw new IllegalStateException("call setup first");
        }
        return context;
    }

    void execute(final Runnable command) {
        this.executorService.execute(command);
    }

    public interface EventCallback {
        void onIceCandidate(IceCandidate iceCandidate);

        void onConnectionChange(PeerConnection.PeerConnectionState newState);

        void onRenegotiationNeeded();
    }

    public abstract static class SetSdpObserver implements SdpObserver {

        @Override
        public void onCreateSuccess(org.webrtc.SessionDescription sessionDescription) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }

        @Override
        public void onCreateFailure(String s) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }
    }

    static class InitializationException extends Exception {

        private InitializationException(final String message, final Throwable throwable) {
            super(message, throwable);
        }

        private InitializationException(final String message) {
            super(message);
        }
    }

    public static class PeerConnectionNotInitialized extends IllegalStateException {

        public PeerConnectionNotInitialized() {
            super("initialize PeerConnection first");
        }
    }

    public static class FailureToSetDescriptionException extends IllegalArgumentException {
        public FailureToSetDescriptionException(String message) {
            super(message);
        }
    }
}
