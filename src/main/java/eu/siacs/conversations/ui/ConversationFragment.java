package eu.siacs.conversations.ui;

import static eu.siacs.conversations.ui.XmppActivity.EXTRA_ACCOUNT;
import static eu.siacs.conversations.ui.XmppActivity.REQUEST_INVITE_TO_CONVERSATION;
import static eu.siacs.conversations.ui.util.SoftKeyboardUtils.hideSoftKeyboard;
import static eu.siacs.conversations.ui.util.SoftKeyboardUtils.showKeyboard;
import static eu.siacs.conversations.utils.PermissionUtils.allGranted;
import static eu.siacs.conversations.utils.PermissionUtils.audioGranted;
import static eu.siacs.conversations.utils.PermissionUtils.cameraGranted;
import static eu.siacs.conversations.utils.PermissionUtils.getFirstDenied;
import static eu.siacs.conversations.utils.PermissionUtils.writeGranted;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.TimePicker;
import android.widget.Toast;
import androidx.annotation.ColorInt;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import androidx.databinding.DataBindingUtil;
import androidx.viewpager.widget.PagerAdapter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.databinding.FragmentConversationBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Blockable;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.MediaGalleryPresentation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.User;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.entities.TransferablePlaceholder;
import eu.siacs.conversations.entities.media.MediaCaptionPresentation;
import eu.siacs.conversations.entities.media.MediaCaptionResolver;
import eu.siacs.conversations.entities.media.MediaLocalDeleteResolver;
import eu.siacs.conversations.http.HttpDownloadConnection;
import eu.siacs.conversations.medialib.activities.EditActivity;
import eu.siacs.conversations.medialib.activities.VideoEditActivity;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.storage.secure.AndroidSecureContentExport;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaExporter;
import eu.siacs.conversations.storage.secure.ContentExportOperation;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway;
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageMediaLegacyBoundary;
import eu.siacs.conversations.storage.secure.SecureMessageMediaSaveBridge;
import eu.siacs.conversations.storage.secure.SecureMessageMediaUiBridge;
import eu.siacs.conversations.storage.secure.SecureOutgoingVoiceStagingRetirer;
import eu.siacs.conversations.ui.actions.MessageAction;
import eu.siacs.conversations.ui.actions.MessageActionController;
import eu.siacs.conversations.ui.actions.MessageActionResolver;
import eu.siacs.conversations.ui.actions.reactions.QuickReaction;
import eu.siacs.conversations.ui.actions.reactions.QuickReactionResolver;
import eu.siacs.conversations.ui.actions.ui.MaterialMessageActionSheet;
import eu.siacs.conversations.ui.actions.ui.MessageActionPreview;
import eu.siacs.conversations.ui.adapter.CommandAdapter;
import eu.siacs.conversations.ui.adapter.MediaPreviewAdapter;
import eu.siacs.conversations.ui.adapter.MessageAdapter;
import eu.siacs.conversations.ui.appearance.ChatWallpaperPresets;
import eu.siacs.conversations.ui.media.OutgoingMediaPreparingPresentation;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.ui.text.TypographyHelper;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.ChatChromeTint;
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator;
import eu.siacs.conversations.ui.util.DateSeparator;
import eu.siacs.conversations.ui.util.EditMessageActionModeCallback;
import eu.siacs.conversations.ui.util.EditMessageSelectionActionModeCallback;
import eu.siacs.conversations.ui.util.ImageAttachmentStaging;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.PresenceSelector;
import eu.siacs.conversations.ui.util.ScrollState;
import eu.siacs.conversations.ui.util.SendButtonAction;
import eu.siacs.conversations.ui.util.SendButtonTool;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.TimelineRefreshGate;
import eu.siacs.conversations.ui.util.TouchTargetHelper;
import eu.siacs.conversations.ui.util.VideoAttachmentStaging;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.ui.util.VoiceRecorder;
import eu.siacs.conversations.ui.util.VoiceRecordingSession;
import eu.siacs.conversations.ui.util.VoiceRecordingStaging;
import eu.siacs.conversations.ui.widget.EditMessage;
import eu.siacs.conversations.ui.widget.HighlighterView;
import eu.siacs.conversations.ui.widget.TabLayout;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.ChatBackgroundHelper;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageMarkup;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.NickValidityChecker;
import eu.siacs.conversations.utils.PermissionUtils;
import eu.siacs.conversations.utils.QuickLoader;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.worker.SendMessageWorker;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.jingle.JingleFileTransferConnection;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ConversationFragment extends XmppFragment
        implements EditMessage.KeyboardListener,
                MessageAdapter.OnContactPictureLongClicked,
                MessageAdapter.OnContactPictureClicked {

    public static final int REQUEST_TRUST_KEYS_TEXT = 0x0208;
    public static final int REQUEST_TRUST_KEYS_ATTACHMENTS = 0x0209;
    public static final int REQUEST_START_DOWNLOAD = 0x0210;
    public static final int REQUEST_ADD_EDITOR_CONTENT = 0x0211;
    public static final int REQUEST_COMMIT_ATTACHMENTS = 0x0212;
    public static final int REQUEST_START_AUDIO_CALL = 0x213;
    public static final int REQUEST_START_VIDEO_CALL = 0x214;
    public static final int REQUEST_PICK_DATE = 0x0215;
    public static final int ATTACHMENT_CHOICE_CHOOSE_IMAGE = 0x0301;
    public static final int ATTACHMENT_CHOICE_TAKE_PHOTO = 0x0302;
    public static final int ATTACHMENT_CHOICE_CHOOSE_FILE = 0x0303;
    public static final int ATTACHMENT_CHOICE_RECORD_VOICE = 0x0304;
    public static final int ATTACHMENT_CHOICE_LOCATION = 0x0305;
    public static final int ATTACHMENT_CHOICE_INVALID = 0x0306;
    public static final int ATTACHMENT_CHOICE_RECORD_VIDEO = 0x0307;
    public static final int ATTACHMENT_CHOICE_EDIT_PHOTO = 0x0308;
    public static final int ATTACHMENT_CHOICE_EDIT_VIDEO = 0x0309;

    private static final int TEXT_ACTION_CUT = 0x0401;
    private static final int TEXT_ACTION_COPY = 0x0402;
    private static final int TEXT_ACTION_PASTE = 0x0403;
    private static final int TEXT_ACTION_SELECT_ALL = 0x0404;
    private static final int TEXT_ACTION_PASTE_AS_QUOTE = 0x0405;

    public static final String RECENTLY_USED_QUICK_ACTION = "recently_used_quick_action";
    public static final String STATE_CONVERSATION_UUID =
            ConversationFragment.class.getName() + ".uuid";
    public static final String STATE_SCROLL_POSITION =
            ConversationFragment.class.getName() + ".scroll_position";
    public static final String STATE_PHOTO_URI =
            ConversationFragment.class.getName() + ".media_previews";
    public static final String STATE_MEDIA_PREVIEWS =
            ConversationFragment.class.getName() + ".take_photo_uri";
    private static final String STATE_LAST_MESSAGE_UUID = "state_last_message_uuid";
    private static final String STATE_ATTACHMENT_CONVERSATION_UUID =
            "state_attachment_conversation_uuid";
    private static final String STATE_MEDIA_COMMIT_CONVERSATION_UUID =
            "state_media_commit_conversation_uuid";

    private static final long STICKY_DATE_HIDE_DELAY_MS = 900L;
    private static final long STICKY_DATE_FADE_MS = 160L;
    private static final long IME_RESIZE_SETTLE_MS = 220L;
    private static final long BOTTOM_SCROLL_EASING_DURATION_MS = 220L;
    private static final long INCOMING_MEDIA_COALESCE_IDLE_MS = 160L;
    private static final long INCOMING_MEDIA_COALESCE_MAX_MS = 360L;
    private static final int BACKWARD_HISTORY_TRIGGER_ITEMS = 5;
    private static final PathInterpolator BOTTOM_SCROLL_EASING =
            new PathInterpolator(0.2f, 0f, 0f, 1f);

    private FragmentConversationBinding binding;

    private int chatSystemBarTopInset;
    private int chatSystemBarLeftInset;
    private int chatSystemBarRightInset;
    private int chatSystemBarBottomInset;
    private int lastImeBottomInset = -1;
    private boolean imeResizeInProgress;
    private boolean pinBottomDuringImeResize;
    @Nullable private VisualScrollAnchor imeResizeScrollAnchor;
    private boolean programmaticBottomPin;
    private boolean passiveBottomPinPending;
    private boolean followLatestMessages = true;
    private boolean userScrollControlsFollowLatest;
    private boolean deferredTimelineRefreshWhileReading;
    @Nullable private Runnable pendingHistoryPageUiPublish;
    private boolean backwardHistoryPaginationArmed = true;
    private boolean suppressNextRefreshBottomFollow;
    private boolean bottomFollowTailInitialized;
    @Nullable private String lastBottomFollowTailUuid;
    private int bottomPinGeneration;
    private int chromePaddingSettleGeneration;
    private long renderedTimelineRevision = Long.MIN_VALUE;
    private long renderedDynamicTimelineSignature = Long.MIN_VALUE;
    private long renderedConversationPresentationRevision = Long.MIN_VALUE;
    private long timelinePresentationRevision;
    @Nullable private String renderedTimelineConversationUuid;
    @Nullable private VisualScrollAnchor attachmentViewportAnchor;
    @Nullable private String attachmentViewportConversationUuid;
    private boolean attachmentViewportKeepBottomPinned;
    private boolean attachmentViewportLaunchPending;
    private boolean attachmentViewportFinishPending;
    private boolean forceTimelineRebuild = true;
    private long timelineRevisionAtStop = Long.MIN_VALUE;
    @Nullable private String timelineConversationUuidAtStop;
    @Nullable private ValueAnimator bottomScrollAnimator;
    private boolean stickyDateScrolling;
    private final Runnable hideStickyDateRunnable =
            () -> {
                if (binding == null || stickyDateScrolling) {
                    return;
                }
                binding.messageDateOverlay
                        .animate()
                        .alpha(0f)
                        .setDuration(STICKY_DATE_FADE_MS)
                        .withEndAction(
                                () -> {
                                    if (binding != null && !stickyDateScrolling) {
                                        binding.messageDateOverlay.setVisibility(View.GONE);
                                    }
                                })
                        .start();
            };
    private int textsendBasePaddingLeft;
    private int textsendBasePaddingTop;
    private int textsendBasePaddingRight;
    private int textsendBasePaddingBottom;
    private int defaultComposerIslandColor;
    private boolean composerFormattingPressInProgress;
    private int composerFormattingSelectionStart = -1;
    private int composerFormattingSelectionEnd = -1;
    private boolean chatBackgroundUsesPreset;
    private int chatBackgroundPresetTopColor;
    private int chatBackgroundPresetBottomColor;
    @Nullable private String chatBackgroundPresetRenderKey;

    private final List<Message> messageList = new ArrayList<>();
    private MediaGalleryPresentation mediaGalleryPresentation;
    private MediaCaptionPresentation mediaCaptionPresentation;
    private final OutgoingMediaPreparingPresentation outgoingMediaPreparingPresentation =
            new OutgoingMediaPreparingPresentation();
    private final List<OutgoingMediaPreparingSession> outgoingMediaPreparingSessions =
            new ArrayList<>();
    private final Map<String, IncomingMediaCoalescingState> incomingMediaCoalescingStates =
            new HashMap<>();
    private final Set<String> incomingMediaKnownMessageUuids = new HashSet<>();
    private final Set<String> incomingMediaReleasedAnchorIds = new HashSet<>();
    @Nullable private String incomingMediaCoalescingConversationUuid;
    private final Runnable incomingMediaCoalescingRefreshRunnable =
            () -> {
                if (binding != null && ConversationFragment.this.conversation != null) {
                    forceTimelineRebuild = true;
                    refresh(false);
                }
            };
    private long mediaGalleryRevision;
    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
    private final PendingItem<String> pendingConversationsUuid = new PendingItem<>();
    private final PendingItem<ArrayList<Attachment>> pendingMediaPreviews = new PendingItem<>();
    private final PendingItem<Bundle> pendingExtras = new PendingItem<>();
    private final PendingItem<Uri> pendingTakePhotoUri = new PendingItem<>();
    private final PendingItem<Uri> pendingEditedImageUri = new PendingItem<>();
    private final PendingItem<Boolean> pendingEditedImageWasPreview = new PendingItem<>();
    private final PendingItem<Uri> pendingEditedVideoUri = new PendingItem<>();
    private final PendingItem<Boolean> pendingEditedVideoWasPreview = new PendingItem<>();
    private final PendingItem<ScrollState> pendingScrollState = new PendingItem<>();
    private final PendingItem<String> pendingLastMessageUuid = new PendingItem<>();
    private final PendingItem<Message> pendingMessage = new PendingItem<>();
    public Uri mPendingEditorContent = null;
    protected MessageAdapter messageListAdapter;
    private MediaPreviewAdapter mediaPreviewAdapter;
    @Nullable private MediaDraftSnapshot pendingMediaDraft;
    @Nullable private String pendingAttachmentConversationUuid;
    @Nullable private String pendingMediaCommitConversationUuid;
    protected CommandAdapter commandAdapter;

    /**
     * Immutable composer state captured when the user commits a media draft. Caption and context
     * stay local in this phase; media wire semantics are deliberately left to a later phase.
     */
    private static final class MediaDraftSnapshot {
        private final String caption;
        private final List<Attachment> attachments;
        @Nullable private final Conversation conversation;
        @Nullable private final Message replyTo;
        @Nullable private final Jid counterpart;

        private MediaDraftSnapshot(
                final String caption,
                final Collection<Attachment> attachments,
                @Nullable final Conversation conversation,
                @Nullable final Message replyTo,
                @Nullable final Jid counterpart) {
            this.caption = caption;
            this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
            this.conversation = conversation;
            this.replyTo = replyTo;
            this.counterpart = counterpart;
        }

        private static MediaDraftSnapshot from(
                final String caption,
                final Collection<Attachment> attachments,
                @Nullable final Conversation conversation) {
            return new MediaDraftSnapshot(
                    caption,
                    attachments,
                    conversation,
                    conversation == null ? null : conversation.getReplyTo(),
                    conversation == null ? null : conversation.getNextCounterpart());
        }

        private String getCaption() {
            return caption;
        }

        private List<Attachment> getAttachments() {
            return attachments;
        }

        @Nullable
        private Conversation getConversation() {
            return conversation;
        }

        @Nullable
        private Message getReplyTo() {
            return replyTo;
        }
    }

    private static final class IncomingMediaCoalescingState {
        private final long firstSeenUptime;
        private long deadlineUptime;

        private IncomingMediaCoalescingState(final long now) {
            firstSeenUptime = now;
            deadlineUptime =
                    Math.min(
                            firstSeenUptime + INCOMING_MEDIA_COALESCE_MAX_MS,
                            now + INCOMING_MEDIA_COALESCE_IDLE_MS);
        }

        private void extend(final long now) {
            deadlineUptime =
                    Math.min(
                            firstSeenUptime + INCOMING_MEDIA_COALESCE_MAX_MS,
                            now + INCOMING_MEDIA_COALESCE_IDLE_MS);
        }
    }

    private final class OutgoingMediaPreparingSession {
        private final MediaDraftSnapshot draft;
        private final Message placeholder;
        @Nullable private final String mediaGroupId;
        private final int expectedMediaMessages;
        private final boolean waitForRelatedCaption;
        private final Set<Attachment> completed =
                Collections.newSetFromMap(new IdentityHashMap<Attachment, Boolean>());
        private boolean preparationComplete;
        private boolean publishedHandoffArmed;
        private boolean terminal;

        private OutgoingMediaPreparingSession(
                final MediaDraftSnapshot draft,
                final Message placeholder,
                @Nullable final String mediaGroupId,
                final int expectedMediaMessages,
                final boolean waitForRelatedCaption) {
            this.draft = draft;
            this.placeholder = placeholder;
            this.mediaGroupId = mediaGroupId;
            this.expectedMediaMessages = expectedMediaMessages;
            this.waitForRelatedCaption = waitForRelatedCaption;
        }

        private boolean keepsPlaceholderUntilPublishedGroup() {
            return mediaGroupId != null && !mediaGroupId.isEmpty() && expectedMediaMessages >= 1;
        }

        private void onAttachmentReady(final Attachment attachment) {
            if (terminal
                    || !completed.add(attachment)
                    || completed.size() < draft.getAttachments().size()) {
                return;
            }
            preparationComplete = true;
            if (!keepsPlaceholderUntilPublishedGroup()) {
                terminal = true;
                removeOutgoingMediaPreparingSession(this);
            }
        }

        private void onAttachmentFailed(
                @Nullable final Attachment attachment, final int errorCode) {
            if (terminal) {
                return;
            }
            terminal = true;
            removeOutgoingMediaPreparingSession(this);
            restoreFailedMediaDraft(draft);
            if (attachment != null && errorCode != 0 && mediaPreviewAdapter != null) {
                mediaPreviewAdapter.markPreparationError(attachment, errorCode);
            }
        }
    }

    private String lastMessageUuid = null;
    private Conversation conversation;

    private static final class VisualScrollAnchor {
        @Nullable final String messageUuid;
        final int topOffsetPx;
        final int fallbackPosition;
        @Nullable final String unreadAnchorUuid;

        private VisualScrollAnchor(
                @Nullable final String messageUuid,
                final int topOffsetPx,
                final int fallbackPosition,
                @Nullable final String unreadAnchorUuid) {
            this.messageUuid = messageUuid;
            this.topOffsetPx = topOffsetPx;
            this.fallbackPosition = fallbackPosition;
            this.unreadAnchorUuid = unreadAnchorUuid;
        }
    }

    private final Runnable finishAttachmentViewportTransactionRunnable =
            () -> {
                if (attachmentViewportFinishPending && !attachmentViewportLaunchPending) {
                    finishAttachmentViewportTransaction(true);
                }
            };

    private boolean hasAttachmentViewportTransaction() {
        return binding != null
                && conversation != null
                && attachmentViewportConversationUuid != null
                && TextUtils.equals(attachmentViewportConversationUuid, conversation.getUuid());
    }

    private void beginAttachmentViewportTransaction() {
        if (binding == null || conversation == null || hasAttachmentViewportTransaction()) {
            return;
        }
        attachmentViewportConversationUuid = conversation.getUuid();
        attachmentViewportKeepBottomPinned =
                !conversation.isInHistoryPart()
                        && (isAutomaticBottomPinActive() || scrolledToBottom());
        attachmentViewportAnchor =
                attachmentViewportKeepBottomPinned ? null : captureVisualScrollAnchor();
        attachmentViewportLaunchPending = false;
        attachmentViewportFinishPending = false;
    }

    private void finishAttachmentViewportTransaction(final boolean restoreViewport) {
        if (binding != null) {
            binding.getRoot().removeCallbacks(finishAttachmentViewportTransactionRunnable);
        }
        final String transactionConversationUuid = attachmentViewportConversationUuid;
        final VisualScrollAnchor transactionAnchor = attachmentViewportAnchor;
        final boolean keepBottomPinned = attachmentViewportKeepBottomPinned;

        attachmentViewportConversationUuid = null;
        attachmentViewportAnchor = null;
        attachmentViewportKeepBottomPinned = false;
        attachmentViewportLaunchPending = false;
        attachmentViewportFinishPending = false;

        if (!restoreViewport
                || binding == null
                || conversation == null
                || !TextUtils.equals(transactionConversationUuid, conversation.getUuid())) {
            return;
        }
        if (keepBottomPinned) {
            binding.messagesView.post(() -> keepLatestPinnedAfterRefresh(false));
        } else if (transactionAnchor != null) {
            binding.messagesView.post(() -> scheduleChromePaddingSettle(transactionAnchor, false));
        }
    }

    private void finishAttachmentViewportTransactionAfterLayout() {
        attachmentViewportLaunchPending = false;
        attachmentViewportFinishPending = true;
        if (binding == null) {
            finishAttachmentViewportTransaction(false);
            return;
        }
        binding.textsend.post(
                () -> {
                    if (!hasAttachmentViewportTransaction()) {
                        return;
                    }
                    if (attachmentViewportKeepBottomPinned) {
                        keepLatestPinnedAfterRefresh(false);
                    } else if (attachmentViewportAnchor != null) {
                        scheduleChromePaddingSettle(attachmentViewportAnchor, false);
                    }
                    scheduleAttachmentViewportTransactionFinish();
                });
    }

    private void scheduleAttachmentViewportTransactionFinish() {
        if (binding == null
                || !hasAttachmentViewportTransaction()
                || !attachmentViewportFinishPending) {
            return;
        }
        final View root = binding.getRoot();
        root.removeCallbacks(finishAttachmentViewportTransactionRunnable);
        root.postDelayed(finishAttachmentViewportTransactionRunnable, IME_RESIZE_SETTLE_MS + 80L);
    }

    private final Runnable finishImeResizeRunnable =
            () -> {
                imeResizeInProgress = false;
                if (binding == null || conversation == null) {
                    clearImeResizeScrollState();
                    return;
                }

                if (programmaticBottomPin) {
                    // Explicit jump-to-latest owns positioning until its easing settles.
                    clearImeResizeScrollState();
                    return;
                }

                if (pinBottomDuringImeResize
                        && !conversation.isInHistoryPart()
                        && binding.messagesView.getCount() > 0) {
                    nudgeLatestToProtectedBottom(binding.messagesView);
                } else if (imeResizeScrollAnchor != null) {
                    restoreVisualScrollAnchor(imeResizeScrollAnchor);
                }

                clearImeResizeScrollState();
                toggleScrollDownButton();
            };
    private ConversationsActivity activity;
    private Vibrator vibrator;
    private boolean reInitRequiredOnStart = true;

    private static final long VOICE_RECORDING_MIN_DURATION_MS = 350L;
    private static final float VOICE_RECORDING_CANCEL_DISTANCE_DP = 96f;
    private static final float VOICE_RECORDING_LOCK_DISTANCE_DP = 72f;
    private static final float VOICE_RECORDING_LOCK_HINT_OFFSET_DP = 0f;
    private final Handler voiceRecordingHandler = new Handler(Looper.getMainLooper());
    private VoiceRecorder voiceRecorder;
    private float voiceRecordingStartX;
    private float voiceRecordingStartY;
    private boolean voiceRecordingCancelArmed;
    private boolean voiceRecordingLocked;
    private boolean voiceRecordingLockedFingerReleased;
    private boolean voiceRecordingActive;
    private ColorStateList voiceSendButtonDefaultBackgroundTint;
    private final Runnable voiceRecordingTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!voiceRecordingActive || voiceRecorder == null || binding == null) {
                        return;
                    }
                    final long elapsed = voiceRecorder.getElapsedMillis();
                    binding.voiceRecordingTimer.setText(
                            TimeFrameUtils.formatElapsedTime(elapsed, false));
                    binding.voiceRecordingDot.setAlpha(((elapsed / 600L) % 2L) == 0L ? 1f : 0.35f);
                    voiceRecordingHandler.postDelayed(this, 200L);
                }
            };

    @ColorInt private int primaryColor = -1;

    private Message previousClickedReply = null;

    @Nullable private String pendingSelectionUuid = null;

    private ActionMode selectionActionMode;
    private final OnClickListener clickToMuc =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    ConferenceDetailsActivity.open(getActivity(), conversation);
                }
            };
    private final OnClickListener leaveMuc =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    activity.xmppConnectionService.archiveConversation(conversation);
                }
            };
    private final OnClickListener joinMuc =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    activity.xmppConnectionService.joinMucExplicitly(conversation);
                }
            };

    private final OnClickListener acceptJoin =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    conversation.setAttribute("accept_non_anonymous", true);
                    activity.xmppConnectionService.updateConversation(conversation);
                    activity.xmppConnectionService.joinMucExplicitly(conversation);
                }
            };

    private final OnClickListener enterPassword =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    MucOptions muc = conversation.getMucOptions();
                    String password = muc.getPassword();
                    if (password == null) {
                        password = "";
                    }
                    activity.quickPasswordEdit(
                            password,
                            value -> {
                                activity.xmppConnectionService.providePasswordForMuc(
                                        conversation, value);
                                return null;
                            });
                }
            };
    private final OnScrollListener mOnScrollListener =
            new OnScrollListener() {

                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                        userScrollControlsFollowLatest = true;
                        pendingSelectionUuid = null;
                        cancelAutomaticBottomPin();
                    } else if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                            && userScrollControlsFollowLatest) {
                        followLatestMessages =
                                conversation != null
                                        && !conversation.isInHistoryPart()
                                        && scrolledToBottom(view);
                        userScrollControlsFollowLatest = false;
                    }
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                            && deferredTimelineRefreshWhileReading
                            && conversation != null
                            && !conversation.isInHistoryPart()
                            && scrolledToBottom(view)) {
                        // Reaching the end of the currently rendered snapshot is an explicit
                        // reader action. Catch the adapter up to the background model only now,
                        // so incoming/status/presentation work cannot repaint the page mid-read.
                        view.post(
                                () -> {
                                    if (binding != null
                                            && deferredTimelineRefreshWhileReading
                                            && conversation != null
                                            && !conversation.isInHistoryPart()
                                            && scrolledToBottom(binding.messagesView)) {
                                        deferredTimelineRefreshWhileReading = false;
                                        refresh(false);
                                    }
                                });
                    }
                    stickyDateScrolling =
                            AbsListView.OnScrollListener.SCROLL_STATE_IDLE != scrollState;
                    if (!stickyDateScrolling && pendingHistoryPageUiPublish != null) {
                        final Runnable pendingPublish = pendingHistoryPageUiPublish;
                        pendingHistoryPageUiPublish = null;
                        view.post(pendingPublish);
                    }
                    if (stickyDateScrolling) {
                        if (binding != null) {
                            binding.messageDateOverlay.removeCallbacks(hideStickyDateRunnable);
                        }
                        updateStickyDateOverlay(view.getFirstVisiblePosition());
                    } else {
                        fireReadEvent();
                        scheduleStickyDateOverlayHide();
                    }
                }

                @Override
                public void onScroll(
                        final AbsListView view,
                        int firstVisibleItem,
                        int visibleItemCount,
                        int totalItemCount) {
                    if (userScrollControlsFollowLatest
                            && conversation != null
                            && !conversation.isInHistoryPart()) {
                        followLatestMessages = scrolledToBottom(view);
                    }
                    toggleScrollDownButton(view);
                    if (stickyDateScrolling) {
                        updateStickyDateOverlay(firstVisibleItem);
                    }
                    synchronized (ConversationFragment.this.messageList) {
                        final boolean nearBackwardHistoryEdge =
                                firstVisibleItem < BACKWARD_HISTORY_TRIGGER_ITEMS;
                        if (!nearBackwardHistoryEdge) {
                            // Rearm only after the viewport has genuinely left the trigger zone.
                            // Adapter/layout callbacks produced by a prepend must not immediately
                            // start another page from the same user gesture.
                            backwardHistoryPaginationArmed = true;
                        }
                        final boolean paginateBackward =
                                nearBackwardHistoryEdge
                                        && backwardHistoryPaginationArmed
                                        && conversation != null
                                        && !messageList.isEmpty()
                                        && conversation.messagesLoaded.get();
                        if (paginateBackward) {
                            backwardHistoryPaginationArmed = false;
                        }
                        final boolean paginationForward =
                                conversation != null
                                        && conversation.isInHistoryPart()
                                        && firstVisibleItem
                                                        + visibleItemCount
                                                        + BACKWARD_HISTORY_TRIGGER_ITEMS
                                                > totalItemCount;
                        loadMoreMessages(paginateBackward, paginationForward, view);
                    }
                }
            };

    private void loadMoreMessages(
            boolean paginateBackward, boolean paginationForward, AbsListView view) {
        if (paginateBackward && (conversation != null && !conversation.messagesLoaded.get())) {
            paginateBackward = false;
        }

        if (conversation != null
                && messageList.size() > 0
                && ((paginateBackward && conversation.messagesLoaded.compareAndSet(true, false))
                        || (paginationForward
                                && conversation.historyPartLoadedForward.compareAndSet(
                                        true, false)))) {
            long timestamp;

            if (paginateBackward) {
                if (messageList.get(0).getType() == Message.TYPE_STATUS
                        && messageList.size() >= 2) {
                    timestamp = messageList.get(1).getTimeSent();
                } else {
                    timestamp = messageList.get(0).getTimeSent();
                }
            } else {
                if (messageList.get(messageList.size() - 1).getType() == Message.TYPE_STATUS
                        && messageList.size() >= 2) {
                    timestamp = messageList.get(messageList.size() - 2).getTimeSent();
                } else {
                    timestamp = messageList.get(messageList.size() - 1).getTimeSent();
                }
            }

            boolean finalPaginateBackward = paginateBackward;
            final String selectionTargetUuid = pendingSelectionUuid;
            activity.xmppConnectionService.loadMoreMessages(
                    conversation,
                    timestamp,
                    !paginateBackward,
                    new XmppConnectionService.OnMoreMessagesLoaded() {
                        @Override
                        public void onMoreMessagesLoaded(
                                final int c, final Conversation conversation) {
                            if (ConversationFragment.this.conversation != conversation) {
                                conversation.messagesLoaded.set(true);
                                return;
                            }
                            runOnUiThread(
                                    () -> {
                                        final Runnable publishHistoryPage =
                                                () ->
                                                        publishLoadedHistoryPage(
                                                                conversation,
                                                                c,
                                                                finalPaginateBackward,
                                                                paginationForward,
                                                                selectionTargetUuid);
                                        if (finalPaginateBackward && stickyDateScrolling) {
                                            // MAM can finish while the finger/fling still owns the
                                            // ListView. Publishing a prepended page at that moment
                                            // forces a full adapter rebind under the gesture and
                                            // produces the visible "blink". Keep the model update
                                            // in the background and publish only once scrolling is
                                            // idle.
                                            pendingHistoryPageUiPublish = publishHistoryPage;
                                        } else {
                                            publishHistoryPage.run();
                                        }
                                    });
                        }
                    });
        }
    }

    private void publishLoadedHistoryPage(
            final Conversation loadedConversation,
            final int loadedCount,
            final boolean paginateBackward,
            final boolean paginationForward,
            @Nullable final String selectionTargetUuid) {
        if (binding == null
                || messageListAdapter == null
                || ConversationFragment.this.conversation != loadedConversation) {
            loadedConversation.messagesLoaded.set(true);
            loadedConversation.historyPartLoadedForward.set(true);
            return;
        }
        if (loadedCount <= 0) {
            // Reaching the archive boundary changes only pagination state. There are no message
            // rows to publish, so rebuilding the adapter would be a pure visual blink.
            renderedTimelineRevision = loadedConversation.getTimelineRevision();
            renderedDynamicTimelineSignature = dynamicTimelineSignature(loadedConversation);
            renderedConversationPresentationRevision =
                    activity.xmppConnectionService.getConversationPresentationRevision();
            renderedTimelineConversationUuid = loadedConversation.getUuid();
            forceTimelineRebuild = false;
            if (!paginateBackward) {
                loadedConversation.historyPartLoadedForward.set(true);
            } else {
                loadedConversation.messagesLoaded.set(true);
            }
            Log.d(Config.LOGTAG, "timeline-refresh: reason=mam-empty policy=NO_REBIND");
            binding.messagesView.post(this::toggleScrollDownButton);
            return;
        }
        synchronized (messageList) {
            final VisualScrollAnchor preservedScrollAnchor = captureVisualScrollAnchor();
            loadedConversation.populateWithMessages(messageList);
            appendOutgoingMediaPreparingMessages();
            try {
                updateStatusMessages();
            } catch (IllegalStateException e) {
                Log.d(
                        Config.LOGTAG,
                        "caught illegal state exception while updating status messages");
            }

            updateMediaGalleryPresentation();
            Log.d(
                    Config.LOGTAG,
                    "timeline-refresh: reason=mam revisionBefore="
                            + renderedTimelineRevision
                            + " revisionAfter="
                            + loadedConversation.getTimelineRevision()
                            + " policy=PRESERVE_ANCHOR_PRE_LAYOUT anchorUuid="
                            + (preservedScrollAnchor == null
                                    ? null
                                    : preservedScrollAnchor.messageUuid)
                            + " anchorOffset="
                            + (preservedScrollAnchor == null
                                    ? 0
                                    : preservedScrollAnchor.topOffsetPx)
                            + " fullRebuild=true");
            messageListAdapter.notifyDataSetChanged();
            renderedTimelineRevision = loadedConversation.getTimelineRevision();
            renderedDynamicTimelineSignature = dynamicTimelineSignature(loadedConversation);
            renderedConversationPresentationRevision =
                    activity.xmppConnectionService.getConversationPresentationRevision();
            renderedTimelineConversationUuid = loadedConversation.getUuid();
            forceTimelineRebuild = false;

            if (paginationForward
                    && selectionTargetUuid != null
                    && selectionTargetUuid.equals(pendingSelectionUuid)
                    && getIndexOfExtended(selectionTargetUuid, messageList) != -1) {
                // Explicit jump/search navigation owns the viewport.
                binding.messagesView.post(() -> centerMessageInViewport(selectionTargetUuid, null));
            } else if (paginateBackward && preservedScrollAnchor != null) {
                // Set the restored selection before ListView performs the layout caused by
                // notifyDataSetChanged(). Posting this used to expose one intermediate frame with
                // prepended rows, which looked like the chat blinking while reading.
                restoreVisualScrollAnchorBeforeLayout(preservedScrollAnchor);
            } else if (preservedScrollAnchor != null) {
                binding.messagesView.post(() -> restoreVisualScrollAnchor(preservedScrollAnchor));
            } else {
                binding.messagesView.post(this::toggleScrollDownButton);
            }

            if (!paginateBackward) {
                loadedConversation.historyPartLoadedForward.set(true);
            } else {
                loadedConversation.messagesLoaded.set(true);
            }
        }
    }

    private void restoreVisualScrollAnchorBeforeLayout(@Nullable final VisualScrollAnchor anchor) {
        if (anchor == null || binding == null) {
            return;
        }
        int position =
                anchor.messageUuid == null
                        ? -1
                        : getIndexOfExtended(anchor.messageUuid, messageList);
        if (position < 0) {
            position = Math.min(anchor.fallbackPosition, Math.max(0, messageList.size() - 1));
        }
        if (position < 0) {
            return;
        }
        followLatestMessages = false;
        userScrollControlsFollowLatest = false;
        lastMessageUuid = anchor.unreadAnchorUuid;
        binding.messagesView.setSelectionFromTop(position, anchor.topOffsetPx);
        binding.messagesView.post(this::toggleScrollDownButton);
    }

    private final EditMessage.OnCommitContentListener mEditorContentListener =
            new EditMessage.OnCommitContentListener() {
                @Override
                public boolean onCommitContent(
                        InputContentInfoCompat inputContentInfo,
                        int flags,
                        Bundle opts,
                        String[] contentMimeTypes) {
                    // try to get permission to read the image, if applicable
                    if ((flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION)
                            != 0) {
                        try {
                            inputContentInfo.requestPermission();
                        } catch (Exception e) {
                            Log.e(
                                    Config.LOGTAG,
                                    "InputContentInfoCompat#requestPermission() failed.",
                                    e);
                            Toast.makeText(
                                            getActivity(),
                                            activity.getString(
                                                    R.string.no_permission_to_access_x,
                                                    inputContentInfo.getDescription()),
                                            Toast.LENGTH_LONG)
                                    .show();
                            return false;
                        }
                    }
                    if (hasPermissions(
                            REQUEST_ADD_EDITOR_CONTENT,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        attachEditorContentToConversation(inputContentInfo.getContentUri());
                    } else {
                        mPendingEditorContent = inputContentInfo.getContentUri();
                    }
                    return true;
                }
            };
    private Message selectedMessage;
    private ArrayList<Message> selectedMessages = new ArrayList<>();
    private final OnClickListener mEnableAccountListener =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    final Account account = conversation == null ? null : conversation.getAccount();
                    if (account != null) {
                        account.setOption(Account.OPTION_SOFT_DISABLED, false);
                        account.setOption(Account.OPTION_DISABLED, false);
                        activity.xmppConnectionService.updateAccount(account);
                    }
                }
            };
    private final OnClickListener mUnblockClickListener =
            new OnClickListener() {
                @Override
                public void onClick(final View v) {
                    v.post(() -> v.setVisibility(View.INVISIBLE));
                    if (conversation.isDomainBlocked()) {
                        BlockContactDialog.show(activity, conversation);
                    } else {
                        unblockConversation(conversation);
                    }
                }
            };

    private final OnEditorActionListener mEditorActionListener =
            (v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    InputMethodManager imm =
                            (InputMethodManager)
                                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null && imm.isFullscreenMode()) {
                        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    }
                    sendMessage();
                    return true;
                } else {
                    return false;
                }
            };
    private final OnClickListener mScrollButtonListener =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    stopScrolling();

                    /*if (previousClickedReply != null) {
                        int lastVisiblePosition = binding.messagesView.getLastVisiblePosition();
                        Message lastVisibleMessage = messageListAdapter.getItem(lastVisiblePosition);
                        Message jump = previousClickedReply;
                        previousClickedReply = null;
                        if (lastVisibleMessage != null) {
                            if (jump.getMergedTimeSent() > lastVisibleMessage.getMergedTimeSent()) {
                                Runnable postSelectionRunnable = () -> highlightMessage(jump.getUuid());
                                updateSelection(jump.getUuid(), postSelectionRunnable, false, false);
                                return;
                            }
                        }
                    }*/

                    scrollToLatest();
                }
            };

    @SuppressLint("ClickableViewAccessibility")
    private final View.OnTouchListener mSendButtonTouchListener =
            (view, event) -> {
                // Once a recording is locked, a fresh tap on the same button is the send action.
                // Returning false on ACTION_DOWN lets MaterialButton dispatch its normal click
                // listener.
                if (voiceRecordingActive
                        && voiceRecordingLocked
                        && voiceRecordingLockedFingerReleased) {
                    return false;
                }

                final Object tag = view.getTag();
                if (!(tag instanceof SendButtonAction) || tag != SendButtonAction.RECORD_VOICE) {
                    return false;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (conversation == null || activity == null || binding == null) {
                            return true;
                        }
                        if (!hasPermissions(
                                ATTACHMENT_CHOICE_RECORD_VOICE, Manifest.permission.RECORD_AUDIO)) {
                            return true;
                        }
                        if (trustKeysIfNeeded(conversation, REQUEST_TRUST_KEYS_ATTACHMENTS)) {
                            return true;
                        }
                        voiceRecordingStartX = event.getRawX();
                        voiceRecordingStartY = event.getRawY();
                        voiceRecordingCancelArmed = false;
                        voiceRecordingLocked = false;
                        voiceRecordingLockedFingerReleased = false;
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        startInlineVoiceRecording();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!voiceRecordingActive) {
                            return true;
                        }
                        if (voiceRecordingLocked) {
                            return true;
                        }

                        final float density = getResources().getDisplayMetrics().density;
                        final float cancelDistancePx = VOICE_RECORDING_CANCEL_DISTANCE_DP * density;
                        final float lockDistancePx = VOICE_RECORDING_LOCK_DISTANCE_DP * density;
                        final float draggedLeft = voiceRecordingStartX - event.getRawX();
                        final float draggedUp = voiceRecordingStartY - event.getRawY();

                        // Vertical gesture wins when it clearly crosses the lock threshold and the
                        // user is not already far into the horizontal cancel gesture.
                        if (draggedUp >= lockDistancePx && draggedLeft < cancelDistancePx * 0.55f) {
                            lockInlineVoiceRecording();
                            return true;
                        }

                        final boolean shouldCancel = draggedLeft >= cancelDistancePx;
                        if (shouldCancel != voiceRecordingCancelArmed) {
                            voiceRecordingCancelArmed = shouldCancel;
                            if (shouldCancel) {
                                performVoiceHaptic();
                            }
                        }
                        updateInlineVoiceGestureUi(
                                draggedLeft, cancelDistancePx, draggedUp, lockDistancePx);
                        return true;

                    case MotionEvent.ACTION_UP:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        if (voiceRecordingActive && !voiceRecordingLocked) {
                            if (voiceRecordingCancelArmed) {
                                cancelInlineVoiceRecording();
                            } else {
                                finishInlineVoiceRecording();
                            }
                        } else if (voiceRecordingActive && voiceRecordingLocked) {
                            // The original hold gesture has ended. Future taps can now be handled
                            // as regular MaterialButton clicks and send the locked recording.
                            voiceRecordingLockedFingerReleased = true;
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        if (voiceRecordingActive && !voiceRecordingLocked) {
                            cancelInlineVoiceRecording();
                        } else if (voiceRecordingActive && voiceRecordingLocked) {
                            voiceRecordingLockedFingerReleased = true;
                        }
                        return true;

                    default:
                        return true;
                }
            };

    private void startInlineVoiceRecording() {
        final Context context = getContext();
        final Conversation targetConversation = conversation;
        if (context == null
                || binding == null
                || voiceRecordingActive
                || targetConversation == null) {
            return;
        }
        if (isCorrectingMessage()
                || (mediaPreviewAdapter != null && mediaPreviewAdapter.hasAttachments())) {
            showIncompatibleComposerMode();
            return;
        }
        final VoiceRecorder recorder = new VoiceRecorder(context);
        if (!recorder.start()) {
            Toast.makeText(context, R.string.unable_to_start_recording, Toast.LENGTH_SHORT).show();
            return;
        }
        if (VoiceRecordingSession.start(
                        targetConversation.getUuid(), targetConversation.getReplyTo(), recorder)
                == null) {
            recorder.cancel();
            Toast.makeText(context, R.string.unable_to_start_recording, Toast.LENGTH_SHORT).show();
            return;
        }
        voiceRecorder = recorder;
        voiceRecordingActive = true;
        voiceRecordingCancelArmed = false;
        voiceRecordingLocked = false;
        voiceRecordingLockedFingerReleased = false;
        binding.textAttachButton.setVisibility(View.GONE);
        binding.inputLayout.setVisibility(View.GONE);
        binding.voiceRecordingPanel.setVisibility(View.VISIBLE);
        binding.voiceRecordingTimer.setText("0:00");
        binding.voiceRecordingCancelHint.setText(R.string.slide_left_to_cancel);
        binding.voiceRecordingCancelHint.setTranslationX(0f);
        binding.voiceRecordingLockHint.setVisibility(View.VISIBLE);
        binding.voiceRecordingLockHint.setImageResource(R.drawable.ic_lock_open_outline_24dp);
        resetVoiceRecordingGesturePresentation();
        binding.voiceRecordingCancelButton.setVisibility(View.GONE);
        setComposerFormattingToolbarVisible(false, true);
        if (voiceSendButtonDefaultBackgroundTint == null) {
            voiceSendButtonDefaultBackgroundTint = binding.textSendButton.getBackgroundTintList();
        }
        updateVoiceRecordingSendButton();
        showVoiceRecordingMicHalo();
        performVoiceHaptic();
        voiceRecordingHandler.removeCallbacks(voiceRecordingTick);
        voiceRecordingHandler.post(voiceRecordingTick);
    }

    private void updateInlineVoiceGestureUi(
            final float draggedLeft,
            final float cancelDistancePx,
            final float draggedUp,
            final float lockDistancePx) {
        if (binding == null || voiceRecordingLocked) {
            return;
        }
        final float cancelProgress = Math.max(0f, Math.min(1f, draggedLeft / cancelDistancePx));
        final float lockProgress = Math.max(0f, Math.min(1f, draggedUp / lockDistancePx));
        final boolean cancelDominant = cancelProgress > 0.1f && cancelProgress > lockProgress;
        final boolean lockDominant = lockProgress > 0.1f && lockProgress >= cancelProgress;
        final float cancelEmphasis = cancelDominant ? cancelProgress : cancelProgress * 0.35f;
        final float lockEmphasis = lockDominant ? lockProgress : lockProgress * 0.35f;

        binding.voiceRecordingCancelHint.setTranslationX(-cancelEmphasis * dpToPx(20));
        binding.voiceRecordingCancelHint.setScaleX(1f + (cancelEmphasis * 0.08f));
        binding.voiceRecordingCancelHint.setScaleY(1f + (cancelEmphasis * 0.08f));
        binding.voiceRecordingCancelHint.setAlpha(
                lockDominant ? 0.45f : 0.72f + (cancelEmphasis * 0.28f));
        binding.voiceRecordingCancelHint.setText(
                voiceRecordingCancelArmed
                        ? R.string.release_to_cancel_voice
                        : R.string.slide_left_to_cancel);

        final int onSurfaceVariant =
                MaterialColors.getColor(
                        binding.voiceRecordingCancelHint,
                        com.google.android.material.R.attr.colorOnSurfaceVariant);
        final int error =
                MaterialColors.getColor(
                        binding.voiceRecordingCancelHint,
                        com.google.android.material.R.attr.colorError);
        final float cancelErrorProgress =
                cancelDominant ? Math.max(0f, Math.min(1f, (cancelProgress - 0.55f) / 0.45f)) : 0f;
        binding.voiceRecordingCancelHint.setTextColor(
                blendVoiceRecordingColors(
                        onSurfaceVariant,
                        error,
                        voiceRecordingCancelArmed ? 1f : cancelErrorProgress));

        // Lock and cancel no longer compete visually: the dominant gesture gets the stronger
        // target.
        binding.voiceRecordingLockHint.setTranslationY(
                -dpToPx(VOICE_RECORDING_LOCK_HINT_OFFSET_DP + (lockEmphasis * 10f)));
        final float lockScale = 1f + (lockEmphasis * 0.15f);
        binding.voiceRecordingLockHint.setScaleX(lockScale);
        binding.voiceRecordingLockHint.setScaleY(lockScale);
        binding.voiceRecordingLockHint.setAlpha(
                cancelDominant ? 0.30f : 0.58f + (lockEmphasis * 0.42f));

        final int surface =
                MaterialColors.getColor(
                        binding.voiceRecordingLockHint,
                        com.google.android.material.R.attr.colorSurface);
        final int primaryContainer =
                MaterialColors.getColor(
                        binding.voiceRecordingLockHint,
                        com.google.android.material.R.attr.colorPrimaryContainer);
        final int primary =
                MaterialColors.getColor(
                        binding.voiceRecordingLockHint,
                        com.google.android.material.R.attr.colorPrimary);
        final int lockBackground =
                blendVoiceRecordingColors(surface, primaryContainer, lockEmphasis * 0.85f);
        final int lockIcon = blendVoiceRecordingColors(onSurfaceVariant, primary, lockEmphasis);
        ViewCompat.setBackgroundTintList(
                binding.voiceRecordingLockHint, ColorStateList.valueOf(lockBackground));
        binding.voiceRecordingLockHint.setImageTintList(ColorStateList.valueOf(lockIcon));

        updateVoiceRecordingMicCancelTint(voiceRecordingCancelArmed ? 1f : cancelErrorProgress);
    }

    private void lockInlineVoiceRecording() {
        if (binding == null || !voiceRecordingActive || voiceRecordingLocked) {
            return;
        }
        voiceRecordingLocked = true;
        voiceRecordingLockedFingerReleased = false;
        voiceRecordingCancelArmed = false;
        if (conversation != null) {
            final VoiceRecordingSession.Session session =
                    VoiceRecordingSession.getForConversation(conversation.getUuid());
            if (session != null) {
                session.setLocked(true);
            }
        }
        performVoiceHaptic();

        binding.voiceRecordingCancelHint.setTranslationX(0f);
        binding.voiceRecordingCancelHint.setText(R.string.voice_recording_locked);
        binding.voiceRecordingLockHint.setImageResource(R.drawable.ic_lock_24dp);
        applyLockedVoiceRecordingPresentation(true);
        hideVoiceRecordingMicHalo(true);
        updateVoiceRecordingSendButton();
    }

    private void updateVoiceRecordingSendButton() {
        if (binding == null) {
            return;
        }
        binding.textSendButton.setTag(SendButtonAction.RECORD_VOICE);
        binding.textSendButton.setIconResource(
                voiceRecordingLocked ? R.drawable.ic_send_24dp : R.drawable.ic_mic_24dp);
        binding.textSendButton.setContentDescription(
                getString(
                        voiceRecordingLocked
                                ? R.string.send_locked_voice_recording
                                : R.string.release_to_send_voice));
        binding.textSendButton.setBackgroundTintList(
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                binding.textSendButton,
                                com.google.android.material.R.attr.colorPrimary)));
        binding.textSendButton.setIconTint(
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                binding.textSendButton,
                                com.google.android.material.R.attr.colorOnPrimary)));
    }

    private void showVoiceRecordingMicHalo() {
        if (binding == null) {
            return;
        }
        final View halo = binding.voiceRecordingMicHalo;
        final int primary =
                MaterialColors.getColor(halo, com.google.android.material.R.attr.colorPrimary);
        ViewCompat.setBackgroundTintList(halo, ColorStateList.valueOf(primary));
        halo.animate().cancel();
        halo.animate().setListener(null);
        alignVoiceRecordingMicHaloToButton();
        halo.setVisibility(View.VISIBLE);
        halo.setAlpha(0f);
        halo.setScaleX(0.48f);
        halo.setScaleY(0.48f);
        halo.animate().alpha(0.12f).scaleX(1f).scaleY(1f).setDuration(140L).start();

        // Recording mode can trigger a composer relayout after ACTION_DOWN. Re-anchor once the
        // layout has settled so the halo follows the actual mic-button center, not inferred
        // margins.
        halo.post(this::alignVoiceRecordingMicHaloToButton);
    }

    private void alignVoiceRecordingMicHaloToButton() {
        if (binding == null) {
            return;
        }
        final View halo = binding.voiceRecordingMicHalo;
        final View button = binding.textSendButton;
        final ViewParent parent = halo.getParent();
        if (!(parent instanceof View) || button.getWidth() <= 0 || button.getHeight() <= 0) {
            return;
        }

        final View haloParent = (View) parent;
        final int[] buttonLocation = new int[2];
        final int[] parentLocation = new int[2];
        button.getLocationInWindow(buttonLocation);
        haloParent.getLocationInWindow(parentLocation);

        final int haloWidth = halo.getWidth() > 0 ? halo.getWidth() : halo.getLayoutParams().width;
        final int haloHeight =
                halo.getHeight() > 0 ? halo.getHeight() : halo.getLayoutParams().height;
        if (haloWidth <= 0 || haloHeight <= 0) {
            return;
        }

        final float buttonCenterX =
                buttonLocation[0] - parentLocation[0] + (button.getWidth() / 2f);
        final float buttonCenterY =
                buttonLocation[1] - parentLocation[1] + (button.getHeight() / 2f);
        halo.setX(buttonCenterX - (haloWidth / 2f));
        halo.setY(buttonCenterY - (haloHeight / 2f));
    }

    private void hideVoiceRecordingMicHalo(final boolean animate) {
        if (binding == null) {
            return;
        }
        final View halo = binding.voiceRecordingMicHalo;
        halo.animate().cancel();
        halo.animate().setListener(null);
        if (!animate) {
            halo.setVisibility(View.GONE);
            halo.setAlpha(0f);
            halo.setScaleX(0.48f);
            halo.setScaleY(0.48f);
            return;
        }
        halo.animate()
                .alpha(0f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(100L)
                .setListener(
                        new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(final Animator animation) {
                                halo.setVisibility(View.GONE);
                                halo.animate().setListener(null);
                            }
                        })
                .start();
    }

    private void resetVoiceRecordingGesturePresentation() {
        if (binding == null) {
            return;
        }
        final int onSurfaceVariant =
                MaterialColors.getColor(
                        binding.voiceRecordingCancelHint,
                        com.google.android.material.R.attr.colorOnSurfaceVariant);
        binding.voiceRecordingCancelHint.setAlpha(0.72f);
        binding.voiceRecordingCancelHint.setScaleX(1f);
        binding.voiceRecordingCancelHint.setScaleY(1f);
        binding.voiceRecordingCancelHint.setTextColor(onSurfaceVariant);

        binding.voiceRecordingLockHint.animate().cancel();
        binding.voiceRecordingLockHint.setAlpha(0.58f);
        binding.voiceRecordingLockHint.setTranslationY(
                -dpToPx(VOICE_RECORDING_LOCK_HINT_OFFSET_DP));
        binding.voiceRecordingLockHint.setScaleX(1f);
        binding.voiceRecordingLockHint.setScaleY(1f);
        binding.voiceRecordingLockHint.setImageTintList(ColorStateList.valueOf(onSurfaceVariant));
        ViewCompat.setBackgroundTintList(binding.voiceRecordingLockHint, null);

        binding.voiceRecordingCancelButton.animate().cancel();
        binding.voiceRecordingCancelButton.setAlpha(1f);
        binding.voiceRecordingCancelButton.setScaleX(1f);
        binding.voiceRecordingCancelButton.setScaleY(1f);
    }

    private void applyLockedVoiceRecordingPresentation(final boolean animateCancelButton) {
        if (binding == null) {
            return;
        }
        final int onSurface =
                MaterialColors.getColor(
                        binding.voiceRecordingCancelHint,
                        com.google.android.material.R.attr.colorOnSurface);
        final int primaryContainer =
                MaterialColors.getColor(
                        binding.voiceRecordingLockHint,
                        com.google.android.material.R.attr.colorPrimaryContainer);
        final int onPrimaryContainer =
                MaterialColors.getColor(
                        binding.voiceRecordingLockHint,
                        com.google.android.material.R.attr.colorOnPrimaryContainer);

        binding.voiceRecordingCancelHint.setAlpha(1f);
        binding.voiceRecordingCancelHint.setScaleX(1f);
        binding.voiceRecordingCancelHint.setScaleY(1f);
        binding.voiceRecordingCancelHint.setTextColor(onSurface);

        final View lockHint = binding.voiceRecordingLockHint;
        ViewCompat.setBackgroundTintList(lockHint, ColorStateList.valueOf(primaryContainer));
        binding.voiceRecordingLockHint.setImageTintList(ColorStateList.valueOf(onPrimaryContainer));
        lockHint.animate().cancel();
        if (animateCancelButton) {
            lockHint.setAlpha(1f);
            lockHint.setVisibility(View.VISIBLE);
            lockHint.animate()
                    .translationY(-dpToPx(VOICE_RECORDING_LOCK_HINT_OFFSET_DP))
                    .scaleX(1.12f)
                    .scaleY(1.12f)
                    .alpha(0f)
                    .setDuration(140L)
                    .withEndAction(
                            () -> {
                                lockHint.setVisibility(View.GONE);
                                lockHint.setAlpha(1f);
                                lockHint.setScaleX(1f);
                                lockHint.setScaleY(1f);
                            })
                    .start();
        } else {
            lockHint.setVisibility(View.GONE);
            lockHint.setAlpha(1f);
            lockHint.setScaleX(1f);
            lockHint.setScaleY(1f);
        }

        binding.voiceRecordingCancelButton.setVisibility(View.VISIBLE);
        binding.voiceRecordingCancelButton.animate().cancel();
        if (animateCancelButton) {
            binding.voiceRecordingCancelButton.setAlpha(0f);
            binding.voiceRecordingCancelButton.setScaleX(0.88f);
            binding.voiceRecordingCancelButton.setScaleY(0.88f);
            binding.voiceRecordingCancelButton
                    .animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start();
        } else {
            binding.voiceRecordingCancelButton.setAlpha(1f);
            binding.voiceRecordingCancelButton.setScaleX(1f);
            binding.voiceRecordingCancelButton.setScaleY(1f);
        }
    }

    private void updateVoiceRecordingMicCancelTint(final float cancelErrorProgress) {
        if (binding == null || voiceRecordingLocked) {
            return;
        }
        final float progress = Math.max(0f, Math.min(1f, cancelErrorProgress));
        final int primary =
                MaterialColors.getColor(
                        binding.textSendButton, com.google.android.material.R.attr.colorPrimary);
        final int onPrimary =
                MaterialColors.getColor(
                        binding.textSendButton, com.google.android.material.R.attr.colorOnPrimary);
        final int error =
                MaterialColors.getColor(
                        binding.textSendButton, com.google.android.material.R.attr.colorError);
        final int onError =
                MaterialColors.getColor(
                        binding.textSendButton, com.google.android.material.R.attr.colorOnError);
        final int background = blendVoiceRecordingColors(primary, error, progress);
        final int icon = blendVoiceRecordingColors(onPrimary, onError, progress);
        binding.textSendButton.setBackgroundTintList(ColorStateList.valueOf(background));
        binding.textSendButton.setIconTint(ColorStateList.valueOf(icon));
        ViewCompat.setBackgroundTintList(
                binding.voiceRecordingMicHalo, ColorStateList.valueOf(background));
        binding.voiceRecordingMicHalo.setAlpha(0.12f + (0.04f * progress));
    }

    @ColorInt
    private static int blendVoiceRecordingColors(
            @ColorInt final int from, @ColorInt final int to, final float fraction) {
        final float clamped = Math.max(0f, Math.min(1f, fraction));
        return Color.argb(
                Math.round(Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * clamped)),
                Math.round(Color.red(from) + ((Color.red(to) - Color.red(from)) * clamped)),
                Math.round(Color.green(from) + ((Color.green(to) - Color.green(from)) * clamped)),
                Math.round(Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * clamped)));
    }

    private void performVoiceHaptic() {
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(20L);
        }
    }

    private void finishInlineVoiceRecording() {
        final Conversation targetConversation = conversation;
        final VoiceRecordingSession.Session session =
                targetConversation == null
                        ? null
                        : VoiceRecordingSession.takeForConversation(targetConversation.getUuid());
        final VoiceRecorder recorder = session == null ? voiceRecorder : session.getRecorder();
        final Message replyTo =
                session == null
                        ? (targetConversation == null ? null : targetConversation.getReplyTo())
                        : session.getReplyTo();
        if (recorder == null || targetConversation == null) {
            cancelInlineVoiceRecording();
            return;
        }
        final long duration = recorder.getElapsedMillis();
        voiceRecorder = null;
        voiceRecordingActive = false;
        voiceRecordingHandler.removeCallbacks(voiceRecordingTick);
        resetInlineVoiceRecordingUi();

        if (duration < VOICE_RECORDING_MIN_DURATION_MS) {
            recorder.cancel();
            return;
        }

        recorder.finish(
                new VoiceRecorder.Callback() {
                    @Override
                    public void onFinished(
                            final Uri uri, final String mimeType, final long durationMs) {
                        sendRecordedVoice(targetConversation, uri, mimeType, replyTo, recorder);
                    }

                    @Override
                    public void onError() {
                        final Context context = getContext();
                        if (context != null) {
                            Toast.makeText(
                                            context,
                                            R.string.unable_to_save_recording,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    }
                });
    }

    private void cancelInlineVoiceRecording() {
        final Conversation targetConversation = conversation;
        final VoiceRecordingSession.Session session =
                targetConversation == null
                        ? null
                        : VoiceRecordingSession.takeForConversation(targetConversation.getUuid());
        final VoiceRecorder recorder = session == null ? voiceRecorder : session.getRecorder();
        voiceRecorder = null;
        voiceRecordingActive = false;
        voiceRecordingCancelArmed = false;
        voiceRecordingLocked = false;
        voiceRecordingLockedFingerReleased = false;
        voiceRecordingHandler.removeCallbacks(voiceRecordingTick);
        if (recorder != null) {
            recorder.cancel();
        }
        resetInlineVoiceRecordingUi();
    }

    private boolean shouldPreserveInlineVoiceRecordingForRecreation() {
        return voiceRecordingActive
                && voiceRecorder != null
                && activity != null
                && !activity.isFinishing()
                && !isRemoving();
    }

    private void detachInlineVoiceRecordingUiForRecreation() {
        voiceRecordingHandler.removeCallbacks(voiceRecordingTick);
        voiceRecorder = null;
        voiceRecordingActive = false;
        voiceRecordingCancelArmed = false;
        voiceRecordingLocked = false;
        voiceRecordingLockedFingerReleased = false;
    }

    private void restoreInlineVoiceRecordingUiIfNeeded() {
        if (binding == null || conversation == null || voiceRecordingActive) {
            return;
        }
        final VoiceRecordingSession.Session session =
                VoiceRecordingSession.getForConversation(conversation.getUuid());
        if (session == null || !session.getRecorder().isRecording()) {
            return;
        }

        // A recreated view has no valid continuation of the old hold gesture. Normalize an unlocked
        // session to the existing stable locked panel: the user can explicitly send or cancel,
        // while
        // the recorder itself keeps running and no synthetic ACTION_UP is generated.
        if (!session.isLocked()) {
            session.setLocked(true);
        }
        voiceRecorder = session.getRecorder();
        voiceRecordingActive = true;
        voiceRecordingCancelArmed = false;
        voiceRecordingLocked = true;
        voiceRecordingLockedFingerReleased = true;

        binding.textAttachButton.setVisibility(View.GONE);
        binding.inputLayout.setVisibility(View.GONE);
        binding.voiceRecordingPanel.setVisibility(View.VISIBLE);
        binding.voiceRecordingTimer.setText(
                TimeFrameUtils.formatElapsedTime(voiceRecorder.getElapsedMillis(), false));
        binding.voiceRecordingCancelHint.setTranslationX(0f);
        binding.voiceRecordingCancelHint.setText(R.string.voice_recording_locked);
        binding.voiceRecordingLockHint.setVisibility(View.VISIBLE);
        binding.voiceRecordingLockHint.setImageResource(R.drawable.ic_lock_24dp);
        applyLockedVoiceRecordingPresentation(false);
        hideVoiceRecordingMicHalo(false);
        if (voiceSendButtonDefaultBackgroundTint == null) {
            voiceSendButtonDefaultBackgroundTint = binding.textSendButton.getBackgroundTintList();
        }
        binding.textSendButton.setScaleX(1f);
        binding.textSendButton.setScaleY(1f);
        updateVoiceRecordingSendButton();
        voiceRecordingHandler.removeCallbacks(voiceRecordingTick);
        voiceRecordingHandler.post(voiceRecordingTick);
    }

    private void resetInlineVoiceRecordingUi() {
        if (binding == null) {
            return;
        }
        binding.voiceRecordingPanel.setVisibility(View.GONE);
        binding.voiceRecordingCancelHint.setTranslationX(0f);
        binding.voiceRecordingCancelHint.setText(R.string.slide_left_to_cancel);
        binding.voiceRecordingDot.setAlpha(1f);
        binding.voiceRecordingLockHint.setVisibility(View.GONE);
        binding.voiceRecordingLockHint.setImageResource(R.drawable.ic_lock_open_outline_24dp);
        resetVoiceRecordingGesturePresentation();
        binding.voiceRecordingCancelButton.setVisibility(View.GONE);
        hideVoiceRecordingMicHalo(false);
        voiceRecordingLocked = false;
        voiceRecordingLockedFingerReleased = false;
        binding.textAttachButton.setVisibility(View.VISIBLE);
        binding.inputLayout.setVisibility(View.VISIBLE);
        binding.textSendButton.animate().scaleX(1f).scaleY(1f).setDuration(100L).start();
        if (voiceSendButtonDefaultBackgroundTint != null) {
            binding.textSendButton.setBackgroundTintList(voiceSendButtonDefaultBackgroundTint);
        }
        updateSendButton();
        updateComposerFormattingToolbar();
    }

    private void sendRecordedVoice(
            final Conversation targetConversation,
            final Uri uri,
            final String mimeType,
            @Nullable final Message replyTo,
            final VoiceRecorder recorder) {
        if (activity == null || uri == null || targetConversation == null) {
            recorder.deleteOutputFile();
            return;
        }
        final List<Attachment> attachments =
                Attachment.of(activity, uri, Attachment.Type.RECORDING);
        final MediaDraftSnapshot draft =
                MediaDraftSnapshot.from("", attachments, targetConversation);
        final Attachment recordingAttachment = attachments.get(0);
        final PresenceSelector.OnPresenceSelected callback =
                () -> {
                    if (activity == null) {
                        recorder.deleteOutputFile();
                        return;
                    }
                    final boolean followOwnSend = shouldFollowOwnSend();
                    final OutgoingMediaPreparingSession preparingSession =
                            beginOutgoingMediaPreparingSession(draft);
                    if (followOwnSend) {
                        scrollToLatest();
                    }
                    attachRecordedVoiceToConversation(
                            targetConversation,
                            uri,
                            mimeType,
                            replyTo,
                            recorder::deleteOutputFile,
                            recorder::deleteOutputFile,
                            recordingAttachment,
                            preparingSession);
                };
        if (targetConversation.getMode() == Conversation.MODE_MULTI
                || Attachment.canBeSendInBand(attachments)
                || (targetConversation.getAccount().httpUploadAvailable()
                        && FileBackend.allFilesUnderSize(
                                activity, attachments, getMaxHttpUploadSize(targetConversation)))) {
            callback.onPresenceSelected();
        } else {
            activity.selectPresence(targetConversation, callback);
        }
    }

    private void attachRecordedVoiceToConversation(
            final Conversation targetConversation,
            final Uri uri,
            final String mimeType,
            @Nullable final Message replyTo,
            final SecureOutgoingVoiceStagingRetirer stagingRetirer,
            final Runnable cleanup,
            final Attachment recordingAttachment,
            @Nullable final OutgoingMediaPreparingSession preparingSession) {
        final ConversationsActivity targetActivity = activity;
        if (targetActivity == null) {
            cleanup.run();
            return;
        }
        targetActivity.delegateUriPermissionsToService(uri);
        targetActivity.xmppConnectionService.attachVoiceRecordingToConversation(
                targetConversation,
                uri,
                mimeType,
                new UiCallback<Message>() {
                    @Override
                    public void success(final Message message) {
                        completeOutgoingMediaPreparingAttachment(
                                preparingSession, recordingAttachment);
                        cleanup.run();
                        targetActivity.runOnUiThread(
                                () -> consumeReplyAfterSuccessfulSend(targetConversation, replyTo));
                    }

                    @Override
                    public void error(final int errorCode, final Message message) {
                        if (preparingSession != null) {
                            failOutgoingMediaPreparingSession(
                                    preparingSession, recordingAttachment, errorCode);
                        } else {
                            cleanup.run();
                        }
                    }

                    @Override
                    public void userInputRequired(final PendingIntent pi, final Message message) {
                        if (preparingSession != null) {
                            failOutgoingMediaPreparingSession(
                                    preparingSession, recordingAttachment, 0);
                        } else {
                            cleanup.run();
                        }
                    }
                },
                stagingRetirer);
    }

    private final View.OnLongClickListener mSendButtonLongClickListener =
            new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Object tag = v.getTag();

                    if (conversation.getNextEncryption() == Message.ENCRYPTION_OTR
                            || conversation.getNextEncryption() == Message.ENCRYPTION_PGP
                            || mediaPreviewAdapter.hasAttachments()) {
                        return false;
                    }

                    if (tag instanceof SendButtonAction) {
                        SendButtonAction action = (SendButtonAction) tag;
                        return switch (action) {
                            case TAKE_PHOTO,
                                            RECORD_VIDEO,
                                            SEND_LOCATION,
                                            RECORD_VOICE,
                                            CHOOSE_PICTURE,
                                            CANCEL ->
                                    false;
                            default -> {
                                sendMessageDelayed();
                                yield true;
                            }
                        };
                    } else {
                        sendMessageDelayed();
                        return true;
                    }
                }
            };

    private final OnClickListener mSendButtonListener =
            new OnClickListener() {

                @Override
                public void onClick(View v) {
                    if (voiceRecordingActive && voiceRecordingLocked) {
                        finishInlineVoiceRecording();
                        return;
                    }
                    Object tag = v.getTag();
                    if (tag instanceof SendButtonAction) {
                        SendButtonAction action = (SendButtonAction) tag;
                        switch (action) {
                            case TAKE_PHOTO:
                            case RECORD_VIDEO:
                            case SEND_LOCATION:
                            case CHOOSE_PICTURE:
                                attachFile(action.toChoice());
                                break;
                            case RECORD_VOICE:
                                Toast.makeText(
                                                getActivity(),
                                                R.string.hold_microphone_to_record,
                                                Toast.LENGTH_SHORT)
                                        .show();
                                break;
                            case CANCEL:
                                if (conversation != null) {
                                    if (conversation.setCorrectingMessage(null)) {
                                        binding.textinput.setText("");
                                        binding.textinput.append(conversation.getDraftMessage());
                                        conversation.setDraftMessage(null);
                                    } else if (conversation.getMode() == Conversation.MODE_MULTI) {
                                        binding.textinput.setText("");
                                    } else {
                                        binding.textinput.setText("");
                                    }
                                    updateChatMsgHint();
                                    updateSendButton();
                                    updateEditablity();
                                }
                                break;
                            default:
                                sendMessage();
                        }
                    } else {
                        sendMessage();
                    }
                }
            };

    private ActionMode.Callback actionModeCallback =
            new ActionMode.Callback() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    mode.getMenuInflater().inflate(R.menu.message_select_context, menu);

                    unregisterForContextMenu(binding.messagesView);

                    selectionActionMode = mode;

                    return true;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    updateSelectionActionVisibility(menu);
                    return true;
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    Collections.sort(
                            selectedMessages, Comparator.comparingLong(Message::getTimeSent));
                    if (item.getItemId() == R.id.copy_message) {
                        StringBuilder sb = new StringBuilder();

                        for (final Message m : selectedMessages) {
                            if (isMessageCopyable(m)) {
                                if (sb.length() > 0) {
                                    sb.append("\n\n");
                                }
                                sb.append(m.getAvatarName());
                                sb.append(", ");
                                sb.append(
                                        DateUtils.formatDateTime(
                                                activity,
                                                m.getTimeSent(),
                                                DateUtils.FORMAT_SHOW_TIME
                                                        | DateUtils.FORMAT_SHOW_DATE
                                                        | DateUtils.FORMAT_SHOW_YEAR
                                                        | DateUtils.FORMAT_ABBREV_MONTH));
                                sb.append(": ");
                                sb.append(m.getBodyForDisplaying());
                            }
                        }

                        ShareUtil.copyToClipboard(activity, sb);
                    } else if (item.getItemId() == R.id.share_message) {
                        ShareUtil.share(activity, selectedMessages);
                    } else if (item.getItemId() == R.id.delete_locally) {
                        deleteSelectedMessagesLocally();
                    }

                    if (selectionActionMode != null) {
                        selectionActionMode.finish();
                    }
                    return true;
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    selectionActionMode = null;
                    registerForContextMenu(binding.messagesView);
                    selectedMessages.clear();
                    messageListAdapter.notifyDataSetChanged();
                }
            };

    private void updateSelectionActionVisibility(final Menu menu) {
        final boolean hasSelection = !selectedMessages.isEmpty();
        menu.findItem(R.id.copy_message)
                .setVisible(
                        hasSelection
                                && selectedMessages.stream().anyMatch(this::isMessageCopyable));
        menu.findItem(R.id.share_message)
                .setVisible(
                        hasSelection
                                && selectedMessages.stream().allMatch(this::isMessageShareable));
        menu.findItem(R.id.delete_locally)
                .setVisible(
                        hasSelection
                                && selectedMessages.stream()
                                        .allMatch(
                                                message ->
                                                        message.getConversation()
                                                                instanceof Conversation));
    }

    private boolean isMessageCopyable(final Message message) {
        final boolean encrypted =
                message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED
                        || message.getEncryption() == Message.ENCRYPTION_PGP;
        return !message.isFileOrImage()
                && !encrypted
                && !message.isGeoUri()
                && !message.treatAsDownloadable()
                && !MessageUtils.unInitiatedButKnownSize(message)
                && message.getTransferable() == null
                && message.getBodyForDisplaying().length() > 0;
    }

    private boolean isMessageShareable(final Message message) {
        final Transferable transferable = message.getTransferable();
        final boolean receiving =
                message.getStatus() == Message.STATUS_RECEIVED
                        && (transferable instanceof JingleFileTransferConnection
                                || transferable instanceof HttpDownloadConnection);
        return (message.isFileOrImage() && !message.isDeleted() && !receiving)
                || (message.getType() == Message.TYPE_TEXT
                        && !message.treatAsDownloadable()
                        && !MessageUtils.unInitiatedButKnownSize(message)
                        && transferable == null);
    }

    private void deleteSelectedMessagesLocally() {
        for (final Message message : new ArrayList<>(selectedMessages)) {
            deleteLocally(message);
        }
    }

    private TabLayout.VisibilityChangeListener visibiltyChangeListener =
            new TabLayout.VisibilityChangeListener() {
                @Override
                public void onVisibilityChanged(int visibility) {
                    applyTabElevationFix(visibility == View.VISIBLE);
                }
            };

    private int completionIndex = 0;
    private int lastCompletionLength = 0;
    private String incomplete;
    private int lastCompletionCursor;
    private boolean firstWord = false;
    private Message mPendingDownloadableMessage;

    private ProgressDialog fetchHistoryDialog;

    private static ConversationFragment findConversationFragment(Activity activity) {
        Fragment fragment = activity.getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationFragment) {
            return (ConversationFragment) fragment;
        }
        fragment = activity.getFragmentManager().findFragmentById(R.id.secondary_fragment);
        if (fragment instanceof ConversationFragment) {
            return (ConversationFragment) fragment;
        }
        return null;
    }

    public static void startStopPending(Activity activity) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            fragment.messageListAdapter.startStopPending();
        }
    }

    public static void downloadFile(Activity activity, Message message) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            fragment.startDownloadable(message);
        }
    }

    public static void registerPendingMessage(Activity activity, Message message) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            fragment.pendingMessage.push(message);
        }
    }

    public static void openPendingMessage(Activity activity) {
        ConversationFragment fragment = findConversationFragment(activity);
        if (fragment != null) {
            Message message = fragment.pendingMessage.pop();
            if (message != null) {
                fragment.messageListAdapter.openDownloadable(message);
            }
        }
    }

    public static Conversation getConversation(Activity activity) {
        return getConversation(activity, R.id.secondary_fragment);
    }

    private static Conversation getConversation(Activity activity, @IdRes int res) {
        final Fragment fragment = activity.getFragmentManager().findFragmentById(res);
        if (fragment instanceof ConversationFragment) {
            return ((ConversationFragment) fragment).getConversation();
        } else {
            return null;
        }
    }

    public static ConversationFragment get(Activity activity) {
        FragmentManager fragmentManager = activity.getFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationFragment) {
            return (ConversationFragment) fragment;
        } else {
            fragment = fragmentManager.findFragmentById(R.id.secondary_fragment);
            return fragment instanceof ConversationFragment
                    ? (ConversationFragment) fragment
                    : null;
        }
    }

    public static Conversation getConversationReliable(Activity activity) {
        final Conversation conversation = getConversation(activity, R.id.secondary_fragment);
        if (conversation != null) {
            return conversation;
        }
        return getConversation(activity, R.id.main_fragment);
    }

    private static boolean scrolledToBottom(AbsListView listView) {
        final int count = listView.getCount();
        if (count == 0) {
            return true;
        }
        if (listView.getLastVisiblePosition() != count - 1) {
            return false;
        }
        final View lastChild = listView.getChildAt(listView.getChildCount() - 1);
        if (lastChild == null) {
            return false;
        }
        // The composer overlays the ListView and is represented by dynamic bottom padding.
        // Comparing against the full view height used to report "at bottom" while the last
        // message was still inside/behind that protected composer area.
        final int visibleBottom = listView.getHeight() - listView.getPaddingBottom();
        return lastChild.getBottom() <= visibleBottom + 1;
    }

    private void installAccessibleTouchTargets() {
        final int minTouchTarget = dpToPx(48);

        final ViewGroup composerRow = (ViewGroup) binding.textAttachButton.getParent();
        TouchTargetHelper.ensureMinTouchTargets(
                composerRow, minTouchTarget, binding.textAttachButton, binding.textSendButton);

        TouchTargetHelper.ensureMinTouchTargets(
                binding.voiceRecordingPanel, minTouchTarget, binding.voiceRecordingCancelButton);
        TouchTargetHelper.ensureMinTouchTargets(
                binding.contextPreview, minTouchTarget, binding.contextPreviewCancel);
        TouchTargetHelper.ensureMinTouchTargets(
                binding.mucSubject, minTouchTarget, binding.mucSubjectHide);

        final ViewGroup conversationRoot = (ViewGroup) binding.voiceRecordingLockHint.getParent();
        TouchTargetHelper.ensureMinTouchTargets(
                conversationRoot, minTouchTarget, binding.voiceRecordingLockHint);
    }

    private void updateMessageListChromePadding() {
        if (binding == null || !isAdded() || getView() == null || getView() != binding.getRoot()) {
            return;
        }
        final ListView listView = binding.messagesView;
        final boolean subjectVisible = binding.mucSubject.getVisibility() == View.VISIBLE;
        final boolean commandTabsVisible = binding.tabLayout.getVisibility() == View.VISIBLE;
        final int topInset =
                subjectVisible || commandTabsVisible
                        ? 0
                        : chatSystemBarTopInset
                                + listView.getResources()
                                        .getDimensionPixelSize(
                                                R.dimen.conversation_header_chrome_height);
        final int bottomInset = Math.max(binding.textsend.getHeight(), dpToPx(52));
        updateNavigationBarProtectionHeight();

        final boolean paddingChanged =
                listView.getPaddingLeft() != chatSystemBarLeftInset
                        || listView.getPaddingTop() != topInset
                        || listView.getPaddingRight() != chatSystemBarRightInset
                        || listView.getPaddingBottom() != bottomInset;
        final boolean canPreserve =
                paddingChanged && listView.getHeight() > 0 && listView.getChildCount() > 0;
        final boolean attachmentViewportActive = hasAttachmentViewportTransaction();
        final boolean shouldPinToBottom =
                canPreserve
                        && conversation != null
                        && !conversation.isInHistoryPart()
                        && ((attachmentViewportActive && attachmentViewportKeepBottomPinned)
                                || programmaticBottomPin
                                || pinBottomDuringImeResize
                                || passiveBottomPinPending
                                || scrolledToBottom(listView));
        final VisualScrollAnchor preservedScrollAnchor =
                canPreserve && !shouldPinToBottom
                        ? (attachmentViewportActive && attachmentViewportAnchor != null
                                ? attachmentViewportAnchor
                                : (imeResizeInProgress && imeResizeScrollAnchor != null
                                        ? imeResizeScrollAnchor
                                        : captureVisualScrollAnchor()))
                        : null;

        if (shouldPinToBottom && !programmaticBottomPin) {
            passiveBottomPinPending = true;
        }

        if (paddingChanged) {
            listView.setPadding(
                    chatSystemBarLeftInset, topInset, chatSystemBarRightInset, bottomInset);
        }

        final ViewGroup.MarginLayoutParams dateParams =
                (ViewGroup.MarginLayoutParams) binding.messageDateOverlay.getLayoutParams();
        dateParams.topMargin = topInset + dpToPx(8);
        binding.messageDateOverlay.setLayoutParams(dateParams);

        if (!canPreserve || programmaticBottomPin) {
            return;
        }

        if (shouldPinToBottom) {
            scheduleChromePaddingSettle(null, true);
        } else if (preservedScrollAnchor != null) {
            scheduleChromePaddingSettle(preservedScrollAnchor, false);
        }
    }

    private void updateNavigationBarProtectionHeight() {
        if (binding == null || !isAdded() || getView() == null || getView() != binding.getRoot()) {
            return;
        }

        // Edge-to-edge protection belongs only to the composer-to-system-edge band.
        // Start at the composer island top and fade down to the navigation edge; do not extend
        // the gradient upward over the message timeline.
        //
        // Remove the current dynamic bottom inset (system bar or IME), restore the base padding,
        // then add only the persistent navigation-bar inset. This keeps the band stable when the
        // keyboard opens while still following multiline/reply/attachment composer height.
        final int composerHeightWithoutDynamicInset =
                Math.max(
                        0,
                        binding.textsend.getHeight()
                                - binding.textsend.getPaddingBottom()
                                + textsendBasePaddingBottom);
        final int composerChromeHeight =
                Math.max(composerHeightWithoutDynamicInset, dpToPx(52)) + chatSystemBarBottomInset;
        final int protectionHeight = composerChromeHeight;

        final ViewGroup.LayoutParams protectionParams =
                binding.navigationBarProtection.getLayoutParams();
        if (protectionParams.height != protectionHeight) {
            protectionParams.height = protectionHeight;
            binding.navigationBarProtection.setLayoutParams(protectionParams);
        }
    }

    private void scheduleChromePaddingSettle(
            @Nullable final VisualScrollAnchor preservedScrollAnchor, final boolean pinToBottom) {
        if (binding == null) {
            return;
        }
        final ListView listView = binding.messagesView;
        final int generation = ++chromePaddingSettleGeneration;
        ViewCompat.postOnAnimation(
                listView,
                () -> {
                    if (binding == null
                            || generation != chromePaddingSettleGeneration
                            || programmaticBottomPin) {
                        return;
                    }

                    if (pinToBottom && passiveBottomPinPending) {
                        final boolean settled = nudgeLatestToProtectedBottom(listView);
                        if (!settled) {
                            ViewCompat.postOnAnimation(
                                    listView,
                                    () -> {
                                        if (binding != null
                                                && generation == chromePaddingSettleGeneration
                                                && !programmaticBottomPin
                                                && passiveBottomPinPending) {
                                            nudgeLatestToProtectedBottom(listView);
                                            if (!imeResizeInProgress) {
                                                passiveBottomPinPending = false;
                                            }
                                        }
                                    });
                        } else if (!imeResizeInProgress) {
                            passiveBottomPinPending = false;
                        }
                    } else if (preservedScrollAnchor != null) {
                        // Keep the same UUID row at the same pixel while composer/IME padding is
                        // changing. Waiting until the IME animation ends makes history visibly
                        // travel underneath a reader who is not following the tail.
                        restoreVisualScrollAnchor(preservedScrollAnchor);
                    }
                });
    }

    private boolean nudgeLatestToProtectedBottom(final ListView listView) {
        final int count = listView.getCount();
        if (count <= 0 || listView.getLastVisiblePosition() != count - 1) {
            return false;
        }

        final int childIndex = count - 1 - listView.getFirstVisiblePosition();
        final View lastChild =
                childIndex >= 0 && childIndex < listView.getChildCount()
                        ? listView.getChildAt(childIndex)
                        : null;
        if (lastChild == null) {
            return false;
        }

        final int protectedBottom = listView.getHeight() - listView.getPaddingBottom();
        final int delta = lastChild.getBottom() - protectedBottom;
        if (Math.abs(delta) > 1) {
            // Passive layout/IME compensation must never restart ListView selection. A small
            // pixel delta follows the moving composer smoothly without visible snap/rebind.
            //
            // scrollListBy() also awakens ListView's vertical scrollbar. Status/self-echo
            // refreshes can therefore make the thumb flash even though the conversation itself
            // stays visually pinned. Suppress only that programmatic scrollbar wake-up; manual
            // user scrolling keeps the normal scrollbar behaviour.
            final boolean verticalScrollBarEnabled = listView.isVerticalScrollBarEnabled();
            if (verticalScrollBarEnabled) {
                listView.setVerticalScrollBarEnabled(false);
            }
            try {
                listView.scrollListBy(delta);
            } finally {
                if (verticalScrollBarEnabled) {
                    listView.setVerticalScrollBarEnabled(true);
                }
            }
        }
        followLatestMessages = true;
        return true;
    }

    private void updateStickyDateOverlay(final int firstVisibleItem) {
        if (binding == null || activity == null) {
            return;
        }
        final long timestamp;
        synchronized (messageList) {
            if (messageList.isEmpty()
                    || firstVisibleItem < 0
                    || firstVisibleItem >= messageList.size()) {
                return;
            }
            timestamp = messageList.get(firstVisibleItem).getTimeSent();
        }
        binding.messageDateOverlay.setText(
                MessageAdapter.formatDateSeparatorLabel(activity, timestamp));
        binding.messageDateOverlay.removeCallbacks(hideStickyDateRunnable);
        if (binding.messageDateOverlay.getVisibility() != View.VISIBLE) {
            binding.messageDateOverlay.setAlpha(0f);
            binding.messageDateOverlay.setVisibility(View.VISIBLE);
        }
        binding.messageDateOverlay.animate().alpha(1f).setDuration(STICKY_DATE_FADE_MS).start();
    }

    private void scheduleStickyDateOverlayHide() {
        if (binding == null) {
            return;
        }
        binding.messageDateOverlay.removeCallbacks(hideStickyDateRunnable);
        binding.messageDateOverlay.postDelayed(hideStickyDateRunnable, STICKY_DATE_HIDE_DELAY_MS);
    }

    private void hideStickyDateOverlayImmediately() {
        if (binding == null) {
            return;
        }
        binding.messageDateOverlay.removeCallbacks(hideStickyDateRunnable);
        binding.messageDateOverlay.animate().cancel();
        binding.messageDateOverlay.setAlpha(0f);
        binding.messageDateOverlay.setVisibility(View.GONE);
    }

    private void toggleScrollDownButton() {
        toggleScrollDownButton(binding.messagesView);
    }

    private void toggleScrollDownButton(AbsListView listView) {
        if (conversation == null || binding == null) {
            return;
        }
        if (!conversation.isInHistoryPart()
                && (isAutomaticBottomPinActive() || scrolledToBottom(listView))) {
            followLatestMessages = true;
            lastMessageUuid = null;
            hideUnreadMessagesCount();
            return;
        }

        showScrollToBottomButton();
        if (lastMessageUuid == null) {
            final Message latest = conversation.getLatestMessage();
            lastMessageUuid = latest == null ? null : latest.getUuid();
        }
        final int receivedSinceAnchor =
                conversation.getReceivedMessagesCountSinceUuid(lastMessageUuid);
        binding.unreadCountCustomView.setUnreadCount(receivedSinceAnchor);
        binding.unreadCountCustomView.setVisibility(
                receivedSinceAnchor > 0 ? View.VISIBLE : View.GONE);
    }

    private void showScrollToBottomButton() {
        if (binding == null) {
            return;
        }
        final View button = binding.scrollToBottomButton;
        button.animate().setListener(null);
        button.animate().cancel();
        button.setEnabled(true);
        if (button.getVisibility() != View.VISIBLE) {
            button.setVisibility(View.VISIBLE);
            button.setAlpha(0f);
            button.setScaleX(0.88f);
            button.setScaleY(0.88f);
        }
        button.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120L).start();
    }

    private void hideScrollToBottomButton() {
        if (binding == null) {
            return;
        }
        final View button = binding.scrollToBottomButton;
        button.animate().setListener(null);
        button.animate().cancel();
        button.setEnabled(false);
        if (button.getVisibility() != View.VISIBLE) {
            button.setVisibility(View.GONE);
            button.setAlpha(1f);
            button.setScaleX(1f);
            button.setScaleY(1f);
            return;
        }
        button.animate()
                .alpha(0f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(100L)
                .setListener(
                        new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(final Animator animation) {
                                if (!button.isEnabled()) {
                                    button.setVisibility(View.GONE);
                                }
                                button.setAlpha(1f);
                                button.setScaleX(1f);
                                button.setScaleY(1f);
                                button.animate().setListener(null);
                            }
                        })
                .start();
    }

    private int getIndexOf(String uuid, List<Message> messages) {
        if (uuid == null) {
            return messages.size() - 1;
        }
        for (int i = 0; i < messages.size(); ++i) {
            if (uuid.equals(messages.get(i).getUuid())) {
                return i;
            }
        }
        return -1;
    }

    private int getIndexOfExtended(String uuid, List<Message> messages) {
        if (uuid == null) {
            return messages.size() - 1;
        }
        for (int i = 0; i < messages.size(); ++i) {
            if (uuid.equals(messages.get(i).getServerMsgId())) {
                return i;
            }

            if (uuid.equals(messages.get(i).getRemoteMsgId())) {
                return i;
            }

            if (uuid.equals(messages.get(i).getUuid())) {
                return i;
            }
        }
        return -1;
    }

    private ScrollState getScrollPosition() {
        final ListView listView = this.binding == null ? null : this.binding.messagesView;
        if (listView == null || listView.getCount() == 0 || scrolledToBottom(listView)) {
            return null;
        }
        final int pos = listView.getFirstVisiblePosition();
        final View view = listView.getChildAt(0);
        return view == null ? null : new ScrollState(pos, view.getTop());
    }

    @Nullable
    private VisualScrollAnchor captureVisualScrollAnchor() {
        if (binding == null) {
            return null;
        }
        final ListView listView = binding.messagesView;
        if (listView.getCount() == 0
                || (conversation != null
                        && !conversation.isInHistoryPart()
                        && scrolledToBottom(listView))) {
            return null;
        }
        final int firstPosition = listView.getFirstVisiblePosition();
        final int adapterCount =
                listView.getAdapter() == null ? 0 : listView.getAdapter().getCount();
        // During reInit()/adapter replacement ListView may still expose child positions from the
        // previous layout while the new adapter is already much smaller. Never index the adapter
        // with those stale positions; a later layout pass will provide a coherent anchor.
        if (firstPosition < 0 || firstPosition >= adapterCount) {
            return null;
        }
        for (int childIndex = 0; childIndex < listView.getChildCount(); childIndex++) {
            final int position = firstPosition + childIndex;
            if (position < 0 || position >= adapterCount) {
                break;
            }
            final Object item = listView.getItemAtPosition(position);
            if (!(item instanceof Message)) {
                continue;
            }
            final Message message = (Message) item;
            if (message.getType() == Message.TYPE_STATUS || message.getUuid() == null) {
                continue;
            }
            final View child = listView.getChildAt(childIndex);
            if (child != null) {
                return new VisualScrollAnchor(
                        message.getUuid(), child.getTop(), position, lastMessageUuid);
            }
        }
        final View firstChild = listView.getChildAt(0);
        return firstChild == null
                ? null
                : new VisualScrollAnchor(null, firstChild.getTop(), firstPosition, lastMessageUuid);
    }

    private void restoreVisualScrollAnchor(@Nullable final VisualScrollAnchor anchor) {
        if (anchor == null || binding == null) {
            return;
        }
        int position =
                anchor.messageUuid == null
                        ? -1
                        : getIndexOfExtended(anchor.messageUuid, messageList);
        if (position < 0) {
            position = Math.min(anchor.fallbackPosition, Math.max(0, messageList.size() - 1));
        }
        if (position < 0) {
            return;
        }
        followLatestMessages = false;
        userScrollControlsFollowLatest = false;
        lastMessageUuid = anchor.unreadAnchorUuid;

        final ListView listView = binding.messagesView;
        final int childIndex = position - listView.getFirstVisiblePosition();
        final View child =
                childIndex >= 0 && childIndex < listView.getChildCount()
                        ? listView.getChildAt(childIndex)
                        : null;
        if (child != null) {
            // Prefer a pixel correction for a row that is already bound. Re-running selection on
            // every IME inset frame causes ListView to visibly snap even when the logical anchor
            // never changed.
            final int delta = child.getTop() - anchor.topOffsetPx;
            if (delta != 0) {
                listView.scrollListBy(delta);
            }
        } else {
            listView.setSelectionFromTop(position, anchor.topOffsetPx);
        }
        listView.post(this::toggleScrollDownButton);
    }

    private void setScrollPosition(ScrollState scrollPosition, String lastMessageUuid) {
        if (scrollPosition != null) {
            followLatestMessages = false;
            userScrollControlsFollowLatest = false;
            this.lastMessageUuid = lastMessageUuid;
            binding.messagesView.setSelectionFromTop(
                    scrollPosition.position, scrollPosition.offset);
            binding.messagesView.post(this::toggleScrollDownButton);
        }
    }

    private void attachLocationToConversation(
            Conversation conversation,
            Uri uri,
            final MediaDraftSnapshot mediaDraft,
            final Attachment draftAttachment) {
        if (conversation == null) {
            return;
        }
        activity.xmppConnectionService.attachLocationToConversation(
                conversation,
                uri,
                new UiCallback<Message>() {

                    @Override
                    public void success(Message message) {}

                    @Override
                    public void error(int errorCode, Message object) {
                        restoreFailedMediaDraft(mediaDraft, draftAttachment);
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi, Message object) {
                        restoreFailedMediaDraft(mediaDraft, draftAttachment);
                    }
                });
    }

    private void attachFileToConversation(Conversation conversation, Uri uri, String type) {
        attachFileToConversation(conversation, uri, type, null);
    }

    private void attachFileToConversation(
            Conversation conversation, Uri uri, String type, @Nullable String mediaGroupId) {
        attachFileToConversation(conversation, uri, type, mediaGroupId, null);
    }

    private void attachFileToConversation(
            Conversation conversation,
            Uri uri,
            String type,
            @Nullable String mediaGroupId,
            @Nullable String mediaSendBatchId) {
        attachFileToConversation(conversation, uri, type, mediaGroupId, mediaSendBatchId, null);
    }

    private void attachFileToConversation(
            Conversation conversation,
            Uri uri,
            String type,
            @Nullable String mediaGroupId,
            @Nullable String mediaSendBatchId,
            @Nullable String mediaCaptionId) {
        attachFileToConversation(
                conversation,
                uri,
                type,
                mediaGroupId,
                mediaSendBatchId,
                mediaCaptionId,
                null,
                null,
                null,
                null);
    }

    private void attachFileToConversation(
            Conversation conversation,
            Uri uri,
            String type,
            @Nullable String mediaGroupId,
            @Nullable String mediaSendBatchId,
            @Nullable String mediaCaptionId,
            @Nullable final MediaDraftSnapshot mediaDraft,
            @Nullable final Attachment draftAttachment,
            @Nullable final SecureOutgoingVoiceStagingRetirer voiceStagingRetirer,
            @Nullable final OutgoingMediaPreparingSession preparingSession) {
        if (conversation == null) {
            return;
        }
        activity.delegateUriPermissionsToService(uri);
        final UiCallback<Message> callback =
                new UiCallback<Message>() {
                    @Override
                    public void success(Message message) {
                        completeOutgoingMediaPreparingAttachment(preparingSession, draftAttachment);
                    }

                    @Override
                    public void error(final int errorCode, Message message) {
                        runOnUiThread(
                                () ->
                                        handleOutgoingMediaPreparationError(
                                                errorCode,
                                                preparingSession,
                                                mediaDraft,
                                                draftAttachment));
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi, Message message) {
                        if (preparingSession != null) {
                            failOutgoingMediaPreparingSession(preparingSession, draftAttachment, 0);
                        } else {
                            restoreFailedMediaDraft(mediaDraft, draftAttachment);
                        }
                    }
                };
        if (draftAttachment != null && draftAttachment.getType() == Attachment.Type.RECORDING) {
            if (voiceStagingRetirer == null) {
                callback.error(R.string.error_io_exception, null);
                return;
            }
            activity.xmppConnectionService.attachVoiceRecordingToConversation(
                    conversation,
                    uri,
                    type,
                    callback,
                    mediaGroupId,
                    mediaSendBatchId,
                    mediaCaptionId,
                    voiceStagingRetirer);
        } else {
            activity.xmppConnectionService.attachFileToConversation(
                    conversation,
                    uri,
                    type,
                    callback,
                    mediaGroupId,
                    mediaSendBatchId,
                    mediaCaptionId);
        }
    }

    public void attachEditorContentToConversation(Uri uri) {
        if (isCorrectingMessage()) {
            showIncompatibleComposerMode();
            return;
        }
        mediaPreviewAdapter.addMediaPreviews(
                Attachment.of(getActivity(), uri, Attachment.Type.FILE));
        toggleInputMethod();
    }

    private void attachImageToConversation(Conversation conversation, Uri uri, String type) {
        attachImageToConversation(conversation, uri, type, null);
    }

    private void attachImageToConversation(
            Conversation conversation, Uri uri, String type, @Nullable String mediaGroupId) {
        attachImageToConversation(conversation, uri, type, mediaGroupId, null);
    }

    private void attachImageToConversation(
            Conversation conversation,
            Uri uri,
            String type,
            @Nullable String mediaGroupId,
            @Nullable String mediaSendBatchId) {
        attachImageToConversation(conversation, uri, type, mediaGroupId, mediaSendBatchId, null);
    }

    private void attachImageToConversation(
            Conversation conversation,
            Uri uri,
            String type,
            @Nullable String mediaGroupId,
            @Nullable String mediaSendBatchId,
            @Nullable String mediaCaptionId) {
        attachImageToConversation(
                conversation,
                uri,
                type,
                mediaGroupId,
                mediaSendBatchId,
                mediaCaptionId,
                null,
                null,
                null);
    }

    private void attachImageToConversation(
            Conversation conversation,
            Uri uri,
            String type,
            @Nullable String mediaGroupId,
            @Nullable String mediaSendBatchId,
            @Nullable String mediaCaptionId,
            @Nullable final MediaDraftSnapshot mediaDraft,
            @Nullable final Attachment draftAttachment,
            @Nullable final OutgoingMediaPreparingSession preparingSession) {
        if (conversation == null) {
            return;
        }
        activity.delegateUriPermissionsToService(uri);
        activity.xmppConnectionService.attachImageToConversation(
                conversation,
                uri,
                type,
                new UiCallback<Message>() {

                    @Override
                    public void userInputRequired(PendingIntent pi, Message object) {
                        if (preparingSession != null) {
                            failOutgoingMediaPreparingSession(preparingSession, draftAttachment, 0);
                        } else {
                            restoreFailedMediaDraft(mediaDraft, draftAttachment);
                        }
                    }

                    @Override
                    public void success(Message message) {
                        completeOutgoingMediaPreparingAttachment(preparingSession, draftAttachment);
                    }

                    @Override
                    public void error(final int error, final Message message) {
                        final ConversationsActivity activity = ConversationFragment.this.activity;
                        if (activity == null) {
                            return;
                        }
                        activity.runOnUiThread(
                                () ->
                                        handleOutgoingMediaPreparationError(
                                                error,
                                                preparingSession,
                                                mediaDraft,
                                                draftAttachment));
                    }
                },
                mediaGroupId,
                mediaSendBatchId,
                mediaCaptionId);
    }

    private void handleOutgoingMediaPreparationError(
            final int errorCode,
            @Nullable final OutgoingMediaPreparingSession preparingSession,
            @Nullable final MediaDraftSnapshot mediaDraft,
            @Nullable final Attachment failedAttachment) {
        if (preparingSession != null) {
            failOutgoingMediaPreparingSession(preparingSession, failedAttachment, errorCode);
            return;
        }
        restoreFailedMediaDraft(mediaDraft, failedAttachment);
        if (failedAttachment != null && errorCode != 0 && mediaPreviewAdapter != null) {
            mediaPreviewAdapter.markPreparationError(failedAttachment, errorCode);
        }
    }

    private void restoreFailedMediaDraft(
            @Nullable final MediaDraftSnapshot mediaDraft,
            @Nullable final Attachment failedAttachment) {
        if (failedAttachment == null) {
            return;
        }
        restoreFailedMediaDraft(mediaDraft, Collections.singletonList(failedAttachment));
    }

    private void restoreFailedMediaDraft(@Nullable final MediaDraftSnapshot mediaDraft) {
        restoreFailedMediaDraft(
                mediaDraft,
                mediaDraft == null ? Collections.emptyList() : mediaDraft.getAttachments());
    }

    private void restoreFailedMediaDraft(
            @Nullable final MediaDraftSnapshot mediaDraft,
            final Collection<Attachment> failedAttachments) {
        if (mediaDraft == null || failedAttachments.isEmpty()) {
            return;
        }
        final ConversationsActivity currentActivity = activity;
        if (currentActivity == null) {
            return;
        }
        currentActivity.runOnUiThread(
                () -> {
                    final Conversation draftConversation = mediaDraft.getConversation();
                    if (draftConversation == null
                            || conversation != draftConversation
                            || binding == null
                            || getView() == null) {
                        return;
                    }
                    final ArrayList<Attachment> previews = mediaPreviewAdapter.getAttachments();
                    for (final Attachment failedAttachment : failedAttachments) {
                        if (!previews.contains(failedAttachment)) {
                            mediaPreviewAdapter.addMediaPreviews(
                                    Collections.singletonList(failedAttachment));
                        }
                    }
                    final Editable currentText = binding.textinput.getText();
                    if ((currentText == null || currentText.length() == 0)
                            && !mediaDraft.getCaption().isEmpty()) {
                        binding.textinput.setText(mediaDraft.getCaption());
                    }
                    if (draftConversation.getReplyTo() == null && mediaDraft.getReplyTo() != null) {
                        setupReply(mediaDraft.getReplyTo());
                    }
                    pendingMediaCommitConversationUuid = null;
                    pendingMediaDraft =
                            MediaDraftSnapshot.from(
                                    binding.textinput.getText().toString(),
                                    mediaPreviewAdapter.getAttachments(),
                                    draftConversation);
                    toggleInputMethod();
                });
    }

    private void sendMessageDelayed() {
        final Calendar currentDate = Calendar.getInstance();
        Calendar date = Calendar.getInstance();
        new DatePickerDialog(
                        activity,
                        new DatePickerDialog.OnDateSetListener() {
                            @Override
                            public void onDateSet(
                                    DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                                date.set(year, monthOfYear, dayOfMonth);
                                new TimePickerDialog(
                                                activity,
                                                new TimePickerDialog.OnTimeSetListener() {
                                                    @Override
                                                    public void onTimeSet(
                                                            TimePicker view,
                                                            int hourOfDay,
                                                            int minute) {
                                                        date.set(Calendar.HOUR_OF_DAY, hourOfDay);
                                                        date.set(Calendar.MINUTE, minute);
                                                        sendMessage(date.getTimeInMillis());
                                                    }
                                                },
                                                currentDate.get(Calendar.HOUR_OF_DAY),
                                                currentDate.get(Calendar.MINUTE),
                                                false)
                                        .show();
                            }
                        },
                        currentDate.get(Calendar.YEAR),
                        currentDate.get(Calendar.MONTH),
                        currentDate.get(Calendar.DATE))
                .show();
    }

    private void sendMessage() {
        sendMessage((Long) null);
    }

    private void sendMessage(Long timeSent) {
        final Editable text = this.binding.textinput.getText();
        final String body = text == null ? "" : text.toString();
        final Conversation conversation = this.conversation;
        if (mediaPreviewAdapter.hasAttachments()) {
            pendingMediaDraft =
                    MediaDraftSnapshot.from(
                            body, mediaPreviewAdapter.getAttachments(), conversation);
            commitAttachments();
            return;
        }
        sendTextMessage(
                body,
                conversation,
                conversation == null ? null : conversation.getReplyTo(),
                timeSent);
    }

    private void sendTextMessage(
            final String body,
            @Nullable final Conversation conversation,
            @Nullable final Message replyTo,
            @Nullable final Long timeSent) {
        if (body.isEmpty() || conversation == null) {
            return;
        }
        if (trustKeysIfNeeded(conversation, REQUEST_TRUST_KEYS_TEXT)) {
            return;
        }

        final int encryption = conversation.getNextEncryption();
        final MessageMarkup.Prepared prepared = MessageMarkup.prepare(body);
        final MessageMarkup.WireMode wireMode =
                MessageMarkup.selectWireMode(conversation, encryption);
        final String outgoingBody = MessageMarkup.bodyForMode(body, prepared, wireMode);

        final Message message;
        if (conversation.getCorrectingMessage() == null) {
            if (replyTo != null) {
                if (Emoticons.isEmoji(prepared.getPlainBody().replaceAll("\\s", ""))) {
                    message = replyTo.react(prepared.getPlainBody().replaceAll("\\s", ""));
                } else {
                    message = replyTo.reply();
                    final int markupOffset =
                            message.getBody().codePointCount(0, message.getBody().length());
                    message.appendBody(outgoingBody);
                    message.setMessageMarkup(
                            MessageMarkup.markupForMode(prepared, wireMode, markupOffset));
                }
                message.setEncryption(encryption);
            } else {
                message = new Message(conversation, outgoingBody, encryption);
                message.setMessageMarkup(MessageMarkup.markupForMode(prepared, wireMode, 0));
            }

            Message.configurePrivateMessage(message);
        } else {
            message = conversation.getCorrectingMessage();
            final String correctionTargetId = message.getCorrectionTargetId();
            if (correctionTargetId == null) {
                return;
            }
            message.setBody(outgoingBody);
            message.setMessageMarkup(MessageMarkup.markupForMode(prepared, wireMode, 0));
            message.putEdited(correctionTargetId, message.getServerMsgId());
            message.setServerMsgId(null);
            if (message.hasProtectedTextPayload()) {
                // Preserve the Store relation's logical identity; only the correction stanza
                // gets a fresh wire identifier.
                message.setWireUuid(UUID.randomUUID().toString());
            } else {
                message.setUuid(UUID.randomUUID().toString());
            }
        }

        switch (conversation.getNextEncryption()) {
            case Message.ENCRYPTION_OTR:
                sendOtrMessage(message);
                break;
            default:
                if (timeSent != null && timeSent > System.currentTimeMillis()) {
                    message.setTime(timeSent);
                    sendMessageDelayed(message, timeSent);
                } else {
                    sendMessage(message);
                }
        }

        setupReply(null);
    }

    private boolean trustKeysIfNeeded(final Conversation conversation, final int requestCode) {
        return conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL
                && trustKeysIfNeeded(requestCode);
    }

    protected boolean trustKeysIfNeeded(int requestCode) {
        final Account account = conversation.getAccount();
        final AxolotlService axolotlService = account.getAxolotlService();
        final List<Jid> targets = axolotlService.getCryptoTargets(conversation);
        final boolean hasUnaccepted = !conversation.getAcceptedCryptoTargets().containsAll(targets);
        final boolean hasUndecidedOwn =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided())
                        .isEmpty();
        final boolean hasUndecidedContacts =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets)
                        .isEmpty();
        final boolean hasPendingKeys =
                !axolotlService.findDevicesWithoutSession(conversation).isEmpty();
        final boolean hasNoTrustedKeys = axolotlService.anyTargetHasNoTrustedKeys(targets);
        final boolean downloadInProgress = axolotlService.hasPendingKeyFetches(targets);

        // Trust decisions are local security state. Session/device-list fetches are transport
        // preparation. When connectivity disappears, a stale PENDING fetch must not turn an
        // already verified 1:1 chat into a full-screen "untrusted fingerprint" flow. Let the
        // normal send path persist the OMEMO message as WAITING; reconnect will prepare/encrypt it
        // and the strict verified-contact policy still excludes any unverified remote session.
        final boolean transportAvailable =
                account.isOnlineAndConnected()
                        && activity != null
                        && activity.xmppConnectionService != null
                        && activity.xmppConnectionService.hasInternetConnection();
        final boolean localTrustDecisionRequired =
                hasUndecidedOwn || hasUndecidedContacts || hasNoTrustedKeys || hasUnaccepted;
        final boolean transportPreparationRequired = hasPendingKeys || downloadInProgress;
        final boolean trustUiRequired =
                localTrustDecisionRequired || (transportAvailable && transportPreparationRequired);

        if (!trustUiRequired) {
            if (transportPreparationRequired && !transportAvailable) {
                Log.d(
                        Config.LOGTAG,
                        "OMEMO send: queueing while offline instead of opening trust UI;"
                                + " pendingKeys="
                                + hasPendingKeys
                                + ", pendingFetch="
                                + downloadInProgress);
            }
            return false;
        }

        axolotlService.createSessionsIfNeeded(conversation);
        Intent intent = new Intent(getActivity(), TrustKeysActivity.class);
        String[] contacts = new String[targets.size()];
        for (int i = 0; i < contacts.length; ++i) {
            contacts[i] = targets.get(i).toString();
        }
        intent.putExtra("contacts", contacts);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        intent.putExtra("conversation", conversation.getUuid());
        startActivityForResult(intent, requestCode);
        return true;
    }

    public void updateChatMsgHint() {
        final boolean multi = conversation.getMode() == Conversation.MODE_MULTI;
        if (conversation.getCorrectingMessage() != null) {
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textinput.setHint(R.string.send_corrected_message);
        } else if (multi && conversation.getNextCounterpart() != null) {
            /*this.binding.textinput.setHint(R.string.send_unencrypted_message);
            this.binding.textInputHint.setVisibility(View.VISIBLE);
            this.binding.textInputHint.setText(
                    getString(
                            R.string.send_private_message_to,
                            conversation.getNextCounterpart().getResource())); */
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textinput.setHint(UIHelper.getMessageHint(getActivity(), conversation));
            getActivity().invalidateOptionsMenu();
        } else if (multi && !conversation.getMucOptions().participating()) {
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textinput.setHint(R.string.muc_read_only_short);
        } else {
            this.binding.textInputHint.setVisibility(View.GONE);
            this.binding.textinput.setHint(UIHelper.getMessageHint(getActivity(), conversation));
            getActivity().invalidateOptionsMenu();
        }
    }

    public void setupIme() {
        this.binding.textinput.refreshIme();
    }

    private void handleActivityResult(ActivityResult activityResult) {
        if (isAttachmentActivityRequest(activityResult.requestCode)) {
            final String targetConversationUuid = pendingAttachmentConversationUuid;
            pendingAttachmentConversationUuid = null;
            if (conversation == null
                    || targetConversationUuid == null
                    || !targetConversationUuid.equals(conversation.getUuid())) {
                handleNegativeActivityResult(activityResult.requestCode);
                finishAttachmentViewportTransactionAfterLayout();
                return;
            }
        }
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
        } else {
            handleNegativeActivityResult(activityResult.requestCode);
        }

        if (isAttachmentActivityRequest(activityResult.requestCode)
                && pendingAttachmentConversationUuid == null) {
            finishAttachmentViewportTransactionAfterLayout();
        }

        ChatBackgroundHelper.onActivityResult(
                activity,
                activityResult.requestCode,
                activityResult.resultCode,
                activityResult.data,
                conversation.getUuid());

        if (activityResult.requestCode == ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND) {
            refresh();
            if (activity != null) {
                activity.invalidateOptionsMenu();
            }
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        if (isAttachmentActivityRequest(requestCode) && isCorrectingMessage()) {
            handleNegativeActivityResult(requestCode);
            showIncompatibleComposerMode();
            return;
        }
        switch (requestCode) {
            case REQUEST_TRUST_KEYS_TEXT:
                sendMessage();
                break;
            case REQUEST_TRUST_KEYS_ATTACHMENTS:
                commitAttachmentsAfterPendingGate();
                break;
            case REQUEST_START_AUDIO_CALL:
                triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
                break;
            case REQUEST_START_VIDEO_CALL:
                triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
                break;
            case REQUEST_PICK_DATE:
                String messageUuid = data.getStringExtra(ConversationsActivity.EXTRA_MESSAGE_UUID);
                if (messageUuid != null) {
                    Runnable postSelectionRunnable = () -> highlightMessage(messageUuid);
                    updateSelection(messageUuid, postSelectionRunnable, false, false);
                }

                break;
            case ATTACHMENT_CHOICE_CHOOSE_IMAGE:
                final List<Attachment> imageUris =
                        Attachment.extractAttachments(getActivity(), data, Attachment.Type.IMAGE);
                if (imageUris.size() == 1 && !skipImageEditor()) {
                    editImage(imageUris.get(0).getUri());
                } else {
                    mediaPreviewAdapter.addMediaPreviews(imageUris);
                    toggleInputMethod();
                }
                break;
            case ATTACHMENT_CHOICE_TAKE_PHOTO:
                final Uri takePhotoUri = pendingTakePhotoUri.pop();
                if (takePhotoUri == null) {
                    Log.d(Config.LOGTAG, "lost take photo uri. unable to attach");
                } else if (!skipImageEditor()) {
                    editImage(takePhotoUri);
                } else {
                    mediaPreviewAdapter.addMediaPreviews(
                            Attachment.of(getActivity(), takePhotoUri, Attachment.Type.IMAGE));
                    toggleInputMethod();
                }
                break;
            case ATTACHMENT_CHOICE_EDIT_PHOTO:
                final Uri editedUriPhoto = data.getParcelableExtra(EditActivity.KEY_EDITED_URI);
                final Uri originalEditedUri = pendingEditedImageUri.pop();
                pendingEditedImageWasPreview.clear();
                if (editedUriPhoto != null && originalEditedUri != null) {
                    mediaPreviewAdapter.replaceOrAddMediaPreview(
                            originalEditedUri, editedUriPhoto, Attachment.Type.IMAGE);
                    if (!originalEditedUri.equals(editedUriPhoto)) {
                        retireControlledImageStaging(originalEditedUri);
                    }
                    toggleInputMethod();
                } else {
                    retireControlledImageStaging(editedUriPhoto);
                    Log.d(Config.LOGTAG, "lost edited photo uri. unable to attach");
                }
                break;
            case ATTACHMENT_CHOICE_RECORD_VIDEO:
                final List<Attachment> recordedVideos =
                        Attachment.extractAttachments(getActivity(), data, Attachment.Type.FILE);
                if (recordedVideos.size() == 1
                        && recordedVideos.get(0).getMime() != null
                        && recordedVideos.get(0).getMime().startsWith("video/")
                        && !skipVideoEditor()) {
                    editVideo(recordedVideos.get(0).getUri());
                } else {
                    mediaPreviewAdapter.addMediaPreviews(recordedVideos);
                    toggleInputMethod();
                }
                break;
            case ATTACHMENT_CHOICE_EDIT_VIDEO:
                final Uri editedVideoUri =
                        data.getParcelableExtra(VideoEditActivity.KEY_EDITED_URI);
                final Uri originalEditedVideoUri = pendingEditedVideoUri.pop();
                pendingEditedVideoWasPreview.clear();
                if (editedVideoUri == null || originalEditedVideoUri == null) {
                    retireControlledVideoStaging(editedVideoUri);
                    Log.d(Config.LOGTAG, "lost edited video uri. unable to attach");
                    break;
                }
                mediaPreviewAdapter.replaceOrAddMediaPreview(
                        originalEditedVideoUri, editedVideoUri, Attachment.Type.FILE);
                if (!originalEditedVideoUri.equals(editedVideoUri)) {
                    retireControlledVideoStaging(originalEditedVideoUri);
                }
                toggleInputMethod();
                if (data.getBooleanExtra(VideoEditActivity.KEY_SEND_NOW, false)
                        && conversation != null) {
                    pendingMediaCommitConversationUuid = conversation.getUuid();
                    commitAttachments();
                }
                break;
            case ATTACHMENT_CHOICE_CHOOSE_FILE:
            case ATTACHMENT_CHOICE_RECORD_VOICE:
                final Attachment.Type type =
                        requestCode == ATTACHMENT_CHOICE_RECORD_VOICE
                                ? Attachment.Type.RECORDING
                                : Attachment.Type.FILE;
                final List<Attachment> fileUris =
                        Attachment.extractAttachments(getActivity(), data, type);
                mediaPreviewAdapter.addMediaPreviews(fileUris);
                toggleInputMethod();
                break;
            case ATTACHMENT_CHOICE_LOCATION:
                final double latitude = data.getDoubleExtra("latitude", 0);
                final double longitude = data.getDoubleExtra("longitude", 0);
                final int accuracy = data.getIntExtra("accuracy", 0);
                final Uri geo;
                if (accuracy > 0) {
                    geo = Uri.parse(String.format("geo:%s,%s;u=%s", latitude, longitude, accuracy));
                } else {
                    geo = Uri.parse(String.format("geo:%s,%s", latitude, longitude));
                }
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), geo, Attachment.Type.LOCATION));
                toggleInputMethod();
                break;
            case REQUEST_INVITE_TO_CONVERSATION:
                XmppActivity.ConferenceInvite invite = XmppActivity.ConferenceInvite.parse(data);
                if (invite != null) {
                    activity.handleConferenceInviteResult(invite.execute(activity));
                }
                break;
        }
    }

    public void editImage(Uri uri) {
        pendingAttachmentConversationUuid = conversation == null ? null : conversation.getUuid();
        pendingEditedImageUri.push(uri);
        pendingEditedImageWasPreview.push(
                mediaPreviewAdapter != null && mediaPreviewAdapter.containsUri(uri));
        Intent intent = new Intent(activity, EditActivity.class);
        intent.setData(uri);
        intent.putExtra(EditActivity.KEY_CHAT_NAME, conversation.getName());
        startActivityForResult(intent, ATTACHMENT_CHOICE_EDIT_PHOTO);
    }

    public void editVideo(final Uri uri) {
        if (conversation == null || activity == null || uri == null) {
            return;
        }
        pendingAttachmentConversationUuid = conversation.getUuid();
        pendingEditedVideoUri.push(uri);
        pendingEditedVideoWasPreview.push(
                mediaPreviewAdapter != null && mediaPreviewAdapter.containsUri(uri));
        final Intent intent = new Intent(activity, VideoEditActivity.class);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(VideoEditActivity.KEY_CHAT_NAME, conversation.getName());
        startActivityForResult(intent, ATTACHMENT_CHOICE_EDIT_VIDEO);
        activity.overridePendingTransition(0, 0);
    }

    private void commitAttachmentsAfterPendingGate() {
        if (conversation == null
                || pendingMediaCommitConversationUuid == null
                || !pendingMediaCommitConversationUuid.equals(conversation.getUuid())) {
            return;
        }
        commitAttachments();
    }

    private void commitAttachments() {
        if (conversation == null || binding == null || mediaPreviewAdapter == null) {
            return;
        }
        if (pendingMediaCommitConversationUuid != null
                && !pendingMediaCommitConversationUuid.equals(conversation.getUuid())) {
            return;
        }
        pendingMediaCommitConversationUuid = conversation.getUuid();
        if (pendingMediaDraft != null && pendingMediaDraft.getConversation() != conversation) {
            return;
        }
        final Editable captionText = binding.textinput.getText();
        final MediaDraftSnapshot draft =
                MediaDraftSnapshot.from(
                        captionText == null ? "" : captionText.toString(),
                        mediaPreviewAdapter.getAttachments(),
                        conversation);
        pendingMediaDraft = draft;
        final Conversation draftConversation = draft.getConversation();
        final List<Attachment> attachments = draft.getAttachments();
        if (attachments.isEmpty()) {
            pendingMediaDraft = null;
            pendingMediaCommitConversationUuid = null;
            return;
        }
        if (anyNeedsExternalStoragePermission(attachments)
                && !hasPermissions(
                        REQUEST_COMMIT_ATTACHMENTS, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return;
        }
        if (trustKeysIfNeeded(draftConversation, REQUEST_TRUST_KEYS_ATTACHMENTS)) {
            return;
        }
        final int mediaAttachmentCount = countMediaAlbumAttachments(attachments);
        final int mediaSendBatchMemberCount =
                containsOnlyMediaAlbumAttachments(attachments) ? mediaAttachmentCount : 0;
        final String mediaGroupId = mediaAttachmentCount >= 1 ? UUID.randomUUID().toString() : null;
        final PresenceSelector.OnPresenceSelected callback =
                () -> {
                    if (activity == null
                            || binding == null
                            || conversation != draftConversation
                            || pendingMediaDraft != draft) {
                        return;
                    }
                    mediaPreviewAdapter.clearPreparationErrors();
                    final boolean hasCaption = !draft.getCaption().trim().isEmpty();
                    final String mediaCaptionId =
                            hasCaption && mediaAttachmentCount > 0 && activity != null
                                    ? activity.xmppConnectionService.beginOutgoingMediaCaption(
                                            draftConversation, draft.getCaption())
                                    : null;
                    final String mediaSendBatchId =
                            mediaSendBatchMemberCount >= 2 && activity != null
                                    ? activity.xmppConnectionService.beginMediaSendBatch(
                                            draftConversation, mediaSendBatchMemberCount)
                                    : null;
                    boolean mediaCaptionAssigned = false;
                    final boolean waitForRelatedCaption =
                            hasCaption
                                    && mediaCaptionId != null
                                    && activity.xmppConnectionService.supportsMessageAttaching(
                                            draftConversation);
                    final OutgoingMediaPreparingSession preparingSession =
                            beginOutgoingMediaPreparingSession(
                                    draft,
                                    mediaGroupId,
                                    mediaAttachmentCount,
                                    waitForRelatedCaption);
                    final List<Attachment> previews = mediaPreviewAdapter.getAttachments();
                    for (final Attachment attachment : attachments) {
                        final boolean mediaAlbumAttachment = isMediaAlbumAttachment(attachment);
                        final boolean releaseMediaCaption =
                                mediaCaptionId != null
                                        && (mediaSendBatchId != null
                                                ? mediaAlbumAttachment
                                                : !mediaCaptionAssigned
                                                        && attachment.getType()
                                                                != Attachment.Type.LOCATION);
                        if (attachment.getType() == Attachment.Type.LOCATION) {
                            attachLocationToConversation(
                                    draftConversation, attachment.getUri(), draft, attachment);
                        } else if (attachment.getType() == Attachment.Type.IMAGE) {
                            attachImageToConversation(
                                    draftConversation,
                                    attachment.getUri(),
                                    attachment.getMime(),
                                    mediaAlbumAttachment ? mediaGroupId : null,
                                    mediaAlbumAttachment ? mediaSendBatchId : null,
                                    releaseMediaCaption ? mediaCaptionId : null,
                                    draft,
                                    attachment,
                                    preparingSession);
                        } else {
                            attachFileToConversation(
                                    draftConversation,
                                    attachment.getUri(),
                                    attachment.getMime(),
                                    mediaAlbumAttachment ? mediaGroupId : null,
                                    mediaAlbumAttachment ? mediaSendBatchId : null,
                                    releaseMediaCaption ? mediaCaptionId : null,
                                    draft,
                                    attachment,
                                    attachment.getType() == Attachment.Type.RECORDING
                                            ? VoiceRecordingStaging.retirerForUri(
                                                    activity, attachment.getUri())
                                            : null,
                                    preparingSession);
                        }
                        if (releaseMediaCaption) {
                            mediaCaptionAssigned = true;
                        }
                        previews.remove(attachment);
                    }
                    if (hasCaption && mediaCaptionId == null) {
                        sendTextMessage(draft.getCaption(), draftConversation, null, null);
                    } else {
                        messageSent();
                        if (draftConversation.getReplyTo() == draft.getReplyTo()) {
                            setupReply(null);
                        }
                    }
                    pendingMediaDraft = null;
                    pendingMediaCommitConversationUuid = null;
                    mediaPreviewAdapter.notifyDataSetChanged();
                    toggleInputMethod();
                };
        if (draftConversation.getMode() == Conversation.MODE_MULTI
                || Attachment.canBeSendInBand(attachments)
                || (draftConversation.getAccount().httpUploadAvailable()
                        && FileBackend.allFilesUnderSize(
                                getActivity(),
                                attachments,
                                getMaxHttpUploadSize(draftConversation)))) {
            callback.onPresenceSelected();
        } else {
            activity.selectPresence(draftConversation, callback);
        }
    }

    private static int countMediaAlbumAttachments(final Collection<Attachment> attachments) {
        int mediaCount = 0;
        for (final Attachment attachment : attachments) {
            if (isMediaAlbumAttachment(attachment)) {
                mediaCount++;
            }
        }
        return mediaCount;
    }

    private static boolean containsOnlyMediaAlbumAttachments(
            final Collection<Attachment> attachments) {
        for (final Attachment attachment : attachments) {
            if (!isMediaAlbumAttachment(attachment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMediaAlbumAttachment(final Attachment attachment) {
        if (attachment.getType() == Attachment.Type.IMAGE) {
            return true;
        }
        final String mime = attachment.getMime();
        return mime != null && mime.startsWith("video/");
    }

    private static boolean anyNeedsExternalStoragePermission(
            final Collection<Attachment> attachments) {
        for (final Attachment attachment : attachments) {
            if (attachment.getType() != Attachment.Type.LOCATION) {
                return true;
            }
        }
        return false;
    }

    private void refreshPendingMediaDraftFromComposer() {
        if (pendingMediaDraft == null
                || pendingMediaDraft.getConversation() != conversation
                || binding == null
                || mediaPreviewAdapter == null) {
            return;
        }
        final List<Attachment> attachments = mediaPreviewAdapter.getAttachments();
        if (attachments.isEmpty()) {
            pendingMediaDraft = null;
            return;
        }
        final Editable captionText = binding.textinput.getText();
        pendingMediaDraft =
                MediaDraftSnapshot.from(
                        captionText == null ? "" : captionText.toString(),
                        attachments,
                        conversation);
    }

    public void toggleInputMethod() {
        final String anchorConversationUuid = conversation == null ? null : conversation.getUuid();
        final boolean attachmentViewportActive = hasAttachmentViewportTransaction();
        final boolean keepBottomPinned =
                attachmentViewportActive
                        ? attachmentViewportKeepBottomPinned
                        : conversation != null
                                && !conversation.isInHistoryPart()
                                && (isAutomaticBottomPinActive() || scrolledToBottom());
        final VisualScrollAnchor composerMutationAnchor =
                keepBottomPinned
                        ? null
                        : attachmentViewportActive
                                ? attachmentViewportAnchor
                                : captureVisualScrollAnchor();

        final boolean hasAttachments = mediaPreviewAdapter.hasAttachments();
        if (!hasAttachments) {
            pendingMediaCommitConversationUuid = null;
        }
        refreshPendingMediaDraftFromComposer();
        binding.textinput.setVisibility(View.VISIBLE);
        binding.mediaPreview.setVisibility(hasAttachments ? View.VISIBLE : View.GONE);
        updateSendButton();

        // Preview add/remove changes textsend height. The layout listener sees that only after the
        // mutation, which is too late to recover the pre-mutation reading position. Preserve it
        // explicitly here; bottom-follow continues to use the normal pin path.
        if (composerMutationAnchor != null) {
            binding.textsend.post(
                    () -> {
                        if (binding != null
                                && conversation != null
                                && TextUtils.equals(anchorConversationUuid, conversation.getUuid())
                                && !programmaticBottomPin) {
                            scheduleChromePaddingSettle(composerMutationAnchor, false);
                        }
                    });
        }
    }

    public void onMediaPreviewRemoved(final Attachment attachment) {
        if (attachment == null) {
            return;
        }
        if (attachment.getType() == Attachment.Type.IMAGE) {
            retireControlledImageStaging(attachment.getUri());
            return;
        }
        final String mime = attachment.getMime();
        if (mime != null && mime.startsWith("video/")) {
            retireControlledVideoStaging(attachment.getUri());
        }
    }

    private void retireControlledImageStaging(final Uri uri) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT || activity == null || uri == null) {
            return;
        }
        if (!ImageAttachmentStaging.retireControlledUri(activity, uri)) {
            Log.w(Config.LOGTAG, "unable to retire controlled outgoing image staging");
        }
    }

    private void retireControlledVideoStaging(final Uri uri) {
        if (activity == null || uri == null) {
            return;
        }
        if (!VideoAttachmentStaging.retireControlledUri(activity, uri)) {
            Log.w(Config.LOGTAG, "unable to retire controlled outgoing video staging");
        }
    }

    private void handleNegativeActivityResult(int requestCode) {
        switch (requestCode) {
            case ATTACHMENT_CHOICE_TAKE_PHOTO:
                final Uri canceledPhotoUri = pendingTakePhotoUri.pop();
                if (canceledPhotoUri != null) {
                    retireControlledImageStaging(canceledPhotoUri);
                    Log.d(
                            Config.LOGTAG,
                            "retired pending photo staging after negative activity result");
                }
                break;
            case ATTACHMENT_CHOICE_EDIT_PHOTO:
                final Uri canceledEditUri = pendingEditedImageUri.pop();
                final Boolean wasPreview = pendingEditedImageWasPreview.pop();
                if (canceledEditUri != null && !Boolean.TRUE.equals(wasPreview)) {
                    retireControlledImageStaging(canceledEditUri);
                }
                break;
            case ATTACHMENT_CHOICE_EDIT_VIDEO:
                final Uri canceledVideoEditUri = pendingEditedVideoUri.pop();
                final Boolean videoWasPreview = pendingEditedVideoWasPreview.pop();
                if (canceledVideoEditUri != null && !Boolean.TRUE.equals(videoWasPreview)) {
                    mediaPreviewAdapter.addMediaPreviews(
                            Attachment.of(
                                    getActivity(), canceledVideoEditUri, Attachment.Type.FILE));
                    toggleInputMethod();
                }
                break;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (activity != null && activity.xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    public void unblockConversation(final Blockable conversation) {
        activity.xmppConnectionService.sendUnblockRequest(conversation);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        Log.d(Config.LOGTAG, "ConversationFragment.onAttach()");
        if (activity instanceof ConversationsActivity) {
            this.activity = (ConversationsActivity) activity;
        } else {
            throw new IllegalStateException(
                    "Trying to attach fragment to activity that is not the ConversationsActivity");
        }
        vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        this.activity = null; // TODO maybe not a good idea since some callbacks really need it
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        if (savedInstanceState == null && conversation != null) {
            conversation.jumpToLatest();
        }
    }

    @SuppressLint("RestrictedApi")
    private static void enableOptionalMenuIcons(final Menu menu) {
        if (menu instanceof MenuBuilder) {
            final MenuBuilder menuBuilder = (MenuBuilder) menu;
            menuBuilder.setOptionalIconsVisible(true);
            menuBuilder.setGroupDividerEnabled(true);
        }
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            if (item.hasSubMenu()) {
                enableOptionalMenuIcons(item.getSubMenu());
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.fragment_conversation, menu);
        enableOptionalMenuIcons(menu);
        final MenuItem menuMucDetails = menu.findItem(R.id.action_muc_details);
        final MenuItem menuMucParticipants = menu.findItem(R.id.action_muc_participants);
        final MenuItem menuContactDetails = menu.findItem(R.id.action_contact_details);
        final MenuItem menuInviteContact = menu.findItem(R.id.action_invite);
        final MenuItem menuAttachFile = menu.findItem(R.id.action_attach_file);
        final MenuItem menuOpenCalendar = menu.findItem(R.id.action_open_calendar);
        final MenuItem menuMute = menu.findItem(R.id.action_mute);
        final MenuItem menuUnmute = menu.findItem(R.id.action_unmute);
        final MenuItem menuAudioCall = menu.findItem(R.id.action_audio_call);
        final MenuItem menuOngoingCall = menu.findItem(R.id.action_ongoing_call);
        final MenuItem menuVideoCall = menu.findItem(R.id.action_video_call);
        final MenuItem menuTogglePinned = menu.findItem(R.id.action_toggle_pinned);
        final MenuItem startSecretChat = menu.findItem(R.id.action_start_secret_chat);
        final MenuItem destroySecretChat = menu.findItem(R.id.action_destroy_secret_chat);
        final MenuItem encryption = menu.findItem(R.id.action_security);
        final MenuItem menuArchive = menu.findItem(R.id.action_archive);
        final MenuItem menuDeleteMucLocal = menu.findItem(R.id.action_delete_muc_local);

        if (conversation != null) {
            boolean considerAsSecretChat =
                    conversation.getMode() == Conversational.MODE_SINGLE
                            && conversation.getNextCounterpart() != null
                            && conversation.hasPermanentCounterpart();

            startSecretChat.setVisible(false);
            destroySecretChat.setVisible(false);
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                menuContactDetails.setVisible(false);
                if (conversation.getNextCounterpart() == null) {
                    final boolean channel =
                            !conversation.getMucOptions().isPrivateAndNonAnonymous();
                    menuArchive.setTitle(
                            channel
                                    ? R.string.conversation_menu_leave_channel
                                    : R.string.conversation_menu_leave_conference);
                    menuArchive.setIcon(R.drawable.ic_logout_24dp);
                    menuArchive.setVisible(!conversation.isMucExplicitlyLeft());
                    menuDeleteMucLocal.setVisible(true);
                    menuDeleteMucLocal.setTitle(
                            channel
                                    ? R.string.conversation_menu_delete_channel_from_device
                                    : R.string.conversation_menu_delete_group_from_device);
                } else {
                    menuDeleteMucLocal.setVisible(false);
                }
                menuInviteContact.setVisible(
                        conversation.getMucOptions().canInvite()
                                && conversation.getNextCounterpart() == null);
                menuMucDetails.setTitle(
                        conversation.getMucOptions().isPrivateAndNonAnonymous()
                                ? R.string.action_muc_details
                                : R.string.channel_details);
                menuAudioCall.setVisible(false);
                menuVideoCall.setVisible(false);
                menuOngoingCall.setVisible(false);
                startSecretChat.setVisible(false);
            } else {
                menuMucParticipants.setVisible(false);
                final XmppConnectionService service =
                        activity == null ? null : activity.xmppConnectionService;
                final Optional<OngoingRtpSession> ongoingRtpSession =
                        service == null
                                ? Optional.absent()
                                : service.getJingleConnectionManager()
                                        .getOngoingRtpConnection(conversation.getContact());
                if (ongoingRtpSession.isPresent()) {
                    menuOngoingCall.setVisible(true);
                    menuAudioCall.setVisible(false);
                    menuVideoCall.setVisible(false);
                } else {
                    menuOngoingCall.setVisible(false);
                    menuAudioCall.setVisible(true);
                    menuVideoCall.setVisible(false);
                }
                menuContactDetails.setVisible(!this.conversation.withSelf());
                menuMucDetails.setVisible(false);
                menuInviteContact.setVisible(
                        service != null
                                && service.findConferenceServer(conversation.getAccount()) != null);
            }
            if (conversation.isMuted()) {
                menuMute.setVisible(false);
            } else {
                menuUnmute.setVisible(false);
            }
            ConversationMenuConfigurator.configureAttachmentMenu(conversation, menu);
            ConversationMenuConfigurator.configureEncryptionMenu(conversation, menu);

            // NeoCont: these actions are available through the composer/header already,
            // so keep the conversation overflow menu focused and free of duplicates.
            menuAttachFile.setVisible(false);
            menuContactDetails.setVisible(false);
            menuInviteContact.setVisible(false);
            menuOpenCalendar.setVisible(false);
            if (conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)) {
                menuTogglePinned.setTitle(R.string.conversation_menu_unpin);
            } else {
                menuTogglePinned.setTitle(R.string.conversation_menu_pin);
            }
            final MenuItem backgroundItem = menu.findItem(R.id.action_set_custom_bg);
            if (backgroundItem != null) {
                backgroundItem.setTitle(
                        hasConversationCustomBackground()
                                ? R.string.conversation_menu_reset_background
                                : R.string.conversation_menu_background);
            }

            if (considerAsSecretChat) {
                encryption.setVisible(false);
            }

            applyConversationMenuVisuals(menu);
        }

        Fragment secondaryFragment =
                activity.getFragmentManager().findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment) {
            activity.showNavigationBar();
        } else {
            activity.hideNavigationBar();
        }

        super.onCreateOptionsMenu(menu, menuInflater);
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        this.binding =
                DataBindingUtil.inflate(inflater, R.layout.fragment_conversation, container, false);
        binding.getRoot().setOnClickListener(null); // TODO why the fuck did we do this?
        textsendBasePaddingLeft = binding.textsend.getPaddingLeft();
        textsendBasePaddingTop = binding.textsend.getPaddingTop();
        textsendBasePaddingRight = binding.textsend.getPaddingRight();
        textsendBasePaddingBottom = binding.textsend.getPaddingBottom();
        defaultComposerIslandColor =
                binding.composerIsland.getCardBackgroundColor().getDefaultColor();
        installChatWindowInsets();
        installAccessibleTouchTargets();

        binding.textinput.addTextChangedListener(
                new StylingHelper.MessageEditorStyler(binding.textinput));
        setupComposerFormattingToolbar();

        binding.textinput.setOnEditorActionListener(mEditorActionListener);
        binding.textinput.setRichContentListener(new String[] {"image/*"}, mEditorContentListener);

        binding.textSendButton.setOnClickListener(this.mSendButtonListener);
        binding.textSendButton.setOnLongClickListener(this.mSendButtonLongClickListener);
        binding.textSendButton.setOnTouchListener(this.mSendButtonTouchListener);
        binding.voiceRecordingCancelButton.setOnClickListener(v -> cancelInlineVoiceRecording());
        binding.textAttachButton.setOnClickListener(v -> showComposerAttachmentMenu());
        binding.contextPreviewCancel.setOnClickListener(
                (v) -> {
                    setupReply(null);
                });

        binding.scrollToBottomButton.setOnClickListener(this.mScrollButtonListener);
        binding.messagesView.setOnScrollListener(mOnScrollListener);
        // Chat follow/preserve behavior is explicit below. Framework transcript mode used
        // to race it and could move the list when the last row merely intersected the viewport.
        binding.messagesView.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        binding.textsend.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateMessageListChromePadding());
        binding.textsend.post(this::updateMessageListChromePadding);
        mediaPreviewAdapter = new MediaPreviewAdapter(this);
        binding.mediaPreview.setAdapter(mediaPreviewAdapter);
        messageListAdapter = new MessageAdapter((XmppActivity) getActivity(), this.messageList);
        messageListAdapter.setOnContactPictureClicked(this);
        messageListAdapter.setOnContactPictureLongClicked(this);
        MessageAdapter.MessageEmptyPartClickListener messageClickListener =
                new MessageAdapter.MessageEmptyPartClickListener() {
                    @Override
                    public void onMessageEmptyPartClick(Message message) {
                        if (selectionActionMode != null) {
                            toggleMessageSelection(message);
                        }
                    }

                    @Override
                    public void onMessageEmptyPartLongClick(Message message) {
                        toggleMessageSelection(message);
                    }
                };

        MessageAdapter.SelectionStatusProvider provider =
                new MessageAdapter.SelectionStatusProvider() {
                    @Override
                    public boolean isSelected(Message message) {
                        return selectedMessages.contains(message);
                    }

                    @Override
                    public boolean isSomethingSelected() {
                        return !selectedMessages.isEmpty();
                    }
                };
        messageListAdapter.setMessageEmptyPartLongClickListener(messageClickListener);
        messageListAdapter.setMessageClickListener(
                message -> {
                    if (selectionActionMode != null) {
                        toggleMessageSelection(message);
                        return;
                    }

                    new MessageActionController(
                                    new MaterialMessageActionSheet(requireActivity()),
                                    new MessageActionController.Host() {
                                        @Override
                                        public void onMessageAction(
                                                Message selectedMessage, MessageAction action) {
                                            handleMessageAction(selectedMessage, action);
                                        }

                                        @Override
                                        public void onQuickReaction(
                                                Message selectedMessage, QuickReaction reaction) {
                                            final Message reactionTarget =
                                                    messageListAdapter.getReactionTarget(
                                                            selectedMessage);
                                            if (reactionTarget == null) {
                                                return;
                                            }
                                            final var reactions =
                                                    Reaction.toggle(
                                                            reactionTarget.getAggregatedReactions()
                                                                    .ourReactions,
                                                            reaction.getEmoji());
                                            if (activity.xmppConnectionService.sendReactions(
                                                    reactionTarget, reactions)) {
                                                return;
                                            }
                                            Toast.makeText(
                                                            activity,
                                                            R.string.could_not_add_reaction,
                                                            Toast.LENGTH_LONG)
                                                    .show();
                                        }

                                        @Override
                                        public void onMoreReactions(Message selectedMessage) {
                                            final Message reactionTarget =
                                                    messageListAdapter.getReactionTarget(
                                                            selectedMessage);
                                            if (reactionTarget == null) {
                                                return;
                                            }
                                            final Intent intent =
                                                    new Intent(activity, AddReactionActivity.class);
                                            intent.putExtra(
                                                    "conversation",
                                                    reactionTarget.getConversation().getUuid());
                                            intent.putExtra("message", reactionTarget.getUuid());
                                            activity.startActivity(intent);
                                        }
                                    },
                                    new MessageActionResolver(requireActivity()),
                                    new QuickReactionResolver())
                            .show(
                                    message,
                                    ownsMessageMediaFile(message),
                                    message.getConversation() instanceof Conversation c
                                            && activity.xmppConnectionService.canModerateMessage(
                                                    c, message));
                });
        messageListAdapter.setSelectionStatusProvider(provider);
        messageListAdapter.setOnMessageBoxSwiped(
                new MessageAdapter.MessageBoxSwipedListener() {
                    @Override
                    public void onMessageBoxReleasedAfterSwipe(Message message) {
                        if (selectionActionMode == null) {
                            quoteMessage(message);
                        }
                    }

                    @Override
                    public void onMessageBoxSwipedEnough() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            vibrator.vibrate(
                                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(10L, 127));
                        } else {
                            vibrator.vibrate(10L);
                        }
                    }
                });
        messageListAdapter.setReplyClickListener(this::scrollToReply);
        messageListAdapter.setOnDateSeparatorClickListener(
                timestamp ->
                        startActivityForResult(
                                ConversationCalendarActivity.Companion.createIntent(
                                        activity, conversation.getUuid(), timestamp),
                                REQUEST_PICK_DATE));

        binding.messagesView.setAdapter(messageListAdapter);

        registerForContextMenu(binding.messagesView);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.binding.textinput.setCustomInsertionActionModeCallback(
                    new EditMessageActionModeCallback(this.binding.textinput));
            this.binding.textinput.setCustomSelectionActionModeCallback(
                    new EditMessageSelectionActionModeCallback());
        }

        binding.tabLayout.setListener(visibiltyChangeListener);

        binding.iconQuote.setColorFilter(getOrCalculatePrimaryColor());

        binding.contextPreviewImage.setClipToOutline(true);
        binding.contextPreviewImage.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        float maxRadius = Integer.min(view.getWidth(), view.getHeight()) / 2f;
                        float radius = Float.min(dpToPx(4), maxRadius);
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });

        return binding.getRoot();
    }

    private void setupComposerFormattingToolbar() {
        binding.formatBoldButton.setCheckable(true);
        binding.formatItalicButton.setCheckable(true);
        binding.formatStrikeButton.setCheckable(true);
        binding.formatCodeButton.setCheckable(true);

        binding.textinput.setOnSelectionChangedListener(
                (selectionStart, selectionEnd) -> {
                    rememberComposerFormattingSelection(selectionStart, selectionEnd);
                    updateComposerFormattingToolbar();
                });

        final View.OnTouchListener preserveSelectionOnPress =
                (view, event) -> {
                    final int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        composerFormattingPressInProgress = true;
                        rememberComposerFormattingSelection(
                                binding.textinput.getSelectionStart(),
                                binding.textinput.getSelectionEnd());
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        // Keep ownership through the ensuing onClick. If the gesture is cancelled
                        // or no click is produced, release it on the next frame.
                        view.post(
                                () -> {
                                    if (composerFormattingPressInProgress) {
                                        composerFormattingPressInProgress = false;
                                        updateComposerFormattingToolbar();
                                    }
                                });
                    }
                    return false;
                };
        binding.formatBoldButton.setOnTouchListener(preserveSelectionOnPress);
        binding.formatItalicButton.setOnTouchListener(preserveSelectionOnPress);
        binding.formatStrikeButton.setOnTouchListener(preserveSelectionOnPress);
        binding.formatCodeButton.setOnTouchListener(preserveSelectionOnPress);
        binding.formatMoreButton.setOnTouchListener(preserveSelectionOnPress);

        binding.formatBoldButton.setOnClickListener(v -> applyComposerFormatting('*'));
        binding.formatItalicButton.setOnClickListener(v -> applyComposerFormatting('_'));
        binding.formatStrikeButton.setOnClickListener(v -> applyComposerFormatting('~'));
        binding.formatCodeButton.setOnClickListener(v -> applyComposerFormatting('`'));
        binding.formatMoreButton.setOnClickListener(
                v -> {
                    restoreComposerFormattingSelectionIfNeeded();
                    composerFormattingPressInProgress = false;
                    showComposerTextActions();
                });

        ViewCompat.setTooltipText(binding.formatBoldButton, getString(R.string.formatting_bold));
        ViewCompat.setTooltipText(
                binding.formatItalicButton, getString(R.string.formatting_italic));
        ViewCompat.setTooltipText(
                binding.formatStrikeButton, getString(R.string.formatting_strikethrough));
        ViewCompat.setTooltipText(binding.formatCodeButton, getString(R.string.formatting_code));
        ViewCompat.setTooltipText(
                binding.formatMoreButton, getString(R.string.formatting_more_actions));
        updateComposerFormattingToolbar();
    }

    private void rememberComposerFormattingSelection(
            final int selectionStart, final int selectionEnd) {
        if (selectionStart < 0 || selectionEnd < 0 || selectionStart == selectionEnd) {
            return;
        }
        composerFormattingSelectionStart = selectionStart;
        composerFormattingSelectionEnd = selectionEnd;
    }

    private void restoreComposerFormattingSelectionIfNeeded() {
        if (binding == null || binding.textinput.hasFormattableSelection()) {
            return;
        }
        final int length = binding.textinput.length();
        final int start = composerFormattingSelectionStart;
        final int end = composerFormattingSelectionEnd;
        if (start < 0 || end < 0 || start == end || start > length || end > length) {
            return;
        }
        binding.textinput.requestFocus();
        binding.textinput.setSelection(start, end);
    }

    private void showComposerTextActions() {
        if (binding == null || !binding.textinput.hasFormattableSelection()) {
            return;
        }
        final Context context = getActivity();
        if (context == null) {
            return;
        }

        final PopupMenu popup = new PopupMenu(context, binding.formatMoreButton);
        final Menu menu = popup.getMenu();
        menu.add(Menu.NONE, TEXT_ACTION_CUT, 0, R.string.text_action_cut);
        menu.add(Menu.NONE, TEXT_ACTION_COPY, 1, R.string.text_action_copy);
        menu.add(Menu.NONE, TEXT_ACTION_PASTE, 2, R.string.text_action_paste);
        menu.add(Menu.NONE, TEXT_ACTION_SELECT_ALL, 3, R.string.text_action_select_all);
        menu.add(Menu.NONE, TEXT_ACTION_PASTE_AS_QUOTE, 4, R.string.paste_as_quote);

        final ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        final boolean hasClipboardText =
                clip != null
                        && clip.getItemCount() > 0
                        && !TextUtils.isEmpty(clip.getItemAt(0).coerceToText(context));
        menu.findItem(TEXT_ACTION_PASTE).setEnabled(hasClipboardText);
        menu.findItem(TEXT_ACTION_PASTE_AS_QUOTE).setEnabled(hasClipboardText);
        menu.findItem(TEXT_ACTION_SELECT_ALL)
                .setEnabled(
                        binding.textinput.getText() != null
                                && Math.abs(
                                                binding.textinput.getSelectionEnd()
                                                        - binding.textinput.getSelectionStart())
                                        < binding.textinput.getText().length());

        popup.setOnMenuItemClickListener(
                item -> {
                    final int id = item.getItemId();
                    if (id == TEXT_ACTION_CUT) {
                        return binding.textinput.onTextContextMenuItem(android.R.id.cut);
                    } else if (id == TEXT_ACTION_COPY) {
                        return binding.textinput.onTextContextMenuItem(android.R.id.copy);
                    } else if (id == TEXT_ACTION_PASTE) {
                        return binding.textinput.onTextContextMenuItem(android.R.id.paste);
                    } else if (id == TEXT_ACTION_SELECT_ALL) {
                        return binding.textinput.onTextContextMenuItem(android.R.id.selectAll);
                    } else if (id == TEXT_ACTION_PASTE_AS_QUOTE) {
                        final ClipData current =
                                clipboard == null ? null : clipboard.getPrimaryClip();
                        if (current != null && current.getItemCount() > 0) {
                            final CharSequence text = current.getItemAt(0).coerceToText(context);
                            if (!TextUtils.isEmpty(text)) {
                                binding.textinput.insertAsQuote(text.toString());
                                return true;
                            }
                        }
                    }
                    return false;
                });
        popup.show();
    }

    private void applyComposerFormatting(final char marker) {
        if (binding == null) {
            return;
        }
        restoreComposerFormattingSelectionIfNeeded();
        composerFormattingPressInProgress = false;
        if (!binding.textinput.toggleSelectionStyle(marker)) {
            updateComposerFormattingToolbar();
            return;
        }
        binding.textinput.requestFocus();
        rememberComposerFormattingSelection(
                binding.textinput.getSelectionStart(), binding.textinput.getSelectionEnd());
        updateComposerFormattingToolbar();
    }

    private void updateComposerFormattingToolbar() {
        if (binding == null) {
            return;
        }
        final boolean shouldShow =
                !voiceRecordingActive
                        && binding.inputLayout.getVisibility() == View.VISIBLE
                        && binding.textinput.isEnabled()
                        && binding.textinput.hasFormattableSelection();
        if (!shouldShow) {
            if (composerFormattingPressInProgress) {
                return;
            }
            setComposerFormattingToolbarVisible(false, true);
            return;
        }

        setComposerFormatButtonState(
                binding.formatBoldButton, binding.textinput.isSelectionStyled('*'));
        setComposerFormatButtonState(
                binding.formatItalicButton, binding.textinput.isSelectionStyled('_'));
        setComposerFormatButtonState(
                binding.formatStrikeButton, binding.textinput.isSelectionStyled('~'));
        setComposerFormatButtonState(
                binding.formatCodeButton, binding.textinput.isSelectionStyled('`'));
        setComposerFormattingToolbarVisible(true, true);
    }

    private void setComposerFormatButtonState(final MaterialButton button, final boolean checked) {
        button.setChecked(checked);
        final int background =
                checked
                        ? MaterialColors.getColor(
                                button, com.google.android.material.R.attr.colorSecondaryContainer)
                        : Color.TRANSPARENT;
        final int foreground =
                MaterialColors.getColor(
                        button,
                        checked
                                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                                : com.google.android.material.R.attr.colorOnSurfaceVariant);
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setIconTint(ColorStateList.valueOf(foreground));
    }

    private void setComposerFormattingToolbarVisible(final boolean visible, final boolean animate) {
        if (binding == null) {
            return;
        }
        final View toolbar = binding.formattingToolbar;
        toolbar.animate().cancel();

        if (visible) {
            if (toolbar.getVisibility() == View.VISIBLE) {
                toolbar.setAlpha(1f);
                toolbar.setTranslationY(0f);
                return;
            }
            toolbar.setVisibility(View.VISIBLE);
            if (!animate) {
                toolbar.setAlpha(1f);
                toolbar.setTranslationY(0f);
                return;
            }
            toolbar.setAlpha(0f);
            toolbar.setTranslationY(dpToPx(4f));
            toolbar.animate().alpha(1f).translationY(0f).setDuration(120L).start();
            return;
        }

        if (toolbar.getVisibility() != View.VISIBLE) {
            return;
        }
        if (!animate) {
            toolbar.setVisibility(View.GONE);
            toolbar.setAlpha(1f);
            toolbar.setTranslationY(0f);
            return;
        }
        toolbar.animate()
                .alpha(0f)
                .translationY(dpToPx(4f))
                .setDuration(90L)
                .withEndAction(
                        () -> {
                            toolbar.setVisibility(View.GONE);
                            toolbar.setAlpha(1f);
                            toolbar.setTranslationY(0f);
                        })
                .start();
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.getRoot().removeCallbacks(finishImeResizeRunnable);
            binding.messagesView.removeCallbacks(incomingMediaCoalescingRefreshRunnable);
            ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), null);
            binding.textinput.setOnSelectionChangedListener(null);
            binding.formattingToolbar.animate().cancel();
        }
        if (bottomScrollAnimator != null) {
            bottomScrollAnimator.cancel();
            bottomScrollAnimator = null;
        }
        imeResizeInProgress = false;
        pinBottomDuringImeResize = false;
        imeResizeScrollAnchor = null;
        passiveBottomPinPending = false;
        programmaticBottomPin = false;
        followLatestMessages = true;
        userScrollControlsFollowLatest = false;
        deferredTimelineRefreshWhileReading = false;
        pendingHistoryPageUiPublish = null;
        backwardHistoryPaginationArmed = true;
        suppressNextRefreshBottomFollow = false;
        composerFormattingPressInProgress = false;
        composerFormattingSelectionStart = -1;
        composerFormattingSelectionEnd = -1;
        lastImeBottomInset = -1;
        forceTimelineRebuild = true;
        renderedTimelineRevision = Long.MIN_VALUE;
        renderedDynamicTimelineSignature = Long.MIN_VALUE;
        renderedConversationPresentationRevision = Long.MIN_VALUE;
        renderedTimelineConversationUuid = null;
        binding.getRoot().removeCallbacks(finishAttachmentViewportTransactionRunnable);
        attachmentViewportAnchor = null;
        attachmentViewportConversationUuid = null;
        attachmentViewportKeepBottomPinned = false;
        attachmentViewportLaunchPending = false;
        attachmentViewportFinishPending = false;
        bottomPinGeneration++;
        chromePaddingSettleGeneration++;
        if (shouldPreserveInlineVoiceRecordingForRecreation()) {
            detachInlineVoiceRecordingUiForRecreation();
        } else {
            cancelInlineVoiceRecording();
        }
        pendingMediaDraft = null;
        pendingAttachmentConversationUuid = null;
        pendingMediaCommitConversationUuid = null;
        incomingMediaCoalescingConversationUuid = null;
        incomingMediaCoalescingStates.clear();
        incomingMediaKnownMessageUuids.clear();
        incomingMediaReleasedAnchorIds.clear();
        chatBackgroundPresetRenderKey = null;
        super.onDestroyView();
        Log.d(Config.LOGTAG, "ConversationFragment.onDestroyView()");
        messageListAdapter.setOnContactPictureClicked(null);
        messageListAdapter.setOnContactPictureLongClicked(null);
        messageListAdapter.setOnMessageBoxSwiped(null);
        messageListAdapter.setMessageEmptyPartLongClickListener(null);
        messageListAdapter.setReplyClickListener(null);
        messageListAdapter.setOnDateSeparatorClickListener(null);
        messageListAdapter.setSelectionStatusProvider(null);
        binding.messagesView.clearDragHelper();
        binding.conversationViewPager.setAdapter(null);
        if (conversation != null) conversation.setupViewPager(null, null, null);
        binding.tabLayout.setListener(null);
        applyTabElevationFix(false);
    }

    private void applyTabElevationFix(boolean tabsVisible) {
        // Legacy command tabs used to raise the whole fragment above the activity toolbar.
        // With the floating chat header that makes Conversation / Commands cover the header
        // and, in edge-to-edge mode, enter the status-bar area. Keep normal activity z-order.
        if (activity != null) {
            final View fragmentHostView = activity.getFragmentHostView();
            if (fragmentHostView != null && fragmentHostView.getElevation() != 0f) {
                fragmentHostView.setElevation(0f);
            }
        }
        updateCommandTabsChrome();
    }

    private void updateCommandTabsChrome() {
        if (binding == null) {
            return;
        }

        final boolean tabsVisible = binding.tabLayout.getVisibility() == View.VISIBLE;
        final int chatHeaderInset =
                chatSystemBarTopInset
                        + binding.getRoot()
                                .getResources()
                                .getDimensionPixelSize(R.dimen.conversation_header_chrome_height);

        final ViewGroup.LayoutParams rawTabParams = binding.tabLayout.getLayoutParams();
        if (rawTabParams instanceof android.widget.RelativeLayout.LayoutParams) {
            final android.widget.RelativeLayout.LayoutParams tabParams =
                    (android.widget.RelativeLayout.LayoutParams) rawTabParams;
            final int wantedTopMargin = tabsVisible ? chatHeaderInset : 0;
            final boolean changed =
                    tabParams.topMargin != wantedTopMargin
                            || tabParams.getMarginStart() != chatSystemBarLeftInset
                            || tabParams.getMarginEnd() != chatSystemBarRightInset;
            if (changed) {
                tabParams.topMargin = wantedTopMargin;
                tabParams.setMarginStart(chatSystemBarLeftInset);
                tabParams.setMarginEnd(chatSystemBarRightInset);
                binding.tabLayout.setLayoutParams(tabParams);
            }
        }

        final ViewGroup.LayoutParams rawSubjectParams = binding.mucSubject.getLayoutParams();
        if (rawSubjectParams instanceof android.widget.RelativeLayout.LayoutParams) {
            final android.widget.RelativeLayout.LayoutParams subjectParams =
                    (android.widget.RelativeLayout.LayoutParams) rawSubjectParams;
            final int wantedTopMargin = tabsVisible ? 0 : chatHeaderInset;
            final int wantedStartMargin = chatSystemBarLeftInset + dpToPx(8);
            final int wantedEndMargin = chatSystemBarRightInset + dpToPx(8);
            final boolean changed =
                    subjectParams.topMargin != wantedTopMargin
                            || subjectParams.getMarginStart() != wantedStartMargin
                            || subjectParams.getMarginEnd() != wantedEndMargin;
            if (changed) {
                subjectParams.topMargin = wantedTopMargin;
                subjectParams.setMarginStart(wantedStartMargin);
                subjectParams.setMarginEnd(wantedEndMargin);
                binding.mucSubject.setLayoutParams(subjectParams);
            }
        }

        updateMessageListChromePadding();
    }

    private void quoteText(String text) {
        if (binding.textinput.isEnabled()) {
            binding.textinput.insertAsQuote(text);
            binding.textinput.requestFocus();
            InputMethodManager inputMethodManager =
                    (InputMethodManager)
                            getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(
                        binding.textinput, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private Message getMessageInteractionTarget(final Message message) {
        synchronized (this.messageList) {
            if (mediaCaptionPresentation != null
                    && mediaCaptionPresentation.isCaptionChild(message)) {
                // Selection and reply retain their existing media-anchor interaction semantics.
                for (final Message candidate : this.messageList) {
                    if (mediaCaptionPresentation.getCaption(candidate) == message) {
                        return candidate;
                    }
                }
            }
        }
        return message;
    }

    private void quoteMessage(Message message) {
        message = getMessageInteractionTarget(message);
        if (message.isPrivateMessage()) privateMessageWith(message.getCounterpart());
        setupReply(message);
    }

    private void handleMessageAction(final Message message, final MessageAction action) {
        switch (action.getType()) {
            case REPLY:
                quoteMessage(message);
                break;
            case EDIT:
                correctMessage(message);
                break;
            case FORWARD:
                ShareUtil.forward(activity, message);
                break;
            case COPY:
                ShareUtil.copyToClipboard(activity, message);
                break;
            case SAVE_TO_SAVED_MESSAGES:
                forwardToSavedMessages(message);
                break;
            case SELECT:
                toggleMessageSelection(message);
                break;
            case SAVE_TO_DOWNLOADS:
                saveToDownloads(message);
                break;
            case SAVE_TO_GALLERY:
                saveToGallery(message);
                break;
            case OPEN_WITH:
                openWith(message);
                break;
            case COPY_URL:
                ShareUtil.copyUrlToClipboard(activity, message);
                break;
            case COPY_LINK:
                ShareUtil.copyLinkToClipboard(activity, message);
                break;
            case DOWNLOAD:
                startDownloadable(message);
                break;
            case DELETE_FILE:
                deleteFile(message);
                break;
            case RETRY:
                resendMessage(message, false);
                break;
            case RETRY_AS_P2P:
                resendMessage(message, true);
                break;
            case SHOW_ERROR:
                showErrorMessage(message);
                break;
            case CANCEL_TRANSFER:
                cancelTransmission(message);
                break;
            case DELETE_LOCALLY:
                deleteLocally(message);
                break;
            case MODERATE_MESSAGE:
                moderateMessageFromActionSheet(message);
                break;
            default:
                break;
        }
    }

    private void moderateMessageFromActionSheet(final Message message) {
        final ConversationsActivity hostActivity = this.activity;
        final XmppConnectionService service =
                hostActivity == null ? null : hostActivity.xmppConnectionService;
        if (!(message.getConversation() instanceof Conversation room)
                || service == null
                || !service.canModerateMessage(room, message)) {
            return;
        }
        new MaterialAlertDialogBuilder(hostActivity)
                .setTitle(R.string.muc_delete_message_title)
                .setMessage(R.string.muc_delete_message_explanation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.delete,
                        (dialog, which) ->
                                service.moderateMessage(
                                        room,
                                        message,
                                        accepted -> {
                                            if (!accepted) {
                                                final ConversationsActivity currentActivity =
                                                        this.activity;
                                                if (currentActivity != null) {
                                                    currentActivity.runOnUiThread(
                                                            () ->
                                                                    Toast.makeText(
                                                                                    currentActivity,
                                                                                    R.string
                                                                                            .muc_moderation_failed,
                                                                                    Toast
                                                                                            .LENGTH_SHORT)
                                                                            .show());
                                                }
                                            }
                                        }))
                .show();
    }

    private void consumeReplyAfterSuccessfulSend(
            final Conversation targetConversation, @Nullable final Message replyTo) {
        if (targetConversation.getReplyTo() != replyTo) {
            return;
        }
        if (conversation == targetConversation && binding != null && getView() != null) {
            setupReply(null);
        } else {
            targetConversation.setReplyTo(null);
        }
    }

    private void setupReply(Message message) {
        Message oldReplyTo = conversation.getReplyTo();
        conversation.setReplyTo(message);
        refreshPendingMediaDraftFromComposer();
        if (message == null) {
            binding.contextPreview.setVisibility(View.GONE);
            return;
        }

        final SpannableStringBuilder body =
                message.getBodyForReplyPreview(activity.xmppConnectionService);
        TypographyHelper.applyCompact(body);

        if (message.isFileOrImage()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            if (message.getFileParams().width > 0 && message.getFileParams().height > 0) {
                binding.contextPreviewImage.setVisibility(View.VISIBLE);
                binding.contextPreviewDoc.setVisibility(View.GONE);
                binding.contextPreviewAudio.setVisibility(View.GONE);
                activity.loadBitmap(message, binding.contextPreviewImage);
            } else if (message.getFileParams().runtime > 0) {
                binding.contextPreviewImage.setVisibility(View.GONE);
                binding.contextPreviewDoc.setVisibility(View.GONE);
                binding.contextPreviewAudio.setVisibility(View.VISIBLE);
            } else {
                binding.contextPreviewImage.setVisibility(View.GONE);
                binding.contextPreviewDoc.setVisibility(View.VISIBLE);
                binding.contextPreviewAudio.setVisibility(View.GONE);
            }
        } else if (message.isOOb()) {
            messageListAdapter.handleTextQuotes(
                    binding.contextPreviewText,
                    body,
                    MessageAdapter.BubbleColor.SURFACE,
                    true,
                    message);
            binding.contextPreviewImage.setVisibility(View.GONE);
            binding.contextPreviewDoc.setVisibility(View.GONE);
            binding.contextPreviewAudio.setVisibility(View.GONE);
            body.append(" 🖼️");
        } else {
            messageListAdapter.handleTextQuotes(
                    binding.contextPreviewText,
                    body,
                    MessageAdapter.BubbleColor.SURFACE,
                    true,
                    message);
            binding.contextPreviewImage.setVisibility(View.GONE);
            binding.contextPreviewDoc.setVisibility(View.GONE);
            binding.contextPreviewAudio.setVisibility(View.GONE);
        }
        binding.contextPreviewText.setText(body);
        binding.contextPreviewAuthor.setText(message.getAvatarName());
        binding.contextPreview.setVisibility(View.VISIBLE);

        if (oldReplyTo != message) {
            showKeyboard(binding.textinput);
        }
    }

    private void scrollToReply(Message message) {
        Element reply = message.getReplyOrReaction();

        if (reply == null) {
            previousClickedReply = null;
            return;
        }
        String replyId = reply.getAttribute("id");

        if (replyId != null) {
            Runnable postSelectionRunnable = () -> highlightMessage(replyId);
            previousClickedReply = message;
            updateSelection(replyId, postSelectionRunnable, true, false);
        }
    }

    private void highlightMessage(String uuid) {
        if (binding == null) {
            return;
        }
        binding.messagesView.post(
                () -> {
                    final int actualIndex = getIndexOfExtended(uuid, messageList);
                    final View view = getVisibleMessageView(actualIndex);
                    if (view == null) {
                        return;
                    }
                    final HighlighterView highlighter = view.findViewById(R.id.highlighter);
                    if (highlighter != null) {
                        highlighter.setVisibility(View.VISIBLE);
                    }
                });
    }

    @Nullable
    private View getVisibleMessageView(final int position) {
        if (binding == null || position < 0) {
            return null;
        }
        final ListView listView = binding.messagesView;
        final int childIndex = position - listView.getFirstVisiblePosition();
        if (childIndex < 0 || childIndex >= listView.getChildCount()) {
            return null;
        }
        return listView.getChildAt(childIndex);
    }

    private void centerMessageInViewport(
            final String uuid, @Nullable final Runnable selectionUpdatedRunnable) {
        if (binding == null || !uuid.equals(pendingSelectionUuid)) {
            return;
        }
        final int position = getIndexOfExtended(uuid, messageList);
        if (position < 0) {
            return;
        }

        // First make the row part of the real ListView layout. Measuring an adapter-created
        // detached row gives the wrong result for media/reply bubbles and was the source of the
        // visible jump offset.
        final ListView listView = binding.messagesView;
        listView.setSelectionFromTop(position, listView.getPaddingTop());
        listView.post(() -> finishCenterMessageInViewport(uuid, selectionUpdatedRunnable, 0));
    }

    private void finishCenterMessageInViewport(
            final String uuid,
            @Nullable final Runnable selectionUpdatedRunnable,
            final int attempt) {
        if (binding == null || !uuid.equals(pendingSelectionUuid)) {
            return;
        }
        final int position = getIndexOfExtended(uuid, messageList);
        if (position < 0) {
            return;
        }

        final ListView listView = binding.messagesView;
        final View row = getVisibleMessageView(position);
        if (row == null) {
            if (attempt < 3) {
                listView.setSelection(position);
                listView.post(
                        () ->
                                finishCenterMessageInViewport(
                                        uuid, selectionUpdatedRunnable, attempt + 1));
            }
            return;
        }

        // Center inside the actually usable chat viewport. The ListView intentionally keeps
        // protected padding under the top island and above the composer; while the IME is open
        // the bottom padding also contains the keyboard inset. Using the raw ListView height here
        // therefore centers the row partly behind the composer/keyboard.
        final int viewportTop = listView.getPaddingTop();
        final int viewportBottom =
                Math.max(viewportTop, listView.getHeight() - listView.getPaddingBottom());
        final int viewportHeight = Math.max(0, viewportBottom - viewportTop);
        final int centeredOffset =
                viewportTop + Math.max(0, (viewportHeight - row.getHeight()) / 2);
        if (Math.abs(row.getTop() - centeredOffset) > 1 && attempt < 3) {
            listView.setSelectionFromTop(position, centeredOffset);
            listView.post(
                    () ->
                            finishCenterMessageInViewport(
                                    uuid, selectionUpdatedRunnable, attempt + 1));
            return;
        }

        // The target has been resolved and laid out. Future list mutations must not keep
        // treating this completed navigation as a pending jump.
        pendingSelectionUuid = null;
        if (selectionUpdatedRunnable != null) {
            selectionUpdatedRunnable.run();
        }
    }

    private void updateSelection(
            final String uuid,
            @Nullable final Runnable selectionUpdatedRunnable,
            final boolean populateFromMam,
            final boolean recursiveFetch) {
        if (recursiveFetch && (fetchHistoryDialog == null || !fetchHistoryDialog.isShowing())) {
            return;
        }

        pendingSelectionUuid = uuid;
        final int position = getIndexOfExtended(uuid, messageList);
        if (position != -1) {
            hideFetchHistoryDialog();
            centerMessageInViewport(uuid, selectionUpdatedRunnable);
            return;
        }

        activity.xmppConnectionService.jumpToMessage(
                conversation,
                uuid,
                new XmppConnectionService.JumpToMessageListener() {
                    @Override
                    public void onSuccess() {
                        activity.runOnUiThread(
                                () -> {
                                    if (!uuid.equals(pendingSelectionUuid)) {
                                        return;
                                    }
                                    refresh(false);
                                    conversation.messagesLoaded.set(true);
                                    conversation.historyPartLoadedForward.set(true);
                                    toggleScrollDownButton();
                                    updateSelection(
                                            uuid, selectionUpdatedRunnable, populateFromMam, false);
                                });
                    }

                    @Override
                    public void onNotFound() {
                        activity.runOnUiThread(
                                () -> {
                                    if (!uuid.equals(pendingSelectionUuid)) {
                                        return;
                                    }
                                    if (populateFromMam && conversation.hasMessagesLeftOnServer()) {
                                        showFetchHistoryDialog();
                                        loadMoreMessages(true, false, binding.messagesView);
                                        binding.messagesView.postDelayed(
                                                () ->
                                                        updateSelection(
                                                                uuid,
                                                                selectionUpdatedRunnable,
                                                                populateFromMam,
                                                                true),
                                                500L);
                                    } else {
                                        hideFetchHistoryDialog();
                                        pendingSelectionUuid = null;

                                        if (populateFromMam && isAdded() && activity != null) {
                                            Toast.makeText(
                                                            activity,
                                                            R.string.reply_original_unavailable,
                                                            Toast.LENGTH_SHORT)
                                                    .show();
                                            previousClickedReply = null;
                                        }
                                    }
                                });
                    }
                });
    }

    private void showFetchHistoryDialog() {
        if (fetchHistoryDialog != null && fetchHistoryDialog.isShowing()) return;

        fetchHistoryDialog = new ProgressDialog(getActivity());
        fetchHistoryDialog.setIndeterminate(true);
        fetchHistoryDialog.setMessage(getString(R.string.please_wait));
        fetchHistoryDialog.setCancelable(true);
        fetchHistoryDialog.show();
    }

    private void hideFetchHistoryDialog() {
        if (fetchHistoryDialog != null && fetchHistoryDialog.isShowing()) {
            fetchHistoryDialog.hide();
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        // This should cancel any remaining click events that would otherwise trigger links
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
        synchronized (this.messageList) {
            super.onCreateContextMenu(menu, v, menuInfo);
            AdapterView.AdapterContextMenuInfo acmi = (AdapterContextMenuInfo) menuInfo;
            this.selectedMessage = this.messageList.get(acmi.position);
            populateContextMenu(menu);
        }
    }

    private void populateContextMenu(final ContextMenu menu) {
        final Message m = this.selectedMessage;
        final Transferable t = m.getTransferable();
        if (m.getType() != Message.TYPE_STATUS && m.getType() != Message.TYPE_RTP_SESSION) {

            if (m.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                    || m.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
                return;
            }

            if (m.getStatus() == Message.STATUS_RECEIVED
                    && t != null
                    && (t.getStatus() == Transferable.STATUS_CANCELLED
                            || t.getStatus() == Transferable.STATUS_FAILED)) {
                return;
            }

            final boolean deleted = m.isDeleted();
            final boolean encrypted =
                    m.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED
                            || m.getEncryption() == Message.ENCRYPTION_PGP;
            final boolean receiving =
                    m.getStatus() == Message.STATUS_RECEIVED
                            && (t instanceof JingleFileTransferConnection
                                    || t instanceof HttpDownloadConnection);
            activity.getMenuInflater().inflate(R.menu.message_context, menu);
            menu.setHeaderTitle(R.string.message_options);
            final MenuItem addReaction = menu.findItem(R.id.action_add_reaction);
            final MenuItem reportAndBlock = menu.findItem(R.id.action_report_and_block);
            final MenuItem manageMucParticipant = menu.findItem(R.id.muc_manage_participant);
            final MenuItem moderateMucMessage = menu.findItem(R.id.muc_moderate_message);
            final MenuItem openWith = menu.findItem(R.id.open_with);
            final MenuItem copyMessage = menu.findItem(R.id.copy_message);
            final MenuItem copyLink = menu.findItem(R.id.copy_link);
            final MenuItem quoteMessage = menu.findItem(R.id.quote_message);
            final MenuItem retryDecryption = menu.findItem(R.id.retry_decryption);
            final MenuItem correctMessage = menu.findItem(R.id.correct_message);
            final MenuItem deleteLocally = menu.findItem(R.id.delete_locally);
            final MenuItem retractMessage = menu.findItem(R.id.retract_message);
            MenuItem shareWith = menu.findItem(R.id.share_with);
            final MenuItem saveToSavedMessages = menu.findItem(R.id.save_to_saved_messages);
            final MenuItem sendAgain = menu.findItem(R.id.send_again);
            final MenuItem retryAsP2P = menu.findItem(R.id.send_again_as_p2p);
            final MenuItem copyUrl = menu.findItem(R.id.copy_url);
            MenuItem saveToDownloads = menu.findItem(R.id.save_to_downloads);
            final MenuItem downloadFile = menu.findItem(R.id.download_file);
            final MenuItem cancelTransmission = menu.findItem(R.id.cancel_transmission);
            final MenuItem deleteFile = menu.findItem(R.id.delete_file);
            final MenuItem showErrorMessage = menu.findItem(R.id.show_error_message);
            MenuItem selectMessage = menu.findItem(R.id.select_message);
            if (m.isModerated()) {
                deleteLocally.setVisible(true);
                return;
            }
            if (m.getConversation() instanceof Conversation c
                    && activity.xmppConnectionService.canModerateMessage(c, m)) {
                moderateMucMessage.setVisible(true);
            }
            final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(m);
            final boolean showError =
                    m.getStatus() == Message.STATUS_SEND_FAILED
                            && m.getErrorMessage() != null
                            && !Message.ERROR_MESSAGE_CANCELLED.equals(m.getErrorMessage());
            final Conversational conversational = m.getConversation();
            if (m.getStatus() == Message.STATUS_RECEIVED
                    && conversational instanceof Conversation c) {
                final XmppConnection connection = c.getAccount().getXmppConnection();
                if (c.isWithStranger()
                        && m.getServerMsgId() != null
                        && !c.isBlocked()
                        && connection != null
                        && connection.getFeatures().spamReporting()) {
                    reportAndBlock.setVisible(true);
                }
            }
            if (conversational instanceof Conversation c) {
                addReaction.setVisible(
                        !showError
                                && !m.isDeleted()
                                && (c.getMode() == Conversational.MODE_SINGLE
                                        || (c.getMucOptions().occupantId()
                                                && c.getMucOptions().participating())));
            } else {
                addReaction.setVisible(false);
            }

            if (conversational instanceof Conversation c
                    && c.getMode() == Conversational.MODE_MULTI
                    && m.getStatus() == Message.STATUS_RECEIVED
                    && manageMucParticipant != null
                    && manageMucParticipant.getSubMenu() != null) {
                final User target = resolveMucUserForMessage(m);
                manageMucParticipant.setVisible(
                        target != null
                                && MucDetailsContextMenuHelper.configureMessageModerationMenu(
                                        activity, manageMucParticipant.getSubMenu(), c, target));
            }
            if (!m.isFileOrImage()
                    && !encrypted
                    && !m.isGeoUri()
                    && !m.treatAsDownloadable()
                    && !unInitiatedButKnownSize
                    && t == null) {
                copyMessage.setVisible(true);
                quoteMessage.setVisible(!showError && !MessageUtils.prepareQuote(m).isEmpty());
                final String scheme = ShareUtil.getLinkScheme(m.getBodyForDisplaying());
                if ("xmpp".equals(scheme)) {
                    copyLink.setTitle(R.string.copy_jabber_id);
                    copyLink.setVisible(true);
                } else if (scheme != null) {
                    copyLink.setVisible(true);
                }
            }

            if (m.getConversation() instanceof Conversation) {
                deleteLocally.setVisible(true);
            }

            if (!showError && m.isCorrectableMessage()) {
                correctMessage.setVisible(true);
            }

            if (!showError && m.isCorrectableMessage()) {
                correctMessage.setVisible(true);

                if (!m.getBody().equals("") && !m.getBody().equals(" ")) {
                    retractMessage.setVisible(true);
                }
            }

            if (m.getReactions() != null) {
                correctMessage.setVisible(false);
                retractMessage.setVisible(true);
            }

            if ((m.isFileOrImage() && !deleted && !receiving)
                    || (m.getType() == Message.TYPE_TEXT && !m.treatAsDownloadable())
                            && !unInitiatedButKnownSize
                            && t == null) {
                shareWith.setVisible(true);
                if (m.getConversation() instanceof Conversation sourceConversation
                        && !sourceConversation.withSelf()) {
                    saveToSavedMessages.setVisible(true);
                }
            }
            if (m.getStatus() == Message.STATUS_SEND_FAILED) {
                sendAgain.setVisible(true);
                final var fileNotUploaded = m.isFileOrImage() && !m.hasFileOnRemoteHost();
                final var isPeerOnline =
                        conversational.getMode() == Conversation.MODE_SINGLE
                                && (conversational instanceof Conversation c)
                                && !c.getContact().getPresences().isEmpty();
                retryAsP2P.setVisible(fileNotUploaded && isPeerOnline);
            }
            if (m.hasFileOnRemoteHost()
                    || m.isGeoUri()
                    || m.treatAsDownloadable()
                    || unInitiatedButKnownSize
                    || t instanceof HttpDownloadConnection) {
                copyUrl.setVisible(true);
            }

            if (m.isFileOrImage() && deleted && m.hasFileOnRemoteHost()) {
                downloadFile.setVisible(true);
                downloadFile.setTitle(
                        activity.getString(
                                R.string.download_x_file,
                                UIHelper.getFileDescriptionString(activity, m)));
            }
            final boolean waitingOfferedSending =
                    m.getStatus() == Message.STATUS_WAITING
                            || m.getStatus() == Message.STATUS_UNSEND
                            || m.getStatus() == Message.STATUS_OFFERED;
            final boolean cancelable =
                    (t != null && !deleted) || waitingOfferedSending && m.needsUploading();
            if (cancelable) {
                cancelTransmission.setVisible(true);
            }
            if (m.isFileOrImage() && !deleted && !cancelable) {
                if (ownsMessageMediaFile(m)) {
                    deleteFile.setVisible(true);

                    String fileDescriptorString = UIHelper.getFileDescriptionString(activity, m);
                    deleteFile.setTitle(
                            activity.getString(R.string.delete_x_file, fileDescriptorString));
                }

                saveToDownloads.setVisible(true);
            }
            if (showError) {
                showErrorMessage.setVisible(true);
            }
            final String mime = m.isFileOrImage() ? m.getMimeType() : null;
            if ((m.isGeoUri() && GeoHelper.openInOsmAnd(getActivity(), m))
                    || (mime != null && mime.startsWith("audio/"))) {
                openWith.setVisible(true);
            }

            selectMessage.setVisible(true);
        }
    }

    private @Nullable User resolveMucUserForMessage(final Message message) {
        if (message == null
                || !(message.getConversation() instanceof Conversation mucConversation)) {
            return null;
        }
        if (mucConversation.getMode() != Conversational.MODE_MULTI) {
            return null;
        }

        return mucConversation.getMucOptions().resolveUser(message);
    }

    private void forwardToSavedMessages(final Message message) {
        if (message == null || activity == null || activity.xmppConnectionService == null) {
            return;
        }
        final Conversation sourceConversation =
                message.getConversation() instanceof Conversation c ? c : null;
        if (sourceConversation == null) {
            return;
        }
        final Account account = sourceConversation.getAccount();
        final Conversation savedMessages =
                activity.xmppConnectionService.findOrCreateConversation(
                        account, account.getJid().asBareJid(), null, false, false, true, null);

        if (message.isFileOrImage() && !message.isDeleted()) {
            forwardMediaToSavedMessages(message, savedMessages);
            return;
        }

        final Message saved =
                new Message(
                        savedMessages,
                        message.getBodyForDisplaying().toString(),
                        savedMessages.getNextEncryption());
        activity.xmppConnectionService.sendMessage(saved);
        Toast.makeText(activity, R.string.saved_to_saved_messages, Toast.LENGTH_SHORT).show();
    }

    private void forwardMediaToSavedMessages(
            final Message message, final Conversation savedMessages) {
        final ConversationsActivity targetActivity = activity;
        if (targetActivity == null || targetActivity.xmppConnectionService == null) {
            return;
        }
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            forwardLegacyMediaToSavedMessages(targetActivity, message, savedMessages);
            return;
        }
        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(
                () -> {
                    try {
                        final Conversations application =
                                (Conversations) targetActivity.getApplication();
                        final AndroidSecureMessageMediaExporter exporter =
                                new AndroidSecureMessageMediaExporter(
                                        targetActivity,
                                        application.getSecureContentStoreProvider().get());
                        final AndroidSecureContentExport export =
                                exporter.prepare(
                                        message.getConversation().getAccount().getUuid(),
                                        message.getUuid(),
                                        ContentExportOperation.SHARE);
                        targetActivity.runOnUiThread(
                                () -> {
                                    if (targetActivity.isFinishing()
                                            || targetActivity.isDestroyed()) {
                                        return;
                                    }
                                    if (export == null) {
                                        forwardLegacyMediaToSavedMessages(
                                                targetActivity, message, savedMessages);
                                    } else {
                                        attachMediaToSavedMessages(
                                                targetActivity,
                                                message,
                                                savedMessages,
                                                export.getUri());
                                    }
                                });
                    } catch (final Exception e) {
                        Log.w(
                                Config.LOGTAG,
                                "unable to prepare secure media for Saved Messages",
                                e);
                        targetActivity.runOnUiThread(
                                () -> {
                                    if (!targetActivity.isFinishing()
                                            && !targetActivity.isDestroyed()) {
                                        Toast.makeText(
                                                        targetActivity,
                                                        R.string.file_transmission_failed,
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                    }
                                });
                    }
                });
    }

    private void forwardLegacyMediaToSavedMessages(
            final ConversationsActivity targetActivity,
            final Message message,
            final Conversation savedMessages) {
        final DownloadableFile file =
                targetActivity.xmppConnectionService.getFileBackend().getFile(message);
        if (!file.exists()) {
            Toast.makeText(targetActivity, R.string.error_file_not_found, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        try {
            attachMediaToSavedMessages(
                    targetActivity,
                    message,
                    savedMessages,
                    FileBackend.getUriForFile(targetActivity, file));
        } catch (final SecurityException e) {
            Toast.makeText(targetActivity, R.string.error_file_not_found, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void attachMediaToSavedMessages(
            final ConversationsActivity targetActivity,
            final Message message,
            final Conversation savedMessages,
            final Uri uri) {
        try {
            targetActivity.delegateUriPermissionsToService(uri);
            targetActivity.xmppConnectionService.attachFileToConversation(
                    savedMessages,
                    uri,
                    message.getMimeType(),
                    new UiCallback<Message>() {
                        @Override
                        public void success(final Message object) {
                            targetActivity.runOnUiThread(
                                    () ->
                                            Toast.makeText(
                                                            targetActivity,
                                                            R.string.saved_to_saved_messages,
                                                            Toast.LENGTH_SHORT)
                                                    .show());
                        }

                        @Override
                        public void error(final int errorCode, final Message object) {
                            targetActivity.runOnUiThread(
                                    () ->
                                            Toast.makeText(
                                                            targetActivity,
                                                            errorCode,
                                                            Toast.LENGTH_SHORT)
                                                    .show());
                        }

                        @Override
                        public void userInputRequired(
                                final PendingIntent pi, final Message object) {}
                    });
        } catch (final SecurityException e) {
            Toast.makeText(targetActivity, R.string.error_file_not_found, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.share_with:
                ShareUtil.share(activity, selectedMessage);
                return true;
            case R.id.save_to_saved_messages:
                forwardToSavedMessages(selectedMessage);
                return true;
            case R.id.correct_message:
                correctMessage(selectedMessage);
                return true;
            case R.id.muc_moderate_message:
                final Message moderationMessageTarget = selectedMessage;
                final ConversationsActivity moderationHostActivity = this.activity;
                final XmppConnectionService moderationService =
                        moderationHostActivity == null
                                ? null
                                : moderationHostActivity.xmppConnectionService;
                if (!(moderationMessageTarget.getConversation() instanceof Conversation room)
                        || moderationService == null
                        || !moderationService.canModerateMessage(room, moderationMessageTarget)) {
                    return true;
                }
                new MaterialAlertDialogBuilder(moderationHostActivity)
                        .setTitle(R.string.muc_delete_message_title)
                        .setMessage(R.string.muc_delete_message_explanation)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(
                                R.string.delete,
                                (dialog, which) ->
                                        moderationService.moderateMessage(
                                                room,
                                                moderationMessageTarget,
                                                accepted -> {
                                                    if (!accepted) {
                                                        final ConversationsActivity
                                                                currentActivity = this.activity;
                                                        if (currentActivity != null) {
                                                            currentActivity.runOnUiThread(
                                                                    () ->
                                                                            Toast.makeText(
                                                                                            currentActivity,
                                                                                            R.string
                                                                                                    .muc_moderation_failed,
                                                                                            Toast
                                                                                                    .LENGTH_SHORT)
                                                                                    .show());
                                                        }
                                                    }
                                                }))
                        .show();
                return true;
            case R.id.delete_locally:
                deleteLocally(selectedMessage);
                return true;
            case R.id.retract_message:
                final Message retractTarget = selectedMessage;
                final ConversationsActivity retractHostActivity = this.activity;
                if (retractTarget == null || retractHostActivity == null) {
                    return true;
                }
                new MaterialAlertDialogBuilder(retractHostActivity)
                        .setTitle(R.string.retract_message)
                        .setMessage(R.string.retract_message_alert_title)
                        .setPositiveButton(
                                R.string.yes,
                                (dialog, whichButton) -> {
                                    final ConversationsActivity currentActivity = this.activity;
                                    if (currentActivity == null
                                            || currentActivity.xmppConnectionService == null
                                            || binding == null
                                            || conversation == null) {
                                        return;
                                    }
                                    final Message message = retractTarget;

                                    Element reactions = message.getReactions();
                                    if (reactions != null) {
                                        final Message previousReaction =
                                                conversation.findMessageReactingTo(
                                                        reactions.getAttribute("id"), null);
                                        if (previousReaction != null) {
                                            reactions = previousReaction.getReactions();
                                        }
                                        for (Element el : reactions.getChildren()) {
                                            if (message.getBody().endsWith(el.getContent())) {
                                                reactions.removeChild(el);
                                            }
                                        }
                                        message.setReactions(reactions);
                                        if (previousReaction != null) {
                                            previousReaction.setReactions(reactions);
                                            currentActivity.xmppConnectionService.updateMessage(
                                                    previousReaction);
                                        }
                                    }
                                    message.setBody(" ");
                                    message.putEdited(message.getUuid(), message.getServerMsgId());
                                    message.setServerMsgId(null);
                                    message.setUuid(UUID.randomUUID().toString());
                                    sendMessage(message);
                                })
                        .setNegativeButton(R.string.no, null)
                        .show();
                return true;
            case R.id.copy_message:
                ShareUtil.copyToClipboard(activity, selectedMessage);
                return true;
            case R.id.copy_link:
                ShareUtil.copyLinkToClipboard(activity, selectedMessage);
                return true;
            case R.id.quote_message:
                quoteMessage(selectedMessage);
                return true;
            case R.id.send_again:
                resendMessage(selectedMessage, false);
                return true;
            case R.id.send_again_as_p2p:
                resendMessage(selectedMessage, true);
                return true;
            case R.id.copy_url:
                ShareUtil.copyUrlToClipboard(activity, selectedMessage);
                return true;
            case R.id.download_file:
                startDownloadable(selectedMessage);
                return true;
            case R.id.cancel_transmission:
                cancelTransmission(selectedMessage);
                return true;
            case R.id.delete_file:
                deleteFile(selectedMessage);
                return true;
            case R.id.save_to_downloads:
                saveToDownloads(selectedMessage);
                return true;
            case R.id.show_error_message:
                showErrorMessage(selectedMessage);
                return true;
            case R.id.select_message:
                toggleMessageSelection(selectedMessage);
                return true;
            case R.id.open_with:
                openWith(selectedMessage);
                return true;
            case R.id.action_report_and_block:
                reportMessage(selectedMessage);
                return true;
            case R.id.action_add_reaction:
                addReaction(selectedMessage);
                return true;
            case R.id.give_moderator_role:
            case R.id.remove_moderator_role:
            case R.id.grant_voice:
            case R.id.revoke_voice:
            case R.id.give_admin_privileges:
            case R.id.remove_admin_privileges:
            case R.id.remove_from_room:
            case R.id.ban_from_conference:
                final User moderationTarget = resolveMucUserForMessage(selectedMessage);
                if (moderationTarget != null) {
                    MucDetailsContextMenuHelper.onContextItemSelected(
                            item, moderationTarget, activity);
                }
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        } else if (conversation == null) {
            return super.onOptionsItemSelected(item);
        }
        switch (item.getItemId()) {
            case R.id.encryption_choice_axolotl:
            case R.id.encryption_choice_none:
                handleEncryptionSelection(item);
                break;
            case R.id.attach_choose_picture:
            case R.id.attach_take_picture:
            case R.id.attach_record_video:
            case R.id.attach_choose_file:
            case R.id.attach_record_voice:
            case R.id.attach_location:
                handleAttachmentSelection(item);
                break;
            case R.id.action_search:
                startSearch();
                break;
            case R.id.action_archive:
                activity.xmppConnectionService.archiveConversation(conversation);
                break;
            case R.id.action_start_secret_chat:
                startOtrChat();
                break;
            case R.id.action_destroy_secret_chat:
                destroySecrectChat();
                break;
            case R.id.action_open_calendar:
                startActivityForResult(
                        ConversationCalendarActivity.Companion.createIntent(
                                activity, conversation.getUuid(), null),
                        REQUEST_PICK_DATE);
                break;
            case R.id.action_contact_details:
                activity.switchToContactDetails(conversation.getContact());
                break;
            case R.id.action_muc_details:
                ConferenceDetailsActivity.open(activity, conversation);
                break;
            case R.id.action_muc_participants:
                Intent intent = new Intent(activity, MucUsersActivity.class);
                intent.putExtra("uuid", conversation.getUuid());
                activity.startActivity(intent);
                break;
            case R.id.action_invite:
                startActivityForResult(
                        ChooseContactActivity.create(activity, conversation),
                        REQUEST_INVITE_TO_CONVERSATION);
                break;
            case R.id.action_clear_history:
                clearHistoryDialog(conversation);
                break;
            case R.id.action_delete_muc_local:
                deleteMucFromDeviceDialog(conversation);
                break;
            case R.id.action_mute:
                muteConversationDialog(conversation);
                break;
            case R.id.action_unmute:
                unMuteConversation(conversation);
                break;
            case R.id.action_throttle:
                throttleNoisyNoftificationsDialog(conversation);
                break;
            case R.id.action_set_custom_bg:
                handleChatBackgroundAction();
                break;
            case R.id.action_audio_call:
                checkPermissionAndTriggerAudioCall();
                break;
            case R.id.action_video_call:
                checkPermissionAndTriggerVideoCall();
                break;
            case R.id.action_ongoing_call:
                returnToOngoingCall();
                break;
            case R.id.action_toggle_pinned:
                togglePinned();
                break;
            case R.id.action_message_action_preview:
                MessageActionPreview.show(requireActivity());
                break;
            case R.id.action_refresh_feature_discovery:
                refreshFeatureDiscovery();
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void applyConversationMenuVisuals(final Menu menu) {
        tintConversationMenuItem(
                menu,
                conversation != null && conversation.isMuted()
                        ? R.id.action_unmute
                        : R.id.action_mute,
                com.google.android.material.R.attr.colorSecondary,
                false);
        tintConversationMenuItem(
                menu, R.id.action_security, com.google.android.material.R.attr.colorPrimary, false);
        tintConversationMenuItem(
                menu,
                R.id.action_toggle_pinned,
                com.google.android.material.R.attr.colorPrimary,
                false);
        tintConversationMenuItem(
                menu,
                R.id.action_set_custom_bg,
                com.google.android.material.R.attr.colorTertiary,
                false);
        tintConversationMenuItem(
                menu,
                R.id.action_archive,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                false);
        tintConversationMenuItem(
                menu,
                R.id.action_clear_history,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                false);
        tintConversationMenuItem(
                menu,
                R.id.action_delete_muc_local,
                com.google.android.material.R.attr.colorError,
                true);
    }

    private void tintConversationMenuItem(
            final Menu menu,
            @IdRes final int itemId,
            final int colorAttribute,
            final boolean tintTitle) {
        if (activity == null) {
            return;
        }
        final MenuItem item = menu.findItem(itemId);
        if (item == null || !item.isVisible()) {
            return;
        }
        final int color =
                MaterialColors.getColor(
                        activity, colorAttribute, "Conversation menu color attribute is missing");
        final Drawable icon = item.getIcon();
        if (icon != null) {
            final Drawable tinted = icon.mutate();
            tinted.setTint(color);
            item.setIcon(tinted);
        }
        if (tintTitle && item.getTitle() != null) {
            final SpannableString title = new SpannableString(item.getTitle());
            title.setSpan(
                    new ForegroundColorSpan(color),
                    0,
                    title.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            item.setTitle(title);
        }
    }

    private boolean hasConversationCustomBackground() {
        return activity != null
                && conversation != null
                && ChatBackgroundHelper.getBgFile(activity, conversation.getUuid()).exists();
    }

    private void handleChatBackgroundAction() {
        if (activity == null || conversation == null) {
            return;
        }
        final File background = ChatBackgroundHelper.getBgFile(activity, conversation.getUuid());
        if (!background.exists()) {
            if (activity.hasStoragePermission(ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND)) {
                ChatBackgroundHelper.openBGPicker(this);
            }
            return;
        }

        try {
            if (!background.delete()) {
                Toast.makeText(activity, R.string.delete_background_failed, Toast.LENGTH_SHORT)
                        .show();
                return;
            }
            updateChatBG();
            activity.invalidateOptionsMenu();
        } catch (final RuntimeException error) {
            Log.w(Config.LOGTAG, "unable to reset conversation background", error);
            Toast.makeText(activity, R.string.delete_background_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshFeatureDiscovery() {
        Set<Map.Entry<String, Presence>> presences =
                conversation.getContact().getPresences().getPresencesMap().entrySet();
        if (presences.isEmpty()) {
            presences = new HashSet<>();
            presences.add(new AbstractMap.SimpleEntry("", null));
        }
        for (Map.Entry<String, Presence> entry : presences) {
            Jid jid = conversation.getContact().getJid();
            if (!entry.getKey().equals("")) jid = jid.withResource(entry.getKey());
            activity.xmppConnectionService.fetchCaps(
                    conversation.getAccount(),
                    jid,
                    entry.getValue(),
                    () -> {
                        if (activity == null) return;
                        activity.runOnUiThread(
                                () -> {
                                    refresh();
                                    refreshCommands();
                                });
                    });
        }
    }

    private void startSearch() {
        final Intent intent = new Intent(getActivity(), SearchActivity.class);
        intent.putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, conversation.getUuid());
        startActivity(intent);
    }

    private void returnToOngoingCall() {
        final Optional<OngoingRtpSession> ongoingRtpSession =
                activity.xmppConnectionService
                        .getJingleConnectionManager()
                        .getOngoingRtpConnection(conversation.getContact());
        if (ongoingRtpSession.isPresent()) {
            startActivity(
                    RtpSessionActivity.createOngoingCallIntent(activity, ongoingRtpSession.get()));
        }
    }

    private void togglePinned() {
        final boolean pinned =
                conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false);
        conversation.setAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, !pinned);
        activity.xmppConnectionService.updateConversation(conversation);
        activity.invalidateOptionsMenu();
    }

    private void checkPermissionAndTriggerAudioCall() {
        if (activity.mUseTor || conversation.getAccount().isOnion()) {
            Toast.makeText(activity, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions =
                    Arrays.asList(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions = Collections.singletonList(Manifest.permission.RECORD_AUDIO);
        }
        if (hasPermissions(REQUEST_START_AUDIO_CALL, permissions)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
        }
    }

    private void checkPermissionAndTriggerVideoCall() {
        if (activity.mUseTor || conversation.getAccount().isOnion()) {
            Toast.makeText(activity, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions =
                    Arrays.asList(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA,
                            Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions =
                    Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA);
        }
        if (hasPermissions(REQUEST_START_VIDEO_CALL, permissions)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
        }
    }

    private void triggerRtpSession(final String action) {
        if (activity.xmppConnectionService.getJingleConnectionManager().isBusy()) {
            Toast.makeText(getActivity(), R.string.only_one_call_at_a_time, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        final Account account = conversation.getAccount();
        if (account.setOption(Account.OPTION_SOFT_DISABLED, false)) {
            activity.xmppConnectionService.updateAccount(account);
        }
        final Contact contact = conversation.getContact();
        if (Config.USE_JINGLE_MESSAGE_INIT && RtpCapability.jmiSupport(contact)) {
            triggerRtpSession(contact.getAccount(), contact.getJid().asBareJid(), action);
        } else {
            final RtpCapability.Capability capability;
            if (action.equals(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)) {
                capability = RtpCapability.Capability.VIDEO;
            } else {
                capability = RtpCapability.Capability.AUDIO;
            }
            PresenceSelector.selectFullJidForDirectRtpConnection(
                    activity,
                    contact,
                    capability,
                    fullJid -> {
                        triggerRtpSession(contact.getAccount(), fullJid, action);
                    });
        }
    }

    private void triggerRtpSession(final Account account, final Jid with, final String action) {
        CallIntegrationConnectionService.placeCall(
                activity.xmppConnectionService,
                account,
                with,
                RtpSessionActivity.actionToMedia(action));
    }

    private boolean isCorrectingMessage() {
        return conversation != null && conversation.getCorrectingMessage() != null;
    }

    private void showIncompatibleComposerMode() {
        final Context context = getContext();
        if (context != null) {
            Toast.makeText(context, R.string.finish_current_composer_action, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private static boolean isAttachmentActivityRequest(final int requestCode) {
        return requestCode == ATTACHMENT_CHOICE_CHOOSE_IMAGE
                || requestCode == ATTACHMENT_CHOICE_TAKE_PHOTO
                || requestCode == ATTACHMENT_CHOICE_CHOOSE_FILE
                || requestCode == ATTACHMENT_CHOICE_RECORD_VOICE
                || requestCode == ATTACHMENT_CHOICE_LOCATION
                || requestCode == ATTACHMENT_CHOICE_RECORD_VIDEO
                || requestCode == ATTACHMENT_CHOICE_EDIT_PHOTO
                || requestCode == ATTACHMENT_CHOICE_EDIT_VIDEO;
    }

    private void showComposerAttachmentMenu() {
        if (activity == null || binding == null) {
            return;
        }
        if (isCorrectingMessage()) {
            showIncompatibleComposerMode();
            return;
        }

        beginAttachmentViewportTransaction();
        final AdaptiveBottomSheet.Sheet sheet =
                AdaptiveBottomSheet.create(activity, R.string.attachment_sheet_title);
        sheet.getDialog()
                .setOnDismissListener(
                        ignored -> {
                            if (binding == null) {
                                finishAttachmentViewportTransaction(false);
                                return;
                            }
                            binding.getRoot()
                                    .post(
                                            () -> {
                                                if (!attachmentViewportLaunchPending
                                                        && pendingAttachmentConversationUuid
                                                                == null) {
                                                    finishAttachmentViewportTransactionAfterLayout();
                                                }
                                            });
                        });
        sheet.useCompactActionList();
        sheet.addCompactAction(
                R.drawable.ic_image_24dp,
                R.string.attachment_action_gallery,
                () -> handleAttachmentSelection(R.id.attach_choose_picture));
        sheet.addCompactAction(
                R.drawable.ic_camera_alt_24dp,
                R.string.attachment_action_camera,
                () -> handleAttachmentSelection(R.id.attach_take_picture));
        sheet.addCompactAction(
                R.drawable.ic_videocam_24dp,
                R.string.attachment_action_video,
                () -> handleAttachmentSelection(R.id.attach_record_video));
        sheet.addCompactAction(
                R.drawable.ic_description_24dp,
                R.string.attachment_action_file,
                () -> handleAttachmentSelection(R.id.attach_choose_file));
        sheet.addCompactAction(
                R.drawable.ic_location_pin_24dp,
                R.string.attachment_action_location,
                () -> handleAttachmentSelection(R.id.attach_location));
        sheet.show();
    }

    private void handleAttachmentSelection(final MenuItem item) {
        handleAttachmentSelection(item.getItemId());
    }

    private void handleAttachmentSelection(@IdRes final int itemId) {
        if (itemId != R.id.attach_record_voice) {
            attachmentViewportLaunchPending = true;
        }
        switch (itemId) {
            case R.id.attach_choose_picture:
                attachFile(ATTACHMENT_CHOICE_CHOOSE_IMAGE);
                break;
            case R.id.attach_take_picture:
                attachFile(ATTACHMENT_CHOICE_TAKE_PHOTO);
                break;
            case R.id.attach_record_video:
                attachFile(ATTACHMENT_CHOICE_RECORD_VIDEO);
                break;
            case R.id.attach_choose_file:
                attachFile(ATTACHMENT_CHOICE_CHOOSE_FILE);
                break;
            case R.id.attach_record_voice:
                // Legacy menu entry is intentionally disabled. Voice messages are recorded
                // exclusively by holding the microphone button in the composer.
                Toast.makeText(
                                getActivity(),
                                R.string.hold_microphone_to_record,
                                Toast.LENGTH_SHORT)
                        .show();
                updateSendButton();
                break;
            case R.id.attach_location:
                attachFile(ATTACHMENT_CHOICE_LOCATION);
                break;
        }
    }

    private void handleEncryptionSelection(MenuItem item) {
        if (conversation == null) {
            return;
        }
        final boolean updated;
        switch (item.getItemId()) {
            case R.id.encryption_choice_none:
                updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE);
                item.setChecked(true);
                break;
            case R.id.encryption_choice_axolotl:
                Log.d(
                        Config.LOGTAG,
                        AxolotlService.getLogprefix(conversation.getAccount())
                                + "Enabled axolotl for Contact "
                                + conversation.getContact().getJid());
                updated = conversation.setNextEncryption(Message.ENCRYPTION_AXOLOTL);
                item.setChecked(true);
                break;
            default:
                updated = conversation.setNextEncryption(Message.ENCRYPTION_NONE);
                break;
        }
        if (updated) {
            activity.xmppConnectionService.updateConversation(conversation);
        }
        updateChatMsgHint();
        getActivity().invalidateOptionsMenu();
        activity.refreshUi();
    }

    public void attachFile(final int attachmentChoice) {
        attachFile(attachmentChoice, true);
    }

    public void attachFile(final int attachmentChoice, final boolean updateRecentlyUsed) {
        if (isCorrectingMessage()) {
            showIncompatibleComposerMode();
            return;
        }
        if (attachmentChoice == ATTACHMENT_CHOICE_RECORD_VOICE) {
            // Do not fall back to the old RecordingActivity. Recording is push-to-talk only.
            final Context context = getContext();
            if (context != null) {
                Toast.makeText(context, R.string.hold_microphone_to_record, Toast.LENGTH_SHORT)
                        .show();
            }
            updateSendButton();
            return;
        } else if (attachmentChoice == ATTACHMENT_CHOICE_TAKE_PHOTO
                || attachmentChoice == ATTACHMENT_CHOICE_RECORD_VIDEO) {
            if (!hasPermissions(
                    attachmentChoice,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA)) {
                return;
            }
        } else if (attachmentChoice != ATTACHMENT_CHOICE_LOCATION) {
            if (!hasPermissions(attachmentChoice, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                return;
            }
        }
        if (updateRecentlyUsed) {
            storeRecentlyUsedQuickAction(attachmentChoice);
        }
        invokeAttachFileIntent(attachmentChoice);
    }

    private void storeRecentlyUsedQuickAction(final int attachmentChoice) {
        try {
            activity.getPreferences()
                    .edit()
                    .putString(
                            RECENTLY_USED_QUICK_ACTION,
                            SendButtonAction.of(attachmentChoice).toString())
                    .apply();
        } catch (IllegalArgumentException e) {
            // just do not save
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        final PermissionUtils.PermissionResult permissionResult =
                PermissionUtils.removeBluetoothConnect(permissions, grantResults);
        if (grantResults.length > 0) {
            if (allGranted(permissionResult.grantResults)) {
                switch (requestCode) {
                    case REQUEST_START_DOWNLOAD:
                        if (this.mPendingDownloadableMessage != null) {
                            startDownloadable(this.mPendingDownloadableMessage);
                        }
                        break;
                    case REQUEST_ADD_EDITOR_CONTENT:
                        if (this.mPendingEditorContent != null) {
                            attachEditorContentToConversation(this.mPendingEditorContent);
                        }
                        break;
                    case REQUEST_COMMIT_ATTACHMENTS:
                        commitAttachmentsAfterPendingGate();
                        break;
                    case REQUEST_START_AUDIO_CALL:
                        triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
                        break;
                    case REQUEST_START_VIDEO_CALL:
                        triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
                        break;
                    case ATTACHMENT_CHOICE_RECORD_VOICE:
                        Toast.makeText(
                                        getActivity(),
                                        R.string.hold_microphone_to_record,
                                        Toast.LENGTH_SHORT)
                                .show();
                        updateSendButton();
                        break;
                    default:
                        attachFile(requestCode);
                        break;
                }
            } else {
                @StringRes int res;
                String firstDenied =
                        getFirstDenied(permissionResult.grantResults, permissionResult.permissions);
                if (Manifest.permission.RECORD_AUDIO.equals(firstDenied)) {
                    res = R.string.no_microphone_permission;
                } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                    res = R.string.no_camera_permission;
                } else {
                    res = R.string.no_storage_permission;
                }
                Toast.makeText(
                                getActivity(),
                                getString(res, getString(R.string.app_name)),
                                Toast.LENGTH_SHORT)
                        .show();
                finishAttachmentViewportTransactionAfterLayout();
            }

            ChatBackgroundHelper.onRequestPermissionsResult(
                    this, requestCode, permissions, grantResults);
        }
        if (writeGranted(grantResults, permissions)) {
            if (activity != null && activity.xmppConnectionService != null) {
                activity.xmppConnectionService.getBitmapCache().evictAll();
                activity.xmppConnectionService.restartFileObserver();
            }
            refresh();
        }
        if (cameraGranted(grantResults, permissions) || audioGranted(grantResults, permissions)) {
            XmppConnectionService.toggleForegroundService(activity);
        }
    }

    private void updateChatBG() {
        if (activity != null && conversation != null && binding != null) {
            final Uri uri = ChatBackgroundHelper.getBgUri(activity, conversation.getUuid());
            if (uri != null) {
                chatBackgroundUsesPreset = false;
                chatBackgroundPresetRenderKey = null;
                final int scrim =
                        MaterialColors.getColor(
                                binding.backgroundImage, R.attr.neoColorChatWallpaperScrim);
                binding.backgroundImage.setForeground(new ColorDrawable(scrim));
                binding.backgroundImage.setImageURI(uri);
            } else {
                final ChatWallpaperPresets.Resolved preset = ChatWallpaperPresets.resolve(activity);
                chatBackgroundUsesPreset = true;
                chatBackgroundPresetTopColor = preset.topColor;
                chatBackgroundPresetBottomColor = preset.bottomColor;
                // refresh(), onStart() and onResume() can all reach this method. Rebinding the
                // same preset needlessly creates a new drawable and used to trigger a full-screen
                // CPU raster on the next draw. Keep the already-bound drawable until the preset,
                // theme or accent actually changes.
                if (!preset.renderKey.equals(chatBackgroundPresetRenderKey)) {
                    binding.backgroundImage.setForeground(null);
                    binding.backgroundImage.setImageDrawable(preset.createChatDrawable(activity));
                    chatBackgroundPresetRenderKey = preset.renderKey;
                }
            }
            binding.backgroundImage.setVisibility(View.VISIBLE);
            binding.backgroundImage.post(this::applyAdaptiveChatChrome);
        }
    }

    private void installChatWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
                binding.getRoot(),
                (view, insets) -> {
                    applyChatWindowInsets(insets);
                    return insets;
                });

        // The fragment root has not been laid out yet, so its first inset callback can arrive
        // after the first frame. Seed from the already-attached activity decor view to prevent the
        // composer from rendering once at the XML-only 8dp bottom padding and then jumping upward.
        final Activity activity = getActivity();
        if (activity != null) {
            final WindowInsetsCompat currentInsets =
                    ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
            if (currentInsets != null) {
                applyChatWindowInsets(currentInsets);
            }
        }
        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    private void applyChatWindowInsets(final WindowInsetsCompat insets) {
        if (binding == null || insets == null) {
            return;
        }
        final androidx.core.graphics.Insets statusBars =
                insets.getInsets(WindowInsetsCompat.Type.statusBars());
        final androidx.core.graphics.Insets systemBars =
                insets.getInsets(WindowInsetsCompat.Type.systemBars());
        final androidx.core.graphics.Insets cutout =
                insets.getInsets(WindowInsetsCompat.Type.displayCutout());
        final androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

        chatSystemBarTopInset = Math.max(statusBars.top, cutout.top);
        chatSystemBarLeftInset = Math.max(systemBars.left, cutout.left);
        chatSystemBarRightInset = Math.max(systemBars.right, cutout.right);
        chatSystemBarBottomInset = systemBars.bottom;
        trackImeResize(ime.bottom);
        final int bottomInset = Math.max(systemBars.bottom, ime.bottom);
        binding.textsend.setPadding(
                textsendBasePaddingLeft + chatSystemBarLeftInset,
                textsendBasePaddingTop,
                textsendBasePaddingRight + chatSystemBarRightInset,
                textsendBasePaddingBottom + bottomInset);

        final ViewGroup.LayoutParams composerProtectionParams =
                binding.composerBottomProtection.getLayoutParams();
        final int composerProtectionHeight = textsendBasePaddingBottom + Math.max(0, ime.bottom);
        if (composerProtectionParams.height != composerProtectionHeight) {
            composerProtectionParams.height = composerProtectionHeight;
            binding.composerBottomProtection.setLayoutParams(composerProtectionParams);
        }
        binding.composerBottomProtection.setVisibility(ime.bottom > 0 ? View.VISIBLE : View.GONE);

        updateCommandTabsChrome();
        updateNavigationBarProtectionHeight();
    }

    private void trackImeResize(final int imeBottomInset) {
        if (binding == null) {
            return;
        }
        if (lastImeBottomInset < 0) {
            lastImeBottomInset = imeBottomInset;
            return;
        }
        if (lastImeBottomInset == imeBottomInset) {
            return;
        }
        if (!imeResizeInProgress) {
            if (hasAttachmentViewportTransaction()) {
                pinBottomDuringImeResize = attachmentViewportKeepBottomPinned;
                imeResizeScrollAnchor = pinBottomDuringImeResize ? null : attachmentViewportAnchor;
            } else {
                pinBottomDuringImeResize =
                        programmaticBottomPin || passiveBottomPinPending || scrolledToBottom();
                imeResizeScrollAnchor =
                        pinBottomDuringImeResize ? null : captureVisualScrollAnchor();
            }
        }
        imeResizeInProgress = true;
        lastImeBottomInset = imeBottomInset;
        final View root = binding.getRoot();
        root.removeCallbacks(finishImeResizeRunnable);
        root.postDelayed(finishImeResizeRunnable, IME_RESIZE_SETTLE_MS);
        if (hasAttachmentViewportTransaction()
                && attachmentViewportFinishPending
                && !attachmentViewportLaunchPending) {
            scheduleAttachmentViewportTransactionFinish();
        }
    }

    private void clearImeResizeScrollState() {
        pinBottomDuringImeResize = false;
        imeResizeScrollAnchor = null;
        passiveBottomPinPending = false;
    }

    private boolean isAutomaticBottomPinActive() {
        return programmaticBottomPin || pinBottomDuringImeResize || passiveBottomPinPending;
    }

    private int beginProgrammaticBottomPin() {
        followLatestMessages = true;
        userScrollControlsFollowLatest = false;
        programmaticBottomPin = true;
        final int generation = ++bottomPinGeneration;
        lastMessageUuid = null;
        hideUnreadMessagesCount();
        return generation;
    }

    private boolean isProgrammaticBottomPinActive(final int generation) {
        return programmaticBottomPin && generation == bottomPinGeneration;
    }

    private void finishProgrammaticBottomPin(final int generation) {
        if (generation != bottomPinGeneration) {
            return;
        }
        programmaticBottomPin = false;
        if (binding != null) {
            toggleScrollDownButton();
        }
    }

    private void cancelAutomaticBottomPin() {
        if (bottomScrollAnimator != null) {
            bottomScrollAnimator.cancel();
            bottomScrollAnimator = null;
        }
        programmaticBottomPin = false;
        pinBottomDuringImeResize = false;
        passiveBottomPinPending = false;
        imeResizeScrollAnchor = null;
        bottomPinGeneration++;
        chromePaddingSettleGeneration++;
    }

    private void applyAdaptiveChatChrome() {
        if (binding == null || activity == null) {
            return;
        }

        final int fallback =
                MaterialColors.getColor(
                        binding.getRoot(), com.google.android.material.R.attr.colorSurface);
        int topVisibleColor = fallback;
        int bottomVisibleColor = fallback;

        if (chatBackgroundUsesPreset) {
            topVisibleColor = chatBackgroundPresetTopColor;
            bottomVisibleColor = chatBackgroundPresetBottomColor;
        } else if (binding.backgroundImage.getVisibility() == View.VISIBLE
                && binding.backgroundImage.getDrawable() instanceof BitmapDrawable) {
            final BitmapDrawable bitmapDrawable =
                    (BitmapDrawable) binding.backgroundImage.getDrawable();
            if (bitmapDrawable.getBitmap() != null
                    && binding.backgroundImage.getWidth() > 0
                    && binding.backgroundImage.getHeight() > 0) {
                final int scrim =
                        MaterialColors.getColor(
                                binding.backgroundImage, R.attr.neoColorChatWallpaperScrim);
                topVisibleColor =
                        ChatChromeTint.applyScrim(
                                ChatChromeTint.sampleVisibleBand(
                                        bitmapDrawable.getBitmap(),
                                        binding.backgroundImage.getWidth(),
                                        binding.backgroundImage.getHeight(),
                                        true),
                                scrim);
                bottomVisibleColor =
                        ChatChromeTint.applyScrim(
                                ChatChromeTint.sampleVisibleBand(
                                        bitmapDrawable.getBitmap(),
                                        binding.backgroundImage.getWidth(),
                                        binding.backgroundImage.getHeight(),
                                        false),
                                scrim);
            }
        }

        final int onSurface =
                MaterialColors.getColor(
                        binding.getRoot(), com.google.android.material.R.attr.colorOnSurface);
        binding.composerIsland.setCardBackgroundColor(
                ChatChromeTint.resolveIslandColor(
                        defaultComposerIslandColor, bottomVisibleColor, onSurface));
        binding.composerBottomProtection.setBackgroundColor(bottomVisibleColor);
        binding.navigationBarProtection.setBackground(
                ChatChromeTint.createSystemBarProtection(
                        bottomVisibleColor,
                        ChatChromeTint.shouldUseDarkSystemIcons(bottomVisibleColor),
                        false));
        activity.applyConversationChrome(topVisibleColor, bottomVisibleColor);
    }

    public void startDownloadable(Message message) {
        if (!hasPermissions(REQUEST_START_DOWNLOAD, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            this.mPendingDownloadableMessage = message;
            return;
        }
        Transferable transferable = message.getTransferable();
        if (transferable != null) {
            if (transferable instanceof TransferablePlaceholder && message.hasFileOnRemoteHost()) {
                createNewConnection(message);
                return;
            }
            if (!transferable.start()) {
                Log.d(Config.LOGTAG, "type: " + transferable.getClass().getName());
                Toast.makeText(getActivity(), R.string.not_connected_try_again, Toast.LENGTH_SHORT)
                        .show();
            }
        } else if (message.treatAsDownloadable()
                || message.hasFileOnRemoteHost()
                || MessageUtils.unInitiatedButKnownSize(message)) {
            createNewConnection(message);
        } else {
            Log.d(
                    Config.LOGTAG,
                    message.getConversation().getAccount() + ": unable to start downloadable");
        }
    }

    private void createNewConnection(final Message message) {
        if (!activity.xmppConnectionService.hasInternetConnection()) {
            Toast.makeText(getActivity(), R.string.not_connected_try_again, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        activity.xmppConnectionService
                .getHttpConnectionManager()
                .createNewDownloadConnection(message, true);
    }

    @SuppressLint("InflateParams")
    protected void clearHistoryDialog(final Conversation conversation) {
        final ConversationsActivity hostActivity = this.activity;
        final XmppConnectionService service =
                hostActivity == null ? null : hostActivity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(hostActivity);
        builder.setTitle(getString(R.string.clear_conversation_history));
        final View dialogView =
                hostActivity.getLayoutInflater().inflate(R.layout.dialog_clear_history, null);
        final CheckBox endConversationCheckBox =
                dialogView.findViewById(R.id.end_conversation_checkbox);
        final boolean rootMuc =
                conversation.getMode() == Conversation.MODE_MULTI
                        && conversation.getNextCounterpart() == null;
        if (rootMuc) {
            endConversationCheckBox.setChecked(false);
            endConversationCheckBox.setVisibility(View.GONE);
        }
        builder.setView(dialogView);
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(
                getString(R.string.confirm),
                (dialog, which) -> {
                    // Confirmation is explicit user intent. Complete the service operation even if
                    // this fragment detached while the dialog was open, but never dereference the
                    // stale Activity for UI follow-up.
                    service.clearConversationHistory(conversation);
                    if (!rootMuc && endConversationCheckBox.isChecked()) {
                        service.archiveConversation(conversation);
                        final ConversationsActivity currentActivity = this.activity;
                        if (currentActivity != null) {
                            currentActivity.onConversationArchived(conversation);
                        }
                    } else {
                        final ConversationsActivity currentActivity = this.activity;
                        if (currentActivity != null) {
                            currentActivity.onConversationsListItemUpdated();
                            if (binding != null && this.conversation == conversation) {
                                refresh();
                            }
                        }
                    }
                });
        builder.create().show();
    }

    private void deleteMucFromDeviceDialog(final Conversation conversation) {
        if (conversation == null
                || conversation.getMode() != Conversation.MODE_MULTI
                || conversation.getNextCounterpart() != null) {
            return;
        }
        final ConversationsActivity hostActivity = this.activity;
        final XmppConnectionService service =
                hostActivity == null ? null : hostActivity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final boolean channel = !conversation.getMucOptions().isPrivateAndNonAnonymous();
        new MaterialAlertDialogBuilder(hostActivity)
                .setTitle(
                        channel
                                ? R.string.conversation_menu_delete_channel_from_device
                                : R.string.conversation_menu_delete_group_from_device)
                .setMessage(
                        channel
                                ? R.string.delete_channel_from_device_message
                                : R.string.delete_group_from_device_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.delete,
                        (dialog, which) -> {
                            service.leaveMucAndForget(conversation);
                            final ConversationsActivity currentActivity = this.activity;
                            if (currentActivity != null) {
                                currentActivity.onConversationArchived(conversation);
                            }
                        })
                .show();
    }

    protected void muteConversationDialog(final Conversation conversation) {
        final ConversationsActivity hostActivity = this.activity;
        final XmppConnectionService service =
                hostActivity == null ? null : hostActivity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(hostActivity);
        builder.setTitle(R.string.disable_notifications);
        final int[] durations =
                hostActivity.getResources().getIntArray(R.array.mute_options_durations);
        final CharSequence[] labels = new CharSequence[durations.length];
        for (int i = 0; i < durations.length; ++i) {
            if (durations[i] == -1) {
                labels[i] = hostActivity.getString(R.string.until_further_notice);
            } else {
                labels[i] = TimeFrameUtils.resolve(hostActivity, 1000L * durations[i]);
            }
        }
        builder.setItems(
                labels,
                (dialog, which) -> {
                    final long till;
                    if (durations[which] == -1) {
                        till = Long.MAX_VALUE;
                    } else {
                        till = System.currentTimeMillis() + (durations[which] * 1000L);
                    }
                    conversation.setMutedTill(till);
                    service.updateConversation(conversation);
                    final ConversationsActivity currentActivity = this.activity;
                    if (currentActivity != null) {
                        currentActivity.onConversationsListItemUpdated();
                        if (binding != null && this.conversation == conversation) {
                            refresh();
                        }
                        currentActivity.invalidateOptionsMenu();
                    }
                });
        builder.create().show();
    }

    protected void throttleNoisyNoftificationsDialog(final Conversation conversation) {
        final ConversationsActivity hostActivity = this.activity;
        final XmppConnectionService service =
                hostActivity == null ? null : hostActivity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(hostActivity);
        builder.setTitle(R.string.pref_noisy_notifications_throttling);
        final int[] durations =
                hostActivity
                        .getResources()
                        .getIntArray(
                                R.array.notification_throttling_periods_values_per_conversation);

        final CharSequence[] labels = new CharSequence[durations.length];
        int checkedIndex = -1;
        long period = conversation.getNotificationThrottlingPeriod();
        for (int i = 0; i < durations.length; ++i) {
            if (period == durations[i]) {
                checkedIndex = i;
            }

            if (durations[i] == -1) {
                labels[i] = hostActivity.getString(R.string.never);
            } else if (durations[i] == -2) {
                labels[i] = hostActivity.getString(R.string.inherit);
            } else {
                labels[i] = TimeFrameUtils.resolve(hostActivity, durations[i]);
            }
        }
        builder.setSingleChoiceItems(
                labels,
                checkedIndex,
                (dialog, which) -> {
                    conversation.setNotificationThrottlingPeriod(durations[which]);
                    service.updateConversation(conversation);
                    final ConversationsActivity currentActivity = this.activity;
                    if (currentActivity != null) {
                        currentActivity.onConversationsListItemUpdated();
                        if (binding != null && this.conversation == conversation) {
                            refresh();
                        }
                        currentActivity.invalidateOptionsMenu();
                    }
                });
        builder.create().show();
    }

    private boolean hasPermissions(int requestCode, List<String> permissions) {
        final List<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            || Config.ONLY_INTERNAL_STORAGE)
                    && permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                continue;
            }
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (missingPermissions.size() == 0) {
            return true;
        } else {
            requestPermissions(missingPermissions.toArray(new String[0]), requestCode);
            return false;
        }
    }

    private boolean hasPermissions(int requestCode, String... permissions) {
        return hasPermissions(requestCode, ImmutableList.copyOf(permissions));
    }

    public void unMuteConversation(final Conversation conversation) {
        conversation.setMutedTill(0);
        this.activity.xmppConnectionService.updateConversation(conversation);
        this.activity.onConversationsListItemUpdated();
        refresh();
        activity.invalidateOptionsMenu();
    }

    protected void invokeAttachFileIntent(final int attachmentChoice) {
        Intent intent = new Intent();
        boolean chooser = false;
        switch (attachmentChoice) {
            case ATTACHMENT_CHOICE_CHOOSE_IMAGE:
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.setType("image/*");
                chooser = true;
                break;
            case ATTACHMENT_CHOICE_RECORD_VIDEO:
                intent.setAction(MediaStore.ACTION_VIDEO_CAPTURE);
                break;
            case ATTACHMENT_CHOICE_TAKE_PHOTO:
                final Uri uri = activity.xmppConnectionService.getFileBackend().getTakePhotoUri();
                pendingTakePhotoUri.push(uri);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);
                break;
            case ATTACHMENT_CHOICE_CHOOSE_FILE:
                chooser = true;
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setAction(Intent.ACTION_GET_CONTENT);
                break;
            case ATTACHMENT_CHOICE_RECORD_VOICE:
                // The legacy full-screen/dialog recorder is no longer part of NeoCont UX.
                // Voice recording starts only from a press-and-hold gesture on the composer mic.
                Toast.makeText(
                                getActivity(),
                                R.string.hold_microphone_to_record,
                                Toast.LENGTH_SHORT)
                        .show();
                updateSendButton();
                return;
            case ATTACHMENT_CHOICE_LOCATION:
                intent = GeoHelper.getFetchIntent(activity);
                break;
        }
        final Context context = getActivity();
        if (context == null || conversation == null) {
            return;
        }
        pendingAttachmentConversationUuid = conversation.getUuid();
        try {
            if (chooser) {
                startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.perform_action_with)),
                        attachmentChoice);
            } else {
                startActivityForResult(intent, attachmentChoice);
            }
        } catch (final ActivityNotFoundException e) {
            pendingAttachmentConversationUuid = null;
            finishAttachmentViewportTransactionAfterLayout();
            Toast.makeText(context, R.string.no_application_found, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onPause() {
        if (voiceRecordingActive) {
            cancelInlineVoiceRecording();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyMessageTypographyPreferenceChanges();
        binding.messagesView.post(this::fireReadEvent);
        updateChatBG();
    }

    public void retireSecurePresentationState() {
        final ConversationsActivity targetActivity = activity;
        if (targetActivity == null || binding == null) {
            return;
        }
        targetActivity.runOnUiThread(
                () -> {
                    if (binding == null) {
                        return;
                    }

                    if (conversation != null) {
                        conversation.setReplyTo(null);
                        conversation.setCorrectingMessage(null);
                    }
                    pendingMediaDraft = null;
                    previousClickedReply = null;

                    // Do not let the editor watcher persist the scrub as a user-authored empty
                    // draft. Durable protected draft state has already been retired by the service.
                    binding.textinput.setKeyboardListener(null);
                    binding.textinput.setText("");
                    binding.textinput.setKeyboardListener(this);

                    binding.contextPreviewText.setText("");
                    binding.contextPreviewAuthor.setText("");
                    binding.contextPreviewImage.setImageDrawable(null);
                    binding.contextPreviewImage.setVisibility(View.GONE);
                    binding.contextPreviewDoc.setVisibility(View.GONE);
                    binding.contextPreviewAudio.setVisibility(View.GONE);
                    binding.contextPreview.setVisibility(View.GONE);

                    for (int i = 0; i < binding.mediaPreview.getChildCount(); i++) {
                        scrubSensitivePresentationView(binding.mediaPreview.getChildAt(i));
                    }
                    if (mediaPreviewAdapter != null) {
                        mediaPreviewAdapter.clearPreviews();
                        mediaPreviewAdapter.notifyDataSetChanged();
                    }
                    selectedMessages.clear();
                    if (selectionActionMode != null) {
                        selectionActionMode.finish();
                    }
                    for (int i = 0; i < binding.messagesView.getChildCount(); i++) {
                        scrubSensitivePresentationView(binding.messagesView.getChildAt(i));
                    }
                    if (messageListAdapter != null) {
                        messageListAdapter.notifyDataSetChanged();
                    }
                    binding.messagesView.invalidateViews();
                    updateSendButton();
                    updateEditablity();
                });
    }

    private static void scrubSensitivePresentationView(final View view) {
        if (view == null) {
            return;
        }
        if (view instanceof TextView) {
            ((TextView) view).setText("");
        }
        if (view instanceof ImageView) {
            ((ImageView) view).setImageDrawable(null);
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scrubSensitivePresentationView(group.getChildAt(i));
            }
        }
    }

    private void applyMessageTypographyPreferenceChanges() {
        if (binding == null || messageListAdapter == null) {
            return;
        }
        if (!messageListAdapter.refreshMessageHyphenationPreference()) {
            return;
        }

        final ListView listView = binding.messagesView;
        final boolean keepBottomPinned =
                conversation != null
                        && !conversation.isInHistoryPart()
                        && scrolledToBottom(listView);
        final VisualScrollAnchor preservedAnchor =
                keepBottomPinned ? null : captureVisualScrollAnchor();

        messageListAdapter.notifyDataSetChanged();

        if (keepBottomPinned) {
            listView.post(() -> keepLatestPinnedAfterRefresh(false));
        } else if (preservedAnchor != null) {
            restoreVisualScrollAnchorBeforeLayout(preservedAnchor);
        }
    }

    private void fireReadEvent() {
        if (activity != null && this.conversation != null) {
            String uuid = getLastVisibleMessageUuid();
            if (uuid != null) {
                activity.onConversationRead(this.conversation, uuid);
            }
        }
    }

    private String getLastVisibleMessageUuid() {
        if (binding == null) {
            return null;
        }
        synchronized (this.messageList) {
            int pos = binding.messagesView.getLastVisiblePosition();
            if (pos >= 0) {
                Message message = null;
                for (int i = pos; i >= 0; --i) {
                    try {
                        message = (Message) binding.messagesView.getItemAtPosition(i);
                    } catch (IndexOutOfBoundsException e) {
                        // should not happen if we synchronize properly. however if that fails we
                        // just gonna try item -1
                        continue;
                    }
                    if (message.getType() != Message.TYPE_STATUS) {
                        break;
                    }
                }
                if (message != null) {
                    return message.getUuid();
                }
            }
        }
        return null;
    }

    private void openWith(final Message message) {
        if (message.isGeoUri()) {
            GeoHelper.view(getActivity(), message);
            return;
        }
        SecureMessageMediaUiBridge.openOrFallback(
                activity,
                message,
                () -> {
                    final DownloadableFile file =
                            activity.xmppConnectionService.getFileBackend().getFile(message);
                    ViewUtil.view(activity, file);
                });
    }

    private void addReaction(final Message message) {
        activity.addReaction(
                message,
                reactions -> {
                    if (activity.xmppConnectionService.sendReactions(message, reactions)) {
                        return;
                    }
                    Toast.makeText(activity, R.string.could_not_add_reaction, Toast.LENGTH_LONG)
                            .show();
                });
    }

    private void toggleMessageSelection(Message message) {
        message = getMessageInteractionTarget(message);
        if (message.getType() == Message.TYPE_STATUS
                || message.getType() == Message.TYPE_RTP_SESSION
                || MessageAdapter.DATE_SEPARATOR_BODY.equals(message.getBody())) {
            return;
        }
        if (selectionActionMode == null) {
            activity.startActionMode(actionModeCallback);
        }

        if (selectedMessages.contains(message)) {
            selectedMessages.remove(message);
        } else {
            selectedMessages.add(message);
        }

        if (selectedMessages.size() == 0) {
            selectionActionMode.finish();
        } else {
            selectionActionMode.setTitle(
                    getString(R.string.message_selection_title, selectedMessages.size()));
            selectionActionMode.invalidate();
        }

        this.messageListAdapter.notifyDataSetChanged();
    }

    private void reportMessage(final Message message) {
        BlockContactDialog.show(activity, conversation.getContact(), message.getServerMsgId());
    }

    private void showErrorMessage(final Message message) {
        final ConversationsActivity hostActivity = this.activity;
        if (hostActivity == null) {
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(hostActivity);
        builder.setTitle(R.string.error_message);
        final String errorMessage = message.getErrorMessage();
        final String[] errorMessageParts =
                errorMessage == null ? new String[0] : errorMessage.split("\\u001f");
        final String displayError;
        if (errorMessageParts.length == 2) {
            displayError = errorMessageParts[1];
        } else {
            displayError = errorMessage;
        }
        builder.setMessage(displayError);
        builder.setNegativeButton(
                R.string.copy_to_clipboard,
                (dialog, which) -> {
                    final ConversationsActivity currentActivity = this.activity;
                    if (currentActivity == null) {
                        return;
                    }
                    currentActivity.copyTextToClipboard(displayError, R.string.error_message);
                    Toast.makeText(
                                    currentActivity,
                                    R.string.error_message_copied_to_clipboard,
                                    Toast.LENGTH_SHORT)
                            .show();
                });
        builder.setPositiveButton(R.string.confirm, null);
        builder.create().show();
    }

    private boolean ownsMessageMediaFile(final Message message) {
        final ConversationsActivity targetActivity = activity;
        if (targetActivity == null) {
            return false;
        }
        final String path = message.getRelativeFilePath();
        final boolean legacyOwned =
                path == null
                        || !path.startsWith("/")
                        || FileBackend.inConversationsDirectory(targetActivity, path);
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            return legacyOwned;
        }
        if (!(targetActivity.getApplication() instanceof Conversations)) {
            return false;
        }
        try {
            final Conversations application = (Conversations) targetActivity.getApplication();
            final SecureMessageMediaLegacyBoundary boundary =
                    new SecureMessageMediaLegacyBoundary(
                            application.getSecureContentStoreProvider().get());
            final String accountUuid = message.getConversation().getAccount().getUuid();
            return boundary.isSecure(accountUuid, message.getUuid()) || legacyOwned;
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "unable to resolve secure media ownership", e);
            return false;
        }
    }

    private void deleteFile(final Message message) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.delete_file_dialog);
        builder.setMessage(R.string.delete_file_dialog_msg);
        builder.setPositiveButton(
                R.string.confirm, (dialog, which) -> deleteFileConfirmed(message));
        builder.create().show();
    }

    private void deleteFileConfirmed(final Message message) {
        final ConversationsActivity targetActivity = activity;
        if (targetActivity == null || targetActivity.xmppConnectionService == null) {
            return;
        }
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            if (targetActivity.xmppConnectionService.getFileBackend().deleteFile(message)) {
                markMessageFileDeleted(targetActivity, message);
            }
            return;
        }
        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(
                () -> {
                    try {
                        final Conversations application =
                                (Conversations) targetActivity.getApplication();
                        final SecureContentStore store =
                                application.getSecureContentStoreProvider().get();
                        final SecureMessageMediaCoordinator coordinator =
                                new SecureMessageMediaCoordinator(
                                        store, new SecureContentTransferGateway(store));
                        final String accountUuid = message.getConversation().getAccount().getUuid();
                        final var secureBinding =
                                coordinator.resolve(accountUuid, message.getUuid());
                        if (secureBinding == null) {
                            final boolean deletedLegacyFile =
                                    targetActivity
                                            .xmppConnectionService
                                            .getFileBackend()
                                            .deleteFile(message);
                            if (deletedLegacyFile) {
                                targetActivity.runOnUiThread(
                                        () -> markMessageFileDeleted(targetActivity, message));
                            }
                            return;
                        }

                        final DownloadableFile legacyFile =
                                targetActivity
                                        .xmppConnectionService
                                        .getFileBackend()
                                        .getFile(message);
                        if (legacyFile.exists()
                                && !targetActivity
                                        .xmppConnectionService
                                        .getFileBackend()
                                        .deleteFile(message)
                                && legacyFile.exists()) {
                            throw new IOException(
                                    "Unable to delete legacy plaintext before secure media"
                                            + " retirement");
                        }

                        coordinator.retire(secureBinding);
                        targetActivity.runOnUiThread(
                                () -> markMessageFileDeleted(targetActivity, message));
                    } catch (final Exception e) {
                        Log.w(Config.LOGTAG, "unable to delete secure message media", e);
                        targetActivity.runOnUiThread(
                                () -> {
                                    if (!targetActivity.isFinishing()
                                            && !targetActivity.isDestroyed()) {
                                        Toast.makeText(
                                                        targetActivity,
                                                        R.string.error_io_exception,
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                    }
                                });
                    }
                });
    }

    private void markMessageFileDeleted(
            final ConversationsActivity targetActivity, final Message message) {
        message.setDeleted(true);
        targetActivity.xmppConnectionService.evictPreview(message.getUuid());
        targetActivity.xmppConnectionService.updateMessage(message, false);
        if (activity == targetActivity
                && binding != null
                && getView() != null
                && !targetActivity.isFinishing()
                && !targetActivity.isDestroyed()) {
            targetActivity.onConversationsListItemUpdated();
            refresh();
        }
    }

    private void saveToDownloads(final Message message) {
        SecureMessageMediaSaveBridge.saveWithStandardFeedbackOrFallback(
                activity, message, () -> saveToDownloadsLegacy(message));
    }

    private void saveToGallery(final Message message) {
        activity.xmppConnectionService.copyMediaToGallery(
                message,
                new UiCallback<>() {
                    @Override
                    public void success(Integer object) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        activity,
                                                        R.string.save_to_gallery_success,
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }

                    @Override
                    public void error(int errorCode, Integer object) {
                        runOnUiThread(
                                () -> Toast.makeText(activity, object, Toast.LENGTH_LONG).show());
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi, Integer object) {}
                });
    }

    private void saveToDownloadsLegacy(final Message message) {
        activity.xmppConnectionService.copyAttachmentToDownloadsFolder(
                message,
                new UiCallback<>() {
                    @Override
                    public void success(Integer object) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        activity,
                                                        R.string.save_to_downloads_success,
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }

                    @Override
                    public void error(int errorCode, Integer object) {
                        runOnUiThread(
                                () -> Toast.makeText(activity, object, Toast.LENGTH_LONG).show());
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi, Integer object) {}
                });
    }

    private void resendMessage(final Message message, final boolean forceP2P) {
        final boolean followOwnSend = shouldFollowOwnSend();
        if (message.isFileOrImage()) {
            if (!(message.getConversation() instanceof Conversation conversation)) {
                return;
            }
            final DownloadableFile file =
                    activity.xmppConnectionService.getFileBackend().getFile(message);
            final boolean durableSecureRetry =
                    Config.SECURE_CONTENT_MEDIA_ROLLOUT && !message.hasFileOnRemoteHost();
            if (durableSecureRetry
                    || (file.exists() && file.canRead())
                    || message.hasFileOnRemoteHost()) {
                final XmppConnection xmppConnection = conversation.getAccount().getXmppConnection();
                if (!message.hasFileOnRemoteHost()
                        && xmppConnection != null
                        && conversation.getMode() == Conversational.MODE_SINGLE
                        && (!xmppConnection
                                        .getFeatures()
                                        .httpUpload(message.getFileParams().getSize())
                                || forceP2P)) {
                    activity.selectPresence(
                            conversation,
                            () -> {
                                message.setCounterpart(conversation.getNextCounterpart());
                                activity.xmppConnectionService.resendFailedMessages(
                                        message, forceP2P);
                                if (followOwnSend) {
                                    scrollToLatest();
                                }
                            });
                    return;
                }
            } else if (!Compatibility.hasStoragePermission(getActivity())) {
                Toast.makeText(activity, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
                return;
            } else {
                Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show();
                message.setDeleted(true);
                activity.xmppConnectionService.updateMessage(message, false);
                activity.onConversationsListItemUpdated();
                refresh();
                return;
            }
        }
        activity.xmppConnectionService.resendFailedMessages(message, false);
        if (followOwnSend) {
            scrollToLatest();
        }
    }

    private void cancelTransmission(Message message) {
        Transferable transferable = message.getTransferable();
        if (transferable != null) {
            transferable.cancel();
        } else if (message.getStatus() != Message.STATUS_RECEIVED) {
            activity.xmppConnectionService.markMessage(
                    message, Message.STATUS_SEND_FAILED, Message.ERROR_MESSAGE_CANCELLED);
        }
    }

    public void privateMessageWith(final Jid counterpart) {
        Conversation c =
                activity.xmppConnectionService.findOrCreateConversation(
                        conversation.getAccount(),
                        conversation.getJid(),
                        null,
                        true,
                        true,
                        false,
                        counterpart);
        if (c != conversation) {
            activity.switchToConversation(c);
        }
    }

    private void deleteLocally(final Message message) {
        final List<Message> deleteTargets =
                MediaLocalDeleteResolver.resolveDeleteTargets(
                        conversation, message, conversation.snapshotMessages());
        for (final Message target : deleteTargets) {
            this.conversation.deleteLocally(target);
        }
        this.activity.xmppConnectionService.deleteMessagesLocally(conversation, deleteTargets);
        refresh();
    }

    private void correctMessage(final Message message) {
        if ((mediaPreviewAdapter != null && mediaPreviewAdapter.hasAttachments())
                || voiceRecordingActive) {
            showIncompatibleComposerMode();
            return;
        }
        this.conversation.setCorrectingMessage(message);
        final Editable editable = binding.textinput.getText();
        this.conversation.setDraftMessage(editable.toString());
        this.binding.textinput.setText("");
        final Element markup = message.getMessageMarkup();
        this.binding.textinput.append(
                markup == null
                        ? message.getBody()
                        : MessageMarkup.toStylingText(message.getBody(), markup));
    }

    private void highlightInConference(String nick) {
        final Editable editable = this.binding.textinput.getText();
        String oldString = editable.toString().trim();
        final int pos = this.binding.textinput.getSelectionStart();
        if (oldString.isEmpty() || pos == 0) {
            editable.insert(0, nick + ": ");
        } else {
            final char before = editable.charAt(pos - 1);
            final char after = editable.length() > pos ? editable.charAt(pos) : '\0';
            if (before == '\n') {
                editable.insert(pos, nick + ": ");
            } else {
                if (pos > 2 && editable.subSequence(pos - 2, pos).toString().equals(": ")) {
                    if (NickValidityChecker.check(
                            conversation,
                            Arrays.asList(
                                    editable.subSequence(0, pos - 2).toString().split(", ")))) {
                        editable.insert(pos - 2, ", " + nick);
                        return;
                    }
                }
                editable.insert(
                        pos,
                        (Character.isWhitespace(before) ? "" : " ")
                                + nick
                                + (Character.isWhitespace(after) ? "" : " "));
                if (Character.isWhitespace(after)) {
                    this.binding.textinput.setSelection(
                            this.binding.textinput.getSelectionStart() + 1);
                }
            }
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        final Activity activity = getActivity();
        if (activity instanceof ConversationsActivity) {
            ((ConversationsActivity) activity).clearPendingViewIntent();
        }
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (conversation != null) {
            outState.putString(STATE_CONVERSATION_UUID, conversation.getUuid());
            outState.putString(STATE_LAST_MESSAGE_UUID, lastMessageUuid);
            final Uri uri = pendingTakePhotoUri.peek();
            if (uri != null) {
                outState.putString(STATE_PHOTO_URI, uri.toString());
            }
            final ScrollState scrollState = getScrollPosition();
            if (scrollState != null) {
                outState.putParcelable(STATE_SCROLL_POSITION, scrollState);
            }
            final ArrayList<Attachment> attachments =
                    mediaPreviewAdapter == null
                            ? new ArrayList<>()
                            : mediaPreviewAdapter.getAttachments();
            if (attachments.size() > 0) {
                outState.putParcelableArrayList(STATE_MEDIA_PREVIEWS, attachments);
            }
            if (pendingAttachmentConversationUuid != null) {
                outState.putString(
                        STATE_ATTACHMENT_CONVERSATION_UUID, pendingAttachmentConversationUuid);
            }
            if (pendingMediaCommitConversationUuid != null) {
                outState.putString(
                        STATE_MEDIA_COMMIT_CONVERSATION_UUID, pendingMediaCommitConversationUuid);
            }
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }
        String uuid = savedInstanceState.getString(STATE_CONVERSATION_UUID);
        ArrayList<Attachment> attachments =
                savedInstanceState.getParcelableArrayList(STATE_MEDIA_PREVIEWS);
        pendingAttachmentConversationUuid =
                savedInstanceState.getString(STATE_ATTACHMENT_CONVERSATION_UUID);
        pendingMediaCommitConversationUuid =
                savedInstanceState.getString(STATE_MEDIA_COMMIT_CONVERSATION_UUID);
        pendingLastMessageUuid.push(savedInstanceState.getString(STATE_LAST_MESSAGE_UUID, null));
        if (uuid != null) {
            QuickLoader.set(getActivity(), uuid);
            this.pendingConversationsUuid.push(uuid);
            if (attachments != null && attachments.size() > 0) {
                this.pendingMediaPreviews.push(attachments);
            }
            String takePhotoUri = savedInstanceState.getString(STATE_PHOTO_URI);
            if (takePhotoUri != null) {
                pendingTakePhotoUri.push(Uri.parse(takePhotoUri));
            }
            pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (this.reInitRequiredOnStart && this.conversation != null) {
            final Bundle extras = pendingExtras.pop();
            this.reInitRequiredOnStart =
                    !reInit(
                            this.conversation,
                            extras != null,
                            extras != null
                                    && extras.getString(ConversationsActivity.EXTRA_MESSAGE_UUID)
                                            != null);
            if (extras != null) {
                processExtras(extras);
            }
        } else if (conversation == null
                && activity != null
                && activity.xmppConnectionService != null) {
            final String uuid = pendingConversationsUuid.pop();
            Log.d(
                    Config.LOGTAG,
                    "ConversationFragment.onStart() - activity was bound but no conversation"
                            + " loaded. uuid="
                            + uuid);
            if (uuid != null) {
                findAndReInitByUuidOrArchive(uuid);
            }
        }

        updateChatBG();

        binding.inputLayout.setBackgroundTintList(
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                binding.inputLayout, R.attr.neoColorComposerSurface)));
        restoreInlineVoiceRecordingUiIfNeeded();
    }

    @Override
    public void onStop() {
        hideStickyDateOverlayImmediately();
        super.onStop();
        final Activity activity = getActivity();
        messageListAdapter.unregisterListenerInAudioPlayer();
        if (activity == null || !activity.isChangingConfigurations()) {
            hideSoftKeyboard(activity);
            messageListAdapter.stopAudioPlayer();
        }
        if (this.conversation != null) {
            timelineRevisionAtStop = this.conversation.getTimelineRevision();
            timelineConversationUuidAtStop = this.conversation.getUuid();
            final String msg = this.binding.textinput.getText().toString();
            storeNextMessage(msg);
            updateChatState(this.conversation, msg);
            this.activity.xmppConnectionService.getNotificationService().setOpenConversation(null);
        }
        this.reInitRequiredOnStart = true;
    }

    private void updateChatState(final Conversation conversation, final String msg) {
        ChatState state = msg.length() == 0 ? Config.DEFAULT_CHAT_STATE : ChatState.PAUSED;
        Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(state)) {
            activity.xmppConnectionService.sendChatState(conversation);
        }
    }

    private void saveMessageDraftStopAudioPlayer() {
        final Conversation previousConversation = this.conversation;
        if (this.activity == null || this.binding == null || previousConversation == null) {
            return;
        }
        Log.d(Config.LOGTAG, "ConversationFragment.saveMessageDraftStopAudioPlayer()");
        final String msg = this.binding.textinput.getText().toString();
        storeNextMessage(msg);
        updateChatState(this.conversation, msg);
        messageListAdapter.stopAudioPlayer();
        pendingMediaDraft = null;
        pendingMediaCommitConversationUuid = null;
        mediaPreviewAdapter.clearPreviews();
        toggleInputMethod();
    }

    public void reInit(final Conversation conversation, final Bundle extras) {
        QuickLoader.set(getActivity(), conversation.getUuid());
        final boolean changedConversation = this.conversation != conversation;
        if (changedConversation) {
            if (voiceRecordingActive) {
                cancelInlineVoiceRecording();
            }
            this.saveMessageDraftStopAudioPlayer();
        }
        this.clearPending();
        if (this.reInit(
                conversation,
                extras != null,
                extras != null
                        && extras.getString(ConversationsActivity.EXTRA_MESSAGE_UUID) != null)) {
            if (extras != null) {
                processExtras(extras);
            }
            this.reInitRequiredOnStart = false;
        } else {
            this.reInitRequiredOnStart = true;
            pendingExtras.push(extras);
        }
        resetUnreadMessagesCount();
    }

    private void reInit(Conversation conversation) {
        reInit(conversation, false, false);
    }

    private boolean reInit(
            final Conversation conversation,
            final boolean hasExtras,
            final boolean hasMessageUUID) {
        if (conversation == null) {
            return false;
        }

        final Conversation originalConversation = this.conversation;
        final boolean conversationChanged = originalConversation != conversation;
        this.conversation = conversation;
        if (conversationChanged) {
            forceTimelineRebuild = true;
            renderedTimelineRevision = Long.MIN_VALUE;
            renderedDynamicTimelineSignature = Long.MIN_VALUE;
            renderedConversationPresentationRevision = Long.MIN_VALUE;
            renderedTimelineConversationUuid = null;
            followLatestMessages = pendingScrollState.peek() == null;
            userScrollControlsFollowLatest = false;
            deferredTimelineRefreshWhileReading = false;
            pendingHistoryPageUiPublish = null;
            backwardHistoryPaginationArmed = true;
            suppressNextRefreshBottomFollow = true;
            bottomFollowTailInitialized = false;
            lastBottomFollowTailUuid = null;
            passiveBottomPinPending = false;
            pinBottomDuringImeResize = false;
            imeResizeScrollAnchor = null;
            chromePaddingSettleGeneration++;
        }
        if (hasMessageUUID) {
            forceTimelineRebuild = true;
        }
        hideStickyDateOverlayImmediately();
        // once we set the conversation all is good and it will automatically do the right thing in
        // onStart()
        if (this.activity == null || this.binding == null) {
            return false;
        }

        if (!activity.xmppConnectionService.isConversationStillOpen(this.conversation)) {
            activity.onConversationArchived(this.conversation);
            return false;
        }

        setupReply(conversation.getReplyTo());

        stopScrolling();
        Log.d(Config.LOGTAG, "reInit(hasExtras=" + hasExtras + ")");

        if (this.conversation.isRead() && hasExtras) {
            Log.d(Config.LOGTAG, "trimming conversation");
            this.conversation.trim();
        }

        setupIme();

        final boolean scrolledToBottomAndNoPending =
                pendingScrollState.peek() == null
                        && (conversationChanged
                                ? followLatestMessages
                                : (followLatestMessages || this.scrolledToBottom()));

        this.binding.textSendButton.setContentDescription(
                conversation.withSelf()
                        ? activity.getString(R.string.send_message_to_saved)
                        : activity.getString(R.string.send_message_to_x, conversation.getName()));
        this.binding.textinput.setKeyboardListener(null);
        final boolean participating =
                conversation.getMode() == Conversational.MODE_SINGLE
                        || conversation.getMucOptions().participating();
        if (participating) {
            this.activity.xmppConnectionService.hydrateConversationSecrets(this.conversation);
            this.binding.textinput.setText(this.conversation.getNextMessage());
            this.binding.textinput.setSelection(this.binding.textinput.length());
        } else {
            this.binding.textinput.setText(MessageUtils.EMPTY_STRING);
        }
        this.binding.textinput.setKeyboardListener(this);
        messageListAdapter.updatePreferences();
        refresh(false);
        activity.invalidateOptionsMenu();
        this.conversation.messagesLoaded.set(true);
        activity.xmppConnectionService.scheduleProtectedTextRehydrateForConversation(
                this.conversation);
        activity.xmppConnectionService.scheduleLegacyPlaintextMigrationForConversation(
                this.conversation);
        Log.d(Config.LOGTAG, "scrolledToBottomAndNoPending=" + scrolledToBottomAndNoPending);

        if (!hasMessageUUID && (hasExtras || scrolledToBottomAndNoPending)) {
            resetUnreadMessagesCount();
            synchronized (this.messageList) {
                Log.d(Config.LOGTAG, "jump to first unread message");
                final Message first = conversation.getFirstUnreadMessage();
                final int bottom = Math.max(0, this.messageList.size() - 1);
                final int pos;
                final boolean jumpToBottom;
                if (first == null) {
                    pos = bottom;
                    jumpToBottom = true;
                } else {
                    int i = getIndexOf(first.getUuid(), this.messageList);
                    pos = i < 0 ? bottom : i;
                    jumpToBottom = false;
                }
                setSelection(pos, jumpToBottom);
            }
        }

        this.binding.messagesView.post(this::fireReadEvent);
        // TODO if we only do this when this fragment is running on main it won't *bing* in tablet
        // layout which might be unnecessary since we can *see* it
        activity.xmppConnectionService
                .getNotificationService()
                .setOpenConversation(this.conversation);

        if (commandAdapter != null && conversation != originalConversation) {
            View currentFocus = null;
            if (activity != null) {
                currentFocus = activity.getCurrentFocus();
            }
            conversation.setupViewPager(
                    binding.conversationViewPager, binding.tabLayout, originalConversation);
            refreshCommands();
            maybeRestoreMessageInputFocus(currentFocus);
        }
        if (commandAdapter == null && conversation != null) {
            View currentFocus = null;
            if (activity != null) {
                currentFocus = activity.getCurrentFocus();
            }
            conversation.setupViewPager(binding.conversationViewPager, binding.tabLayout, null);
            commandAdapter = new CommandAdapter((XmppActivity) getActivity());
            binding.commandsView.setAdapter(commandAdapter);
            binding.commandsView.setOnItemClickListener(
                    (parent, view, position, id) -> {
                        if (activity == null) return;

                        final Element command = commandAdapter.getItem(position);
                        activity.startCommand(
                                conversation.getAccount(),
                                command.getAttributeAsJid("jid"),
                                command.getAttribute("node"));
                    });
            refreshCommands();
            maybeRestoreMessageInputFocus(currentFocus);
        }

        previousClickedReply = null;
        restoreInlineVoiceRecordingUiIfNeeded();

        return true;
    }

    public void refreshForNewCaps() {
        refreshCommands();
    }

    protected void refreshCommands() {
        if (commandAdapter == null) return;

        Jid commandJid = conversation.getContact().resourceWhichSupport(Namespace.COMMANDS);
        if (commandJid == null && conversation.getJid().isDomainJid()) {
            commandJid = conversation.getJid();
        }
        if (commandJid == null) {
            conversation.hideViewPager();
        } else {
            activity.xmppConnectionService.fetchCommands(
                    conversation.getAccount(),
                    commandJid,
                    (iq) -> {
                        if (activity == null) return;

                        activity.runOnUiThread(
                                () -> {
                                    if (iq.getType() == Iq.Type.RESULT) {
                                        binding.commandsViewProgressbar.setVisibility(View.GONE);
                                        commandAdapter.clear();
                                        for (Element child : iq.query().getChildren()) {
                                            if (!"item".equals(child.getName())
                                                    || !Namespace.DISCO_ITEMS.equals(
                                                            child.getNamespace())) continue;
                                            commandAdapter.add(child);
                                        }
                                    }

                                    if (commandAdapter.getCount() < 1) {
                                        conversation.hideViewPager();
                                    } else {
                                        conversation.showViewPager();
                                    }
                                });
                    });
        }
    }

    private void maybeRestoreMessageInputFocus(View currentFocus) {
        if (currentFocus == this.binding.textinput) {
            this.binding.textinput.requestFocus();
        }
    }

    private void resetUnreadMessagesCount() {
        lastMessageUuid = null;
        hideUnreadMessagesCount();
    }

    private void hideUnreadMessagesCount() {
        if (this.binding == null) {
            return;
        }
        hideScrollToBottomButton();
        previousClickedReply = null;
        this.binding.unreadCountCustomView.setUnreadCount(0);
        this.binding.unreadCountCustomView.setVisibility(View.GONE);
    }

    private void setSelection(int pos, boolean jumpToBottom) {
        if (binding == null || pos < 0) {
            return;
        }
        if (jumpToBottom) {
            followLatestMessages = true;
            userScrollControlsFollowLatest = false;
            final ListView listView = binding.messagesView;
            listView.setSelection(pos);
            passiveBottomPinPending = true;
            final int generation = ++chromePaddingSettleGeneration;
            ViewCompat.postOnAnimation(
                    listView,
                    () -> {
                        if (binding == null
                                || generation != chromePaddingSettleGeneration
                                || programmaticBottomPin) {
                            return;
                        }
                        final boolean settled = nudgeLatestToProtectedBottom(listView);
                        if (!settled) {
                            ViewCompat.postOnAnimation(
                                    listView,
                                    () -> {
                                        if (binding != null
                                                && generation == chromePaddingSettleGeneration
                                                && !programmaticBottomPin) {
                                            nudgeLatestToProtectedBottom(listView);
                                            if (!imeResizeInProgress) {
                                                passiveBottomPinPending = false;
                                            }
                                        }
                                    });
                        } else if (!imeResizeInProgress) {
                            passiveBottomPinPending = false;
                        }
                    });
        } else {
            followLatestMessages = false;
            userScrollControlsFollowLatest = false;
            binding.messagesView.setSelection(pos);
            binding.messagesView.post(
                    () -> {
                        if (binding != null) {
                            binding.messagesView.setSelection(pos);
                        }
                    });
        }
        binding.messagesView.post(this::fireReadEvent);
    }

    private void animateBottomDeltaWithEasing(
            final ListView listView,
            final int delta,
            final int generation,
            final Runnable onFinished) {
        if (Math.abs(delta) <= 1 || !isProgrammaticBottomPinActive(generation)) {
            onFinished.run();
            return;
        }

        if (bottomScrollAnimator != null) {
            bottomScrollAnimator.cancel();
        }

        final ValueAnimator animator = ValueAnimator.ofInt(0, delta);
        bottomScrollAnimator = animator;
        final int[] previous = {0};
        animator.setDuration(BOTTOM_SCROLL_EASING_DURATION_MS);
        animator.setInterpolator(BOTTOM_SCROLL_EASING);
        animator.addUpdateListener(
                valueAnimator -> {
                    if (!isProgrammaticBottomPinActive(generation) || binding == null) {
                        valueAnimator.cancel();
                        return;
                    }
                    final int current = (Integer) valueAnimator.getAnimatedValue();
                    final int step = current - previous[0];
                    previous[0] = current;
                    if (step != 0) {
                        listView.scrollListBy(step);
                    }
                });
        animator.addListener(
                new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(final Animator animation) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(final Animator animation) {
                        if (bottomScrollAnimator == animation) {
                            bottomScrollAnimator = null;
                        }
                        if (!cancelled && isProgrammaticBottomPinActive(generation)) {
                            onFinished.run();
                        }
                    }
                });
        animator.start();
    }

    private void settleLatestToProtectedBottom(final int generation, final int attempt) {
        if (!isProgrammaticBottomPinActive(generation)
                || binding == null
                || conversation == null
                || conversation.isInHistoryPart()) {
            return;
        }

        final ListView listView = binding.messagesView;
        final int count = listView.getCount();
        if (count <= 0) {
            finishProgrammaticBottomPin(generation);
            return;
        }

        final int lastPosition = count - 1;
        final int childIndex = lastPosition - listView.getFirstVisiblePosition();
        final View lastChild =
                childIndex >= 0 && childIndex < listView.getChildCount()
                        ? listView.getChildAt(childIndex)
                        : null;

        if (lastChild == null) {
            if (attempt >= 4) {
                finishProgrammaticBottomPin(generation);
                return;
            }
            listView.smoothScrollToPosition(lastPosition);
            listView.postDelayed(
                    () -> settleLatestToProtectedBottom(generation, attempt + 1), 120L);
            return;
        }

        final int protectedBottom = listView.getHeight() - listView.getPaddingBottom();
        final int delta = lastChild.getBottom() - protectedBottom;
        if (Math.abs(delta) <= 1) {
            finishProgrammaticBottomPin(generation);
            return;
        }

        animateBottomDeltaWithEasing(
                listView,
                delta,
                generation,
                () -> {
                    if (!isProgrammaticBottomPinActive(generation)) {
                        return;
                    }
                    if (attempt >= 4) {
                        finishProgrammaticBottomPin(generation);
                        return;
                    }
                    // Re-measure after the animation. Composer/IME layout may have changed while
                    // we were moving, so never snap with setSelectionFromTop here.
                    listView.post(() -> settleLatestToProtectedBottom(generation, attempt + 1));
                });
    }

    private void smoothlyPinToLatest() {
        if (binding == null || conversation == null || conversation.isInHistoryPart()) {
            return;
        }
        final int generation = beginProgrammaticBottomPin();
        settleLatestToProtectedBottom(generation, 0);
    }

    private void keepLatestPinnedAfterRefresh(final boolean animateForNewTail) {
        if (binding == null || conversation == null || conversation.isInHistoryPart()) {
            return;
        }

        // If an explicit send/latest animation or IME compensation already owns the bottom edge,
        // let that single owner finish and re-measure the current adapter. Restarting another
        // easing animation for MUC self-echo/status refreshes is what caused the visible "hunting".
        if (programmaticBottomPin || pinBottomDuringImeResize) {
            return;
        }

        if (animateForNewTail) {
            smoothlyPinToLatest();
            return;
        }

        final ListView listView = binding.messagesView;
        passiveBottomPinPending = true;
        final int generation = ++chromePaddingSettleGeneration;
        ViewCompat.postOnAnimation(
                listView,
                () -> {
                    if (binding == null
                            || generation != chromePaddingSettleGeneration
                            || programmaticBottomPin) {
                        return;
                    }
                    final boolean settled = nudgeLatestToProtectedBottom(listView);
                    if (!settled) {
                        ViewCompat.postOnAnimation(
                                listView,
                                () -> {
                                    if (binding != null
                                            && generation == chromePaddingSettleGeneration
                                            && !programmaticBottomPin
                                            && passiveBottomPinPending) {
                                        nudgeLatestToProtectedBottom(listView);
                                        if (!imeResizeInProgress) {
                                            passiveBottomPinPending = false;
                                        }
                                    }
                                });
                    } else if (!imeResizeInProgress) {
                        passiveBottomPinPending = false;
                    }
                });
    }

    private void scrollToLatest() {
        if (binding == null || conversation == null) {
            return;
        }
        final int generation = beginProgrammaticBottomPin();
        if (conversation.isInHistoryPart()) {
            conversation.jumpToLatest();
            refresh(false);
        } else if (deferredTimelineRefreshWhileReading) {
            // The reader explicitly asked for the latest state. Publish the deferred model once,
            // then let the normal bottom-pin path position the refreshed adapter.
            deferredTimelineRefreshWhileReading = false;
            refresh(false);
        }

        final ListView listView = binding.messagesView;
        listView.post(
                () -> {
                    if (!isProgrammaticBottomPinActive(generation) || binding == null) {
                        return;
                    }
                    final int count = listView.getCount();
                    if (count <= 0) {
                        finishProgrammaticBottomPin(generation);
                        return;
                    }

                    final int lastPosition = count - 1;
                    if (listView.getLastVisiblePosition() != lastPosition) {
                        // The explicit "latest" action must not animate through an arbitrarily
                        // long history. ListView smooth scrolling advances at a bounded rate and
                        // used to exhaust the settle retry budget before the last row was visible.
                        // Jump to the final row first; stackFromBottom keeps it at the lower edge.
                        // The existing settle pass on the next frame then performs only the small
                        // pixel correction needed to sit exactly above the composer.
                        listView.setSelection(lastPosition);
                        ViewCompat.postOnAnimation(
                                listView, () -> settleLatestToProtectedBottom(generation, 0));
                    } else {
                        settleLatestToProtectedBottom(generation, 0);
                    }
                });
        listView.post(this::fireReadEvent);
    }

    private boolean scrollAfterSendEnabled() {
        if (activity == null) {
            return true;
        }
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(activity);
        return preferences.getBoolean(
                "scroll_to_bottom", activity.getResources().getBoolean(R.bool.scroll_to_bottom));
    }

    private boolean shouldFollowOwnSend() {
        return scrollAfterSendEnabled() || followLatestMessages || scrolledToBottom();
    }

    private boolean scrolledToBottom() {
        return conversation != null
                && !conversation.isInHistoryPart()
                && binding != null
                && scrolledToBottom(binding.messagesView);
    }

    private void processExtras(final Bundle extras) {
        final String downloadUuid = extras.getString(ConversationsActivity.EXTRA_DOWNLOAD_UUID);
        final String text = extras.getString(Intent.EXTRA_TEXT);
        final String nick = extras.getString(ConversationsActivity.EXTRA_NICK);
        final String node = extras.getString(ConversationsActivity.EXTRA_NODE);
        final String postInitAction =
                extras.getString(ConversationsActivity.EXTRA_POST_INIT_ACTION);
        final boolean asQuote = extras.getBoolean(ConversationsActivity.EXTRA_AS_QUOTE);
        final boolean pm = extras.getBoolean(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, false);
        final boolean doNotAppend =
                extras.getBoolean(ConversationsActivity.EXTRA_DO_NOT_APPEND, false);
        final String type = extras.getString(ConversationsActivity.EXTRA_TYPE);
        final List<Uri> uris = extractUris(extras);
        if (uris != null && uris.size() > 0) {
            if (isCorrectingMessage()) {
                showIncompatibleComposerMode();
                return;
            }
            if (uris.size() == 1 && "geo".equals(uris.get(0).getScheme())) {
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), uris.get(0), Attachment.Type.LOCATION));
            } else {
                final List<Uri> cleanedUris = cleanUris(new ArrayList<>(uris));
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), cleanedUris, type));
            }
            toggleInputMethod();
            return;
        }
        if (nick != null) {
            if (pm) {
                Jid jid = conversation.getJid();
                try {
                    Jid next = Jid.of(jid.getLocal(), jid.getDomain(), nick);
                    privateMessageWith(next);
                } catch (final IllegalArgumentException ignored) {
                    // do nothing
                }
            } else {
                final MucOptions mucOptions = conversation.getMucOptions();
                if (mucOptions.participating() || conversation.getNextCounterpart() != null) {
                    highlightInConference(nick);
                }
            }
        } else {
            if (text != null && GeoHelper.GEO_URI.matcher(text).matches()) {
                mediaPreviewAdapter.addMediaPreviews(
                        Attachment.of(getActivity(), Uri.parse(text), Attachment.Type.LOCATION));
                toggleInputMethod();
                return;
            } else if (text != null && asQuote) {
                quoteText(text);
            } else {
                appendText(text, doNotAppend);
            }
        }
        if (ConversationsActivity.POST_ACTION_RECORD_VOICE.equals(postInitAction)) {
            Toast.makeText(getActivity(), R.string.hold_microphone_to_record, Toast.LENGTH_SHORT)
                    .show();
            updateSendButton();
            return;
        }

        if ("message".equals(postInitAction)) {
            binding.conversationViewPager.post(
                    () -> {
                        binding.conversationViewPager.setCurrentItem(0);
                    });
        }
        if ("command".equals(postInitAction)) {
            binding.conversationViewPager.post(
                    () -> {
                        PagerAdapter adapter = binding.conversationViewPager.getAdapter();
                        if (adapter != null && adapter.getCount() > 1) {
                            binding.conversationViewPager.setCurrentItem(1);
                        }
                        final String jid = extras.getString(ConversationsActivity.EXTRA_JID);
                        Jid commandJid = null;
                        if (jid != null) {
                            try {
                                commandJid = Jid.of(jid);
                            } catch (final IllegalArgumentException e) {
                            }
                        }
                        if (commandJid == null || !commandJid.isFullJid()) {
                            final Jid discoJid =
                                    conversation
                                            .getContact()
                                            .resourceWhichSupport(Namespace.COMMANDS);
                            if (discoJid != null) commandJid = discoJid;
                        }
                        if (node != null && commandJid != null) {
                            conversation.startCommand(
                                    commandFor(commandJid, node),
                                    activity.xmppConnectionService,
                                    activity);
                        }
                    });
            return;
        }

        final Message message =
                downloadUuid == null ? null : conversation.findMessageWithFileAndUuid(downloadUuid);
        if (message != null) {
            startDownloadable(message);
        }

        String messageUuid = extras.getString(ConversationsActivity.EXTRA_MESSAGE_UUID);
        if (messageUuid != null) {
            Runnable postSelectionRunnable = () -> highlightMessage(messageUuid);
            updateSelection(messageUuid, postSelectionRunnable, false, false);
        }
    }

    private Element commandFor(final Jid jid, final String node) {
        if (commandAdapter != null) {
            for (int i = 0; i < commandAdapter.getCount(); i++) {
                Element command = commandAdapter.getItem(i);
                final String commandNode = command.getAttribute("node");
                if (commandNode == null || !commandNode.equals(node)) continue;

                final Jid commandJid = command.getAttributeAsJid("jid");
                if (commandJid != null && !commandJid.asBareJid().equals(jid.asBareJid())) continue;

                return command;
            }
        }

        return new Element("command", Namespace.COMMANDS)
                .setAttribute("name", node)
                .setAttribute("node", node)
                .setAttribute("jid", jid);
    }

    private List<Uri> extractUris(final Bundle extras) {
        final List<Uri> uris = extras.getParcelableArrayList(Intent.EXTRA_STREAM);
        if (uris != null) {
            return uris;
        }
        final Uri uri = extras.getParcelable(Intent.EXTRA_STREAM);
        if (uri != null) {
            return Collections.singletonList(uri);
        } else {
            return null;
        }
    }

    private List<Uri> cleanUris(final List<Uri> uris) {
        final Iterator<Uri> iterator = uris.iterator();
        while (iterator.hasNext()) {
            final Uri uri = iterator.next();
            if (FileBackend.dangerousFile(uri)) {
                iterator.remove();
                Toast.makeText(
                                requireActivity(),
                                R.string.security_violation_not_attaching_file,
                                Toast.LENGTH_SHORT)
                        .show();
            }
        }
        return uris;
    }

    private void updateComposerBlockingState(final Conversation conversation) {
        if (binding == null || conversation == null) {
            return;
        }
        final Account account = conversation.getAccount();
        final int mode = conversation.getMode();

        final boolean rootMuc =
                mode == Conversation.MODE_MULTI && conversation.getNextCounterpart() == null;
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        final boolean explicitlyLeft =
                rootMuc
                        && (conversation.isMucExplicitlyLeft()
                                || (service != null
                                        && service.isMucExplicitlyLeft(
                                                conversation.getAccount(), conversation.getJid())));
        if (explicitlyLeft) {
            final boolean channel = !conversation.getMucOptions().isPrivateAndNonAnonymous();
            showComposerBlockingState(
                    channel
                            ? R.string.muc_explicitly_left_channel
                            : R.string.muc_explicitly_left_group,
                    channel ? R.string.return_to_channel : R.string.return_to_group_chat,
                    joinMuc);
            return;
        }

        // Other archived conversations must never inherit a blocking state from the previously
        // rendered conversation. Clear the in-composer blocker before leaving this update path.
        if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
            hideComposerBlockingState();
            return;
        }

        if (account.getStatus() == Account.State.DISABLED) {
            showComposerBlockingState(
                    R.string.this_account_is_disabled,
                    R.string.enable,
                    this.mEnableAccountListener);
            return;
        }
        if (account.getStatus() == Account.State.LOGGED_OUT) {
            showComposerBlockingState(
                    R.string.this_account_is_logged_out,
                    R.string.log_in,
                    this.mEnableAccountListener);
            return;
        }
        if (conversation.isBlocked()) {
            showComposerBlockingState(
                    R.string.contact_blocked, R.string.unblock, this.mUnblockClickListener);
            return;
        }

        if (isMucPrivateMessage(conversation)
                && !isMucPrivateMessageTargetAvailable(conversation)) {
            final Conversation parent = conversation.getParentConversation();
            final int messageRes =
                    conversation.getMucOptions().online()
                            ? R.string.muc_private_participant_left
                            : R.string.muc_private_room_unavailable;
            showComposerBlockingState(
                    messageRes,
                    parent == null ? 0 : R.string.muc_private_return_to_group,
                    parent == null ? null : view -> activity.switchToConversation(parent));
            return;
        }

        if (mode == Conversation.MODE_MULTI
                && conversation.getNextCounterpart() == null
                && !conversation.getMucOptions().online()
                && account.getStatus() == Account.State.ONLINE) {
            switch (conversation.getMucOptions().getError()) {
                case NICK_IN_USE:
                    showComposerBlockingState(R.string.nick_in_use, R.string.edit, clickToMuc);
                    return;
                case NO_RESPONSE:
                case NONE:
                    showComposerBlockingState(R.string.joining_conference, 0, null);
                    return;
                case SERVER_NOT_FOUND:
                    if (conversation.receivedMessagesCount() > 0) {
                        showComposerBlockingState(
                                R.string.remote_server_not_found, R.string.try_again, joinMuc);
                    } else {
                        showComposerBlockingState(
                                R.string.remote_server_not_found, R.string.leave, leaveMuc);
                    }
                    return;
                case REMOTE_SERVER_TIMEOUT:
                    if (conversation.receivedMessagesCount() > 0) {
                        showComposerBlockingState(
                                R.string.remote_server_timeout, R.string.try_again, joinMuc);
                    } else {
                        showComposerBlockingState(
                                R.string.remote_server_timeout, R.string.leave, leaveMuc);
                    }
                    return;
                case PASSWORD_REQUIRED:
                    showComposerBlockingState(
                            R.string.conference_requires_password,
                            R.string.enter_password,
                            enterPassword);
                    return;
                case BANNED:
                    showComposerBlockingState(R.string.conference_banned, R.string.leave, leaveMuc);
                    return;
                case MEMBERS_ONLY:
                    showComposerBlockingState(
                            R.string.conference_members_only, R.string.leave, leaveMuc);
                    return;
                case RESOURCE_CONSTRAINT:
                    showComposerBlockingState(
                            R.string.conference_resource_constraint, R.string.try_again, joinMuc);
                    return;
                case KICKED:
                    showComposerBlockingState(R.string.conference_kicked, R.string.join, joinMuc);
                    return;
                case TECHNICAL_PROBLEMS:
                    showComposerBlockingState(
                            R.string.conference_technical_problems, R.string.try_again, joinMuc);
                    return;
                case UNKNOWN:
                    showComposerBlockingState(
                            R.string.conference_unknown_error, R.string.try_again, joinMuc);
                    return;
                case INVALID_NICK:
                    showComposerBlockingState(R.string.invalid_muc_nick, R.string.edit, clickToMuc);
                    return;
                case SHUTDOWN:
                    showComposerBlockingState(
                            R.string.conference_shutdown, R.string.try_again, joinMuc);
                    return;
                case DESTROYED:
                    showComposerBlockingState(
                            R.string.conference_destroyed, R.string.leave, leaveMuc);
                    return;
                case NON_ANONYMOUS:
                    // Privacy-sensitive join remains an explicit user action, but now occupies the
                    // composer itself instead of a persistent layer above it.
                    showComposerBlockingState(
                            R.string.group_chat_will_make_your_jabber_id_public,
                            R.string.join,
                            acceptJoin);
                    return;
                default:
                    break;
            }
        }

        if (mode == Conversation.MODE_MULTI
                && conversation.getNextCounterpart() == null
                && conversation.getMucOptions().online()
                && !conversation.getMucOptions().participating()) {
            final MucOptions mucOptions = conversation.getMucOptions();
            final MucOptions.Role role = mucOptions.getSelf().getRole();
            if (mucOptions.moderated() && role == MucOptions.Role.VISITOR) {
                if (mucOptions.isVoiceRequestPending()) {
                    showComposerBlockingState(R.string.muc_voice_request_pending, 0, null);
                } else {
                    showComposerBlockingState(
                            R.string.muc_read_only_visitor,
                            R.string.muc_voice_request_action,
                            view -> requestVoiceInMuc());
                }
            } else {
                showComposerBlockingState(R.string.muc_read_only_generic, 0, null);
            }
            return;
        }

        // Presence/subscription requests, stranger controls and OTR verification are non-blocking
        // conversation actions. They are intentionally not rendered as persistent composer state.
        hideComposerBlockingState();
    }

    private void requestVoiceInMuc() {
        if (conversation == null || activity == null || activity.xmppConnectionService == null) {
            return;
        }
        if (activity.xmppConnectionService.requestVoiceInConference(conversation)) {
            final Conversation targetConversation = conversation;
            updateComposerBlockingState(targetConversation);
            updateChatMsgHint();
            updateEditablity();
            if (binding != null) {
                binding.textsend.postDelayed(
                        () -> {
                            if (binding == null
                                    || conversation != targetConversation
                                    || targetConversation.getMucOptions().isVoiceRequestPending()) {
                                return;
                            }
                            updateComposerBlockingState(targetConversation);
                            updateChatMsgHint();
                            updateSendButton();
                            updateEditablity();
                        },
                        MucOptions.VOICE_REQUEST_COOLDOWN_MILLIS + 250L);
            }
            Toast.makeText(requireActivity(), R.string.muc_voice_request_sent, Toast.LENGTH_SHORT)
                    .show();
        } else {
            Toast.makeText(
                            requireActivity(),
                            R.string.muc_voice_request_unavailable,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private boolean isComposerBlocked(final Conversation conversation) {
        if (conversation == null || conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
            return true;
        }
        final Account account = conversation.getAccount();
        if (account.getStatus() == Account.State.DISABLED
                || account.getStatus() == Account.State.LOGGED_OUT
                || conversation.isBlocked()) {
            return true;
        }
        if (conversation.getMode() != Conversation.MODE_MULTI) {
            return false;
        }
        // Root MUCs are not writable until our own room presence has completed. Private
        // occupant chats are handled separately because their availability depends on the target
        // occupant rather than the room join state alone.
        return conversation.getNextCounterpart() == null
                && !conversation.getMucOptions().online()
                && account.getStatus() == Account.State.ONLINE;
    }

    @Override
    public void refresh() {
        if (this.binding == null) {
            Log.d(
                    Config.LOGTAG,
                    "ConversationFragment.refresh() skipped updated because view binding was null");
            return;
        }
        updateChatBG();
        if (this.conversation != null
                && this.activity != null
                && this.activity.xmppConnectionService != null) {
            if (!activity.xmppConnectionService.isConversationStillOpen(this.conversation)) {
                activity.onConversationArchived(this.conversation);
                return;
            }
        }
        if (this.conversation != null
                && this.activity != null
                && this.activity.xmppConnectionService != null) {
            activity.xmppConnectionService.scheduleProtectedTextRehydrateForConversation(
                    this.conversation);
        }
        this.refresh(true);
    }

    private long dynamicTimelineSignature(final Conversation conversation) {
        if (conversation.getMode() != Conversation.MODE_MULTI
                || conversation.getNextCounterpart() != null) {
            return timelinePresentationRevision;
        }
        ChatState state = ChatState.COMPOSING;
        List<User> users = conversation.getMucOptions().getUsersWithChatState(state, 5);
        if (users.isEmpty()) {
            state = ChatState.PAUSED;
            users = conversation.getMucOptions().getUsersWithChatState(state, 5);
        }
        long signature = state.ordinal();
        for (final User user : users) {
            signature = 31L * signature + String.valueOf(user.getFullJid()).hashCode();
        }
        return 31L * (31L * signature + users.size()) + timelinePresentationRevision;
    }

    private void markTimelinePresentationChanged() {
        timelinePresentationRevision++;
    }

    private boolean shouldFreezeTimelineForReading() {
        return binding != null
                && conversation != null
                && !conversation.isInHistoryPart()
                && pendingSelectionUuid == null
                && binding.messagesView.getCount() > 0
                && !isAutomaticBottomPinActive()
                && !scrolledToBottom(binding.messagesView);
    }

    private void refresh(boolean notifyConversationRead) {
        synchronized (this.messageList) {
            if (this.conversation != null) {
                final boolean suppressBottomFollow = suppressNextRefreshBottomFollow;
                suppressNextRefreshBottomFollow = false;
                final long revisionAfter = conversation.getTimelineRevision();
                final long dynamicSignature = dynamicTimelineSignature(conversation);
                final long presentationRevisionAfter =
                        activity.xmppConnectionService.getConversationPresentationRevision();
                final boolean conversationChanged =
                        !TextUtils.equals(renderedTimelineConversationUuid, conversation.getUuid());
                final boolean revisionChanged = renderedTimelineRevision != revisionAfter;
                final boolean dynamicTimelineChanged =
                        renderedDynamicTimelineSignature != dynamicSignature;
                final TimelineRefreshGate.RefreshAction refreshAction =
                        TimelineRefreshGate.evaluate(
                                forceTimelineRebuild,
                                conversationChanged,
                                renderedTimelineRevision,
                                revisionAfter,
                                renderedDynamicTimelineSignature,
                                dynamicSignature,
                                renderedConversationPresentationRevision,
                                presentationRevisionAfter);
                final boolean requestedAdapterRefresh =
                        refreshAction != TimelineRefreshGate.RefreshAction.NONE;
                final boolean deferTimelineRefresh =
                        requestedAdapterRefresh
                                && !forceTimelineRebuild
                                && !conversationChanged
                                && shouldFreezeTimelineForReading();
                if (deferTimelineRefresh) {
                    deferredTimelineRefreshWhileReading = true;
                }
                final boolean fullRebuild =
                        !deferTimelineRefresh
                                && refreshAction == TimelineRefreshGate.RefreshAction.REBUILD;
                final boolean rowRebind =
                        !deferTimelineRefresh
                                && refreshAction == TimelineRefreshGate.RefreshAction.REBIND;
                final boolean adapterRefresh = requestedAdapterRefresh && !deferTimelineRefresh;
                final boolean keepBottomPinned =
                        adapterRefresh
                                && !suppressBottomFollow
                                && binding != null
                                && binding.messagesView.getCount() > 0
                                && !conversation.isInHistoryPart()
                                && (isAutomaticBottomPinActive()
                                        || scrolledToBottom(binding.messagesView));
                final VisualScrollAnchor preservedScrollAnchor =
                        adapterRefresh && !keepBottomPinned ? captureVisualScrollAnchor() : null;
                final String reason;
                if (forceTimelineRebuild) {
                    reason = "structural";
                } else if (conversationChanged) {
                    reason = "conversation";
                } else if (revisionChanged) {
                    reason = "revision";
                } else if (dynamicTimelineChanged) {
                    reason = "status";
                } else if (rowRebind) {
                    reason = "presentation";
                } else if (timelineRevisionAtStop == revisionAfter
                        && TextUtils.equals(
                                timelineConversationUuidAtStop, conversation.getUuid())) {
                    reason = "lifecycle-unchanged";
                } else {
                    reason = "chrome";
                }
                if (deferTimelineRefresh) {
                    Log.d(
                            Config.LOGTAG,
                            "timeline-refresh: reason="
                                    + reason
                                    + " policy=DEFER_WHILE_READING revisionBefore="
                                    + renderedTimelineRevision
                                    + " revisionAfter="
                                    + revisionAfter);
                    binding.messagesView.post(this::toggleScrollDownButton);
                }
                if (adapterRefresh || "lifecycle-unchanged".equals(reason)) {
                    Log.d(
                            Config.LOGTAG,
                            "timeline-refresh: reason="
                                    + reason
                                    + " revisionBefore="
                                    + renderedTimelineRevision
                                    + " revisionAfter="
                                    + revisionAfter
                                    + " policy="
                                    + (keepBottomPinned ? "FOLLOW_BOTTOM" : "PRESERVE_ANCHOR")
                                    + " anchorUuid="
                                    + (preservedScrollAnchor == null
                                            ? null
                                            : preservedScrollAnchor.messageUuid)
                                    + " anchorOffset="
                                    + (preservedScrollAnchor == null
                                            ? 0
                                            : preservedScrollAnchor.topOffsetPx)
                                    + " fullRebuild="
                                    + fullRebuild);
                    if ("lifecycle-unchanged".equals(reason)) {
                        timelineRevisionAtStop = Long.MIN_VALUE;
                        timelineConversationUuidAtStop = null;
                    }
                }

                if (fullRebuild) {
                    final Message latestMessageBeforePresentation = conversation.getLatestMessage();
                    final String latestTailUuid =
                            latestMessageBeforePresentation == null
                                    ? null
                                    : latestMessageBeforePresentation.getUuid();
                    final boolean latestTailChanged =
                            bottomFollowTailInitialized
                                    && !TextUtils.equals(lastBottomFollowTailUuid, latestTailUuid);
                    bottomFollowTailInitialized = true;
                    lastBottomFollowTailUuid = latestTailUuid;
                    conversation.populateWithMessages(this.messageList);
                    appendOutgoingMediaPreparingMessages();
                    coalesceIncomingMediaPresentation();
                    updateStatusMessages();
                    updateMediaGalleryPresentation();
                    selectedMessages.removeIf(message -> !messageList.contains(message));
                    if (selectionActionMode != null) {
                        if (selectedMessages.isEmpty()) {
                            selectionActionMode.finish();
                        } else {
                            selectionActionMode.invalidate();
                        }
                    }

                    this.messageListAdapter.notifyDataSetChanged();
                    renderedTimelineRevision = revisionAfter;
                    renderedDynamicTimelineSignature = dynamicTimelineSignature(conversation);
                    renderedConversationPresentationRevision = presentationRevisionAfter;
                    renderedTimelineConversationUuid = conversation.getUuid();
                    forceTimelineRebuild = false;
                    deferredTimelineRefreshWhileReading = false;
                    binding.messagesView.post(
                            () -> {
                                if (keepBottomPinned) {
                                    keepLatestPinnedAfterRefresh(latestTailChanged);
                                } else if (preservedScrollAnchor != null) {
                                    restoreVisualScrollAnchor(preservedScrollAnchor);
                                } else {
                                    toggleScrollDownButton();
                                }
                            });
                } else if (rowRebind) {
                    this.messageListAdapter.notifyDataSetChanged();
                    renderedConversationPresentationRevision = presentationRevisionAfter;
                    deferredTimelineRefreshWhileReading = false;
                    binding.messagesView.post(
                            () -> {
                                if (keepBottomPinned) {
                                    keepLatestPinnedAfterRefresh(false);
                                } else if (preservedScrollAnchor != null) {
                                    restoreVisualScrollAnchor(preservedScrollAnchor);
                                } else {
                                    toggleScrollDownButton();
                                }
                            });
                }
                updateComposerBlockingState(conversation);
                updateChatMsgHint();
                if (notifyConversationRead && activity != null) {
                    binding.messagesView.post(this::fireReadEvent);
                }
                updateSendButton();
                updateEditablity();
                conversation.refreshSessions();

                if (conversation != null
                        && conversation.getMode() == Conversational.MODE_MULTI
                        && conversation.getNextCounterpart() == null) {
                    String subject = conversation.getMucOptions().getSubject();
                    Boolean hidden = conversation.getMucOptions().subjectHidden();

                    if (Bookmark.printableValue(subject) && !hidden) {
                        binding.mucSubjectText.setText(subject);
                        binding.mucSubject.setOnClickListener(
                                v -> ConferenceDetailsActivity.open(getActivity(), conversation));
                        binding.mucSubjectHide.setOnClickListener(
                                v -> {
                                    conversation.getMucOptions().hideSubject();
                                    binding.mucSubject.setVisibility(View.GONE);
                                    binding.mucSubject.post(this::updateMessageListChromePadding);
                                });
                        binding.mucSubject.setVisibility(View.VISIBLE);
                        binding.mucSubject.post(this::updateMessageListChromePadding);
                    } else {
                        binding.mucSubject.setVisibility(View.GONE);
                        binding.mucSubject.post(this::updateMessageListChromePadding);
                    }
                } else {
                    binding.mucSubject.setVisibility(View.GONE);
                    binding.mucSubject.post(this::updateMessageListChromePadding);
                }
            }
        }
    }

    protected void messageSent() {
        messageSent(false);
    }

    private void messageSent(final boolean deferBottomFollowUntilPublished) {
        final boolean followOwnSend = shouldFollowOwnSend();
        final VisualScrollAnchor preservedScrollAnchor =
                followOwnSend ? null : captureVisualScrollAnchor();

        binding.textinput.setText("");
        if (conversation.setCorrectingMessage(null)) {
            binding.textinput.append(conversation.getDraftMessage());
            conversation.setDraftMessage(null);
        }
        storeNextMessage();
        updateChatMsgHint();

        if (followOwnSend) {
            followLatestMessages = true;
            if (!deferBottomFollowUntilPublished && !isAutomaticBottomPinActive()) {
                scrollToLatest();
            }
        } else if (preservedScrollAnchor != null) {
            // Clearing a multiline composer changes the overlay padding. Restore the exact reading
            // position after that layout pass so sending does not move the history underneath.
            binding.textsend.post(
                    () -> {
                        if (binding != null && conversation != null) {
                            restoreVisualScrollAnchor(preservedScrollAnchor);
                        }
                    });
        }
    }

    private boolean storeNextMessage() {
        return storeNextMessage(this.binding.textinput.getText().toString());
    }

    private boolean storeNextMessage(String msg) {
        final boolean participating =
                conversation.getMode() == Conversational.MODE_SINGLE
                        || conversation.getMucOptions().participating();
        if (this.conversation.getStatus() != Conversation.STATUS_ARCHIVED && participating) {
            return this.activity.xmppConnectionService.persistSecureDraft(this.conversation, msg);
        }
        return false;
    }

    public long getMaxHttpUploadSize(Conversation conversation) {
        final XmppConnection connection = conversation.getAccount().getXmppConnection();
        return connection == null ? -1 : connection.getFeatures().getMaxHttpUploadSize();
    }

    private boolean isMucPrivateMessage(final Conversation conversation) {
        return conversation != null
                && conversation.getMode() == Conversation.MODE_MULTI
                && conversation.getNextCounterpart() != null;
    }

    private boolean isMucPrivateMessageTargetAvailable(final Conversation conversation) {
        if (!isMucPrivateMessage(conversation)) {
            return true;
        }
        final MucOptions mucOptions = conversation.getMucOptions();
        return mucOptions.online() && mucOptions.isUserInRoom(conversation.getNextCounterpart());
    }

    private boolean canWriteToConversation(final Conversation conversation) {
        if (conversation == null || isComposerBlocked(conversation)) {
            return false;
        }
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            return true;
        }
        if (isMucPrivateMessage(conversation)) {
            return isMucPrivateMessageTargetAvailable(conversation);
        }
        return conversation.getMucOptions().participating();
    }

    private void updateEditablity() {
        final boolean canWrite = canWriteToConversation(this.conversation);
        this.binding.textinput.setFocusable(canWrite);
        this.binding.textinput.setFocusableInTouchMode(canWrite);
        this.binding.textSendButton.setEnabled(canWrite);
        this.binding.textAttachButton.setEnabled(canWrite);
        final int attachIconColor =
                MaterialColors.getColor(
                        this.binding.textAttachButton,
                        com.google.android.material.R.attr.colorOnSurfaceVariant);
        this.binding.textAttachButton.setIconTint(ColorStateList.valueOf(attachIconColor));
        this.binding.textAttachButton.setAlpha(canWrite ? 1f : 0.38f);
        this.binding.textSendButton.setAlpha(canWrite ? 1f : 0.38f);
        if (canWrite) {
            updateSendButton();
        } else {
            final int disabledSendColor =
                    MaterialColors.getColor(
                            this.binding.textSendButton,
                            com.google.android.material.R.attr.colorOnSurfaceVariant);
            this.binding.textSendButton.setIconTint(ColorStateList.valueOf(disabledSendColor));
        }
        this.binding.textinput.setCursorVisible(canWrite);
        this.binding.textinput.setEnabled(canWrite);
        updateComposerFormattingToolbar();
    }

    public void updateSendButton() {
        if (binding == null) {
            return;
        }
        if (voiceRecordingActive) {
            updateVoiceRecordingSendButton();
            return;
        }
        boolean hasAttachments =
                mediaPreviewAdapter != null && mediaPreviewAdapter.hasAttachments();
        final Conversation c = this.conversation;
        if (c == null) {
            return;
        }
        final Presence.Status status;
        final String text =
                this.binding.textinput == null ? "" : this.binding.textinput.getText().toString();
        final SendButtonAction action;
        if (hasAttachments) {
            action = SendButtonAction.TEXT;
        } else {
            action = SendButtonTool.getAction(getActivity(), c, text);
        }
        if (c.getAccount().getStatus() == Account.State.ONLINE) {
            if (activity != null
                    && activity.xmppConnectionService != null
                    && activity.xmppConnectionService.getMessageArchiveService().isCatchingUp(c)) {
                status = Presence.Status.OFFLINE;
            } else if (c.getMode() == Conversation.MODE_SINGLE) {
                status = c.getContact().getShownStatus();
            } else {
                status =
                        c.getMucOptions().online()
                                ? Presence.Status.ONLINE
                                : Presence.Status.OFFLINE;
            }
        } else {
            status = Presence.Status.OFFLINE;
        }
        this.binding.textSendButton.setTag(action);
        final Activity activity = getActivity();
        if (activity != null) {
            /*int imageResource = SendButtonTool.getSendButtonImageResource(activity, action, status);
            boolean shouldBePrimary = SendButtonTool.shouldSendButtonBePrimary(action, status);
            Drawable image = AppCompatResources.getDrawable(getContext(), imageResource);
            if (shouldBePrimary) {
                image.setTint(getOrCalculatePrimaryColor());
            }


            this.binding.textSendButton.setIconResource(imageResource);
            this.binding.textSendButton.setIconTint(
                    ColorStateList.valueOf());*/

            this.binding.textSendButton.setTag(action);
            this.binding.textSendButton.setIconResource(
                    SendButtonTool.getSendButtonImageResource(action));
            final int sendIconColor =
                    canWriteToConversation(c)
                            ? SendButtonTool.getSendButtonColor(this.binding.textSendButton, status)
                            : MaterialColors.getColor(
                                    this.binding.textSendButton,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant);
            this.binding.textSendButton.setIconTint(ColorStateList.valueOf(sendIconColor));
            if (action == SendButtonAction.RECORD_VOICE) {
                this.binding.textSendButton.setContentDescription(
                        getString(R.string.hold_microphone_to_record));
            } else {
                this.binding.textSendButton.setContentDescription(
                        c.withSelf()
                                ? getString(R.string.send_message_to_saved)
                                : getString(R.string.send_message_to_x, c.getName()));
            }
        }
    }

    private void coalesceIncomingMediaPresentation() {
        if (conversation == null
                || conversation.getMode() != Conversational.MODE_SINGLE
                || binding == null) {
            return;
        }

        final String conversationUuid = conversation.getUuid();
        if (!conversationUuid.equals(incomingMediaCoalescingConversationUuid)) {
            resetIncomingMediaCoalescing(conversationUuid);
            for (final Message message : messageList) {
                if (message != null && message.getUuid() != null) {
                    incomingMediaKnownMessageUuids.add(message.getUuid());
                }
            }
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final Iterator<Map.Entry<String, IncomingMediaCoalescingState>> stateIterator =
                incomingMediaCoalescingStates.entrySet().iterator();
        while (stateIterator.hasNext()) {
            final Map.Entry<String, IncomingMediaCoalescingState> entry = stateIterator.next();
            if (entry.getValue().deadlineUptime <= now) {
                incomingMediaReleasedAnchorIds.add(entry.getKey());
                stateIterator.remove();
            }
        }

        final List<Message> snapshot = new ArrayList<>(messageList);
        final Set<String> newlyObservedMessageUuids = new HashSet<>();
        for (final Message message : snapshot) {
            if (message != null
                    && message.getUuid() != null
                    && incomingMediaKnownMessageUuids.add(message.getUuid())) {
                newlyObservedMessageUuids.add(message.getUuid());
            }
        }

        for (final Message message : snapshot) {
            if (message == null
                    || message.getUuid() == null
                    || !newlyObservedMessageUuids.contains(message.getUuid())
                    || !isIncomingDirectMessage(message)) {
                continue;
            }

            final String relatedAnchorId = incomingMediaRelationAnchorId(message);
            if (relatedAnchorId != null) {
                if (incomingMediaCoalescingStates.containsKey(relatedAnchorId)
                        || !hasPreviouslyPublishedIncomingAnchor(
                                snapshot, relatedAnchorId, newlyObservedMessageUuids)) {
                    extendIncomingMediaCoalescing(relatedAnchorId, now);
                } else {
                    incomingMediaReleasedAnchorIds.add(relatedAnchorId);
                }
                continue;
            }

            if (isIncomingMediaCoalescingCandidate(message)) {
                final String anchorId = incomingMediaAnchorIdentity(message);
                if (anchorId != null) {
                    extendIncomingMediaCoalescing(anchorId, now);
                }
            }
        }

        messageList.removeIf(message -> shouldSuppressIncomingMediaRow(message, now));
        scheduleIncomingMediaCoalescingRefresh(now);
    }

    private void resetIncomingMediaCoalescing(final String conversationUuid) {
        if (binding != null) {
            binding.messagesView.removeCallbacks(incomingMediaCoalescingRefreshRunnable);
        }
        incomingMediaCoalescingConversationUuid = conversationUuid;
        incomingMediaCoalescingStates.clear();
        incomingMediaKnownMessageUuids.clear();
        incomingMediaReleasedAnchorIds.clear();
    }

    private void extendIncomingMediaCoalescing(final String anchorId, final long now) {
        if (anchorId.isEmpty() || incomingMediaReleasedAnchorIds.contains(anchorId)) {
            return;
        }
        final IncomingMediaCoalescingState existing = incomingMediaCoalescingStates.get(anchorId);
        if (existing == null) {
            incomingMediaCoalescingStates.put(anchorId, new IncomingMediaCoalescingState(now));
        } else {
            existing.extend(now);
        }
    }

    private void scheduleIncomingMediaCoalescingRefresh(final long now) {
        if (binding == null) {
            return;
        }
        long earliestDeadline = Long.MAX_VALUE;
        for (final IncomingMediaCoalescingState state : incomingMediaCoalescingStates.values()) {
            if (state.deadlineUptime > now) {
                earliestDeadline = Math.min(earliestDeadline, state.deadlineUptime);
            }
        }
        binding.messagesView.removeCallbacks(incomingMediaCoalescingRefreshRunnable);
        if (earliestDeadline != Long.MAX_VALUE) {
            binding.messagesView.postDelayed(
                    incomingMediaCoalescingRefreshRunnable, Math.max(1L, earliestDeadline - now));
        }
    }

    private static boolean hasPreviouslyPublishedIncomingAnchor(
            final List<Message> snapshot,
            final String anchorId,
            final Set<String> newlyObservedMessageUuids) {
        for (final Message candidate : snapshot) {
            if (candidate == null
                    || candidate.getUuid() == null
                    || newlyObservedMessageUuids.contains(candidate.getUuid())
                    || !isIncomingMediaCoalescingCandidate(candidate)) {
                continue;
            }
            final String candidateAnchorId = incomingMediaAnchorIdentity(candidate);
            if (anchorId.equals(candidateAnchorId)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSuppressIncomingMediaRow(
            @Nullable final Message message, final long now) {
        if (!isIncomingDirectMessage(message)) {
            return false;
        }

        final String relatedAnchorId = incomingMediaRelationAnchorId(message);
        if (relatedAnchorId != null) {
            final IncomingMediaCoalescingState state =
                    incomingMediaCoalescingStates.get(relatedAnchorId);
            return state != null && state.deadlineUptime > now;
        }

        if (!isIncomingMediaCoalescingCandidate(message)) {
            return false;
        }
        final String anchorId = incomingMediaAnchorIdentity(message);
        final IncomingMediaCoalescingState state =
                anchorId == null ? null : incomingMediaCoalescingStates.get(anchorId);
        return state != null && state.deadlineUptime > now;
    }

    private boolean isIncomingDirectMessage(@Nullable final Message message) {
        return message != null
                && message.getConversation() == conversation
                && message.getStatus() <= Message.STATUS_RECEIVED
                && message.getType() != Message.TYPE_STATUS
                && !message.isPrivateMessage();
    }

    private static boolean isIncomingMediaCoalescingCandidate(final Message message) {
        if (!message.isFileOrImage()) {
            return false;
        }
        final String mime = message.getMimeType();
        return message.getType() == Message.TYPE_IMAGE
                || (mime != null && (mime.startsWith("image/") || mime.startsWith("video/")));
    }

    @Nullable
    private static String incomingMediaAnchorIdentity(final Message message) {
        final String remoteId = message.getRemoteMsgId();
        if (remoteId != null && !remoteId.isEmpty()) {
            return remoteId;
        }
        final String uuid = message.getUuid();
        return uuid == null || uuid.isEmpty() ? null : uuid;
    }

    @Nullable
    private static String incomingMediaRelationAnchorId(final Message message) {
        String anchorId = null;
        for (final Element payload : message.getPayloads()) {
            if (!"attach-to".equals(payload.getName())
                    || !Namespace.MESSAGE_ATTACHING.equals(payload.getNamespace())) {
                continue;
            }
            final String candidate = payload.getAttribute("id");
            if (candidate == null
                    || candidate.isEmpty()
                    || !candidate.equals(candidate.trim())
                    || anchorId != null) {
                return null;
            }
            anchorId = candidate;
        }
        return anchorId;
    }

    private void updateMediaGalleryPresentation() {
        if (conversation == null || messageListAdapter == null) {
            return;
        }
        final List<Message> snapshot = new ArrayList<>();
        for (final Message message : messageList) {
            if (message != null
                    && message.getConversation() == conversation
                    && message.getType() != Message.TYPE_STATUS) {
                snapshot.add(message);
            }
        }
        if (mediaGalleryPresentation == null
                || !mediaGalleryPresentation.matchesSnapshot(conversation, snapshot)) {
            mediaGalleryPresentation =
                    MediaGalleryPresentation.forSnapshot(
                            conversation, ++mediaGalleryRevision, snapshot);
        }
        if (mediaCaptionPresentation == null
                || !mediaCaptionPresentation.matchesSnapshot(conversation, snapshot)) {
            mediaCaptionPresentation = MediaCaptionPresentation.forSnapshot(conversation, snapshot);
        }
        messageListAdapter.setIncomingMediaGalleryPresentation(mediaGalleryPresentation);
        messageListAdapter.setMediaCaptionPresentation(mediaCaptionPresentation);
        messageListAdapter.setOutgoingMediaPreparingPresentation(
                outgoingMediaPreparingPresentation);
    }

    private void appendOutgoingMediaPreparingMessages() {
        if (conversation == null) {
            return;
        }

        // populateWithMessages() may run while background upload callbacks are adding one member of
        // a prepared batch at a time. Keep those partial rows hidden behind the optimistic album
        // until the complete local group (and related caption, when applicable) is visible.
        final List<Message> publishedSnapshot = new ArrayList<>(messageList);
        final Iterator<OutgoingMediaPreparingSession> iterator =
                outgoingMediaPreparingSessions.iterator();
        while (iterator.hasNext()) {
            final OutgoingMediaPreparingSession session = iterator.next();
            if (session.terminal || session.draft.getConversation() != conversation) {
                continue;
            }

            if (session.keepsPlaceholderUntilPublishedGroup()) {
                final boolean completePublishedGroup =
                        session.preparationComplete
                                && hasCompletePublishedMediaGroup(session, publishedSnapshot);
                if (completePublishedGroup && session.publishedHandoffArmed) {
                    // Two-phase handoff: only drop the optimistic row after one full adapter
                    // refresh has already observed the complete real group. Otherwise the first
                    // refresh can expose the individual outgoing children for a frame before
                    // album/caption presentation catches up.
                    session.terminal = true;
                    outgoingMediaPreparingPresentation.remove(session.placeholder);
                    messageList.remove(session.placeholder);
                    iterator.remove();
                    markTimelinePresentationChanged();
                    continue;
                }
                if (completePublishedGroup && !session.publishedHandoffArmed) {
                    session.publishedHandoffArmed = true;
                    if (binding != null) {
                        // Upload/Jingle progress normally triggers the next refresh by itself.
                        // Avoid an immediate second full list rebuild here: it causes a visible
                        // optimistic -> real -> rebound flash. Keep only a slow safety fallback.
                        binding.messagesView.postDelayed(
                                () -> {
                                    if (!session.terminal
                                            && conversation == session.draft.getConversation()) {
                                        forceTimelineRebuild = true;
                                        refresh(false);
                                    }
                                },
                                500L);
                    }
                }
                messageList.removeIf(message -> isPublishedMediaGroupMember(session, message));
            }

            if (!messageList.contains(session.placeholder)) {
                messageList.add(session.placeholder);
            }
        }
    }

    private static boolean isPublishedMediaGroupMember(
            final OutgoingMediaPreparingSession session, @Nullable final Message message) {
        return message != null
                && message.getStatus() > Message.STATUS_RECEIVED
                && message.isFileOrImage()
                && session.mediaGroupId != null
                && session.mediaGroupId.equals(message.getMediaGroupId());
    }

    private static boolean hasCompletePublishedMediaGroup(
            final OutgoingMediaPreparingSession session, final List<Message> publishedSnapshot) {
        int publishedMediaCount = 0;
        Message anchor = null;
        for (final Message message : publishedSnapshot) {
            if (!isPublishedMediaGroupMember(session, message)) {
                continue;
            }
            publishedMediaCount++;
            if (anchor == null) {
                anchor = message;
            }
        }
        if (publishedMediaCount < session.expectedMediaMessages) {
            return false;
        }
        if (!session.waitForRelatedCaption) {
            return true;
        }
        if (anchor != null && MediaCaptionResolver.getCaption(anchor, publishedSnapshot) != null) {
            return true;
        }
        // The first published member is normally the anchor, but keep reconciliation robust to a
        // callback ordering change by checking every member of the local group.
        for (final Message message : publishedSnapshot) {
            if (isPublishedMediaGroupMember(session, message)
                    && MediaCaptionResolver.getCaption(message, publishedSnapshot) != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private OutgoingMediaPreparingSession beginOutgoingMediaPreparingSession(
            final MediaDraftSnapshot draft) {
        return beginOutgoingMediaPreparingSession(draft, null, 0, false);
    }

    @Nullable
    private OutgoingMediaPreparingSession beginOutgoingMediaPreparingSession(
            final MediaDraftSnapshot draft,
            @Nullable final String mediaGroupId,
            final int expectedMediaMessages,
            final boolean waitForRelatedCaption) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT
                || draft.getConversation() == null
                || draft.getAttachments().isEmpty()
                || binding == null
                || messageListAdapter == null) {
            return null;
        }
        for (final Attachment attachment : draft.getAttachments()) {
            if (attachment.getType() == Attachment.Type.LOCATION) {
                return null;
            }
        }
        final Message placeholder =
                new Message(
                        draft.getConversation(), "", draft.getConversation().getNextEncryption());
        placeholder.setStatus(Message.STATUS_WAITING);
        final OutgoingMediaPreparingSession session =
                new OutgoingMediaPreparingSession(
                        draft,
                        placeholder,
                        mediaGroupId,
                        expectedMediaMessages,
                        waitForRelatedCaption);
        outgoingMediaPreparingSessions.add(session);
        outgoingMediaPreparingPresentation.add(
                placeholder, draft.getCaption(), draft.getAttachments());
        messageList.add(placeholder);
        markTimelinePresentationChanged();
        updateMediaGalleryPresentation();
        messageListAdapter.notifyDataSetChanged();
        return session;
    }

    private void completeOutgoingMediaPreparingAttachment(
            @Nullable final OutgoingMediaPreparingSession session,
            @Nullable final Attachment attachment) {
        if (session == null || attachment == null || activity == null) {
            return;
        }
        activity.runOnUiThread(() -> session.onAttachmentReady(attachment));
    }

    private void failOutgoingMediaPreparingSession(
            @Nullable final OutgoingMediaPreparingSession session,
            @Nullable final Attachment attachment,
            final int errorCode) {
        if (session == null || activity == null) {
            return;
        }
        activity.runOnUiThread(() -> session.onAttachmentFailed(attachment, errorCode));
    }

    private void removeOutgoingMediaPreparingSession(final OutgoingMediaPreparingSession session) {
        outgoingMediaPreparingSessions.remove(session);
        outgoingMediaPreparingPresentation.remove(session.placeholder);
        messageList.remove(session.placeholder);
        markTimelinePresentationChanged();
        if (messageListAdapter != null) {
            updateMediaGalleryPresentation();
            messageListAdapter.notifyDataSetChanged();
        }
    }

    protected void updateStatusMessages() {
        DateSeparator.addAll(this.messageList);
        if (showLoadMoreMessages(conversation)) {
            this.messageList.add(0, Message.createLoadMoreMessage(conversation));
        }
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            // One-to-one chat state is rendered in the conversation header.
            // Read receipts remain represented by delivered/read check marks.
            return;
        }

        final MucOptions mucOptions = conversation.getMucOptions();
        ChatState state = ChatState.COMPOSING;
        List<MucOptions.User> users = mucOptions.getUsersWithChatState(state, 5);
        if (users.size() == 0) {
            state = ChatState.PAUSED;
            users = mucOptions.getUsersWithChatState(state, 5);
        }

        final boolean isMucPm =
                conversation.getNextCounterpart() != null
                        && conversation.hasPermanentCounterpart()
                        && conversation.getMode() == Conversation.MODE_MULTI;

        if (users.size() > 0 && !isMucPm) {
            final Message statusMessage;
            if (users.size() == 1) {
                final MucOptions.User user = users.get(0);
                final int id =
                        state == ChatState.COMPOSING
                                ? R.string.contact_is_typing
                                : R.string.contact_has_stopped_typing;
                statusMessage =
                        Message.createStatusMessage(
                                conversation, getString(id, UIHelper.getDisplayName(user)));
                statusMessage.setTrueCounterpart(user.getRealJid());
                statusMessage.setCounterpart(user.getFullJid());
            } else {
                final int id =
                        state == ChatState.COMPOSING
                                ? R.string.contacts_are_typing
                                : R.string.contacts_have_stopped_typing;
                statusMessage =
                        Message.createStatusMessage(
                                conversation, getString(id, UIHelper.concatNames(users)));
                statusMessage.setCounterparts(users);
            }
            this.messageList.add(statusMessage);
        }
    }

    private void stopScrolling() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        binding.messagesView.dispatchTouchEvent(cancel);
    }

    private boolean showLoadMoreMessages(final Conversation c) {
        if (activity == null || activity.xmppConnectionService == null) {
            return false;
        }
        final boolean mam = hasMamSupport(c) && !c.getContact().isBlocked();
        final MessageArchiveService service =
                activity.xmppConnectionService.getMessageArchiveService();
        return mam
                && (c.getLastClearHistory().getTimestamp() != 0
                        || (c.countMessages() == 0
                                && c.messagesLoaded.get()
                                && c.hasMessagesLeftOnServer()
                                && !service.queryInProgress(c)));
    }

    private boolean hasMamSupport(final Conversation c) {
        if (c.getMode() == Conversation.MODE_SINGLE) {
            final XmppConnection connection = c.getAccount().getXmppConnection();
            return connection != null && connection.getFeatures().mam();
        } else {
            return c.getMucOptions().mamSupport();
        }
    }

    private void showComposerBlockingState(
            final int message, final int action, final OnClickListener clickListener) {
        this.binding.composerBlockingState.setVisibility(View.VISIBLE);
        this.binding.composerBlockingState.setOnClickListener(null);
        this.binding.composerBlockingMessage.setText(message);
        this.binding.composerBlockingMessage.setOnClickListener(null);
        this.binding.composerBlockingAction.setVisibility(
                clickListener == null ? View.GONE : View.VISIBLE);
        if (action != 0) {
            this.binding.composerBlockingAction.setText(action);
        }
        this.binding.composerBlockingAction.setOnClickListener(clickListener);
        this.binding.composerBlockingAction.setOnLongClickListener(null);
    }

    private void hideComposerBlockingState() {
        this.binding.composerBlockingState.setVisibility(View.GONE);
    }

    protected void sendMessageDelayed(Message message, long delay) {
        SendMessageWorker.Companion.scheduleMessageSending(
                activity, message, conversation.getReplyTo(), delay);
        messageSent();
    }

    protected void sendMessage(Message message) {
        final String draft = this.binding.textinput.getText().toString();
        if (activity.xmppConnectionService.sendMessageWithSecureTextPreparation(
                message,
                () ->
                        activity.runOnUiThread(
                                () -> {
                                    if (binding != null) {
                                        // Always enqueue behind the current send event. A secure
                                        // mutation failure can be reported synchronously before
                                        // messageSent() clears the composer; restoring immediately
                                        // would then be skipped and the draft would be lost.
                                        binding.textinput.post(
                                                () -> {
                                                    if (binding != null) {
                                                        restoreFailedSecureTextDraft(
                                                                message, draft);
                                                    }
                                                });
                                    }
                                }))) {
            // Protected text is committed/sent on a serial executor. At this point the new row may
            // not exist in the conversation yet, so scrolling now would animate toward the old
            // tail. Let the refresh that publishes the new message own the single bottom-follow.
            messageSent(true);
            return;
        }
        activity.xmppConnectionService.sendMessage(message);
        messageSent();
    }

    private void restoreFailedSecureTextDraft(final Message message, final String draft) {
        if (message.getConversation() != this.conversation
                || !TextUtils.isEmpty(this.binding.textinput.getText())) {
            return;
        }
        this.binding.textinput.setText(draft);
        storeNextMessage(draft);
        updateChatMsgHint();
        updateSendButton();
    }

    protected void sendOtrMessage(final Message message) {
        final ConversationsActivity activity = (ConversationsActivity) getActivity();
        final XmppConnectionService xmppService = activity.xmppConnectionService;
        message.setCounterpart(conversation.getNextCounterpart());
        xmppService.sendMessage(message);
        messageSent();
    }

    protected void startOtrChat() {
        final ConversationsActivity activity = (ConversationsActivity) getActivity();
        activity.selectPresence(
                conversation,
                () -> {
                    Conversation c =
                            activity.xmppConnectionService.findOrCreateConversation(
                                    conversation.getAccount(),
                                    conversation.getJid(),
                                    null,
                                    false,
                                    false,
                                    false,
                                    conversation.getNextCounterpart());
                    conversation.setNextCounterpart(null);
                    if (c != conversation) {
                        activity.switchToConversation(c);
                    }
                });
    }

    private void destroySecrectChat() {
        conversation.endOtrIfNeeded();
        activity.xmppConnectionService.destroyConversation(conversation);
    }

    public void appendText(String text, final boolean doNotAppend) {
        if (text == null) {
            return;
        }
        final Editable editable = this.binding.textinput.getText();
        String previous = editable == null ? "" : editable.toString();
        if (doNotAppend && !TextUtils.isEmpty(previous)) {
            Toast.makeText(getActivity(), R.string.already_drafting_message, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        if (UIHelper.isLastLineQuote(previous)) {
            text = '\n' + text;
        } else if (previous.length() != 0
                && !Character.isWhitespace(previous.charAt(previous.length() - 1))) {
            text = " " + text;
        }
        this.binding.textinput.append(text);
    }

    @Override
    public boolean onEnterPressed(final boolean isCtrlPressed) {
        if (isCtrlPressed || enterIsSend()) {
            sendMessage();
            return true;
        }
        return false;
    }

    private boolean enterIsSend() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return p.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send));
    }

    private boolean skipImageEditor() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return p.getBoolean(
                "skip_image_editor_screen",
                getResources().getBoolean(R.bool.skip_image_editor_screen));
    }

    private boolean skipVideoEditor() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return p.getBoolean(
                "skip_video_editor_screen",
                getResources().getBoolean(R.bool.skip_video_editor_screen));
    }

    public boolean onArrowUpCtrlPressed() {
        final Message lastEditableMessage =
                conversation == null ? null : conversation.getLastEditableMessage();
        if (lastEditableMessage != null) {
            correctMessage(lastEditableMessage);
            return true;
        } else {
            Toast.makeText(getActivity(), R.string.could_not_correct_message, Toast.LENGTH_LONG)
                    .show();
            return false;
        }
    }

    @Override
    public void onTypingStarted() {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE
                && conversation.setOutgoingChatState(ChatState.COMPOSING)) {
            service.sendChatState(conversation);
        }
        runOnUiThread(this::updateSendButton);
    }

    @Override
    public void onTypingStopped() {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(ChatState.PAUSED)) {
            service.sendChatState(conversation);
        }
    }

    @Override
    public void onTextDeleted() {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account.State status = conversation.getAccount().getStatus();
        if (status == Account.State.ONLINE
                && conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
            service.sendChatState(conversation);
        }
        if (storeNextMessage()) {
            runOnUiThread(
                    () -> {
                        if (activity == null) {
                            return;
                        }
                        activity.onConversationsListItemUpdated();
                    });
        }
        runOnUiThread(this::updateSendButton);
    }

    @Override
    public void onTextChanged() {
        refreshPendingMediaDraftFromComposer();
        if (conversation != null && conversation.getCorrectingMessage() != null) {
            runOnUiThread(this::updateSendButton);
        }
    }

    @Override
    public boolean onTabPressed(boolean repeated) {
        if (conversation == null || conversation.getMode() == Conversation.MODE_SINGLE) {
            return false;
        }
        if (repeated) {
            completionIndex++;
        } else {
            lastCompletionLength = 0;
            completionIndex = 0;
            final String content = this.binding.textinput.getText().toString();
            lastCompletionCursor = this.binding.textinput.getSelectionEnd();
            int start =
                    lastCompletionCursor > 0
                            ? content.lastIndexOf(" ", lastCompletionCursor - 1) + 1
                            : 0;
            firstWord = start == 0;
            incomplete = content.substring(start, lastCompletionCursor);
        }
        List<String> completions = new ArrayList<>();
        for (MucOptions.User user : conversation.getMucOptions().getUsers()) {
            String name = user.getName();
            if (name != null && name.startsWith(incomplete)) {
                completions.add(name + (firstWord ? ": " : " "));
            }
        }
        Collections.sort(completions);
        if (completions.size() > completionIndex) {
            String completion = completions.get(completionIndex).substring(incomplete.length());
            this.binding
                    .textinput
                    .getEditableText()
                    .delete(lastCompletionCursor, lastCompletionCursor + lastCompletionLength);
            this.binding.textinput.getEditableText().insert(lastCompletionCursor, completion);
            lastCompletionLength = completion.length();
        } else {
            completionIndex = -1;
            this.binding
                    .textinput
                    .getEditableText()
                    .delete(lastCompletionCursor, lastCompletionCursor + lastCompletionLength);
            lastCompletionLength = 0;
        }
        return true;
    }

    @Override
    public void onBackendConnected() {
        Log.d(Config.LOGTAG, "ConversationFragment.onBackendConnected()");
        String uuid = pendingConversationsUuid.pop();
        if (uuid != null) {
            if (!findAndReInitByUuidOrArchive(uuid)) {
                return;
            }
        } else {
            if (!activity.xmppConnectionService.isConversationStillOpen(conversation)) {
                clearPending();
                activity.onConversationArchived(conversation);
                return;
            }
        }
        ActivityResult activityResult = postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }
        clearPending();
    }

    private boolean findAndReInitByUuidOrArchive(@NonNull final String uuid) {
        Conversation conversation = activity.xmppConnectionService.findConversationByUuid(uuid);
        if (conversation == null) {
            clearPending();
            activity.onConversationArchived(null);
            return false;
        }
        reInit(conversation);
        ScrollState scrollState = pendingScrollState.pop();
        String lastMessageUuid = pendingLastMessageUuid.pop();
        List<Attachment> attachments = pendingMediaPreviews.pop();
        if (scrollState != null) {
            setScrollPosition(scrollState, lastMessageUuid);
        }
        if (attachments != null && attachments.size() > 0) {
            Log.d(Config.LOGTAG, "had attachments on restore");
            mediaPreviewAdapter.addMediaPreviews(attachments);
            toggleInputMethod();
        }
        return true;
    }

    private void clearPending() {
        if (postponedActivityResult.clear()) {
            Log.e(Config.LOGTAG, "cleared pending intent with unhandled result left");
            if (pendingTakePhotoUri.clear()) {
                Log.e(Config.LOGTAG, "cleared pending photo uri");
            }
        }
        if (pendingScrollState.clear()) {
            Log.e(Config.LOGTAG, "cleared scroll state");
        }
        if (pendingConversationsUuid.clear()) {
            Log.e(Config.LOGTAG, "cleared pending conversations uuid");
        }
        if (pendingMediaPreviews.clear()) {
            Log.e(Config.LOGTAG, "cleared pending media previews");
        }
    }

    public Conversation getConversation() {
        return conversation;
    }

    @Override
    public void onContactPictureLongClicked(View v, final Message message) {
        if (selectionActionMode != null) {
            toggleMessageSelection(message);
            return;
        }

        final String fingerprint;
        fingerprint =
                message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                        ? message.getFingerprint()
                        : null;
        final PopupMenu popupMenu = new PopupMenu(getActivity(), v);
        final Contact contact = message.getContact();
        if (message.getStatus() <= Message.STATUS_RECEIVED
                && (contact == null || !contact.isSelf())) {
            if (message.getConversation().getMode() == Conversation.MODE_MULTI) {
                final Jid cp = message.getCounterpart();
                if (cp == null || cp.isBareJid()) {
                    return;
                }
                final User user = conversation.getMucOptions().resolveUser(message);
                popupMenu.inflate(R.menu.muc_details_context);
                final Menu menu = popupMenu.getMenu();
                MucDetailsContextMenuHelper.configureMucDetailsContextMenu(
                        activity, menu, conversation, user);
                popupMenu.setOnMenuItemClickListener(
                        menuItem ->
                                MucDetailsContextMenuHelper.onContextItemSelected(
                                        menuItem, user, activity, fingerprint));
            } else {
                popupMenu.inflate(R.menu.one_on_one_context);
                popupMenu.setOnMenuItemClickListener(
                        item -> {
                            switch (item.getItemId()) {
                                case R.id.action_contact_details:
                                    activity.switchToContactDetails(
                                            message.getContact(), fingerprint);
                                    break;
                                case R.id.action_show_qr_code:
                                    activity.showQrCode(
                                            "xmpp:"
                                                    + message.getContact()
                                                            .getJid()
                                                            .asBareJid()
                                                            .toString());
                                    break;
                            }
                            return true;
                        });
            }
        } else {
            popupMenu.inflate(R.menu.account_context);
            final Menu menu = popupMenu.getMenu();
            menu.findItem(R.id.action_manage_accounts)
                    .setVisible(QuickConversationsService.isConversations());
            popupMenu.setOnMenuItemClickListener(
                    item -> {
                        final XmppActivity activity = this.activity;
                        if (activity == null) {
                            Log.e(Config.LOGTAG, "Unable to perform action. no context provided");
                            return true;
                        }
                        switch (item.getItemId()) {
                            case R.id.action_show_qr_code:
                                activity.showQrCode(conversation.getAccount().getShareableUri());
                                break;
                            case R.id.action_account_details:
                                activity.switchToAccount(
                                        message.getConversation().getAccount(), fingerprint);
                                break;
                            case R.id.action_manage_accounts:
                                AccountUtils.launchManageAccounts(activity);
                                break;
                        }
                        return true;
                    });
        }
        popupMenu.show();
    }

    @Override
    public void onContactPictureClicked(Message message) {
        if (selectionActionMode != null) {
            toggleMessageSelection(message);
            return;
        }

        String fingerprint;
        fingerprint =
                message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                        ? message.getFingerprint()
                        : null;
        final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
        if (received) {
            if (message.getConversation() instanceof Conversation
                    && message.getConversation().getMode() == Conversation.MODE_MULTI) {
                final Jid historicalUser = message.getCounterpart();
                if (historicalUser != null && !historicalUser.isBareJid()) {
                    final MucOptions mucOptions =
                            ((Conversation) message.getConversation()).getMucOptions();
                    final User resolvedUser = mucOptions.resolveUser(message);
                    final Jid currentUser =
                            resolvedUser != null && resolvedUser.getFullJid() != null
                                    ? resolvedUser.getFullJid()
                                    : historicalUser;
                    if (mucOptions.participating()
                            || ((Conversation) message.getConversation()).getNextCounterpart()
                                    != null) {
                        if (!mucOptions.isUserInRoom(currentUser)
                                && (message.getOccupantId() == null
                                        || mucOptions.findUserByOccupantId(message.getOccupantId())
                                                == null)
                                && mucOptions.findUserByRealJid(
                                                message.getTrueCounterpart() == null
                                                        ? null
                                                        : message.getTrueCounterpart().asBareJid())
                                        == null) {
                            Toast.makeText(
                                            getActivity(),
                                            activity.getString(
                                                    R.string.user_has_left_conference,
                                                    historicalUser.getResource()),
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                        highlightInConference(currentUser.getResource());
                    } else {
                        Toast.makeText(
                                        getActivity(),
                                        R.string.you_are_not_participating,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                }
                return;
            } else {
                if (!message.getContact().isSelf()) {
                    activity.switchToContactDetails(message.getContact(), fingerprint);
                    return;
                }
            }
        }
        activity.startActivity(
                ProfileNavigation.contextualProfileIntent(
                        activity, message.getConversation().getAccount().getUuid()));
    }

    private Activity requireActivity() {
        final Activity activity = getActivity();
        if (activity == null) {
            throw new IllegalStateException("Activity not attached");
        }
        return activity;
    }

    @ColorInt
    private int getOrCalculatePrimaryColor() {
        if (primaryColor != -1) return primaryColor;

        TypedValue typedValue = new TypedValue();
        getContext()
                .getTheme()
                .resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true);
        primaryColor = typedValue.data;

        return primaryColor;
    }

    private static int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    private static float dpToPx(float dp) {
        return dp * Resources.getSystem().getDisplayMetrics().density;
    }
}
