package eu.siacs.conversations.services;

import static eu.siacs.conversations.utils.Compatibility.s;
import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.security.KeyChain;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;
import androidx.annotation.BoolRes;
import androidx.annotation.IntegerRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.android.JabberIdContact;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Blockable;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.OnRenameListener;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.PresenceTemplate;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.entities.Roster;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.entities.media.MediaCaptionResolver;
import eu.siacs.conversations.generator.AbstractGenerator;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.generator.MessageGenerator;
import eu.siacs.conversations.generator.PresenceGenerator;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.parser.AbstractParser;
import eu.siacs.conversations.parser.IqParser;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.DatabaseBackendProvider;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.persistance.FilePathInfo;
import eu.siacs.conversations.persistance.LocalAccountDataSnapshot;
import eu.siacs.conversations.persistance.UnifiedPushDatabase;
import eu.siacs.conversations.receiver.SystemEventReceiver;
import eu.siacs.conversations.security.cryptolock.InactiveDeviceActivationRecoveryCoordinatorV1;
import eu.siacs.conversations.security.cryptolock.InactiveDeviceDeactivationRecoveryCoordinatorV1;
import eu.siacs.conversations.security.cryptolock.InactiveDevicePrivacyRuntimeV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionStateV1;
import eu.siacs.conversations.services.media.OutgoingMediaCaptionCoordinator;
import eu.siacs.conversations.storage.secure.AccountSecretConnectionPolicyV1;
import eu.siacs.conversations.storage.secure.AccountSecretMigrationCoordinatorV1;
import eu.siacs.conversations.storage.secure.AccountSecretMigrationOutcomeV1;
import eu.siacs.conversations.storage.secure.AccountSecretRetirementV1;
import eu.siacs.conversations.storage.secure.AccountSecretRuntimePersistenceV1;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaReadCache;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaSaver;
import eu.siacs.conversations.storage.secure.DatabaseSecureMessagePayloadCoordinator;
import eu.siacs.conversations.storage.secure.LegacyPlaintextMessageMigrator;
import eu.siacs.conversations.storage.secure.LegacyPlaintextSqliteCleanupCoordinator;
import eu.siacs.conversations.storage.secure.LegacyPlaintextSqliteCleanupResult;
import eu.siacs.conversations.storage.secure.PersistentSecureContentKeyMaterialStore;
import eu.siacs.conversations.storage.secure.ScopedAccountSecretVaultV1;
import eu.siacs.conversations.storage.secure.SecureColdStartPerfTrace;
import eu.siacs.conversations.storage.secure.SecureMessageMediaLifecyclePolicy;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessagePayloadMode;
import eu.siacs.conversations.storage.secure.SecureMessageRetirementBoundary;
import eu.siacs.conversations.storage.secure.SecureMessageSearchCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageSearchRepairResult;
import eu.siacs.conversations.storage.secure.SecureMessageSearchRuntime;
import eu.siacs.conversations.storage.secure.SecureMessageTextRepository;
import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentStagingRetirer;
import eu.siacs.conversations.storage.secure.SecureOutgoingTextPayloadPublisher;
import eu.siacs.conversations.storage.secure.SecureOutgoingTextPayloadReader;
import eu.siacs.conversations.storage.secure.SecureOutgoingVoiceStagingRetirer;
import eu.siacs.conversations.storage.secure.SecureTextPayload;
import eu.siacs.conversations.storage.secure.SecureTextPayloadTooLargeException;
import eu.siacs.conversations.ui.ChooseAccountForProfilePictureActivity;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.RtpSessionActivity;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.attachments.AttachmentBrowserRepository;
import eu.siacs.conversations.ui.attachments.AttachmentEntry;
import eu.siacs.conversations.ui.attachments.AttachmentPage;
import eu.siacs.conversations.ui.interfaces.OnAttachmentPageLoaded;
import eu.siacs.conversations.ui.interfaces.OnAvatarPublication;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.ImageAttachmentStaging;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.VideoAttachmentStaging;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.ConversationsFileObserver;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.EasyOnboardingInvite;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.PhoneHelper;
import eu.siacs.conversations.utils.QuickLoader;
import eu.siacs.conversations.utils.ReplacingSerialSingleThreadExecutor;
import eu.siacs.conversations.utils.ReplacingTaskManager;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.TorServiceUtils;
import eu.siacs.conversations.utils.WakeLockHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.LocalizedContent;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.MucModerationPolicy;
import eu.siacs.conversations.xmpp.MucModerationProtocol;
import eu.siacs.conversations.xmpp.OnContactStatusChanged;
import eu.siacs.conversations.xmpp.OnGatewayResult;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated;
import eu.siacs.conversations.xmpp.OnMessageAcknowledged;
import eu.siacs.conversations.xmpp.OnStatusChanged;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.forms.Field;
import eu.siacs.conversations.xmpp.jid.OtrJidHelper;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.xmpp.pep.Avatar;
import eu.siacs.conversations.xmpp.pep.PublishOptions;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.File;
import java.io.IOException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.leolin.shortcutbadger.ShortcutBadger;
import net.java.otr4j.session.Session;
import net.java.otr4j.session.SessionID;
import net.java.otr4j.session.SessionImpl;
import net.java.otr4j.session.SessionStatus;
import org.conscrypt.Conscrypt;
import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep;

public class XmppConnectionService extends Service {

    public static final String ACTION_REPLY_TO_CONVERSATION = "reply_to_conversations";
    public static final String ACTION_MARK_AS_READ = "mark_as_read";
    public static final String ACTION_SNOOZE = "snooze";
    public static final String ACTION_CLEAR_MESSAGE_NOTIFICATION = "clear_message_notification";
    public static final String ACTION_CLEAR_MISSED_CALL_NOTIFICATION =
            "clear_missed_call_notification";
    public static final String ACTION_DISMISS_ERROR_NOTIFICATIONS = "dismiss_error";
    public static final String ACTION_TRY_AGAIN = "try_again";

    public static final String ACTION_TEMPORARILY_DISABLE = "temporarily_disable";
    public static final String ACTION_PING = "ping";
    public static final String ACTION_IDLE_PING = "idle_ping";
    public static final String ACTION_INTERNAL_PING = "internal_ping";
    public static final String ACTION_SECURE_CONTENT_UNLOCKED = "secure_content_unlocked";
    public static final String ACTION_FCM_TOKEN_REFRESH = "fcm_token_refresh";
    public static final String ACTION_FCM_MESSAGE_RECEIVED = "fcm_message_received";
    public static final String ACTION_DISMISS_CALL = "dismiss_call";
    public static final String ACTION_END_CALL = "end_call";
    public static final String ACTION_PROVISION_ACCOUNT = "provision_account";
    public static final String ACTION_CALL_INTEGRATION_SERVICE_STARTED =
            "call_integration_service_started";
    private static final String ACTION_POST_CONNECTIVITY_CHANGE =
            "eu.siacs.conversations.POST_CONNECTIVITY_CHANGE";
    public static final String ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS =
            "eu.siacs.conversations.UNIFIED_PUSH_RENEW";
    public static final String ACTION_QUICK_LOG = "eu.siacs.conversations.QUICK_LOG";

    private static final String SETTING_LAST_ACTIVITY_TS = "last_activity_timestamp";
    private static final String MUC_EXPLICIT_LEAVE_PREF_PREFIX = "muc_explicit_leave_v1:";
    private static final String MUC_FORGOTTEN_PREF_PREFIX = "muc_forgotten_v1:";
    private static final long LEGACY_BACKGROUND_MIGRATION_STARTUP_GRACE_MS = 30_000L;
    private static final long LEGACY_BACKGROUND_MIGRATION_CADENCE_MS = 60_000L;

    public final CountDownLatch restoredFromDatabaseLatch = new CountDownLatch(1);
    private static final Executor FILE_OBSERVER_EXECUTOR = Executors.newSingleThreadExecutor();
    public static final Executor FILE_ATTACHMENT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Executor COPY_TO_DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService internalPingExecutor =
            Executors.newSingleThreadScheduledExecutor();

    private final AtomicReference<Network> mActiveNetwork = new AtomicReference<>();
    private final AtomicReference<Network> mRecoveredNetwork = new AtomicReference<>();
    private final AtomicLong mNetworkTransitionGeneration = new AtomicLong(0L);
    private final AtomicLong mNetworkLossGraceUntil = new AtomicLong(0L);
    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull final Network network) {
                    handleActiveNetworkChange(network, "available");
                }

                @Override
                public void onLost(@NonNull final Network network) {
                    if (Objects.equal(mActiveNetwork.get(), network)) {
                        handleActiveNetworkChange(null, "lost");
                    }
                }

                @Override
                public void onCapabilitiesChanged(
                        @NonNull final Network network,
                        @NonNull final NetworkCapabilities capabilities) {
                    if (Objects.equal(mActiveNetwork.get(), network)
                            && capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && capabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            && !Objects.equal(mRecoveredNetwork.get(), network)) {
                        handleValidatedActiveNetwork(network);
                    }
                }

                @Override
                public void onLinkPropertiesChanged(
                        @NonNull final Network network,
                        @NonNull final LinkProperties linkProperties) {
                    if (Objects.equal(mActiveNetwork.get(), network)) {
                        Resolver.clearCache();
                    }
                }
            };
    private static final SerialSingleThreadExecutor VIDEO_COMPRESSION_EXECUTOR =
            new SerialSingleThreadExecutor("VideoCompression");
    private final SerialSingleThreadExecutor mDatabaseWriterExecutor =
            new SerialSingleThreadExecutor("DatabaseWriter");
    private final SerialSingleThreadExecutor mDatabaseReaderExecutor =
            new SerialSingleThreadExecutor("DatabaseReader");
    private final SerialSingleThreadExecutor mNotificationExecutor =
            new SerialSingleThreadExecutor("NotificationExecutor");
    private final ReplacingTaskManager mRosterSyncTaskManager = new ReplacingTaskManager();
    private final IBinder mBinder = new XmppConnectionBinder();
    private final List<Conversation> conversations = new CopyOnWriteArrayList<>();
    private final MucExplicitLeaveGuard mucExplicitLeaveGuard = new MucExplicitLeaveGuard();
    private final IqGenerator mIqGenerator = new IqGenerator(this);
    private final Set<String> mInProgressAvatarFetches = new HashSet<>();
    private final Set<String> mOmittedPepAvatarFetches = new HashSet<>();
    private final HashSet<Jid> mLowPingTimeoutMode = new HashSet<>();
    private final Consumer<Iq> mDefaultIqHandler =
            (packet) -> {
                if (packet.getType() != Iq.Type.RESULT) {
                    final var error = packet.getError();
                    String text = error != null ? error.findChildContent("text") : null;
                    if (text != null) {
                        Log.d(Config.LOGTAG, "received iq error: " + text);
                    }
                }
            };
    public DatabaseBackend databaseBackend;
    @Nullable private volatile SecureMessagePayloadCoordinator secureMessagePayloadCoordinator;
    @Nullable private volatile SecureMessageTextRepository secureMessageTextRepository;
    private final Executor secureTextSendExecutor = Executors.newSingleThreadExecutor();
    private final Executor legacyTextMigrationExecutor = Executors.newSingleThreadExecutor();
    private final Executor secureContentMaintenanceExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> legacyTextMigrationInFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> secureContentAccountCleanupInProgress = ConcurrentHashMap.newKeySet();
    private final Object secureContentMigrationCleanupLock = new Object();
    private final Object secureContentMediaMutationLock = new Object();
    private final AtomicBoolean legacyBackgroundMigrationScheduled = new AtomicBoolean(false);
    private final AtomicBoolean secureContentRelationBackfillInFlight = new AtomicBoolean(false);
    private final AtomicLong legacyBackgroundMigrationLastScheduledAt = new AtomicLong(0L);
    private final AtomicLong legacyBackgroundMigrationAccountCursor = new AtomicLong(0L);
    private final AtomicLong messageRestoreCompletedAt = new AtomicLong(0L);
    private final AtomicLong protectedTextRehydrateCryptoEpoch = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong conversationPresentationRevision = new AtomicLong(0L);
    private final Set<String> protectedTextConversationRehydrateInFlight =
            ConcurrentHashMap.newKeySet();
    private final Set<String> protectedTextConversationRehydratePending =
            ConcurrentHashMap.newKeySet();
    private final Set<String> protectedTextVisibleMessageRehydrateInFlight =
            ConcurrentHashMap.newKeySet();
    private final ReplacingSerialSingleThreadExecutor mContactMergerExecutor =
            new ReplacingSerialSingleThreadExecutor("ContactMerger");
    private long mLastActivity = 0;

    private final AppSettings appSettings = new AppSettings(this);
    private final FileBackend fileBackend = new FileBackend(this);
    private MemorizingTrustManager mMemorizingTrustManager;
    private final NotificationService mNotificationService = new NotificationService(this);
    private final UnifiedPushBroker unifiedPushBroker = new UnifiedPushBroker(this);
    private final ChannelDiscoveryService mChannelDiscoveryService =
            new ChannelDiscoveryService(this);
    private final ShortcutService mShortcutService = new ShortcutService(this);
    private final AtomicBoolean mInitialAddressbookSyncCompleted = new AtomicBoolean(false);
    private final AtomicBoolean mOngoingVideoTranscoding = new AtomicBoolean(false);
    private final AtomicBoolean mForceDuringOnCreate = new AtomicBoolean(false);
    private final AtomicReference<OngoingCall> ongoingCall = new AtomicReference<>();
    private final MessageGenerator mMessageGenerator = new MessageGenerator(this);
    private final Map<String, MediaSendBatch> mediaSendBatches = new ConcurrentHashMap<>();
    private final Set<String> releasedDeferredMediaCaptions = ConcurrentHashMap.newKeySet();
    private final OutgoingMediaCaptionCoordinator outgoingMediaCaptionCoordinator =
            new OutgoingMediaCaptionCoordinator();
    private final ThreadLocal<Integer> suppressedConversationUiUpdates =
            ThreadLocal.withInitial(() -> 0);
    public OnContactStatusChanged onContactStatusChanged =
            (contact, online) -> {
                final var conversation = find(contact);
                if (conversation == null) {
                    return;
                }
                if (online) {
                    conversation.endOtrIfNeeded();
                    if (contact.getPresences().size() == 1) {
                        sendUnsentMessages(conversation);
                    }
                } else {
                    // check if the resource we are haveing a conversation with is still online
                    if (conversation.hasValidOtrSession()) {
                        String otrResource =
                                conversation.getOtrSession().getSessionID().getUserID();
                        if (!(Arrays.asList(contact.getPresences().toResourceArray())
                                .contains(otrResource))) {
                            conversation.endOtrIfNeeded();
                        }
                    }
                }
            };
    private final PresenceGenerator mPresenceGenerator = new PresenceGenerator(this);
    private List<Account> accounts;
    private final JingleConnectionManager mJingleConnectionManager =
            new JingleConnectionManager(this);
    private final HttpConnectionManager mHttpConnectionManager = new HttpConnectionManager(this);
    private final AvatarService mAvatarService = new AvatarService(this);
    private final MessageArchiveService mMessageArchiveService = new MessageArchiveService(this);
    private final PushManagementService mPushManagementService = new PushManagementService(this);
    private final QuickConversationsService mQuickConversationsService =
            new QuickConversationsService(this);
    private final ConversationsFileObserver fileObserver =
            new ConversationsFileObserver(
                    Environment.getExternalStorageDirectory().getAbsolutePath()) {
                @Override
                public void onEvent(final int event, final File file) {
                    markFileDeleted(file);
                }
            };
    private final OnMessageAcknowledged mOnMessageAcknowledgedListener =
            new OnMessageAcknowledged() {

                @Override
                public boolean onMessageAcknowledged(
                        final Account account, final Jid to, final String id) {
                    if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                        final String sessionId =
                                id.substring(
                                        JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX
                                                .length());
                        mJingleConnectionManager.updateProposedSessionDiscovered(
                                account,
                                to,
                                sessionId,
                                JingleConnectionManager.DeviceDiscoveryState
                                        .SEARCHING_ACKNOWLEDGED);
                    }

                    final Jid bare = to.asBareJid();

                    for (final Conversation conversation : getConversations()) {
                        if (conversation.getAccount() == account
                                && conversation.getJid().asBareJid().equals(bare)) {
                            final Message message = conversation.findUnsentMessageWithUuid(id);
                            if (message != null) {
                                message.setStatus(Message.STATUS_SEND);
                                message.setErrorMessage(null);
                                databaseBackend.updateMessage(message, false);
                                markTimelineChanged(message);
                                return true;
                            }
                        }
                    }
                    return false;
                }
            };

    private boolean destroyed = false;

    private int unreadCount = -1;

    // Ui callback listeners
    private final Set<OnConversationUpdate> mOnConversationUpdates =
            Collections.newSetFromMap(new WeakHashMap<OnConversationUpdate, Boolean>());
    private final Set<OnShowErrorToast> mOnShowErrorToasts =
            Collections.newSetFromMap(new WeakHashMap<OnShowErrorToast, Boolean>());
    private final Set<OnAccountUpdate> mOnAccountUpdates =
            Collections.newSetFromMap(new WeakHashMap<OnAccountUpdate, Boolean>());
    private final Set<OnCaptchaRequested> mOnCaptchaRequested =
            Collections.newSetFromMap(new WeakHashMap<OnCaptchaRequested, Boolean>());
    private final Set<OnRosterUpdate> mOnRosterUpdates =
            Collections.newSetFromMap(new WeakHashMap<OnRosterUpdate, Boolean>());
    private final Set<OnUpdateBlocklist> mOnUpdateBlocklist =
            Collections.newSetFromMap(new WeakHashMap<OnUpdateBlocklist, Boolean>());
    private final Set<OnMucRosterUpdate> mOnMucRosterUpdate =
            Collections.newSetFromMap(new WeakHashMap<OnMucRosterUpdate, Boolean>());
    private final Set<OnKeyStatusUpdated> mOnKeyStatusUpdated =
            Collections.newSetFromMap(new WeakHashMap<OnKeyStatusUpdated, Boolean>());
    private final Set<OnJingleRtpConnectionUpdate> onJingleRtpConnectionUpdate =
            Collections.newSetFromMap(new WeakHashMap<OnJingleRtpConnectionUpdate, Boolean>());

    private final Object LISTENER_LOCK = new Object();
    private final Conversations.UiStateListener mUiStateListener = this::onUiForegroundChanged;

    public final Set<String> FILENAMES_TO_IGNORE_DELETION = new HashSet<>();

    private final AtomicLong mLastExpiryRun = new AtomicLong(0);
    private final LruCache<Pair<String, String>, ServiceDiscoveryResult> discoCache =
            new LruCache<>(20);
    private final OnStatusChanged statusListener =
            new OnStatusChanged() {

                @Override
                public void onStatusChanged(final Account account) {
                    XmppConnection connection = account.getXmppConnection();
                    updateAccountUi();

                    if (account.getStatus() == Account.State.ONLINE
                            || account.getStatus().isError()) {
                        mQuickConversationsService.signalAccountStateChange();
                    }

                    if (account.getStatus() == Account.State.ONLINE) {
                        synchronized (mLowPingTimeoutMode) {
                            if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": leaving low ping timeout mode");
                            }
                        }
                        if (account.setShowErrorNotification(true)) {
                            databaseBackend.updateAccount(account);
                        }
                        mMessageArchiveService.executePendingQueries(account);
                        mJingleConnectionManager.prefetchCallDependencies(account);
                        if (connection != null) {
                            applyDesiredClientStateIndication(connection);
                        }
                        List<Conversation> conversations = getConversations();
                        for (Conversation conversation : conversations) {
                            final boolean inProgressJoin;
                            synchronized (account.inProgressConferenceJoins) {
                                inProgressJoin =
                                        account.inProgressConferenceJoins.contains(conversation);
                            }
                            final boolean pendingJoin;
                            synchronized (account.pendingConferenceJoins) {
                                pendingJoin = account.pendingConferenceJoins.contains(conversation);
                            }
                            if (conversation.getAccount() == account
                                    && !pendingJoin
                                    && !inProgressJoin) {
                                if (!conversation.startOtrIfNeeded()) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": couldn't start OTR with "
                                                    + conversation.getContact().getJid()
                                                    + " when needed");
                                }
                                sendUnsentMessages(conversation);
                            }
                        }
                        final List<Conversation> pendingLeaves;
                        synchronized (account.pendingConferenceLeaves) {
                            pendingLeaves = new ArrayList<>(account.pendingConferenceLeaves);
                            account.pendingConferenceLeaves.clear();
                        }
                        for (Conversation conversation : pendingLeaves) {
                            leaveMuc(conversation);
                        }
                        final List<Conversation> pendingJoins;
                        synchronized (account.pendingConferenceJoins) {
                            pendingJoins = new ArrayList<>(account.pendingConferenceJoins);
                            account.pendingConferenceJoins.clear();
                        }
                        for (Conversation conversation : pendingJoins) {
                            joinMuc(conversation);
                        }
                        scheduleWakeUpCall(
                                getPingIntervalMillis(account, false),
                                account.getUuid().hashCode());
                    } else if (account.getStatus() == Account.State.OFFLINE
                            || account.getStatus() == Account.State.DISABLED
                            || account.getStatus() == Account.State.LOGGED_OUT) {
                        resetSendingToWaiting(account);
                        if (account.isConnectionEnabled() && isInLowPingTimeoutMode(account)) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": went into offline state during low ping mode."
                                            + " reconnecting now");
                            reconnectAccount(account, true, false);
                        } else {
                            final int timeToReconnect = SECURE_RANDOM.nextInt(10) + 2;
                            scheduleWakeUpCall(timeToReconnect, account.getUuid().hashCode());
                        }
                    } else if (account.getStatus() == Account.State.REGISTRATION_SUCCESSFUL) {
                        databaseBackend.updateAccount(account);
                        reconnectAccount(account, true, false);
                    } else if (account.getStatus() != Account.State.CONNECTING
                            && account.getStatus() != Account.State.NO_INTERNET) {
                        resetSendingToWaiting(account);
                        if (connection != null && account.getStatus().isAttemptReconnect()) {
                            final boolean aggressive =
                                    account.getStatus() == Account.State.SEE_OTHER_HOST
                                            || hasJingleRtpConnection(account);
                            final int next = connection.getTimeToNextAttempt(aggressive);
                            final boolean lowPingTimeoutMode = isInLowPingTimeoutMode(account);
                            if (next <= 0) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": error connecting account. reconnecting now."
                                                + " lowPingTimeout="
                                                + lowPingTimeoutMode);
                                reconnectAccount(account, true, false);
                            } else {
                                final int attempt = connection.getAttempt() + 1;
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": error connecting account. try again in "
                                                + next
                                                + "s for the "
                                                + attempt
                                                + " time. lowPingTimeout="
                                                + lowPingTimeoutMode
                                                + ", aggressive="
                                                + aggressive);
                                scheduleWakeUpCall(next, account.getUuid().hashCode());
                                if (aggressive) {
                                    internalPingExecutor.schedule(
                                            XmppConnectionService.this
                                                    ::manageAccountConnectionStatesInternal,
                                            (next * 1000L) + 50,
                                            TimeUnit.MILLISECONDS);
                                }
                            }
                        }
                    }
                    getNotificationService().updateErrorNotification();
                }
            };
    private WakeLock wakeLock;
    private LruCache<String, Bitmap> mBitmapCache;
    private LruCache<String, Drawable> mDrawableCache;
    private final BroadcastReceiver mInternalEventReceiver = new InternalEventReceiver();
    private final BroadcastReceiver mInternalRestrictedEventReceiver =
            new RestrictedEventReceiver(Arrays.asList(TorServiceUtils.ACTION_STATUS));
    private final BroadcastReceiver mInternalScreenEventReceiver = new InternalEventReceiver();

    private static String generateFetchKey(Account account, final Avatar avatar) {
        return account.getJid().asBareJid() + "_" + avatar.owner + "_" + avatar.sha1sum;
    }

    private boolean isInLowPingTimeoutMode(Account account) {
        synchronized (mLowPingTimeoutMode) {
            return mLowPingTimeoutMode.contains(account.getJid().asBareJid());
        }
    }

    public void startOngoingVideoTranscodingForegroundNotification() {
        mOngoingVideoTranscoding.set(true);
        toggleForegroundService();
    }

    public void stopOngoingVideoTranscodingForegroundNotification() {
        mOngoingVideoTranscoding.set(false);
        toggleForegroundService();
    }

    public boolean areMessagesInitialized() {
        return this.restoredFromDatabaseLatch.getCount() == 0;
    }

    public void copyAttachmentToDownloadsFolder(Message m, final UiCallback<Integer> callback) {
        COPY_TO_DOWNLOAD_EXECUTOR.execute(
                () -> {
                    if (Config.SECURE_CONTENT_MEDIA_ROLLOUT
                            && getApplication() instanceof Conversations) {
                        final Conversations application = (Conversations) getApplication();
                        final AndroidSecureMessageMediaSaver saver =
                                new AndroidSecureMessageMediaSaver(
                                        this, application.getSecureContentStoreProvider().get());
                        try {
                            if (saver.save(m)) {
                                callback.success(-1);
                                return;
                            }
                        } catch (final Exception e) {
                            Log.w(Config.LOGTAG, "unable to save secure media to Downloads", e);
                            callback.error(-1, R.string.error_io_exception);
                            return;
                        }
                    }

                    try {
                        fileBackend.copyAttachmentToDownloadsFolder(m);
                        callback.success(-1);
                    } catch (FileBackend.FileCopyException e) {
                        callback.error(-1, e.getResId());
                    }
                });
    }

    public void copyMediaToGallery(Message m, final UiCallback<Integer> callback) {
        COPY_TO_DOWNLOAD_EXECUTOR.execute(
                () -> {
                    if (Config.SECURE_CONTENT_MEDIA_ROLLOUT
                            && getApplication() instanceof Conversations) {
                        final Conversations application = (Conversations) getApplication();
                        final AndroidSecureMessageMediaSaver saver =
                                new AndroidSecureMessageMediaSaver(
                                        this, application.getSecureContentStoreProvider().get());
                        try {
                            if (saver.saveToGallery(m)) {
                                callback.success(-1);
                                return;
                            }
                        } catch (final Exception e) {
                            Log.w(Config.LOGTAG, "unable to export secure media to gallery", e);
                            callback.error(-1, R.string.error_io_exception);
                            return;
                        }
                    }

                    try {
                        fileBackend.copyAttachmentToGalleryFolder(m);
                        callback.success(-1);
                    } catch (FileBackend.FileCopyException e) {
                        callback.error(-1, e.getResId());
                    }
                });
    }

    public AppSettings getAppSettings() {
        return this.appSettings;
    }

    public FileBackend getFileBackend() {
        return this.fileBackend;
    }

    /**
     * Returns the disabled-by-default protected message payload composition point.
     *
     * <p>The first caller performs Store and publication recovery and must therefore be a
     * background repository/worker, never a UI render path. Legacy message code receives null while
     * the rollout gate remains disabled.
     */
    @Nullable
    public SecureMessagePayloadCoordinator getSecureMessagePayloadCoordinator() {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT
                || databaseBackend == null
                || !(getApplication() instanceof Conversations)) {
            return null;
        }
        final SecureMessagePayloadCoordinator current = secureMessagePayloadCoordinator;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (secureMessagePayloadCoordinator == null) {
                final long coordinatorStarted = System.nanoTime();
                final Conversations application = (Conversations) getApplication();
                final DatabaseSecureMessagePayloadCoordinator coordinator =
                        new DatabaseSecureMessagePayloadCoordinator(
                                application.getSecureContentStoreProvider().get(),
                                databaseBackend,
                                SecureMessageSearchRuntime.INSTANCE.create(databaseBackend));
                final long publicationRecoveryStarted = System.nanoTime();
                coordinator.recoverInterruptedPublications();
                SecureColdStartPerfTrace.stage(
                        "secure_text_publication_recovery",
                        System.nanoTime() - publicationRecoveryStarted);
                final long retirementRecoveryStarted = System.nanoTime();
                coordinator.recoverInterruptedRetirements();
                SecureColdStartPerfTrace.stage(
                        "secure_text_retirement_recovery",
                        System.nanoTime() - retirementRecoveryStarted);
                secureMessagePayloadCoordinator = coordinator;
                mDatabaseWriterExecutor.execute(this::recoverModeratedMessageRetirements);
                mDatabaseWriterExecutor.execute(this::recoverMucRetractionRetirements);
                mDatabaseWriterExecutor.execute(this::recoverVerifiedMucRetractions);
                SecureColdStartPerfTrace.stage(
                        "secure_text_coordinator_init", System.nanoTime() - coordinatorStarted);
            }
            return secureMessagePayloadCoordinator;
        }
    }

    /**
     * Returns the opt-in U4 protected outgoing-text producer only while the message-payload rollout
     * remains enabled. This composition point is for a future background repository; no legacy
     * send, renderer, parser or XMPP wire path calls it.
     */
    @Nullable
    public SecureOutgoingTextPayloadPublisher getSecureOutgoingTextPayloadPublisher() {
        final SecureMessagePayloadCoordinator coordinator = getSecureMessagePayloadCoordinator();
        return coordinator == null ? null : new SecureOutgoingTextPayloadPublisher(coordinator);
    }

    /**
     * Returns the U4.2 full-verification reader for the selected protected outgoing-text class. It
     * is deliberately not connected to any legacy Message.body renderer.
     */
    @Nullable
    public SecureOutgoingTextPayloadReader getSecureOutgoingTextPayloadReader() {
        final SecureMessagePayloadCoordinator coordinator = getSecureMessagePayloadCoordinator();
        return coordinator == null ? null : new SecureOutgoingTextPayloadReader(coordinator);
    }

    /** Background-only protected text repository. It is never consulted from adapter binding. */
    @Nullable
    public SecureMessageTextRepository getSecureMessageTextRepository() {
        final SecureMessagePayloadCoordinator coordinator = getSecureMessagePayloadCoordinator();
        if (coordinator == null) {
            return null;
        }
        final SecureMessageTextRepository current = secureMessageTextRepository;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (secureMessageTextRepository == null) {
                secureMessageTextRepository =
                        new SecureMessageTextRepository(coordinator, databaseBackend);
            }
            return secureMessageTextRepository;
        }
    }

    private boolean isSecureContentAccountAvailableForMutation(final Account account) {
        return account != null
                && !secureContentAccountCleanupInProgress.contains(account.getUuid())
                && findAccountByUuid(account.getUuid()) == account;
    }

    boolean isSecureContentAccountAvailableForMutation(final Message message) {
        return message != null
                && message.getConversation() != null
                && isSecureContentAccountAvailableForMutation(
                        message.getConversation().getAccount());
    }

    Object secureContentMediaMutationLock() {
        return secureContentMediaMutationLock;
    }

    private void beginSecureContentAccountCleanup(final Account account) {
        secureContentAccountCleanupInProgress.add(account.getUuid());
        mHttpConnectionManager.beginAccountCleanup(account);
        mJingleConnectionManager.beginAccountCleanup(account);
    }

    private void endSecureContentAccountCleanup(final Account account) {
        mHttpConnectionManager.endAccountCleanup(account);
        mJingleConnectionManager.endAccountCleanup(account);
        secureContentAccountCleanupInProgress.remove(account.getUuid());
    }

    @FunctionalInterface
    private interface SecureContentCleanupOperation {
        void run() throws Exception;
    }

    /**
     * Drains account-owned secure-text producers and legacy migration before destructive account
     * cleanup. New mutations are already rejected by the account cleanup gate.
     */
    private void runWithSecureContentMutationsQuiesced(
            final SecureContentCleanupOperation operation) throws Exception {
        synchronized (secureContentMigrationCleanupLock) {
            final SecureMessageTextRepository repository = secureMessageTextRepository;
            if (repository == null) {
                synchronized (secureContentMediaMutationLock) {
                    operation.run();
                }
                return;
            }
            synchronized (repository) {
                synchronized (secureContentMediaMutationLock) {
                    operation.run();
                }
            }
        }
    }

    /**
     * Persists incoming plain text through the Store while the explicit rollout is enabled.
     * Publication failures remain unavailable and never fall back to Message.body persistence.
     */
    public boolean persistIncomingMessage(final Message message) {
        if (!isProtectedTextCandidate(message)) {
            databaseBackend.createMessage(message);
            return true;
        }
        if (!isSecureContentAccountAvailableForMutation(message)) {
            return false;
        }
        final SecureMessageTextRepository repository = getSecureMessageTextRepository();
        if (repository == null) {
            return false;
        }
        synchronized (repository) {
            if (!isSecureContentAccountAvailableForMutation(message)) {
                return false;
            }
            try {
                repository.publishInitial(message);
                return true;
            } catch (IOException | RuntimeException e) {
                Log.w(Config.LOGTAG, "unable to publish incoming protected text", e);
                return false;
            }
        }
    }

    /** Replaces an incoming protected body while preserving its logical message ownership. */
    public boolean replaceIncomingProtectedMessage(
            final Message message, final String expectedMessageUuid) {
        if (!isSecureContentAccountAvailableForMutation(message)) {
            return false;
        }
        final SecureMessageTextRepository repository = getSecureMessageTextRepository();
        if (repository == null || !message.hasProtectedTextPayload()) {
            return false;
        }
        synchronized (repository) {
            if (!isSecureContentAccountAvailableForMutation(message)) {
                return false;
            }
            try {
                repository.replace(message);
                final boolean updated = databaseBackend.updateMessage(message, expectedMessageUuid);
                if (updated) {
                    markTimelineChanged(message);
                }
                return updated;
            } catch (IOException | RuntimeException e) {
                Log.w(Config.LOGTAG, "unable to replace incoming protected text", e);
                return false;
            }
        }
    }

    private static boolean isProtectedTextCandidate(final Message message) {
        return !message.isModerated()
                && Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT
                && !message.needsUploading()
                && !message.isFileOrImage()
                && !message.isOOb()
                && !message.treatAsDownloadable()
                && (message.getType() == Message.TYPE_TEXT
                        || message.getType() == Message.TYPE_PRIVATE);
    }

    private boolean prepareProtectedTextForSend(final Message message, final boolean resend) {
        if (!isProtectedTextCandidate(message)) {
            return true;
        }
        if (!isSecureContentAccountAvailableForMutation(message)) {
            return false;
        }
        final SecureMessageTextRepository repository = getSecureMessageTextRepository();
        if (repository == null) {
            return false;
        }
        synchronized (repository) {
            if (!isSecureContentAccountAvailableForMutation(message)) {
                return false;
            }
            try {
                boolean publishedInThisCall = false;
                if (message.hasProtectedTextPayload()) {
                    if (message.edited() && !resend) {
                        repository.replace(message);
                        publishedInThisCall = true;
                    }
                } else if (!resend) {
                    repository.publishInitial(message);
                    publishedInThisCall = true;
                } else {
                    // Historical unclassified messages retain their legacy retry semantics.
                    return true;
                }
                // Publication completed a verified Store read and hydrated Message.body. Reading
                // the
                // same large payload again here used to duplicate the entire post-commit hot path.
                return publishedInThisCall
                        || !message.hasProtectedTextPayload()
                        || repository.resolveForTransport(message) != null;
            } catch (IOException | RuntimeException e) {
                Log.w(Config.LOGTAG, "unable to prepare protected outgoing text", e);
                return false;
            }
        }
    }

    /**
     * Keeps Store write/commit and verification off the composer thread for protected text. The
     * single executor preserves send order and calls the failure callback at most once.
     */
    public boolean sendMessageWithSecureTextPreparation(
            final Message message, @Nullable final Runnable failureCallback) {
        if (!isProtectedTextCandidate(message)) {
            return false;
        }
        final AtomicBoolean failureDelivered = new AtomicBoolean(false);
        if (!isSecureContentAccountAvailableForMutation(message)) {
            if (failureCallback != null && failureDelivered.compareAndSet(false, true)) {
                failureCallback.run();
            }
            return true;
        }
        secureTextSendExecutor.execute(
                () -> {
                    if (!isSecureContentAccountAvailableForMutation(message)) {
                        if (failureCallback != null
                                && failureDelivered.compareAndSet(false, true)) {
                            failureCallback.run();
                        }
                        return;
                    }
                    final long startedAt = SystemClock.elapsedRealtime();
                    final int payloadBytes = secureTextPayloadBytes(message);
                    sendMessage(message);
                    final boolean failed = message.getStatus() == Message.STATUS_SEND_FAILED;
                    SecureTextPayload.logStage(
                            payloadBytes, "send_path", startedAt, failed ? "send_failed" : null);
                    if (failed
                            && failureCallback != null
                            && failureDelivered.compareAndSet(false, true)) {
                        failureCallback.run();
                    }
                });
        return true;
    }

    private static int secureTextPayloadBytes(final Message message) {
        try {
            return SecureTextPayload.utf8ByteCount(
                    message.getBody(), SecureTextPayload.MAXIMUM_BYTES);
        } catch (final SecureTextPayloadTooLargeException e) {
            return e.getObservedBytes();
        } catch (final IOException e) {
            return 0;
        }
    }

    public AvatarService getAvatarService() {
        return this.mAvatarService;
    }

    public void attachLocationToConversation(
            final Conversation conversation, final Uri uri, final UiCallback<Message> callback) {
        final Message message =
                new Message(conversation, uri.toString(), conversation.getNextEncryption());
        Message.configurePrivateMessage(message);
        sendMessage(message);
        callback.success(message);
    }

    public void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback) {
        attachFileToConversation(conversation, uri, type, callback, null);
    }

    public void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId) {
        attachFileToConversation(conversation, uri, type, callback, mediaGroupId, null);
    }

    public void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId,
            @Nullable final String mediaSendBatchId) {
        attachFileToConversation(
                conversation, uri, type, callback, mediaGroupId, mediaSendBatchId, null);
    }

    public void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId,
            @Nullable final String mediaSendBatchId,
            @Nullable final String mediaCaptionId) {
        attachFileToConversation(
                conversation,
                uri,
                type,
                callback,
                mediaGroupId,
                mediaSendBatchId,
                mediaCaptionId,
                null);
    }

    public void attachVoiceRecordingToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final SecureOutgoingVoiceStagingRetirer stagingRetirer) {
        attachVoiceRecordingToConversation(
                conversation, uri, type, callback, null, null, null, stagingRetirer);
    }

    public void attachVoiceRecordingToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId,
            @Nullable final String mediaSendBatchId,
            @Nullable final String mediaCaptionId,
            @Nullable final SecureOutgoingVoiceStagingRetirer stagingRetirer) {
        attachFileToConversation(
                conversation,
                uri,
                type,
                callback,
                mediaGroupId,
                mediaSendBatchId,
                mediaCaptionId,
                stagingRetirer);
    }

    private void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId,
            @Nullable final String mediaSendBatchId,
            @Nullable final String mediaCaptionId,
            @Nullable final SecureOutgoingAttachmentStagingRetirer stagingRetirer) {
        final Message message;
        if (conversation.getReplyTo() == null) {
            message = new Message(conversation, "", conversation.getNextEncryption());
        } else {
            message = conversation.getReplyTo().reply();
            message.setEncryption(conversation.getNextEncryption());
        }

        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_FILE);
        }
        configureLocalMediaGroup(message, mediaGroupId);
        registerMediaSendBatchMember(
                mediaSendBatchId,
                message,
                isMediaSendBatchMime(MimeUtils.guessMimeTypeFromUriAndMime(this, uri, type)));
        final SecureOutgoingAttachmentStagingRetirer effectiveStagingRetirer =
                stagingRetirer != null
                        ? stagingRetirer
                        : VideoAttachmentStaging.retirerForUri(this, uri);
        final AttachFileToConversationRunnable runnable =
                new AttachFileToConversationRunnable(
                        this,
                        uri,
                        type,
                        message,
                        callback,
                        mediaSendBatchId,
                        mediaCaptionId,
                        effectiveStagingRetirer);
        if (runnable.isVideoMessage()) {
            VIDEO_COMPRESSION_EXECUTOR.execute(runnable);
        } else {
            FILE_ATTACHMENT_EXECUTOR.execute(runnable);
        }
    }

    public void attachImageToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback) {
        attachImageToConversation(conversation, uri, type, callback, null);
    }

    public void attachImageToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId) {
        attachImageToConversation(conversation, uri, type, callback, mediaGroupId, null);
    }

    public void attachImageToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId,
            @Nullable final String mediaSendBatchId) {
        attachImageToConversation(
                conversation, uri, type, callback, mediaGroupId, mediaSendBatchId, null);
    }

    public void attachImageToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final UiCallback<Message> callback,
            @Nullable final String mediaGroupId,
            @Nullable final String mediaSendBatchId,
            @Nullable final String mediaCaptionId) {
        final String mimeType = MimeUtils.guessMimeTypeFromUriAndMime(this, uri, type);
        final String compressPictures = getCompressPicturesPreference();
        final SecureOutgoingAttachmentStagingRetirer sourceStagingRetirer =
                Config.SECURE_CONTENT_MEDIA_ROLLOUT
                        ? ImageAttachmentStaging.retirerForUri(this, uri)
                        : null;

        if ("never".equals(compressPictures)
                || ("auto".equals(compressPictures) && getFileBackend().useImageAsIs(uri))
                || (mimeType != null && mimeType.endsWith("/gif"))
                || getFileBackend().unusualBounds(uri)) {
            attachFileToConversation(
                    conversation,
                    uri,
                    mimeType,
                    callback,
                    mediaGroupId,
                    mediaSendBatchId,
                    mediaCaptionId,
                    sourceStagingRetirer);
            return;
        }
        final Message message;
        if (conversation.getReplyTo() == null) {
            message = new Message(conversation, "", conversation.getNextEncryption());
        } else {
            message = conversation.getReplyTo().reply();
            message.setEncryption(conversation.getNextEncryption());
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_IMAGE);
        }
        configureLocalMediaGroup(message, mediaGroupId);
        registerMediaSendBatchMember(mediaSendBatchId, message, true);
        FILE_ATTACHMENT_EXECUTOR.execute(
                () -> {
                    File secureImageStaging = null;
                    try {
                        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                            secureImageStaging =
                                    ImageAttachmentStaging.createTransformedFile(
                                            this, message.getUuid(), compressedImageExtension());
                            getFileBackend().copyImageToPrivateStorage(secureImageStaging, uri);
                            final File committedSource = secureImageStaging;
                            final SecureOutgoingAttachmentStagingRetirer stagingRetirer =
                                    ImageAttachmentStaging.combine(
                                            () ->
                                                    ImageAttachmentStaging.retire(
                                                            this, committedSource),
                                            sourceStagingRetirer);
                            new AttachFileToConversationRunnable(
                                            this,
                                            FileBackend.getUriForFile(this, committedSource),
                                            compressedImageMimeType(),
                                            message,
                                            withImageStagingFailureCleanup(
                                                    committedSource, callback),
                                            mediaSendBatchId,
                                            mediaCaptionId,
                                            stagingRetirer)
                                    .run();
                            return;
                        }
                        getFileBackend().copyImageToPrivateStorage(message, uri);
                    } catch (FileBackend.ImageCompressionException e) {
                        if (secureImageStaging != null) {
                            ImageAttachmentStaging.retire(this, secureImageStaging);
                        }
                        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                            message.setType(Message.TYPE_FILE);
                        }
                        final AttachFileToConversationRunnable runnable =
                                new AttachFileToConversationRunnable(
                                        this,
                                        uri,
                                        mimeType,
                                        message,
                                        callback,
                                        mediaSendBatchId,
                                        mediaCaptionId,
                                        sourceStagingRetirer);
                        runnable.run();
                        return;
                    } catch (final FileBackend.FileCopyException e) {
                        if (secureImageStaging != null) {
                            ImageAttachmentStaging.retire(this, secureImageStaging);
                        }
                        failMediaSendBatchMessage(message, mediaSendBatchId, mediaCaptionId);
                        callback.error(e.getResId(), message);
                        return;
                    }
                    sendPreparedMediaMessage(message, mediaSendBatchId, mediaCaptionId);
                    callback.success(message);
                });
    }

    private UiCallback<Message> withImageStagingFailureCleanup(
            final File staging, final UiCallback<Message> delegate) {
        return new UiCallback<Message>() {
            @Override
            public void userInputRequired(
                    final PendingIntent pendingIntent, final Message message) {
                retireFailedImageStaging(staging);
                delegate.userInputRequired(pendingIntent, message);
            }

            @Override
            public void success(final Message message) {
                delegate.success(message);
            }

            @Override
            public void error(final int errorCode, final Message message) {
                retireFailedImageStaging(staging);
                delegate.error(errorCode, message);
            }
        };
    }

    private void retireFailedImageStaging(final File staging) {
        if (!ImageAttachmentStaging.retire(this, staging)) {
            Log.w(Config.LOGTAG, "unable to retire failed outgoing image staging");
        }
    }

    private static String compressedImageExtension() {
        switch (Config.IMAGE_FORMAT) {
            case JPEG:
                return "jpg";
            case PNG:
                return "png";
            case WEBP:
                return "webp";
            default:
                throw new IllegalStateException("Unknown image format");
        }
    }

    private static String compressedImageMimeType() {
        return "image/"
                + ("jpg".equals(compressedImageExtension()) ? "jpeg" : compressedImageExtension());
    }

    public boolean supportsMessageAttaching(final Conversation conversation) {
        // XEP-0367 attachment relations are additive metadata on otherwise ordinary direct-chat
        // messages. Do not make album/caption semantics depend on transient presence/disco state:
        // a second resource may be offline, stale, or not have completed disco yet, which used to
        // make the same user-initiated gallery nondeterministically fall back to unrelated media
        // messages. Peers that do not understand the relation still receive the normal media/text
        // messages and simply ignore the unknown attach-to payload.
        return conversation != null
                && conversation.getMode() == Conversation.MODE_SINGLE
                && conversation.getReplyTo() == null;
    }

    /**
     * Registers a non-empty draft caption as ephemeral send state. The token is never persisted or
     * serialized; the coordinator releases the ordinary text only after media dispatch.
     */
    @Nullable
    public String beginOutgoingMediaCaption(final Conversation conversation, final String body) {
        return outgoingMediaCaptionCoordinator.register(
                conversation, body, supportsMessageAttaching(conversation));
    }

    /** Starts a local batch; the token is neither stored nor sent over XMPP. */
    @Nullable
    public String beginMediaSendBatch(
            final Conversation conversation, final int expectedMediaMessages) {
        if (expectedMediaMessages < 2) {
            return null;
        }
        final String batchId = UUID.randomUUID().toString();
        mediaSendBatches.put(
                batchId,
                new MediaSendBatch(
                        batchId, expectedMediaMessages, supportsMessageAttaching(conversation)));
        return batchId;
    }

    private void registerMediaSendBatchMember(
            @Nullable final String batchId, final Message message, final boolean eligibleMedia) {
        if (batchId == null) {
            return;
        }
        final MediaSendBatch batch = mediaSendBatches.get(batchId);
        if (batch != null) {
            batch.register(message, eligibleMedia);
        }
    }

    void sendPreparedMediaMessage(final Message message, @Nullable final String batchId) {
        sendPreparedMediaMessage(message, batchId, null);
    }

    void sendPreparedMediaMessage(
            final Message message,
            @Nullable final String batchId,
            @Nullable final String mediaCaptionId) {
        if (batchId == null) {
            sendPreparedMediaMessagesAtomically(
                    Collections.singletonList(message),
                    takeMediaCaption(
                            mediaCaptionId, message, isMediaSendBatchMime(message.getMimeType())));
            return;
        }
        final MediaSendBatch batch = mediaSendBatches.get(batchId);
        if (batch == null) {
            sendPreparedMediaMessagesAtomically(
                    Collections.singletonList(message),
                    takeMediaCaption(mediaCaptionId, message, false));
            return;
        }
        final List<Message> readyMessages = batch.onReady(message);
        if (!readyMessages.isEmpty()) {
            final Message anchor = batch.getAnchorMessage();
            sendPreparedMediaMessagesAtomically(
                    readyMessages, takeMediaCaption(mediaCaptionId, anchor, anchor != null));
        } else if (batch.isComplete()) {
            failMediaCaption(mediaCaptionId);
        }
        removeCompletedMediaSendBatch(batchId, batch);
    }

    void failMediaSendBatchMessage(final Message message, @Nullable final String batchId) {
        failMediaSendBatchMessage(message, batchId, null);
    }

    void failMediaSendBatchMessage(
            final Message message,
            @Nullable final String batchId,
            @Nullable final String mediaCaptionId) {
        if (batchId == null) {
            failMediaCaption(mediaCaptionId);
            return;
        }
        final MediaSendBatch batch = mediaSendBatches.get(batchId);
        if (batch == null) {
            failMediaCaption(mediaCaptionId);
            return;
        }
        final List<Message> readyMessages = batch.onFailed(message);
        if (!readyMessages.isEmpty()) {
            final Message anchor = batch.getAnchorMessage();
            sendPreparedMediaMessagesAtomically(
                    readyMessages, takeMediaCaption(mediaCaptionId, anchor, anchor != null));
        } else if (batch.isComplete()) {
            failMediaCaption(mediaCaptionId);
        }
        removeCompletedMediaSendBatch(batchId, batch);
    }

    @Nullable
    private Message takeMediaCaption(
            @Nullable final String mediaCaptionId,
            @Nullable final Message anchor,
            final boolean relationReleased) {
        return outgoingMediaCaptionCoordinator.onMediaReleased(
                mediaCaptionId, anchor, relationReleased);
    }

    private void failMediaCaption(@Nullable final String mediaCaptionId) {
        outgoingMediaCaptionCoordinator.discard(mediaCaptionId);
    }

    private void sendPreparedMediaMessagesAtomically(
            final List<Message> mediaMessages, @Nullable final Message caption) {
        if (mediaMessages.isEmpty() && caption == null) {
            return;
        }
        boolean deferCaption = false;
        if (caption != null) {
            for (final Message mediaMessage : mediaMessages) {
                if (mediaMessage.needsUploading()) {
                    deferCaption = true;
                    break;
                }
            }
        }
        final int previousSuppression = suppressedConversationUiUpdates.get();
        suppressedConversationUiUpdates.set(previousSuppression + 1);
        try {
            for (final Message mediaMessage : mediaMessages) {
                sendMessage(mediaMessage);
            }
            if (caption != null) {
                if (deferCaption) {
                    // Publish/persist through the normal text pipeline, including protected-text
                    // SCS handling, but keep the stanza local until every related media transport
                    // has actually been released.
                    sendMessage(caption, false, false, false, true);
                    // A very small upload can theoretically finish on another executor before the
                    // held caption has been inserted into the conversation. Reconcile once after
                    // registration so that an already-completed single media transfer cannot leave
                    // its caption stuck in STATUS_WAITING forever. This is also safe for albums:
                    // releaseDeferredMediaCaptions() checks the whole local media transaction.
                    for (final Message mediaMessage : mediaMessages) {
                        releaseDeferredMediaCaptions(mediaMessage);
                    }
                } else {
                    sendMessage(caption);
                }
            }
        } finally {
            if (previousSuppression == 0) {
                suppressedConversationUiUpdates.remove();
                updateConversationUi();
            } else {
                suppressedConversationUiUpdates.set(previousSuppression);
            }
        }
    }

    private void removeCompletedMediaSendBatch(final String batchId, final MediaSendBatch batch) {
        if (batch.isComplete()) {
            mediaSendBatches.remove(batchId, batch);
        }
    }

    private static boolean isMediaSendBatchMime(@Nullable final String mimeType) {
        return mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"));
    }

    private static void configureLocalMediaGroup(
            final Message message, @Nullable final String mediaGroupId) {
        message.setMediaGroupId(mediaGroupId);
    }

    public Conversation find(final Account account, final Jid jid, final Jid counterpart) {
        return find(getConversations(), account, jid, counterpart);
    }

    public List<Conversation> findAll(final Account account, final Jid jid) {
        return findAll(getConversations(), account, jid);
    }

    public boolean isMuc(final Account account, final Jid jid) {
        final Conversation c = find(account, jid, null);
        return c != null && c.getMode() == Conversational.MODE_MULTI;
    }

    public void search(
            final List<String> term,
            final String uuid,
            final OnSearchResultsAvailable onSearchResultsAvailable) {
        MessageSearchTask.search(this, term, uuid, onSearchResultsAvailable);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        final String action = Strings.nullToEmpty(intent == null ? null : intent.getAction());
        final boolean needsForegroundService =
                intent != null
                        && intent.getBooleanExtra(
                                SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, false);
        if (needsForegroundService) {
            Log.d(
                    Config.LOGTAG,
                    "toggle forced foreground service after receiving event (action="
                            + action
                            + ")");
            toggleForegroundService(true);
        }
        final String uuid = intent == null ? null : intent.getStringExtra("uuid");
        switch (action) {
            case QuickConversationsService.SMS_RETRIEVED_ACTION:
                mQuickConversationsService.handleSmsReceived(intent);
                break;
            case ConnectivityManager.CONNECTIVITY_ACTION:
                {
                    final ConnectivityManager connectivityManager =
                            ContextCompat.getSystemService(this, ConnectivityManager.class);
                    final Network activeNetwork =
                            connectivityManager == null
                                    ? null
                                    : connectivityManager.getActiveNetwork();
                    handleActiveNetworkChange(activeNetwork, "broadcast");
                    break;
                }
            case Intent.ACTION_SHUTDOWN:
                logoutAndSave(true);
                return START_NOT_STICKY;
            case ACTION_CLEAR_MESSAGE_NOTIFICATION:
                mNotificationExecutor.execute(
                        () -> {
                            try {
                                final Conversation c = findConversationByUuid(uuid);
                                if (c != null) {
                                    mNotificationService.clearMessages(c);
                                } else {
                                    mNotificationService.clearMessages();
                                }
                                restoredFromDatabaseLatch.await();

                            } catch (InterruptedException e) {
                                Log.d(
                                        Config.LOGTAG,
                                        "unable to process clear message notification");
                            }
                        });
                break;
            case ACTION_CLEAR_MISSED_CALL_NOTIFICATION:
                mNotificationExecutor.execute(
                        () -> {
                            try {
                                final Conversation c = findConversationByUuid(uuid);
                                if (c != null) {
                                    mNotificationService.clearMissedCalls(c);
                                } else {
                                    mNotificationService.clearMissedCalls();
                                }
                                restoredFromDatabaseLatch.await();

                            } catch (InterruptedException e) {
                                Log.d(
                                        Config.LOGTAG,
                                        "unable to process clear missed call notification");
                            }
                        });
                break;
            case ACTION_DISMISS_CALL:
                {
                    if (intent == null) {
                        break;
                    }
                    final String sessionId =
                            intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                    Log.d(
                            Config.LOGTAG,
                            "received intent to dismiss call with session id " + sessionId);
                    mJingleConnectionManager.rejectRtpSession(sessionId);
                    break;
                }
            case TorServiceUtils.ACTION_STATUS:
                final String status =
                        intent == null ? null : intent.getStringExtra(TorServiceUtils.EXTRA_STATUS);
                // TODO port and host are in 'extras' - but this may not be a reliable source?
                if ("ON".equals(status)) {
                    handleOrbotStartedEvent();
                    return START_STICKY;
                }
                break;
            case ACTION_END_CALL:
                {
                    if (intent == null) {
                        break;
                    }
                    final String sessionId =
                            intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                    Log.d(
                            Config.LOGTAG,
                            "received intent to end call with session id " + sessionId);
                    mJingleConnectionManager.endRtpSession(sessionId);
                }
                break;
            case ACTION_PROVISION_ACCOUNT:
                {
                    if (intent == null) {
                        break;
                    }
                    final String address = intent.getStringExtra("address");
                    final String password = intent.getStringExtra("password");
                    if (QuickConversationsService.isQuicksy()
                            || Strings.isNullOrEmpty(address)
                            || Strings.isNullOrEmpty(password)) {
                        break;
                    }
                    provisionAccount(address, password);
                    break;
                }
            case ACTION_DISMISS_ERROR_NOTIFICATIONS:
                dismissErrorNotifications();
                break;
            case ACTION_TRY_AGAIN:
                resetAllAttemptCounts(false, true);
                break;
            case ACTION_REPLY_TO_CONVERSATION:
                final Bundle remoteInput =
                        intent == null ? null : RemoteInput.getResultsFromIntent(intent);
                if (remoteInput == null) {
                    break;
                }
                final CharSequence body = remoteInput.getCharSequence("text_reply");
                final boolean dismissNotification =
                        intent.getBooleanExtra("dismiss_notification", false);
                final String lastMessageUuid = intent.getStringExtra("last_message_uuid");
                if (body == null || body.length() <= 0) {
                    break;
                }
                mNotificationExecutor.execute(
                        () -> {
                            try {
                                restoredFromDatabaseLatch.await();
                                final Conversation c = findConversationByUuid(uuid);
                                if (c != null) {
                                    directReply(
                                            c,
                                            body.toString(),
                                            lastMessageUuid,
                                            dismissNotification);
                                }
                            } catch (InterruptedException e) {
                                Log.d(Config.LOGTAG, "unable to process direct reply");
                            }
                        });
                break;
            case ACTION_MARK_AS_READ:
                mNotificationExecutor.execute(
                        () -> {
                            final Conversation c = findConversationByUuid(uuid);
                            if (c == null) {
                                Log.d(
                                        Config.LOGTAG,
                                        "received mark read intent for unknown conversation ("
                                                + uuid
                                                + ")");
                                return;
                            }
                            try {
                                restoredFromDatabaseLatch.await();
                                sendReadMarker(c, null);
                            } catch (InterruptedException e) {
                                Log.d(
                                        Config.LOGTAG,
                                        "unable to process notification read marker for"
                                                + " conversation "
                                                + c.getName());
                            }
                        });
                break;
            case ACTION_SNOOZE:
                mNotificationExecutor.execute(
                        () -> {
                            final Conversation c = findConversationByUuid(uuid);
                            if (c == null) {
                                Log.d(
                                        Config.LOGTAG,
                                        "received snooze intent for unknown conversation ("
                                                + uuid
                                                + ")");
                                return;
                            }
                            c.setMutedTill(System.currentTimeMillis() + 30 * 60 * 1000);
                            mNotificationService.clearMessages(c);
                            updateConversation(c);
                        });
            case AudioManager.RINGER_MODE_CHANGED_ACTION:
            case NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED:
                break;
            case Intent.ACTION_SCREEN_ON:
                deactivateGracePeriod();
                break;
            case Intent.ACTION_USER_PRESENT:
            case Intent.ACTION_SCREEN_OFF:
                break;
            case ACTION_FCM_TOKEN_REFRESH:
                refreshAllFcmTokens();
                break;
            case ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS:
                if (intent == null) {
                    break;
                }
                final String instance = intent.getStringExtra("instance");
                final String application = intent.getStringExtra("application");
                final Messenger messenger = intent.getParcelableExtra("messenger");
                final UnifiedPushBroker.PushTargetMessenger pushTargetMessenger;
                if (messenger != null && application != null && instance != null) {
                    pushTargetMessenger =
                            new UnifiedPushBroker.PushTargetMessenger(
                                    new UnifiedPushDatabase.PushTarget(application, instance),
                                    messenger);
                    Log.d(Config.LOGTAG, "found push target messenger");
                } else {
                    pushTargetMessenger = null;
                }
                final Optional<UnifiedPushBroker.Transport> transport =
                        renewUnifiedPushEndpoints(pushTargetMessenger);
                if (instance != null && transport.isPresent()) {
                    unifiedPushBroker.rebroadcastEndpoint(messenger, instance, transport.get());
                }
                break;
            case ACTION_IDLE_PING:
                scheduleNextIdlePing();
                break;
            case ACTION_SECURE_CONTENT_UNLOCKED:
                scheduleResidentProtectedTextRehydrateAfterCryptoUnlock();
                scheduleSecureContentRelationBackfill();
                break;
            case ACTION_FCM_MESSAGE_RECEIVED:
                Log.d(Config.LOGTAG, "push message arrived in service. account");
                break;
            case ACTION_QUICK_LOG:
                final String message = intent == null ? null : intent.getStringExtra("message");
                if (message != null && Config.QUICK_LOG) {
                    quickLog(message);
                }
                break;
            case Intent.ACTION_SEND:
                final Uri uri = intent == null ? null : intent.getData();
                if (uri != null) {
                    Log.d(Config.LOGTAG, "received uri permission for " + uri);
                }
                return START_STICKY;
            case ACTION_TEMPORARILY_DISABLE:
                toggleSoftDisabled(true);
                if (checkListeners()) {
                    stopSelf();
                }
                return START_NOT_STICKY;
        }
        final var extras = intent == null ? null : intent.getExtras();
        try {
            internalPingExecutor.execute(() -> manageAccountConnectionStates(action, extras));
        } catch (final RejectedExecutionException e) {
            Log.e(Config.LOGTAG, "can not schedule connection states manager");
        }
        if (SystemClock.elapsedRealtime() - mLastExpiryRun.get() >= Config.EXPIRY_INTERVAL) {
            expireOldMessages();
        }
        return START_STICKY;
    }

    private void quickLog(final String message) {
        if (Strings.isNullOrEmpty(message)) {
            return;
        }
        ShareUtil.share(getApplication(), message);
    }

    private void manageAccountConnectionStatesInternal() {
        manageAccountConnectionStates(ACTION_INTERNAL_PING, null);
    }

    private synchronized void manageAccountConnectionStates(
            final String action, final Bundle extras) {
        if (isSecureConnectivityBlocked()) {
            enforceSecureConnectivityBlocked();
            return;
        }
        scheduleResidentProtectedTextRehydrateAfterCryptoUnlock();
        final var accountSecretMigration =
                new AccountSecretMigrationCoordinatorV1(getApplicationContext(), databaseBackend);
        final HashSet<Account> accountsWithUnavailableSecrets = new HashSet<>();
        for (final Account account : accounts) {
            if (!account.isSecretVaultHydrated()) {
                try {
                    final var outcome = accountSecretMigration.migrateAndHydrate(account);
                    if (outcome == AccountSecretMigrationOutcomeV1.LOCKED_OR_UNAVAILABLE
                            || outcome == AccountSecretMigrationOutcomeV1.FAILED) {
                        accountsWithUnavailableSecrets.add(account);
                        Log.w(
                                Config.LOGTAG,
                                "protected account secrets unavailable; deferring one account");
                    }
                } catch (final RuntimeException | AssertionError e) {
                    accountsWithUnavailableSecrets.add(account);
                    Log.e(
                            Config.LOGTAG,
                            "protected account secret hydration failed; deferring one account",
                            e);
                }
            }
        }
        final String pushedAccountHash = extras == null ? null : extras.getString("account");
        final boolean interactive = java.util.Objects.equals(ACTION_TRY_AGAIN, action);
        WakeLockHelper.acquire(wakeLock);
        boolean pingNow =
                ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                        || (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0
                                && ACTION_POST_CONNECTIVITY_CHANGE.equals(action));
        final HashSet<Account> pingCandidates = new HashSet<>();
        final String androidId = pushedAccountHash == null ? null : PhoneHelper.getAndroidId(this);
        for (final Account account : accounts) {
            if (accountsWithUnavailableSecrets.contains(account)) {
                enforceSecureConnectivityBlocked(account);
                continue;
            }
            try {
                final boolean pushWasMeantForThisAccount =
                        androidId != null
                                && CryptoHelper.getAccountFingerprint(account, androidId)
                                        .equals(pushedAccountHash);
                pingNow |=
                        processAccountState(
                                account,
                                interactive,
                                "ui".equals(action),
                                pushWasMeantForThisAccount,
                                pingCandidates);
            } catch (final RuntimeException | AssertionError e) {
                Log.e(
                        Config.LOGTAG,
                        "account connection processing failed; keeping other accounts alive",
                        e);
                enforceSecureConnectivityBlocked(account);
            }
        }
        if (pingNow) {
            for (final Account account : pingCandidates) {
                final var connection = account.getXmppConnection();
                final boolean lowTimeout = isInLowPingTimeoutMode(account);
                final var delta =
                        (SystemClock.elapsedRealtime() - connection.getLastPacketReceived())
                                / 1000L;
                connection.sendPing();
                Log.d(
                        Config.LOGTAG,
                        String.format(
                                "%s: send ping (action=%s,lowTimeout=%s,interval=%s)",
                                account.getJid().asBareJid(), action, lowTimeout, delta));
                scheduleWakeUpCall(
                        lowTimeout ? Config.LOW_PING_TIMEOUT : Config.PING_TIMEOUT,
                        account.getUuid().hashCode());
            }
        }
        WakeLockHelper.release(wakeLock);
        scheduleLegacyPlaintextBackgroundMigration();
    }

    private void scheduleLegacyPlaintextBackgroundMigration() {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT
                || destroyed
                || restoredFromDatabaseLatch.getCount() != 0
                || !checkListeners()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final long restoreCompleted = messageRestoreCompletedAt.get();
        if (restoreCompleted <= 0L
                || now - restoreCompleted < LEGACY_BACKGROUND_MIGRATION_STARTUP_GRACE_MS) {
            return;
        }
        final long previous = legacyBackgroundMigrationLastScheduledAt.get();
        if (previous > 0L && now - previous < LEGACY_BACKGROUND_MIGRATION_CADENCE_MS) {
            return;
        }
        if (!SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                getApplicationContext())) {
            return;
        }
        if (!legacyBackgroundMigrationScheduled.compareAndSet(false, true)) {
            return;
        }
        legacyBackgroundMigrationLastScheduledAt.set(now);
        legacyTextMigrationExecutor.execute(
                () -> {
                    try {
                        synchronized (secureContentMigrationCleanupLock) {
                            runLegacyPlaintextBackgroundMigrationBatch();
                        }
                    } catch (final RuntimeException exception) {
                        Log.w(
                                Config.LOGTAG,
                                "background legacy plaintext migration failed",
                                exception);
                    } finally {
                        legacyBackgroundMigrationScheduled.set(false);
                    }
                });
    }

    private void runLegacyPlaintextBackgroundMigrationBatch() {
        if (destroyed
                || !checkListeners()
                || !SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                        getApplicationContext())) {
            return;
        }
        final SecureMessagePayloadCoordinator coordinator = getSecureMessagePayloadCoordinator();
        if (coordinator == null || accounts == null || accounts.isEmpty()) {
            return;
        }

        final List<Account> eligibleAccounts = new ArrayList<>();
        for (final Account account : accounts) {
            if (account != null
                    && account.isConnectionEnabled()
                    && isSecureContentAccountAvailableForMutation(account)) {
                eligibleAccounts.add(account);
            }
        }
        if (eligibleAccounts.isEmpty()) {
            maybeRunLegacyPlaintextSqliteCleanup();
            return;
        }

        final long cursor = legacyBackgroundMigrationAccountCursor.getAndIncrement();
        final int start = (int) Math.floorMod(cursor, (long) eligibleAccounts.size());
        final LegacyPlaintextMessageMigrator migrator =
                new LegacyPlaintextMessageMigrator(databaseBackend, coordinator);
        for (int offset = 0; offset < eligibleAccounts.size(); ++offset) {
            if (destroyed
                    || !checkListeners()
                    || !SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                            getApplicationContext())) {
                return;
            }
            final Account account =
                    eligibleAccounts.get((start + offset) % eligibleAccounts.size());
            if (!isSecureContentAccountAvailableForMutation(account)) {
                continue;
            }
            final LegacyPlaintextMessageMigrator.BatchResult result =
                    migrator.migrateNewestBackgroundBatch(
                            account.getUuid(),
                            LegacyPlaintextMessageMigrator.DEFAULT_BACKGROUND_BATCH_SIZE);
            if (result.getSelected() > 0) {
                Log.d(
                        Config.LOGTAG,
                        "background legacy plaintext migration selected="
                                + result.getSelected()
                                + " migrated="
                                + result.getMigrated()
                                + " skipped="
                                + result.getSkipped()
                                + " failed="
                                + result.getFailed());
                return;
            }
        }

        for (int offset = 0; offset < eligibleAccounts.size(); ++offset) {
            final Account account =
                    eligibleAccounts.get((start + offset) % eligibleAccounts.size());
            if (!isSecureContentAccountAvailableForMutation(account)) {
                continue;
            }
            final SecureMessageSearchRepairResult repair =
                    coordinator.repairSearchIndexBatch(account.getUuid(), 32);
            if (repair.getSelected() > 0) {
                Log.d(
                        Config.LOGTAG,
                        "secure search repair selected="
                                + repair.getSelected()
                                + " repaired="
                                + repair.getRepaired()
                                + " failed="
                                + repair.getFailed());
                if (repair.getRepaired() > 0) {
                    return;
                }
            }
        }

        // Message plaintext is exhausted for enabled accounts. Retire legacy conversation
        // secrets next; this includes archived/not-opened rows and is bounded independently.
        final int conversationSecrets = databaseBackend.migrateLegacyConversationSecretsBatch(32);
        if (conversationSecrets > 0) {
            Log.d(
                    Config.LOGTAG,
                    "background conversation secret migration migrated=" + conversationSecrets);
            return;
        }

        // Private crypto state is migrated independently and includes disabled accounts because
        // local key material remains sensitive.
        if (migrateLegacyPrivateCryptoStateBatch()) {
            return;
        }

        // No message/conversation-secret/private-crypto candidate remains. Run the normal message
        // residue scrub and a
        // second one-shot scrub for pages that previously contained OMEMO private state.
        maybeRunLegacyPlaintextSqliteCleanup();
        maybeRunPrivateCryptoSqliteCleanup();
    }

    private boolean migrateLegacyPrivateCryptoStateBatch() {
        if (accounts == null
                || destroyed
                || !checkListeners()
                || !SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                        getApplicationContext())) {
            return false;
        }
        for (final Account account : accounts) {
            if (account == null) {
                continue;
            }
            final int migrated = databaseBackend.migrateLegacyPrivateCryptoStateBatch(account, 32);
            if (migrated > 0) {
                Log.d(Config.LOGTAG, "background private crypto migration migrated=" + migrated);
                return true;
            }
        }
        return false;
    }

    private void maybeRunPrivateCryptoSqliteCleanup() {
        final SharedPreferences prefs =
                getApplicationContext()
                        .getSharedPreferences(
                                "private_crypto_sqlite_cleanup_v1", Context.MODE_PRIVATE);
        if (prefs.getBoolean("completed", false)) {
            return;
        }
        if (accounts != null) {
            for (final Account account : accounts) {
                if (account != null && databaseBackend.hasLegacyPrivateCryptoState(account)) {
                    return;
                }
            }
        }
        final LegacyPlaintextSqliteCleanupResult result =
                databaseBackend.cleanupMigratedLegacyPlaintextResidue();
        if (result.getCompleted()) {
            prefs.edit().putBoolean("completed", true).commit();
        }
    }

    private void maybeRunLegacyPlaintextSqliteCleanup() {
        if (destroyed
                || !checkListeners()
                || !SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                        getApplicationContext())) {
            return;
        }
        final LegacyPlaintextSqliteCleanupCoordinator cleanup =
                new LegacyPlaintextSqliteCleanupCoordinator(
                        getApplicationContext(), databaseBackend);
        final LegacyPlaintextSqliteCleanupResult result = cleanup.runIfEligible();
        if (result != null) {
            Log.d(
                    Config.LOGTAG,
                    "legacy plaintext sqlite cleanup eligible="
                            + result.getEligible()
                            + " completed="
                            + result.getCompleted()
                            + " bodiesBefore="
                            + result.getPlaintextBodyRowsBefore()
                            + " bodiesAfter="
                            + result.getPlaintextBodyRowsAfter()
                            + " freelistAfter="
                            + result.getFreelistPagesAfterVacuum());
        }
    }

    private void handleOrbotStartedEvent() {
        for (final Account account : accounts) {
            if (account.getStatus() == Account.State.TOR_NOT_AVAILABLE) {
                reconnectAccount(account, true, false);
            }
        }
    }

    private boolean processAccountState(
            final Account account,
            final boolean interactive,
            final boolean isUiAction,
            final boolean isAccountPushed,
            final HashSet<Account> pingCandidates) {
        if (!account.getStatus().isAttemptReconnect()) {
            return false;
        }
        if (isNetworkTransitionGraceActive()) {
            return false;
        }
        final var requestCode = account.getUuid().hashCode();
        if (!hasInternetConnection()) {
            account.setStatus(Account.State.NO_INTERNET);
            statusListener.onStatusChanged(account);
        } else {
            if (account.getStatus() == Account.State.NO_INTERNET) {
                account.setStatus(Account.State.OFFLINE);
                statusListener.onStatusChanged(account);
            }
            if (account.getStatus() == Account.State.ONLINE) {
                synchronized (mLowPingTimeoutMode) {
                    long lastReceived = account.getXmppConnection().getLastPacketReceived();
                    long lastSent = account.getXmppConnection().getLastPingSent();
                    long pingInterval = getPingIntervalMillis(account, isUiAction);
                    long msToNextPing =
                            (Math.max(lastReceived, lastSent) + pingInterval)
                                    - SystemClock.elapsedRealtime();
                    int pingTimeout =
                            mLowPingTimeoutMode.contains(account.getJid().asBareJid())
                                    ? Config.LOW_PING_TIMEOUT * 1000
                                    : Config.PING_TIMEOUT * 1000;
                    long pingTimeoutIn = (lastSent + pingTimeout) - SystemClock.elapsedRealtime();
                    if (lastSent > lastReceived) {
                        if (pingTimeoutIn < 0) {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ping timeout");
                            this.reconnectAccount(account, true, interactive);
                        } else {
                            this.scheduleWakeUpCall(pingTimeoutIn, requestCode);
                        }
                    } else {
                        pingCandidates.add(account);
                        if (isAccountPushed) {
                            if (mLowPingTimeoutMode.add(account.getJid().asBareJid())) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": entering low ping timeout mode");
                            }
                            return true;
                        } else if (msToNextPing <= 0) {
                            return true;
                        } else {
                            this.scheduleWakeUpCall(msToNextPing, requestCode);
                            if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": leaving low ping timeout mode");
                            }
                        }
                    }
                }
            } else if (account.getStatus() == Account.State.OFFLINE) {
                reconnectAccount(account, true, interactive);
            } else if (account.getStatus() == Account.State.CONNECTING) {
                final var connection = account.getXmppConnection();
                final var connectionDuration = connection.getConnectionDuration();
                final var discoDuration = connection.getDiscoDuration();
                final var connectionTimeout = Config.CONNECT_TIMEOUT * 1000L - connectionDuration;
                final var discoTimeout = Config.CONNECT_DISCO_TIMEOUT * 1000L - discoDuration;
                if (connectionTimeout < 0) {
                    connection.triggerConnectionTimeout();
                } else if (discoTimeout < 0) {
                    connection.sendDiscoTimeout();
                    scheduleWakeUpCall(discoTimeout, requestCode);
                } else {
                    scheduleWakeUpCall(Math.min(connectionTimeout, discoTimeout), requestCode);
                }
            } else {
                final boolean aggressive =
                        account.getStatus() == Account.State.SEE_OTHER_HOST
                                || hasJingleRtpConnection(account);
                if (account.getXmppConnection().getTimeToNextAttempt(aggressive) <= 0) {
                    reconnectAccount(account, true, interactive);
                }
            }
        }
        return false;
    }

    private void toggleSoftDisabled(final boolean softDisabled) {
        for (final Account account : this.accounts) {
            if (account.isEnabled()) {
                if (account.setOption(Account.OPTION_SOFT_DISABLED, softDisabled)) {
                    updateAccount(account);
                }
            }
        }
    }

    public boolean processUnifiedPushMessage(
            final Account account, final Jid transport, final Element push) {
        return unifiedPushBroker.processPushMessage(account, transport, push);
    }

    public void reinitializeMuclumbusService() {
        mChannelDiscoveryService.initializeMuclumbusService();
    }

    public void discoverChannels(
            String query,
            ChannelDiscoveryService.Method method,
            ChannelDiscoveryService.OnChannelSearchResultsFound onChannelSearchResultsFound) {
        mChannelDiscoveryService.discover(
                Strings.nullToEmpty(query).trim(), method, onChannelSearchResultsFound);
    }

    public boolean isDataSaverDisabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        final ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        return !Compatibility.isActiveNetworkMetered(connectivityManager)
                || Compatibility.getRestrictBackgroundStatus(connectivityManager)
                        == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
    }

    public Map<Integer, Integer> getMessagesCountGroupByDay(
            String conversationUuid, int year, int month) {
        return databaseBackend.getMessagesCountGroupByDay(conversationUuid, year, month);
    }

    private void directReply(
            final Conversation conversation,
            final String body,
            final String lastMessageUuid,
            final boolean dismissAfterReply) {
        final Message inReplyTo =
                lastMessageUuid == null ? null : conversation.findMessageWithUuid(lastMessageUuid);
        Message message = new Message(conversation, body, conversation.getNextEncryption());
        if (inReplyTo != null) {
            if (Emoticons.isEmoji(body.toString().replaceAll("\\s", ""))) {
                message = conversation.getReplyTo().react(body.toString().replaceAll("\\s", ""));
            } else {
                message = inReplyTo.reply();
            }
            message.clearFallbacks("urn:xmpp:reply:0");
            message.setBody(body);
            message.setEncryption(conversation.getNextEncryption());
        }
        if (inReplyTo != null && inReplyTo.isPrivateMessage()) {
            Message.configurePrivateMessage(message, inReplyTo.getCounterpart());
        }
        message.markUnread();
        sendMessage(message);
        if (dismissAfterReply) {
            markRead(conversation, true);
        } else {
            mNotificationService.pushFromDirectReply(message);
        }
    }

    private boolean dndOnSilentMode() {
        return getBooleanPreference(AppSettings.DND_ON_SILENT_MODE, R.bool.dnd_on_silent_mode);
    }

    private boolean manuallyChangePresence() {
        return getBooleanPreference(
                AppSettings.MANUALLY_CHANGE_PRESENCE, R.bool.manually_change_presence);
    }

    private boolean treatVibrateAsSilent() {
        return getBooleanPreference(
                AppSettings.TREAT_VIBRATE_AS_SILENT, R.bool.treat_vibrate_as_silent);
    }

    private boolean awayWhenScreenLocked() {
        return getBooleanPreference(
                AppSettings.AWAY_WHEN_SCREEN_IS_OFF, R.bool.away_when_screen_off);
    }

    private String getCompressPicturesPreference() {
        return getPreferences()
                .getString(
                        "picture_compression",
                        getResources().getString(R.string.picture_compression));
    }

    private Presence.Status getTargetPresence() {
        if (dndOnSilentMode() && isPhoneSilenced()) {
            return Presence.Status.DND;
        } else if (awayWhenScreenLocked() && isScreenLocked()) {
            return Presence.Status.AWAY;
        } else {
            return Presence.Status.ONLINE;
        }
    }

    public boolean isScreenLocked() {
        final KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        final PowerManager powerManager = getSystemService(PowerManager.class);
        final boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        final boolean interactive;
        try {
            interactive = powerManager != null && powerManager.isInteractive();
        } catch (final Exception e) {
            return false;
        }
        return locked || !interactive;
    }

    private boolean isPhoneSilenced() {
        final NotificationManager notificationManager = getSystemService(NotificationManager.class);
        final int filter =
                notificationManager == null
                        ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                        : notificationManager.getCurrentInterruptionFilter();
        final boolean notificationDnd = filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY;
        final AudioManager audioManager = getSystemService(AudioManager.class);
        final int ringerMode =
                audioManager == null
                        ? AudioManager.RINGER_MODE_NORMAL
                        : audioManager.getRingerMode();
        try {
            if (treatVibrateAsSilent()) {
                return notificationDnd || ringerMode != AudioManager.RINGER_MODE_NORMAL;
            } else {
                return notificationDnd || ringerMode == AudioManager.RINGER_MODE_SILENT;
            }
        } catch (final Throwable throwable) {
            Log.d(
                    Config.LOGTAG,
                    "platform bug in isPhoneSilenced (" + throwable.getMessage() + ")");
            return notificationDnd;
        }
    }

    private void resetAllAttemptCounts(boolean reallyAll, boolean retryImmediately) {
        Log.d(Config.LOGTAG, "resetting all attempt counts");
        for (Account account : accounts) {
            if (account.hasErrorStatus() || reallyAll) {
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    connection.resetAttemptCount(retryImmediately);
                }
            }
            if (account.setShowErrorNotification(true)) {
                mDatabaseWriterExecutor.execute(() -> databaseBackend.updateAccount(account));
            }
        }
        mNotificationService.updateErrorNotification();
    }

    private void dismissErrorNotifications() {
        for (final Account account : this.accounts) {
            if (account.hasErrorStatus()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid() + ": dismissing error notification");
                if (account.setShowErrorNotification(false)) {
                    mDatabaseWriterExecutor.execute(() -> databaseBackend.updateAccount(account));
                }
            }
        }
    }

    private void expireOldMessages() {
        expireOldMessages(false);
    }

    public void expireOldMessages(final boolean resetHasMessagesLeftOnServer) {
        mLastExpiryRun.set(SystemClock.elapsedRealtime());
        mDatabaseWriterExecutor.execute(
                () -> {
                    long timestamp = getAutomaticMessageDeletionDate();
                    if (timestamp > 0) {
                        databaseBackend.expireOldMessages(timestamp);
                        synchronized (XmppConnectionService.this.conversations) {
                            for (Conversation conversation :
                                    XmppConnectionService.this.conversations) {
                                conversation.expireOldMessages(timestamp);
                                if (resetHasMessagesLeftOnServer) {
                                    conversation.messagesLoaded.set(true);
                                    conversation.setHasMessagesLeftOnServer(true);
                                }
                            }
                        }
                        updateConversationUi();
                    }
                });
    }

    private void handleActiveNetworkChange(
            @Nullable final Network activeNetwork, final String reason) {
        final Network previousNetwork = mActiveNetwork.getAndSet(activeNetwork);
        if (Objects.equal(previousNetwork, activeNetwork)) {
            return;
        }
        final long generation = mNetworkTransitionGeneration.incrementAndGet();
        Log.d(
                Config.LOGTAG,
                "active network changed ("
                        + reason
                        + "): "
                        + previousNetwork
                        + " -> "
                        + activeNetwork);
        Resolver.clearCache();
        mRecoveredNetwork.set(null);
        if (activeNetwork == null) {
            mNetworkLossGraceUntil.set(
                    SystemClock.elapsedRealtime() + Config.NETWORK_TRANSITION_GRACE_MS);
            scheduleNetworkTask(
                    () -> finalizeNetworkLoss(generation), Config.NETWORK_TRANSITION_GRACE_MS);
            return;
        }

        mNetworkLossGraceUntil.set(0L);
        final long delay =
                isNetworkValidated(activeNetwork) ? 0L : Config.NETWORK_PROVISIONAL_READY_GRACE_MS;
        scheduleActiveNetworkRecovery(activeNetwork, generation, reason, delay);
    }

    private void handleValidatedActiveNetwork(@NonNull final Network network) {
        final long generation = mNetworkTransitionGeneration.incrementAndGet();
        mNetworkLossGraceUntil.set(0L);
        scheduleActiveNetworkRecovery(network, generation, "validated", 0L);
    }

    private void scheduleActiveNetworkRecovery(
            @NonNull final Network network,
            final long generation,
            @NonNull final String reason,
            final long delayMs) {
        scheduleNetworkTask(
                () -> recoverOnActiveNetwork(network, generation, reason), Math.max(0L, delayMs));
    }

    private void scheduleNetworkTask(final Runnable task, final long delayMs) {
        try {
            internalPingExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException e) {
            Log.d(Config.LOGTAG, "network transition ignored while service is shutting down");
        }
    }

    private void recoverOnActiveNetwork(
            @NonNull final Network network, final long generation, @NonNull final String reason) {
        if (generation != mNetworkTransitionGeneration.get()
                || !Objects.equal(mActiveNetwork.get(), network)) {
            Log.d(Config.LOGTAG, "ignoring superseded network recovery for " + network);
            return;
        }
        if (!hasInternetCapability(network)) {
            Log.d(
                    Config.LOGTAG,
                    "active network "
                            + network
                            + " has no INTERNET capability yet; waiting for capabilities");
            return;
        }

        mRecoveredNetwork.set(network);
        mNetworkLossGraceUntil.set(0L);
        if (Config.RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE) {
            // A newly selected Android default network is a new transport opportunity. Give it one
            // immediate attempt instead of inheriting backoff accumulated on the old path.
            resetAllAttemptCounts(true, true);
        }
        for (final Account account : accounts) {
            final Account.State state = account.getStatus();
            if (state == Account.State.ONLINE || state == Account.State.CONNECTING) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": reconnecting on active network "
                                + network
                                + " ("
                                + reason
                                + ")");
                // Forced socket close intentionally preserves XEP-0198 streamId and stanza queue.
                reconnectAccount(account, true, false);
            }
        }
        manageAccountConnectionStatesInternal();
        if (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0) {
            schedulePostConnectivityChange();
        }
    }

    private void finalizeNetworkLoss(final long generation) {
        if (generation != mNetworkTransitionGeneration.get() || mActiveNetwork.get() != null) {
            return;
        }
        mNetworkLossGraceUntil.set(0L);
        if (hasInternetConnection()) {
            manageAccountConnectionStatesInternal();
            return;
        }
        for (final Account account : accounts) {
            final Account.State state = account.getStatus();
            if (state == Account.State.ONLINE || state == Account.State.CONNECTING) {
                synchronized (account) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": network transition grace expired; closing stale socket");
                    disconnect(account, true);
                    account.getRoster().clearPresences();
                    account.setStatus(Account.State.NO_INTERNET);
                    statusListener.onStatusChanged(account);
                }
            }
        }
    }

    private boolean isNetworkTransitionGraceActive() {
        return SystemClock.elapsedRealtime() < mNetworkLossGraceUntil.get();
    }

    private boolean hasInternetCapability(@NonNull final Network network) {
        final ConnectivityManager cm =
                ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm == null) {
            return true;
        }
        try {
            final NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to inspect active network capabilities", e);
            return true;
        }
    }

    private boolean isNetworkValidated(@NonNull final Network network) {
        final ConnectivityManager cm =
                ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        try {
            final NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to inspect network validation", e);
            return false;
        }
    }

    public boolean hasInternetConnection() {
        final ConnectivityManager cm =
                ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm == null) {
            return true; // if internet connection can not be checked it is probably best to just
            // try
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                final Network activeNetwork = cm.getActiveNetwork();
                final NetworkCapabilities capabilities =
                        activeNetwork == null ? null : cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                final NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                return networkInfo != null
                        && (networkInfo.isConnected()
                                || networkInfo.getType() == ConnectivityManager.TYPE_ETHERNET);
            }
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to check for internet connection", e);
            return true; // if internet connection can not be checked it is probably best to just
            // try
        }
    }

    @SuppressLint("TrulyRandom")
    @Override
    public void onCreate() {
        final android.app.Application application = getApplication();
        if (application instanceof Conversations) {
            ((Conversations) application).addUiStateListener(mUiStateListener);
        }
        LibIdnXmppStringprep.setup();
        if (Compatibility.runsTwentySix()) {
            mNotificationService.initializeChannels();
        }
        mChannelDiscoveryService.initializeMuclumbusService();
        mForceDuringOnCreate.set(Compatibility.runsAndTargetsTwentySix(this));
        toggleForegroundService();
        this.destroyed = false;
        OmemoSetting.load(this);
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable throwable) {
            Log.e(Config.LOGTAG, "unable to initialize security provider", throwable);
        }
        updateMemorizingTrustManager();
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        this.mBitmapCache =
                new LruCache<String, Bitmap>(cacheSize) {
                    @Override
                    protected int sizeOf(final String key, final Bitmap bitmap) {
                        return bitmap.getByteCount() / 1024;
                    }
                };

        this.mDrawableCache =
                new LruCache<String, Drawable>(cacheSize) {
                    @Override
                    protected int sizeOf(final String key, final Drawable drawable) {
                        if (drawable instanceof BitmapDrawable) {
                            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                            if (bitmap == null) return 1024;

                            return bitmap.getByteCount() / 1024;
                        } else {
                            return drawable.getIntrinsicWidth()
                                    * drawable.getIntrinsicHeight()
                                    * 40
                                    / 1024;
                        }
                    }
                };
        if (mLastActivity == 0) {
            mLastActivity =
                    getPreferences().getLong(SETTING_LAST_ACTIVITY_TS, System.currentTimeMillis());
        }

        Log.d(Config.LOGTAG, "initializing database...");
        this.databaseBackend = DatabaseBackendProvider.getInstance(getApplicationContext());
        Log.d(Config.LOGTAG, "restoring accounts...");
        this.accounts = databaseBackend.getAccounts();
        retireLegacyDeviceConfirmationState();
        // Do not migrate/hydrate Account Secret Vault state from Service.onCreate().
        // Startup must first recover the durable High Security transaction state and bring the UI
        // up without performing protected writes on the main service thread. Account secrets are
        // hydrated transactionally in manageAccountConnectionStates(), immediately before an
        // account is allowed to connect.
        if (!InactiveDeviceDeactivationRecoveryCoordinatorV1.recoverIfNeeded(
                getApplicationContext(), databaseBackend)) {
            Log.w(Config.LOGTAG, "inactive-device deactivation recovery remains unresolved");
        }
        if (!InactiveDeviceActivationRecoveryCoordinatorV1.recoverIfNeeded(
                getApplicationContext(), databaseBackend)) {
            Log.w(Config.LOGTAG, "inactive-device activation recovery remains unresolved");
        }
        synchronizeSecureContentAccountRegistry();
        final SharedPreferences.Editor editor = getPreferences().edit();
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        editor.putBoolean(SystemEventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply();
        editor.apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);
        reconfigurePushDistributor();

        if (CallIntegration.hasSystemFeature(this)) {
            CallIntegrationConnectionService.togglePhoneAccountsAsync(this, this.accounts);
        }

        restoreFromDatabase();
        mDatabaseWriterExecutor.execute(this::recoverModeratedMessageRetirements);
                mDatabaseWriterExecutor.execute(this::recoverMucRetractionRetirements);
                mDatabaseWriterExecutor.execute(this::recoverVerifiedMucRetractions);

        if (QuickConversationsService.isContactListIntegration(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED) {
            startContactObserver();
        }
        FILE_OBSERVER_EXECUTOR.execute(fileBackend::deleteHistoricAvatarPath);
        if (Compatibility.hasStoragePermission(this)) {
            Log.d(Config.LOGTAG, "starting file observer");
            FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::startWatching);
            FILE_OBSERVER_EXECUTOR.execute(this::checkForDeletedFiles);
        }

        final PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            this.wakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK, "Conversations:Service");
        }

        toggleForegroundService();
        updateUnreadCountBadge();
        toggleScreenEventReceiver();
        final IntentFilter systemBroadcastFilter = new IntentFilter();
        scheduleNextIdlePing();
        final ConnectivityManager connectivityManager =
                ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (connectivityManager != null) {
            final Network initialNetwork = connectivityManager.getActiveNetwork();
            mActiveNetwork.set(initialNetwork);
            mRecoveredNetwork.set(initialNetwork);
            try {
                connectivityManager.registerDefaultNetworkCallback(mNetworkCallback);
            } catch (final RuntimeException e) {
                Log.d(Config.LOGTAG, "unable to register default network callback", e);
                systemBroadcastFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            }
        } else {
            systemBroadcastFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        }
        systemBroadcastFilter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        ContextCompat.registerReceiver(
                this,
                this.mInternalEventReceiver,
                systemBroadcastFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        final IntentFilter exportedBroadcastFilter = new IntentFilter();
        exportedBroadcastFilter.addAction(TorServiceUtils.ACTION_STATUS);
        ContextCompat.registerReceiver(
                this,
                this.mInternalRestrictedEventReceiver,
                exportedBroadcastFilter,
                ContextCompat.RECEIVER_EXPORTED);
        mForceDuringOnCreate.set(false);
        toggleForegroundService();
        internalPingExecutor.scheduleWithFixedDelay(
                this::manageAccountConnectionStatesInternal, 10, 10, TimeUnit.SECONDS);
        final SharedPreferences sharedPreferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(
                            SharedPreferences sharedPreferences, @Nullable String key) {
                        Log.d(Config.LOGTAG, "preference '" + key + "' has changed");
                        if (AppSettings.KEEP_FOREGROUND_SERVICE.equals(key)) {
                            toggleForegroundService();
                        }
                    }
                });
    }

    private void checkForDeletedFiles() {
        if (destroyed) {
            Log.d(
                    Config.LOGTAG,
                    "Do not check for deleted files because service has been destroyed");
            return;
        }
        final long start = SystemClock.elapsedRealtime();
        final List<FilePathInfo> relativeFilePaths = databaseBackend.getFilePathInfo();
        final List<FilePathInfo> changed = new ArrayList<>();
        for (final FilePathInfo filePath : relativeFilePaths) {
            if (destroyed) {
                Log.d(
                        Config.LOGTAG,
                        "Stop checking for deleted files because service has been destroyed");
                return;
            }
            final File file = fileBackend.getFileForPath(filePath.path);
            if (filePath.setDeleted(!file.exists())) {
                changed.add(filePath);
            }
        }
        final long duration = SystemClock.elapsedRealtime() - start;
        Log.d(
                Config.LOGTAG,
                "found "
                        + changed.size()
                        + " changed files on start up. total="
                        + relativeFilePaths.size()
                        + ". ("
                        + duration
                        + "ms)");
        if (changed.size() > 0) {
            databaseBackend.markFilesAsChanged(changed);
            markChangedFiles(changed);
        }
    }

    public void startContactObserver() {
        getContentResolver()
                .registerContentObserver(
                        ContactsContract.Contacts.CONTENT_URI,
                        true,
                        new ContentObserver(null) {
                            @Override
                            public void onChange(boolean selfChange) {
                                super.onChange(selfChange);
                                if (restoredFromDatabaseLatch.getCount() == 0) {
                                    loadPhoneContacts();
                                }
                            }
                        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.d(Config.LOGTAG, "clear cache due to low memory");
            getBitmapCache().evictAll();
        }
    }

    @Override
    public void onDestroy() {
        final android.app.Application application = getApplication();
        if (application instanceof Conversations) {
            ((Conversations) application).removeUiStateListener(mUiStateListener);
        }
        final ConnectivityManager connectivityManager =
                ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(mNetworkCallback);
            } catch (final IllegalArgumentException ignored) {
                // callback was not registered
            }
        }
        try {
            unregisterReceiver(this.mInternalEventReceiver);
            unregisterReceiver(this.mInternalRestrictedEventReceiver);
            unregisterReceiver(this.mInternalScreenEventReceiver);
        } catch (final IllegalArgumentException e) {
            // ignored
        }
        destroyed = false;
        fileObserver.stopWatching();
        internalPingExecutor.shutdown();
        super.onDestroy();
    }

    public void restartFileObserver() {
        Log.d(Config.LOGTAG, "restarting file observer");
        FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::restartWatching);
        FILE_OBSERVER_EXECUTOR.execute(this::checkForDeletedFiles);
    }

    public void toggleScreenEventReceiver() {
        // NeoCont no longer derives XMPP availability from lock-screen state. Keep this method as
        // a compatibility seam for callers while ensuring an older saved preference cannot
        // silently re-enable the legacy presence behavior after upgrade.
        try {
            unregisterReceiver(this.mInternalScreenEventReceiver);
        } catch (IllegalArgumentException e) {
            // ignored
        }
    }

    public void toggleForegroundService() {
        toggleForegroundService(false);
    }

    public void setOngoingCall(
            AbstractJingleConnection.Id id, Set<Media> media, final boolean reconnecting) {
        ongoingCall.set(new OngoingCall(id, media, reconnecting));
        toggleForegroundService(false);
    }

    public void removeOngoingCall() {
        ongoingCall.set(null);
        toggleForegroundService(false);
    }

    private void toggleForegroundService(final boolean force) {
        final boolean status;
        final OngoingCall ongoing = ongoingCall.get();
        final boolean ongoingVideoTranscoding = mOngoingVideoTranscoding.get();
        final int id;
        if (force
                || mForceDuringOnCreate.get()
                || ongoingVideoTranscoding
                || ongoing != null
                || (Compatibility.keepForegroundService(this) && hasEnabledAccounts())) {
            final Notification notification;
            if (ongoing != null) {
                notification = this.mNotificationService.getOngoingCallNotification(ongoing);
                id = NotificationService.ONGOING_CALL_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, true, ongoing.media.contains(Media.VIDEO));
            } else if (ongoingVideoTranscoding) {
                notification = this.mNotificationService.getIndeterminateVideoTranscoding();
                id = NotificationService.ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, false, false);
            } else {
                notification = this.mNotificationService.createForegroundNotification();
                id = NotificationService.FOREGROUND_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, false, false);
            }
            mNotificationService.notify(id, notification);
            status = true;
        } else {
            id = 0;
            stopForeground(true);
            status = false;
        }

        for (final int toBeRemoved :
                Collections2.filter(
                        Arrays.asList(
                                NotificationService.FOREGROUND_NOTIFICATION_ID,
                                NotificationService.ONGOING_CALL_NOTIFICATION_ID,
                                NotificationService.ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID),
                        i -> i != id)) {
            mNotificationService.cancel(toBeRemoved);
        }
        Log.d(
                Config.LOGTAG,
                "ForegroundService: " + (status ? "on" : "off") + ", notification: " + id);
    }

    private void startForegroundOrCatch(
            final int id,
            final Notification notification,
            final boolean requireMicrophone,
            final boolean requireCamera) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                int foregroundServiceType = 0;
                if (requireMicrophone
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                    foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                if (requireCamera
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                    foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                if (foregroundServiceType == 0) {
                    if (getSystemService(PowerManager.class)
                            .isIgnoringBatteryOptimizations(getPackageName())) {
                        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
                    } else if (ContextCompat.checkSelfPermission(
                                    this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                    } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                    } else {
                        foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                        Log.w(Config.LOGTAG, "falling back to special use foreground service type");
                    }
                }
                startForeground(id, notification, foregroundServiceType);
            } else {
                startForeground(id, notification);
            }
        } catch (final IllegalStateException | SecurityException e) {
            Log.e(Config.LOGTAG, "Could not start foreground service", e);
        }
    }

    public boolean foregroundNotificationNeedsUpdatingWhenErrorStateChanges() {
        return !mOngoingVideoTranscoding.get()
                && ongoingCall.get() == null
                && Compatibility.keepForegroundService(this)
                && hasEnabledAccounts();
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if ((Compatibility.keepForegroundService(this) && hasEnabledAccounts())
                || mOngoingVideoTranscoding.get()
                || ongoingCall.get() != null) {
            Log.d(Config.LOGTAG, "ignoring onTaskRemoved because foreground service is activated");
        } else {
            this.logoutAndSave(false);
        }
    }

    private void logoutAndSave(boolean stop) {
        int activeAccounts = 0;
        for (final Account account : accounts) {
            if (account.isConnectionEnabled()) {
                databaseBackend.writeRoster(account.getRoster());
                activeAccounts++;
            }
            if (account.getXmppConnection() != null) {
                new Thread(() -> disconnect(account, false)).start();
            }
        }
        if (stop || activeAccounts == 0) {
            Log.d(Config.LOGTAG, "good bye");
            stopSelf();
        }
    }

    private void schedulePostConnectivityChange() {
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final long triggerAtMillis =
                SystemClock.elapsedRealtime()
                        + (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL * 1000);
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_POST_CONNECTIVITY_CHANGE);
        try {
            final PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this,
                            1,
                            intent,
                            s()
                                    ? PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT
                                    : PendingIntent.FLAG_UPDATE_CURRENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for post connectivity change", e);
        }
    }

    public void scheduleSmResumeAckWatchdog(
            final Account account, final XmppConnection connection) {
        try {
            internalPingExecutor.schedule(
                    () -> {
                        if (destroyed
                                || account.getXmppConnection() != connection
                                || !connection.isWaitingForSmCatchup()) {
                            return;
                        }
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": no XEP-0198 ack after resume; reconnecting");
                        reconnectAccount(account, true, false);
                    },
                    Config.SM_RESUME_ACK_TIMEOUT,
                    TimeUnit.SECONDS);
        } catch (final RejectedExecutionException e) {
            Log.d(Config.LOGTAG, "SM resume watchdog ignored while service is shutting down");
        }
    }

    public void scheduleWakeUpCall(final int seconds, final int requestCode) {
        scheduleWakeUpCall((seconds < 0 ? 1 : seconds + 1) * 1000L, requestCode);
    }

    public void scheduleWakeUpCall(final long milliSeconds, final int requestCode) {
        final var timeToWake = SystemClock.elapsedRealtime() + milliSeconds;
        final var alarmManager = getSystemService(AlarmManager.class);
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_PING);
        try {
            final PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for ping", e);
        }
    }

    private void scheduleNextIdlePing() {
        final long timeToWake = SystemClock.elapsedRealtime() + (Config.IDLE_PING_INTERVAL * 1000);
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_IDLE_PING);
        try {
            final PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this,
                            0,
                            intent,
                            s()
                                    ? PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT
                                    : PendingIntent.FLAG_UPDATE_CURRENT);
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to schedule alarm for idle ping", e);
        }
    }

    public XmppConnection createConnection(final Account account) {
        final XmppConnection connection = new XmppConnection(account, this);
        connection.setOnStatusChangedListener(this.statusListener);
        connection.setOnJinglePacketReceivedListener((mJingleConnectionManager::deliverPacket));
        connection.setOnMessageAcknowledgeListener(this.mOnMessageAcknowledgedListener);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mMessageArchiveService);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mAvatarService);
        AxolotlService axolotlService = account.getAxolotlService();
        if (axolotlService != null) {
            connection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService);
        }
        return connection;
    }

    public void sendChatState(Conversation conversation) {
        if (sendChatStates()) {
            final var packet = mMessageGenerator.generateChatState(conversation);
            sendMessagePacket(conversation.getAccount(), packet);
        }
    }


    public boolean retractOwnMucMessage(
            final Conversation room, final Message original) {
        if (room == null
                || original == null
                || original.getConversation() != room
                || room.getMode() != Conversation.MODE_MULTI
                || original.isPrivateMessage()
                || original.isModerated()) {
            return false;
        }

        final int status = original.getStatus();
        if (status != Message.STATUS_SEND_RECEIVED
                && status != Message.STATUS_SEND_DISPLAYED) {
            return false;
        }

        final String targetId = original.getRoomStanzaId();
        if (targetId == null || targetId.isEmpty()) {
            return false;
        }

        final Account account = room.getAccount();
        if (account == null
                || account.getStatus() != Account.State.ONLINE
                || account.getXmppConnection() == null
                || !room.getMucOptions().online()
                || !room.getMucOptions().participating()) {
            return false;
        }

        final var packet =
                mMessageGenerator.generateMucRetraction(room, targetId);
        sendMessagePacket(account, packet);
        return true;
    }

    private void sendFileMessage(
            final Message message, final boolean delay, final boolean forceP2P) {
        final var account = message.getConversation().getAccount();
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": send file message. forceP2P=" + forceP2P);
        final DownloadableFile uploadFile = fileBackend.getFile(message, false);
        final long preparedSize = message.getFileParams().getSize();
        final long uploadPayloadSize =
                (preparedSize > 0 ? preparedSize : uploadFile.getSize())
                        + ((Config.ENCRYPT_ON_HTTP_UPLOADED
                                        || message.getEncryption() == Message.ENCRYPTION_AXOLOTL)
                                ? 16
                                : 0);
        if ((account.httpUploadAvailable(uploadPayloadSize)
                        || message.getConversation().getMode() == Conversation.MODE_MULTI)
                && !forceP2P) {
            mHttpConnectionManager.createNewUploadConnection(message, delay);
        } else {
            mJingleConnectionManager.startJingleFileTransfer(message);
        }
    }

    public void sendMessage(final Message message) {
        sendMessage(message, false, false, false);
    }

    private void sendMessage(
            final Message message,
            final boolean resend,
            final boolean delay,
            final boolean forceP2P) {
        sendMessage(message, resend, delay, forceP2P, false);
    }

    private void sendMessage(
            final Message message,
            final boolean resend,
            final boolean delay,
            final boolean forceP2P,
            final boolean holdForMediaTransport) {
        final Account account = message.getConversation().getAccount();
        if (account.setShowErrorNotification(true)) {
            databaseBackend.updateAccount(account);
            mNotificationService.updateErrorNotification();
        }
        final Conversation conversation = (Conversation) message.getConversation();
        account.deactivateGracePeriod();

        if (QuickConversationsService.isQuicksy()
                && conversation.getMode() == Conversation.MODE_SINGLE) {
            final Contact contact = conversation.getContact();
            if (!contact.showInRoster() && contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": adding "
                                + contact.getJid()
                                + " on sending message");
                createContact(contact, true);
            }
        }

        im.conversations.android.xmpp.model.stanza.Message packet = null;
        final boolean secureText = isProtectedTextCandidate(message);
        final long stanzaPreparationStartedAt = secureText ? SystemClock.elapsedRealtime() : 0;
        final boolean addToConversation = !message.edited();
        boolean saveInDb = addToConversation;
        if (!prepareProtectedTextForSend(message, resend)) {
            message.setStatus(Message.STATUS_SEND_FAILED);
            if (!resend && addToConversation) {
                restoreReplyForMessage(conversation, message);
                conversation.add(message);
            }
            updateConversationUi();
            return;
        }
        if (message.hasProtectedTextPayload()) {
            // publishInitial() created metadata with an empty body before the Store commit.
            saveInDb = false;
        }
        message.setStatus(Message.STATUS_WAITING);

        if (message.getEncryption() != Message.ENCRYPTION_NONE
                && conversation.getMode() == Conversation.MODE_MULTI
                && conversation.isPrivateAndNonAnonymous()) {
            if (conversation.setAttribute(
                    Conversation.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, true)) {
                databaseBackend.updateConversation(conversation);
            }
        }

        if (!resend && message.getEncryption() != Message.ENCRYPTION_OTR) {
            conversation.endOtrIfNeeded();
            conversation.findUnsentMessagesWithEncryption(
                    Message.ENCRYPTION_OTR,
                    message1 -> markMessage(message1, Message.STATUS_SEND_FAILED));
        }

        final boolean inProgressJoin = isJoinInProgress(conversation);

        if (!holdForMediaTransport && account.isOnlineAndConnected() && !inProgressJoin) {
            switch (message.getEncryption()) {
                case Message.ENCRYPTION_NONE:
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(
                                        fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay, forceP2P);
                        } else {
                            break;
                        }
                    } else {
                        packet = mMessageGenerator.generateChat(message);
                    }
                    break;
                case Message.ENCRYPTION_PGP:
                case Message.ENCRYPTION_DECRYPTED:
                    // Legacy OpenPGP messages are kept in the database for history compatibility,
                    // but NeoCont no longer creates or sends OpenPGP messages.
                    message.setStatus(Message.STATUS_SEND_FAILED);
                    break;
                case Message.ENCRYPTION_OTR:
                    SessionImpl otrSession = conversation.getOtrSession();
                    if (otrSession != null
                            && otrSession.getSessionStatus() == SessionStatus.ENCRYPTED) {
                        try {
                            message.setCounterpart(
                                    OtrJidHelper.fromSessionID(otrSession.getSessionID()));
                        } catch (IllegalArgumentException e) {
                            break;
                        }
                        if (message.needsUploading()) {
                            mJingleConnectionManager.startJingleFileTransfer(message);
                        } else {
                            packet = mMessageGenerator.generateOtrChat(message);
                        }
                    } else if (otrSession == null) {
                        if (message.fixCounterpart()) {
                            conversation.startOtrSession(
                                    message.getCounterpart().getResource(), true);
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": could not fix counterpart for OTR message to"
                                            + " contact "
                                            + message.getCounterpart());
                            break;
                        }
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + " OTR session with "
                                        + message.getContact()
                                        + " is in wrong state: "
                                        + otrSession.getSessionStatus().toString());
                    }
                    break;
                case Message.ENCRYPTION_AXOLOTL:
                    message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(
                                        fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay, forceP2P);
                        } else {
                            break;
                        }
                    } else {
                        XmppAxolotlMessage axolotlMessage =
                                account.getAxolotlService().fetchAxolotlMessageFromCache(message);
                        if (axolotlMessage == null) {
                            account.getAxolotlService().preparePayloadMessage(message, delay);
                        } else {
                            packet = mMessageGenerator.generateAxolotlChat(message, axolotlMessage);
                        }
                    }
                    break;
            }
            if (secureText) {
                SecureTextPayload.logStage(
                        secureTextPayloadBytes(message),
                        "stanza_encryption_prepare",
                        stanzaPreparationStartedAt,
                        null);
            }
            if (packet != null) {
                if (account.getXmppConnection().getFeatures().sm()
                        || (conversation.getMode() == Conversation.MODE_MULTI
                                && message.getCounterpart().isBareJid())) {
                    message.setStatus(Message.STATUS_UNSEND);
                } else {
                    message.setStatus(Message.STATUS_SEND);
                }
            }
        } else {
            switch (message.getEncryption()) {
                case Message.ENCRYPTION_PGP:
                case Message.ENCRYPTION_DECRYPTED:
                    message.setStatus(Message.STATUS_SEND_FAILED);
                    break;
                case Message.ENCRYPTION_OTR:
                    if (!conversation.hasValidOtrSession() && message.getCounterpart() != null) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": create otr session without starting for "
                                        + message.getContact().getJid());
                        conversation.startOtrSession(message.getCounterpart().getResource(), false);
                    }
                    break;
                case Message.ENCRYPTION_AXOLOTL:
                    message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                    break;
            }
        }

        boolean mucMessage =
                conversation.getMode() == Conversation.MODE_MULTI && !message.isPrivateMessage();
        if (mucMessage) {
            message.setCounterpart(conversation.getMucOptions().getSelf().getFullJid());
        }

        if (resend) {
            if (packet != null && addToConversation) {
                if (account.getXmppConnection().getFeatures().sm() || mucMessage) {
                    markMessage(message, Message.STATUS_UNSEND);
                } else {
                    markMessage(message, Message.STATUS_SEND);
                }
            }
        } else {
            if (addToConversation) {
                restoreReplyForMessage(conversation, message);
                conversation.add(message);
            }
            if (saveInDb) {
                databaseBackend.createMessage(message);
            } else if (message.edited()) {
                if (!databaseBackend.updateMessage(message, message.getEditedId())) {
                    Log.e(Config.LOGTAG, "error updated message in DB after edit");
                } else {
                    markTimelineChanged(message);
                }
            }
            if (suppressedConversationUiUpdates.get() == 0) {
                updateConversationUi();
            }
        }
        if (packet != null) {
            if (delay) {
                mMessageGenerator.addDelay(packet, message.getTimeSent());
            }
            if (conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
                if (this.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()));
                }
            }
            final long connectionSendStartedAt = secureText ? SystemClock.elapsedRealtime() : 0;
            sendMessagePacket(account, packet);
            if (secureText) {
                SecureTextPayload.logStage(
                        secureTextPayloadBytes(message),
                        "connection_send",
                        connectionSendStartedAt,
                        null);
            }
        }
    }

    private boolean isJoinInProgress(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                final boolean inProgress = account.inProgressConferenceJoins.contains(conversation);
                final boolean pending = account.pendingConferenceJoins.contains(conversation);
                final boolean inProgressJoin = inProgress || pending;
                if (inProgressJoin) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": holding back message to group. inProgress="
                                    + inProgress
                                    + ", pending="
                                    + pending);
                }
                return inProgressJoin;
            } else {
                return false;
            }
        }
    }

    private void sendUnsentMessages(final Conversation conversation) {
        conversation.findWaitingMessages(
                message -> {
                    if (!isDeferredMediaCaptionBlocked(conversation, message)) {
                        resendMessage(message, true);
                    }
                });
    }

    private boolean isDeferredMediaCaptionBlocked(
            final Conversation conversation, final Message candidate) {
        final List<Message> snapshot = conversation.snapshotMessages();
        final Message anchor = MediaCaptionResolver.resolveAnchor(candidate, snapshot);
        return anchor != null && !isMediaTransactionTransportReady(anchor, snapshot);
    }

    public void releaseDeferredMediaCaptions(final Message completedMedia) {
        if (!(completedMedia.getConversation() instanceof Conversation conversation)) {
            return;
        }
        final List<Message> snapshot = conversation.snapshotMessages();
        for (final Message candidate : snapshot) {
            if (candidate.getStatus() != Message.STATUS_WAITING) {
                continue;
            }
            final Message anchor = MediaCaptionResolver.resolveAnchor(candidate, snapshot);
            if (anchor == null
                    || !sameLocalMediaTransaction(anchor, completedMedia)
                    || !isMediaTransactionTransportReady(anchor, snapshot)) {
                continue;
            }
            // Several album members may finish almost simultaneously on different transfer
            // executors. Claim the logical caption once so two completion callbacks cannot race
            // through STATUS_WAITING and emit the same stanza twice. The claim is process-local:
            // after a process restart the normal WAITING-message recovery remains authoritative.
            if (releasedDeferredMediaCaptions.add(candidate.getUuid())) {
                resendMessage(candidate, false);
            }
        }
    }

    private static boolean sameLocalMediaTransaction(
            final Message anchor, final Message completedMedia) {
        if (anchor == completedMedia) {
            return true;
        }
        final String anchorGroupId = anchor.getMediaGroupId();
        final String completedGroupId = completedMedia.getMediaGroupId();
        return anchorGroupId != null
                && !anchorGroupId.isEmpty()
                && anchorGroupId.equals(completedGroupId);
    }

    private static boolean isMediaTransactionTransportReady(
            final Message anchor, final List<Message> snapshot) {
        final String mediaGroupId = anchor.getMediaGroupId();
        for (final Message member : snapshot) {
            if (!member.isFileOrImage()) {
                continue;
            }
            final boolean sameMember =
                    mediaGroupId == null || mediaGroupId.isEmpty()
                            ? member == anchor
                            : mediaGroupId.equals(member.getMediaGroupId());
            if (!sameMember) {
                continue;
            }
            if (member.getTransferable() != null
                    || member.getStatus() == Message.STATUS_WAITING
                    || member.getStatus() == Message.STATUS_SEND_FAILED) {
                return false;
            }
            if (member.needsUploading() && member.getStatus() < Message.STATUS_SEND_RECEIVED) {
                return false;
            }
        }
        return true;
    }

    public void resendMessage(final Message message, final boolean delay) {
        sendMessage(message, true, delay, false);
        if (message.isFileOrImage()) {
            // HTTP upload may hand an OMEMO media message to an asynchronous payload-encryption
            // pass. The first resend can therefore leave it STATUS_WAITING; the second resend is
            // the first point where the stanza is actually ready. Reconcile deferred captions
            // after every media resend so they are released immediately at that point instead of
            // waiting for an unrelated reconnect/status event.
            releaseDeferredMediaCaptions(message);
        }
    }

    public void requestEasyOnboardingInvite(
            final Account account, final EasyOnboardingInvite.OnInviteRequested callback) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            callback.inviteRequestFailed(
                    getString(R.string.server_does_not_support_easy_onboarding_invites));
            return;
        }

        // Account-creation invites use XEP-0401 when the connected server advertises it.
        // Do not switch this action to urn:xmpp:invite#invite: the generated token must
        // authorize creation of a new account on the selected server.
        final String commandNode = Namespace.EASY_ONBOARDING_INVITE;
        final Jid jid = connection.getJidForCommand(commandNode);
        if (jid == null) {
            callback.inviteRequestFailed(
                    getString(R.string.server_does_not_support_easy_onboarding_invites));
            return;
        }

        final Iq request = new Iq(Iq.Type.SET);
        request.setTo(jid);
        final Element command = request.addChild("command", Namespace.COMMANDS);
        command.setAttribute("node", commandNode);
        command.setAttribute("action", "execute");
        sendIqPacket(
                account,
                request,
                response ->
                        handleEasyOnboardingInviteResponse(
                                account, jid, commandNode, response, callback, false));
    }

    private void handleEasyOnboardingInviteResponse(
            final Account account,
            final Jid commandJid,
            final String commandNode,
            final Iq response,
            final EasyOnboardingInvite.OnInviteRequested callback,
            final boolean formAlreadySubmitted) {
        if (response.getType() == Iq.Type.ERROR) {
            callback.inviteRequestFailed(IqParser.errorMessage(response));
            return;
        }
        if (response.getType() != Iq.Type.RESULT) {
            callback.inviteRequestFailed(getString(R.string.remote_server_timeout));
            return;
        }

        final EasyOnboardingInvite invite = parseEasyOnboardingInviteResponse(account, response);
        if (invite != null) {
            callback.inviteRequested(invite);
            return;
        }

        final Element resultCommand = response.findChild("command", Namespace.COMMANDS);
        final String status = resultCommand == null ? null : resultCommand.getAttribute("status");

        // XEP-0401#create-account is a two-step ad-hoc command. A compliant server may first
        // return status='executing' with a data form and only return the invite URI after the
        // client completes that form. The old NeoCont code tried to parse an URI immediately,
        // which produced "Unable to parse invitation" on standard ejabberd mod_invites.
        if (!formAlreadySubmitted && resultCommand != null && "executing".equals(status)) {
            final Element formElement = resultCommand.findChild("x", Namespace.DATA);
            final String sessionId = resultCommand.getAttribute("sessionid");
            if (formElement != null && !Strings.isNullOrEmpty(sessionId)) {
                final Data form = Data.parse(formElement);
                final Field username = form.getFieldByName("username");
                if (username != null && username.isRequired()) {
                    // NeoCont creates generic invitation links; it deliberately does not reserve
                    // a username. If a custom server requires one, do not send an invalid form.
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": create-account invite requires a username");
                    callback.inviteRequestFailed(getString(R.string.unable_to_parse_invite));
                    return;
                }

                final Data submit = new Data();
                submit.setAttribute("type", "submit");
                // Ask the server to establish the inviter/invitee roster relationship when the
                // server exposes that optional field. No username means the invitee chooses it.
                if (form.getFieldByName("roster-subscription") != null) {
                    submit.put("roster-subscription", "1");
                }

                final Iq complete = new Iq(Iq.Type.SET);
                complete.setTo(commandJid);
                final Element command = complete.addChild("command", Namespace.COMMANDS);
                command.setAttribute("node", commandNode);
                command.setAttribute("sessionid", sessionId);
                command.setAttribute("action", "complete");
                command.addChild(submit);
                sendIqPacket(
                        account,
                        complete,
                        nextResponse ->
                                handleEasyOnboardingInviteResponse(
                                        account,
                                        commandJid,
                                        commandNode,
                                        nextResponse,
                                        callback,
                                        true));
                return;
            }
        }

        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": invite command returned no URI. node="
                        + commandNode
                        + ", status="
                        + status);
        callback.inviteRequestFailed(getString(R.string.unable_to_parse_invite));
    }

    private EasyOnboardingInvite parseEasyOnboardingInviteResponse(
            final Account account, final Iq response) {
        final Element resultCommand = response.findChild("command", Namespace.COMMANDS);
        final Element x =
                resultCommand == null ? null : resultCommand.findChild("x", Namespace.DATA);
        if (x == null) {
            return null;
        }

        // XEP-0401 0.6 uses fields directly under <x/>. Older server modules based on
        // XEP-0401 0.4/0.5 commonly wrap result fields in <item/>. Accept both shapes.
        EasyOnboardingInvite invite = parseEasyOnboardingInviteData(account, Data.parse(x));
        if (invite != null) {
            return invite;
        }
        final Element item = x.findChild("item");
        return item == null ? null : parseEasyOnboardingInviteData(account, Data.parse(item));
    }

    private EasyOnboardingInvite parseEasyOnboardingInviteData(
            final Account account, final Data data) {
        final String uri = data.getValue("uri");
        if (Strings.isNullOrEmpty(uri)) {
            return null;
        }
        final String landingUrl = data.getValue("landing-url");
        return new EasyOnboardingInvite(account.getDomain().toString(), uri, landingUrl);
    }

    public void fetchBookmarks(final Account account) {
        final Iq iqPacket = new Iq(Iq.Type.GET);
        final Element query = iqPacket.query("jabber:iq:private");
        query.addChild("storage", Namespace.BOOKMARKS);
        final Consumer<Iq> callback =
                (response) -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element query1 = response.query();
                        final Element storage = query1.findChild("storage", "storage:bookmarks");
                        Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                        processBookmarksInitial(account, bookmarks, false);
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": could not fetch bookmarks");
                    }
                };
        sendIqPacket(account, iqPacket, callback);
    }

    public void fetchBookmarks2(final Account account) {
        final Iq retrieve = mIqGenerator.retrieveBookmarks();
        sendIqPacket(
                account,
                retrieve,
                (response) -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element pubsub = response.findChild("pubsub", Namespace.PUBSUB);
                        final Map<Jid, Bookmark> bookmarks =
                                Bookmark.parseFromPubSub(pubsub, account);
                        processBookmarksInitial(account, bookmarks, true);
                    }
                });
    }

    public void fetchMessageDisplayedSynchronization(final Account account) {
        Log.d(Config.LOGTAG, account.getJid() + ": retrieve mds");
        final var retrieve = mIqGenerator.retrieveMds();
        sendIqPacket(
                account,
                retrieve,
                (response) -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        return;
                    }
                    final var pubSub = response.findChild("pubsub", Namespace.PUBSUB);
                    final Element items = pubSub == null ? null : pubSub.findChild("items");
                    if (items == null
                            || !Namespace.MDS_DISPLAYED.equals(items.getAttribute("node"))) {
                        return;
                    }
                    for (final Element child : items.getChildren()) {
                        if ("item".equals(child.getName())) {
                            processMdsItem(account, child);
                        }
                    }
                });
    }

    public void processMdsItem(final Account account, final Element item) {
        final Jid jid =
                item == null ? null : Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("id"));
        if (jid == null) {
            return;
        }
        final Element displayed = item.findChild("displayed", Namespace.MDS_DISPLAYED);
        final Element stanzaId =
                displayed == null ? null : displayed.findChild("stanza-id", Namespace.STANZA_IDS);
        final String id = stanzaId == null ? null : stanzaId.getAttribute("id");
        final Conversation conversation = find(account, jid, null);
        if (id != null && conversation != null) {
            conversation.setDisplayState(id);
            markReadUpToStanzaId(conversation, id);
        }
    }

    public void markReadUpToStanzaId(final Conversation conversation, final String stanzaId) {
        final Message message = conversation.findMessageWithServerMsgId(stanzaId);
        if (message == null) { // do we want to check if isRead?
            return;
        }
        markReadUpTo(conversation, message);
    }

    public void markReadUpTo(final Conversation conversation, final Message message) {
        final boolean isDismissNotification = isDismissNotification(message);
        final var uuid = message.getUuid();
        Log.d(
                Config.LOGTAG,
                conversation.getAccount().getJid().asBareJid()
                        + ": mark "
                        + conversation.getJid().asBareJid()
                        + " as read up to "
                        + uuid);
        markRead(conversation, uuid, isDismissNotification);
    }

    private static boolean isDismissNotification(final Message message) {
        Message next = message.next();
        while (next != null) {
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                return false;
            }
            next = next.next();
        }
        return true;
    }

    public void processBookmarksInitial(
            final Account account, final Map<Jid, Bookmark> bookmarks, final boolean pep) {
        final Set<Jid> previousBookmarks = account.getBookmarkedJids();
        final List<Bookmark> passwordBearingBookmarks = new ArrayList<>();
        for (final Bookmark bookmark : bookmarks.values()) {
            previousBookmarks.remove(bookmark.getJid().asBareJid());
            final String bookmarkPassword = bookmark.getPassword();
            if (bookmarkPassword != null) {
                if (ScopedAccountSecretVaultV1.storeString(
                        getApplicationContext(),
                        account.getUuid(),
                        "BOOKMARK_PASSWORD",
                        bookmark.getJid().asBareJid().toString(),
                        bookmarkPassword)) {
                    bookmark.setPassword(null);
                    passwordBearingBookmarks.add(bookmark);
                } else {
                    Log.w(Config.LOGTAG, "unable to protect bookmark password from server");
                }
            }
            processModifiedBookmark(bookmark, pep);
        }
        if (pep) {
            processDeletedBookmarks(account, previousBookmarks);
        }
        account.setBookmarks(bookmarks);
        // Retire pre-existing server-side password copies after the local protected copy is
        // verified.
        for (final Bookmark bookmark : passwordBearingBookmarks) {
            createBookmark(account, bookmark);
        }
    }

    public void processDeletedBookmarks(final Account account, final Collection<Jid> bookmarks) {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": "
                        + bookmarks.size()
                        + " bookmarks have been removed");
        for (final Jid bookmark : bookmarks) {
            processDeletedBookmark(account, bookmark);
        }
    }

    public void processDeletedBookmark(final Account account, final Jid jid) {
        final Conversation conversation = find(account, jid, null);
        if (conversation == null) {
            return;
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": archiving MUC " + jid + " after PEP update");
        archiveConversation(conversation, false);
    }

    private void processModifiedBookmark(final Bookmark bookmark, final boolean pep) {
        final Account account = bookmark.getAccount();
        if (isMucForgotten(account, bookmark.getJid())) {
            bookmark.setConversation(null);
            deleteBookmark(account, bookmark);
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": retracting stale bookmark for forgotten MUC "
                            + bookmark.getJid().asBareJid());
            return;
        }
        if (isMucExplicitlyLeft(account, bookmark.getJid())) {
            bookmark.setConversation(null);
            if (bookmark.autojoin()) {
                bookmark.setAutojoin(false);
                createBookmark(account, bookmark);
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": keeping bookmark with autojoin disabled for explicitly left MUC "
                            + bookmark.getJid().asBareJid());
            return;
        }
        Conversation conversation = find(bookmark.getAccount(), bookmark.getJid(), null);
        if (conversation == null) {
            final Conversation archived =
                    databaseBackend.findConversation(account, bookmark.getJid().asBareJid(), null);
            if (archived != null && archived.isMucExplicitlyLeft()) {
                rememberMucExplicitLeave(account, bookmark.getJid());
                bookmark.setConversation(null);
                if (bookmark.autojoin()) {
                    bookmark.setAutojoin(false);
                    createBookmark(account, bookmark);
                }
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": preserving bookmark for legacy explicitly left MUC "
                                + bookmark.getJid().asBareJid());
                return;
            }
        }
        if (conversation != null && conversation.isMucExplicitlyLeft()) {
            bookmark.setConversation(null);
            if (bookmark.autojoin()) {
                bookmark.setAutojoin(false);
                createBookmark(account, bookmark);
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": keeping explicitly left MUC archived despite bookmark sync "
                            + bookmark.getJid().asBareJid());
            return;
        }
        if (conversation != null) {
            if (conversation.getMode() != Conversation.MODE_MULTI) {
                return;
            }
            bookmark.setConversation(conversation);
            hydrateBookmarkPassword(conversation, bookmark);
            if (pep && !bookmark.autojoin()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": archiving conference ("
                                + conversation.getJid()
                                + ") after receiving pep");
                archiveConversation(conversation, false);
            } else {
                final MucOptions mucOptions = conversation.getMucOptions();
                if (mucOptions.getError() == MucOptions.Error.NICK_IN_USE) {
                    final String current = mucOptions.getActualNick();
                    final String proposed = mucOptions.getProposedNickPure();
                    if (current != null && !current.equals(proposed)) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": proposed nick changed after bookmark push "
                                        + current
                                        + "->"
                                        + proposed);
                        joinMuc(conversation);
                    }
                } else {
                    checkMucRequiresRename(conversation);
                }
            }
        } else if (bookmark.autojoin()) {
            // Do not auto-join inside creation: the protected bookmark password must be hydrated
            // before the first presence stanza is generated.
            conversation =
                    findOrCreateConversation(
                            account, bookmark.getFullJid(), null, true, false, false, null);
            bookmark.setConversation(conversation);
            hydrateBookmarkPassword(conversation, bookmark);
            joinMuc(conversation);
        }
    }

    private void hydrateBookmarkPassword(final Conversation conversation, final Bookmark bookmark) {
        if (conversation == null || bookmark == null) {
            return;
        }
        final String password =
                ScopedAccountSecretVaultV1.readString(
                        getApplicationContext(),
                        bookmark.getAccount().getUuid(),
                        "BOOKMARK_PASSWORD",
                        bookmark.getJid().asBareJid().toString());
        if (password != null) {
            conversation.getMucOptions().setPassword(password);
            ScopedAccountSecretVaultV1.storeString(
                    getApplicationContext(),
                    bookmark.getAccount().getUuid(),
                    "MUC_PASSWORD",
                    conversation.getUuid(),
                    password);
        }
    }

    public void processModifiedBookmark(final Bookmark bookmark) {
        processModifiedBookmark(bookmark, true);
    }

    public void ensureBookmarkIsAutoJoin(final Conversation conversation) {
        clearMucExplicitLeave(conversation.getAccount(), conversation.getJid());
        if (conversation.setMucExplicitlyLeft(false)) {
            updateConversation(conversation);
        }
        final var account = conversation.getAccount();
        final var existingBookmark = conversation.getBookmark();
        if (existingBookmark == null) {
            final var bookmark = new Bookmark(account, conversation.getJid().asBareJid());
            bookmark.setAutojoin(true);
            createBookmark(account, bookmark);
        } else {
            if (existingBookmark.autojoin()) {
                return;
            }
            existingBookmark.setAutojoin(true);
            createBookmark(account, existingBookmark);
        }
    }

    public void createBookmark(final Account account, final Bookmark bookmark) {
        final String bookmarkPassword = bookmark.getPassword();
        if (bookmarkPassword != null) {
            if (!ScopedAccountSecretVaultV1.storeString(
                    getApplicationContext(),
                    account.getUuid(),
                    "BOOKMARK_PASSWORD",
                    bookmark.getJid().asBareJid().toString(),
                    bookmarkPassword)) {
                Log.w(Config.LOGTAG, "bookmark password vault unavailable; refusing server update");
                return;
            }
            // Passwords are local protected secrets. Never publish them in XMPP bookmarks.
            bookmark.setPassword(null);
        }
        account.putBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": no connection. ignoring bookmark creation");
        } else if (connection.getFeatures().bookmarks2()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": pushing bookmark via Bookmarks 2");
            final Element item = mIqGenerator.publishBookmarkItem(bookmark);
            pushNodeAndEnforcePublishOptions(
                    account,
                    Namespace.BOOKMARKS2,
                    item,
                    bookmark.getJid().asBareJid().toString(),
                    PublishOptions.persistentWhitelistAccessMaxItems());
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    public void deleteBookmark(final Account account, final Bookmark bookmark) {
        account.removeBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": bookmark removed locally; server retract deferred until"
                            + " reconnect");
            return;
        }
        if (connection.getFeatures().bookmarks2()) {
            final Iq request =
                    mIqGenerator.deleteItem(
                            Namespace.BOOKMARKS2, bookmark.getJid().asBareJid().toString());
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": removing bookmark via Bookmarks 2");
            sendIqPacket(
                    account,
                    request,
                    (response) -> {
                        if (response.getType() == Iq.Type.ERROR) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": unable to delete bookmark "
                                            + response.getErrorCondition());
                        }
                    });
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    private void pushBookmarksPrivateXml(Account account) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": pushing bookmarks via private xml");
        final Iq iqPacket = new Iq(Iq.Type.SET);
        Element query = iqPacket.query("jabber:iq:private");
        Element storage = query.addChild("storage", "storage:bookmarks");
        for (final Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        sendIqPacket(account, iqPacket, mDefaultIqHandler);
    }

    private void pushBookmarksPep(Account account) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": pushing bookmarks via pep");
        final Element storage = new Element("storage", "storage:bookmarks");
        for (final Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        pushNodeAndEnforcePublishOptions(
                account,
                Namespace.BOOKMARKS,
                storage,
                "current",
                PublishOptions.persistentWhitelistAccess());
    }

    private void pushNodeAndEnforcePublishOptions(
            final Account account,
            final String node,
            final Element element,
            final String id,
            final Bundle options) {
        pushNodeAndEnforcePublishOptions(account, node, element, id, options, true);
    }

    private void pushNodeAndEnforcePublishOptions(
            final Account account,
            final String node,
            final Element element,
            final String id,
            final Bundle options,
            final boolean retry) {
        final Iq packet = mIqGenerator.publishElement(node, element, id, options);
        sendIqPacket(
                account,
                packet,
                (response) -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        return;
                    }
                    if (retry && PublishOptions.preconditionNotMet(response)) {
                        pushNodeConfiguration(
                                account,
                                node,
                                options,
                                new OnConfigurationPushed() {
                                    @Override
                                    public void onPushSucceeded() {
                                        pushNodeAndEnforcePublishOptions(
                                                account, node, element, id, options, false);
                                    }

                                    @Override
                                    public void onPushFailed() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": unable to push node configuration ("
                                                        + node
                                                        + ")");
                                    }
                                });
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": error publishing "
                                        + node
                                        + " (retry="
                                        + retry
                                        + ") "
                                        + response);
                    }
                });
    }

    private Map<String, Conversation> computeBareIdConversationsMap() {
        Map<String, Conversation> res = new HashMap<>();
        for (Conversation c : this.conversations) {
            if (c.getNextCounterpart() == null) {
                res.put(c.getJid().asBareJid().toString(), c);
            }
        }

        return res;
    }

    private void restoreFromDatabase() {
        SecureColdStartPerfTrace.milestone("service_restore_started");
        synchronized (this.conversations) {
            final Map<String, Account> accountLookupTable =
                    ImmutableMap.copyOf(Maps.uniqueIndex(this.accounts, Account::getUuid));
            Log.d(Config.LOGTAG, "restoring conversations...");
            final long startTimeConversationsRestore = SystemClock.elapsedRealtime();
            final long coldStartConversationsRestore = System.nanoTime();
            this.conversations.addAll(
                    databaseBackend.getConversations(Conversation.STATUS_AVAILABLE));

            Map<String, Conversation> map = null;

            for (Conversation c : this.conversations) {
                if (c.getNextCounterpart() != null) {
                    if (map == null) {
                        map = computeBareIdConversationsMap();
                    }

                    Conversation parent = map.get(c.getJid().asBareJid().toString());

                    if (parent != null) {
                        c.setParentConversation(parent);
                    }
                }
            }

            for (Iterator<Conversation> iterator = conversations.listIterator();
                    iterator.hasNext(); ) {
                Conversation conversation = iterator.next();
                Account account = accountLookupTable.get(conversation.getAccountUuid());
                if (account != null) {
                    conversation.setAccount(account);
                } else {
                    Log.e(
                            Config.LOGTAG,
                            "unable to restore Conversations with " + conversation.getJid());
                    iterator.remove();
                }
            }
            long diffConversationsRestore =
                    SystemClock.elapsedRealtime() - startTimeConversationsRestore;
            Log.d(
                    Config.LOGTAG,
                    "finished restoring conversations in " + diffConversationsRestore + "ms");
            SecureColdStartPerfTrace.stage(
                    "conversations_restore", System.nanoTime() - coldStartConversationsRestore);
            SecureColdStartPerfTrace.increment("conversations_loaded", this.conversations.size());
            Runnable runnable =
                    () -> {
                        try {
                            final long restorePrepStarted = System.nanoTime();
                            DatabaseBackend backend = DatabaseBackendProvider.getInstance(this);
                            if (backend.requiresMessageIndexRebuild()) {
                                backend.rebuildMessagesIndex();
                            }
                            final long deletionDate = getAutomaticMessageDeletionDate();
                            mLastExpiryRun.set(SystemClock.elapsedRealtime());
                            if (deletionDate > 0) {
                                Log.d(
                                        Config.LOGTAG,
                                        "deleting messages that are older than "
                                                + AbstractGenerator.getTimestamp(deletionDate));
                                databaseBackend.expireOldMessages(deletionDate);
                            }
                            Log.d(Config.LOGTAG, "restoring roster...");
                            for (final Account account : accounts) {
                                try {
                                    databaseBackend.readRoster(account.getRoster());
                                } catch (final RuntimeException | AssertionError e) {
                                    Log.e(
                                            Config.LOGTAG,
                                            "roster restore failed for one account; continuing"
                                                    + " startup",
                                            e);
                                }
                                try {
                                    account.initAccountServices(
                                            XmppConnectionService
                                                    .this); // roster needs to be loaded at this
                                    // stage
                                } catch (final RuntimeException | AssertionError e) {
                                    Log.e(
                                            Config.LOGTAG,
                                            "crypto service init failed for one account; continuing"
                                                    + " message restore",
                                            e);
                                }
                            }
                            getBitmapCache().evictAll();
                            loadPhoneContacts();
                            SecureColdStartPerfTrace.stage(
                                    "restore_pre_messages", System.nanoTime() - restorePrepStarted);
                            Log.d(Config.LOGTAG, "restoring messages...");
                            final long startMessageRestore = SystemClock.elapsedRealtime();
                            final long coldStartMessageRestore = System.nanoTime();

                            // Restore only the latest row for every conversation first. The
                            // overview
                            // adapter uses getLatestMessage() for text/media preview, timestamp and
                            // ordering, so this makes the visible chat list useful before full
                            // pages
                            // (and their protected text payloads) are hydrated.
                            final long previewRestoreStarted = System.nanoTime();
                            final Map<Conversation, Message> previewsByConversation =
                                    new HashMap<>();
                            final List<Message> previewMessages = new ArrayList<>();
                            for (final Conversation conversation : this.conversations) {
                                try {
                                    final Message preview = loadConversationPreview(conversation);
                                    if (preview != null) {
                                        previewsByConversation.put(conversation, preview);
                                        previewMessages.add(preview);
                                    }
                                } catch (final RuntimeException | AssertionError e) {
                                    Log.e(
                                            Config.LOGTAG,
                                            "conversation preview restore failed; continuing with"
                                                    + " remaining previews",
                                            e);
                                }
                            }

                            final long previewHydrateStarted = System.nanoTime();
                            hydrateProtectedTextConversationPreviews(previewMessages);
                            SecureColdStartPerfTrace.stage(
                                    "conversation_preview_hydrate",
                                    System.nanoTime() - previewHydrateStarted);

                            int previewsLoaded = 0;
                            for (final Map.Entry<Conversation, Message> entry :
                                    previewsByConversation.entrySet()) {
                                final Conversation conversation = entry.getKey();
                                conversation.addAll(0, List.of(entry.getValue()), false);
                                if (conversation.countMessages() > 0) {
                                    previewsLoaded++;
                                }
                            }

                            SecureColdStartPerfTrace.stage(
                                    "conversation_previews_total",
                                    System.nanoTime() - previewRestoreStarted);
                            SecureColdStartPerfTrace.increment(
                                    "conversation_previews_loaded", previewsLoaded);
                            updateConversationUi();
                            SecureColdStartPerfTrace.milestone(
                                    "conversation_list_previews_visible");

                            final boolean quickLoadWasInMemory = QuickLoader.hasInMemoryTarget();
                            final Conversation quickLoad =
                                    QuickLoader.get(getApplicationContext(), this.conversations);
                            if (quickLoad != null) {
                                SecureColdStartPerfTrace.increment("quick_loader_hits", 1);
                                SecureColdStartPerfTrace.increment(
                                        quickLoadWasInMemory
                                                ? "quick_loader_in_memory_hits"
                                                : "quick_loader_persisted_hits",
                                        1);
                                final long quickRestoreStarted = System.nanoTime();
                                try {
                                    restoreMessages(quickLoad, true);
                                    SecureColdStartPerfTrace.stage(
                                            "quick_loader_restore",
                                            System.nanoTime() - quickRestoreStarted);
                                    updateConversationUi();
                                    SecureColdStartPerfTrace.milestone(
                                            "first_message_batch_visible");
                                    final long diffMessageRestore =
                                            SystemClock.elapsedRealtime() - startMessageRestore;
                                    Log.d(
                                            Config.LOGTAG,
                                            "quickly restored "
                                                    + quickLoad.getName()
                                                    + " after "
                                                    + diffMessageRestore
                                                    + "ms");
                                } catch (final RuntimeException | AssertionError e) {
                                    SecureColdStartPerfTrace.stage(
                                            "quick_loader_restore",
                                            System.nanoTime() - quickRestoreStarted);
                                    Log.e(
                                            Config.LOGTAG,
                                            "quick conversation restore failed; continuing with"
                                                    + " remaining history",
                                            e);
                                }
                            } else {
                                SecureColdStartPerfTrace.increment("quick_loader_misses", 1);
                            }
                            for (Conversation conversation : this.conversations) {
                                if (quickLoad != conversation) {
                                    try {
                                        restoreMessages(conversation, false);
                                    } catch (final RuntimeException | AssertionError e) {
                                        Log.e(
                                                Config.LOGTAG,
                                                "conversation restore failed; continuing with"
                                                        + " remaining history",
                                                e);
                                    }
                                }
                            }
                            mNotificationService.finishBacklog();
                            restoredFromDatabaseLatch.countDown();
                            messageRestoreCompletedAt.compareAndSet(
                                    0L, SystemClock.elapsedRealtime());
                            final long diffMessageRestore =
                                    SystemClock.elapsedRealtime() - startMessageRestore;
                            SecureColdStartPerfTrace.stage(
                                    "message_restore_total",
                                    System.nanoTime() - coldStartMessageRestore);
                            SecureColdStartPerfTrace.milestone("messages_restore_complete");
                            Log.d(
                                    Config.LOGTAG,
                                    "finished restoring messages in " + diffMessageRestore + "ms");
                            updateConversationUi();
                            SecureColdStartPerfTrace.milestone("first_message_batch_visible");
                            SecureColdStartPerfTrace.finish();
                            scheduleSecureContentRelationBackfill();
                        } finally {
                            // Keep coordination correct even if an unexpected restore defect is
                            // allowed to surface through the executor's uncaught-exception path.
                            restoredFromDatabaseLatch.countDown();
                        }
                    };
            mDatabaseReaderExecutor.execute(
                    runnable); // will contain one write command (expiry) but that's fine
        }
    }

    @Nullable
    private Message loadConversationPreview(final Conversation conversation) {
        final long readStarted = System.nanoTime();
        final List<Message> messages = databaseBackend.getMessages(conversation, 1);
        SecureColdStartPerfTrace.stage(
                "conversation_preview_db_read", System.nanoTime() - readStarted);
        return messages.isEmpty() ? null : messages.get(0);
    }

    private void restoreMessages(
            final Conversation conversation, final boolean hydrateWholeProtectedPage) {
        final long pageReadStarted = System.nanoTime();
        final List<Message> loaded = databaseBackend.getMessages(conversation, Config.PAGE_SIZE);
        SecureColdStartPerfTrace.stage("message_db_read", System.nanoTime() - pageReadStarted);
        SecureColdStartPerfTrace.increment("message_pages", 1);
        SecureColdStartPerfTrace.increment("messages_loaded", loaded.size());

        // A preview row may already be resident from the first-pass overview restore. Reuse that
        // exact Message instance so its verified protected body and presentation state survive,
        // while older page rows are inserted around it without creating a duplicate.
        final List<Message> messages = new ArrayList<>(loaded.size());
        for (final Message loadedMessage : loaded) {
            final Message resident = conversation.findMessageWithUuid(loadedMessage.getUuid());
            messages.add(resident == null ? loadedMessage : resident);
        }

        final long textHydrateStarted = System.nanoTime();
        if (hydrateWholeProtectedPage) {
            hydrateProtectedTextMessages(messages);
        } else {
            hydrateProtectedTextRequiredForClosedConversation(messages);
        }
        SecureColdStartPerfTrace.stage(
                "secure_text_hydrate", System.nanoTime() - textHydrateStarted);

        final long replyRestoreStarted = System.nanoTime();
        restoreRepliesForMessages(conversation, messages);
        SecureColdStartPerfTrace.stage("reply_restore", System.nanoTime() - replyRestoreStarted);

        final List<Message> additions = new ArrayList<>(messages.size());
        for (final Message message : messages) {
            if (conversation.findMessageWithUuid(message.getUuid()) == null) {
                additions.add(message);
            }
        }

        final long pageApplyStarted = System.nanoTime();
        conversation.addAll(0, additions, false);
        conversation.findUnsentTextMessages(
                message -> markMessage(message, Message.STATUS_WAITING));
        conversation.findUnreadMessagesAndCalls(mNotificationService::pushFromBacklog);
        SecureColdStartPerfTrace.stage("message_page_apply", System.nanoTime() - pageApplyStarted);
    }

    /**
     * Closed conversations need durable classification and notification/resend plaintext, but they
     * do not need every historical protected body materialized during process start.
     *
     * <p>Modes are loaded for the whole resident page so opening the conversation can immediately
     * render the protected placeholder and schedule bounded rehydrate. Only unread rows
     * (notification text / MUC highlight) and unsent outgoing rows are decrypted on cold start.
     */
    private void hydrateProtectedTextRequiredForClosedConversation(final List<Message> messages) {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT || messages.isEmpty()) {
            return;
        }
        final String accountUuid = messages.get(0).getConversation().getAccount().getUuid();
        try {
            databaseBackend.loadSecureMessagePayloadModes(accountUuid, messages);
        } catch (final RuntimeException | AssertionError e) {
            Log.w(
                    Config.LOGTAG,
                    "protected text mode load failed for deferred startup page; hydrating page",
                    e);
            hydrateProtectedTextMessages(messages);
            return;
        }

        final List<Message> required = new ArrayList<>();
        long deferred = 0L;
        for (final Message message : messages) {
            if (message.getSecureMessagePayloadMode() != SecureMessagePayloadMode.PROTECTED
                    || message.hasVerifiedProtectedBody()) {
                continue;
            }
            if (!message.isRead()
                    || message.getStatus() == Message.STATUS_UNSEND
                    || message.getStatus() == Message.STATUS_WAITING) {
                required.add(message);
            } else {
                deferred++;
            }
        }
        if (deferred > 0L) {
            SecureColdStartPerfTrace.increment("secure_text_reads_deferred", deferred);
        }
        if (!required.isEmpty()) {
            hydrateProtectedTextMessages(required);
        }
    }

    private void hydrateProtectedTextMessages(final List<Message> messages) {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT || messages == null || messages.isEmpty()) {
            return;
        }
        final SecureMessageTextRepository repository = getSecureMessageTextRepository();
        if (repository != null) {
            try {
                repository.hydrateLoadedPage(messages);
            } catch (final Exception | AssertionError e) {
                // History rows are still useful even if one protected batch cannot be decrypted
                // yet. Keep startup/history available and leave affected protected bodies empty
                // until secure storage becomes readable again.
                Log.e(
                        Config.LOGTAG,
                        "protected text hydration failed for loaded page; continuing history"
                                + " restore",
                        e);
            }
        }
    }

    private void hydrateProtectedTextConversationPreviews(final List<Message> messages) {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT || messages.isEmpty()) {
            return;
        }
        final SecureMessageTextRepository repository = getSecureMessageTextRepository();
        if (repository != null) {
            try {
                repository.hydrateConversationPreviews(messages);
            } catch (final Exception | AssertionError e) {
                // Kotlin read-plan setup can throw a checked security exception while HS is
                // locked. Defer protected bodies until unlock, as with loaded history pages.
                Log.e(
                        Config.LOGTAG,
                        "protected text preview hydration failed; continuing conversation restore",
                        e);
            }
        }
    }

    /**
     * Builds only the non-authoritative message -> content routing accelerator for stores that
     * predate it. This is intentionally off the serial database restore path and runs at most once
     * at a time. Exact AEAD metadata remains authoritative after the candidate is resolved.
     */
    private void scheduleSecureContentRelationBackfill() {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT
                || destroyed
                || !(getApplication() instanceof Conversations)
                || !secureContentRelationBackfillInFlight.compareAndSet(false, true)) {
            return;
        }
        secureContentMaintenanceExecutor.execute(
                () -> {
                    boolean rebuilt = false;
                    try {
                        final Conversations application = (Conversations) getApplication();
                        rebuilt =
                                application
                                        .getSecureContentStoreProvider()
                                        .backfillMessageRelationIndexIfNeeded();
                    } catch (final Exception | AssertionError e) {
                        Log.w(Config.LOGTAG, "secure content relation backfill deferred", e);
                    } finally {
                        secureContentRelationBackfillInFlight.set(false);
                    }
                    if (rebuilt && !destroyed) {
                        updateConversationPresentationUi();
                    }
                });
    }

    /**
     * Rehydrates protected text only for the currently presented conversation page.
     *
     * <p>Normal read-cache eviction does not invalidate a verified live Message. This repair path
     * remains for genuine transient misses (for example a page restored while crypto was
     * unavailable) and is kept off RecyclerView binding because UI binding must never open the
     * Store.
     */
    public void scheduleProtectedTextRehydrateForConversation(final Conversation conversation) {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT
                || conversation == null
                || destroyed
                || !SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                        getApplicationContext())) {
            return;
        }

        final List<Message> resident = new ArrayList<>();
        conversation.populateWithMessages(resident);
        if (!hasMissingProtectedText(resident)) {
            return;
        }

        final String key = conversation.getAccount().getUuid() + ":" + conversation.getUuid();
        if (!protectedTextConversationRehydrateInFlight.add(key)) {
            // Do not drop a refresh request that races with an active MUC catch-up pass. The
            // in-flight snapshot may predate a later LRU eviction, so remember one coalesced
            // follow-up instead of waiting for another user/navigation event.
            protectedTextConversationRehydratePending.add(key);
            return;
        }

        mDatabaseReaderExecutor.execute(
                () -> {
                    int restored = 0;
                    try {
                        if (!SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                                getApplicationContext())) {
                            return;
                        }

                        // Re-snapshot at execution time because MAM may still be appending while
                        // this task is queued. Keep the pass within the normal resident-page
                        // budget, below the protected-text LRU capacity.
                        final List<Message> latest = new ArrayList<>();
                        conversation.populateWithMessages(latest);
                        final List<Message> candidates = missingProtectedTextCandidates(latest);
                        if (candidates.isEmpty()) {
                            return;
                        }

                        hydrateProtectedTextMessages(candidates);
                        for (final Message message : candidates) {
                            if (message.hasVerifiedProtectedBody()) {
                                restored++;
                            }
                        }
                    } catch (final RuntimeException | AssertionError e) {
                        Log.w(Config.LOGTAG, "visible protected text rehydrate failed", e);
                    } finally {
                        protectedTextConversationRehydrateInFlight.remove(key);
                        if (protectedTextConversationRehydratePending.remove(key)) {
                            // Re-check the current resident window after the active pass. This is
                            // bounded/coalesced: one pending bit per conversation, and the public
                            // scheduler exits immediately when no protected bodies are missing.
                            scheduleProtectedTextRehydrateForConversation(conversation);
                        }
                    }

                    // Do not create a retry loop for genuinely unavailable/corrupt payloads.
                    // A later real UI/MAM event may retry them, but only successful hydration
                    // causes this pass to publish another UI refresh.
                    if (restored > 0) {
                        updateConversationPresentationUi();
                    }
                });
    }

    /**
     * Repairs a protected-text miss for a Message that is actually being bound on screen.
     *
     * <p>This is a fallback for a genuine missing verified presentation, not normal cache eviction.
     * Adapter binding must not open secure storage, so it only schedules this per-message
     * background repair. Requests are coalesced by message identity and remain fail-closed.
     */
    public void scheduleProtectedTextRehydrateForVisibleMessage(final Message message) {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT
                || message == null
                || destroyed
                || message.getSecureMessagePayloadMode() != SecureMessagePayloadMode.PROTECTED
                || message.hasVerifiedProtectedBody()
                || !SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                        getApplicationContext())) {
            return;
        }

        final Conversational conversational = message.getConversation();
        if (!(conversational instanceof Conversation conversation)
                || !isConversationStillOpen(conversation)) {
            return;
        }

        final String key = conversation.getAccount().getUuid() + ":" + message.getUuid();
        if (!protectedTextVisibleMessageRehydrateInFlight.add(key)) {
            return;
        }

        mDatabaseReaderExecutor.execute(
                () -> {
                    boolean restored = false;
                    try {
                        if (!SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                                        getApplicationContext())
                                || message.getSecureMessagePayloadMode()
                                        != SecureMessagePayloadMode.PROTECTED
                                || message.hasVerifiedProtectedBody()
                                || !isConversationStillOpen(conversation)) {
                            return;
                        }

                        final SecureMessageTextRepository repository =
                                getSecureMessageTextRepository();
                        if (repository == null) {
                            return;
                        }
                        restored = repository.hydrate(message);
                    } catch (final IOException | RuntimeException | AssertionError e) {
                        Log.w(Config.LOGTAG, "visible protected text targeted rehydrate failed", e);
                    } finally {
                        protectedTextVisibleMessageRehydrateInFlight.remove(key);
                    }

                    if (restored && message.hasVerifiedProtectedBody()) {
                        updateConversationPresentationUi();
                    }
                });
    }

    private static boolean hasMissingProtectedText(final List<Message> messages) {
        final int start = Math.max(0, messages.size() - Config.PAGE_SIZE * Config.MAX_NUM_PAGES);
        for (int i = start; i < messages.size(); i++) {
            final Message message = messages.get(i);
            if (message != null
                    && message.getSecureMessagePayloadMode() == SecureMessagePayloadMode.PROTECTED
                    && !message.hasVerifiedProtectedBody()) {
                return true;
            }
        }
        return false;
    }

    private static List<Message> missingProtectedTextCandidates(final List<Message> messages) {
        final int start = Math.max(0, messages.size() - Config.PAGE_SIZE * Config.MAX_NUM_PAGES);
        final List<Message> candidates = new ArrayList<>();
        for (int i = start; i < messages.size(); i++) {
            final Message message = messages.get(i);
            if (message != null
                    && message.getSecureMessagePayloadMode() == SecureMessagePayloadMode.PROTECTED
                    && !message.hasVerifiedProtectedBody()) {
                candidates.add(message);
            }
        }
        return candidates;
    }

    /**
     * Cold start may restore Message rows while High Security is still LOCKED. Those protected rows
     * intentionally remain body-less because the AMK is unavailable at that point. After a
     * successful crypto unlock, rehydrate only Message objects already resident in conversations.
     *
     * <p>This runs on the same serial database reader as cold-start restore. If unlock wins the
     * race, the task naturally executes after restore without a second DB page read or duplicate
     * Message objects.
     */
    private void scheduleResidentProtectedTextRehydrateAfterCryptoUnlock() {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT) {
            return;
        }
        final var snapshot = SecureContentCryptoSessionRuntimeV1.snapshot(getApplicationContext());
        if (snapshot.getState() != SecureContentCryptoSessionStateV1.ACTIVE) {
            return;
        }
        final long epoch = snapshot.getEpoch();
        if (protectedTextRehydrateCryptoEpoch.getAndSet(epoch) == epoch) {
            return;
        }

        mDatabaseReaderExecutor.execute(
                () -> {
                    if (!SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                            getApplicationContext())) {
                        protectedTextRehydrateCryptoEpoch.compareAndSet(epoch, Long.MIN_VALUE);
                        return;
                    }

                    // Keep the same bounded unit as normal history hydration. Combining every
                    // resident conversation into one account batch can exceed the protected-text
                    // read-plan bound and makes one failed batch suppress hydration everywhere.
                    boolean hadResidentMessages = false;
                    for (final Conversation conversation : getConversations()) {
                        if (!SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(
                                getApplicationContext())) {
                            protectedTextRehydrateCryptoEpoch.compareAndSet(epoch, Long.MIN_VALUE);
                            return;
                        }
                        final List<Message> resident = new ArrayList<>();
                        conversation.populateWithMessages(resident);
                        if (resident.isEmpty()) {
                            continue;
                        }
                        hadResidentMessages = true;
                        hydrateProtectedTextMessages(resident);
                    }
                    if (hadResidentMessages) {
                        updateConversationPresentationUi();
                    }
                });
    }

    /**
     * Schedules a tiny migration batch only from Messages already loaded for actual chat reading.
     *
     * <p>This method must not be called by cold-start conversation restoration. It performs no
     * account-wide scan: candidate identities and plaintext come exclusively from the supplied
     * resident page, and duplicate in-flight message identities are suppressed.
     */
    public void scheduleLegacyPlaintextMigrationForLoadedPage(final List<Message> loadedPage) {
        if (!Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT || loadedPage == null || loadedPage.isEmpty()) {
            return;
        }
        final List<Message> candidates = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        for (final Message message : loadedPage) {
            if (candidates.size()
                    >= SecureMessageTextRepository.DEFAULT_LAZY_MIGRATION_BATCH_SIZE) {
                break;
            }
            if (message == null
                    || !message.hasLegacyPlaintextBody()
                    || !isSecureContentAccountAvailableForMutation(message)) {
                continue;
            }
            final String key =
                    message.getConversation().getAccount().getUuid() + ":" + message.getUuid();
            if (legacyTextMigrationInFlight.add(key)) {
                candidates.add(message);
                keys.add(key);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        legacyTextMigrationExecutor.execute(
                () -> {
                    try {
                        synchronized (secureContentMigrationCleanupLock) {
                            final List<Message> eligible = new ArrayList<>();
                            for (final Message candidate : candidates) {
                                if (isSecureContentAccountAvailableForMutation(candidate)) {
                                    eligible.add(candidate);
                                }
                            }
                            if (eligible.isEmpty()) {
                                return;
                            }
                            final SecureMessageTextRepository repository =
                                    getSecureMessageTextRepository();
                            if (repository == null) {
                                return;
                            }
                            final var result =
                                    repository.migrateLegacyLoadedPage(
                                            eligible,
                                            SecureMessageTextRepository
                                                    .DEFAULT_LAZY_MIGRATION_BATCH_SIZE);
                            if (result.getRefreshed() > 0) {
                                updateConversationUi();
                            }
                        }
                    } catch (final RuntimeException exception) {
                        Log.w(Config.LOGTAG, "lazy legacy plaintext migration failed", exception);
                    } finally {
                        for (final String key : keys) {
                            legacyTextMigrationInFlight.remove(key);
                        }
                    }
                });
    }

    /** Schedules migration only for the page currently resident in an opened conversation. */
    public void scheduleLegacyPlaintextMigrationForConversation(final Conversation conversation) {
        if (conversation == null) {
            return;
        }
        final List<Message> resident = new ArrayList<>();
        conversation.populateWithMessages(resident);
        scheduleLegacyPlaintextMigrationForLoadedPage(resident);
    }

    public void loadPhoneContacts() {
        mContactMergerExecutor.execute(
                () -> {
                    final Map<Jid, JabberIdContact> contacts = JabberIdContact.load(this);
                    Log.d(Config.LOGTAG, "start merging phone contacts with roster");
                    for (final Account account : accounts) {
                        final List<Contact> withSystemAccounts =
                                account.getRoster().getWithSystemAccounts(JabberIdContact.class);
                        for (final JabberIdContact jidContact : contacts.values()) {
                            final Contact contact =
                                    account.getRoster().getContact(jidContact.getJid());
                            boolean needsCacheClean = contact.setPhoneContact(jidContact);
                            if (needsCacheClean) {
                                getAvatarService().clear(contact);
                            }
                            withSystemAccounts.remove(contact);
                        }
                        for (final Contact contact : withSystemAccounts) {
                            boolean needsCacheClean =
                                    contact.unsetPhoneContact(JabberIdContact.class);
                            if (needsCacheClean) {
                                getAvatarService().clear(contact);
                            }
                        }
                    }
                    Log.d(Config.LOGTAG, "finished merging phone contacts");
                    mShortcutService.refresh(
                            mInitialAddressbookSyncCompleted.compareAndSet(false, true));
                    updateRosterUi();
                    mQuickConversationsService.considerSync();
                });
    }

    public void syncRoster(final Account account) {
        mRosterSyncTaskManager.execute(
                account, () -> databaseBackend.writeRoster(account.getRoster()));
    }

    public List<Conversation> getConversations() {
        return this.conversations;
    }

    private void markFileDeleted(final File file) {
        synchronized (FILENAMES_TO_IGNORE_DELETION) {
            if (FILENAMES_TO_IGNORE_DELETION.remove(file.getAbsolutePath())) {
                Log.d(Config.LOGTAG, "ignored deletion of " + file.getAbsolutePath());
                return;
            }
        }
        final boolean isInternalFile = fileBackend.isInternalFile(file);
        final List<String> uuids = databaseBackend.markFileAsDeleted(file, isInternalFile);
        Log.d(
                Config.LOGTAG,
                "deleted file "
                        + file.getAbsolutePath()
                        + " internal="
                        + isInternalFile
                        + ", database hits="
                        + uuids.size());
        markUuidsAsDeletedFiles(uuids);
    }

    private void markUuidsAsDeletedFiles(List<String> uuids) {
        boolean deleted = false;
        for (Conversation conversation : getConversations()) {
            deleted |= conversation.markAsDeleted(uuids);
        }
        for (final String uuid : uuids) {
            evictPreview(uuid);
        }
        if (deleted) {
            updateConversationUi();
        }
    }

    private void markChangedFiles(List<FilePathInfo> infos) {
        boolean changed = false;
        for (Conversation conversation : getConversations()) {
            changed |= conversation.markAsChanged(infos);
        }
        if (changed) {
            updateConversationUi();
        }
    }

    public void populateWithOrderedConversations(final List<Conversation> list) {
        populateWithOrderedConversations(list, true, true);
    }

    public void populateWithOrderedConversations(
            final List<Conversation> list, final boolean includeNoFileUpload) {
        populateWithOrderedConversations(list, includeNoFileUpload, true);
    }

    public void populateWithOrderedConversations(
            final List<Conversation> list, final boolean includeNoFileUpload, final boolean sort) {
        final List<String> orderedUuids;
        if (sort) {
            orderedUuids = null;
        } else {
            orderedUuids = new ArrayList<>();
            for (Conversation conversation : list) {
                orderedUuids.add(conversation.getUuid());
            }
        }
        list.clear();
        if (includeNoFileUpload) {
            list.addAll(getConversations());
        } else {
            for (Conversation conversation : getConversations()) {
                if (conversation.getMode() == Conversation.MODE_SINGLE
                        || (conversation.getAccount().httpUploadAvailable()
                                && conversation.getMucOptions().participating())) {
                    list.add(conversation);
                }
            }
        }
        try {
            if (orderedUuids != null) {
                Collections.sort(
                        list,
                        (a, b) -> {
                            final int indexA = orderedUuids.indexOf(a.getUuid());
                            final int indexB = orderedUuids.indexOf(b.getUuid());
                            if (indexA == -1 || indexB == -1 || indexA == indexB) {
                                return a.compareTo(b);
                            }
                            return indexA - indexB;
                        });
            } else {
                Collections.sort(list);
            }
        } catch (IllegalArgumentException e) {
            // ignore
        }
    }

    public void jumpToMessage(
            final Conversation conversation, final String uuid, JumpToMessageListener listener) {
        final Runnable runnable =
                () -> {
                    final List<Message> messages =
                            databaseBackend.getMessagesNearUuid(conversation, 30, uuid);

                    if (messages == null || messages.isEmpty()) {
                        listener.onNotFound();
                        return;
                    }

                    hydrateProtectedTextMessages(messages);
                    restoreRepliesForMessages(conversation, messages);

                    conversation.jumpToHistoryPart(messages);
                    scheduleLegacyPlaintextMigrationForLoadedPage(messages);
                    listener.onSuccess();
                };

        mDatabaseReaderExecutor.execute(runnable);
    }

    public void restoreReplyForMessage(final Conversation conversation, Message message) {
        restoreRepliesForMessages(conversation, List.of(message));
    }

    public void restoreRepliesForMessages(final Conversation conversation, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Map<String, ArrayList<Message>> notFoundReplies = null;

        for (Message m : messages) {
            Element reply = m.getReplyOrReaction();

            if (reply == null) {
                continue;
            }

            String replyId = reply.getAttribute("id");

            if (replyId == null) {
                continue;
            }

            Message replyMessage = null;

            for (Message rep : messages) {
                if (StringUtils.equals(replyId, rep.getServerMsgId())) {
                    replyMessage = rep;
                    break;
                }

                if (StringUtils.equals(replyId, rep.getRemoteMsgId())) {
                    replyMessage = rep;
                    break;
                }

                if (StringUtils.equals(replyId, rep.getUuid())) {
                    replyMessage = rep;
                    break;
                }
            }

            if (replyMessage == null) {
                replyMessage = conversation.getMessageWithAnyMatchingId(replyId);
            }

            if (replyMessage != null) {
                m.setReplyMessage(replyMessage, false);
            } else {
                if (notFoundReplies == null) {
                    notFoundReplies = new HashMap<>();
                }

                ArrayList<Message> list =
                        notFoundReplies.computeIfAbsent(replyId, id -> new ArrayList<>());
                list.add(m);
            }
        }

        if (notFoundReplies != null) {
            List<Message> restored =
                    databaseBackend.getMessagesByIds(conversation, notFoundReplies.keySet());
            hydrateProtectedTextMessages(restored);

            for (String id : notFoundReplies.keySet()) {
                ArrayList<Message> notFoundRepliesMessages = notFoundReplies.get(id);
                if (notFoundRepliesMessages == null) continue;

                for (Message m : restored) {
                    if (StringUtils.equals(id, m.getServerMsgId())) {
                        for (Message rm : notFoundRepliesMessages) {
                            rm.setReplyMessage(m, true);
                        }
                        break;
                    }

                    if (StringUtils.equals(id, m.getRemoteMsgId())) {
                        for (Message rm : notFoundRepliesMessages) {
                            rm.setReplyMessage(m, true);
                        }
                        break;
                    }

                    if (StringUtils.equals(id, m.getUuid())) {
                        for (Message rm : notFoundRepliesMessages) {
                            rm.setReplyMessage(m, true);
                        }
                        break;
                    }
                }
            }
        }
    }

    public void loadMoreMessages(
            final Conversation conversation,
            final long timestamp,
            boolean isForward,
            final OnMoreMessagesLoaded callback) {
        if (XmppConnectionService.this
                .getMessageArchiveService()
                .queryInProgress(conversation, callback)) {
            return;
        } else if (timestamp == 0) {
            return;
        }

        if (isForward) {
            Log.d(
                    Config.LOGTAG,
                    "load more messages for "
                            + conversation.getName()
                            + " after "
                            + MessageGenerator.getTimestamp(timestamp));
        } else {
            Log.d(
                    Config.LOGTAG,
                    "load more messages for "
                            + conversation.getName()
                            + " prior to "
                            + MessageGenerator.getTimestamp(timestamp));
        }

        final Runnable runnable =
                () -> {
                    final Account account = conversation.getAccount();
                    List<Message> messages =
                            databaseBackend.getMessages(
                                    conversation, Config.PAGE_SIZE, timestamp, isForward);
                    hydrateProtectedTextMessages(messages);

                    if (messages.size() > 0) {
                        restoreRepliesForMessages(conversation, messages);

                        if (isForward) {
                            conversation.addAll(-1, messages, true);
                        } else {
                            conversation.addAll(0, messages, true);
                        }
                        scheduleLegacyPlaintextMigrationForLoadedPage(messages);

                        callback.onMoreMessagesLoaded(messages.size(), conversation);
                    } else if (!isForward
                            && conversation.hasMessagesLeftOnServer()
                            && account.isOnlineAndConnected()
                            && conversation.getLastClearHistory().getTimestamp() == 0) {
                        final boolean mamAvailable;
                        if (conversation.getMode() == Conversation.MODE_SINGLE) {
                            mamAvailable =
                                    account.getXmppConnection().getFeatures().mam()
                                            && !conversation.getContact().isBlocked();
                        } else {
                            mamAvailable = conversation.getMucOptions().mamSupport();
                        }
                        if (mamAvailable) {
                            MessageArchiveService.Query query =
                                    getMessageArchiveService()
                                            .query(
                                                    conversation,
                                                    new MamReference(0),
                                                    timestamp,
                                                    false);
                            if (query != null) {
                                query.setCallback(callback);
                            }
                        }
                    }
                };
        mDatabaseReaderExecutor.execute(runnable);
    }

    public List<Account> getAccounts() {
        return this.accounts;
    }

    /**
     * Retires the removed LibreSpan-to-LibreSpan device-confirmation experiment.
     *
     * <p>Old builds may have left an ongoing notification, a notification channel, and
     * account-scoped preference markers. They no longer influence OMEMO trust; normal device-list
     * and QR/manual verification remain authoritative.
     */
    private void retireLegacyDeviceConfirmationState() {
        final SharedPreferences preferences = getPreferences();
        final Map<String, ?> snapshot = preferences.getAll();
        final NotificationManager notificationManager = getSystemService(NotificationManager.class);

        if (accounts != null && notificationManager != null) {
            for (final Account account : accounts) {
                final int accountHash = account.getUuid().hashCode();
                final int waitingId = 0x4e440000 ^ accountHash;
                notificationManager.cancel(waitingId);
                notificationManager.cancel(waitingId + 17);

                final Object rawKnown =
                        snapshot.get("device_approval_known_devices_" + account.getUuid());
                if (rawKnown instanceof String) {
                    for (final String part : ((String) rawKnown).split(",")) {
                        try {
                            final int deviceId = Integer.parseInt(part.trim());
                            notificationManager.cancel(0x4e430000 ^ accountHash ^ deviceId);
                        } catch (final NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        final SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (final String key : snapshot.keySet()) {
            if (key.startsWith("device_approval_") || key.startsWith("device_recovery_prompt_")) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            notificationManager.deleteNotificationChannel("device_security");
        }
    }

    private void synchronizeSecureContentAccountRegistry() {
        final Set<String> registeredAccountUuids = new HashSet<>();
        if (this.accounts != null) {
            for (final Account account : this.accounts) {
                // Secure Content ownership follows retained local account identity, not whether the
                // account is currently allowed to connect. Disabled and soft-logged-out accounts
                // still own their encrypted metadata/blobs until the local account is deleted.
                registeredAccountUuids.add(account.getUuid());
            }
        }
        if (getApplication() instanceof Conversations) {
            ((Conversations) getApplication())
                    .getSecureContentAccountRegistry()
                    .replaceRegisteredAccountUuids(registeredAccountUuids);
        }
    }

    /**
     * This will find all conferences with the contact as member and also the conference that is the
     * contact (that 'fake' contact is used to store the avatar)
     */
    public List<Conversation> findAllConferencesWith(Contact contact) {
        final ArrayList<Conversation> results = new ArrayList<>();
        for (final Conversation c : conversations) {
            if (c.getMode() != Conversation.MODE_MULTI) {
                continue;
            }
            final MucOptions mucOptions = c.getMucOptions();
            if (c.getJid().asBareJid().equals(contact.getJid().asBareJid())
                    || (mucOptions != null && mucOptions.isContactInRoom(contact))) {
                results.add(c);
            }
        }
        return results;
    }

    public Conversation find(final Contact contact) {
        for (final Conversation conversation : this.conversations) {
            if (conversation.getContact() == contact) {
                return conversation;
            }
        }
        return null;
    }

    private Conversation find(
            final Iterable<Conversation> haystack,
            final Account account,
            final Jid jid,
            final Jid counterpart) {
        if (jid == null) {
            return null;
        }

        if (counterpart != null) {
            for (final Conversation conversation : haystack) {
                if ((account == null || conversation.getAccount() == account)
                        && (conversation.getJid().asBareJid().equals(jid.asBareJid()))
                        && Objects.equal(conversation.getNextCounterpart(), counterpart)
                        && conversation.hasPermanentCounterpart()) {
                    return conversation;
                }
            }
        } else {
            for (final Conversation conversation : haystack) {
                if ((account == null || conversation.getAccount() == account)
                        && (conversation.getJid().asBareJid().equals(jid.asBareJid()))
                        && (conversation.getNextCounterpart() == null
                                || !conversation.hasPermanentCounterpart())) {
                    return conversation;
                }
            }
        }

        return null;
    }

    private List<Conversation> findAll(
            final Iterable<Conversation> haystack, final Account account, final Jid jid) {
        if (jid == null) {
            return null;
        }

        List<Conversation> res = new ArrayList<>();

        for (final Conversation conversation : haystack) {
            if ((account == null || conversation.getAccount() == account)
                    && (conversation.getJid().asBareJid().equals(jid.asBareJid()))) {
                res.add(conversation);
            }
        }

        return res;
    }

    public boolean isConversationsListEmpty(final Conversation ignore) {
        synchronized (this.conversations) {
            final int size = this.conversations.size();
            return size == 0 || size == 1 && this.conversations.get(0) == ignore;
        }
    }

    public boolean isConversationStillOpen(final Conversation conversation) {
        synchronized (this.conversations) {
            for (Conversation current : this.conversations) {
                if (current == conversation) {
                    return true;
                }
            }
        }
        return false;
    }

    private String mucExplicitLeavePreferenceKey(final Account account, final Jid jid) {
        return MUC_EXPLICIT_LEAVE_PREF_PREFIX + account.getUuid() + "|" + jid.asBareJid();
    }

    private String mucForgottenPreferenceKey(final Account account, final Jid jid) {
        return MUC_FORGOTTEN_PREF_PREFIX + account.getUuid() + "|" + jid.asBareJid();
    }

    public boolean isMucForgotten(final Account account, final Jid jid) {
        if (account == null || jid == null) {
            return false;
        }
        return getPreferences().getBoolean(mucForgottenPreferenceKey(account, jid), false);
    }

    public boolean isMucExplicitlyLeft(final Account account, final Jid jid) {
        if (account == null || jid == null) {
            return false;
        }
        return mucExplicitLeaveGuard.contains(account.getUuid(), jid)
                || getPreferences().getBoolean(mucExplicitLeavePreferenceKey(account, jid), false)
                || isMucForgotten(account, jid);
    }

    private void rememberMucExplicitLeave(final Account account, final Jid jid) {
        mucExplicitLeaveGuard.suppress(account.getUuid(), jid);
        if (!getPreferences()
                .edit()
                .putBoolean(mucExplicitLeavePreferenceKey(account, jid), true)
                .commit()) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": unable to persist explicit MUC leave marker for "
                            + jid.asBareJid());
        }
    }

    private void rememberMucForgotten(final Account account, final Jid jid) {
        rememberMucExplicitLeave(account, jid);
        if (!getPreferences()
                .edit()
                .putBoolean(mucForgottenPreferenceKey(account, jid), true)
                .commit()) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": unable to persist forgotten MUC marker for "
                            + jid.asBareJid());
        }
    }

    private void clearMucExplicitLeave(final Account account, final Jid jid) {
        mucExplicitLeaveGuard.allow(account.getUuid(), jid);
        if (!getPreferences()
                .edit()
                .remove(mucExplicitLeavePreferenceKey(account, jid))
                .remove(mucForgottenPreferenceKey(account, jid))
                .commit()) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": unable to clear explicit MUC leave markers for "
                            + jid.asBareJid());
        }
    }

    public boolean checkIsArchived(Account account, Jid jid, Jid counterpart) {
        if (counterpart == null && isMucExplicitlyLeft(account, jid)) {
            return true;
        }
        Conversation conversation = find(account, jid, counterpart);
        if (conversation != null) {
            return conversation.getStatus() == Conversation.STATUS_ARCHIVED;
        }
        conversation = databaseBackend.findConversation(account, jid, counterpart);

        return conversation != null && conversation.getStatus() == Conversation.STATUS_ARCHIVED;
    }

    public Conversation findOrCreateConversation(
            final Account account,
            final Jid jid,
            final MessageArchiveService.Query query,
            final boolean muc,
            final boolean joinAfterCreate,
            final boolean async,
            Jid counterpart) {
        if (muc && counterpart == null && joinAfterCreate) {
            // joinAfterCreate is reserved for an explicit user action (Join / Return to channel).
            // Only that intent may clear a durable explicit-leave marker.
            clearMucExplicitLeave(account, jid);
        }
        synchronized (this.conversations) {
            final var cached = find(account, jid, counterpart);
            if (cached != null) {
                if (muc
                        && counterpart == null
                        && joinAfterCreate
                        && !cached.getMucOptions().online()) {
                    joinMucExplicitly(cached);
                }
                return cached;
            }

            final var existing = databaseBackend.findConversation(account, jid, counterpart);
            final Conversation conversation;
            final boolean loadMessagesFromDb;
            if (existing != null) {
                conversation = existing;
                loadMessagesFromDb = restoreFromArchive(conversation, jid, muc);
            } else {
                String conversationName;
                final Contact contact = account.getRoster().getContact(jid);
                if (contact != null) {
                    conversationName = contact.getDisplayName();
                } else {
                    conversationName = jid.getLocal();
                }

                if (muc) {
                    conversation =
                            new Conversation(
                                    conversationName,
                                    account,
                                    jid,
                                    Conversation.MODE_MULTI,
                                    counterpart);
                } else {
                    conversation =
                            new Conversation(
                                    conversationName,
                                    account,
                                    jid.asBareJid(),
                                    Conversation.MODE_SINGLE,
                                    counterpart);
                }
                this.databaseBackend.createConversation(conversation);
                loadMessagesFromDb = false;
            }
            if (muc
                    && counterpart == null
                    && joinAfterCreate
                    && conversation.setMucExplicitlyLeft(false)) {
                updateConversation(conversation);
            }
            if (async) {
                mDatabaseReaderExecutor.execute(
                        () ->
                                postProcessConversation(
                                        conversation, loadMessagesFromDb, joinAfterCreate, query));
            } else {
                postProcessConversation(conversation, loadMessagesFromDb, joinAfterCreate, query);
            }
            this.conversations.add(conversation);

            if (counterpart != null) {
                Conversation parent = find(account, jid, null);
                if (parent != null) {
                    conversation.setParentConversation(parent);
                }
            }

            updateConversationUi();
            return conversation;
        }
    }

    public Conversation findConversationByUuidReliable(final String uuid) {
        final var cached = findConversationByUuid(uuid);
        if (cached != null) {
            return cached;
        }
        final var existing = databaseBackend.findConversation(uuid);
        if (existing == null) {
            return null;
        }
        Log.d(
                Config.LOGTAG,
                existing.getJid().asBareJid()
                        + ": restoring conversation with "
                        + existing.getJid()
                        + " from DB");
        final Map<String, Account> accounts =
                ImmutableMap.copyOf(Maps.uniqueIndex(this.accounts, Account::getUuid));
        existing.setAccount(accounts.get(existing.getAccountUuid()));
        final boolean keepArchived =
                existing.getMode() == Conversational.MODE_MULTI
                        && existing.getNextCounterpart() == null
                        && existing.isMucExplicitlyLeft()
                        && existing.getStatus() == Conversation.STATUS_ARCHIVED;
        final boolean loadMessagesFromDb =
                keepArchived
                        ? existing.messagesLoaded.compareAndSet(true, false)
                        : restoreFromArchive(existing);
        final boolean joinAfterRestore =
                existing.getMode() == Conversational.MODE_MULTI && !keepArchived;
        mDatabaseReaderExecutor.execute(
                () ->
                        postProcessConversation(
                                existing, loadMessagesFromDb, joinAfterRestore, null));
        this.conversations.add(existing);

        Jid counterpart = existing.getNextCounterpart();
        if (counterpart != null) {
            Conversation parent = find(existing.getAccount(), existing.getJid(), null);
            if (parent != null) {
                existing.setParentConversation(parent);
            }
        }

        updateConversationUi();
        return existing;
    }

    private boolean restoreFromArchive(
            final Conversation conversation, final Jid jid, final boolean muc) {
        if (muc) {
            conversation.setMode(Conversation.MODE_MULTI);
            conversation.setContactJid(jid);
        } else {
            conversation.setMode(Conversation.MODE_SINGLE);
            conversation.setContactJid(jid.asBareJid());
        }
        return restoreFromArchive(conversation);
    }

    private boolean restoreFromArchive(final Conversation conversation) {
        conversation.setStatus(Conversation.STATUS_AVAILABLE);
        databaseBackend.updateConversation(conversation);
        return conversation.messagesLoaded.compareAndSet(true, false);
    }

    private void postProcessConversation(
            final Conversation c,
            final boolean loadMessagesFromDb,
            final boolean joinAfterCreate,
            final MessageArchiveService.Query query) {
        final var singleMode = c.getMode() == Conversational.MODE_SINGLE;
        final var account = c.getAccount();
        if (loadMessagesFromDb) {
            List<Message> messages = databaseBackend.getMessages(c, Config.PAGE_SIZE);
            hydrateProtectedTextMessages(messages);
            restoreRepliesForMessages(c, messages);
            c.addAll(0, messages, false);
            updateConversationUi();
            c.messagesLoaded.set(true);
        }
        if (account.getXmppConnection() != null
                && !c.getContact().isBlocked()
                && account.getXmppConnection().getFeatures().mam()
                && singleMode) {
            if (query == null) {
                mMessageArchiveService.query(c);
            } else {
                if (query.getConversation() == null) {
                    mMessageArchiveService.query(c, query.getStart(), query.isCatchup());
                }
            }
        }
        if (joinAfterCreate) {
            joinMuc(c);
        }
    }

    public void archiveConversation(Conversation conversation) {
        if (conversation != null
                && conversation.getMode() == Conversation.MODE_MULTI
                && conversation.getNextCounterpart() == null) {
            // Persist explicit leave before the async DB writer is involved. This keeps reconnect,
            // MAM and invitations from reviving the room while preserving its local history.
            rememberMucExplicitLeave(conversation.getAccount(), conversation.getJid());
            if (conversation.setMucExplicitlyLeft(true)) {
                updateConversation(conversation);
            }
        }
        archiveConversation(conversation, true);
    }

    public void leaveMucAndForget(final Conversation conversation) {
        if (conversation == null
                || conversation.getMode() != Conversation.MODE_MULTI
                || conversation.getNextCounterpart() != null) {
            return;
        }
        final Account account = conversation.getAccount();
        rememberMucForgotten(account, conversation.getJid());

        final Bookmark bookmark = conversation.getBookmark();
        if (bookmark != null) {
            bookmark.setConversation(null);
            deleteBookmark(account, bookmark);
        }

        // First perform the protocol leave and remove the live conversation, then retire local
        // message/media content and delete the archived conversation row.
        destroyConversation(conversation);
    }

    public void destroyConversation(Conversation conversation) {
        archiveConversation(conversation);
        final Runnable runnable =
                () -> {
                    if (!retireSecureMessageContentForConversation(conversation)) {
                        return;
                    }
                    databaseBackend.deleteMessagesInConversation(conversation);
                    ScopedAccountSecretVaultV1.storeString(
                            getApplicationContext(),
                            conversation.getAccount().getUuid(),
                            "DRAFT_TEXT",
                            conversation.getUuid(),
                            null);
                    ScopedAccountSecretVaultV1.storeString(
                            getApplicationContext(),
                            conversation.getAccount().getUuid(),
                            "MUC_PASSWORD",
                            conversation.getUuid(),
                            null);

                    if (!databaseBackend.deleteConversation(
                            conversation.getAccount(),
                            conversation.getContactJid().asBareJid(),
                            conversation.getNextCounterpart())) {
                        Log.d(
                                Config.LOGTAG,
                                conversation.getJid().asBareJid()
                                        + ": unable to delete conversation");
                    }
                };
        mDatabaseWriterExecutor.execute(runnable);
    }

    private void archiveConversation(
            Conversation conversation, final boolean maySynchronizeWithBookmarks) {
        getNotificationService().clear(conversation);
        conversation.setStatus(Conversation.STATUS_ARCHIVED);
        persistSecureDraft(conversation, null);
        synchronized (this.conversations) {
            getMessageArchiveService().kill(conversation);
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                if (conversation.getNextCounterpart() == null) {
                    final Bookmark bookmark = conversation.getBookmark();
                    if (maySynchronizeWithBookmarks && bookmark != null) {
                        if (conversation.getMucOptions().getError() == MucOptions.Error.DESTROYED
                                && conversation.getAccount().getStatus() == Account.State.ONLINE) {
                            Account account = bookmark.getAccount();
                            bookmark.setConversation(null);
                            deleteBookmark(account, bookmark);
                        } else if (bookmark.autojoin()) {
                            bookmark.setAutojoin(false);
                            createBookmark(bookmark.getAccount(), bookmark);
                        }
                    }
                    leaveMuc(conversation);
                }
            } else {
                if (conversation
                        .getContact()
                        .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                    stopPresenceUpdatesTo(conversation.getContact());
                }
            }
            conversation.endOtrIfNeeded();
            updateConversation(conversation);
            this.conversations.remove(conversation);
            updateConversationUi();
        }
    }

    public void stopPresenceUpdatesTo(Contact contact) {
        Log.d(Config.LOGTAG, "Canceling presence request from " + contact.getJid().toString());
        sendPresencePacket(contact.getAccount(), mPresenceGenerator.stopPresenceUpdatesTo(contact));
        contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
    }

    public boolean createAccount(final Account account) {
        account.initAccountServices(this);

        // New authentication secrets must become durable in Account Secret Vault before the
        // account row exists. This removes the crash/WAL window where a freshly entered password
        // could briefly exist as durable plaintext in SQLite.
        if (!AccountSecretRuntimePersistenceV1.persistIfHydrated(
                getApplicationContext(), account)) {
            Log.w(Config.LOGTAG, "new account secret vault commit failed");
            return false;
        }

        databaseBackend.createAccount(account);

        final var secretOutcome =
                new AccountSecretMigrationCoordinatorV1(getApplicationContext(), databaseBackend)
                        .migrateNewAccount(account);
        if (!AccountSecretConnectionPolicyV1.permitsConnection(secretOutcome)) {
            Log.w(Config.LOGTAG, "new account secret migration not committed; blocking reconnect");
            return false;
        }

        if (CallIntegration.hasSystemFeature(this)) {
            CallIntegrationConnectionService.togglePhoneAccountAsync(this, account);
        }
        this.accounts.add(account);
        synchronizeSecureContentAccountRegistry();
        this.reconnectAccountInBackground(account);
        updateAccountUi();
        syncEnabledAccountSetting();
        toggleForegroundService();
        return true;
    }

    private void syncEnabledAccountSetting() {
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        getPreferences()
                .edit()
                .putBoolean(SystemEventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts)
                .apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);
    }

    private void toggleSetProfilePictureActivity(final boolean enabled) {
        try {
            final ComponentName name =
                    new ComponentName(this, ChooseAccountForProfilePictureActivity.class);
            final int targetState =
                    enabled
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager()
                    .setComponentEnabledSetting(name, targetState, PackageManager.DONT_KILL_APP);
        } catch (IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle profile picture activity");
        }
    }

    public boolean reconfigurePushDistributor() {
        return this.unifiedPushBroker.reconfigurePushDistributor();
    }

    private Optional<UnifiedPushBroker.Transport> renewUnifiedPushEndpoints(
            final UnifiedPushBroker.PushTargetMessenger pushTargetMessenger) {
        return this.unifiedPushBroker.renewUnifiedPushEndpoints(pushTargetMessenger);
    }

    public Optional<UnifiedPushBroker.Transport> renewUnifiedPushEndpoints() {
        return this.unifiedPushBroker.renewUnifiedPushEndpoints(null);
    }

    public UnifiedPushBroker getUnifiedPushBroker() {
        return this.unifiedPushBroker;
    }

    private void provisionAccount(final String address, final String password) {
        final Jid jid = Jid.of(address);
        final Account account = new Account(jid, password);
        account.setOption(Account.OPTION_DISABLED, true);
        Log.d(Config.LOGTAG, jid.asBareJid().toString() + ": provisioning account");
        createAccount(account);
    }

    public void createAccountFromKey(final String alias, final OnAccountCreated callback) {
        new Thread(
                        () -> {
                            try {
                                final X509Certificate[] chain =
                                        KeyChain.getCertificateChain(this, alias);
                                final X509Certificate cert =
                                        chain != null && chain.length > 0 ? chain[0] : null;
                                if (cert == null) {
                                    callback.informUser(R.string.unable_to_parse_certificate);
                                    return;
                                }
                                Pair<Jid, String> info = CryptoHelper.extractJidAndName(cert);
                                if (info == null) {
                                    callback.informUser(R.string.certificate_does_not_contain_jid);
                                    return;
                                }
                                if (findAccountByJid(info.first) == null) {
                                    final Account account = new Account(info.first, "");
                                    account.setPrivateKeyAlias(alias);
                                    account.setOption(Account.OPTION_DISABLED, true);
                                    account.setOption(Account.OPTION_FIXED_USERNAME, true);
                                    account.setDisplayName(info.second);
                                    createAccount(account);
                                    callback.onAccountCreated(account);
                                    if (Config.X509_VERIFICATION) {
                                        try {
                                            getMemorizingTrustManager()
                                                    .getNonInteractive(account.getServer())
                                                    .checkClientTrusted(chain, "RSA");
                                        } catch (CertificateException e) {
                                            callback.informUser(
                                                    R.string.certificate_chain_is_not_trusted);
                                        }
                                    }
                                } else {
                                    callback.informUser(R.string.account_already_exists);
                                }
                            } catch (Exception e) {
                                callback.informUser(R.string.unable_to_parse_certificate);
                            }
                        })
                .start();
    }

    public void updateKeyInAccount(final Account account, final String alias) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": update key in account " + alias);
        try {
            X509Certificate[] chain =
                    KeyChain.getCertificateChain(XmppConnectionService.this, alias);
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + " loaded certificate chain");
            Pair<Jid, String> info = CryptoHelper.extractJidAndName(chain[0]);
            if (info == null) {
                showErrorToastInUi(R.string.certificate_does_not_contain_jid);
                return;
            }
            if (account.getJid().asBareJid().equals(info.first)) {
                account.setPrivateKeyAlias(alias);
                account.setDisplayName(info.second);
                databaseBackend.updateAccount(account);
                if (Config.X509_VERIFICATION) {
                    try {
                        getMemorizingTrustManager()
                                .getNonInteractive()
                                .checkClientTrusted(chain, "RSA");
                    } catch (CertificateException e) {
                        showErrorToastInUi(R.string.certificate_chain_is_not_trusted);
                    }
                    account.getAxolotlService().regenerateKeys(true);
                }
            } else {
                showErrorToastInUi(R.string.jid_does_not_match_certificate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean updateAccount(final Account account) {
        if (databaseBackend.updateAccount(account)) {
            synchronizeSecureContentAccountRegistry();
            account.setShowErrorNotification(true);
            this.statusListener.onStatusChanged(account);
            databaseBackend.updateAccount(account);
            reconnectAccountInBackground(account);
            updateAccountUi();
            getNotificationService().updateErrorNotification();
            toggleForegroundService();
            syncEnabledAccountSetting();
            mChannelDiscoveryService.cleanCache();
            if (CallIntegration.hasSystemFeature(this)) {
                CallIntegrationConnectionService.togglePhoneAccountAsync(this, account);
            }
            return true;
        } else {
            return false;
        }
    }

    public void updateAccountPasswordOnServer(
            final Account account,
            final String newPassword,
            final OnAccountPasswordChanged callback) {
        final Iq iq = getIqGenerator().generateSetPassword(account, newPassword);
        sendIqPacket(
                account,
                iq,
                (packet) -> {
                    if (packet.getType() == Iq.Type.RESULT) {
                        account.setPassword(newPassword);
                        account.setOption(Account.OPTION_MAGIC_CREATE, false);
                        databaseBackend.updateAccount(account);
                        callback.onPasswordChangeSucceeded();
                    } else {
                        callback.onPasswordChangeFailed();
                    }
                });
    }

    public enum AccountRemovalResult {
        SUCCESS,
        FAILED
    }

    public interface OnAccountRemoved {
        void onComplete(AccountRemovalResult result);
    }

    public enum ServerAccountRemovalResult {
        SUCCESS,
        SERVER_FAILED,
        LOCAL_FAILED
    }

    public interface OnServerAccountRemoved {
        void onComplete(ServerAccountRemovalResult result);
    }

    /**
     * Established accounts may own protected text/media. Their removal must retire those objects
     * while the account is still registered with Secure Content.
     */
    private boolean requiresSecureContentRetirementForAccountRemoval(final Account account) {
        return (Config.SECURE_CONTENT_MEDIA_ROLLOUT || Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT)
                && account != null
                && account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY);
    }

    public void unregisterAccount(final Account account, final Consumer<Boolean> callback) {
        unregisterAccountForRemoval(
                account, result -> callback.accept(result == ServerAccountRemovalResult.SUCCESS));
    }

    public void unregisterAccountForRemoval(
            final Account account, final OnServerAccountRemoved callback) {
        final Iq iqPacket = new Iq(Iq.Type.SET);
        final Element query = iqPacket.addChild("query", Namespace.REGISTER);
        query.addChild("remove");
        sendIqPacket(
                account,
                iqPacket,
                (response) -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        callback.onComplete(ServerAccountRemovalResult.SERVER_FAILED);
                        return;
                    }
                    deleteAccount(
                            account,
                            result ->
                                    callback.onComplete(
                                            result == AccountRemovalResult.SUCCESS
                                                    ? ServerAccountRemovalResult.SUCCESS
                                                    : ServerAccountRemovalResult.LOCAL_FAILED));
                });
    }

    public void deleteAccount(final Account account) {
        deleteAccount(account, null);
    }

    /**
     * Removes one local account without exposing a false-success UI.
     *
     * <p>With Secure Content enabled, crypto-first local-data retirement runs while the account is
     * still registered and account-owned media writers are gated. The Account row and runtime
     * registry are removed only after that retirement succeeds.
     */
    public void deleteAccount(final Account account, @Nullable final OnAccountRemoved callback) {
        if (account == null) {
            completeAccountRemoval(callback, AccountRemovalResult.FAILED);
            return;
        }
        if (requiresSecureContentRetirementForAccountRemoval(account)) {
            deleteAccountWithSecureRetirement(account, callback);
        } else {
            deleteAccountAfterProtectedContentRetired(account, callback);
        }
    }

    private void deleteAccountWithSecureRetirement(
            final Account account, @Nullable final OnAccountRemoved callback) {
        final String accountUuid = account.getUuid();
        mDatabaseWriterExecutor.execute(
                () -> {
                    AccountRemovalResult result = AccountRemovalResult.FAILED;
                    beginSecureContentAccountCleanup(account);
                    try {
                        if (findAccountByUuid(accountUuid) != account) {
                            throw new IllegalStateException("Account is no longer available");
                        }
                        runWithSecureContentMutationsQuiesced(
                                () -> {
                                    clearLocalAccountDataUnderCleanupGate(account, false);
                                    if (!retireAndDeleteAccountPersistence(account)) {
                                        throw new IllegalStateException(
                                                "Unable to retire account persistence");
                                    }
                                    prepareAccountRuntimeForRemoval(account);
                                    releaseDeletedAccountRuntime(account);
                                });
                        result = AccountRemovalResult.SUCCESS;
                    } catch (final Exception exception) {
                        Log.e(Config.LOGTAG, "unable to remove account safely", exception);
                    } finally {
                        endSecureContentAccountCleanup(account);
                    }
                    completeAccountRemoval(callback, result);
                });
    }

    private void deleteAccountAfterProtectedContentRetired(
            final Account account, @Nullable final OnAccountRemoved callback) {
        mDatabaseWriterExecutor.execute(
                () -> {
                    AccountRemovalResult result = AccountRemovalResult.FAILED;
                    try {
                        if (findAccountByUuid(account.getUuid()) != account) {
                            throw new IllegalStateException("Account is no longer available");
                        }
                        if (!account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)) {
                            // An unfinished registration must stop owning a live connection before
                            // its protected account state is retired. Otherwise registration or
                            // post-connect callbacks can race the vault/database deletion.
                            account.setOption(Account.OPTION_DISABLED, true);
                            if (account.getXmppConnection() != null) {
                                disconnect(account, true);
                            }
                        }
                        if (!retireAndDeleteAccountPersistence(account)) {
                            throw new IllegalStateException("Unable to retire account persistence");
                        }
                        prepareAccountRuntimeForRemoval(account);
                        releaseDeletedAccountRuntime(account);
                        result = AccountRemovalResult.SUCCESS;
                    } catch (final Exception exception) {
                        Log.e(Config.LOGTAG, "unable to remove account", exception);
                    }
                    completeAccountRemoval(callback, result);
                });
    }

    private boolean retireAndDeleteAccountPersistence(final Account account) {
        for (final Bookmark bookmark : account.getBookmarks()) {
            if (!ScopedAccountSecretVaultV1.storeString(
                    getApplicationContext(),
                    account.getUuid(),
                    "BOOKMARK_PASSWORD",
                    bookmark.getJid().asBareJid().toString(),
                    null)) {
                Log.w(Config.LOGTAG, "unable to retire protected bookmark secret");
                return false;
            }
        }
        if (!databaseBackend.retireProtectedAccountScopedSecrets(account)) {
            Log.w(Config.LOGTAG, "unable to retire protected account scoped secrets");
            return false;
        }
        if (!AccountSecretRetirementV1.retireDeletedAccount(
                getApplicationContext(), account.getUuid())) {
            Log.w(Config.LOGTAG, "unable to retire account authentication secrets");
            return false;
        }
        if (!new PersistentSecureContentKeyMaterialStore(getApplicationContext())
                .retireAccountKeyMaterialForRemoval(account.getUuid())) {
            Log.w(Config.LOGTAG, "unable to retire account key material");
            return false;
        }
        if (!databaseBackend.deleteAccount(account)) {
            Log.w(Config.LOGTAG, "unable to delete account database row");
            return false;
        }
        return true;
    }

    private void prepareAccountRuntimeForRemoval(final Account account) {
        if (account.getStatus() != Account.State.ONLINE) {
            return;
        }
        account.getAxolotlService().deleteOmemoIdentity();
        for (final Conversation conversation : new ArrayList<>(conversations)) {
            if (conversation.getAccount() == account
                    && conversation.getMode() == Conversation.MODE_MULTI) {
                leaveMuc(conversation);
            }
        }
    }

    private void releaseDeletedAccountRuntime(final Account account) {
        final boolean connected = account.getStatus() == Account.State.ONLINE;
        synchronized (this.conversations) {
            for (final Conversation conversation : new ArrayList<>(conversations)) {
                if (conversation.getAccount() != account) {
                    continue;
                }
                conversations.remove(conversation);
                mNotificationService.clear(conversation);
            }
            if (account.getXmppConnection() != null) {
                new Thread(() -> disconnect(account, !connected)).start();
            }
            this.accounts.remove(account);
            synchronizeSecureContentAccountRegistry();
            if (CallIntegration.hasSystemFeature(this)) {
                CallIntegrationConnectionService.unregisterPhoneAccount(this, account);
            }
            this.mRosterSyncTaskManager.clear(account);
            updateAccountUi();
            updateConversationUi();
            updateUnreadCountBadge();
            mNotificationService.updateErrorNotification();
            syncEnabledAccountSetting();
            toggleForegroundService();
        }
    }

    private void completeAccountRemoval(
            @Nullable final OnAccountRemoved callback, final AccountRemovalResult result) {
        if (callback != null) {
            callback.onComplete(result);
        }
    }

    public void setOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnConversationUpdates.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as ConversationListChangedListener");
            }
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnConversationUpdates.remove(listener);
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnShowErrorToastListener(OnShowErrorToast listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnShowErrorToasts.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnShowErrorToastListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnShowErrorToastListener(OnShowErrorToast onShowErrorToast) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnShowErrorToasts.remove(onShowErrorToast);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnAccountUpdates.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnAccountListChangedtListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnAccountUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnCaptchaRequested.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnCaptchaRequestListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnCaptchaRequested.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnRosterUpdates.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnRosterUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnRosterUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnUpdateBlocklist.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnUpdateBlocklistListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnUpdateBlocklist.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnKeyStatusUpdatedListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnKeyStatusUpdated.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnKeyStatusUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnNewKeysAvailableListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnKeyStatusUpdated.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.onJingleRtpConnectionUpdate.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnJingleRtpConnectionUpdate");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.onJingleRtpConnectionUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnMucRosterUpdateListener(OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnMucRosterUpdate.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnMucRosterListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnMucRosterUpdateListener(final OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnMucRosterUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    private boolean isUiInForeground() {
        final android.app.Application application = getApplication();
        return application instanceof Conversations
                && ((Conversations) application).isUiInForeground();
    }

    private void onUiForegroundChanged(final boolean foreground) {
        Log.d(
                Config.LOGTAG,
                "UI lifecycle switched to " + (foreground ? "foreground" : "background"));
        for (final Account account : getAccounts()) {
            final XmppConnection connection = account.getXmppConnection();
            if (connection == null || account.getStatus() != Account.State.ONLINE) {
                continue;
            }
            applyDesiredClientStateIndication(connection);
            if (!foreground) {
                // Replace any shorter foreground alarm with the background cadence.
                final long backgroundPingInterval = getPingIntervalMillis(account, false);
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": background ping interval="
                                + (backgroundPingInterval / 1000L)
                                + "s");
                scheduleWakeUpCall(backgroundPingInterval, account.getUuid().hashCode());
            }
        }
    }

    private void applyDesiredClientStateIndication(final XmppConnection connection) {
        if (connection == null || !connection.getFeatures().csi()) {
            return;
        }
        if (isUiInForeground()) {
            connection.sendActive();
        } else {
            connection.sendInactive();
        }
    }

    public void restoreClientStateAfterStreamResume(final Account account) {
        if (account == null) {
            return;
        }
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            applyDesiredClientStateIndication(connection);
        }
    }

    private long getPingIntervalMillis(final Account account, final boolean isUiAction) {
        if (isUiAction) {
            return Config.PING_MIN_INTERVAL * 1000L;
        }
        final XmppConnection connection = account == null ? null : account.getXmppConnection();
        final boolean pushBackedBackground =
                !isUiInForeground()
                        && connection != null
                        && connection.getFeatures().csi()
                        && connection.getFeatures().sm()
                        && mPushManagementService.available(account);
        return (pushBackedBackground
                        ? Config.PUSH_BACKGROUND_PING_INTERVAL
                        : Config.PING_MAX_INTERVAL)
                * 1000L;
    }

    public boolean checkListeners() {
        return (this.mOnAccountUpdates.isEmpty()
                && this.mOnConversationUpdates.isEmpty()
                && this.mOnRosterUpdates.isEmpty()
                && this.mOnCaptchaRequested.isEmpty()
                && this.mOnMucRosterUpdate.isEmpty()
                && this.mOnUpdateBlocklist.isEmpty()
                && this.mOnShowErrorToasts.isEmpty()
                && this.onJingleRtpConnectionUpdate.isEmpty()
                && this.mOnKeyStatusUpdated.isEmpty());
    }

    private void switchToForeground() {
        toggleSoftDisabled(false);
        if (isSecureConnectivityBlocked()) {
            enforceSecureConnectivityBlocked();
            this.mNotificationService.setIsInForeground(true);
            Log.d(Config.LOGTAG, "foreground entered while secure connectivity is blocked");
            return;
        }
        final boolean broadcastLastActivity = broadcastLastActivity();
        for (Conversation conversation : getConversations()) {
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                conversation.getMucOptions().resetChatState();
            } else {
                conversation.setIncomingChatState(Config.DEFAULT_CHAT_STATE);
            }
        }
        for (Account account : getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE) {
                account.deactivateGracePeriod();
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (broadcastLastActivity) {
                        sendPresence(
                                account,
                                false); // send new presence but don't include idle because we are
                        // not
                    }
                }
            }
        }
        Log.d(Config.LOGTAG, "app switched into foreground");
    }

    private void switchToBackground() {
        final boolean broadcastLastActivity = broadcastLastActivity();
        if (broadcastLastActivity) {
            mLastActivity = System.currentTimeMillis();
            final SharedPreferences.Editor editor = getPreferences().edit();
            editor.putLong(SETTING_LAST_ACTIVITY_TS, mLastActivity);
            editor.apply();
        }
        for (Account account : getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE) {
                XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (broadcastLastActivity) {
                        sendPresence(account, true);
                    }
                }
            }
        }
        this.mNotificationService.setIsInForeground(false);
        Log.d(Config.LOGTAG, "app switched into background");
    }

    public void connectMultiModeConversations(Account account) {
        List<Conversation> conversations = getConversations();
        for (Conversation conversation : conversations) {
            if (conversation.getMode() == Conversation.MODE_MULTI
                    && conversation.getAccount() == account
                    && conversation.getStatus() == Conversation.STATUS_AVAILABLE
                    && !conversation.isMucExplicitlyLeft()) {
                joinMuc(conversation);
            }
        }
    }

    public void mucSelfPingAndRejoin(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (account.inProgressConferenceJoins.contains(conversation)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": canceling muc self ping because join is already under way");
                return;
            }
        }
        synchronized (account.inProgressConferencePings) {
            if (!account.inProgressConferencePings.add(conversation)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": canceling muc self ping because ping is already under way");
                return;
            }
        }
        final Jid self = conversation.getMucOptions().getSelf().getFullJid();
        final Iq ping = new Iq(Iq.Type.GET);
        ping.setTo(self);
        ping.addChild("ping", Namespace.PING);
        sendIqPacket(
                conversation.getAccount(),
                ping,
                (response) -> {
                    if (response.getType() == Iq.Type.ERROR) {
                        final var error = response.getError();
                        if (error == null
                                || error.hasChild("service-unavailable")
                                || error.hasChild("feature-not-implemented")
                                || error.hasChild("item-not-found")) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": ping to "
                                            + self
                                            + " came back as ignorable error");
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": ping to "
                                            + self
                                            + " failed. attempting rejoin");
                            joinMuc(conversation);
                        }
                    } else if (response.getType() == Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": ping to "
                                        + self
                                        + " came back fine");
                    }
                    synchronized (account.inProgressConferencePings) {
                        account.inProgressConferencePings.remove(conversation);
                    }
                });
    }

    public void joinMuc(Conversation conversation) {
        if (conversation != null
                && conversation.getNextCounterpart() == null
                && (conversation.isMucExplicitlyLeft()
                        || isMucExplicitlyLeft(conversation.getAccount(), conversation.getJid()))) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": suppressing automatic MUC join after explicit leave "
                            + conversation.getJid().asBareJid());
            return;
        }
        joinMuc(conversation, null, false);
    }

    public void joinMucExplicitly(final Conversation conversation) {
        if (conversation == null) {
            return;
        }
        clearMucExplicitLeave(conversation.getAccount(), conversation.getJid());
        boolean changed = conversation.setMucExplicitlyLeft(false);
        if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
            conversation.setStatus(Conversation.STATUS_AVAILABLE);
            changed = true;
        }
        if (changed) {
            updateConversation(conversation);
        }
        joinMuc(conversation, null, false);
    }

    public void joinMuc(Conversation conversation, boolean followedInvite) {
        if (conversation == null) {
            return;
        }
        // Receiving an invitation is not user intent. A room left explicitly may only be revived
        // by Join / Return to channel, which goes through joinMucExplicitly() or joinAfterCreate.
        if (conversation.getNextCounterpart() == null
                && (conversation.isMucExplicitlyLeft()
                        || isMucExplicitlyLeft(conversation.getAccount(), conversation.getJid()))) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": ignoring invite auto-rejoin for explicitly left MUC "
                            + conversation.getJid().asBareJid());
            return;
        }
        boolean changed = false;
        if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
            conversation.setStatus(Conversation.STATUS_AVAILABLE);
            changed = true;
        }
        if (changed) {
            updateConversation(conversation);
        }
        joinMuc(conversation, null, followedInvite);
    }

    private void joinMuc(Conversation conversation, final OnConferenceJoined onConferenceJoined) {
        joinMuc(conversation, onConferenceJoined, false);
    }

    private void joinMuc(
            Conversation conversation,
            final OnConferenceJoined onConferenceJoined,
            final boolean followedInvite) {
        if (conversation != null
                && conversation.getNextCounterpart() == null
                && (conversation.isMucExplicitlyLeft()
                        || isMucExplicitlyLeft(conversation.getAccount(), conversation.getJid()))) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": suppressing MUC join after explicit leave "
                            + conversation.getJid().asBareJid());
            return;
        }
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE) {
            synchronized (account.inProgressConferenceJoins) {
                account.inProgressConferenceJoins.add(conversation);
            }
            if (Config.MUC_LEAVE_BEFORE_JOIN) {
                sendPresencePacket(account, mPresenceGenerator.leave(conversation.getMucOptions()));
            }
            conversation.resetMucOptions();
            hydrateConversationSecrets(conversation);
            if (onConferenceJoined != null) {
                conversation.getMucOptions().flagNoAutoPushConfiguration();
            }
            conversation.setHasMessagesLeftOnServer(false);
            fetchConferenceConfiguration(
                    conversation,
                    new OnConferenceConfigurationFetched() {

                        private void join(Conversation conversation) {
                            Account account = conversation.getAccount();
                            final MucOptions mucOptions = conversation.getMucOptions();

                            if (mucOptions.nonanonymous()
                                    && !mucOptions.membersOnly()
                                    && !conversation.getBooleanAttribute(
                                            "accept_non_anonymous", false)) {
                                synchronized (account.inProgressConferenceJoins) {
                                    account.inProgressConferenceJoins.remove(conversation);
                                }
                                mucOptions.setError(MucOptions.Error.NON_ANONYMOUS);
                                updateConversationUi();
                                if (onConferenceJoined != null) {
                                    onConferenceJoined.onConferenceJoined(conversation);
                                }
                                return;
                            }

                            final Jid joinJid = mucOptions.getSelf().getFullJid();
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString()
                                            + ": joining conversation "
                                            + joinJid.toString());
                            final var packet =
                                    mPresenceGenerator.selfPresence(
                                            account,
                                            Presence.Status.ONLINE,
                                            mucOptions.nonanonymous()
                                                    || onConferenceJoined != null);
                            packet.setTo(joinJid);
                            Element x = packet.addChild("x", "http://jabber.org/protocol/muc");
                            if (conversation.getMucOptions().getPassword() != null) {
                                x.addChild("password").setContent(mucOptions.getPassword());
                            }

                            if (mucOptions.mamSupport()) {
                                // Use MAM instead of the limited muc history to get history
                                x.addChild("history").setAttribute("maxchars", "0");
                            } else {
                                // Fallback to muc history
                                x.addChild("history")
                                        .setAttribute(
                                                "since",
                                                PresenceGenerator.getTimestamp(
                                                        conversation
                                                                .getLastMessageTransmitted()
                                                                .getTimestamp()));
                            }
                            sendPresencePacket(account, packet);
                            if (onConferenceJoined != null) {
                                onConferenceJoined.onConferenceJoined(conversation);
                            }
                            if (!joinJid.equals(conversation.getJid())) {
                                conversation.setContactJid(joinJid);
                                databaseBackend.updateConversation(conversation);
                            }

                            if (mucOptions.mamSupport()) {
                                getMessageArchiveService().catchupMUC(conversation);
                            }
                            if (mucOptions.isPrivateAndNonAnonymous()) {
                                fetchConferenceMembers(conversation);

                                if (followedInvite) {
                                    final Bookmark bookmark = conversation.getBookmark();
                                    if (bookmark != null) {
                                        if (!bookmark.autojoin()) {
                                            bookmark.setAutojoin(true);
                                            createBookmark(account, bookmark);
                                        }
                                    } else {
                                        saveConversationAsBookmark(conversation, null);
                                    }
                                }
                            }
                            synchronized (account.inProgressConferenceJoins) {
                                account.inProgressConferenceJoins.remove(conversation);
                                sendUnsentMessages(conversation);
                            }
                        }

                        @Override
                        public void onConferenceConfigurationFetched(Conversation conversation) {
                            if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": conversation ("
                                                + conversation.getJid()
                                                + ") got archived before IQ result");
                                return;
                            }
                            join(conversation);
                        }

                        @Override
                        public void onFetchFailed(
                                final Conversation conversation, final String errorCondition) {
                            if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": conversation ("
                                                + conversation.getJid()
                                                + ") got archived before IQ result");
                                return;
                            }
                            if ("remote-server-not-found".equals(errorCondition)) {
                                synchronized (account.inProgressConferenceJoins) {
                                    account.inProgressConferenceJoins.remove(conversation);
                                }
                                conversation
                                        .getMucOptions()
                                        .setError(MucOptions.Error.SERVER_NOT_FOUND);
                                updateConversationUi();
                            } else {
                                join(conversation);
                                fetchConferenceConfiguration(conversation);
                            }
                        }
                    });
            updateConversationUi();
        } else {
            synchronized (account.pendingConferenceJoins) {
                account.pendingConferenceJoins.add(conversation);
            }
            conversation.resetMucOptions();
            conversation.setHasMessagesLeftOnServer(false);
            updateConversationUi();
        }
    }

    private void fetchConferenceMembers(final Conversation conversation) {
        final Account account = conversation.getAccount();
        final AxolotlService axolotlService = account.getAxolotlService();
        final String[] affiliations = {"member", "admin", "owner"};
        final Consumer<Iq> callback =
                new Consumer<Iq>() {

                    private int i = 0;
                    private boolean success = true;

                    @Override
                    public void accept(Iq response) {
                        final boolean omemoEnabled =
                                conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL;
                        final Element query = response.findChild("query", Namespace.MUC_ADMIN);
                        if (response.getType() == Iq.Type.RESULT && query != null) {
                            for (Element child : query.getChildren()) {
                                if ("item".equals(child.getName())) {
                                    MucOptions.User user =
                                            AbstractParser.parseItem(conversation, child);
                                    if (!user.realJidMatchesAccount()) {
                                        boolean isNew =
                                                conversation.getMucOptions().updateUser(user);
                                        Contact contact = user.getContact();
                                        if (omemoEnabled
                                                && isNew
                                                && user.getRealJid() != null
                                                && (contact == null
                                                        || !contact.mutualPresenceSubscription())
                                                && axolotlService.hasEmptyDeviceList(
                                                        user.getRealJid())) {
                                            axolotlService.fetchDeviceIds(user.getRealJid());
                                        }
                                    }
                                }
                            }
                        } else {
                            success = false;
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": could not request affiliation "
                                            + affiliations[i]
                                            + " in "
                                            + conversation.getJid().asBareJid());
                        }
                        ++i;
                        if (i >= affiliations.length) {
                            final var mucOptions = conversation.getMucOptions();
                            final var members = mucOptions.getMembers(true);
                            if (success) {
                                List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                                boolean changed = false;
                                for (ListIterator<Jid> iterator = cryptoTargets.listIterator();
                                        iterator.hasNext(); ) {
                                    Jid jid = iterator.next();
                                    if (!members.contains(jid)
                                            && !members.contains(jid.getDomain())) {
                                        iterator.remove();
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": removed "
                                                        + jid
                                                        + " from crypto targets of "
                                                        + conversation.getName());
                                        changed = true;
                                    }
                                }
                                if (changed) {
                                    conversation.setAcceptedCryptoTargets(cryptoTargets);
                                    updateConversation(conversation);
                                }
                            }
                            getAvatarService().clear(mucOptions);
                            updateMucRosterUi();
                            updateConversationUi();
                        }
                    }
                };
        for (String affiliation : affiliations) {
            sendIqPacket(
                    account, mIqGenerator.queryAffiliation(conversation, affiliation), callback);
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": fetching members for " + conversation.getName());
    }

    public boolean rememberMucPassword(final Conversation conversation, final String password) {
        if (conversation == null || conversation.getMode() != Conversation.MODE_MULTI) {
            return false;
        }
        final String accountUuid = conversation.getAccount().getUuid();
        if (!ScopedAccountSecretVaultV1.storeString(
                getApplicationContext(),
                accountUuid,
                "MUC_PASSWORD",
                conversation.getUuid(),
                password)) {
            return false;
        }
        conversation.getMucOptions().setPassword(password);
        if (conversation.clearLegacyMucPassword()) {
            updateConversation(conversation);
        }
        return true;
    }

    public void hydrateConversationSecrets(final Conversation conversation) {
        if (conversation == null || conversation.getAccount() == null) {
            return;
        }
        final String accountUuid = conversation.getAccount().getUuid();

        final String legacyMuc = conversation.getLegacyMucPassword();
        if (!Strings.isNullOrEmpty(legacyMuc)
                && ScopedAccountSecretVaultV1.storeString(
                        getApplicationContext(),
                        accountUuid,
                        "MUC_PASSWORD",
                        conversation.getUuid(),
                        legacyMuc)) {
            conversation.getMucOptions().setPassword(legacyMuc);
            if (conversation.clearLegacyMucPassword()) {
                updateConversation(conversation);
            }
        } else if (conversation.getMode() == Conversation.MODE_MULTI) {
            String password =
                    ScopedAccountSecretVaultV1.readString(
                            getApplicationContext(),
                            accountUuid,
                            "MUC_PASSWORD",
                            conversation.getUuid());
            final Bookmark bookmark = conversation.getBookmark();
            if (password == null && bookmark != null) {
                password =
                        ScopedAccountSecretVaultV1.readString(
                                getApplicationContext(),
                                accountUuid,
                                "BOOKMARK_PASSWORD",
                                bookmark.getJid().asBareJid().toString());
            }
            if (password == null && bookmark != null && bookmark.getPassword() != null) {
                password = bookmark.getPassword();
                ScopedAccountSecretVaultV1.storeString(
                        getApplicationContext(),
                        accountUuid,
                        "BOOKMARK_PASSWORD",
                        bookmark.getJid().asBareJid().toString(),
                        password);
                ScopedAccountSecretVaultV1.storeString(
                        getApplicationContext(),
                        accountUuid,
                        "MUC_PASSWORD",
                        conversation.getUuid(),
                        password);
            }
            if (password != null) {
                conversation.getMucOptions().setPassword(password);
            }
        }

        final String legacyDraft = conversation.getLegacyNextMessage();
        final long timestamp = conversation.getLongAttribute("next_message_timestamp", 0L);
        if (!Strings.isNullOrEmpty(legacyDraft)
                && ScopedAccountSecretVaultV1.storeString(
                        getApplicationContext(),
                        accountUuid,
                        "DRAFT_TEXT",
                        conversation.getUuid(),
                        legacyDraft)) {
            conversation.hydrateProtectedDraft(legacyDraft, timestamp);
            if (conversation.clearLegacyNextMessage()) {
                updateConversation(conversation);
            }
        } else if (legacyDraft == null) {
            final String protectedDraft =
                    ScopedAccountSecretVaultV1.readString(
                            getApplicationContext(),
                            accountUuid,
                            "DRAFT_TEXT",
                            conversation.getUuid());
            conversation.hydrateProtectedDraft(protectedDraft, timestamp);
        }
    }

    public boolean persistSecureDraft(final Conversation conversation, final String value) {
        if (conversation == null || conversation.getAccount() == null) {
            return false;
        }
        final String normalized = value == null || value.trim().isEmpty() ? null : value;
        if (!ScopedAccountSecretVaultV1.storeString(
                getApplicationContext(),
                conversation.getAccount().getUuid(),
                "DRAFT_TEXT",
                conversation.getUuid(),
                normalized)) {
            return false;
        }
        conversation.setNextMessage(normalized);
        conversation.clearLegacyNextMessage();
        updateConversation(conversation);
        return true;
    }

    public void providePasswordForMuc(final Conversation conversation, final String password) {
        if (conversation.getMode() == Conversation.MODE_MULTI
                && rememberMucPassword(conversation, password)) {
            if (conversation.getBookmark() != null) {
                final Bookmark bookmark = conversation.getBookmark();
                bookmark.setAutojoin(true);
                ScopedAccountSecretVaultV1.storeString(
                        getApplicationContext(),
                        conversation.getAccount().getUuid(),
                        "BOOKMARK_PASSWORD",
                        bookmark.getJid().asBareJid().toString(),
                        password);
                createBookmark(conversation.getAccount(), bookmark);
            }
            updateConversation(conversation);
            joinMuc(conversation);
        }
    }

    /**
     * Runs only on the database writer for gated bulk deletion. A failed retirement deliberately
     * leaves the Message DB rows intact so recovery retains their exact account/message relation.
     * Media retirement is required independently of the separate protected-text rollout.
     */
    private boolean retireSecureMessageContentForConversation(final Conversation conversation) {
        if (!SecureMessageMediaLifecyclePolicy.requiresRetirement(
                Config.SECURE_CONTENT_MEDIA_ROLLOUT, Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT)) {
            return true;
        }
        try {
            final String accountUuid = conversation.getAccount().getUuid();
            if (Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT) {
                final SecureMessagePayloadCoordinator coordinator =
                        getSecureMessagePayloadCoordinator();
                if (coordinator == null) {
                    Log.e(
                            Config.LOGTAG,
                            "secure message payload coordinator is unavailable during conversation"
                                    + " delete");
                    return false;
                }
                final List<String> messageUuids =
                        databaseBackend.getMessageUuidsForConversation(
                                accountUuid, conversation.getUuid());
                coordinator.retireAllForConversation(accountUuid, conversation.getUuid());
                final SecureMessageTextRepository repository = getSecureMessageTextRepository();
                if (repository != null) {
                    repository.invalidateConversation(accountUuid, messageUuids);
                }
                return true;
            }
            if (!(getApplication() instanceof Conversations)) {
                Log.e(
                        Config.LOGTAG,
                        "secure media store is unavailable during conversation delete");
                return false;
            }
            final Conversations application = (Conversations) getApplication();
            final SecureMessageRetirementBoundary boundary =
                    new SecureMessageRetirementBoundary(
                            null, application.getSecureContentStoreProvider().get());
            boundary.retireConversation(
                    accountUuid,
                    conversation.getUuid(),
                    databaseBackend.getMessageUuidsForConversation(
                            accountUuid, conversation.getUuid()));
            return true;
        } catch (final Exception exception) {
            Log.e(
                    Config.LOGTAG,
                    "unable to retire secure message content before conversation delete",
                    exception);
            return false;
        }
    }

    public boolean canModerateMessage(final Conversation conversation, final Message message) {
        return MucModerationPolicy.canModerate(conversation, message);
    }

    /** The IQ result only acknowledges the request; only the room event changes the timeline. */
    public void moderateMessage(
            final Conversation conversation,
            final Message message,
            final java.util.function.Consumer<Boolean> callback) {
        if (!canModerateMessage(conversation, message)) {
            callback.accept(false);
            return;
        }
        final Iq request =
                mIqGenerator.moderateMessage(conversation, message.getRoomStanzaId(), null);
        sendIqPacket(
                conversation.getAccount(),
                request,
                response -> {
                    final boolean accepted = MucModerationProtocol.requestAccepted(response);
                    if (!accepted) {
                        Log.w(Config.LOGTAG, "MUC moderation request rejected");
                    }
                    callback.accept(accepted);
                });
    }

    /** Called only after the parser has verified the bare-room service sender. */
    public void applyMessageModeration(
            final Conversation conversation,
            final String roomStanzaId,
            final String by,
            final String reason,
            final long stamp) {
        if (conversation == null
                || conversation.getMode() != Conversation.MODE_MULTI
                || roomStanzaId == null
                || roomStanzaId.isEmpty()) return;
        final Message resident = conversation.findMessageForModerationId(roomStanzaId);
        Message target = resident;
        if (target == null) {
            target = databaseBackend.getMessageWithRoomStanzaId(conversation, roomStanzaId);
        }
        if (target == null) {
            final Message legacy =
                    databaseBackend.getMessageWithServerMsgId(conversation, roomStanzaId);
            if (legacy != null && legacy.getRoomStanzaId() == null) {
                target = legacy;
            }
        }
        if (!databaseBackend.recordMessageModeration(
                conversation, roomStanzaId, by, reason, stamp)) {
            Log.w(Config.LOGTAG, "unable to persist MUC moderation marker");
            return;
        }
        if (target != null) {
            if (target.getRoomStanzaId() == null) {
                target.setRoomStanzaId(roomStanzaId);
            }
            retireModeratedMessage(conversation, target, resident != null, by, reason, stamp);
        }
    }

    /** An archived tombstone can replace an existing row by archive identity. */
    public void applyArchivedMessageModeration(
            final Conversation conversation,
            final Message target,
            final String roomStanzaId,
            final String by,
            final String reason,
            final long stamp) {
        if (conversation == null
                || target == null
                || conversation.getMode() != Conversation.MODE_MULTI) return;
        if (roomStanzaId != null
                && !roomStanzaId.isEmpty()
                && !databaseBackend.recordMessageModeration(
                        conversation, roomStanzaId, by, reason, stamp)) {
            Log.w(Config.LOGTAG, "unable to persist archived moderation marker");
            return;
        }
        retireModeratedMessage(
                conversation,
                target,
                conversation.findMessageWithRoomStanzaId(target.getRoomStanzaId()) == target,
                by,
                reason,
                stamp);
    }

    private void retireModeratedMessage(
            final Conversation conversation,
            final Message target,
            final boolean resident,
            final String by,
            final String reason,
            final long stamp) {
        final boolean changed = !target.isModerated();
        final String accountUuid = conversation.getAccount().getUuid();
        final boolean retired =
                MucModerationRetirement.retireNow(
                        accountUuid,
                        target.getUuid(),
                        this::retireModeratedContent,
                        this::invalidateModeratedMessageCache);
        if (!retired) {
            Log.w(Config.LOGTAG, "MUC moderation content retirement deferred");
        }
        target.markModerated(by, reason, stamp);
        target.setModerationRetired(retired);
        databaseBackend.updateMessage(target, true);
        if (retired) databaseBackend.markMessageModerationRetired(accountUuid, target.getUuid());
        conversation.refreshModeratedReplyReferences(target);
        final boolean removedFromTimeline =
                resident && conversation.removeModeratedMessageFromTimeline(target);
        mBitmapCache.evictAll();
        if (resident && (changed || removedFromTimeline)) {
            // The moderation tombstone stays durable, but Conversations-style presentation has no
            // standalone "deleted by moderator" bubble.
            if (changed && !removedFromTimeline) {
                conversation.markTimelineChanged();
            }
            updateConversationUi();
        }
        getNotificationService().updateNotification();
    }

    private void retireModeratedContent(final String accountUuid, final String messageUuid) {
        if (Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT) {
            final SecureMessagePayloadCoordinator coordinator =
                    getSecureMessagePayloadCoordinator();
            if (coordinator == null)
                throw new IllegalStateException("payload coordinator unavailable");
            coordinator.retire(accountUuid, messageUuid);
        } else if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            if (!(getApplication() instanceof Conversations)) {
                throw new IllegalStateException("secure media store unavailable");
            }
            final Conversations application = (Conversations) getApplication();
            new SecureMessageRetirementBoundary(
                            null, application.getSecureContentStoreProvider().get())
                    .retireMessage(accountUuid, messageUuid);
        }
    }

    private void recoverModeratedMessageRetirements() {
        if (databaseBackend == null) return;
        final List<String[]> pending = databaseBackend.getPendingModeratedMessageIds();
        final int recovered =
                MucModerationRetirement.recoverPending(
                        pending,
                        this::retireModeratedContent,
                        this::invalidateModeratedMessageCache,
                        databaseBackend::markMessageModerationRetired);
        if (recovered < pending.size()) {
            Log.w(Config.LOGTAG, "MUC moderation retirement recovery deferred");
        }
    }

    /**
     * Retry durable XEP-0424 retirement jobs after startup or
     * protected-content coordinator initialization.
     * A failure leaves SQLCipher state at RETIRE_PENDING.
     */
    /**
     * Apply only an already verified XEP-0424 event.
     * The SQLCipher transaction must succeed before resident
     * plaintext is retired or the message is removed from the UI.
     */
    public void applyVerifiedMucRetraction(
            final Conversation room, final String requestId) {
        if (room == null
                || room.getMode() != Conversation.MODE_MULTI
                || room.getAccount() == null
                || requestId == null
                || requestId.isBlank()) {
            return;
        }

        mDatabaseWriterExecutor.execute(() -> {
            final boolean[] applied = {false};

            try {
                runWithSecureContentMutationsQuiesced(() -> {
                    if (!databaseBackend
                            .beginVerifiedMucRetractionRetirement(
                                    room, requestId)) {
                        return;
                    }

                    applied[0] = true;

                    final String accountUuid =
                            room.getAccount().getUuid();
                    final String roomUuid = room.getUuid();

                    for (final DatabaseBackend.PendingRetractionRetirement job :
                            databaseBackend.getPendingMucRetractionRetirements()) {
                        if (!accountUuid.equals(job.accountUuid)
                                || !roomUuid.equals(job.conversationUuid)
                                || !requestId.equals(job.requestId)) {
                            continue;
                        }

                        final Message resident =
                                room.findResidentMessageWithUuid(
                                        job.messageUuid);

                        if (resident != null) {
                            resident.markRetracted();
                            room.refreshModeratedReplyReferences(resident);
                            room.removeRetractedMessageFromTimeline(resident);
                        }
                    }
                });
            } catch (final Exception | AssertionError e) {
                Log.w(
                        Config.LOGTAG,
                        "XEP-0424 verified retirement deferred",
                        e);
            }

            if (applied[0]) {
                mBitmapCache.evictAll();
                updateConversationUi();
                getNotificationService().updateNotification();
                recoverMucRetractionRetirements();
            }
        });
    }

    /**
     * Replay verified author retractions that were interrupted
     * before their SQLCipher RETIRE_PENDING transition.
     */
    private void recoverVerifiedMucRetractions() {
        if (databaseBackend == null) return;

        final List<DatabaseBackend.PendingRetractionRetirement> jobs =
                databaseBackend.getVerifiedMucRetractionsToResume();

        int scheduled = 0;

        for (final DatabaseBackend.PendingRetractionRetirement job : jobs) {
            if (job == null) continue;

            Conversation room = null;

            for (final Conversation candidate : conversations) {
                if (job.conversationUuid.equals(candidate.getUuid())
                        && candidate.getAccount() != null
                        && job.accountUuid.equals(
                                candidate.getAccount().getUuid())) {
                    room = candidate;
                    break;
                }
            }

            if (room == null) {
                final Conversation stored =
                        databaseBackend.findConversation(job.conversationUuid);

                if (stored != null
                        && job.accountUuid.equals(stored.getAccountUuid())) {
                    for (final Account account : accounts) {
                        if (job.accountUuid.equals(account.getUuid())) {
                            stored.setAccount(account);
                            room = stored;
                            break;
                        }
                    }
                }
            }

            if (room != null
                    && room.getMode() == Conversation.MODE_MULTI
                    && job.requestId != null
                    && !job.requestId.isBlank()) {
                applyVerifiedMucRetraction(room, job.requestId);
                scheduled++;
            }
        }

        if (scheduled < jobs.size()) {
            Log.w(
                    Config.LOGTAG,
                    "XEP-0424 VERIFIED recovery partially deferred");
        }

        // All scheduled jobs are queued on the same serial writer.
        // Rescan after they have had a chance to transition.
        if (jobs.size() == 128
                && scheduled == jobs.size()
                && !destroyed) {
            mDatabaseWriterExecutor.execute(
                    this::recoverVerifiedMucRetractions);
        }
    }

    private void recoverMucRetractionRetirements() {
        if (databaseBackend == null) return;

        final List<DatabaseBackend.PendingRetractionRetirement> pending =
                databaseBackend.getPendingMucRetractionRetirements();

        if (pending.isEmpty()) return;

        final int[] completed = {0};
        try {
            runWithSecureContentMutationsQuiesced(
                    () -> completed[0] =
                            MucRetractionRetirement.recoverPending(
                                    pending,
                                    this::retireModeratedContent,
                                    this::invalidateModeratedMessageCache,
                                    databaseBackend::completeMucRetractionRetirement));
        } catch (final Exception | AssertionError e) {
            Log.w(
                    Config.LOGTAG,
                    "XEP-0424 SCS retirement recovery deferred",
                    e);
            return;
        }

        if (completed[0] < pending.size()) {
            Log.w(
                    Config.LOGTAG,
                    "XEP-0424 content retirement recovery deferred");
            return;
        }

        // The DB returns at most 128 jobs. Drain further batches
        // without blocking this worker indefinitely.
        if (pending.size() == 128 && !destroyed) {
            mDatabaseWriterExecutor.execute(
                    this::recoverMucRetractionRetirements);
        }
    }

    private void invalidateModeratedMessageCache(
            final String accountUuid, final String messageUuid) {
        final SecureMessageTextRepository repository = getSecureMessageTextRepository();
        if (repository != null) {
            repository.invalidate(accountUuid, messageUuid);
        }
    }

    public void deleteMessageLocally(final Conversation conversation, final Message message) {
        deleteMessagesLocally(conversation, Collections.singletonList(message));
    }

    public void deleteMessagesLocally(
            final Conversation conversation, final Collection<Message> messages) {
        final List<Message> targets = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final Message message : messages) {
            if (message == null
                    || message.getConversation() != conversation
                    || !seen.add(message.getUuid())) {
                continue;
            }
            targets.add(message);
            releasedDeferredMediaCaptions.remove(message.getUuid());
        }
        if (targets.isEmpty()) {
            return;
        }

        if (!SecureMessageMediaLifecyclePolicy.requiresRetirement(
                Config.SECURE_CONTENT_MEDIA_ROLLOUT, Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT)) {
            for (final Message target : targets) {
                databaseBackend.deleteMessageInConversation(conversation, target);
            }
            return;
        }

        final String accountUuid = conversation.getAccount().getUuid();
        mDatabaseWriterExecutor.execute(
                () -> {
                    try {
                        if (Config.SECURE_MESSAGE_PAYLOAD_ROLLOUT) {
                            final SecureMessagePayloadCoordinator coordinator =
                                    getSecureMessagePayloadCoordinator();
                            if (coordinator == null) {
                                Log.e(
                                        Config.LOGTAG,
                                        "secure message payload coordinator is unavailable during"
                                                + " local delete");
                                return;
                            }
                            final SecureMessageTextRepository repository =
                                    getSecureMessageTextRepository();
                            for (final Message target : targets) {
                                coordinator.retire(accountUuid, target.getUuid());
                                if (repository != null) {
                                    repository.invalidate(accountUuid, target.getUuid());
                                }
                            }
                        } else {
                            if (!(getApplication() instanceof Conversations)) {
                                Log.e(
                                        Config.LOGTAG,
                                        "secure media store is unavailable during local delete");
                                return;
                            }
                            final Conversations application = (Conversations) getApplication();
                            final SecureMessageRetirementBoundary boundary =
                                    new SecureMessageRetirementBoundary(
                                            null,
                                            application.getSecureContentStoreProvider().get());
                            for (final Message target : targets) {
                                boundary.retireMessage(accountUuid, target.getUuid());
                            }
                        }

                        for (final Message target : targets) {
                            databaseBackend.deleteMessageInConversation(conversation, target);
                        }
                    } catch (final Exception exception) {
                        Log.e(
                                Config.LOGTAG,
                                "unable to retire secure message content before local delete",
                                exception);
                    }
                });
    }

    public void deleteAvatar(final Account account) {
        final AtomicBoolean executed = new AtomicBoolean(false);
        final Runnable onDeleted =
                () -> {
                    if (executed.compareAndSet(false, true)) {
                        account.setAvatar(null);
                        databaseBackend.updateAccount(account);
                        getAvatarService().clear(account);
                        updateAccountUi();
                    }
                };
        deleteVcardAvatar(account, onDeleted);
        deletePepNode(account, Namespace.AVATAR_DATA);
        deletePepNode(account, Namespace.AVATAR_METADATA, onDeleted);
    }

    public void deletePepNode(final Account account, final String node) {
        deletePepNode(account, node, null);
    }

    private void deletePepNode(final Account account, final String node, final Runnable runnable) {
        final Iq request = mIqGenerator.deleteNode(node);
        sendIqPacket(
                account,
                request,
                (packet) -> {
                    if (packet.getType() == Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": successfully deleted pep node "
                                        + node);
                        if (runnable != null) {
                            runnable.run();
                        }
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": failed to delete " + packet);
                    }
                });
    }

    private void deleteVcardAvatar(final Account account, @NonNull final Runnable runnable) {
        final Iq retrieveVcard = mIqGenerator.retrieveVcardAvatar(account.getJid().asBareJid());
        sendIqPacket(
                account,
                retrieveVcard,
                (response) -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": no vCard set. nothing to do");
                        return;
                    }
                    final Element vcard = response.findChild("vCard", "vcard-temp");
                    if (vcard == null) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": no vCard set. nothing to do");
                        return;
                    }
                    Element photo = vcard.findChild("PHOTO");
                    if (photo == null) {
                        photo = vcard.addChild("PHOTO");
                    }
                    photo.clearChildren();
                    final Iq publication = new Iq(Iq.Type.SET);
                    publication.setTo(account.getJid().asBareJid());
                    publication.addChild(vcard);
                    sendIqPacket(
                            account,
                            publication,
                            (publicationResponse) -> {
                                if (publicationResponse.getType() == Iq.Type.RESULT) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": successfully deleted vcard avatar");
                                    runnable.run();
                                } else {
                                    Log.d(
                                            Config.LOGTAG,
                                            "failed to publish vcard "
                                                    + publicationResponse.getErrorCondition());
                                }
                            });
                });
    }

    private boolean hasEnabledAccounts() {
        if (this.accounts == null) {
            return false;
        }
        for (final Account account : this.accounts) {
            if (account.isConnectionEnabled()) {
                return true;
            }
        }
        return false;
    }

    public void loadAttachmentMessage(
            final String conversationUuid,
            final String messageUuid,
            final Consumer<Message> callback) {
        mDatabaseReaderExecutor.execute(
                () -> {
                    final Conversation conversation =
                            findConversationByUuidReliable(conversationUuid);
                    if (conversation == null) {
                        callback.accept(null);
                        return;
                    }
                    Message message = conversation.findMessageWithFileAndUuid(messageUuid);
                    if (message == null) {
                        message = conversation.findMessageWithUuid(messageUuid);
                    }
                    if (message == null) {
                        final ArrayList<Message> loaded =
                                databaseBackend.getMessagesByLocalUuids(
                                        conversation, Collections.singleton(messageUuid));
                        message = loaded.isEmpty() ? null : loaded.get(0);
                    }
                    callback.accept(message);
                });
    }

    public void getAttachmentPage(
            final String accountUuid,
            final Jid jid,
            final AttachmentEntry.Category category,
            final AttachmentPage.Cursor cursor,
            final int limit,
            final OnAttachmentPageLoaded callback) {
        new Thread(
                        () -> {
                            final Conversations application = (Conversations) getApplication();
                            final AttachmentBrowserRepository repository =
                                    new AttachmentBrowserRepository(
                                            databaseBackend,
                                            fileBackend,
                                            application.getSecureContentStoreProvider().get());
                            callback.onAttachmentPageLoaded(
                                    repository.loadPage(
                                            accountUuid, jid.asBareJid(), category, cursor, limit));
                        },
                        "attachment-browser")
                .start();
    }

    public void getAttachments(
            final Conversation conversation, int limit, final OnMediaLoaded onMediaLoaded) {
        getAttachments(
                conversation.getAccount(), conversation.getJid().asBareJid(), limit, onMediaLoaded);
    }

    public void getAttachments(
            final Account account,
            final Jid jid,
            final int limit,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(account.getUuid(), jid.asBareJid(), limit, onMediaLoaded);
    }

    public void getAttachments(
            final String account,
            final Jid jid,
            final int limit,
            final OnMediaLoaded onMediaLoaded) {
        final int pageLimit = limit > 0 ? limit : 60;
        getAttachmentPage(
                account,
                jid.asBareJid(),
                AttachmentEntry.Category.MEDIA,
                null,
                pageLimit,
                page -> onMediaLoaded.onMediaLoaded(toMediaAttachments(page.entries)));
    }

    private List<Attachment> toMediaAttachments(final List<AttachmentEntry> entries) {
        final ArrayList<Attachment> result = new ArrayList<>(entries.size());
        for (final AttachmentEntry entry : entries) {
            final UUID uuid;
            try {
                uuid = UUID.fromString(entry.messageUuid);
            } catch (final IllegalArgumentException ignored) {
                continue;
            }
            if (entry.isSecure()) {
                result.add(Attachment.secure(uuid, entry.mimeType));
                continue;
            }
            if (entry.legacyPath == null) {
                continue;
            }
            final File file = fileBackend.getFileForPath(entry.legacyPath);
            if (file.isFile()) {
                result.add(Attachment.of(uuid, file, entry.mimeType));
            }
        }
        return result;
    }

    public void persistSelfNick(final MucOptions.User self, final boolean modified) {
        final Conversation conversation = self.getConversation();
        final Account account = conversation.getAccount();
        final Jid full = self.getFullJid();
        if (!full.equals(conversation.getJid())) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": persisting full jid " + full);
            conversation.setContactJid(full);
            databaseBackend.updateConversation(conversation);
        }

        final Bookmark bookmark = conversation.getBookmark();
        if (bookmark == null || !modified) {
            return;
        }
        final var nick = full.getResource();
        final String defaultNick = MucOptions.defaultNick(account);
        if (nick.equals(defaultNick) || nick.equals(bookmark.getNick())) {
            return;
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": persist nick '"
                        + full.getResource()
                        + "' into bookmark for "
                        + conversation.getJid().asBareJid());
        bookmark.setNick(nick);
        createBookmark(bookmark.getAccount(), bookmark);
    }

    public boolean renameInMuc(
            final Conversation conversation,
            final String nick,
            final UiCallback<Conversation> callback) {
        final Account account = conversation.getAccount();
        final Bookmark bookmark = conversation.getBookmark();
        final MucOptions options = conversation.getMucOptions();
        final Jid joinJid = options.createJoinJid(nick);
        if (joinJid == null) {
            return false;
        }
        if (options.online()) {
            options.setOnRenameListener(
                    new OnRenameListener() {

                        @Override
                        public void onSuccess() {
                            callback.success(conversation);
                        }

                        @Override
                        public void onFailure() {
                            callback.error(R.string.nick_in_use, conversation);
                        }
                    });

            final var packet =
                    mPresenceGenerator.selfPresence(
                            account, Presence.Status.ONLINE, options.nonanonymous());
            packet.setTo(joinJid);
            sendPresencePacket(account, packet);
            if (nick.equals(MucOptions.defaultNick(account))
                    && bookmark != null
                    && bookmark.getNick() != null) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": removing nick from bookmark for "
                                + bookmark.getJid());
                bookmark.setNick(null);
                createBookmark(account, bookmark);
            }
        } else {
            conversation.setContactJid(joinJid);
            databaseBackend.updateConversation(conversation);
            if (account.getStatus() == Account.State.ONLINE) {
                if (bookmark != null) {
                    bookmark.setNick(nick);
                    createBookmark(account, bookmark);
                }
                joinMuc(conversation);
            }
        }
        return true;
    }

    public void checkMucRequiresRename() {
        synchronized (this.conversations) {
            for (final Conversation conversation : this.conversations) {
                if (conversation.getMode() == Conversational.MODE_MULTI) {
                    checkMucRequiresRename(conversation);
                }
            }
        }
    }

    private void checkMucRequiresRename(final Conversation conversation) {
        final var options = conversation.getMucOptions();
        if (!options.online()) {
            return;
        }
        final var account = conversation.getAccount();
        final String current = options.getActualNick();
        final String proposed = options.getProposedNickPure();
        if (current == null || current.equals(proposed)) {
            return;
        }
        final Jid joinJid = options.createJoinJid(proposed);
        Log.d(
                Config.LOGTAG,
                String.format(
                        "%s: muc rename required %s (was: %s)",
                        account.getJid().asBareJid(), joinJid, current));
        final var packet =
                mPresenceGenerator.selfPresence(
                        account, Presence.Status.ONLINE, options.nonanonymous());
        packet.setTo(joinJid);
        sendPresencePacket(account, packet);
    }

    public void leaveMuc(Conversation conversation) {
        leaveMuc(conversation, false);
    }

    private void leaveMuc(Conversation conversation, boolean now) {
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE || now) {
            sendPresencePacket(
                    conversation.getAccount(),
                    mPresenceGenerator.leave(conversation.getMucOptions()));
            conversation.getMucOptions().setOffline();
            Bookmark bookmark = conversation.getBookmark();
            if (bookmark != null) {
                bookmark.setConversation(null);
            }
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": leaving muc "
                            + conversation.getJid());
        } else {
            synchronized (account.pendingConferenceLeaves) {
                account.pendingConferenceLeaves.add(conversation);
            }
        }
    }

    public String findConferenceServer(final Account account) {
        return findConferenceServer(account, false);
    }

    public String findConferenceServer(final Account account, boolean onlyMainAccount) {
        String server;
        if (account.getXmppConnection() != null) {
            server = account.getXmppConnection().getMucServer();
            if (server != null) {
                return server;
            }
        }

        if (!onlyMainAccount) {
            for (Account other : getAccounts()) {
                if (other != account && other.getXmppConnection() != null) {
                    server = other.getXmppConnection().getMucServer();
                    if (server != null) {
                        return server;
                    }
                }
            }
        }
        return null;
    }

    public void createPublicChannel(
            final Account account,
            final String name,
            final Jid address,
            final UiCallback<Conversation> callback) {
        joinMuc(
                findOrCreateConversation(account, address, null, true, false, true, null),
                conversation -> {
                    final Bundle configuration = IqGenerator.defaultChannelConfiguration();
                    if (!TextUtils.isEmpty(name)) {
                        configuration.putString("muc#roomconfig_roomname", name);
                    }
                    pushConferenceConfiguration(
                            conversation,
                            configuration,
                            new OnConfigurationPushed() {
                                @Override
                                public void onPushSucceeded() {
                                    saveConversationAsBookmark(conversation, name);
                                    callback.success(conversation);
                                }

                                @Override
                                public void onPushFailed() {
                                    if (conversation
                                            .getMucOptions()
                                            .getSelf()
                                            .getAffiliation()
                                            .ranks(MucOptions.Affiliation.OWNER)) {
                                        callback.error(
                                                R.string.unable_to_set_channel_configuration,
                                                conversation);
                                    } else {
                                        callback.error(
                                                R.string.joined_an_existing_channel, conversation);
                                    }
                                }
                            });
                });
    }

    public boolean createAdhocConference(
            final Account account,
            final String name,
            final Iterable<Jid> jids,
            final UiCallback<Conversation> callback) {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString()
                        + ": creating adhoc conference with "
                        + jids.toString());
        if (account.getStatus() == Account.State.ONLINE) {
            String server = findConferenceServer(account, true);
            final boolean serverFormOtherAccount;
            if (server == null) {
                server = findConferenceServer(account, false);
                serverFormOtherAccount = server != null;
            } else {
                serverFormOtherAccount = false;
            }

            try {
                if (server == null) {
                    if (callback != null) {
                        callback.error(R.string.no_conference_server_found, null);
                    }
                    return false;
                }
                final Jid jid = Jid.of(CryptoHelper.pronounceable(), server, null);
                final Conversation conversation =
                        findOrCreateConversation(account, jid, null, true, false, true, null);
                joinMuc(
                        conversation,
                        new OnConferenceJoined() {
                            @Override
                            public void onConferenceJoined(final Conversation conversation) {
                                final Bundle configuration =
                                        IqGenerator.defaultGroupChatConfiguration();
                                if (!TextUtils.isEmpty(name)) {
                                    configuration.putString("muc#roomconfig_roomname", name);
                                }
                                pushConferenceConfiguration(
                                        conversation,
                                        configuration,
                                        new OnConfigurationPushed() {
                                            @Override
                                            public void onPushSucceeded() {
                                                for (Jid invite : jids) {
                                                    invite(conversation, invite);
                                                }
                                                for (String resource :
                                                        account.getSelfContact()
                                                                .getPresences()
                                                                .toResourceArray()) {
                                                    Jid other =
                                                            account.getJid().withResource(resource);
                                                    Log.d(
                                                            Config.LOGTAG,
                                                            account.getJid().asBareJid()
                                                                    + ": sending direct invite to "
                                                                    + other);
                                                    directInvite(conversation, other);
                                                }
                                                saveConversationAsBookmark(conversation, name);
                                                if (callback != null) {
                                                    callback.success(conversation);
                                                }
                                            }

                                            @Override
                                            public void onPushFailed() {
                                                archiveConversation(conversation);
                                                if (callback != null) {
                                                    if (serverFormOtherAccount) {
                                                        callback.error(
                                                                R.string.no_conference_server_found,
                                                                conversation);
                                                    } else {
                                                        callback.error(
                                                                R.string.conference_creation_failed,
                                                                conversation);
                                                    }
                                                }
                                            }
                                        });
                            }
                        });
                return true;
            } catch (IllegalArgumentException e) {
                if (callback != null) {
                    if (serverFormOtherAccount) {
                        callback.error(R.string.no_conference_server_found, null);
                    } else {
                        callback.error(R.string.conference_creation_failed, null);
                    }
                }
                return false;
            }
        } else {
            if (callback != null) {
                callback.error(R.string.not_connected_try_again, null);
            }
            return false;
        }
    }

    public void fetchConferenceConfiguration(final Conversation conversation) {
        fetchConferenceConfiguration(conversation, null);
    }

    public void checkIfMuc(
            final Account account,
            final Jid jid,
            eu.siacs.conversations.utils.Consumer<Boolean> cb) {
        if (jid.isDomainJid()) {
            // Spec basically says MUC needs to have a node
            // And also specifies that MUC and MUC service should have the same identity...
            cb.accept(false);
            return;
        }

        Iq request = mIqGenerator.queryDiscoInfo(jid.asBareJid());
        sendIqPacket(
                account,
                request,
                (reply) -> {
                    ServiceDiscoveryResult result = new ServiceDiscoveryResult(reply);
                    cb.accept(
                            result.getFeatures().contains("http://jabber.org/protocol/muc")
                                    && result.hasIdentity("conference", null));
                });
    }

    public void fetchConferenceConfiguration(
            final Conversation conversation, final OnConferenceConfigurationFetched callback) {
        final Iq request = mIqGenerator.queryDiscoInfo(conversation.getJid().asBareJid());
        final var account = conversation.getAccount();
        sendIqPacket(
                account,
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final MucOptions mucOptions = conversation.getMucOptions();
                        final Bookmark bookmark = conversation.getBookmark();
                        final boolean sameBefore =
                                StringUtils.equals(
                                        bookmark == null ? null : bookmark.getBookmarkName(),
                                        mucOptions.getName());

                        if (mucOptions.updateConfiguration(new ServiceDiscoveryResult(response))) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": muc configuration changed for "
                                            + conversation.getJid().asBareJid());
                            updateConversation(conversation);
                        }

                        if (bookmark != null
                                && (sameBefore || bookmark.getBookmarkName() == null)) {
                            if (bookmark.setBookmarkName(
                                    StringUtils.nullOnEmpty(mucOptions.getName()))) {
                                createBookmark(account, bookmark);
                            }
                        }

                        if (callback != null) {
                            callback.onConferenceConfigurationFetched(conversation);
                        }

                        updateConversationUi();
                    } else if (response.getType() == Iq.Type.TIMEOUT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": received timeout waiting for conference configuration"
                                        + " fetch");
                    } else {
                        if (callback != null) {
                            callback.onFetchFailed(conversation, response.getErrorCondition());
                        }
                    }
                });
    }

    public void pushNodeConfiguration(
            Account account,
            final String node,
            final Bundle options,
            final OnConfigurationPushed callback) {
        pushNodeConfiguration(account, account.getJid().asBareJid(), node, options, callback);
    }

    public void pushNodeConfiguration(
            Account account,
            final Jid jid,
            final String node,
            final Bundle options,
            final OnConfigurationPushed callback) {
        Log.d(Config.LOGTAG, "pushing node configuration");
        sendIqPacket(
                account,
                mIqGenerator.requestPubsubConfiguration(jid, node),
                responseToRequest -> {
                    if (responseToRequest.getType() == Iq.Type.RESULT) {
                        Element pubsub =
                                responseToRequest.findChild(
                                        "pubsub", "http://jabber.org/protocol/pubsub#owner");
                        Element configuration =
                                pubsub == null ? null : pubsub.findChild("configure");
                        Element x =
                                configuration == null
                                        ? null
                                        : configuration.findChild("x", Namespace.DATA);
                        if (x != null) {
                            final Data data = Data.parse(x);
                            data.submit(options);
                            sendIqPacket(
                                    account,
                                    mIqGenerator.publishPubsubConfiguration(jid, node, data),
                                    responseToPublish -> {
                                        if (responseToPublish.getType() == Iq.Type.RESULT
                                                && callback != null) {
                                            Log.d(
                                                    Config.LOGTAG,
                                                    account.getJid().asBareJid()
                                                            + ": successfully changed node"
                                                            + " configuration for node "
                                                            + node);
                                            callback.onPushSucceeded();
                                        } else if (responseToPublish.getType() == Iq.Type.ERROR
                                                && callback != null) {
                                            callback.onPushFailed();
                                        }
                                    });
                        } else if (callback != null) {
                            callback.onPushFailed();
                        }
                    } else if (responseToRequest.getType() == Iq.Type.ERROR && callback != null) {
                        callback.onPushFailed();
                    }
                });
    }

    public void pushConferenceConfiguration(
            final Conversation conversation,
            final Bundle options,
            final OnConfigurationPushed callback) {
        if (options.getString("muc#roomconfig_whois", "moderators").equals("anyone")) {
            conversation.setAttribute("accept_non_anonymous", true);
            updateConversation(conversation);
        }
        if (options.containsKey("muc#roomconfig_moderatedroom")) {
            final boolean moderated = "1".equals(options.getString("muc#roomconfig_moderatedroom"));
            options.putString("members_by_default", moderated ? "0" : "1");
        }
        if (options.containsKey("muc#roomconfig_allowpm")) {
            // ejabberd :-/
            final boolean allow = "anyone".equals(options.getString("muc#roomconfig_allowpm"));
            options.putString("allow_private_messages", allow ? "1" : "0");
            options.putString("allow_private_messages_from_visitors", allow ? "anyone" : "nobody");
        }
        final var account = conversation.getAccount();
        final Iq request = new Iq(Iq.Type.GET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner");
        sendIqPacket(
                account,
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Data data =
                                Data.parse(response.query().findChild("x", Namespace.DATA));
                        data.submit(options);
                        final Iq set = new Iq(Iq.Type.SET);
                        set.setTo(conversation.getJid().asBareJid());
                        set.query("http://jabber.org/protocol/muc#owner").addChild(data);
                        sendIqPacket(
                                account,
                                set,
                                packet -> {
                                    if (callback != null) {
                                        if (packet.getType() == Iq.Type.RESULT) {
                                            callback.onPushSucceeded();
                                        } else {
                                            Log.d(Config.LOGTAG, "failed: " + packet.toString());
                                            callback.onPushFailed();
                                        }
                                    }
                                });
                    } else {
                        if (callback != null) {
                            callback.onPushFailed();
                        }
                    }
                });
    }

    public void pushSubjectToConference(final Conversation conference, final String subject) {
        final var packet =
                this.getMessageGenerator()
                        .conferenceSubject(conference, StringUtils.nullOnEmpty(subject));
        this.sendMessagePacket(conference.getAccount(), packet);
    }

    public boolean requestVoiceInConference(final Conversation conference) {
        if (conference == null || conference.getMode() != Conversational.MODE_MULTI) {
            return false;
        }
        final Account account = conference.getAccount();
        final MucOptions mucOptions = conference.getMucOptions();
        if (account.getStatus() != Account.State.ONLINE
                || !mucOptions.online()
                || !mucOptions.moderated()
                || mucOptions.participating()
                || mucOptions.isVoiceRequestPending()
                || mucOptions.getSelf().getRole() != MucOptions.Role.VISITOR) {
            return false;
        }
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return false;
        }
        final var packet = this.getMessageGenerator().requestVoice(conference);
        mucOptions.setVoiceRequestPending(true);
        this.sendMessagePacket(account, packet);
        updateConversationUi();
        updateMucRosterUi();
        return true;
    }

    public void refreshMucOwnerAdminAffiliations(final Conversation conference) {
        if (conference == null || conference.getMode() != Conversation.MODE_MULTI) {
            return;
        }
        final MucOptions mucOptions = conference.getMucOptions();
        if (mucOptions.getSelf().getAffiliation() != MucOptions.Affiliation.OWNER) {
            return;
        }
        mucOptions.beginOwnerAdminAffiliationRefresh();
        queryMucAffiliationList(conference, MucOptions.Affiliation.OWNER);
        queryMucAffiliationList(conference, MucOptions.Affiliation.ADMIN);
    }

    private void queryMucAffiliationList(
            final Conversation conference, final MucOptions.Affiliation affiliation) {
        final Iq request = mIqGenerator.queryAffiliation(conference, affiliation.toString());
        sendIqPacket(
                conference.getAccount(),
                request,
                response -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                "unable to query MUC " + affiliation + " affiliation list");
                        return;
                    }
                    final Element query = response.findChild("query", Namespace.MUC_ADMIN);
                    if (query == null) {
                        Log.w(Config.LOGTAG, "MUC affiliation result missing muc#admin query");
                        return;
                    }
                    final List<MucOptions.User> snapshot = new ArrayList<>();
                    for (final Element child : query.getChildren()) {
                        if (!"item".equals(child.getName())) {
                            continue;
                        }
                        final MucOptions.User user = AbstractParser.parseItem(conference, child);
                        if (user.getRealJid() != null && user.getAffiliation() == affiliation) {
                            snapshot.add(user);
                        }
                    }
                    conference.getMucOptions().applyAffiliationListSnapshot(affiliation, snapshot);
                    getAvatarService().clear(conference.getMucOptions());
                    updateMucRosterUi();
                });
    }

    public void changeAffiliationInConference(
            final Conversation conference,
            Jid user,
            final MucOptions.Affiliation affiliation,
            final OnAffiliationChanged callback) {
        final Jid jid = user.asBareJid();
        final Iq request =
                this.mIqGenerator.changeAffiliation(conference, jid, affiliation.toString());
        sendIqPacket(
                conference.getAccount(),
                request,
                (response) -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final var mucOptions = conference.getMucOptions();
                        mucOptions.changeAffiliation(jid, affiliation);
                        getAvatarService().clear(mucOptions);
                        if (mucOptions.getSelf().getAffiliation() == MucOptions.Affiliation.OWNER) {
                            refreshMucOwnerAdminAffiliations(conference);
                        }
                        if (callback != null) {
                            callback.onAffiliationChangedSuccessful(jid);
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    "changed affiliation of " + user + " to " + affiliation);
                        }
                    } else if (callback != null) {
                        callback.onAffiliationChangeFailed(
                                jid, R.string.could_not_change_affiliation);
                    } else {
                        Log.d(Config.LOGTAG, "unable to change affiliation");
                    }
                });
    }

    public void changeRoleInConference(
            final Conversation conference, final String nick, MucOptions.Role role) {
        final var account = conference.getAccount();
        final Iq request = this.mIqGenerator.changeRole(conference, nick, role.toString());
        sendIqPacket(
                account,
                request,
                (packet) -> {
                    if (packet.getType() != Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + " unable to change role of " + nick);
                    }
                });
    }

    public void destroyRoom(final Conversation conversation, final OnRoomDestroy callback) {
        final Iq request = new Iq(Iq.Type.SET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner").addChild("destroy");
        sendIqPacket(
                conversation.getAccount(),
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        if (callback != null) {
                            callback.onRoomDestroySucceeded();
                        }
                    } else if (response.getType() == Iq.Type.ERROR) {
                        if (callback != null) {
                            callback.onRoomDestroyFailed();
                        }
                    }
                });
    }

    private boolean isSecureConnectivityBlocked() {
        return InactiveDevicePrivacyRuntimeV1.evaluateForService(getApplicationContext())
                || SecureContentCryptoSessionRuntimeV1.requiresAuthentication(
                        getApplicationContext());
    }

    private void enforceSecureConnectivityBlocked() {
        // Stop network activity before retiring in-memory authentication/private crypto state.
        // OMEMO/OTR callbacks can still be running while an XMPP connection is alive; clearing
        // their keys/sessions first creates a race where those callbacks observe half-retired
        // runtime state. The secure boundary is therefore: disconnect first, then retire the AMK,
        // then wipe remaining transient state.
        for (final Account account : getAccounts()) {
            enforceSecureConnectivityBlocked(account);
        }
        // Search hydration deliberately owns short-lived Message instances outside the resident
        // conversation graph. Stop it before invalidating the crypto epoch so no result callback
        // can republish presentation plaintext while High Security is retiring process state.
        MessageSearchTask.cancelRunningTasks();
        SecureContentCryptoSessionRuntimeV1.suspendForPrivacy(getApplicationContext());

        // High Security suspension retires not only key material but also every process-owned
        // plaintext presentation accelerator. SecureMessageTextRepository keeps strong String
        // references in its LRUs, and decoded secure-media previews live in the bitmap cache.
        final SecureMessageTextRepository textRepository = secureMessageTextRepository;
        if (textRepository != null) {
            textRepository.clearTransientPlaintext();
        }
        if (mBitmapCache != null) {
            mBitmapCache.evictAll();
        }

        for (final Account account : accounts) {
            account.clearTransientAuthenticationSecrets();
            account.clearTransientCryptoSecrets();
        }
        for (final Conversation conversation : getConversations()) {
            conversation.clearTransientProtectedSecrets();
        }

        // Force currently attached conversation/search surfaces to drop already-bound TextViews,
        // Drawables and reply/composer presentation after their backing plaintext has been retired.
        updateConversationPresentationUi();
    }

    private void enforceSecureConnectivityBlocked(final Account account) {
        synchronized (account) {
            final XmppConnection connection = account.getXmppConnection();
            if (connection != null) {
                disconnect(account, true);
            }
            account.getRoster().clearPresences();
            if (account.getStatus() != Account.State.DISABLED
                    && account.getStatus() != Account.State.LOGGED_OUT
                    && account.getStatus() != Account.State.OFFLINE) {
                account.setStatus(Account.State.OFFLINE);
                statusListener.onStatusChanged(account);
            }
        }
    }

    private void disconnect(final Account account, boolean force) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        if (!force) {
            final List<Conversation> conversations = getConversations();
            for (Conversation conversation : conversations) {
                if (conversation.getAccount() == account) {
                    if (conversation.getMode() == Conversation.MODE_MULTI) {
                        leaveMuc(conversation, true);
                    } else {
                        if (conversation.endOtrIfNeeded()) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": ended otr session with "
                                            + conversation.getJid());
                        }
                    }
                }
            }
            sendOfflinePresence(account);
        }
        connection.disconnect(force);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void updateMessage(Message message) {
        updateMessage(message, true);
    }

    private static void markTimelineChanged(final Message message) {
        if (message != null && message.getConversation() instanceof Conversation) {
            ((Conversation) message.getConversation()).markTimelineChanged();
        }
    }

    public void updateMessage(Message message, boolean includeBody) {
        databaseBackend.updateMessage(message, includeBody);
        markTimelineChanged(message);
        updateConversationUi();
    }

    public void createMessageAsync(final Message message) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.createMessage(message));
    }

    public void updateMessage(Message message, String uuid) {
        if (!databaseBackend.updateMessage(message, uuid)) {
            Log.e(Config.LOGTAG, "error updated message in DB after edit");
        } else {
            markTimelineChanged(message);
        }
        updateConversationUi();
    }

    public void syncDirtyContacts(Account account) {
        for (Contact contact : account.getRoster().getContacts()) {
            if (contact.getOption(Contact.Options.DIRTY_PUSH)) {
                pushContactToServer(contact);
            }
            if (contact.getOption(Contact.Options.DIRTY_DELETE)) {
                deleteContactOnServer(contact);
            }
        }
    }

    public void createContact(final Contact contact, final boolean autoGrant) {
        createContact(contact, autoGrant, null);
    }

    public void createContact(
            final Contact contact, final boolean autoGrant, final String preAuth) {
        if (autoGrant) {
            contact.setOption(Contact.Options.PREEMPTIVE_GRANT);
            contact.setOption(Contact.Options.ASKING);
        }
        pushContactToServer(contact, preAuth);
    }

    public void onOtrSessionEstablished(Conversation conversation) {
        final Account account = conversation.getAccount();
        final Session otrSession = conversation.getOtrSession();
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + " otr session established with "
                        + conversation.getJid()
                        + "/"
                        + otrSession.getSessionID().getUserID());
        conversation.findUnsentMessagesWithEncryption(
                Message.ENCRYPTION_OTR,
                new Conversation.OnMessageFound() {

                    @Override
                    public void onMessageFound(Message message) {
                        SessionID id = otrSession.getSessionID();
                        try {
                            message.setCounterpart(
                                    Jid.of(id.getAccountID() + "/" + id.getUserID()));
                        } catch (IllegalArgumentException e) {
                            return;
                        }
                        if (message.needsUploading()) {
                            mJingleConnectionManager.startJingleFileTransfer(message);
                        } else {
                            im.conversations.android.xmpp.model.stanza.Message outPacket =
                                    mMessageGenerator.generateOtrChat(message);
                            if (outPacket != null) {
                                mMessageGenerator.addDelay(outPacket, message.getTimeSent());
                                message.setStatus(Message.STATUS_SEND);
                                databaseBackend.updateMessage(message, false);
                                markTimelineChanged(message);
                                sendMessagePacket(account, outPacket);
                            }
                        }
                        updateConversationUi();
                    }
                });
    }

    public void pushContactToServer(final Contact contact) {
        pushContactToServer(contact, null);
    }

    private void pushContactToServer(final Contact contact, final String preAuth) {
        contact.resetOption(Contact.Options.DIRTY_DELETE);
        contact.setOption(Contact.Options.DIRTY_PUSH);
        final Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final boolean ask = contact.getOption(Contact.Options.ASKING);
            final boolean sendUpdates =
                    contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
                            && contact.getOption(Contact.Options.PREEMPTIVE_GRANT);
            final Iq iq = new Iq(Iq.Type.SET);
            iq.query(Namespace.ROSTER).addChild(contact.asElement());
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
            if (sendUpdates) {
                sendPresencePacket(account, mPresenceGenerator.sendPresenceUpdatesTo(contact));
            }
            if (ask) {
                sendPresencePacket(
                        account, mPresenceGenerator.requestPresenceUpdatesFrom(contact, preAuth));
            }
        } else {
            syncRoster(contact.getAccount());
        }
    }

    public void publishMucAvatar(
            final Conversation conversation, final Uri image, final OnAvatarPublication callback) {
        new Thread(
                        () -> {
                            final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
                            final int size = Config.AVATAR_SIZE;
                            final Avatar avatar =
                                    getFileBackend().getPepAvatar(image, size, format);
                            if (avatar != null) {
                                if (!getFileBackend().save(conversation.getAccount(), avatar)) {
                                    callback.onAvatarPublicationFailed(
                                            R.string.error_saving_avatar);
                                    return;
                                }
                                avatar.owner = conversation.getJid().asBareJid();
                                publishMucAvatar(conversation, avatar, callback);
                            } else {
                                callback.onAvatarPublicationFailed(
                                        R.string.error_publish_avatar_converting);
                            }
                        })
                .start();
    }

    public void publishAvatarAsync(
            final Account account,
            final Uri image,
            final boolean open,
            final OnAvatarPublication callback) {
        new Thread(() -> publishAvatar(account, image, open, callback)).start();
    }

    private void publishAvatar(
            final Account account,
            final Uri image,
            final boolean open,
            final OnAvatarPublication callback) {
        final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
        final int size = Config.AVATAR_SIZE;
        final Avatar avatar = getFileBackend().getPepAvatar(image, size, format);
        if (avatar != null) {
            if (!getFileBackend().save(account, avatar)) {
                Log.d(Config.LOGTAG, "unable to save vcard");
                callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                return;
            }
            publishAvatar(account, avatar, open, callback);
        } else {
            callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
        }
    }

    private void publishMucAvatar(
            Conversation conversation, Avatar avatar, OnAvatarPublication callback) {
        final var account = conversation.getAccount();
        final Iq retrieve = mIqGenerator.retrieveVcardAvatar(avatar);
        sendIqPacket(
                account,
                retrieve,
                (response) -> {
                    boolean itemNotFound =
                            response.getType() == Iq.Type.ERROR
                                    && response.hasChild("error")
                                    && response.findChild("error").hasChild("item-not-found");
                    if (response.getType() == Iq.Type.RESULT || itemNotFound) {
                        Element vcard = response.findChild("vCard", "vcard-temp");
                        if (vcard == null) {
                            vcard = new Element("vCard", "vcard-temp");
                        }
                        Element photo = vcard.findChild("PHOTO");
                        if (photo == null) {
                            photo = vcard.addChild("PHOTO");
                        }
                        photo.clearChildren();
                        photo.addChild("TYPE").setContent(avatar.type);
                        photo.addChild("BINVAL").setContent(avatar.image);
                        final Iq publication = new Iq(Iq.Type.SET);
                        publication.setTo(conversation.getJid().asBareJid());
                        publication.addChild(vcard);
                        sendIqPacket(
                                account,
                                publication,
                                (publicationResponse) -> {
                                    if (publicationResponse.getType() == Iq.Type.RESULT) {
                                        callback.onAvatarPublicationSucceeded();
                                    } else {
                                        Log.d(
                                                Config.LOGTAG,
                                                "failed to publish vcard "
                                                        + publicationResponse.getErrorCondition());
                                        callback.onAvatarPublicationFailed(
                                                R.string.error_publish_avatar_server_reject);
                                    }
                                });
                    } else {
                        Log.d(Config.LOGTAG, "failed to request vcard " + response);
                        callback.onAvatarPublicationFailed(
                                R.string.error_publish_avatar_no_server_support);
                    }
                });
    }

    public void publishAvatar(
            final Account account,
            final Avatar avatar,
            final boolean open,
            final OnAvatarPublication callback) {
        final Bundle options;
        if (account.getXmppConnection().getFeatures().pepPublishOptions()) {
            options = open ? PublishOptions.openAccess() : PublishOptions.presenceAccess();
        } else {
            options = null;
        }
        publishAvatar(account, avatar, options, true, callback);
    }

    public void publishAvatar(
            Account account,
            final Avatar avatar,
            final Bundle options,
            final boolean retry,
            final OnAvatarPublication callback) {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": publishing avatar. options=" + options);
        final Iq packet = this.mIqGenerator.publishAvatar(avatar, options);
        this.sendIqPacket(
                account,
                packet,
                result -> {
                    if (result.getType() == Iq.Type.RESULT) {
                        publishAvatarMetadata(account, avatar, options, true, callback);
                    } else if (retry && PublishOptions.preconditionNotMet(result)) {
                        pushNodeConfiguration(
                                account,
                                Namespace.AVATAR_DATA,
                                options,
                                new OnConfigurationPushed() {
                                    @Override
                                    public void onPushSucceeded() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": changed node configuration for avatar"
                                                        + " node");
                                        publishAvatar(account, avatar, options, false, callback);
                                    }

                                    @Override
                                    public void onPushFailed() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": unable to change node configuration"
                                                        + " for avatar node");
                                        publishAvatar(account, avatar, null, false, callback);
                                    }
                                });
                    } else {
                        Element error = result.findChild("error");
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": server rejected avatar "
                                        + (avatar.size / 1024)
                                        + "KiB "
                                        + (error != null ? error.toString() : ""));
                        if (callback != null) {
                            callback.onAvatarPublicationFailed(
                                    R.string.error_publish_avatar_server_reject);
                        }
                    }
                });
    }

    public void publishAvatarMetadata(
            Account account,
            final Avatar avatar,
            final Bundle options,
            final boolean retry,
            final OnAvatarPublication callback) {
        final Iq packet =
                XmppConnectionService.this.mIqGenerator.publishAvatarMetadata(avatar, options);
        sendIqPacket(
                account,
                packet,
                result -> {
                    if (result.getType() == Iq.Type.RESULT) {
                        if (account.setAvatar(avatar.getFilename())) {
                            getAvatarService().clear(account);
                            databaseBackend.updateAccount(account);
                            notifyAccountAvatarHasChanged(account);
                        }
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": published avatar "
                                        + (avatar.size / 1024)
                                        + "KiB");
                        if (callback != null) {
                            callback.onAvatarPublicationSucceeded();
                        }
                    } else if (retry && PublishOptions.preconditionNotMet(result)) {
                        pushNodeConfiguration(
                                account,
                                Namespace.AVATAR_METADATA,
                                options,
                                new OnConfigurationPushed() {
                                    @Override
                                    public void onPushSucceeded() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": changed node configuration for avatar"
                                                        + " meta data node");
                                        publishAvatarMetadata(
                                                account, avatar, options, false, callback);
                                    }

                                    @Override
                                    public void onPushFailed() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": unable to change node configuration"
                                                        + " for avatar meta data node");
                                        publishAvatarMetadata(
                                                account, avatar, null, false, callback);
                                    }
                                });
                    } else {
                        if (callback != null) {
                            callback.onAvatarPublicationFailed(
                                    R.string.error_publish_avatar_server_reject);
                        }
                    }
                });
    }

    public void republishAvatarIfNeeded(final Account account) {
        if (account.getAxolotlService().isPepBroken()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": skipping republication of avatar because pep is broken");
            return;
        }
        final Iq packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(
                account,
                packet,
                new Consumer<Iq>() {

                    private Avatar parseAvatar(Iq packet) {
                        Element pubsub =
                                packet.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                        if (pubsub != null) {
                            Element items = pubsub.findChild("items");
                            if (items != null) {
                                return Avatar.parseMetadata(items);
                            }
                        }
                        return null;
                    }

                    private boolean errorIsItemNotFound(Iq packet) {
                        Element error = packet.findChild("error");
                        return packet.getType() == Iq.Type.ERROR
                                && error != null
                                && error.hasChild("item-not-found");
                    }

                    @Override
                    public void accept(final Iq packet) {
                        if (packet.getType() == Iq.Type.RESULT || errorIsItemNotFound(packet)) {
                            final Avatar serverAvatar = parseAvatar(packet);
                            if (serverAvatar == null && account.getAvatar() != null) {
                                final Avatar avatar =
                                        fileBackend.getStoredPepAvatar(
                                                account, account.getAvatar());
                                if (avatar != null) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": avatar on server was null. republishing");
                                    // publishing as 'open' - old server (that requires
                                    // republication) likely doesn't support access models anyway
                                    publishAvatar(
                                            account,
                                            fileBackend.getStoredPepAvatar(
                                                    account, account.getAvatar()),
                                            true,
                                            null);
                                } else {
                                    Log.e(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": error rereading avatar");
                                }
                            }
                        }
                    }
                });
    }

    public void cancelAvatarFetches(final Account account) {
        synchronized (mInProgressAvatarFetches) {
            for (final Iterator<String> iterator = mInProgressAvatarFetches.iterator();
                    iterator.hasNext(); ) {
                final String KEY = iterator.next();
                if (KEY.startsWith(account.getJid().asBareJid() + "_")) {
                    iterator.remove();
                }
            }
        }
    }

    public void fetchAvatar(Account account, Avatar avatar) {
        fetchAvatar(account, avatar, null);
    }

    public void fetchAvatar(
            Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final String KEY = generateFetchKey(account, avatar);
        synchronized (this.mInProgressAvatarFetches) {
            if (mInProgressAvatarFetches.add(KEY)) {
                switch (avatar.origin) {
                    case PEP:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarPep(account, avatar, callback);
                        break;
                    case VCARD:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarVcard(account, avatar, callback);
                        break;
                }
            } else if (avatar.origin == Avatar.Origin.PEP) {
                mOmittedPepAvatarFetches.add(KEY);
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": already fetching "
                                + avatar.origin
                                + " avatar for "
                                + avatar.owner);
            }
        }
    }

    private void fetchAvatarPep(
            final Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrievePepAvatar(avatar);
        sendIqPacket(
                account,
                packet,
                (result) -> {
                    synchronized (mInProgressAvatarFetches) {
                        mInProgressAvatarFetches.remove(generateFetchKey(account, avatar));
                    }
                    final String ERROR =
                            account.getJid().asBareJid()
                                    + ": fetching avatar for "
                                    + avatar.owner
                                    + " failed ";
                    if (result.getType() == Iq.Type.RESULT) {
                        avatar.image = IqParser.avatarData(result);
                        if (avatar.image != null) {
                            if (getFileBackend().save(account, avatar)) {
                                if (account.getJid().asBareJid().equals(avatar.owner)) {
                                    if (account.setAvatar(avatar.getFilename())) {
                                        databaseBackend.updateAccount(account);
                                    }
                                    getAvatarService().clear(account);
                                    updateConversationUi();
                                    updateAccountUi();
                                } else {
                                    final Contact contact =
                                            account.getRoster().getContact(avatar.owner);
                                    contact.setAvatar(avatar);
                                    syncRoster(account);
                                    getAvatarService().clear(contact);
                                    updateConversationUi();
                                    updateRosterUi();
                                }
                                if (callback != null) {
                                    callback.success(avatar);
                                }
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": successfully fetched pep avatar for "
                                                + avatar.owner);
                                return;
                            }
                        } else {

                            Log.d(Config.LOGTAG, ERROR + "(parsing error)");
                        }
                    } else {
                        Element error = result.findChild("error");
                        if (error == null) {
                            Log.d(Config.LOGTAG, ERROR + "(server error)");
                        } else {
                            Log.d(Config.LOGTAG, ERROR + error.toString());
                        }
                    }
                    if (callback != null) {
                        callback.error(0, null);
                    }
                });
    }

    private void fetchAvatarVcard(
            final Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrieveVcardAvatar(avatar);
        this.sendIqPacket(
                account,
                packet,
                response -> {
                    final boolean previouslyOmittedPepFetch;
                    synchronized (mInProgressAvatarFetches) {
                        final String KEY = generateFetchKey(account, avatar);
                        mInProgressAvatarFetches.remove(KEY);
                        previouslyOmittedPepFetch = mOmittedPepAvatarFetches.remove(KEY);
                    }
                    if (response.getType() == Iq.Type.RESULT) {
                        Element vCard = response.findChild("vCard", "vcard-temp");
                        Element photo = vCard != null ? vCard.findChild("PHOTO") : null;
                        String image = photo != null ? photo.findChildContent("BINVAL") : null;
                        if (image != null) {
                            avatar.image = image;
                            if (getFileBackend().save(account, avatar)) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": successfully fetched vCard avatar for "
                                                + avatar.owner
                                                + " omittedPep="
                                                + previouslyOmittedPepFetch);
                                if (avatar.owner.isBareJid()) {
                                    if (account.getJid().asBareJid().equals(avatar.owner)
                                            && account.getAvatar() == null) {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": had no avatar. replacing with vcard");
                                        account.setAvatar(avatar.getFilename());
                                        databaseBackend.updateAccount(account);
                                        getAvatarService().clear(account);
                                        updateAccountUi();
                                    } else {
                                        final Contact contact =
                                                account.getRoster().getContact(avatar.owner);
                                        contact.setAvatar(avatar, previouslyOmittedPepFetch);
                                        syncRoster(account);
                                        getAvatarService().clear(contact);
                                        updateRosterUi();
                                    }
                                    updateConversationUi();
                                } else {
                                    Conversation conversation =
                                            find(account, avatar.owner.asBareJid(), null);
                                    if (conversation != null
                                            && conversation.getMode() == Conversation.MODE_MULTI) {
                                        MucOptions.User user =
                                                conversation
                                                        .getMucOptions()
                                                        .findUserByFullJid(avatar.owner);
                                        if (user != null) {
                                            if (user.setAvatar(avatar)) {
                                                getAvatarService().clear(user);
                                                updateConversationUi();
                                                updateMucRosterUi();
                                            }
                                            if (user.getRealJid() != null) {
                                                Contact contact =
                                                        account.getRoster()
                                                                .getContact(user.getRealJid());
                                                contact.setAvatar(avatar);
                                                syncRoster(account);
                                                getAvatarService().clear(contact);
                                                updateRosterUi();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
    }

    public void checkForAvatar(final Account account, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(
                account,
                packet,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        Element pubsub =
                                response.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                        if (pubsub != null) {
                            Element items = pubsub.findChild("items");
                            if (items != null) {
                                Avatar avatar = Avatar.parseMetadata(items);
                                if (avatar != null) {
                                    avatar.owner = account.getJid().asBareJid();
                                    if (fileBackend.isAvatarCached(account, avatar)) {
                                        if (account.setAvatar(avatar.getFilename())) {
                                            databaseBackend.updateAccount(account);
                                        }
                                        getAvatarService().clear(account);
                                        callback.success(avatar);
                                    } else {
                                        fetchAvatarPep(account, avatar, callback);
                                    }
                                    return;
                                }
                            }
                        }
                    }
                    callback.error(0, null);
                });
    }

    public void notifyAccountAvatarHasChanged(final Account account) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null && connection.getFeatures().bookmarksConversion()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": avatar changed. resending presence to online group chats");
            for (Conversation conversation : conversations) {
                if (conversation.getAccount() == account
                        && conversation.getMode() == Conversational.MODE_MULTI) {
                    final MucOptions mucOptions = conversation.getMucOptions();
                    if (mucOptions.online()) {
                        final var packet =
                                mPresenceGenerator.selfPresence(
                                        account, Presence.Status.ONLINE, mucOptions.nonanonymous());
                        packet.setTo(mucOptions.getSelf().getFullJid());
                        connection.sendPresencePacket(packet);
                    }
                }
            }
        }
    }

    public void deleteContactOnServer(Contact contact) {
        contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
        contact.resetOption(Contact.Options.DIRTY_PUSH);
        contact.setOption(Contact.Options.DIRTY_DELETE);
        Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final Iq iq = new Iq(Iq.Type.SET);
            Element item = iq.query(Namespace.ROSTER).addChild("item");
            item.setAttribute("jid", contact.getJid());
            item.setAttribute("subscription", "remove");
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
        }
    }

    public void updateConversation(final Conversation conversation) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.updateConversation(conversation));
    }

    private void reconnectAccount(
            final Account account, final boolean force, final boolean interactive) {
        if (isSecureConnectivityBlocked()) {
            enforceSecureConnectivityBlocked(account);
            return;
        }
        if (!account.isSecretVaultHydrated()) {
            final var secretOutcome =
                    new AccountSecretMigrationCoordinatorV1(
                                    getApplicationContext(), databaseBackend)
                            .migrateAndHydrate(account);
            if (!AccountSecretConnectionPolicyV1.permitsConnection(secretOutcome)) {
                Log.w(
                        Config.LOGTAG,
                        "protected account secrets unavailable; blocking direct reconnect");
                enforceSecureConnectivityBlocked(account);
                return;
            }
        }
        synchronized (account) {
            final XmppConnection existingConnection = account.getXmppConnection();
            final XmppConnection connection;
            if (existingConnection != null) {
                connection = existingConnection;
            } else if (account.isConnectionEnabled()) {
                connection = createConnection(account);
                account.setXmppConnection(connection);
            } else {
                return;
            }
            final boolean hasInternet = hasInternetConnection();
            if (account.isConnectionEnabled() && hasInternet) {
                if (existingConnection != null) {
                    disconnect(account, force);
                }
                connection.setInteractive(interactive);
                connection.prepareNewConnection();
                final Thread thread = connection.prepareConnectionThread();
                thread.start();
                scheduleWakeUpCall(Config.CONNECT_DISCO_TIMEOUT, account.getUuid().hashCode());
            } else {
                disconnect(account, force || account.getTrueStatus().isError() || !hasInternet);
                account.getRoster().clearPresences();
                connection.resetEverything();
                final AxolotlService axolotlService = account.getAxolotlService();
                if (axolotlService != null) {
                    axolotlService.resetBrokenness();
                }
                if (!hasInternet) {
                    account.setStatus(Account.State.NO_INTERNET);
                }
            }
        }
    }

    public void reconnectAccountInBackground(final Account account) {
        new Thread(() -> reconnectAccount(account, false, true)).start();
    }

    public void invite(final Conversation conversation, final Jid contact) {
        Log.d(
                Config.LOGTAG,
                conversation.getAccount().getJid().asBareJid()
                        + ": inviting "
                        + contact
                        + " to "
                        + conversation.getJid().asBareJid());
        final MucOptions.User user =
                conversation.getMucOptions().findUserByRealJid(contact.asBareJid());
        if (user == null || user.getAffiliation() == MucOptions.Affiliation.OUTCAST) {
            changeAffiliationInConference(conversation, contact, MucOptions.Affiliation.NONE, null);
        }
        final var packet = mMessageGenerator.invite(conversation, contact);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void directInvite(Conversation conversation, Jid jid) {
        final var packet = mMessageGenerator.directInvite(conversation, jid);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void resetSendingToWaiting(Account account) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getAccount() == account) {
                conversation.findUnsentTextMessages(
                        message -> markMessage(message, Message.STATUS_WAITING));
            }
        }
    }

    public Message markMessage(
            final Account account, final Jid recipient, final String uuid, final int status) {
        return markMessage(account, recipient, uuid, status, null);
    }

    public Message markMessage(
            final Account account,
            final Jid recipient,
            final String uuid,
            final int status,
            String errorMessage) {
        if (uuid == null) {
            return null;
        }
        for (Conversation conversation : getConversations()) {
            if (conversation.getJid().asBareJid().equals(recipient)
                    && conversation.getAccount() == account) {
                final Message message = conversation.findSentMessageWithUuidOrRemoteId(uuid);
                if (message != null) {
                    markMessage(message, status, errorMessage);
                    return message;
                }
            }
        }
        return null;
    }

    public boolean markMessage(
            final Conversation conversation,
            final String uuid,
            final int status,
            final String serverMessageId) {
        return markMessage(conversation, uuid, status, serverMessageId, null);
    }

    public boolean markMessage(
            final Conversation conversation,
            final String uuid,
            final int status,
            final String serverMessageId,
            final LocalizedContent body) {
        if (uuid == null) {
            return false;
        } else {
            final Message message = conversation.findSentMessageWithUuid(uuid);

            if (message != null) {
                if (message.getServerMsgId() == null) {
                    message.setServerMsgId(serverMessageId);
                }
                if (message.getEncryption() == Message.ENCRYPTION_NONE
                        && message.isTypeText()
                        && isBodyModified(message, body)) {
                    message.setBody(body.content);
                    if (body.count > 1) {
                        message.setBodyLanguage(body.language);
                    }
                    markMessage(message, status, null, true);
                } else {
                    markMessage(message, status);
                }
                return true;
            } else {
                return false;
            }
        }
    }

    private static boolean isBodyModified(final Message message, final LocalizedContent body) {
        if (body == null || body.content == null) {
            return false;
        }
        return !body.content.equals(message.getBody());
    }

    public void markMessage(Message message, int status) {
        markMessage(message, status, null);
    }

    public void markMessage(final Message message, final int status, final String errorMessage) {
        markMessage(message, status, errorMessage, false);
    }

    public void markMessage(
            final Message message,
            final int status,
            final String errorMessage,
            final boolean includeBody) {
        final int oldStatus = message.getStatus();
        if (status == Message.STATUS_SEND_FAILED
                && (oldStatus == Message.STATUS_SEND_RECEIVED
                        || oldStatus == Message.STATUS_SEND_DISPLAYED)) {
            return;
        }
        if (status == Message.STATUS_SEND_RECEIVED && oldStatus == Message.STATUS_SEND_DISPLAYED) {
            return;
        }
        message.setErrorMessage(errorMessage);
        message.setStatus(status);
        if (status != Message.STATUS_WAITING) {
            releasedDeferredMediaCaptions.remove(message.getUuid());
        }
        databaseBackend.updateMessage(message, includeBody);
        markTimelineChanged(message);
        updateConversationUi();
        if (oldStatus != status && status == Message.STATUS_SEND_FAILED) {
            mNotificationService.pushFailedDelivery(message);
        }
    }

    public SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    public long getAutomaticMessageDeletionDate() {
        final long timeout =
                getLongPreference(
                        AppSettings.AUTOMATIC_MESSAGE_DELETION,
                        R.integer.automatic_message_deletion);
        return timeout == 0 ? timeout : (System.currentTimeMillis() - (timeout * 1000));
    }

    public long getLongPreference(String name, @IntegerRes int res) {
        long defaultValue = getResources().getInteger(res);
        try {
            return Long.parseLong(getPreferences().getString(name, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBooleanPreference(String name, @BoolRes int res) {
        return getPreferences().getBoolean(name, getResources().getBoolean(res));
    }

    public boolean confirmMessages() {
        return getBooleanPreference("confirm_messages", R.bool.confirm_messages);
    }

    public boolean allowMessageCorrection() {
        return getBooleanPreference("allow_message_correction", R.bool.allow_message_correction);
    }

    public boolean sendChatStates() {
        return getBooleanPreference("chat_states", R.bool.chat_states);
    }

    public boolean useTorToConnect() {
        return QuickConversationsService.isConversations()
                && getBooleanPreference("use_tor", R.bool.use_tor);
    }

    public boolean showExtendedConnectionOptions() {
        return QuickConversationsService.isConversations()
                && getBooleanPreference(
                        AppSettings.SHOW_CONNECTION_OPTIONS, R.bool.show_connection_options);
    }

    public boolean broadcastLastActivity() {
        return getBooleanPreference(AppSettings.BROADCAST_LAST_ACTIVITY, R.bool.last_activity);
    }

    public int unreadCount() {
        int count = 0;
        for (Conversation conversation : getConversations()) {
            count += conversation.unreadCount();
        }
        return count;
    }

    private <T> List<T> threadSafeList(Set<T> set) {
        synchronized (LISTENER_LOCK) {
            return set.isEmpty() ? Collections.emptyList() : new ArrayList<>(set);
        }
    }

    public void showErrorToastInUi(int resId) {
        for (OnShowErrorToast listener : threadSafeList(this.mOnShowErrorToasts)) {
            listener.onShowErrorToast(resId);
        }
    }

    public long getConversationPresentationRevision() {
        return conversationPresentationRevision.get();
    }

    public void updateConversationPresentationUi() {
        conversationPresentationRevision.incrementAndGet();
        updateConversationUi(false);
    }

    public void markConversationPresentationChanged() {
        conversationPresentationRevision.incrementAndGet();
    }

    public void updateConversationUi() {
        updateConversationUi(false);
    }

    public void updateConversationUi(boolean newCaps) {
        for (OnConversationUpdate listener : threadSafeList(this.mOnConversationUpdates)) {
            listener.onConversationUpdate(newCaps);
        }
    }

    public void notifyJingleRtpConnectionUpdate(
            final Account account,
            final Jid with,
            final String sessionId,
            final RtpEndUserState state) {
        for (OnJingleRtpConnectionUpdate listener :
                threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onJingleRtpConnectionUpdate(account, with, sessionId, state);
        }
    }

    public void notifyJingleRtpConnectionUpdate(
            CallIntegration.AudioDevice selectedAudioDevice,
            Set<CallIntegration.AudioDevice> availableAudioDevices) {
        for (OnJingleRtpConnectionUpdate listener :
                threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
        }
    }

    public void updateAccountUi() {
        for (final OnAccountUpdate listener : threadSafeList(this.mOnAccountUpdates)) {
            listener.onAccountUpdate();
        }
    }

    public void updateRosterUi() {
        for (OnRosterUpdate listener : threadSafeList(this.mOnRosterUpdates)) {
            listener.onRosterUpdate();
        }
    }

    public boolean displayCaptchaRequest(Account account, String id, Data data, Bitmap captcha) {
        if (mOnCaptchaRequested.size() > 0) {
            DisplayMetrics metrics = getApplicationContext().getResources().getDisplayMetrics();
            Bitmap scaled =
                    Bitmap.createScaledBitmap(
                            captcha,
                            (int) (captcha.getWidth() * metrics.scaledDensity),
                            (int) (captcha.getHeight() * metrics.scaledDensity),
                            false);
            for (OnCaptchaRequested listener : threadSafeList(this.mOnCaptchaRequested)) {
                listener.onCaptchaRequested(account, id, data, scaled);
            }
            return true;
        }
        return false;
    }

    public void updateBlocklistUi(final OnUpdateBlocklist.Status status) {
        for (OnUpdateBlocklist listener : threadSafeList(this.mOnUpdateBlocklist)) {
            listener.OnUpdateBlocklist(status);
        }
    }

    public void updateMucRosterUi() {
        for (OnMucRosterUpdate listener : threadSafeList(this.mOnMucRosterUpdate)) {
            listener.onMucRosterUpdate();
        }
    }

    public void keyStatusUpdated(AxolotlService.FetchStatus report) {
        for (OnKeyStatusUpdated listener : threadSafeList(this.mOnKeyStatusUpdated)) {
            listener.onKeyStatusUpdated(report);
        }
    }

    public Account findAccountByJid(final Jid jid) {
        for (final Account account : this.accounts) {
            if (account.getJid().asBareJid().equals(jid.asBareJid())) {
                return account;
            }
        }
        return null;
    }

    public Account findAccountByUuid(final String uuid) {
        for (Account account : this.accounts) {
            if (account.getUuid().equals(uuid)) {
                return account;
            }
        }
        return null;
    }

    public Conversation findConversationByUuid(String uuid) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getUuid().equals(uuid)) {
                return conversation;
            }
        }
        return null;
    }

    public Conversation findUniqueConversationByJid(XmppUri xmppUri) {
        List<Conversation> findings = new ArrayList<>();
        for (Conversation c : getConversations()) {
            if (c.getAccount().isEnabled()
                    && c.getJid().asBareJid().equals(xmppUri.getJid())
                    && c.getNextCounterpart() == null
                    && ((c.getMode() == Conversational.MODE_MULTI)
                            == xmppUri.isAction(XmppUri.ACTION_JOIN))) {
                findings.add(c);
            }
        }
        return findings.size() == 1 ? findings.get(0) : null;
    }

    public boolean markRead(final Conversation conversation, boolean dismiss) {
        return markRead(conversation, null, dismiss).size() > 0;
    }

    public void markRead(final Conversation conversation) {
        markRead(conversation, null, true);
    }

    public List<Message> markRead(
            final Conversation conversation, String upToUuid, boolean dismiss) {
        if (dismiss) {
            mNotificationService.clear(conversation);
        }
        final List<Message> readMessages = conversation.markRead(upToUuid);
        if (readMessages.size() > 0) {
            Runnable runnable =
                    () -> {
                        for (Message message : readMessages) {
                            databaseBackend.updateMessage(message, false);
                        }
                    };
            mDatabaseWriterExecutor.execute(runnable);
            updateConversationUi();
            updateUnreadCountBadge();
            return readMessages;
        } else {
            return readMessages;
        }
    }

    public synchronized void updateUnreadCountBadge() {
        int count = unreadCount();
        if (unreadCount != count) {
            Log.d(Config.LOGTAG, "update unread count to " + count);
            if (count > 0) {
                ShortcutBadger.applyCount(getApplicationContext(), count);
            } else {
                ShortcutBadger.removeCount(getApplicationContext());
            }
            unreadCount = count;
        }
    }

    public void sendReadMarker(final Conversation conversation, final String upToUuid) {
        final boolean isPrivateAndNonAnonymousMuc =
                conversation.getMode() == Conversation.MODE_MULTI
                        && conversation.isPrivateAndNonAnonymous();
        final List<Message> readMessages = this.markRead(conversation, upToUuid, true);
        if (readMessages.isEmpty()) {
            return;
        }
        final var account = conversation.getAccount();
        final var connection = account.getXmppConnection();
        updateConversationUi();
        final var last =
                Iterables.getLast(
                        Collections2.filter(
                                readMessages,
                                m ->
                                        !m.isPrivateMessage()
                                                && m.getStatus() == Message.STATUS_RECEIVED),
                        null);
        if (last == null) {
            return;
        }

        final boolean sendDisplayedMarker =
                confirmMessages()
                        && (last.trusted() || isPrivateAndNonAnonymousMuc)
                        && last.getRemoteMsgId() != null
                        && (last.markable || isPrivateAndNonAnonymousMuc);
        final boolean serverAssist =
                connection != null && connection.getFeatures().mdsServerAssist();

        final String stanzaId = last.getServerMsgId();

        if (sendDisplayedMarker && serverAssist) {
            final var mdsDisplayed = mIqGenerator.mdsDisplayed(stanzaId, conversation);
            final var packet = mMessageGenerator.confirm(last);
            packet.addChild(mdsDisplayed);
            if (!last.isPrivateMessage()) {
                packet.setTo(packet.getTo().asBareJid());
            }
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server assisted " + packet);
            this.sendMessagePacket(account, packet);
        } else {
            publishMds(last);
            // read markers will be sent after MDS to flush the CSI stanza queue
            if (sendDisplayedMarker) {
                Log.d(
                        Config.LOGTAG,
                        conversation.getAccount().getJid().asBareJid()
                                + ": sending displayed marker to "
                                + last.getCounterpart().toString());
                final var packet = mMessageGenerator.confirm(last);
                this.sendMessagePacket(account, packet);
            }
        }
    }

    private void publishMds(@Nullable final Message message) {
        final String stanzaId = message == null ? null : message.getServerMsgId();
        if (Strings.isNullOrEmpty(stanzaId)) {
            return;
        }
        final Conversation conversation;
        final var conversational = message.getConversation();
        if (conversational instanceof Conversation c) {
            conversation = c;
        } else {
            return;
        }
        final var account = conversation.getAccount();
        final var connection = account.getXmppConnection();
        if (connection == null || !connection.getFeatures().mds()) {
            return;
        }
        final Jid itemId;
        if (message.isPrivateMessage()) {
            itemId = message.getCounterpart();
        } else {
            itemId = conversation.getJid().asBareJid();
        }
        Log.d(Config.LOGTAG, "publishing mds for " + itemId + "/" + stanzaId);
        publishMds(account, itemId, stanzaId, conversation);
    }

    private void publishMds(
            final Account account,
            final Jid itemId,
            final String stanzaId,
            final Conversation conversation) {
        final var item = mIqGenerator.mdsDisplayed(stanzaId, conversation);
        pushNodeAndEnforcePublishOptions(
                account,
                Namespace.MDS_DISPLAYED,
                item,
                itemId.toString(),
                PublishOptions.persistentWhitelistAccessMaxItems());
    }

    public boolean sendReactions(final Message message, final Collection<String> reactions) {
        if (message.getConversation() instanceof Conversation conversation) {
            final var isPrivateMessage = message.isPrivateMessage();
            final Jid reactTo;
            final boolean typeGroupChat;
            final String reactToId;
            final Collection<Reaction> combinedReactions;
            if (conversation.getMode() == Conversational.MODE_MULTI && !isPrivateMessage) {
                final var mucOptions = conversation.getMucOptions();
                if (!mucOptions.participating()) {
                    Log.d(Config.LOGTAG, "not participating in MUC");
                    return false;
                }
                final var self = mucOptions.getSelf();
                final String occupantId = self.getOccupantId();
                if (Strings.isNullOrEmpty(occupantId)) {
                    Log.d(Config.LOGTAG, "occupant id not found for reaction in MUC");
                    return false;
                }
                final var existingRaw =
                        ImmutableSet.copyOf(
                                Collections2.transform(message.getReactionsNew(), r -> r.reaction));
                final var reactionsAsExistingVariants =
                        ImmutableSet.copyOf(
                                Collections2.transform(
                                        reactions, r -> Emoticons.existingVariant(r, existingRaw)));
                if (!reactions.equals(reactionsAsExistingVariants)) {
                    Log.d(Config.LOGTAG, "modified reactions to existing variants");
                }
                reactToId = message.getServerMsgId();
                reactTo = conversation.getJid().asBareJid();
                typeGroupChat = true;
                combinedReactions =
                        Reaction.withOccupantId(
                                message.getReactionsNew(),
                                reactionsAsExistingVariants,
                                false,
                                self.getFullJid(),
                                conversation.getAccount().getJid(),
                                occupantId);
            } else {
                if (message.isCarbon() || message.getStatus() == Message.STATUS_RECEIVED) {
                    reactToId = message.getRemoteMsgId();
                } else {
                    reactToId = message.getUuid();
                }
                typeGroupChat = false;
                if (isPrivateMessage) {
                    reactTo = message.getCounterpart();
                } else {
                    reactTo = conversation.getJid().asBareJid();
                }
                combinedReactions =
                        Reaction.withFrom(
                                message.getReactionsNew(),
                                reactions,
                                false,
                                conversation.getAccount().getJid());
            }
            if (reactTo == null || Strings.isNullOrEmpty(reactToId)) {
                return false;
            }
            final var reactionMessage =
                    mMessageGenerator.reaction(reactTo, typeGroupChat, reactToId, reactions);
            sendMessagePacket(conversation.getAccount(), reactionMessage);
            message.setReactions(combinedReactions);
            updateMessage(message, false);
            return true;
        } else {
            return false;
        }
    }

    public MemorizingTrustManager getMemorizingTrustManager() {
        return this.mMemorizingTrustManager;
    }

    public void setMemorizingTrustManager(MemorizingTrustManager trustManager) {
        this.mMemorizingTrustManager = trustManager;
    }

    public void updateMemorizingTrustManager() {
        final MemorizingTrustManager trustManager;
        if (appSettings.isTrustSystemCAStore()) {
            trustManager = new MemorizingTrustManager(getApplicationContext());
        } else {
            trustManager = new MemorizingTrustManager(getApplicationContext(), null);
        }
        setMemorizingTrustManager(trustManager);
    }

    public void syncRosterToDisk(final Account account) {
        Runnable runnable = () -> databaseBackend.writeRoster(account.getRoster());
        mDatabaseWriterExecutor.execute(runnable);
    }

    public LruCache<String, Bitmap> getBitmapCache() {
        return this.mBitmapCache;
    }

    public static String bitmapCacheKey(
            final String accountUuid,
            final String messageUuid,
            final int size,
            final String variant) {
        if (accountUuid == null
                || messageUuid == null
                || size <= 0
                || variant == null
                || variant.isEmpty()) {
            throw new IllegalArgumentException("invalid bitmap cache identity");
        }
        return "message:" + accountUuid + ":" + messageUuid + ":" + size + ":" + variant;
    }

    public Bitmap getCachedMessageBitmap(
            final Message message, final int size, final String variant) {
        return mBitmapCache.get(
                bitmapCacheKey(
                        message.getConversation().getAccount().getUuid(),
                        message.getUuid(),
                        size,
                        variant));
    }

    public void cacheMessageBitmap(
            final Message message, final int size, final String variant, final Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        mBitmapCache.put(
                bitmapCacheKey(
                        message.getConversation().getAccount().getUuid(),
                        message.getUuid(),
                        size,
                        variant),
                bitmap);
    }

    public LruCache<String, Drawable> getDrawableCache() {
        return this.mDrawableCache;
    }

    public Collection<String> getKnownHosts() {
        final Set<String> hosts = new HashSet<>();
        for (final Account account : getAccounts()) {
            hosts.add(account.getServer());
            for (final Contact contact : account.getRoster().getContacts()) {
                if (contact.showInRoster()) {
                    final String server = contact.getServer();
                    if (server != null) {
                        hosts.add(server);
                    }
                }
            }
        }
        if (Config.QUICKSY_DOMAIN != null) {
            hosts.remove(
                    Config.QUICKSY_DOMAIN
                            .toString()); // we only want to show this when we type a e164
            // number
        }
        if (Config.MAGIC_CREATE_DOMAIN != null) {
            hosts.add(Config.MAGIC_CREATE_DOMAIN);
        }
        return hosts;
    }

    public Collection<String> getKnownConferenceHosts() {
        final Set<String> mucServers = new HashSet<>();
        for (final Account account : accounts) {
            if (account.getXmppConnection() != null) {
                mucServers.addAll(account.getXmppConnection().getMucServers());
                for (final Bookmark bookmark : account.getBookmarks()) {
                    final Jid jid = bookmark.getJid();
                    final String s = jid == null ? null : jid.getDomain().toString();
                    if (s != null) {
                        mucServers.add(s);
                    }
                }
            }
        }
        return mucServers;
    }

    public void sendMessagePacket(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendMessagePacket(packet);
        }
    }

    public void sendPresencePacket(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Presence packet) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendPresencePacket(packet);
        }
    }

    public void sendCreateAccountWithCaptchaPacket(Account account, String id, Data data) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        connection.sendCreateAccountWithCaptchaPacket(id, data);
    }

    public void sendIqPacket(final Account account, final Iq packet, final Consumer<Iq> callback) {
        sendIqPacket(account, packet, callback, null);
    }

    public void sendIqPacket(
            final Account account, final Iq packet, final Consumer<Iq> callback, Long timeout) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendIqPacket(packet, callback, timeout);
        } else if (callback != null) {
            callback.accept(Iq.TIMEOUT);
        }
    }

    public void sendPresence(final Account account) {
        sendPresence(account, checkListeners() && broadcastLastActivity());
    }

    private void sendPresence(final Account account, final boolean includeIdleTimestamp) {
        // Product presence is intentionally simple: connected accounts advertise Online, while
        // the user's free-form status message and optional XEP-0319 idle timestamp remain.
        final Presence.Status status = Presence.Status.ONLINE;
        account.setPresenceStatus(status);
        final var packet = mPresenceGenerator.selfPresence(account, status);
        if (mLastActivity > 0 && includeIdleTimestamp) {
            long since =
                    Math.min(mLastActivity, System.currentTimeMillis()); // don't send future dates
            packet.addChild("idle", Namespace.IDLE)
                    .setAttribute("since", AbstractGenerator.getTimestamp(since));
        }
        sendPresencePacket(account, packet);
    }

    private void deactivateGracePeriod() {
        for (Account account : getAccounts()) {
            account.deactivateGracePeriod();
        }
    }

    public void refreshAllPresences() {
        boolean includeIdleTimestamp = checkListeners() && broadcastLastActivity();
        for (Account account : getAccounts()) {
            if (account.isConnectionEnabled()) {
                sendPresence(account, includeIdleTimestamp);
            }
        }
    }

    private void refreshAllFcmTokens() {
        for (Account account : getAccounts()) {
            if (account.isOnlineAndConnected() && mPushManagementService.available(account)) {
                mPushManagementService.registerPushTokenOnServer(account);
            }
        }
    }

    private void sendOfflinePresence(final Account account) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": sending offline presence");
        sendPresencePacket(account, mPresenceGenerator.sendOfflinePresence(account));
    }

    public MessageGenerator getMessageGenerator() {
        return this.mMessageGenerator;
    }

    public PresenceGenerator getPresenceGenerator() {
        return this.mPresenceGenerator;
    }

    public IqGenerator getIqGenerator() {
        return this.mIqGenerator;
    }

    public JingleConnectionManager getJingleConnectionManager() {
        return this.mJingleConnectionManager;
    }

    private boolean hasJingleRtpConnection(final Account account) {
        return this.mJingleConnectionManager.hasJingleRtpConnection(account);
    }

    public MessageArchiveService getMessageArchiveService() {
        return this.mMessageArchiveService;
    }

    public QuickConversationsService getQuickConversationsService() {
        return this.mQuickConversationsService;
    }

    public List<Contact> findContacts(Jid jid, String accountJid) {
        ArrayList<Contact> contacts = new ArrayList<>();
        for (Account account : getAccounts()) {
            if ((account.isEnabled() || accountJid != null)
                    && (accountJid == null
                            || accountJid.equals(account.getJid().asBareJid().toString()))) {
                Contact contact = account.getRoster().getContactFromContactList(jid);
                if (contact != null) {
                    contacts.add(contact);
                }
            }
        }
        return contacts;
    }

    public void remapMucPrivateMessageCounterpart(
            final Conversation parent,
            final Jid previousCounterpart,
            final Jid replacementCounterpart) {
        if (parent == null
                || previousCounterpart == null
                || replacementCounterpart == null
                || previousCounterpart.equals(replacementCounterpart)) {
            return;
        }
        final Conversation root =
                parent.getNextCounterpart() == null ? parent : parent.getParentConversation();
        if (root == null) {
            return;
        }
        boolean changed = false;
        synchronized (this.conversations) {
            for (final Conversation candidate : this.conversations) {
                if (candidate.getMode() != Conversation.MODE_MULTI
                        || candidate.getAccount() != root.getAccount()
                        || !candidate.getJid().asBareJid().equals(root.getJid().asBareJid())
                        || !previousCounterpart.equals(candidate.getNextCounterpart())) {
                    continue;
                }
                candidate.setNextCounterpart(replacementCounterpart);
                candidate.setParentConversation(root);
                updateConversation(candidate);
                changed = true;
            }
        }
        if (changed) {
            updateConversationUi();
        }
    }

    public Conversation findFirstMuc(Jid jid) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getAccount().isEnabled()
                    && conversation.getJid().asBareJid().equals(jid.asBareJid())
                    && conversation.getNextCounterpart() == null
                    && conversation.getMode() == Conversation.MODE_MULTI) {
                return conversation;
            }
        }
        return null;
    }

    public NotificationService getNotificationService() {
        return this.mNotificationService;
    }

    public HttpConnectionManager getHttpConnectionManager() {
        return this.mHttpConnectionManager;
    }

    public void resendFailedMessages(final Message message, final boolean forceP2P) {
        message.setTime(System.currentTimeMillis());
        markMessage(message, Message.STATUS_WAITING);
        this.sendMessage(message, true, false, forceP2P);
        if (message.getConversation() instanceof Conversation c) {
            c.sort();
        }
        updateConversationUi();
    }

    public interface OnLocalAccountDataCleared {
        void onComplete(boolean success);
    }

    /** Clears retained-account message data locally without issuing any server command. */
    public void clearLocalAccountData(
            final Account account, @Nullable final OnLocalAccountDataCleared callback) {
        final String accountUuid = account.getUuid();
        mDatabaseWriterExecutor.execute(
                () -> {
                    boolean success = false;
                    beginSecureContentAccountCleanup(account);
                    try {
                        if (findAccountByUuid(accountUuid) != account) {
                            throw new IllegalStateException("Account is no longer available");
                        }
                        runWithSecureContentMutationsQuiesced(
                                () -> clearLocalAccountDataUnderCleanupGate(account, true));
                        success = true;
                    } catch (final Exception exception) {
                        // Fail closed: Message rows remain when secure retirement or cleanup fails.
                        Log.e(Config.LOGTAG, "unable to clear local account data", exception);
                    } finally {
                        endSecureContentAccountCleanup(account);
                    }
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                });
    }

    /**
     * Crypto-first account data cleanup. Caller must hold both account cleanup gates for the full
     * operation so no HTTP/Jingle writer can repopulate protected state between retirement and DB
     * deletion.
     */
    private void clearLocalAccountDataUnderCleanupGate(
            final Account account, final boolean updateRetainedAccountState) throws Exception {
        final String accountUuid = account.getUuid();
        final LocalAccountDataSnapshot snapshot =
                databaseBackend.snapshotLocalAccountData(accountUuid);

        if (!(getApplication() instanceof Conversations)) {
            throw new IllegalStateException("Secure Content Store is unavailable");
        }
        final Conversations application = (Conversations) getApplication();
        final var store = application.getSecureContentStoreProvider().get();
        final SecureMessageRetirementBoundary retirement =
                new SecureMessageRetirementBoundary(getSecureMessagePayloadCoordinator(), store);
        retirement.retireAccount(accountUuid);

        for (final String path : snapshot.getLegacyMediaPaths()) {
            if (!fileBackend.deleteAppOwnedLegacyMedia(accountUuid, path)) {
                throw new IOException("Unable to delete app-owned legacy media");
            }
        }
        if (!fileBackend.deleteAccountPlaintextMedia(accountUuid)) {
            throw new IOException("Unable to delete account plaintext media");
        }
        if (!fileBackend.deleteAccountAvatarCache(account)) {
            throw new IOException("Unable to delete account avatar cache");
        }
        // Avatar bitmaps are not message media. Retire the in-memory view after their files.
        getBitmapCache().evictAll();

        final SecureMessageSearchCoordinator search =
                SecureMessageSearchRuntime.INSTANCE.create(databaseBackend);
        if (search == null) {
            throw new IllegalStateException("Secure search cleanup is unavailable");
        }
        search.deleteAccount(accountUuid);
        final SecureMessageTextRepository repository = secureMessageTextRepository;
        if (repository != null) {
            repository.invalidateAccount(accountUuid);
        }
        new AndroidSecureMessageMediaReadCache(this, store).clearAccount(accountUuid);
        for (final String messageUuid : snapshot.getMessageUuids()) {
            evictPreview(messageUuid);
        }

        databaseBackend.deleteLocalAccountMessageData(snapshot);

        if (!updateRetainedAccountState) {
            return;
        }
        final Map<String, LocalAccountDataSnapshot.ConversationClearMarker> markers =
                new HashMap<>();
        for (final LocalAccountDataSnapshot.ConversationClearMarker marker :
                snapshot.getConversations()) {
            markers.put(marker.getConversationUuid(), marker);
        }
        for (final Conversation conversation : getConversations()) {
            if (!accountUuid.equals(conversation.getAccount().getUuid())) {
                continue;
            }
            final LocalAccountDataSnapshot.ConversationClearMarker marker =
                    markers.get(conversation.getUuid());
            conversation.clearLocalMessageState();
            conversation.setHasMessagesLeftOnServer(false);
            if (marker != null) {
                conversation.setLastClearHistory(
                        marker.getClearTimestamp(), marker.getServerMessageId());
            }
            mNotificationService.clear(conversation);
        }
        updateConversationUi();
        updateUnreadCountBadge();
    }

    public void clearConversationHistory(final Conversation conversation) {
        final long clearDate;
        final String reference;
        if (conversation.countMessages() > 0) {
            Message latestMessage = conversation.getLatestMessage();
            clearDate = latestMessage.getTimeSent() + 1000;
            reference = latestMessage.getServerMsgId();
        } else {
            clearDate = System.currentTimeMillis();
            reference = null;
        }
        conversation.clearMessages();
        conversation.setHasMessagesLeftOnServer(false); // avoid messages getting loaded through mam
        conversation.setLastClearHistory(clearDate, reference);
        Runnable runnable =
                () -> {
                    if (!retireSecureMessageContentForConversation(conversation)) {
                        return;
                    }
                    databaseBackend.deleteMessagesInConversation(conversation);
                    databaseBackend.updateConversation(conversation);
                };
        mDatabaseWriterExecutor.execute(runnable);
    }

    public boolean sendBlockRequest(
            final Blockable blockable, final boolean reportSpam, final String serverMsgId) {
        if (blockable != null && blockable.getBlockedJid() != null) {
            final var account = blockable.getAccount();
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(
                    account,
                    getIqGenerator().generateSetBlockRequest(jid, reportSpam, serverMsgId),
                    (response) -> {
                        if (response.getType() == Iq.Type.RESULT) {
                            account.getBlocklist().add(jid);
                            updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED);
                        }
                    });
            if (blockable.getBlockedJid().isFullJid()) {
                return false;
            } else if (removeBlockedConversations(blockable.getAccount(), jid)) {
                updateConversationUi();
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean removeBlockedConversations(final Account account, final Jid blockedJid) {
        boolean removed = false;
        synchronized (this.conversations) {
            boolean domainJid = blockedJid.getLocal() == null;
            for (Conversation conversation : this.conversations) {
                boolean jidMatches =
                        (domainJid
                                        && blockedJid
                                                .getDomain()
                                                .equals(conversation.getJid().getDomain()))
                                || blockedJid.equals(conversation.getJid().asBareJid());
                if (conversation.getAccount() == account
                        && conversation.getMode() == Conversation.MODE_SINGLE
                        && jidMatches) {
                    this.conversations.remove(conversation);
                    markRead(conversation);
                    conversation.setStatus(Conversation.STATUS_ARCHIVED);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": archiving conversation "
                                    + conversation.getJid().asBareJid()
                                    + " because jid was blocked");
                    updateConversation(conversation);
                    removed = true;
                }
            }
        }
        return removed;
    }

    public void sendUnblockRequest(final Blockable blockable) {
        if (blockable != null && blockable.getJid() != null) {
            final var account = blockable.getAccount();
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(
                    account,
                    getIqGenerator().generateSetUnblockRequest(jid),
                    response -> {
                        if (response.getType() == Iq.Type.RESULT) {
                            account.getBlocklist().remove(jid);
                            updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED);
                        }
                    });
        }
    }

    public void publishDisplayName(final Account account) {
        String displayName = account.getDisplayName();
        final Iq request;
        if (TextUtils.isEmpty(displayName)) {
            request = mIqGenerator.deleteNode(Namespace.NICK);
        } else {
            request = mIqGenerator.publishNick(displayName);
        }
        mAvatarService.clear(account);
        sendIqPacket(
                account,
                request,
                (packet) -> {
                    if (packet.getType() == Iq.Type.ERROR) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": unable to modify nick name "
                                        + packet);
                    }
                });
    }

    public ServiceDiscoveryResult getCachedServiceDiscoveryResult(Pair<String, String> key) {
        ServiceDiscoveryResult result = discoCache.get(key);
        if (result != null) {
            return result;
        } else {
            if (key.first == null || key.second == null) return null;
            result = databaseBackend.findDiscoveryResult(key.first, key.second);
            if (result != null) {
                discoCache.put(key, result);
            }
            return result;
        }
    }

    public void fetchFromGateway(
            Account account, final Jid jid, final String input, final OnGatewayResult callback) {
        Iq request = new Iq(input == null ? Iq.Type.GET : Iq.Type.SET);
        request.setTo(jid);
        Element query = request.query("jabber:iq:gateway");
        if (input != null) {
            Element prompt = query.addChild("prompt");
            prompt.setContent(input);
        }
        sendIqPacket(
                account,
                request,
                (Iq packet) -> {
                    if (packet.getType() == Iq.Type.RESULT) {
                        callback.onGatewayResult(
                                packet.query().findChildContent(input == null ? "prompt" : "jid"),
                                null);
                    } else {
                        Element error = packet.findChild("error");
                        callback.onGatewayResult(
                                null, error == null ? null : error.findChildContent("text"));
                    }
                });
    }

    public void fetchCaps(final Account account, final Jid jid, final Presence presence) {
        fetchCaps(account, jid, presence, null);
    }

    public void fetchCaps(Account account, final Jid jid, final Presence presence, Runnable cb) {
        final Pair<String, String> key =
                presence == null ? null : new Pair<>(presence.getHash(), presence.getVer());
        final ServiceDiscoveryResult disco =
                key == null ? null : getCachedServiceDiscoveryResult(key);
        if (disco != null) {
            presence.setServiceDiscoveryResult(disco);
            final Contact contact = account.getRoster().getContact(jid);
            if (contact.refreshRtpCapability()) {
                syncRoster(account);
            }

            updateConversationUi(true);
        } else {
            final Iq request = new Iq(Iq.Type.GET);
            request.setTo(jid);
            final String node = presence == null ? null : presence.getNode();
            final String ver = presence == null ? null : presence.getVer();
            final Element query = request.query(Namespace.DISCO_INFO);
            if (node != null && ver != null) {
                query.setAttribute("node", node + "#" + ver);
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": making disco request for "
                            + (key == null ? "" : key.second)
                            + " to "
                            + jid);
            sendIqPacket(
                    account,
                    request,
                    (response) -> {
                        if (response.getType() == Iq.Type.RESULT) {
                            final ServiceDiscoveryResult discoveryResult =
                                    new ServiceDiscoveryResult(response);
                            if (presence == null
                                    || presence.getVer() == null
                                    || presence.getVer().equals(discoveryResult.getVer())) {
                                databaseBackend.insertDiscoveryResult(discoveryResult);
                                injectServiceDiscoveryResult(
                                        account.getRoster(),
                                        presence == null ? null : presence.getHash(),
                                        presence == null ? null : presence.getVer(),
                                        jid.getResource(),
                                        discoveryResult);

                                updateConversationUi(true);
                                if (cb != null) cb.run();
                            } else {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": mismatch in caps for contact "
                                                + jid
                                                + " "
                                                + presence.getVer()
                                                + " vs "
                                                + discoveryResult.getVer());
                            }
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": unable to fetch caps from "
                                            + jid);
                        }
                    });
        }
    }

    public void fetchCommands(Account account, final Jid jid, OnIqPacketReceived callback) {
        final Iq request = mIqGenerator.queryDiscoItems(jid, "http://jabber.org/protocol/commands");
        sendIqPacket(account, request, iq -> callback.onIqPacketReceived(iq), 5L);
    }

    private void injectServiceDiscoveryResult(
            Roster roster, String hash, String ver, String resource, ServiceDiscoveryResult disco) {
        boolean rosterNeedsSync = false;
        for (final Contact contact : roster.getContacts()) {
            boolean serviceDiscoverySet = false;
            Presence onePresence = contact.getPresences().get(resource == null ? "" : resource);
            if (onePresence != null) {
                onePresence.setServiceDiscoveryResult(disco);
                serviceDiscoverySet = true;
            } else if (resource == null && hash == null && ver == null) {
                Presence p = new Presence(Presence.Status.OFFLINE, null, null, null, "");
                p.setServiceDiscoveryResult(disco);
                contact.updatePresence("", p);
                serviceDiscoverySet = true;
            }
            if (hash != null && ver != null) {
                for (final Presence presence : contact.getPresences().getPresences()) {
                    if (hash.equals(presence.getHash()) && ver.equals(presence.getVer())) {
                        presence.setServiceDiscoveryResult(disco);
                        serviceDiscoverySet = true;
                    }
                }
            }
            if (serviceDiscoverySet) {
                rosterNeedsSync |= contact.refreshRtpCapability();
            }
        }
        if (rosterNeedsSync) {
            syncRoster(roster.getAccount());
        }
    }

    public void fetchMamPreferences(final Account account, final OnMamPreferencesFetched callback) {
        final MessageArchiveService.Version version = MessageArchiveService.Version.get(account);
        final Iq request = new Iq(Iq.Type.GET);
        request.addChild("prefs", version.namespace);
        sendIqPacket(
                account,
                request,
                (packet) -> {
                    final Element prefs = packet.findChild("prefs", version.namespace);
                    if (packet.getType() == Iq.Type.RESULT && prefs != null) {
                        callback.onPreferencesFetched(prefs);
                    } else {
                        callback.onPreferencesFetchFailed();
                    }
                });
    }

    public PushManagementService getPushManagementService() {
        return mPushManagementService;
    }

    public void changeStatus(Account account, PresenceTemplate template, String signature) {
        if (!template.getStatusMessage().isEmpty()) {
            databaseBackend.insertPresenceTemplate(template);
        }
        account.clearLegacyEncryptionSignature();
        account.setPresenceStatus(template.getStatus());
        account.setPresenceStatusMessage(template.getStatusMessage());
        databaseBackend.updateAccount(account);
        sendPresence(account);
    }

    public List<PresenceTemplate> getPresenceTemplates(Account account) {
        List<PresenceTemplate> templates = databaseBackend.getPresenceTemplates();
        for (PresenceTemplate template : account.getSelfContact().getPresences().asTemplates()) {
            if (!templates.contains(template)) {
                templates.add(0, template);
            }
        }
        return templates;
    }

    public void saveConversationAsBookmark(final Conversation conversation, final String name) {
        final Account account = conversation.getAccount();
        final Bookmark bookmark = new Bookmark(account, conversation.getJid().asBareJid());
        final String nick = conversation.getJid().getResource();
        if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
            bookmark.setNick(nick);
        }
        if (!TextUtils.isEmpty(name)) {
            bookmark.setBookmarkName(name);
        }
        bookmark.setAutojoin(true);
        createBookmark(account, bookmark);
        bookmark.setConversation(conversation);
    }

    public boolean verifyFingerprints(Contact contact, List<XmppUri.Fingerprint> fingerprints) {
        boolean needsRosterWrite = false;
        boolean performedVerification = false;
        final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OTR) {
                performedVerification |= contact.addOtrFingerprint(fp.fingerprint);
                needsRosterWrite |= performedVerification;
            } else if (fp.type == XmppUri.FingerprintType.OMEMO) {
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                FingerprintStatus fingerprintStatus =
                        axolotlService.getFingerprintTrust(fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        performedVerification = true;
                        axolotlService.setFingerprintTrust(
                                fingerprint, fingerprintStatus.toVerified());
                    }
                } else {
                    axolotlService.preVerifyFingerprint(contact, fingerprint);
                }
            }
        }

        if (needsRosterWrite) {
            syncRosterToDisk(contact.getAccount());
        }

        return performedVerification;
    }

    public boolean verifyFingerprints(Account account, List<XmppUri.Fingerprint> fingerprints) {
        final AxolotlService axolotlService = account.getAxolotlService();
        boolean verifiedSomething = false;
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                Log.d(Config.LOGTAG, "trying to verify own fp=" + fingerprint);
                FingerprintStatus fingerprintStatus =
                        axolotlService.getFingerprintTrust(fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        axolotlService.setFingerprintTrust(
                                fingerprint, fingerprintStatus.toVerified());
                        verifiedSomething = true;
                    }
                } else {
                    axolotlService.preVerifyFingerprint(account, fingerprint);
                    verifiedSomething = true;
                }
            }
        }
        return verifiedSomething;
    }

    public boolean blindTrustBeforeVerification() {
        return getBooleanPreference(AppSettings.BLIND_TRUST_BEFORE_VERIFICATION, R.bool.btbv);
    }

    public ShortcutService getShortcutService() {
        return mShortcutService;
    }

    public void pushMamPreferences(Account account, Element prefs) {
        final Iq set = new Iq(Iq.Type.SET);
        set.addChild(prefs);
        sendIqPacket(account, set, null);
    }

    public void evictPreview(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return;
        }
        boolean removed = mBitmapCache.remove(uuid) != null;
        final String messageMarker = ":" + uuid + ":";
        for (final String key : new ArrayList<>(mBitmapCache.snapshot().keySet())) {
            if (key.contains(messageMarker) && mBitmapCache.remove(key) != null) {
                removed = true;
            }
        }
        if (removed) {
            Log.d(Config.LOGTAG, "deleted cached preview variants");
        }
    }

    public interface OnMamPreferencesFetched {
        void onPreferencesFetched(Element prefs);

        void onPreferencesFetchFailed();
    }

    public interface OnAccountCreated {
        void onAccountCreated(Account account);

        void informUser(int r);
    }

    public interface JumpToMessageListener {
        void onSuccess();

        void onNotFound();
    }

    public interface OnMoreMessagesLoaded {
        void onMoreMessagesLoaded(int count, Conversation conversation);
    }

    public interface OnAccountPasswordChanged {
        void onPasswordChangeSucceeded();

        void onPasswordChangeFailed();
    }

    public interface OnRoomDestroy {
        void onRoomDestroySucceeded();

        void onRoomDestroyFailed();
    }

    public interface OnAffiliationChanged {
        void onAffiliationChangedSuccessful(Jid jid);

        void onAffiliationChangeFailed(Jid jid, int resId);
    }

    public interface OnConversationUpdate {
        default void onConversationUpdate() {
            onConversationUpdate(false);
        }

        default void onConversationUpdate(boolean newCaps) {
            onConversationUpdate();
        }
    }

    public interface OnJingleRtpConnectionUpdate {
        void onJingleRtpConnectionUpdate(
                final Account account,
                final Jid with,
                final String sessionId,
                final RtpEndUserState state);

        void onAudioDeviceChanged(
                CallIntegration.AudioDevice selectedAudioDevice,
                Set<CallIntegration.AudioDevice> availableAudioDevices);
    }

    public interface OnAccountUpdate {
        void onAccountUpdate();
    }

    public interface OnCaptchaRequested {
        void onCaptchaRequested(Account account, String id, Data data, Bitmap captcha);
    }

    public interface OnRosterUpdate {
        void onRosterUpdate();
    }

    public interface OnMucRosterUpdate {
        void onMucRosterUpdate();
    }

    public interface OnConferenceConfigurationFetched {
        void onConferenceConfigurationFetched(Conversation conversation);

        void onFetchFailed(Conversation conversation, String errorCondition);
    }

    public interface OnConferenceJoined {
        void onConferenceJoined(Conversation conversation);
    }

    public interface OnConfigurationPushed {
        void onPushSucceeded();

        void onPushFailed();
    }

    public interface OnShowErrorToast {
        void onShowErrorToast(int resId);
    }

    public class XmppConnectionBinder extends Binder {
        public XmppConnectionService getService() {
            return XmppConnectionService.this;
        }
    }

    private class InternalEventReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            onStartCommand(intent, 0, 0);
        }
    }

    private class RestrictedEventReceiver extends BroadcastReceiver {

        private final Collection<String> allowedActions;

        private RestrictedEventReceiver(final Collection<String> allowedActions) {
            this.allowedActions = allowedActions;
        }

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent == null ? null : intent.getAction();
            if (allowedActions.contains(action)) {
                onStartCommand(intent, 0, 0);
            } else {
                Log.e(Config.LOGTAG, "restricting broadcast of event " + action);
            }
        }
    }

    public static class OngoingCall {
        public final AbstractJingleConnection.Id id;
        public final Set<Media> media;
        public final boolean reconnecting;

        public OngoingCall(
                AbstractJingleConnection.Id id, Set<Media> media, final boolean reconnecting) {
            this.id = id;
            this.media = media;
            this.reconnecting = reconnecting;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OngoingCall that = (OngoingCall) o;
            return reconnecting == that.reconnecting
                    && Objects.equal(id, that.id)
                    && Objects.equal(media, that.media);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id, media, reconnecting);
        }
    }

    public static void toggleForegroundService(final XmppConnectionService service) {
        if (service == null) {
            return;
        }
        service.toggleForegroundService();
    }

    public static void toggleForegroundService(final ConversationsActivity activity) {
        if (activity == null) {
            return;
        }
        toggleForegroundService(activity.xmppConnectionService);
    }
}
