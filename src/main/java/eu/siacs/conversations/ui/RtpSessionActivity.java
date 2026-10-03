package eu.siacs.conversations.ui;

import static java.util.Arrays.asList;
import static eu.siacs.conversations.utils.PermissionUtils.getFirstDenied;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.opengl.GLException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.jetbrains.annotations.NotNull;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityRtpSessionBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MainThreadExecutor;
import eu.siacs.conversations.ui.util.Rationals;
import eu.siacs.conversations.utils.PermissionUtils;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.ContentAddition;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;

public class RtpSessionActivity extends XmppActivity
        implements XmppConnectionService.OnJingleRtpConnectionUpdate,
        eu.siacs.conversations.ui.widget.SurfaceViewRenderer.OnAspectRatioChanged {

    public static final String EXTRA_WITH = "with";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_PROPOSED_SESSION_ID = "proposed_session_id";
    public static final String EXTRA_LAST_REPORTED_STATE = "last_reported_state";
    public static final String EXTRA_LAST_ACTION = "last_action";
    public static final String ACTION_ACCEPT_CALL = "action_accept_call";
    public static final String ACTION_MAKE_VOICE_CALL = "action_make_voice_call";
    public static final String ACTION_MAKE_VIDEO_CALL = "action_make_video_call";

    @Override
    protected boolean isAppLockProtected() {
        return false;
    }

    private static final int CALL_DURATION_UPDATE_INTERVAL = 250;
    private static final int BUTTON_VISIBILITY_TIMEOUT = 10_000;

    public static final List<RtpEndUserState> END_CARD =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.SECURITY_ERROR,
                    RtpEndUserState.DECLINED_OR_BUSY,
                    RtpEndUserState.CONTACT_OFFLINE,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                    RtpEndUserState.RETRACTED);
    private static final List<RtpEndUserState> STATES_SHOWING_HELP_BUTTON =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.SECURITY_ERROR);
    private static final List<RtpEndUserState> STATES_SHOWING_SWITCH_TO_CHAT =
            Arrays.asList(
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.CONNECTED,
                    RtpEndUserState.RECONNECTING,
                    RtpEndUserState.INCOMING_CONTENT_ADD);
    private static final List<RtpEndUserState> STATES_CONSIDERED_CONNECTED =
            Arrays.asList(RtpEndUserState.CONNECTED, RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_PIP_PLACEHOLDER =
            Arrays.asList(
                    RtpEndUserState.ACCEPTING_CALL,
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_SPEAKER_CONFIGURATION =
            new ImmutableList.Builder<RtpEndUserState>()
                    .add(RtpEndUserState.FINDING_DEVICE)
                    .add(RtpEndUserState.RINGING)
                    .add(RtpEndUserState.ACCEPTING_CALL)
                    .add(RtpEndUserState.CONNECTING)
                    .addAll(STATES_CONSIDERED_CONNECTED)
                    .build();
    private static final String PROXIMITY_WAKE_LOCK_TAG = "conversations:in-rtp-session";
    private static final int REQUEST_ACCEPT_CALL = 0x1111;
    private static final int REQUEST_ACCEPT_CONTENT = 0x1112;
    private static final int REQUEST_ADD_CONTENT = 0x1113;
    private WeakReference<JingleRtpConnection> rtpConnectionReference;

    private ActivityRtpSessionBinding binding;
    private PowerManager.WakeLock mProximityWakeLock;

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    updateCallDuration();
                    mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
                }
            };
    private boolean buttonsHiddenAfterTimeout = false;
    private final Runnable mVisibilityToggleExecutor = this::updateButtonInVideoCallVisibility;
    private final AtomicLong uiEventSequence = new AtomicLong();
    private boolean establishedUiRendered;
    private String renderedSessionId;
    private int callSystemTopInset = 0;
    @Nullable private Float callAvatarAnchorCenterY;
    private int callAvatarAnchorRootWidth = -1;
    private int callAvatarAnchorRootHeight = -1;

    public static Set<Media> actionToMedia(final String action) {
        if (ACTION_MAKE_VIDEO_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO, Media.VIDEO);
        } else if (ACTION_MAKE_VOICE_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO);
        } else {
            Log.w(
                    Config.LOGTAG,
                    "actionToMedia can not get media set from unknown action " + action);
            return Collections.emptySet();
        }
    }

    private static void addSink(
            final VideoTrack videoTrack, final SurfaceViewRenderer surfaceViewRenderer) {
        try {
            videoTrack.addSink(surfaceViewRenderer);
        } catch (final IllegalStateException e) {
            Log.e(
                    Config.LOGTAG,
                    "possible race condition on trying to display video track. ignoring",
                    e);
        }
    }

    private static void removeSink(
            final VideoTrack videoTrack, final SurfaceViewRenderer surfaceViewRenderer) {
        try {
            videoTrack.removeSink(surfaceViewRenderer);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "video track already disposed while removing sink");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            keyguardManager.requestDismissKeyguard(this, null);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow()
                    .addFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_rtp_session);
        this.binding.remoteVideo.setOnClickListener(this::onVideoScreenClick);
        this.binding.localVideo.setOnClickListener(this::onVideoScreenClick);
        this.binding.localVideo.setRoundedCornerRadius(
                getResources().getDimension(R.dimen.call_local_video_corner_radius));
        this.binding.videoChatButton.setOnClickListener(ignored -> switchToConversation());
        this.binding.videoAudioOnlyButton.setOnClickListener(this::downgradeToAudio);
        applySystemBarInsets();
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

    }

    private void applySystemBarInsets() {
        final int baseControlsBottom =
                getResources().getDimensionPixelSize(R.dimen.call_controls_margin_bottom);
        binding.callRoot.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    callSystemTopInset = insets.getSystemWindowInsetTop();
                    final int bottomInset = insets.getSystemWindowInsetBottom();

                    final RelativeLayout.LayoutParams controlsParams =
                            (RelativeLayout.LayoutParams) binding.buttonRow.getLayoutParams();
                    controlsParams.bottomMargin = baseControlsBottom + bottomInset;
                    binding.buttonRow.setLayoutParams(controlsParams);

                    updateCallContentTopInset();
                    return insets;
                });
        binding.callRoot.requestApplyInsets();
    }

    private void onVideoScreenClick(final View view) {
        if (!isInConnectedVideoCall() || isPictureInPicture()) {
            return;
        }
        if (buttonsHiddenAfterTimeout) {
            resetVisibilityExecutorShowButtons();
        } else {
            mHandler.removeCallbacks(mVisibilityToggleExecutor);
            hideInCallButtons();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_rtp_session, menu);
        final MenuItem help = menu.findItem(R.id.action_help);
        final MenuItem gotoChat = menu.findItem(R.id.action_goto_chat);
        help.setVisible(Config.HELP != null && isHelpButtonVisible());
        gotoChat.setVisible(isSwitchToConversationVisible());
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (xmppConnectionService != null) {
                if (xmppConnectionService.getNotificationService().stopSoundAndVibration()) {
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isHelpButtonVisible() {
        try {
            return STATES_SHOWING_HELP_BUTTON.contains(requireRtpConnection().getEndUserState());
        } catch (IllegalStateException e) {
            final Intent intent = getIntent();
            final String state =
                    intent != null ? intent.getStringExtra(EXTRA_LAST_REPORTED_STATE) : null;
            if (state != null) {
                return STATES_SHOWING_HELP_BUTTON.contains(RtpEndUserState.valueOf(state));
            } else {
                return false;
            }
        }
    }

    public static Intent createOngoingCallIntent(
            final Context context, final OngoingRtpSession session) {
        final Intent intent = new Intent(context, RtpSessionActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, session.getAccount().getJid().asBareJid().toString());
        intent.putExtra(EXTRA_WITH, session.getWith().toString());
        if (session instanceof JingleRtpConnection) {
            intent.putExtra(EXTRA_SESSION_ID, session.getSessionId());
        } else {
            intent.putExtra(EXTRA_PROPOSED_SESSION_ID, session.getSessionId());
            intent.putExtra(
                    EXTRA_LAST_ACTION,
                    session.getMedia().contains(Media.VIDEO)
                            ? ACTION_MAKE_VIDEO_CALL
                            : ACTION_MAKE_VOICE_CALL);
        }
        return intent;
    }

    private boolean isSwitchToConversationVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        return connection != null
                && STATES_SHOWING_SWITCH_TO_CHAT.contains(connection.getEndUserState());
    }

    private void switchToConversation() {
        final Contact contact = getWith();
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), null, false, false, true, null);
        final Intent intent = new Intent(this, ConversationsActivity.class);
        intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    public boolean onOptionsItemSelected(final MenuItem item) {
        final var itemItem = item.getItemId();
        if (itemItem == R.id.action_help) {
            launchHelpInBrowser();
            return true;
        } else if (itemItem == R.id.action_goto_chat) {
            switchToConversation();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void launchHelpInBrowser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Config.HELP);
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void endCall(View view) {
        endCall();
    }

    private void endCall() {
        if (this.rtpConnectionReference == null) {
            retractSessionProposal();
            finish();
        } else {
            requireRtpConnection().endCall();
        }
    }

    private void retractSessionProposal() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final Account account = extractAccount(intent);
        final Jid with = Jid.of(intent.getStringExtra(EXTRA_WITH));
        final String state = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
        if (!Intent.ACTION_VIEW.equals(action)
                || state == null
                || !END_CARD.contains(RtpEndUserState.valueOf(state))) {
            final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
            resetIntent(account, with, RtpEndUserState.RETRACTED, media);
        }
        xmppConnectionService
                .getJingleConnectionManager()
                .retractSessionProposal(account, with.asBareJid());
    }

    private void rejectCall(View view) {
        requireRtpConnection().rejectCall();
        finish();
    }

    private void acceptCall(View view) {
        requestPermissionsAndAcceptCall();
    }

    private void acceptContentAdd() {
        try {
            final ContentAddition pendingContentAddition =
                    requireRtpConnection().getPendingContentAddition();
            if (pendingContentAddition == null) {
                Log.d(Config.LOGTAG, "content offer was gone after granting permission");
                return;
            }
            requireRtpConnection().acceptContentAdd(pendingContentAddition.summary);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "content offer is no longer available", e);
        }
    }

    private void requestPermissionAndSwitchToVideo() {
        final List<String> permissions = permissions(ImmutableSet.of(Media.VIDEO, Media.AUDIO));
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ADD_CONTENT)) {
            switchToVideo();
        }
    }

    private void switchToVideo() {
        try {
            requireRtpConnection().addMedia(Media.VIDEO);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to add video to call", e);
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void acceptContentAdd(final ContentAddition contentAddition) {
        if (contentAddition == null
                || contentAddition.direction != ContentAddition.Direction.INCOMING) {
            Log.d(Config.LOGTAG, "ignore press on content-accept button");
            return;
        }
        requestPermissionAndAcceptContentAdd(contentAddition);
    }

    private void requestPermissionAndAcceptContentAdd(final ContentAddition contentAddition) {
        final List<String> permissions = permissions(contentAddition.media());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CONTENT)) {
            try {
                requireRtpConnection().acceptContentAdd(contentAddition.summary);
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "incoming content offer is no longer available", e);
            }
        }
    }

    private void rejectContentAdd(final View view) {
        requireRtpConnection().rejectContentAdd();
    }

    private void requestPermissionsAndAcceptCall() {
        final List<String> permissions = permissions(getMedia());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CALL)) {
            putScreenInCallMode();
            acceptCall();
        }
    }

    private List<String> permissions(final Set<Media> media) {
        final ImmutableList.Builder<String> permissions = ImmutableList.builder();
        if (media.contains(Media.VIDEO)) {
            permissions.add(Manifest.permission.CAMERA).add(Manifest.permission.RECORD_AUDIO);
        } else {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return permissions.build();
    }

    private void acceptCall() {
        try {
            requireRtpConnection().acceptCall();
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "call is no longer available while accepting", e);
        }
    }

    private void putScreenInCallMode() {
        putScreenInCallMode(requireRtpConnection().getMedia());
    }

    private void putScreenInCallMode(final Set<Media> media) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!media.contains(Media.VIDEO)) {
            final JingleRtpConnection rtpConnection =
                    rtpConnectionReference != null ? rtpConnectionReference.get() : null;
            final CallIntegration callIntegration =
                    rtpConnection == null ? null : rtpConnection.getCallIntegration();
            if (callIntegration == null
                    || callIntegration.getSelectedAudioDevice()
                    == CallIntegration.AudioDevice.EARPIECE) {
                acquireProximityWakeLock();
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private void acquireProximityWakeLock() {
        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            Log.e(Config.LOGTAG, "power manager not available");
            return;
        }
        if (isFinishing()) {
            Log.e(Config.LOGTAG, "do not acquire wakelock. activity is finishing");
            return;
        }
        if (this.mProximityWakeLock == null) {
            this.mProximityWakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_WAKE_LOCK_TAG);
        }
        if (!this.mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "acquiring proximity wake lock");
            this.mProximityWakeLock.acquire();
        }
    }

    private void releaseProximityWakeLock() {
        if (this.mProximityWakeLock != null && mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "releasing proximity wake lock");
            this.mProximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            this.mProximityWakeLock = null;
        }
    }

    private void putProximityWakeLockInProperState(final CallIntegration.AudioDevice audioDevice) {
        if (audioDevice == CallIntegration.AudioDevice.EARPIECE) {
            acquireProximityWakeLock();
        } else {
            releaseProximityWakeLock();
        }
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    public void onNewIntent(final Intent intent) {
        Log.d(Config.LOGTAG, this.getClass().getName() + ".onNewIntent()");
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        if (xmppConnectionService == null) {
            Log.d(
                    Config.LOGTAG,
                    "RtpSessionActivity: background service wasn't bound in onNewIntent()");
            return;
        }
        initializeWithIntent(Event.ON_NEW_INTENT, intent);
    }

    @Override
    protected void onBackendConnected() {
        final var intent = getIntent();
        if (intent == null) {
            return;
        }
        initializeWithIntent(Event.ON_BACKEND_CONNECTED, intent);
    }

    private void initializeWithIntent(final Event event, @NonNull final Intent intent) {
        final String action = intent.getAction();
        Log.d(Config.LOGTAG, "initializeWithIntent(" + event + "," + action + ")");
        final Account account = extractAccount(intent);
        final var extraWith = intent.getStringExtra(EXTRA_WITH);
        final Jid with = Strings.isNullOrEmpty(extraWith) ? null : Jid.of(extraWith);
        if (with == null || account == null) {
            Log.e(Config.LOGTAG, "intent is missing extras (account or with)");
            return;
        }
        final String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null) {
            if (initializeActivityWithRunningRtpSession(account, with, sessionId)) {
                return;
            }
            if (ACTION_ACCEPT_CALL.equals(intent.getAction())) {
                Log.d(Config.LOGTAG, "intent action was accept");
                requestPermissionsAndAcceptCall();
                resetIntent(intent.getExtras());
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            final String proposedSessionId = intent.getStringExtra(EXTRA_PROPOSED_SESSION_ID);
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, proposedSessionId);
            if (terminatedRtpSession != null) {
                // termination (due to message error or 'busy' was faster than opening the activity
                initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
                return;
            }
            final String extraLastState = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
            final RtpEndUserState state =
                    extraLastState == null ? null : RtpEndUserState.valueOf(extraLastState);
            final var contact = account.getRoster().getContact(with);
            if (state != null) {
                Log.d(Config.LOGTAG, "restored last state from intent extra");
                updateButtonConfiguration(state);
                updateVerifiedShield(false);
                updateStateDisplay(state);
                updateIncomingCallScreen(state, contact);
                invalidateOptionsMenu();
            }
            setWith(state, contact);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .fireJingleRtpConnectionStateUpdates()) {
                return;
            }
            if (END_CARD.contains(state)) {
                return;
            }
            final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
            final Set<Media> media = actionToMedia(lastAction);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .hasMatchingProposal(account, with)) {
                putScreenInCallMode(media);
                return;
            }
            Log.d(Config.LOGTAG, "restored state (" + state + ") was not an end card. finishing");
            finish();
        }
    }

    private void setWith(final RtpEndUserState state) {
        setWith(state, getWith());
    }

    private void setWith(final RtpEndUserState state, final Contact contact) {
        final String displayName = contact.getDisplayName();
        final String bareJid = contact.getJid().asBareJid().toString();
        binding.with.setText(displayName);
        binding.withJid.setVisibility(View.GONE);

        binding.incomingCallName.setText(displayName);
        if (contact.showInContactList()) {
            binding.incomingCallJid.setVisibility(View.GONE);
        } else {
            binding.incomingCallJid.setText(bareJid);
            binding.incomingCallJid.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final PermissionUtils.PermissionResult permissionResult =
                PermissionUtils.removeBluetoothConnect(permissions, grantResults);
        if (PermissionUtils.allGranted(permissionResult.grantResults)) {
            if (requestCode == REQUEST_ACCEPT_CALL) {
                acceptCall();
            } else if (requestCode == REQUEST_ACCEPT_CONTENT) {
                acceptContentAdd();
            } else if (requestCode == REQUEST_ADD_CONTENT) {
                switchToVideo();
            }
        } else {
            @StringRes int res;
            final String firstDenied =
                    getFirstDenied(permissionResult.grantResults, permissionResult.permissions);
            if (firstDenied == null) {
                return;
            }
            if (Manifest.permission.RECORD_AUDIO.equals(firstDenied)) {
                res = R.string.no_microphone_permission;
            } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                res = R.string.no_camera_permission;
            } else {
                throw new IllegalStateException("Invalid permission result request");
            }
            Toast.makeText(this, getString(res, getString(R.string.app_name)), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
        mHandler.postDelayed(mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT);
        this.binding.remoteVideo.setOnAspectRatioChanged(this);

        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection != null) {
            final RtpEndUserState state = connection.getEndUserState();
            if (state == RtpEndUserState.ENDED || END_CARD.contains(state)) {
                return;
            }
            renderLiveCallState(
                    connection,
                    "onStart",
                    state,
                    uiEventSequence.incrementAndGet(),
                    getWith());
            updateCallDuration();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection != null) {
            connection.recordUiDiagnostic(
                    "lifecycle onResume state=" + connection.getEndUserState());
        }
        resetVisibilityExecutorShowButtons();
    }

    @Override
    public void onStop() {
        final JingleRtpConnection lifecycleConnection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (lifecycleConnection != null) {
            lifecycleConnection.recordUiDiagnostic(
                    "lifecycle onStop state=" + lifecycleConnection.getEndUserState());
        }
        mHandler.removeCallbacks(mTickExecutor);
        mHandler.removeCallbacks(mVisibilityToggleExecutor);
        final WeakReference<JingleRtpConnection> weakReference = this.rtpConnectionReference;
        final JingleRtpConnection jingleRtpConnection =
                weakReference == null ? null : weakReference.get();
        if (jingleRtpConnection != null) {
            releaseVideoTracks(jingleRtpConnection);
        }
        binding.remoteVideo.setOnAspectRatioChanged(null);
        binding.remoteVideo.release();
        binding.localVideo.release();
        releaseProximityWakeLock();
        super.onStop();
    }

    private void releaseVideoTracks(final JingleRtpConnection jingleRtpConnection) {
        final Optional<VideoTrack> remoteVideo = jingleRtpConnection.getRemoteVideoTrack();
        if (remoteVideo.isPresent()) {
            removeSink(remoteVideo.get(), binding.remoteVideo);
        }
        final Optional<VideoTrack> localVideo = jingleRtpConnection.getLocalVideoTrack();
        if (localVideo.isPresent()) {
            removeSink(localVideo.get(), binding.localVideo);
        }
    }

    @Override
    public void onBackPressed() {
        if (switchToPictureInPicture()) {
            return;
        }
        // Navigation is presentation-only. Ending or retracting a call is reserved for
        // explicit hang-up/reject actions and terminal Jingle state.
        super.onBackPressed();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Home/task-switch navigation must not own the Jingle session lifecycle.
        switchToPictureInPicture();
    }

    private boolean isConnected() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        final RtpEndUserState endUserState =
                connection == null ? null : connection.getEndUserState();
        if (connection == null || endUserState == null) {
            return false;
        }
        return CallUiState.from(
                        endUserState,
                        connection.getMedia(),
                        connection.getPendingContentAddition())
                .isEstablished();
    }

    private boolean switchToPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceSupportsPictureInPicture()) {
            if (shouldBePictureInPicture()) {
                startPictureInPicture();
                return true;
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startPictureInPicture() {
        try {
            final Rational rational = this.binding.remoteVideo.getAspectRatio();
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational " + rational + ". clipped to " + clippedRational);
            enterPictureInPictureMode(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        } catch (final IllegalStateException e) {
            // this sometimes happens on Samsung phones (possibly when Knox is enabled)
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e);
        }
    }

    @Override
    public void onAspectRatioChanged(final Rational rational) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPictureInPicture()) {
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational after aspect ratio change "
                            + rational
                            + ". clipped to "
                            + clippedRational);
            setPictureInPictureParams(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        }
    }

    private boolean deviceSupportsPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } else {
            return false;
        }
    }

    private boolean shouldBePictureInPicture() {
        try {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            return rtpConnection.getPresentationMedia().contains(Media.VIDEO)
                    && Arrays.asList(
                            RtpEndUserState.ACCEPTING_CALL,
                            RtpEndUserState.CONNECTING,
                            RtpEndUserState.CONNECTED,
                            RtpEndUserState.RECONNECTING)
                    .contains(rtpConnection.getEndUserState());
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    private boolean isInConnectedVideoCall() {
        final JingleRtpConnection rtpConnection;
        try {
            rtpConnection = requireRtpConnection();
        } catch (final IllegalStateException e) {
            return false;
        }
        return rtpConnection.getPresentationMedia().contains(Media.VIDEO)
                && rtpConnection.getEndUserState() == RtpEndUserState.CONNECTED;
    }

    private boolean initializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        final WeakReference<JingleRtpConnection> reference =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .findJingleRtpConnection(account, with, sessionId);
        if (reference == null || reference.get() == null) {
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, sessionId);
            if (terminatedRtpSession == null) {
                Log.e(Config.LOGTAG, "failed to initialize activity with running rtp session. session not found");
                finish();
                return true;
            }
            initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
            return true;
        }
        this.rtpConnectionReference = reference;
        final RtpEndUserState currentState = requireRtpConnection().getEndUserState();
        if (currentState == RtpEndUserState.ENDED) {
            finish();
            return true;
        }
        if (currentState == RtpEndUserState.INCOMING_CALL) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(
                requireRtpConnection().getState())) {
            putScreenInCallMode();
        }
        setWith(currentState);
        renderLiveCallState(
                requireRtpConnection(),
                "initialize",
                currentState,
                uiEventSequence.incrementAndGet(),
                getWith());
        return false;
    }

    private void initializeWithTerminatedSessionState(
            final Account account,
            final Jid with,
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession) {
        Log.d(Config.LOGTAG, "initializeWithTerminatedSessionState()");
        if (terminatedRtpSession.state == RtpEndUserState.ENDED) {
            finish();
            return;
        }
        final RtpEndUserState state = terminatedRtpSession.state;
        final var contact = account.getRoster().getContact(with);
        resetIntent(account, with, terminatedRtpSession.state, terminatedRtpSession.media);
        setWith(state, contact);
        updateButtonConfiguration(state);
        updateStateDisplay(state);
        updateIncomingCallScreen(state, contact);
        updateCallDuration();
        updateVerifiedShield(false);
        invalidateOptionsMenu();
    }

    private void reInitializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        runOnUiThread(() -> initializeActivityWithRunningRtpSession(account, with, sessionId));
        resetIntent(account, with, sessionId);
    }

    private void resetIntent(final Account account, final Jid with, final String sessionId) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toString());
        intent.putExtra(EXTRA_WITH, with.toString());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        setIntent(intent);
    }

    private void ensureSurfaceViewRendererIsSetup(final SurfaceViewRenderer surfaceViewRenderer) {
        surfaceViewRenderer.setVisibility(View.VISIBLE);
        try {
            surfaceViewRenderer.init(requireRtpConnection().getEglBaseContext(), null);
        } catch (final IllegalStateException ignored) {
            // SurfaceViewRenderer was already initialized
        } catch (final RuntimeException e) {
            if (Throwables.getRootCause(e) instanceof GLException glException) {
                Log.w(Config.LOGTAG, "could not set up hardware renderer", glException);
            }
        }
        surfaceViewRenderer.setEnableHardwareScaler(true);
    }

    private void renderLiveCallState(
            final JingleRtpConnection connection,
            final String source,
            final RtpEndUserState callbackState,
            final long sequence,
            final Contact contact) {
        final AbstractJingleConnection.Id id = connection.getId();
        if (!id.sessionId.equals(renderedSessionId)) {
            renderedSessionId = id.sessionId;
            establishedUiRendered = false;
            connection.recordUiDiagnostic(
                    "session-reset seq=" + sequence + " source=" + source);
        }

        final RtpEndUserState liveState = connection.getEndUserState();
        final Set<Media> media = connection.getPresentationMedia();
        final ContentAddition contentAddition = connection.getPendingContentAddition();
        final CallUiState uiState = CallUiState.from(liveState, media, contentAddition);

        connection.recordUiDiagnostic(
                "render-begin seq="
                        + sequence
                        + " source="
                        + source
                        + " callback="
                        + callbackState
                        + " live="
                        + liveState
                        + " mode="
                        + uiState
                        + " establishedSeen="
                        + establishedUiRendered);

        if (establishedUiRendered
                && (liveState == RtpEndUserState.INCOMING_CALL
                        || liveState == RtpEndUserState.ACCEPTING_CALL
                        || liveState == RtpEndUserState.FINDING_DEVICE
                        || liveState == RtpEndUserState.RINGING
                        || liveState == RtpEndUserState.CONNECTING)) {
            connection.recordUiDiagnostic(
                    "render-suppress-regression seq="
                            + sequence
                            + " live="
                            + liveState
                            + " callback="
                            + callbackState);
            return;
        }

        updateStateDisplay(liveState, media, contentAddition);
        updateVerifiedShield(
                connection.isVerified() && STATES_SHOWING_SWITCH_TO_CHAT.contains(liveState));
        updateButtonConfiguration(liveState, media, contentAddition);
        updateVideoViews(liveState);
        updateIncomingCallScreen(liveState, contact);
        invalidateOptionsMenu();

        if (uiState.isEstablished()) {
            establishedUiRendered = true;
        }
        connection.recordUiDiagnostic(
                "render-end seq="
                        + sequence
                        + " live="
                        + liveState
                        + " mode="
                        + uiState
                        + " incoming="
                        + binding.incomingCallStatus.getVisibility()
                        + " active="
                        + binding.activeCallStatus.getVisibility()
                        + " appbar="
                        + binding.appBarLayout.getVisibility());
    }

    private void updateStateDisplay(final RtpEndUserState state) {
        updateStateDisplay(state, Collections.emptySet(), null);
    }

    private void updateStateDisplay(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        switch (state) {
            case INCOMING_CALL -> {
                Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty");
                if (media.contains(Media.VIDEO)) {
                    setTitle(R.string.rtp_state_incoming_video_call);
                } else {
                    setTitle(R.string.rtp_state_incoming_call);
                }
            }
            case INCOMING_CONTENT_ADD -> {
                if (contentAddition != null && contentAddition.media().contains(Media.VIDEO)) {
                    setTitle(R.string.rtp_state_content_add_video);
                } else {
                    setTitle(R.string.rtp_state_content_add);
                }
            }
            case CONNECTING -> setTitle(R.string.rtp_state_connecting);
            case CONNECTED -> setTitle(R.string.rtp_state_connected);
            case RECONNECTING -> setTitle(R.string.rtp_state_reconnecting);
            case ACCEPTING_CALL -> setTitle(R.string.rtp_state_accepting_call);
            case ENDING_CALL -> setTitle(R.string.rtp_state_ending_call);
            case FINDING_DEVICE -> setTitle(R.string.rtp_state_finding_device);
            case RINGING -> setTitle(R.string.rtp_state_ringing);
            case DECLINED_OR_BUSY -> setTitle(R.string.rtp_state_declined_or_busy);
            case CONTACT_OFFLINE -> setTitle(R.string.rtp_state_contact_offline);
            case CONNECTIVITY_ERROR -> setTitle(R.string.rtp_state_connectivity_error);
            case CONNECTIVITY_LOST_ERROR -> setTitle(R.string.rtp_state_connectivity_lost_error);
            case RETRACTED -> setTitle(R.string.rtp_state_retracted);
            case APPLICATION_ERROR -> setTitle(R.string.rtp_state_application_failure);
            case SECURITY_ERROR -> setTitle(R.string.rtp_state_security_error);
            case ENDED ->
                    throw new IllegalStateException(
                            "Activity should have called finishAndReleaseWakeLock();");
            default ->
                    throw new IllegalStateException(
                            String.format("State %s has not been handled in UI", state));
        }
    }

    @StringRes
    private int callStateTitle(final RtpEndUserState state) {
        return switch (state) {
            case CONNECTING -> R.string.rtp_state_connecting;
            case CONNECTED -> R.string.rtp_state_connected;
            case RECONNECTING -> R.string.rtp_state_reconnecting;
            case ACCEPTING_CALL -> R.string.rtp_state_accepting_call;
            case ENDING_CALL -> R.string.rtp_state_ending_call;
            case FINDING_DEVICE -> R.string.rtp_state_finding_device;
            case RINGING -> R.string.rtp_state_ringing;
            case INCOMING_CONTENT_ADD -> R.string.rtp_state_content_add;
            case DECLINED_OR_BUSY -> R.string.rtp_state_declined_or_busy;
            case CONTACT_OFFLINE -> R.string.rtp_state_contact_offline;
            case CONNECTIVITY_ERROR -> R.string.rtp_state_connectivity_error;
            case CONNECTIVITY_LOST_ERROR -> R.string.rtp_state_connectivity_lost_error;
            case RETRACTED -> R.string.rtp_state_retracted;
            case APPLICATION_ERROR -> R.string.rtp_state_application_failure;
            case SECURITY_ERROR -> R.string.rtp_state_security_error;
            default -> throw new IllegalArgumentException("No call title for " + state);
        };
    }

    private void updateVerifiedShield(final boolean verified) {
        if (isPictureInPicture()) {
            this.binding.verified.setVisibility(View.GONE);
            return;
        }
        this.binding.verified.setVisibility(verified ? View.VISIBLE : View.GONE);
    }

    private void updateIncomingCallScreen(final RtpEndUserState state) {
        updateIncomingCallScreen(state, null);
    }

    private void updateIncomingCallScreen(final RtpEndUserState state, final Contact contact) {
        rememberCallAvatarAnchor();
        final boolean portrait = getResources().getBoolean(R.bool.is_portrait_mode);
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        final boolean activeVideo =
                connection != null
                        && connection.getPresentationMedia().contains(Media.VIDEO)
                        && (state == RtpEndUserState.CONNECTED
                                || state == RtpEndUserState.RECONNECTING);
        final boolean incomingCall = state == RtpEndUserState.INCOMING_CALL;
        final boolean acceptingCall = state == RtpEndUserState.ACCEPTING_CALL;
        final boolean terminal = END_CARD.contains(state);
        final boolean activeAudioIdentity =
                !activeVideo
                        && !incomingCall
                        && !acceptingCall
                        && !terminal
                        && state != RtpEndUserState.ENDING_CALL
                        && state != RtpEndUserState.ENDED;
        final boolean showContactPhoto =
                portrait
                        && !activeVideo
                        && !acceptingCall
                        && state != RtpEndUserState.ENDING_CALL
                        && state != RtpEndUserState.ENDED;

        binding.incomingCallStatus.setVisibility(View.GONE);
        binding.activeCallStatus.setVisibility(View.GONE);
        binding.incomingCallIdentity.setVisibility(View.GONE);

        if (incomingCall) {
            binding.appBarLayout.setVisibility(View.GONE);
            binding.incomingCallStatus.setVisibility(View.VISIBLE);
            binding.incomingCallIdentity.setVisibility(View.VISIBLE);
            if (connection != null && connection.getPresentationMedia().contains(Media.VIDEO)) {
                binding.incomingCallStatus.setText(R.string.rtp_state_incoming_video_call);
            } else {
                binding.incomingCallStatus.setText(R.string.rtp_state_incoming_call);
            }
        } else if (acceptingCall) {
            binding.appBarLayout.setVisibility(View.GONE);
            binding.incomingCallStatus.setVisibility(View.VISIBLE);
            binding.incomingCallStatus.setText(R.string.rtp_state_accepting_call);
        } else if (terminal) {
            binding.appBarLayout.setVisibility(View.GONE);
            binding.incomingCallIdentity.setVisibility(View.VISIBLE);
            binding.activeCallStatus.setVisibility(View.VISIBLE);
            binding.activeCallStatus.setText(callStateTitle(state));
        } else if (activeAudioIdentity) {
            binding.appBarLayout.setVisibility(View.VISIBLE);
            binding.incomingCallIdentity.setVisibility(View.VISIBLE);
            binding.activeCallStatus.setVisibility(View.VISIBLE);
            binding.activeCallStatus.setText(callStateTitle(state));
        } else {
            binding.appBarLayout.setVisibility(activeVideo ? View.GONE : View.VISIBLE);
        }

        updateCallContentTopInset();

        if (showContactPhoto) {
            binding.contactPhoto.setVisibility(View.VISIBLE);
            if (contact == null) {
                AvatarWorkerTask.loadAvatar(
                        getWith(), binding.contactPhoto, R.dimen.call_avatar_size);
            } else {
                AvatarWorkerTask.loadAvatar(
                        contact, binding.contactPhoto, R.dimen.call_avatar_size);
            }
        } else {
            binding.contactPhoto.setVisibility(View.GONE);
        }

        if (incomingCall) {
            final Account account = contact == null ? getWith().getAccount() : contact.getAccount();
            binding.usingAccount.setVisibility(View.VISIBLE);
            binding.usingAccount.setText(
                    getString(R.string.using_account, account.getJid().asBareJid().toString()));
        } else {
            binding.usingAccount.setVisibility(View.GONE);
        }

        if (showContactPhoto) {
            stabilizeCallAvatarPosition();
        }
    }

    private void updateCallContentTopInset() {
        if (binding == null) {
            return;
        }
        final RelativeLayout.LayoutParams params =
                (RelativeLayout.LayoutParams) binding.mainCallContent.getLayoutParams();
        final int safeTop =
                binding.appBarLayout.getVisibility() == View.VISIBLE ? 0 : callSystemTopInset;
        if (params.topMargin != safeTop) {
            params.topMargin = safeTop;
            binding.mainCallContent.setLayoutParams(params);
        }
    }

    private void rememberCallAvatarAnchor() {
        if (binding == null
                || binding.contactPhoto.getVisibility() != View.VISIBLE
                || binding.contactPhoto.getWidth() <= 0
                || binding.contactPhoto.getHeight() <= 0
                || binding.callRoot.getWidth() <= 0
                || binding.callRoot.getHeight() <= 0) {
            return;
        }

        final int rootWidth = binding.callRoot.getWidth();
        final int rootHeight = binding.callRoot.getHeight();
        if (callAvatarAnchorCenterY != null
                && (rootWidth != callAvatarAnchorRootWidth
                        || rootHeight != callAvatarAnchorRootHeight)) {
            // A real viewport change (rotation / window resize) establishes a new anchor.
            binding.callIdentityStack.setTranslationY(0f);
            callAvatarAnchorCenterY = null;
        }

        if (callAvatarAnchorCenterY == null) {
            final int[] location = new int[2];
            binding.contactPhoto.getLocationInWindow(location);
            callAvatarAnchorCenterY =
                    location[1] + binding.contactPhoto.getHeight() / 2f;
            callAvatarAnchorRootWidth = rootWidth;
            callAvatarAnchorRootHeight = rootHeight;
        }
    }

    private void stabilizeCallAvatarPosition() {
        if (binding == null) {
            return;
        }
        final View identityStack = binding.callIdentityStack;
        final ViewTreeObserver observer = identityStack.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        observer.addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        final ViewTreeObserver current = identityStack.getViewTreeObserver();
                        if (current.isAlive()) {
                            current.removeOnPreDrawListener(this);
                        }
                        if (binding == null
                                || binding.contactPhoto.getVisibility() != View.VISIBLE
                                || binding.contactPhoto.getWidth() <= 0
                                || binding.callRoot.getWidth() <= 0
                                || binding.callRoot.getHeight() <= 0) {
                            return true;
                        }

                        final int rootWidth = binding.callRoot.getWidth();
                        final int rootHeight = binding.callRoot.getHeight();
                        if (callAvatarAnchorCenterY != null
                                && (rootWidth != callAvatarAnchorRootWidth
                                        || rootHeight != callAvatarAnchorRootHeight)) {
                            identityStack.setTranslationY(0f);
                            callAvatarAnchorCenterY = null;
                        }

                        final int[] location = new int[2];
                        binding.contactPhoto.getLocationInWindow(location);
                        final float currentCenterY =
                                location[1] + binding.contactPhoto.getHeight() / 2f;

                        if (callAvatarAnchorCenterY == null) {
                            callAvatarAnchorCenterY = currentCenterY;
                            callAvatarAnchorRootWidth = rootWidth;
                            callAvatarAnchorRootHeight = rootHeight;
                            return true;
                        }

                        float delta = callAvatarAnchorCenterY - currentCenterY;
                        if (Math.abs(delta) > 0.5f) {
                            // Preserve the avatar anchor across state changes, but never let the
                            // translated identity stack escape the safe content viewport. Terminal
                            // states hide the app bar, so without this clamp RINGING ->
                            // DECLINED_OR_BUSY can retain an anchor underneath the top system bar.
                            final int[] stackLocation = new int[2];
                            final int[] contentLocation = new int[2];
                            identityStack.getLocationInWindow(stackLocation);
                            binding.mainCallContent.getLocationInWindow(contentLocation);

                            final float safeTop = contentLocation[1];
                            final float safeBottom =
                                    contentLocation[1] + binding.mainCallContent.getHeight();
                            float proposedTop = stackLocation[1] + delta;
                            float proposedBottom = proposedTop + identityStack.getHeight();

                            if (proposedTop < safeTop) {
                                delta += safeTop - proposedTop;
                                proposedTop = safeTop;
                                proposedBottom = proposedTop + identityStack.getHeight();
                            }
                            if (proposedBottom > safeBottom) {
                                delta -= proposedBottom - safeBottom;
                            }

                            if (Math.abs(delta) > 0.5f) {
                                identityStack.setTranslationY(
                                        identityStack.getTranslationY() + delta);
                                return false;
                            }
                        }
                        return true;
                    }
                });
    }

    private Set<Media> getMedia() {
        return requireRtpConnection().getPresentationMedia();
    }

    public ContentAddition getPendingContentAddition() {
        return requireRtpConnection().getPendingContentAddition();
    }

    private void updateButtonConfiguration(final RtpEndUserState state) {
        updateButtonConfiguration(state, Collections.emptySet(), null);
    }

    @SuppressLint("RestrictedApi")
    private void updateButtonConfiguration(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        final boolean incomingCall = state == RtpEndUserState.INCOMING_CALL;
        final boolean acceptingCall = state == RtpEndUserState.ACCEPTING_CALL;
        final boolean incomingPresentation = incomingCall || acceptingCall;
        final boolean terminalPresentation = END_CARD.contains(state);
        binding.incomingCallActions.setVisibility(incomingCall ? View.VISIBLE : View.GONE);
        binding.terminalCallActions.setVisibility(
                terminalPresentation ? View.VISIBLE : View.GONE);
        binding.terminalExit.setContentDescription(getString(R.string.close_call_screen));
        binding.terminalExit.setOnClickListener(this::exit);
        binding.terminalRetry.setContentDescription(getString(R.string.try_again));
        binding.terminalRetry.setOnClickListener(this::retry);
        binding.inCallControls.setVisibility(
                incomingPresentation || terminalPresentation || buttonsHiddenAfterTimeout
                        ? View.GONE
                        : View.VISIBLE);
        if (incomingPresentation || terminalPresentation) {
            binding.endCallContainer.setVisibility(View.GONE);
        }
        if (state == RtpEndUserState.ENDING_CALL || isPictureInPicture()) {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CALL) {
            this.binding.rejectCall.setContentDescription(getString(R.string.dismiss_call));
            this.binding.rejectCall.setOnClickListener(this::rejectCall);
            this.binding.rejectCall.setImageResource(R.drawable.ic_call_end_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.answer_call));
            this.binding.acceptCall.setOnClickListener(this::acceptCall);
            this.binding.acceptCall.setImageResource(R.drawable.ic_call_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (isIncomingVideoOffer(state, contentAddition)) {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setContentDescription(getString(R.string.hang_up));
            this.binding.endCall.setOnClickListener(this::endCall);
            this.binding.endCall.setImageResource(R.drawable.ic_call_end_24dp);
            setVisibleAndShow(this.binding.endCall);
        } else if (state == RtpEndUserState.INCOMING_CONTENT_ADD) {
            this.binding.rejectCall.setContentDescription(
                    getString(R.string.reject_switch_to_video));
            this.binding.rejectCall.setOnClickListener(this::rejectContentAdd);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.accept));
            this.binding.acceptCall.setOnClickListener((v -> acceptContentAdd(contentAddition)));
            this.binding.acceptCall.setImageResource(R.drawable.ic_check_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (terminalPresentation) {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        } else {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setContentDescription(getString(R.string.hang_up));
            this.binding.endCall.setOnClickListener(this::endCall);
            this.binding.endCall.setImageResource(R.drawable.ic_call_end_24dp);
            setVisibleAndShow(this.binding.endCall);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        }
        updateIncomingVideoOffer(state, contentAddition);
        updateOutgoingVideoUpgrade(state, media, contentAddition);
        updateInCallButtonConfiguration(state, media, contentAddition);
        updateVideoChrome(state, media);
        updateReconnectOverlay(state, media);
        updateCallControlLabelVisibility();
        if (incomingPresentation || terminalPresentation) {
            binding.inCallControls.setVisibility(View.GONE);
            binding.endCallContainer.setVisibility(View.GONE);
            binding.incomingCallActions.setVisibility(incomingCall ? View.VISIBLE : View.GONE);
            binding.terminalCallActions.setVisibility(
                    terminalPresentation ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isIncomingVideoOffer(
            final RtpEndUserState state, final ContentAddition contentAddition) {
        return state == RtpEndUserState.INCOMING_CONTENT_ADD
                && contentAddition != null
                && contentAddition.direction == ContentAddition.Direction.INCOMING
                && contentAddition.media().contains(Media.VIDEO);
    }

    private void updateIncomingVideoOffer(
            final RtpEndUserState state, final ContentAddition contentAddition) {
        if (!isIncomingVideoOffer(state, contentAddition) || isPictureInPicture()) {
            binding.incomingVideoOffer.setVisibility(View.GONE);
            return;
        }
        binding.incomingVideoOfferText.setText(
                getString(R.string.call_video_offer, getWith().getDisplayName()));
        binding.incomingVideoOfferReject.setOnClickListener(this::rejectContentAdd);
        binding.incomingVideoOfferAccept.setOnClickListener(
                ignored -> acceptContentAdd(contentAddition));
        binding.incomingVideoOffer.setVisibility(View.VISIBLE);
    }

    private void updateOutgoingVideoUpgrade(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        final CallUiState uiState = CallUiState.from(state, media, contentAddition);
        if (uiState != CallUiState.ADDING_VIDEO || isPictureInPicture()) {
            binding.outgoingVideoUpgrade.setVisibility(View.GONE);
            return;
        }
        binding.outgoingVideoUpgradeCancel.setOnClickListener(
                ignored -> {
                    try {
                        requireRtpConnection().retractContentAdd();
                    } catch (final IllegalStateException e) {
                        Toast.makeText(
                                        this,
                                        R.string.could_not_modify_call,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                });
        binding.outgoingVideoUpgrade.setVisibility(View.VISIBLE);
    }

    private static void setVisibleAndShow(final FloatingActionButton button) {
        button.show();
        button.setVisibility(View.VISIBLE);
    }

    private void updateCallControlLabelVisibility() {
        final boolean routeVisible = binding.inCallAudioRoute.getVisibility() == View.VISIBLE;
        final boolean micVisible = binding.inCallActionLeft.getVisibility() == View.VISIBLE;
        final boolean videoVisible = binding.inCallActionRight.getVisibility() == View.VISIBLE;
        final boolean cameraVisible = binding.inCallActionFarRight.getVisibility() == View.VISIBLE;
        final boolean endVisible = binding.endCall.getVisibility() == View.VISIBLE;

        binding.inCallAudioRouteContainer.setVisibility(routeVisible ? View.VISIBLE : View.GONE);
        binding.inCallActionLeftContainer.setVisibility(micVisible ? View.VISIBLE : View.GONE);
        binding.inCallActionRightContainer.setVisibility(videoVisible ? View.VISIBLE : View.GONE);
        binding.inCallActionFarRightContainer.setVisibility(cameraVisible ? View.VISIBLE : View.GONE);
        binding.endCallContainer.setVisibility(endVisible ? View.VISIBLE : View.GONE);

        binding.inCallAudioRouteLabel.setVisibility(routeVisible ? View.VISIBLE : View.GONE);
        binding.inCallActionLeftLabel.setVisibility(micVisible ? View.VISIBLE : View.GONE);
        binding.inCallActionRightLabel.setVisibility(videoVisible ? View.VISIBLE : View.GONE);
        binding.inCallActionFarRightLabel.setVisibility(cameraVisible ? View.VISIBLE : View.GONE);
        binding.endCallLabel.setVisibility(endVisible ? View.VISIBLE : View.GONE);
    }

    private boolean isPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return isInPictureInPictureMode();
        } else {
            return false;
        }
    }

    private void updateInCallButtonConfiguration() {
        final JingleRtpConnection connection = requireRtpConnection();
        updateInCallButtonConfiguration(
                connection.getEndUserState(),
                connection.getPresentationMedia(),
                connection.getPendingContentAddition());
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfiguration(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        final var showButtons = !isPictureInPicture() && !buttonsHiddenAfterTimeout;
        final CallUiState uiState = CallUiState.from(state, media, contentAddition);
        if (uiState.isEstablished() && showButtons) {
            Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty");
            if (media.contains(Media.AUDIO)) {
                final CallIntegration callIntegration = requireRtpConnection().getCallIntegration();
                updateInCallButtonConfigurationSpeaker(
                        callIntegration.getSelectedAudioDevice(),
                        callIntegration.getAudioDevices().size());
                updateInCallButtonConfigurationMicrophone(
                        requireRtpConnection().isMicrophoneEnabled());
            } else {
                this.binding.inCallAudioRoute.setVisibility(View.GONE);
                this.binding.inCallActionLeft.setVisibility(View.GONE);
            }
            if (media.contains(Media.VIDEO)) {
                final JingleRtpConnection rtpConnection = requireRtpConnection();
                updateInCallButtonConfigurationVideo(
                        rtpConnection.isVideoEnabled(), rtpConnection.isCameraSwitchable());
            } else {
                updateInCallButtonConfigurationAddVideo();
            }
        } else if (STATES_SHOWING_SPEAKER_CONFIGURATION.contains(state)
                && showButtons
                && Media.audioOnly(media)) {
            final CallIntegration callIntegration;
            try {
                callIntegration = requireCallIntegration();
            } catch (final IllegalStateException e) {
                Log.e(Config.LOGTAG, "can not update InCallButtonConfiguration in state " + state);
                return;
            }
            updateInCallButtonConfigurationSpeaker(
                    callIntegration.getSelectedAudioDevice(),
                    callIntegration.getAudioDevices().size());
            this.binding.inCallActionRight.setVisibility(View.GONE);
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        } else {
            this.binding.inCallAudioRoute.setVisibility(View.GONE);
            this.binding.inCallActionLeft.setVisibility(View.GONE);
            this.binding.inCallActionRight.setVisibility(View.GONE);
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationSpeaker(
            final CallIntegration.AudioDevice selectedAudioDevice, final int numberOfChoices) {
        switch (selectedAudioDevice) {
            case EARPIECE ->
                    this.binding.inCallAudioRoute.setImageResource(R.drawable.ic_volume_off_24dp);
            case WIRED_HEADSET ->
                    this.binding.inCallAudioRoute.setImageResource(R.drawable.ic_headset_mic_24dp);
            case SPEAKER_PHONE ->
                    this.binding.inCallAudioRoute.setImageResource(R.drawable.ic_volume_up_24dp);
            case BLUETOOTH ->
                    this.binding.inCallAudioRoute.setImageResource(
                            R.drawable.ic_bluetooth_audio_24dp);
            default ->
                    this.binding.inCallAudioRoute.setImageResource(R.drawable.ic_volume_up_24dp);
        }
        this.binding.inCallAudioRoute.setContentDescription(getString(R.string.audio_output));
        this.binding.inCallAudioRouteLabel.setText(getAudioRouteLabel(selectedAudioDevice));
        if (numberOfChoices >= 2) {
            this.binding.inCallAudioRoute.setClickable(true);
            this.binding.inCallAudioRoute.setOnClickListener(this::showAudioRoutePicker);
        } else {
            this.binding.inCallAudioRoute.setOnClickListener(null);
            this.binding.inCallAudioRoute.setClickable(false);
        }
        setVisibleAndShow(this.binding.inCallAudioRoute);
    }

    private void showAudioRoutePicker(final View ignored) {
        final CallIntegration callIntegration;
        try {
            callIntegration = requireCallIntegration();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<CallIntegration.AudioDevice> devices =
                new ArrayList<>(callIntegration.getAudioDevices());
        devices.sort(Enum::compareTo);
        if (devices.isEmpty()) {
            return;
        }
        final CharSequence[] labels = new CharSequence[devices.size()];
        int checked = -1;
        final CallIntegration.AudioDevice selected = callIntegration.getSelectedAudioDevice();
        for (int i = 0; i < devices.size(); ++i) {
            final CallIntegration.AudioDevice device = devices.get(i);
            labels[i] = getAudioRouteLabel(device);
            if (device == selected) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.audio_output)
                .setSingleChoiceItems(
                        labels,
                        checked,
                        (dialog, which) -> {
                            final CallIntegration.AudioDevice device = devices.get(which);
                            callIntegration.setAudioDevice(device);
                            putProximityWakeLockInProperState(device);
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private CharSequence getAudioRouteLabel(final CallIntegration.AudioDevice device) {
        return switch (device) {
            case EARPIECE -> getString(R.string.audio_route_earpiece);
            case SPEAKER_PHONE -> getString(R.string.audio_route_speaker);
            case BLUETOOTH -> getString(R.string.audio_route_bluetooth);
            case WIRED_HEADSET -> getString(R.string.audio_route_wired_headset);
            case STREAMING -> getString(R.string.audio_route_streaming);
            case NONE -> getString(R.string.audio_route_unknown);
        };
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationAddVideo() {
        final JingleRtpConnection connection = requireRtpConnection();
        this.binding.inCallActionFarRight.setVisibility(View.GONE);
        if (connection.isSwitchToVideoAvailable()) {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_24dp);
            this.binding.inCallActionRight.setContentDescription(
                    getString(R.string.switch_to_video));
            this.binding.inCallActionRight.setOnClickListener(
                    ignored -> requestPermissionAndSwitchToVideo());
            final boolean enabled = !connection.isMediaModificationInProgress();
            this.binding.inCallActionRight.setClickable(enabled);
            this.binding.inCallActionRight.setEnabled(enabled);
            setVisibleAndShow(this.binding.inCallActionRight);
        } else {
            this.binding.inCallActionRight.setVisibility(View.GONE);
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationVideo(
            final boolean videoEnabled, final boolean isCameraSwitchable) {
        final JingleRtpConnection connection = requireRtpConnection();
        final boolean modificationInProgress = connection.isMediaModificationInProgress();
        setVisibleAndShow(this.binding.inCallActionRight);
        if (isCameraSwitchable) {
            this.binding.inCallActionFarRight.setImageResource(
                    R.drawable.ic_flip_camera_android_24dp);
            setVisibleAndShow(this.binding.inCallActionFarRight);
            this.binding.inCallActionFarRight.setOnClickListener(this::switchCamera);
            this.binding.inCallActionFarRight.setContentDescription(
                    getString(R.string.flip_camera));
            this.binding.inCallActionFarRight.setClickable(!modificationInProgress);
            this.binding.inCallActionFarRight.setEnabled(!modificationInProgress);
        } else {
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
        this.binding.inCallActionRight.setClickable(!modificationInProgress);
        this.binding.inCallActionRight.setEnabled(!modificationInProgress);
        if (videoEnabled) {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::disableVideo);
            this.binding.inCallActionRight.setContentDescription(
                    getString(R.string.video_is_enabled_tap_to_disable));
        } else {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_off_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::enableVideo);
            this.binding.inCallActionRight.setContentDescription(
                    getString(R.string.video_is_disabled_tap_to_enable));
        }
    }

    private void switchCamera(final View view) {
        if (requireRtpConnection().isMediaModificationInProgress()) {
            return;
        }
        resetVisibilityToggleExecutor();
        Futures.addCallback(
                requireRtpConnection().switchCamera(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(@Nullable Boolean isFrontCamera) {
                        binding.localVideo.setMirror(Boolean.TRUE.equals(isFrontCamera));
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Log.d(
                                Config.LOGTAG,
                                "could not switch camera",
                                Throwables.getRootCause(throwable));
                        Toast.makeText(
                                        RtpSessionActivity.this,
                                        R.string.could_not_switch_camera,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                },
                MainThreadExecutor.getInstance());
    }

    private void enableVideo(final View view) {
        resetVisibilityToggleExecutor();
        try {
            requireRtpConnection().setVideoEnabled(true);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.unable_to_enable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(true, requireRtpConnection().isCameraSwitchable());
    }

    private void disableVideo(final View view) {
        resetVisibilityToggleExecutor();
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        final ContentAddition pending = rtpConnection.getPendingContentAddition();
        if (pending != null && pending.direction == ContentAddition.Direction.OUTGOING) {
            rtpConnection.retractContentAdd();
            return;
        }
        try {
            rtpConnection.setVideoEnabled(false);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_disable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(false, rtpConnection.isCameraSwitchable());
    }

    private void downgradeToAudio(final View view) {
        resetVisibilityToggleExecutor();
        final JingleRtpConnection connection = requireRtpConnection();
        if (!connection.isVideoDowngradeRequestAvailable()) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
            return;
        }
        binding.videoAudioOnlyButton.setEnabled(false);
        connection.recordUiDiagnostic("video-audio-only click");
        Futures.addCallback(
                connection.requestVideoDowngrade(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(@Nullable final Boolean started) {
                        if (Boolean.TRUE.equals(started)) {
                            return;
                        }
                        final JingleRtpConnection current =
                                rtpConnectionReference != null
                                        ? rtpConnectionReference.get()
                                        : null;
                        if (current == connection) {
                            binding.videoAudioOnlyButton.setEnabled(
                                    connection.isVideoDowngradeRequestAvailable());
                        }
                        Toast.makeText(
                                        RtpSessionActivity.this,
                                        R.string.could_not_modify_call,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        final JingleRtpConnection current =
                                rtpConnectionReference != null
                                        ? rtpConnectionReference.get()
                                        : null;
                        if (current == connection) {
                            binding.videoAudioOnlyButton.setEnabled(
                                    connection.isVideoDowngradeRequestAvailable());
                        }
                        Log.d(
                                Config.LOGTAG,
                                "unable to switch active video call back to audio",
                                Throwables.getRootCause(throwable));
                        Toast.makeText(
                                        RtpSessionActivity.this,
                                        R.string.could_not_modify_call,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                },
                MainThreadExecutor.getInstance());
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationMicrophone(final boolean microphoneEnabled) {
        if (microphoneEnabled) {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::disableMicrophone);
        } else {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_off_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::enableMicrophone);
        }
        setVisibleAndShow(this.binding.inCallActionLeft);
    }

    private void updateCallDuration() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null || connection.zeroDuration()) {
            this.binding.duration.setVisibility(View.GONE);
            return;
        }
        if (connection.getPresentationMedia().contains(Media.VIDEO)) {
            this.binding.duration.setVisibility(View.GONE);
            return;
        }
        final String elapsed =
                TimeFrameUtils.formatElapsedTime(connection.getCallDuration(), false);
        this.binding.duration.setText(elapsed);
        this.binding.duration.setVisibility(View.VISIBLE);
    }

    private void resetVisibilityToggleExecutor() {
        mHandler.removeCallbacks(this.mVisibilityToggleExecutor);
        mHandler.postDelayed(this.mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT);
    }

    private void updateButtonInVideoCallVisibility() {
        if (isInConnectedVideoCall()) {
            if (isPictureInPicture()) {
                return;
            }
            Log.d(Config.LOGTAG, "hiding in-call chrome after timeout was reached");
            hideInCallButtons();
        }
    }

    private void hideInCallButtons() {
        this.buttonsHiddenAfterTimeout = true;
        // Keep the destructive exit affordance permanently available. Only secondary video
        // controls auto-hide; a state refresh must never make the call look impossible to end.
        binding.buttonRow.setVisibility(View.VISIBLE);
        binding.videoCallHeader.setVisibility(View.GONE);
        binding.inCallControls.setVisibility(View.GONE);
        binding.inCallAudioRoute.hide();
        binding.inCallActionLeft.hide();
        binding.inCallActionRight.hide();
        binding.inCallActionFarRight.hide();
        binding.inCallAudioRouteContainer.setVisibility(View.GONE);
        binding.inCallActionLeftContainer.setVisibility(View.GONE);
        binding.inCallActionRightContainer.setVisibility(View.GONE);
        binding.inCallActionFarRightContainer.setVisibility(View.GONE);
        binding.inCallAudioRouteLabel.setVisibility(View.GONE);
        binding.inCallActionLeftLabel.setVisibility(View.GONE);
        binding.inCallActionRightLabel.setVisibility(View.GONE);
        binding.inCallActionFarRightLabel.setVisibility(View.GONE);
        setVisibleAndShow(binding.endCall);
        binding.endCallContainer.setVisibility(View.VISIBLE);
        binding.endCallLabel.setVisibility(View.VISIBLE);
    }

    private void showInCallButtons() {
        this.buttonsHiddenAfterTimeout = false;
        binding.buttonRow.setVisibility(View.VISIBLE);
        final JingleRtpConnection rtpConnection;
        try {
            rtpConnection = requireRtpConnection();
        } catch (final IllegalStateException e) {
            return;
        }
        updateButtonConfiguration(
                rtpConnection.getEndUserState(),
                rtpConnection.getPresentationMedia(),
                rtpConnection.getPendingContentAddition());
    }

    private void updateVideoChrome(final RtpEndUserState state, final Set<Media> media) {
        final boolean visible =
                !isPictureInPicture()
                        && !buttonsHiddenAfterTimeout
                        && media.contains(Media.VIDEO)
                        && (state == RtpEndUserState.CONNECTED
                                || state == RtpEndUserState.RECONNECTING);
        binding.videoCallHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        final boolean showAudioOnly =
                visible
                        && connection != null
                        && media.contains(Media.AUDIO)
                        && media.contains(Media.VIDEO);
        binding.videoAudioOnlyButton.setVisibility(showAudioOnly ? View.VISIBLE : View.GONE);
        binding.videoAudioOnlyButton.setEnabled(
                showAudioOnly && connection.isVideoDowngradeRequestAvailable());
    }

    private void updateReconnectOverlay(
            final RtpEndUserState state, final Set<Media> media) {
        final boolean reconnecting =
                !isPictureInPicture() && state == RtpEndUserState.RECONNECTING;
        binding.reconnectOverlay.setVisibility(reconnecting ? View.VISIBLE : View.GONE);
        if (reconnecting) {
            binding.reconnectOverlayText.setText(
                    media.contains(Media.VIDEO)
                            ? R.string.reconnecting_video_call
                            : R.string.reconnecting_call);
            binding.reconnectOverlay.setOnClickListener(this::onVideoScreenClick);
        }
    }

    private void resetVisibilityExecutorShowButtons() {
        resetVisibilityToggleExecutor();
        showInCallButtons();
    }

    private void updateVideoViews(final RtpEndUserState state) {
        if (END_CARD.contains(state) || state == RtpEndUserState.ENDING_CALL) {
            binding.localVideoContainer.setVisibility(View.GONE);
            binding.localVideo.setVisibility(View.GONE);
            binding.localVideo.release();
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.remoteVideo.release();
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            if (isPictureInPicture()) {
                binding.appBarLayout.setVisibility(View.GONE);
                binding.pipPlaceholder.setVisibility(View.VISIBLE);
                if (Arrays.asList(
                                RtpEndUserState.APPLICATION_ERROR,
                                RtpEndUserState.CONNECTIVITY_ERROR,
                                RtpEndUserState.SECURITY_ERROR)
                        .contains(state)) {
                    binding.pipWarning.setVisibility(View.VISIBLE);
                    binding.pipWaiting.setVisibility(View.GONE);
                } else {
                    binding.pipWarning.setVisibility(View.GONE);
                    binding.pipWaiting.setVisibility(View.GONE);
                }
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                binding.pipPlaceholder.setVisibility(View.GONE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }
        if (isPictureInPicture() && STATES_SHOWING_PIP_PLACEHOLDER.contains(state)) {
            binding.localVideoContainer.setVisibility(View.GONE);
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.appBarLayout.setVisibility(View.GONE);
            binding.pipPlaceholder.setVisibility(View.VISIBLE);
            binding.pipWarning.setVisibility(View.GONE);
            binding.pipWaiting.setVisibility(View.VISIBLE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            return;
        }
        final boolean activeVideoSession;
        try {
            activeVideoSession =
                    requireRtpConnection().getPresentationMedia().contains(Media.VIDEO);
        } catch (final IllegalStateException e) {
            return;
        }
        final Optional<VideoTrack> localVideoTrack =
                activeVideoSession ? getLocalVideoTrack() : Optional.absent();
        if (localVideoTrack.isPresent() && !isPictureInPicture()) {
            binding.localVideoContainer.setVisibility(View.VISIBLE);
            ensureSurfaceViewRendererIsSetup(binding.localVideo);
            // paint local view over remote view
            binding.localVideo.setZOrderMediaOverlay(true);
            binding.localVideo.setMirror(requireRtpConnection().isFrontCamera());
            addSink(localVideoTrack.get(), binding.localVideo);
        } else {
            binding.localVideoContainer.setVisibility(View.GONE);
            binding.localVideo.setVisibility(View.GONE);
        }
        final Optional<VideoTrack> remoteVideoTrack =
                activeVideoSession ? getRemoteVideoTrack() : Optional.absent();
        if (remoteVideoTrack.isPresent()) {
            ensureSurfaceViewRendererIsSetup(binding.remoteVideo);
            addSink(remoteVideoTrack.get(), binding.remoteVideo);
            binding.remoteVideo.setScalingType(
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                    RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            if (state == RtpEndUserState.CONNECTED || state == RtpEndUserState.RECONNECTING) {
                binding.appBarLayout.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.VISIBLE);
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.GONE);
            }
            if (isPictureInPicture() && !requireRtpConnection().isMicrophoneEnabled()) {
                binding.pipLocalMicOffIndicator.setVisibility(View.VISIBLE);
            } else {
                binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
        }
    }

    private Optional<VideoTrack> getLocalVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getLocalVideoTrack();
    }

    private Optional<VideoTrack> getRemoteVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getRemoteVideoTrack();
    }

    private void disableMicrophone(final View view) {
        setMicrophoneEnabled(false);
    }

    private void enableMicrophone(final View view) {
        setMicrophoneEnabled(true);
    }

    private void setMicrophoneEnabled(final boolean enabled) {
        resetVisibilityExecutorShowButtons();
        try {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            if (rtpConnection.setMicrophoneEnabled(enabled)) {
                updateInCallButtonConfiguration();
            }
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToEarpiece(final View view) {
        try {
            requireCallIntegration().setAudioDevice(CallIntegration.AudioDevice.EARPIECE);
            acquireProximityWakeLock();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToSpeaker(final View view) {
        try {
            requireCallIntegration().setAudioDevice(CallIntegration.AudioDevice.SPEAKER_PHONE);
            releaseProximityWakeLock();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void retry(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.of(intent.getStringExtra(EXTRA_WITH));
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final String action = intent.getAction();
        final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
        this.rtpConnectionReference = null;
        Log.d(Config.LOGTAG, "attempting retry with " + with.toString());
        CallIntegrationConnectionService.placeCall(xmppConnectionService, account, with, media);
    }

    private void exit(final View view) {
        finish();
    }

    private void recordVoiceMail(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.of(intent.getStringExtra(EXTRA_WITH));
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(account, with, null, false, false, true, null);
        final Intent launchIntent = new Intent(this, ConversationsActivity.class);
        launchIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        launchIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        launchIntent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(
                ConversationsActivity.EXTRA_POST_INIT_ACTION,
                ConversationsActivity.POST_ACTION_RECORD_VOICE);
        startActivity(launchIntent);
        finish();
    }

    private Contact getWith() {
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        final Account account = id.account;
        return account.getRoster().getContact(id.with);
    }

    private JingleRtpConnection requireRtpConnection() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            throw new IllegalStateException("No RTP connection found");
        }
        return connection;
    }

    private CallIntegration requireCallIntegration() {
        return requireOngoingRtpSession().getCallIntegration();
    }

    private OngoingRtpSession requireOngoingRtpSession() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection != null) {
            return connection;
        }
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        final var account = extractAccount(currentIntent);
        if (withExtra == null) {
            throw new IllegalStateException("Current intent has no EXTRA_WITH");
        }
        final var matching =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .matchingProposal(account, Jid.of(withExtra));
        if (matching.isPresent()) {
            return matching.get();
        }
        throw new IllegalStateException("No matching session proposal");
    }

    @Override
    public void onJingleRtpConnectionUpdate(
            Account account, Jid with, final String sessionId, RtpEndUserState state) {
        Log.d(Config.LOGTAG, "onJingleRtpConnectionUpdate(" + state + ")");
        if (END_CARD.contains(state)) {
            Log.d(Config.LOGTAG, "end card reached");
            releaseProximityWakeLock();
            runOnUiThread(
                    () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
        if (with.isBareJid()) {
            // TODO check for ENDED
            updateRtpSessionProposalState(account, with, state);
            return;
        }
        if (emptyReference(this.rtpConnectionReference)) {
            if (END_CARD.contains(state)) {
                Log.d(Config.LOGTAG, "not reinitializing session");
                return;
            }
            // this happens when going from proposed session to actual session
            reInitializeActivityWithRunningRtpSession(account, with, sessionId);
            return;
        }
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        final AbstractJingleConnection.Id id = rtpConnection.getId();
        final Contact contact = getWith();
        if (account == id.account && id.with.equals(with) && id.sessionId.equals(sessionId)) {
            if (state == RtpEndUserState.ENDED) {
                finish();
                return;
            }
            final long sequence = uiEventSequence.incrementAndGet();
            rtpConnection.recordUiDiagnostic(
                    "observer seq=" + sequence + " callback=" + state);
            resetVisibilityToggleExecutor();
            runOnUiThread(
                    () ->
                            renderLiveCallState(
                                    rtpConnection, "observer", state, sequence, contact));
            if (END_CARD.contains(state)) {
                resetIntent(account, with, state, rtpConnection.getMedia());
                releaseVideoTracks(rtpConnection);
                this.rtpConnectionReference = null;
            }
        } else {
            Log.d(Config.LOGTAG, "received update for other rtp session");
        }
    }

    @Override
    public void onAudioDeviceChanged(
            final CallIntegration.AudioDevice selectedAudioDevice,
            final Set<CallIntegration.AudioDevice> availableAudioDevices) {
        Log.d(
                Config.LOGTAG,
                "onAudioDeviceChanged in activity: selected:"
                        + selectedAudioDevice
                        + ", available:"
                        + availableAudioDevices);
        try {
            final OngoingRtpSession ongoingRtpSession = requireOngoingRtpSession();
            final RtpEndUserState endUserState;
            if (ongoingRtpSession instanceof JingleRtpConnection jingleRtpConnection) {
                endUserState = jingleRtpConnection.getEndUserState();
            } else {
                // for session proposals all end user states are functionally the same
                endUserState = RtpEndUserState.RINGING;
            }
            final Set<Media> media =
                    ongoingRtpSession instanceof JingleRtpConnection jingleRtpConnection
                            ? jingleRtpConnection.getPresentationMedia()
                            : ongoingRtpSession.getMedia();
            if (END_CARD.contains(endUserState)) {
                Log.d(
                        Config.LOGTAG,
                        "onAudioDeviceChanged() nothing to do because end card has been reached");
            } else {
                if (Media.audioOnly(media)
                        && STATES_SHOWING_SPEAKER_CONFIGURATION.contains(endUserState)) {
                    final CallIntegration callIntegration = requireCallIntegration();
                    updateInCallButtonConfigurationSpeaker(
                            callIntegration.getSelectedAudioDevice(),
                            callIntegration.getAudioDevices().size());
                }
                Log.d(
                        Config.LOGTAG,
                        "put proximity wake lock into proper state after device update");
                putProximityWakeLockInProperState(selectedAudioDevice);
            }
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "RTP connection was not available when audio device changed");
        }
    }

    private void updateRtpSessionProposalState(
            final Account account, final Jid with, final RtpEndUserState state) {
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        if (withExtra == null) {
            return;
        }
        final Set<Media> media = actionToMedia(currentIntent.getStringExtra(EXTRA_LAST_ACTION));
        if (Jid.of(withExtra).asBareJid().equals(with)) {
            runOnUiThread(
                    () -> {
                        updateVerifiedShield(false);
                        updateStateDisplay(state);
                        updateButtonConfiguration(state, media, null);
                        final Contact contact = account.getRoster().getContact(with);
                        updateIncomingCallScreen(state, contact);
                        invalidateOptionsMenu();
                    });
            resetIntent(account, with, state, media);
        }
    }

    private void resetIntent(final Bundle extras) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtras(extras);
        setIntent(intent);
    }

    private void resetIntent(
            final Account account, Jid with, final RtpEndUserState state, final Set<Media> media) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toString());
        if (RtpCapability.jmiSupport(account.getRoster().getContact(with))) {
            intent.putExtra(EXTRA_WITH, with.asBareJid().toString());
        } else {
            intent.putExtra(EXTRA_WITH, with.toString());
        }
        intent.putExtra(EXTRA_LAST_REPORTED_STATE, state.toString());
        intent.putExtra(
                EXTRA_LAST_ACTION,
                media.contains(Media.VIDEO) ? ACTION_MAKE_VIDEO_CALL : ACTION_MAKE_VOICE_CALL);
        setIntent(intent);
    }

    private static boolean emptyReference(final WeakReference<?> weakReference) {
        return weakReference == null || weakReference.get() == null;
    }

    private enum Event {
        ON_BACKEND_CONNECTED,
        ON_NEW_INTENT
    }
}