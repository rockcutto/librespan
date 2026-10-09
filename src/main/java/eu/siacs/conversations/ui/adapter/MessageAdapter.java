package eu.siacs.conversations.ui.adapter;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.SystemClock;
import android.net.Uri;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.format.DateUtils;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Space;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.customview.widget.ViewDragHelper;

import com.google.android.material.button.MaterialButton;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.MediaAttachmentRelations;
import eu.siacs.conversations.entities.MediaGalleryPresentation;
import eu.siacs.conversations.entities.media.MediaCaptionPresentation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Message.FileParams;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.storage.secure.SecureMessageMediaUiBridge;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.appearance.AppearanceSnapshotReader;
import eu.siacs.conversations.ui.appearance.AppearanceState;
import eu.siacs.conversations.ui.reactions.ReactionFlowLayout;
import eu.siacs.conversations.ui.appearance.TypographyPolicy;
import eu.siacs.conversations.ui.appearance.TypographyRole;
import eu.siacs.conversations.ui.reactions.ReactionRenderer;
import eu.siacs.conversations.ui.media.MediaMessageChromeRenderer;
import eu.siacs.conversations.ui.media.OutgoingMediaPreparingPresentation;
import eu.siacs.conversations.ui.media.MediaAlbumLayoutPlanner;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.MediaAlbumActivity;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.DraggableListView;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.service.AudioPlayer;
import eu.siacs.conversations.ui.text.DividerSpan;
import eu.siacs.conversations.ui.text.QuoteSpan;
import eu.siacs.conversations.ui.text.TypographyHelper;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.ui.widget.ClickableMovementMethod;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.MessageMarkup;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;

public class MessageAdapter extends ArrayAdapter<Message> implements DraggableListView.DraggableAdapter {

    private static final float MIN_PORTRAIT_MEDIA_PREVIEW_ASPECT = 9f / 16f;
    private static final long MESSAGE_ENTER_DURATION_MS = 180L;
    private static final long MESSAGE_ENTER_TTL_MS = 1200L;
    private static final float MESSAGE_ENTER_START_ALPHA = 0.84f;
    private static final int MESSAGE_ENTER_OFFSET_DP = 3;
    private static final PathInterpolator MESSAGE_ENTER_EASING =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final long PROTECTED_TEXT_REVEAL_FRAME_MS = 140L;
    private static final long PROTECTED_TEXT_REVEAL_FADE_MS = 150L;
    private static final int PROTECTED_TEXT_REVEAL_MAX_FRAMES = 24;
    private static final float PROTECTED_TEXT_REVEAL_ALPHA = 0.58f;
    private static final String[] PROTECTED_TEXT_REVEAL_FRAMES = {
            "\u00b7 \u25e6 \u2022 \u2219 \u00b7 \u2022 \u25e6 \u2219",
            "\u25e6 \u2022 \u2219 \u00b7 \u2022 \u25e6 \u2219 \u00b7",
            "\u2022 \u2219 \u00b7 \u25e6 \u2219 \u00b7 \u25e6 \u2022",
            "\u2219 \u00b7 \u25e6 \u2022 \u00b7 \u25e6 \u2022 \u2219"
    };

    public static final String DATE_SEPARATOR_BODY = "DATE_SEPARATOR";
    private static final int SENT = 0;
    private static final int RECEIVED = 1;
    private static final int STATUS = 2;
    private static final int DATE_SEPARATOR = 3;
    private static final int RTP_SESSION = 4;
    private static final int ALBUM_CONTINUATION = 5;
    private static final int CAPTION_CONTINUATION = 6;
    private static final int CALL_GROUP_CONTINUATION = 7;
    private static final long CALL_GROUP_MAX_GAP_MS = 3L * 60L * 60L * 1000L;
    private final XmppActivity activity;
    private final AudioPlayer audioPlayer;
    private List<String> highlightedTerm = null;
    private final DisplayMetrics metrics;
    private OnContactPictureClicked mOnContactPictureClickedListener;
    private OnContactPictureLongClicked mOnContactPictureLongClickedListener;
    private MessageEmptyPartClickListener messageEmptyPartClickListener;
    private MessageClickListener messageClickListener;
    private SelectionStatusProvider selectionStatusProvider;
    private MessageBoxSwipedListener messageBoxSwipedListener;
    private ReplyClickListener replyClickListener;
    private OnDateSeparatorClickListener onDateSeparatorClickListener;
    private BubbleDesign bubbleDesign = new BubbleDesign(false, TypographyPolicy.DEFAULT_MESSAGE_TEXT_SP);
    private boolean messageHyphenationEnabled = false;
    private final boolean mForceNames;
    private final MediaAlbumSource outgoingMediaAlbumSource = this::getOutgoingMediaAlbum;
    private MediaAlbumSource incomingMediaAlbumSource = MediaAlbumSource.empty();
    @Nullable
    private MediaGalleryPresentation incomingMediaGalleryPresentation;
    @Nullable
    private MediaCaptionPresentation mediaCaptionPresentation;
    @Nullable
    private OutgoingMediaPreparingPresentation outgoingMediaPreparingPresentation;
    private MediaAlbumSource mediaAlbumSource;
    private final Set<String> activeIncomingTransferMessages = new HashSet<>();
    private final Map<String, Long> pendingMessageEnterAnimations = new HashMap<>();
    @Nullable private Conversational enterAnimationConversation;
    @Nullable private String enterAnimationTailUuid;
    private boolean enterAnimationBaselineReady;

    @ColorInt
    private int primaryColor = -1;

    private ViewDragHelper dragHelper = null;
    private final ViewDragHelper.Callback dragCallback = new ViewDragHelper.Callback() {
        private int horizontalOffset = 0;
        private boolean swipedEnoughFirstTime = true;

        @Override
        public boolean tryCaptureView(@NonNull View child, int pointerId) {
            return child.getTag(R.id.TAG_DRAGGABLE) != null
                    && (selectionStatusProvider == null
                            || !selectionStatusProvider.isSomethingSelected());
        }

        @Override
        public void onViewCaptured(@NonNull View capturedChild, int activePointerId) {
            horizontalOffset = 0;
            swipedEnoughFirstTime = true;
            super.onViewCaptured(capturedChild, activePointerId);
        }

        @Override
        public void onViewReleased(@NonNull View releasedChild, float xvel, float yvel) {
            if (dragHelper != null) {
                dragHelper.settleCapturedViewAt(0, releasedChild.getTop());
                ViewCompat.postOnAnimation(releasedChild, new SettleRunnable(releasedChild));
                ViewHolder viewHolder = (ViewHolder) releasedChild.getTag();

                if (viewHolder != null && viewHolder.position >= 0 && viewHolder.position < getCount() && Math.abs(horizontalOffset) > releasedChild.getWidth()/6) {
                    Message m = getItem(viewHolder.position);
                    if (messageBoxSwipedListener != null) {
                        messageBoxSwipedListener.onMessageBoxReleasedAfterSwipe(m);
                    }
                }
            }
            horizontalOffset = 0;
            swipedEnoughFirstTime = true;
            super.onViewReleased(releasedChild, xvel, yvel);
        }

        @Override
        public int clampViewPositionHorizontal(@NonNull View child, int left, int dx) {
            horizontalOffset = left;

            if (Math.abs(horizontalOffset) > child.getWidth()/6 && swipedEnoughFirstTime) {
                swipedEnoughFirstTime = false;
                messageBoxSwipedListener.onMessageBoxSwipedEnough();
            }

            if (left < 0) {
                return Math.max(-child.getWidth()/4, left);
            } else {
                return Math.min(child.getWidth()/4, left);
            }
        }


        @Override
        public int clampViewPositionVertical(@NonNull View child, int top, int dy) {
            return child.getTop();
        }

        @Override
        public int getViewHorizontalDragRange(@NonNull View child) {
            return Math.max(Math.abs(child.getLeft()), 1);
        }

        private class SettleRunnable implements Runnable {
            private View view;

            public SettleRunnable(View view) {
                this.view = view;
            }

            @Override
            public void run() {
                if (dragHelper != null && dragHelper.continueSettling(true)) {
                    ViewCompat.postOnAnimation(view, this);
                }
            }
        }
    };

    public MessageAdapter(final XmppActivity activity, final List<Message> messages, final boolean forceNames) {
        super(activity, 0, messages);
        this.audioPlayer = new AudioPlayer(this);
        this.activity = activity;
        metrics = getContext().getResources().getDisplayMetrics();
        updatePreferences();
        this.mForceNames = forceNames;
        updateMediaAlbumSource();
    }

    public MessageAdapter(final XmppActivity activity, final List<Message> messages) {
        this(activity, messages, false);
    }

    @Override
    public void notifyDataSetChanged() {
        updateMessageEnterAnimationCandidates();
        super.notifyDataSetChanged();
    }

    private void updateMessageEnterAnimationCandidates() {
        final Message currentTail = findLastEnterAnimatableMessage();
        if (currentTail == null) {
            pendingMessageEnterAnimations.clear();
            enterAnimationConversation = null;
            enterAnimationTailUuid = null;
            enterAnimationBaselineReady = false;
            return;
        }

        final Conversational currentConversation = currentTail.getConversation();
        if (!enterAnimationBaselineReady || enterAnimationConversation != currentConversation) {
            pendingMessageEnterAnimations.clear();
            enterAnimationConversation = currentConversation;
            enterAnimationTailUuid = currentTail.getUuid();
            enterAnimationBaselineReady = true;
            return;
        }

        pruneExpiredMessageEnterAnimations();
        if (currentTail.getUuid().equals(enterAnimationTailUuid)) {
            return;
        }

        final int previousTailPosition = findMessagePositionByUuid(enterAnimationTailUuid);
        if (previousTailPosition < 0) {
            // Tail replacement/deletion (for example an outgoing media preparation placeholder)
            // is not a new-message event. Reset the baseline instead of replaying history.
            pendingMessageEnterAnimations.clear();
            enterAnimationTailUuid = currentTail.getUuid();
            return;
        }

        final long now = SystemClock.uptimeMillis();
        for (int i = previousTailPosition + 1; i < getCount(); i++) {
            final Message message = getItem(i);
            if (isEnterAnimatableMessage(message, i)
                    && message.getConversation() == currentConversation) {
                pendingMessageEnterAnimations.put(message.getUuid(), now);
            }
        }
        enterAnimationTailUuid = currentTail.getUuid();
    }

    @Nullable
    private Message findLastEnterAnimatableMessage() {
        for (int i = getCount() - 1; i >= 0; i--) {
            final Message message = getItem(i);
            if (isEnterAnimatableMessage(message, i)) {
                return message;
            }
        }
        return null;
    }

    private boolean isEnterAnimatableMessage(
            @Nullable final Message message, final int position) {
        if (message == null) {
            return false;
        }
        final int type = getItemViewType(position);
        return type == SENT || type == RECEIVED;
    }

    private int findMessagePositionByUuid(@Nullable final String uuid) {
        if (uuid == null) {
            return -1;
        }
        for (int i = getCount() - 1; i >= 0; i--) {
            final Message message = getItem(i);
            if (message != null && uuid.equals(message.getUuid())) {
                return i;
            }
        }
        return -1;
    }

    private void pruneExpiredMessageEnterAnimations() {
        final long now = SystemClock.uptimeMillis();
        pendingMessageEnterAnimations
                .entrySet()
                .removeIf(entry -> now - entry.getValue() > MESSAGE_ENTER_TTL_MS);
    }

    private boolean consumeMessageEnterAnimation(final Message message) {
        final Long queuedAt = pendingMessageEnterAnimations.remove(message.getUuid());
        return queuedAt != null
                && SystemClock.uptimeMillis() - queuedAt <= MESSAGE_ENTER_TTL_MS;
    }

    public void setIncomingMediaGalleryPresentation(
            @Nullable final MediaGalleryPresentation presentation) {
        incomingMediaGalleryPresentation = presentation;
        incomingMediaAlbumSource =
                presentation == null ? MediaAlbumSource.empty() : presentation::getAlbum;
        updateMediaAlbumSource();
    }

    public void setMediaCaptionPresentation(
            @Nullable final MediaCaptionPresentation presentation) {
        mediaCaptionPresentation = presentation;
    }

    public void setOutgoingMediaPreparingPresentation(
            @Nullable final OutgoingMediaPreparingPresentation presentation) {
        outgoingMediaPreparingPresentation = presentation;
    }

    @Nullable
    static Message getCaptionForPresentation(
            @Nullable final MediaGalleryPresentation presentation, @Nullable final Message message) {
        return presentation == null || message == null ? null : presentation.getCaption(message);
    }

    @Nullable
    private Message getMediaCaption(@Nullable final Message message) {
        return mediaCaptionPresentation == null || message == null
                ? null
                : mediaCaptionPresentation.getCaption(message);
    }

    static boolean shouldHideMediaCaptionChild(@Nullable final Message message) {
        return MediaAttachmentRelations.isCaptionChild(message);
    }

    private void updateMediaAlbumSource() {
        mediaAlbumSource =
                MediaAlbumSource.select(
                        MessageAdapter::isIncomingMessage,
                        incomingMediaAlbumSource,
                        outgoingMediaAlbumSource);
    }

    private static boolean isIncomingMessage(@Nullable final Message message) {
        return message != null && message.getStatus() <= Message.STATUS_RECEIVED;
    }

    private static boolean isIncomingTransferActive(final Message message) {
        final Transferable transferable = message == null ? null : message.getTransferable();
        if (!isIncomingMessage(message) || transferable == null) {
            return false;
        }
        return transferable.getStatus() == Transferable.STATUS_CHECKING
                || transferable.getStatus() == Transferable.STATUS_DOWNLOADING;
    }

    private static boolean hasIncomingTransferPresentation(final Message message) {
        final Transferable transferable = message.getTransferable();
        if (!isIncomingMessage(message) || transferable == null) {
            return false;
        }
        return switch (transferable.getStatus()) {
            case Transferable.STATUS_CHECKING,
                    Transferable.STATUS_DOWNLOADING,
                    Transferable.STATUS_FAILED,
                    Transferable.STATUS_CANCELLED -> true;
            default -> false;
        };
    }

    @Nullable
    @Override
    public ViewDragHelper.Callback getDragCallback() {
        return dragCallback;
    }

    @Override
    public void setViewDragHelper(@Nullable ViewDragHelper helper) {
        this.dragHelper = helper;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        boolean enabled;

        switch (getItemViewType(position)) {
            case SENT:
            case RECEIVED:
                enabled = true;
                break;
            default:
                enabled = false;
        }

        return enabled;
    }


    private static void resetMessageBubbleInteraction(final ViewHolder viewHolder) {
        viewHolder.message_box.setOnClickListener(null);
        viewHolder.message_box.setOnLongClickListener(null);
        viewHolder.messageBody.setOnClickListener(null);
        viewHolder.messageBody.setOnLongClickListener(null);
        viewHolder.messageBody.setOnTouchListener(null);
        viewHolder.messageBody.setMovementMethod(null);
        viewHolder.image.setOnLongClickListener(null);
        viewHolder.audioPlayer.setOnLongClickListener(null);
        viewHolder.download_button.setOnLongClickListener(null);

        if (viewHolder.fileCard != null) {
            viewHolder.fileCard.setOnLongClickListener(null);
        }

        if (viewHolder.mediaFooter != null) {
            viewHolder.mediaFooter.setOnClickListener(null);
            viewHolder.mediaFooter.setOnLongClickListener(null);
        }

        if (viewHolder.messageStatusRow != null) {
            viewHolder.messageStatusRow.setOnClickListener(null);
            viewHolder.messageStatusRow.setOnLongClickListener(null);
        }

        if (viewHolder.mediaCaption != null) {
            viewHolder.mediaCaption.setOnClickListener(null);
            viewHolder.mediaCaption.setOnLongClickListener(null);
            viewHolder.mediaCaption.setOnTouchListener(null);
        }

        if (viewHolder.mediaCaptionDivider != null) {
            viewHolder.mediaCaptionDivider.setOnLongClickListener(null);
        }
    }

    public void flagScreenOn() {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void flagScreenOff() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void setVolumeControl(final int stream) {
        activity.setVolumeControlStream(stream);
    }

    public void setOnContactPictureClicked(OnContactPictureClicked listener) {
        this.mOnContactPictureClickedListener = listener;
    }

    public Activity getActivity() {
        return activity;
    }

    public void setOnContactPictureLongClicked(
            OnContactPictureLongClicked listener) {
        this.mOnContactPictureLongClickedListener = listener;
    }

    public void setMessageEmptyPartLongClickListener(
            MessageEmptyPartClickListener listener) {
        this.messageEmptyPartClickListener = listener;
    }

    public void setMessageClickListener(
            MessageClickListener listener
    ) {
        this.messageClickListener = listener;
    }
    public void setSelectionStatusProvider(
            SelectionStatusProvider provider) {
        this.selectionStatusProvider = provider;
    }

    public void setOnMessageBoxSwiped(MessageBoxSwipedListener listener) {
        this.messageBoxSwipedListener = listener;
    }

    public void setReplyClickListener(ReplyClickListener listener) {
        this.replyClickListener = listener;
    }

    public void setOnDateSeparatorClickListener(OnDateSeparatorClickListener listener) {
        this.onDateSeparatorClickListener = listener;
    }

    @Override
    public int getViewTypeCount() {
        return 8;
    }

    private int getItemViewType(Message message) {
        if (message.getType() == Message.TYPE_STATUS) {
            if (DATE_SEPARATOR_BODY.equals(message.getBody())) {
                return DATE_SEPARATOR;
            } else {
                return STATUS;
            }
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            return RTP_SESSION;
        } else if (message.getStatus() <= Message.STATUS_RECEIVED) {
            return RECEIVED;
        } else {
            return SENT;
        }
    }

    @Override
    public int getItemViewType(int position) {
        final Message message = getItem(position);

        if (isMediaAlbumContinuation(position)) {
            return ALBUM_CONTINUATION;
        }

        if (isMediaCaptionContinuation(position)) {
            return CAPTION_CONTINUATION;
        }

        if (isCallGroupContinuation(position)) {
            return CALL_GROUP_CONTINUATION;
        }

        return this.getItemViewType(message);
    }

    private boolean isMediaAlbumContinuation(final int position) {
        return mediaAlbumSource.isContinuation(getItem(position));
    }

    private boolean isMediaCaptionContinuation(final int position) {
        return mediaCaptionPresentation != null
                && mediaCaptionPresentation.isCaptionChild(getItem(position));
    }

    private boolean isCallGroupContinuation(final int position) {
        final List<Message> run = getCallRun(position);
        return shouldCollapseCallRun(run)
                && !run.isEmpty()
                && getItem(position) != run.get(run.size() - 1);
    }

    private List<Message> getCallRun(final int position) {
        final Message anchor = getItem(position);
        if (!isRtpSessionMessage(anchor)) {
            return Collections.emptyList();
        }

        int start = position;
        while (start > 0) {
            final Message earlier = getItem(start - 1);
            final Message later = getItem(start);
            if (!canGroupCallPair(earlier, later)) {
                break;
            }
            start--;
        }

        int end = position;
        while (end + 1 < getCount()) {
            final Message earlier = getItem(end);
            final Message later = getItem(end + 1);
            if (!canGroupCallPair(earlier, later)) {
                break;
            }
            end++;
        }

        final List<Message> run = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            run.add(getItem(i));
        }
        return run;
    }

    private static boolean shouldCollapseCallRun(final List<Message> run) {
        if (run.size() < 2) {
            return false;
        }
        if (run.size() >= 3) {
            return true;
        }
        for (final Message message : run) {
            if (!RtpSessionStatus.of(message.getBody()).successful) {
                return true;
            }
        }
        // Two real conversations are useful history and should remain separate.
        return false;
    }

    private static boolean canGroupCallPair(
            @Nullable final Message earlier, @Nullable final Message later) {
        if (!isRtpSessionMessage(earlier)
                || !isRtpSessionMessage(later)
                || earlier.getConversation() != later.getConversation()) {
            return false;
        }
        final long earlierTime = earlier.getTimeSent();
        final long laterTime = later.getTimeSent();
        return earlierTime > 0
                && laterTime >= earlierTime
                && laterTime - earlierTime <= CALL_GROUP_MAX_GAP_MS
                && isSameLocalDay(earlierTime, laterTime);
    }

    private static boolean isRtpSessionMessage(@Nullable final Message message) {
        return message != null && message.getType() == Message.TYPE_RTP_SESSION;
    }

    private static boolean isSameLocalDay(final long first, final long second) {
        final Calendar a = Calendar.getInstance();
        a.setTimeInMillis(first);
        final Calendar b = Calendar.getInstance();
        b.setTimeInMillis(second);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA)
                && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private List<Message> getMediaAlbum(final int position) {
        return mediaAlbumSource.getAlbum(getItem(position));
    }

    /**
     * Reactions are presented once for the whole visual album, on its anchor row.
     *
     * <p>Album child messages still keep their own per-item actions (open, save, delete, info),
     * but reaction writes must target the same anchor that owns the single visible reaction row.
     * Otherwise a reaction sent from tile 2+ is stored on a hidden continuation message and looks
     * as if it disappeared.</p>
     */
    public Message getReactionTarget(@Nullable final Message message) {
        if (message == null) {
            return null;
        }
        final List<Message> album = mediaAlbumSource.getAlbum(message);
        return album.size() > 1 ? album.get(0) : message;
    }

    private List<Message> getOutgoingMediaAlbum(final Message message) {
        final String mediaGroupId = message == null ? null : message.getMediaGroupId();
        final List<Message> album = new ArrayList<>();
        if (message == null
                || message.getStatus() <= Message.STATUS_RECEIVED
                || Strings.isNullOrEmpty(mediaGroupId)) {
            if (message != null && message.isFileOrImage()) {
                album.add(message);
            }
            return album;
        }
        for (int i = 0; i < getCount(); ++i) {
            final Message candidate = getItem(i);
            if (candidate != null
                    && mediaGroupId.equals(candidate.getMediaGroupId())
                    && candidate.isFileOrImage()) {
                album.add(candidate);
            }
        }
        return album;
    }

    private static int getMediaVisualWidth(final ViewHolder viewHolder) {
        final View mediaVisual =
                viewHolder.mediaAlbum.getVisibility() == View.VISIBLE
                        ? viewHolder.mediaAlbum
                        : viewHolder.image;
        final ViewGroup.LayoutParams layoutParams = mediaVisual.getLayoutParams();
        return layoutParams.width > 0 ? layoutParams.width : mediaVisual.getMeasuredWidth();
    }

    static boolean shouldOverlayStatusOnMedia(
            final boolean hasMediaVisual, final boolean hasCaption, final boolean sent) {
        return hasMediaVisual && !(sent && hasCaption);
    }

    @Nullable
    static Integer aggregateAlbumUploadProgress(@Nullable final List<Message> album) {
        if (album == null || album.size() < 2) {
            return null;
        }
        boolean uploading = false;
        long weightedProgress = 0L;
        long totalWeight = 0L;
        for (final Message member : album) {
            if (member == null) {
                continue;
            }
            final Transferable transferable = member.getTransferable();
            final boolean memberUploading =
                    transferable != null
                            && transferable.getStatus() == Transferable.STATUS_UPLOADING;
            uploading |= memberUploading;

            final Long paramSize = member.getFileParams().size;
            final Long transferSize = transferable == null ? null : transferable.getFileSize();
            final long weight =
                    paramSize != null && paramSize > 0
                            ? paramSize
                            : transferSize != null && transferSize > 0 ? transferSize : 1L;

            final int progress;
            if (memberUploading) {
                progress = Math.max(0, Math.min(100, transferable.getProgress()));
            } else if (member.getStatus() == Message.STATUS_SEND
                    || member.getStatus() == Message.STATUS_SEND_RECEIVED
                    || member.getStatus() == Message.STATUS_SEND_DISPLAYED) {
                progress = 100;
            } else {
                progress = 0;
            }
            totalWeight += weight;
            weightedProgress += weight * progress;
        }
        if (!uploading || totalWeight <= 0L) {
            return null;
        }
        return (int) Math.max(0L, Math.min(100L, weightedProgress / totalWeight));
    }

    private void placeStatusRow(
            final ViewHolder viewHolder, final boolean overlayMedia, final boolean sent) {
        if (viewHolder.messageStatusRow == null
                || viewHolder.messageMetaRow == null
                || viewHolder.mediaVisualContainer == null) {
            return;
        }

        viewHolder.messageMetaRow.setVisibility(overlayMedia ? View.GONE : View.VISIBLE);
        final ViewGroup targetParent =
                overlayMedia ? viewHolder.mediaVisualContainer : viewHolder.messageMetaRow;
        if (viewHolder.messageStatusRow.getParent() instanceof ViewGroup currentParent
                && currentParent != targetParent) {
            currentParent.removeView(viewHolder.messageStatusRow);
        }

        if (viewHolder.messageStatusRow.getParent() != targetParent) {
            if (overlayMedia) {
                targetParent.addView(viewHolder.messageStatusRow);
            } else {
                final int index = sent ? targetParent.getChildCount() : 0;
                targetParent.addView(viewHolder.messageStatusRow, index);
            }
        }

        if (overlayMedia) {
            final FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | (sent ? Gravity.END : Gravity.START));
            final int verticalMargin = dpToPx(6);
            params.topMargin = verticalMargin;
            params.bottomMargin = verticalMargin;
            // Align the actual metadata text, not the overlay background edge.
            // media: 2dp outer margin + 4dp outer padding = 6dp text anchor
            // text:  6dp outer margin + 0dp outer padding = 6dp text anchor
            params.setMarginStart(dpToPx(sent ? 6 : 2));
            params.setMarginEnd(dpToPx(sent ? 2 : 6));
            viewHolder.messageStatusRow.setLayoutParams(params);
        } else {
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.setMarginStart(dpToPx(sent ? 4 : 6));
            params.setMarginEnd(dpToPx(sent ? 6 : 4));
            viewHolder.messageStatusRow.setLayoutParams(params);
        }
    }

    private void placeReactionRow(
            final ViewHolder viewHolder, final boolean overlayMedia, final boolean sent) {
        if (viewHolder.reactions == null
                || viewHolder.messageMetaRow == null
                || viewHolder.mediaVisualContainer == null) {
            return;
        }

        final ViewGroup targetParent =
                overlayMedia ? viewHolder.mediaVisualContainer : viewHolder.messageMetaRow;
        if (viewHolder.reactions.getParent() instanceof ViewGroup currentParent
                && currentParent != targetParent) {
            currentParent.removeView(viewHolder.reactions);
        }

        if (viewHolder.reactions.getParent() != targetParent) {
            if (overlayMedia) {
                targetParent.addView(viewHolder.reactions);
            } else {
                final int index = sent ? 0 : targetParent.getChildCount();
                targetParent.addView(viewHolder.reactions, index);
            }
        }

        if (overlayMedia) {
            final FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | (sent ? Gravity.START : Gravity.END));
            final int margin = dpToPx(6);
            params.setMargins(margin, margin, margin, margin);
            viewHolder.reactions.setLayoutParams(params);
        } else {
            final LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            viewHolder.reactions.setLayoutParams(params);
        }
    }

    private void resolveMediaOverlayCollision(
            final ViewHolder viewHolder, final boolean hasMediaVisual, final boolean sent) {
        if (!hasMediaVisual
                || viewHolder.mediaVisualContainer == null
                || viewHolder.reactions == null
                || viewHolder.messageStatusRow == null) {
            return;
        }
        viewHolder.mediaVisualContainer.post(
                () -> {
                    if (viewHolder.reactions.getVisibility() != View.VISIBLE
                            || viewHolder.messageStatusRow.getVisibility() != View.VISIBLE
                            || !(viewHolder.reactions.getLayoutParams()
                                    instanceof FrameLayout.LayoutParams)) {
                        return;
                    }
                    final int margin = dpToPx(6);
                    final int gap = dpToPx(6);
                    final int available =
                            Math.max(0, viewHolder.mediaVisualContainer.getWidth() - margin * 2);
                    final FrameLayout.LayoutParams params =
                            (FrameLayout.LayoutParams) viewHolder.reactions.getLayoutParams();
                    params.gravity = Gravity.BOTTOM | (sent ? Gravity.START : Gravity.END);
                    params.bottomMargin =
                            shouldStackMediaOverlays(
                                            available,
                                            viewHolder.reactions.getMeasuredWidth(),
                                            viewHolder.messageStatusRow.getMeasuredWidth(),
                                            gap)
                                    ? margin
                                            + viewHolder.messageStatusRow.getMeasuredHeight()
                                            + dpToPx(4)
                                    : margin;
                    params.leftMargin = margin;
                    params.rightMargin = margin;
                    viewHolder.reactions.setLayoutParams(params);
                });
    }

    static boolean shouldStackMediaOverlays(
            final int availableWidth,
            final int reactionWidth,
            final int statusWidth,
            final int gap) {
        return Math.max(0, reactionWidth)
                        + Math.max(0, statusWidth)
                        + Math.max(0, gap)
                > Math.max(0, availableWidth);
    }

    static int requiredMetadataWidth(
            final int statusWidthWithMargins,
            final int reactionWidthWithMargins) {
        return Math.max(0, statusWidthWithMargins) + Math.max(0, reactionWidthWithMargins);
    }

    private static int naturalWidthWithMargins(final View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return 0;
        }
        final int unspecified =
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(unspecified, unspecified);
        int width = view.getMeasuredWidth();
        final ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof ViewGroup.MarginLayoutParams margins) {
            width += Math.max(0, margins.getMarginStart()) + Math.max(0, margins.getMarginEnd());
        }
        return width;
    }

    private void ensureMetadataFitsBubble(
            final ViewHolder viewHolder,
            final boolean hasMediaVisual,
            final boolean standaloneEmoji) {
        if (viewHolder.message_box == null) {
            return;
        }
        viewHolder.message_box.setMinimumWidth(0);
        if (hasMediaVisual
                || standaloneEmoji
                || viewHolder.messageMetaRow == null
                || viewHolder.messageMetaRow.getVisibility() != View.VISIBLE) {
            return;
        }

        final int minimumWidth =
                requiredMetadataWidth(
                        naturalWidthWithMargins(viewHolder.messageStatusRow),
                        naturalWidthWithMargins(viewHolder.reactions));
        viewHolder.message_box.setMinimumWidth(minimumWidth);
    }

    private void styleStatusRow(
            final ViewHolder viewHolder, final Message message, final boolean compactOverlay) {
        if (viewHolder.messageStatusRow == null) {
            return;
        }

        if (!compactOverlay) {
            viewHolder.messageStatusRow.setBackground(null);
            viewHolder.messageStatusRow.setPadding(0, 0, 0, 0);
            final int status = message.getStatus();
            final boolean sent = status != Message.STATUS_RECEIVED;
            // Outgoing bubbles use the primary container, so the same alpha reads noticeably
            // louder than incoming metadata. Keep routine sent/delivered/read states quieter;
            // failures remain fully emphasized via the error color.
            viewHolder.messageStatusRow.setAlpha(
                    status == Message.STATUS_SEND_FAILED ? 1.0f : (sent ? 0.56f : 0.66f));
            return;
        }

        viewHolder.messageStatusRow.setBackgroundResource(
                R.drawable.background_message_status_overlay);
        final boolean sent = message.getStatus() != Message.STATUS_RECEIVED;
        final boolean mediaOverlay =
                viewHolder.messageStatusRow.getParent() == viewHolder.mediaVisualContainer;
        if (mediaOverlay) {
            // Keep message_time on the same 6dp vertical guide as normal text metadata.
            viewHolder.messageStatusRow.setPaddingRelative(
                    dpToPx(sent ? 7 : 4),
                    dpToPx(3),
                    dpToPx(sent ? 4 : 7),
                    dpToPx(3));
        } else {
            viewHolder.messageStatusRow.setPadding(
                    dpToPx(7), dpToPx(3), dpToPx(7), dpToPx(3));
        }
        viewHolder.messageStatusRow.setAlpha(1.0f);

        final boolean sendFailed = message.getStatus() == Message.STATUS_SEND_FAILED;
        final int overlayMetadataColor = calmMediaOverlayMetadataColor();
        if (!sendFailed) {
            viewHolder.time.setTextColor(overlayMetadataColor);
            if (viewHolder.indicatorReceived != null) {
                final int deliveryColor =
                        message.getStatus() == Message.STATUS_SEND_DISPLAYED
                                ? readStatusTonalColor(
                                        viewHolder.indicatorReceived, overlayMetadataColor)
                                : overlayMetadataColor;
                ImageViewCompat.setImageTintList(
                        viewHolder.indicatorReceived,
                        ColorStateList.valueOf(deliveryColor));
            }
        }
        if (viewHolder.indicator != null) {
            ImageViewCompat.setImageTintList(
                    viewHolder.indicator, ColorStateList.valueOf(overlayMetadataColor));
        }
        if (viewHolder.edit_indicator != null) {
            ImageViewCompat.setImageTintList(
                    viewHolder.edit_indicator, ColorStateList.valueOf(overlayMetadataColor));
        }
        if (viewHolder.encryption != null) {
            viewHolder.encryption.setTextColor(overlayMetadataColor);
        }
    }

    private void displayStatus(
            final ViewHolder viewHolder,
            final Message message,
            final int type,
            final BubbleColor bubbleColor,
            final List<Message> mediaAlbum,
            final boolean outgoingMediaPreparing) {
        final int status = message.getStatus();
        final Integer albumUploadProgress = aggregateAlbumUploadProgress(mediaAlbum);
        final boolean error;
        final Transferable transferable = message.getTransferable();
        final boolean sent = status != Message.STATUS_RECEIVED;
        final String fileSize;
        if (message.isFileOrImage()
                || transferable != null
                || MessageUtils.unInitiatedButKnownSize(message)) {
            final FileParams params = message.getFileParams();
            fileSize = params.size != null ? UIHelper.filesizeToString(params.size) : null;
            if (message.getStatus() == Message.STATUS_SEND_FAILED
                    || (transferable != null
                    && (transferable.getStatus() == Transferable.STATUS_FAILED
                    || transferable.getStatus()
                    == Transferable.STATUS_CANCELLED))) {
                error = true;
            } else {
                error = message.getStatus() == Message.STATUS_SEND_FAILED;
            }
        } else {
            fileSize = null;
            error = message.getStatus() == Message.STATUS_SEND_FAILED;
        }

        if (sent) {
            final @DrawableRes Integer receivedIndicator;
            if (outgoingMediaPreparing) {
                receivedIndicator = Integer.valueOf(R.drawable.ic_more_horiz_24dp);
            } else if (albumUploadProgress != null) {
                receivedIndicator = Integer.valueOf(R.drawable.ic_upload_24dp);
            } else {
                // Keep the nullable Integer intact. A ternary mixing primitive drawable ids with
                // this nullable result forces auto-unboxing and crashes when "no icon" is valid.
                receivedIndicator = getMessageStatusAsDrawable(message, status);
            }
            if (receivedIndicator == null) {
                viewHolder.indicatorReceived.setVisibility(View.INVISIBLE);
            } else {
                viewHolder.indicatorReceived.setImageResource(receivedIndicator);
                if (status == Message.STATUS_SEND_FAILED) {
                    setImageTintError(viewHolder.indicatorReceived);
                } else if (status == Message.STATUS_SEND_DISPLAYED) {
                    setReadStatusTint(viewHolder.indicatorReceived, bubbleColor);
                } else {
                    // Sent and delivered stay in the quiet metadata tone. Read keeps the bold
                    // double-check shape and receives only a small tonal shift, not a bright accent.
                    setImageTint(viewHolder.indicatorReceived, bubbleColor);
                }
                viewHolder.indicatorReceived.setVisibility(View.VISIBLE);
            }
        } else {
            viewHolder.indicatorReceived.setContentDescription(null);
            viewHolder.indicatorReceived.setVisibility(View.GONE);
        }
        final String additionalStatusInfo =
                outgoingMediaPreparing
                        ? getContext().getString(R.string.outgoing_media_preparing)
                        : albumUploadProgress != null
                                ? getContext()
                                        .getString(
                                                R.string.message_upload_progress,
                                                albumUploadProgress)
                                : getAdditionalStatusInfo(message, status);

        if (error && sent) {
            viewHolder
                    .time
                    .setTextColor(
                            MaterialColors.getColor(
                                    viewHolder.time, androidx.appcompat.R.attr.colorError));
        } else {
            setTextColor(viewHolder.time, bubbleColor);
        }

        if (viewHolder.encryption != null) {
            if (error && sent) {
                viewHolder
                        .encryption
                        .setTextColor(
                                MaterialColors.getColor(
                                        viewHolder.encryption, androidx.appcompat.R.attr.colorError));
            } else {
                setTextColor(viewHolder.encryption, bubbleColor);
            }
        }

        if (message.getEncryption() == Message.ENCRYPTION_NONE) {
            final Conversational conversational = message.getConversation();
            final boolean encryptionExpected =
                    conversational instanceof Conversation
                            && ((Conversation) conversational).getNextEncryption()
                                    != Message.ENCRYPTION_NONE;
            if (encryptionExpected) {
                // An unencrypted message is only noteworthy when this conversation normally
                // expects encryption. In an intentionally unencrypted chat/MUC the composer and
                // conversation chrome already communicate that state, so repeating an open lock
                // on every bubble is visual noise.
                viewHolder.indicator.setImageResource(R.drawable.ic_lock_open_outline_24dp);
                viewHolder
                        .indicator
                        .setContentDescription(
                                getContext().getString(R.string.encryption_disabled));
                setImageTint(viewHolder.indicator, bubbleColor);
                viewHolder.indicator.setVisibility(View.VISIBLE);
            } else {
                viewHolder.indicator.setContentDescription(null);
                viewHolder.indicator.setVisibility(View.GONE);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
            final FingerprintStatus fingerprintStatus =
                    message.getConversation()
                            .getAccount()
                            .getAxolotlService()
                            .getFingerprintTrust(message.getFingerprint());
            if (fingerprintStatus != null && fingerprintStatus.isTrusted()) {
                // Normal protected traffic is the expected state. Keep metadata quiet and reserve
                // the icon for states that need the user's attention.
                viewHolder.indicator.setContentDescription(null);
                viewHolder.indicator.setVisibility(View.GONE);
            } else {
                viewHolder.indicator.setImageResource(R.drawable.ic_warning_24dp);
                viewHolder
                        .indicator
                        .setContentDescription(getContext().getString(R.string.not_trusted));
                if ((fingerprintStatus != null && fingerprintStatus.isCompromised())
                        || (error && sent)) {
                    setImageTintError(viewHolder.indicator);
                } else {
                    setImageTint(viewHolder.indicator, bubbleColor);
                }
                viewHolder.indicator.setVisibility(View.VISIBLE);
            }
        } else {
            viewHolder.indicator.setImageResource(R.drawable.ic_lock_24dp);
            viewHolder
                    .indicator
                    .setContentDescription(getContext().getString(R.string.encryption_enabled));
            if (error && sent) {
                setImageTintError(viewHolder.indicator);
            } else {
                setImageTint(viewHolder.indicator, bubbleColor);
            }
            viewHolder.indicator.setVisibility(View.VISIBLE);
        }

        if (message.edited()) {
            viewHolder.edit_indicator.setVisibility(View.VISIBLE);
            if (error && sent) {
                setImageTintError(viewHolder.edit_indicator);
            } else {
                setImageTint(viewHolder.edit_indicator, bubbleColor);
            }
        } else {
            viewHolder.edit_indicator.setVisibility(View.GONE);
        }

        final String formattedTime = formatMessageTime(message.getTimeSent());
        final String bodyLanguage = message.getBodyLanguage();
        final ImmutableList.Builder<String> timeInfoBuilder = new ImmutableList.Builder<>();

        if (mForceNames) {
            final String displayName = UIHelper.getMessageDisplayName(message);
            if (displayName != null) {
                timeInfoBuilder.add(displayName);
            }
        }
        if (fileSize != null && !message.isFileOrImage()) {
            timeInfoBuilder.add(fileSize);
        }
        if (bodyLanguage != null) {
            timeInfoBuilder.add(bodyLanguage.toUpperCase(Locale.US));
        }
        if (additionalStatusInfo != null) {
            timeInfoBuilder.add(additionalStatusInfo);
        } else {
            timeInfoBuilder.add(formattedTime);
        }
        final var timeInfo = timeInfoBuilder.build();
        viewHolder.time.setText(Joiner.on(" · ").join(timeInfo));
    }

    private String formatMessageTime(final long timestamp) {
        return android.text.format.DateFormat.getTimeFormat(getContext())
                .format(new java.util.Date(timestamp));
    }

    public static String formatDateSeparatorLabel(
            final Context context, final long timestamp) {
        if (UIHelper.today(timestamp)) {
            return context.getString(R.string.today);
        }
        if (UIHelper.yesterday(timestamp)) {
            return context.getString(R.string.yesterday);
        }
        final java.util.Calendar messageDate = java.util.Calendar.getInstance();
        messageDate.setTimeInMillis(timestamp);
        final java.util.Calendar now = java.util.Calendar.getInstance();
        final int flags =
                DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_ABBREV_MONTH
                        | (messageDate.get(java.util.Calendar.YEAR)
                                        == now.get(java.util.Calendar.YEAR)
                                ? DateUtils.FORMAT_NO_YEAR
                                : DateUtils.FORMAT_SHOW_YEAR);
        return DateUtils.formatDateTime(context, timestamp, flags);
    }

    public static @DrawableRes Integer getMessageStatusAsDrawable(
            final Message message, final int status) {
        final var transferable = message.getTransferable();
        return switch (status) {
            case Message.STATUS_WAITING -> R.drawable.ic_more_horiz_24dp;
            case Message.STATUS_UNSEND -> transferable == null ? null : R.drawable.ic_upload_24dp;
            case Message.STATUS_SEND -> R.drawable.ic_done_24dp;
            case Message.STATUS_SEND_RECEIVED -> R.drawable.ic_done_all_24dp;
            case Message.STATUS_SEND_DISPLAYED -> R.drawable.ic_done_all_bold_24dp;
            case Message.STATUS_SEND_FAILED -> {
                final String errorMessage = message.getErrorMessage();
                if (Message.ERROR_MESSAGE_CANCELLED.equals(errorMessage)) {
                    yield R.drawable.ic_cancel_24dp;
                } else {
                    yield R.drawable.ic_error_24dp;
                }
            }
            case Message.STATUS_OFFERED -> R.drawable.ic_p2p_24dp;
            default -> null;
        };
    }

    @Nullable
    private String getAdditionalStatusInfo(final Message message, final int mergedStatus) {
        final var transferable = message.getTransferable();
        if (hasIncomingTransferPresentation(message)) {
            return switch (transferable.getStatus()) {
                case Transferable.STATUS_CHECKING,
                        Transferable.STATUS_DOWNLOADING -> null;
                case Transferable.STATUS_FAILED ->
                        getContext().getString(R.string.incoming_transfer_failed);
                case Transferable.STATUS_CANCELLED ->
                        getContext().getString(R.string.incoming_transfer_cancelled);
                default -> null;
            };
        }
        final String additionalStatusInfo;
        if (mergedStatus == Message.STATUS_SEND_FAILED) {
            additionalStatusInfo = getContext().getString(R.string.message_not_sent);
        } else if (mergedStatus == Message.STATUS_UNSEND) {
            if (transferable == null) {
                return null;
            }
            return getContext().getString(R.string.message_upload_progress, transferable.getProgress());
        } else {
            additionalStatusInfo = null;
        }
        return additionalStatusInfo;
    }

    private void displayInfoMessage(ViewHolder viewHolder, CharSequence text, BubbleColor bubbleColor) {
        hideFileCard(viewHolder);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        viewHolder.messageBody.setMinimumWidth(0);
        viewHolder.messageBody.setText(text);
        setTextSize(viewHolder.messageBody, this.bubbleDesign.messageTextSizeSp);
        viewHolder
                .messageBody
                .setTextColor(bubbleToOnSurfaceVariant(viewHolder.messageBody, bubbleColor));
        viewHolder.messageBody.setTextIsSelectable(false);
    }

    private void displayBusyInfoMessage(
            final ViewHolder viewHolder,
            final CharSequence text,
            final BubbleColor bubbleColor) {
        displayInfoMessage(viewHolder, text, bubbleColor);
        if (viewHolder.busyIndicator == null) {
            return;
        }
        viewHolder.busyIndicator.setIndeterminateTintList(
                ColorStateList.valueOf(
                        bubbleToOnSurfaceVariant(viewHolder.busyIndicator, bubbleColor)));
        viewHolder.busyIndicator.setVisibility(View.VISIBLE);
    }

    private void displayOutgoingMediaPreparingMessage(
            final ViewHolder viewHolder,
            @Nullable final String caption,
            final List<Attachment> attachments,
            final BubbleColor bubbleColor,
            final boolean compactOneToOneAlbum) {
        if (!displayOutgoingMediaPreparingVisual(
                viewHolder, attachments, compactOneToOneAlbum)) {
            displayBusyInfoMessage(
                    viewHolder, activity.getString(R.string.outgoing_media_preparing), bubbleColor);
        }
        if (viewHolder.mediaCaption == null || viewHolder.mediaCaptionDivider == null) {
            return;
        }
        if (caption == null || caption.trim().isEmpty()) {
            viewHolder.mediaCaptionDivider.setVisibility(View.GONE);
            viewHolder.mediaCaption.setText(null);
            viewHolder.mediaCaption.setVisibility(View.GONE);
            viewHolder.mediaCaption.setMovementMethod(null);
            return;
        }
        setTextSize(viewHolder.mediaCaption, this.bubbleDesign.messageTextSizeSp);
        setTextColor(viewHolder.mediaCaption, bubbleColor);
        final SpannableStringBuilder displayedCaption = new SpannableStringBuilder(caption);
        TypographyHelper.apply(displayedCaption);
        applySimpleListHangingIndent(viewHolder.mediaCaption, displayedCaption);
        viewHolder.mediaCaption.setText(displayedCaption);
        viewHolder.mediaCaption.setMovementMethod(null);
        viewHolder.mediaCaptionDivider.setVisibility(View.VISIBLE);
        viewHolder.mediaCaption.setVisibility(View.VISIBLE);
    }

    private boolean displayOutgoingMediaPreparingVisual(
            final ViewHolder viewHolder,
            final List<Attachment> attachments,
            final boolean compactOneToOneAlbum) {
        if (attachments == null || attachments.isEmpty()) {
            return false;
        }
        for (final Attachment attachment : attachments) {
            final String mime = attachment.getMime();
            if (attachment.getType() != Attachment.Type.IMAGE
                    && (mime == null || !mime.startsWith("video/"))) {
                return false;
            }
        }
        if (attachments.size() == 1) {
            displayOutgoingMediaPreparingSingle(viewHolder, attachments.get(0));
            return true;
        }
        return displayOutgoingMediaPreparingAlbum(
                viewHolder, attachments, compactOneToOneAlbum);
    }

    private void displayOutgoingMediaPreparingSingle(
            final ViewHolder viewHolder, final Attachment attachment) {
        hideFileCard(viewHolder);
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.mediaAlbum.removeAllViews();
        viewHolder.mediaAlbum.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.VISIBLE);
        // Match committed single-media behavior: show the complete image while preparing.
        viewHolder.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewHolder.image.setOnClickListener(null);
        viewHolder.image.setOnLongClickListener(null);
        viewHolder.image.setImageDrawable(null);
        MediaMessageChromeRenderer.applyConnectedMediaClip(viewHolder.image, 16);

        final int target =
                Math.max(
                        1,
                        Math.round(
                                activity.getResources()
                                        .getDimension(R.dimen.image_preview_width)));
        viewHolder.image.setLayoutParams(new LinearLayout.LayoutParams(target, target));
        final int previewSize =
                Math.max(
                        1,
                        Math.round(
                                activity.getResources()
                                        .getDimension(R.dimen.media_preview_size)));
        loadPreparingSingleAttachmentPreview(attachment, viewHolder.image, previewSize);
    }

    private void bindPreparingSinglePreview(
            final ImageView image, final android.graphics.Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        final int target =
                Math.max(
                        1,
                        Math.round(
                                activity.getResources()
                                        .getDimension(R.dimen.image_preview_width)));
        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();
        int scaledWidth = target;
        int scaledHeight = target;
        if (bitmapWidth > 0 && bitmapHeight > 0) {
            if (bitmapWidth <= bitmapHeight) {
                scaledHeight = target;
                scaledWidth =
                        (int) Math.round(bitmapWidth / ((double) bitmapHeight / target));
            } else {
                scaledWidth = target;
                scaledHeight =
                        (int) Math.round(bitmapHeight / ((double) bitmapWidth / target));
            }
            scaledWidth = clampPortraitPreviewWidth(scaledWidth, scaledHeight);
        }
        image.setLayoutParams(new LinearLayout.LayoutParams(scaledWidth, scaledHeight));
        image.setImageBitmap(bitmap);
    }

    private void loadPreparingSingleAttachmentPreview(
            final Attachment attachment, final ImageView image, final int previewSize) {
        final String token = attachment.getUuid().toString();
        image.setTag(R.id.TAG_MESSAGE_THUMBNAIL_ID, token);
        final android.graphics.Bitmap cached =
                activity.xmppConnectionService
                        .getFileBackend()
                        .getPreviewForUri(attachment, previewSize, true);
        if (cached != null) {
            bindPreparingSinglePreview(image, cached);
            return;
        }
        new AsyncTask<Void, Void, android.graphics.Bitmap>() {
            @Override
            protected android.graphics.Bitmap doInBackground(final Void... ignored) {
                return activity.xmppConnectionService
                        .getFileBackend()
                        .getPreviewForUri(attachment, previewSize, false);
            }

            @Override
            protected void onPostExecute(final android.graphics.Bitmap bitmap) {
                if (bitmap != null
                        && token.equals(image.getTag(R.id.TAG_MESSAGE_THUMBNAIL_ID))) {
                    bindPreparingSinglePreview(image, bitmap);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private boolean displayOutgoingMediaPreparingAlbum(
            final ViewHolder viewHolder,
            final List<Attachment> attachments,
            final boolean compactOneToOneAlbum) {
        if (attachments == null || attachments.size() < 2) {
            return false;
        }
        for (final Attachment attachment : attachments) {
            final String mime = attachment.getMime();
            if (attachment.getType() != Attachment.Type.IMAGE
                    && (mime == null || !mime.startsWith("video/"))) {
                return false;
            }
        }

        hideFileCard(viewHolder);
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);

        final GridLayout grid = viewHolder.mediaAlbum;
        grid.removeAllViews();
        grid.setVisibility(View.VISIBLE);
        final int gap = dpToPx(1);
        final int width = (int) activity.getResources().getDimension(R.dimen.image_preview_width);
        final int tileSize = (width - gap) / 2;
        if (compactOneToOneAlbum && attachments.size() <= 10) {
            displayCompactPreparingAlbum(
                    grid, attachments, width, tileSize, gap);
            return true;
        }
        final MediaAlbumLayoutPlanner.Plan plan =
                MediaAlbumLayoutPlanner.plan(attachments.size());
        final int rowCount = plan.getRowCount();
        final int height = rowCount * tileSize + (rowCount - 1) * gap;
        grid.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        grid.setRowCount(rowCount);
        final int seamColor =
                MaterialColors.getColor(
                        grid,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest,
                        Color.BLACK);
        grid.setBackgroundColor(seamColor);
        MediaMessageChromeRenderer.applyConnectedMediaClip(grid, 16);

        final int previewSize =
                Math.max(
                        1,
                        Math.round(
                                activity.getResources()
                                        .getDimension(R.dimen.media_preview_size)));
        for (final MediaAlbumLayoutPlanner.Tile tile : plan.getTiles()) {
            final int columnSpan = tile.getColumnSpan();
            final int tileWidth = columnSpan == 2 ? width : tileSize;
            addPreparingAlbumTile(
                    grid,
                    attachments.get(tile.getIndex()),
                    tile.getRow(),
                    tile.getColumn(),
                    columnSpan,
                    tileWidth,
                    tileSize,
                    gap,
                    previewSize);
        }
        return true;
    }

    private void addPreparingAlbumTile(
            final GridLayout grid,
            final Attachment attachment,
            final int row,
            final int column,
            final int columnSpan,
            final int width,
            final int height,
            final int gap,
            final int previewSize) {
        final FrameLayout tile = new FrameLayout(activity);
        final int surfaceColor =
                MaterialColors.getColor(
                        tile,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest,
                        Color.BLACK);
        tile.setBackgroundColor(surfaceColor);
        final ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(
                image,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadPreparingAttachmentPreview(attachment, image, previewSize);

        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams(
                        GridLayout.spec(row), GridLayout.spec(column, columnSpan));
        params.width = width;
        params.height = height;
        final int rightMargin = column + columnSpan < 2 ? gap : 0;
        final int bottomMargin = row < grid.getRowCount() - 1 ? gap : 0;
        params.setMargins(0, 0, rightMargin, bottomMargin);
        grid.addView(tile, params);
    }

    private void loadPreparingAttachmentPreview(
            final Attachment attachment, final ImageView image, final int previewSize) {
        final String token = attachment.getUuid().toString();
        image.setTag(R.id.TAG_MESSAGE_THUMBNAIL_ID, token);
        final var cached =
                activity.xmppConnectionService
                        .getFileBackend()
                        .getPreviewForUri(attachment, previewSize, true);
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }
        new AsyncTask<Void, Void, android.graphics.Bitmap>() {
            @Override
            protected android.graphics.Bitmap doInBackground(final Void... ignored) {
                return activity.xmppConnectionService
                        .getFileBackend()
                        .getPreviewForUri(attachment, previewSize, false);
            }

            @Override
            protected void onPostExecute(final android.graphics.Bitmap bitmap) {
                if (bitmap != null
                        && token.equals(image.getTag(R.id.TAG_MESSAGE_THUMBNAIL_ID))) {
                    image.setImageBitmap(bitmap);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void configureMessageTextLayout(
            final TextView textView, final CharSequence displayedText) {
        textView.setBreakStrategy(
                messageHyphenationEnabled
                        ? Layout.BREAK_STRATEGY_BALANCED
                        : Layout.BREAK_STRATEGY_SIMPLE);
        textView.setHyphenationFrequency(
                messageHyphenationEnabled
                        ? Layout.HYPHENATION_FREQUENCY_FULL
                        : Layout.HYPHENATION_FREQUENCY_NONE);
        textView.setTextLocale(
                messageHyphenationEnabled
                        ? TypographyHelper.inferTextLocale(displayedText)
                        : getContext()
                                .getResources()
                                .getConfiguration()
                                .getLocales()
                                .get(0));
    }

    private void displayEmojiMessage(
            final ViewHolder viewHolder,
            final Message message,
            final String body,
            final BubbleColor bubbleColor) {
        hideFileCard(viewHolder);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        viewHolder.messageBody.setMinimumWidth(0);
        configureMessageTextLayout(viewHolder.messageBody, body);
        setTextSize(viewHolder.messageBody, this.bubbleDesign.messageTextSizeSp);
        setTextColor(viewHolder.messageBody, bubbleColor);
        Spannable span = new SpannableString(body);
        float size = Emoticons.isEmoji(body) ? 3.0f : 2.0f;
        span.setSpan(new RelativeSizeSpan(size), 0, body.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        viewHolder.messageBody.setText(span);
        bindTextBubbleInteraction(viewHolder, message);
    }

    private static final String COMPACT_QUOTE_SEPARATOR = "\n\n";

    static QuoteBoundaryPlan trailingQuoteBoundaryPlan(
            final CharSequence body, final int quoteEnd) {
        if (body == null || quoteEnd < 0 || quoteEnd > body.length()) {
            return QuoteBoundaryPlan.none();
        }
        int existingEnd = quoteEnd;
        while (existingEnd < body.length() && body.charAt(existingEnd) == '\n') {
            existingEnd++;
        }
        if (existingEnd >= body.length()) {
            return QuoteBoundaryPlan.none();
        }
        return new QuoteBoundaryPlan(quoteEnd, existingEnd);
    }

    static final class QuoteBoundaryPlan {
        final int replaceStart;
        final int replaceEnd;

        private QuoteBoundaryPlan(final int replaceStart, final int replaceEnd) {
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
        }

        static QuoteBoundaryPlan none() {
            return new QuoteBoundaryPlan(-1, -1);
        }

        boolean applies() {
            return replaceStart >= 0;
        }
    }

    private void applyQuoteSpan(
            final TextView textView,
            SpannableStringBuilder body,
            int start,
            int end,
            BubbleColor bubbleColor,
            boolean highlightReply,
            Message message
    ) {
        if (start > 1 && !"\n\n".equals(body.subSequence(start - 2, start).toString())) {
            body.insert(start++, "\n");
            body.setSpan(
                    new DividerSpan(false),
                    start - ("\n".equals(body.subSequence(start - 2, start - 1).toString()) ? 2 : 1),
                    start,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            end++;
        }
        final QuoteBoundaryPlan trailingBoundary = trailingQuoteBoundaryPlan(body, end);
        if (trailingBoundary.applies()) {
            body.replace(
                    trailingBoundary.replaceStart,
                    trailingBoundary.replaceEnd,
                    COMPACT_QUOTE_SEPARATOR);
            body.setSpan(
                    new DividerSpan(false),
                    trailingBoundary.replaceStart,
                    trailingBoundary.replaceStart + COMPACT_QUOTE_SEPARATOR.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        final int color =
                ColorUtils.setAlphaComponent(
                        bubbleToOnSurfaceColor(textView, bubbleColor),
                        200);
        final int dashColor =
                highlightReply && start == 0
                        ? ColorUtils.setAlphaComponent(
                                ContextCompat.getColor(activity, R.color.blue_a100),
                                150)
                        : -1;

        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        body.setSpan(
                new QuoteSpan(color, dashColor, metrics),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (highlightReply && start == 0) {
            body.setSpan(new ReplyClickableSpan(new WeakReference(replyClickListener), new WeakReference(message)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public void handleTextQuotes(
            final TextView textView,
            SpannableStringBuilder body,
            BubbleColor bubbleColor,
            boolean highlightReply,
            Message message
    ) {
        boolean startsWithQuote = false;
        int quoteDepth = 0;
        while (!message.isStylingDisabled()
                && QuoteHelper.bodyContainsQuoteStart(body)
                && quoteDepth <= Config.QUOTE_MAX_DEPTH) {
            if (quoteDepth == 0) {
                quoteDepth = 1;
            }
            char previous = '\n';
            int lineStart = -1;
            int lineTextStart = -1;
            int quoteStart = -1;
            for (int i = 0; i <= body.length(); i++) {
                char current = body.length() > i ? body.charAt(i) : '\n';
                if (lineStart == -1) {
                    if (previous == '\n') {
                        if (i < body.length() && QuoteHelper.isPositionQuoteStart(body, i)) {
                            lineStart = i;
                            if (quoteStart == -1) quoteStart = i;
                            if (i == 0) startsWithQuote = true;
                        } else if (quoteStart >= 0) {
                            applyQuoteSpan(textView, body, quoteStart, i - 1, bubbleColor, quoteDepth == 1 && highlightReply, message);
                            quoteStart = -1;
                        }
                    }
                } else {
                    if (current != ' ' && lineTextStart == -1) {
                        lineTextStart = i;
                    }
                    if (current == '\n') {
                        body.delete(lineStart, lineTextStart);
                        i -= lineTextStart - lineStart;
                        if (i == lineStart) {
                            body.insert(i++, " ");
                        }
                        lineStart = -1;
                        lineTextStart = -1;
                    }
                }
                previous = current;
            }
            if (quoteStart >= 0) {
                applyQuoteSpan(textView, body, quoteStart, body.length(), bubbleColor, quoteDepth == 1 && highlightReply, message);
            }
            quoteDepth++;
        }
        if (quoteDepth == 0 && highlightReply) {
            int start = -1;
            int end = -1;
            for (Element el : message.getPayloads()) {
                if ("fallback".equals(el.getName()) && "urn:xmpp:fallback:0".equals(el.getNamespace()) && "urn:xmpp:reply:0".equals(el.getAttribute("for"))) {
                    Element bodyEl = el.findChild("body", "urn:xmpp:fallback:0");
                    if (bodyEl != null) {
                        String startString = bodyEl.getAttribute("start");
                        String endString = bodyEl.getAttribute("end");
                        try {
                            start = Integer.parseInt(startString);
                            end = Integer.parseInt(endString);
                        } catch (final NumberFormatException ignored) {
                        }
                    }
                    break;
                }
            }
            if (start == -1 && end == -1) {
                final String quirk = getContext().getString(R.string.reply_reference) + "\n";
                body.insert(0, quirk);
                start = 0;
                end = quirk.length();
            } else if (start == -1) {
                start = 0;
            } else if (end == -1 || end >= body.length()) {
                end = body.length();
            }

            applyQuoteSpan(textView, body, start, end, bubbleColor, true, message);
        }
    }

    private void displayTextMessage(
            final ViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor,
            int type,
            final boolean revealResolvedProtectedText) {
        hideFileCard(viewHolder);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        viewHolder.messageBody.setMinimumWidth(0);

        setTextSize(viewHolder.messageBody, this.bubbleDesign.messageTextSizeSp);
        setTextColor(viewHolder.messageBody, bubbleColor);

        if (message.getSecureMessagePayloadMode()
                        == eu.siacs.conversations.storage.secure.SecureMessagePayloadMode.PROTECTED
                && !message.hasVerifiedProtectedBody()) {
            startProtectedTextReveal(viewHolder, message);
            bindTextBubbleInteraction(viewHolder, message);
            return;
        }

        if (message.getBody() != null) {
            final String nick = UIHelper.getMessageDisplayName(message);

            Message replyMessage = message.getReplyMessage();
            final boolean showReplyAsSeparatePart = replyMessage != null;

            SpannableStringBuilder body = message.getBodyForDisplaying(showReplyAsSeparatePart);

            // XEP-0394 offsets refer to the canonical clean body. Apply semantic formatting
            // before display-only typography mutates characters (NBSP, typographic dashes, etc.).
            // Runtime typography is length-preserving, so the spans remain aligned afterwards.
            final Element messageMarkup = message.getMessageMarkup();
            final boolean messageMarkupApplied =
                    MessageMarkup.canApplyToDisplayedBody(message, body)
                            && StylingHelper.formatMarkup(body, messageMarkup);

            boolean hasMeCommand = message.hasMeCommand();
            if (hasMeCommand) {
                body = body.replace(0, Message.ME_COMMAND.length(), nick + " ");
            }

            if (body.length() > Config.MAX_DISPLAY_MESSAGE_CHARS) {
                body = new SpannableStringBuilder(body, 0, Config.MAX_DISPLAY_MESSAGE_CHARS);
                body.append("\u2026");
            }

            TypographyHelper.apply(body);
            configureMessageTextLayout(viewHolder.messageBody, body);
            applySimpleListHangingIndent(viewHolder.messageBody, body);

            Message.MergeSeparator[] mergeSeparators = body.getSpans(0, body.length(), Message.MergeSeparator.class);
            for (Message.MergeSeparator mergeSeparator : mergeSeparators) {
                int start = body.getSpanStart(mergeSeparator);
                int end = body.getSpanEnd(mergeSeparator);
                body.setSpan(new DividerSpan(true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            for (final android.text.style.QuoteSpan quote : body.getSpans(0, body.length(), android.text.style.QuoteSpan.class)) {
                int start = body.getSpanStart(quote);
                int end = body.getSpanEnd(quote);
                body.removeSpan(quote);
                applyQuoteSpan(viewHolder.messageBody, body, start, end, bubbleColor, message.getReplyMessage() != null, message);
            }

            maybeShowReply(replyMessage, showReplyAsSeparatePart, viewHolder, message, bubbleColor);
            handleTextQuotes(viewHolder.messageBody, body, bubbleColor, message.getReplyMessage() != null && !showReplyAsSeparatePart, message);

            if (!message.isPrivateMessage()) {
                if (hasMeCommand) {
                    body.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), 0, nick.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (message.getConversation().getMode() == Conversation.MODE_MULTI
                    && message.getConversation() instanceof Conversation) {
                applyMucMentionEmphasis(
                        body,
                        (Conversation) message.getConversation());
            }
            Matcher matcher = Emoticons.getEmojiPattern(body).matcher(body);
            while (matcher.find()) {
                if (matcher.start() < matcher.end()) {
                    body.setSpan(new RelativeSizeSpan(1.2f), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            if (!messageMarkupApplied
                    && messageMarkup == null
                    && !message.isStylingDisabled()) {
                StylingHelper.format(body, viewHolder.messageBody.getCurrentTextColor());
            }
            if (highlightedTerm != null) {
                StylingHelper.highlight(viewHolder.messageBody, body, highlightedTerm, StylingHelper.isDarkText(viewHolder.messageBody));
            }
            MyLinkify.addLinks(body, true);
            viewHolder.messageBody.setAutoLinkMask(0);
            viewHolder.messageBody.animate().cancel();
            viewHolder.messageBody.setAlpha(revealResolvedProtectedText ? 0.45f : 1f);
            viewHolder.messageBody.setText(body);
            ensureWholeWordsFitIndentedText(viewHolder.messageBody, body);
            viewHolder.messageBody.setMovementMethod(ClickableMovementMethod.getInstance());
            bindTextBubbleInteraction(viewHolder, message);
            if (revealResolvedProtectedText) {
                viewHolder.messageBody
                        .animate()
                        .alpha(1f)
                        .setDuration(PROTECTED_TEXT_REVEAL_FADE_MS)
                        .start();
            }
        } else {
            viewHolder.messageBody.animate().cancel();
            viewHolder.messageBody.setAlpha(1f);
            viewHolder.messageBody.setText("");
            viewHolder.messageBody.setTextIsSelectable(false);
        }
    }

    private void applyMucMentionEmphasis(
            final SpannableStringBuilder body,
            final Conversation conversation) {
        final Set<String> participantNames = new HashSet<>();
        final String ownNick = conversation.getMucOptions().getActualNick();
        if (!Strings.isNullOrEmpty(ownNick)) {
            participantNames.add(ownNick);
        }
        for (final MucOptions.User user : conversation.getMucOptions().getUsers()) {
            final String name = user.getName();
            if (!Strings.isNullOrEmpty(name)) {
                participantNames.add(name);
            }
        }

        final String plainBody = body.toString();
        for (final String name : participantNames) {
            if (!plainBody.contains(name)) {
                continue;
            }
            final Pattern pattern = NotificationService.generateNickHighlightPattern(name);
            final Matcher matcher = pattern.matcher(plainBody);
            while (matcher.find()) {
                body.setSpan(
                        new StyleSpan(Typeface.BOLD),
                        matcher.start(),
                        matcher.end(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /**
     * Keeps a protected-text miss visually intentional without driving adapter refreshes.
     *
     * One runnable lives on the bound holder, mutates only that TextView, and is cancelled on
     * rebind. The real text is never delayed: once rehydrate publishes a verified body, the next
     * normal adapter bind cancels this placeholder and crossfades immediately to the body.
     */
    private void startProtectedTextReveal(final ViewHolder viewHolder, final Message message) {
        if (viewHolder.messageBody == null) {
            return;
        }
        final String messageUuid = message.getUuid();
        if (messageUuid.equals(viewHolder.protectedTextRevealMessageUuid)
                && viewHolder.protectedTextRevealRunnable != null) {
            return;
        }

        stopProtectedTextReveal(viewHolder);
        viewHolder.protectedTextRevealMessageUuid = messageUuid;
        viewHolder.protectedTextRevealFrame = 0;
        viewHolder.messageBody.animate().cancel();
        viewHolder.messageBody.setAlpha(PROTECTED_TEXT_REVEAL_ALPHA);
        viewHolder.messageBody.setTextIsSelectable(false);
        viewHolder.messageBody.setMovementMethod(null);
        viewHolder.messageBody.setText(PROTECTED_TEXT_REVEAL_FRAMES[0]);
        viewHolder.protectedTextRevealFrame = 1;

        final Runnable revealRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        if (viewHolder.messageBody == null
                                || viewHolder.protectedTextRevealRunnable != this
                                || !messageUuid.equals(viewHolder.protectedTextRevealMessageUuid)) {
                            return;
                        }
                        viewHolder.messageBody.setText(
                                PROTECTED_TEXT_REVEAL_FRAMES[
                                        viewHolder.protectedTextRevealFrame
                                                % PROTECTED_TEXT_REVEAL_FRAMES.length]);
                        viewHolder.protectedTextRevealFrame++;
                        if (viewHolder.protectedTextRevealFrame
                                < PROTECTED_TEXT_REVEAL_MAX_FRAMES) {
                            viewHolder.messageBody.postDelayed(
                                    this, PROTECTED_TEXT_REVEAL_FRAME_MS);
                        }
                    }
                };
        viewHolder.protectedTextRevealRunnable = revealRunnable;
        viewHolder.messageBody.postDelayed(
                revealRunnable, PROTECTED_TEXT_REVEAL_FRAME_MS);
    }

    /**
     * Cancels a holder-local reveal animation. Returns true only when the cancelled placeholder
     * belonged to the same message and that message now has verified protected text.
     */
    private boolean prepareProtectedTextRevealForBind(
            final ViewHolder viewHolder, final Message message) {
        if (viewHolder.messageBody == null) {
            return false;
        }
        final String previousUuid = viewHolder.protectedTextRevealMessageUuid;
        final boolean sameMessage =
                previousUuid != null && previousUuid.equals(message.getUuid());
        final boolean resolvedSameMessage =
                sameMessage
                        && message.getSecureMessagePayloadMode()
                                == eu.siacs.conversations.storage.secure.SecureMessagePayloadMode.PROTECTED
                        && message.hasVerifiedProtectedBody();

        if (!sameMessage
                || resolvedSameMessage
                || message.getSecureMessagePayloadMode()
                        != eu.siacs.conversations.storage.secure.SecureMessagePayloadMode.PROTECTED) {
            stopProtectedTextReveal(viewHolder);
        }
        return resolvedSameMessage;
    }

    private void stopProtectedTextReveal(final ViewHolder viewHolder) {
        if (viewHolder.messageBody != null && viewHolder.protectedTextRevealRunnable != null) {
            viewHolder.messageBody.removeCallbacks(viewHolder.protectedTextRevealRunnable);
            viewHolder.messageBody.animate().cancel();
            viewHolder.messageBody.setAlpha(1f);
        }
        viewHolder.protectedTextRevealRunnable = null;
        viewHolder.protectedTextRevealMessageUuid = null;
        viewHolder.protectedTextRevealFrame = 0;
    }

    private static void applySimpleListHangingIndent(
            final TextView textView, final SpannableStringBuilder body) {
        if (body == null || body.length() < 3) {
            return;
        }
        final int hangingIndent =
                Math.max(
                        1,
                        Math.round(
                                TypedValue.applyDimension(
                                        TypedValue.COMPLEX_UNIT_DIP,
                                        14f,
                                        textView.getResources().getDisplayMetrics())));

        boolean inCode = false;
        boolean lineStartedInCode = false;
        int lineStart = 0;
        for (int index = 0; index <= body.length(); index++) {
            final boolean atEnd = index == body.length();
            final char current = atEnd ? '\n' : body.charAt(index);
            if (!atEnd && current == '`') {
                inCode = !inCode;
            }
            if (current != '\n') {
                continue;
            }

            if (!lineStartedInCode && isSimpleListMarker(body, lineStart, index)) {
                body.setSpan(
                        new LeadingMarginSpan.Standard(0, hangingIndent),
                        lineStart,
                        Math.max(lineStart + 1, index),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            lineStart = index + 1;
            lineStartedInCode = inCode;
        }
    }

    private static boolean isSimpleListMarker(
            final CharSequence text, final int start, final int end) {
        if (start < 0 || start + 2 > end || start + 1 >= text.length()) {
            return false;
        }
        final char marker = text.charAt(start);
        return (marker == '-' || marker == '*' || marker == '•')
                && text.charAt(start + 1) == ' ';
    }

    private static void ensureWholeWordsFitIndentedText(
            final TextView textView, final CharSequence text) {
        if (!(text instanceof Spanned spanned) || text.length() == 0) {
            return;
        }

        final QuoteSpan[] quoteMargins =
                spanned.getSpans(0, spanned.length(), QuoteSpan.class);
        if (quoteMargins.length == 0) {
            return;
        }

        float requiredContentWidth = 0f;
        int tokenStart = 0;
        while (tokenStart < text.length()) {
            while (tokenStart < text.length()
                    && Character.isWhitespace(text.charAt(tokenStart))) {
                tokenStart++;
            }
            if (tokenStart >= text.length()) {
                break;
            }

            int tokenEnd = tokenStart + 1;
            while (tokenEnd < text.length()
                    && !Character.isWhitespace(text.charAt(tokenEnd))) {
                tokenEnd++;
            }

            float tokenWidth =
                    Layout.getDesiredWidth(text, tokenStart, tokenEnd, textView.getPaint());
            int leadingMargin = 0;
            final LeadingMarginSpan[] tokenMargins =
                    spanned.getSpans(tokenStart, tokenEnd, LeadingMarginSpan.class);
            for (final LeadingMarginSpan margin : tokenMargins) {
                leadingMargin += Math.max(0, margin.getLeadingMargin(true));
            }
            requiredContentWidth =
                    Math.max(requiredContentWidth, tokenWidth + leadingMargin);
            tokenStart = tokenEnd;
        }

        if (requiredContentWidth <= 0f) {
            return;
        }

        final int requiredWidth =
                (int) Math.ceil(requiredContentWidth)
                        + textView.getCompoundPaddingLeft()
                        + textView.getCompoundPaddingRight();
        textView.setMinimumWidth(requiredWidth);
    }

    private void bindTextBubbleInteraction(final ViewHolder viewHolder, final Message message) {
        viewHolder.message_box.setOnClickListener(
                v -> {
                    if (messageClickListener != null) {
                        messageClickListener.onMessageClick(message);
                    }
                });
        viewHolder.message_box.setOnLongClickListener(
                v -> {
                    if (messageEmptyPartClickListener != null) {
                        messageEmptyPartClickListener.onMessageEmptyPartLongClick(message);
                    }
                    return messageEmptyPartClickListener != null;
                });
        viewHolder.messageBody.setOnLongClickListener(
                v -> viewHolder.message_box.performLongClick());
        viewHolder.messageBody.setOnTouchListener(
                new PlainTextBubbleTouchBridge(viewHolder.message_box, selectionStatusProvider));
    }

    private View.OnLongClickListener messageActionLongClickListener(final Message message) {
        return v -> {
            if (messageClickListener == null) {
                return false;
            }
            messageClickListener.onMessageClick(message);
            return true;
        };
    }

    private static final class PlainTextBubbleTouchBridge implements View.OnTouchListener {
        private final View messageBox;
        private final int touchSlop;
        private final SelectionStatusProvider selectionStatusProvider;
        private URLSpan pressedUrl;
        private float downX;
        private float downY;
        private boolean moved;

        private PlainTextBubbleTouchBridge(
                final View messageBox,
                final SelectionStatusProvider selectionStatusProvider) {
            this.messageBox = messageBox;
            this.selectionStatusProvider = selectionStatusProvider;
            this.touchSlop = ViewConfiguration.get(messageBox.getContext()).getScaledTouchSlop();
        }

        private static URLSpan touchedUrl(final View view, final MotionEvent event) {
            if (!(view instanceof TextView textView)
                    || !(textView.getText() instanceof Spanned text)) {
                return null;
            }

            final Layout layout = textView.getLayout();
            if (layout == null) {
                return null;
            }

            final float x = event.getX() - textView.getTotalPaddingLeft()
                    + textView.getScrollX();
            final float y = event.getY() - textView.getTotalPaddingTop()
                    + textView.getScrollY();

            if (y < 0 || y >= layout.getHeight()) {
                return null;
            }

            final int line = layout.getLineForVertical((int) y);
            final float left = layout.getLineLeft(line);
            final float right = layout.getLineRight(line);
            if (x < Math.min(left, right) || x > Math.max(left, right)) {
                return null;
            }

            final int offset = layout.getOffsetForHorizontal(line, x);
            for (final URLSpan url : text.getSpans(offset, offset, URLSpan.class)) {
                if (offset >= text.getSpanStart(url)
                        && offset < text.getSpanEnd(url)) {
                    return url;
                }
            }
            return null;
        }

        @Override
        public boolean onTouch(final View view, final MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    pressedUrl = touchedUrl(view, event);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop) {
                        moved = true;
                    }
                    return false;
                case MotionEvent.ACTION_POINTER_DOWN:
                    moved = true;
                    return false;
                case MotionEvent.ACTION_UP:
                    final boolean wasMoved = moved;
                    final boolean wasLongPress =
                            event.getEventTime() - event.getDownTime()
                                    >= ViewConfiguration.getLongPressTimeout();
                    final boolean shouldHandleTap = !wasMoved && !wasLongPress;
                    final URLSpan downUrl = pressedUrl;

                    moved = false;
                    pressedUrl = null;

                    if (shouldHandleTap) {
                        final boolean selecting = selectionStatusProvider != null
                                && selectionStatusProvider.isSomethingSelected();

                        // Open only when down and up hit the same URL.
                        // Selection mode always owns the tap instead.
                        if (!selecting && downUrl != null
                                && downUrl == touchedUrl(view, event)) {
                            downUrl.onClick(view);
                            return true;
                        }

                        // Non-link text keeps its existing message actions.
                        messageBox.performClick();
                    }

                    return shouldHandleTap || wasMoved || wasLongPress;
                case MotionEvent.ACTION_CANCEL:
                    moved = false;
                    pressedUrl = null;
                    return false;
                default:
                    return false;
            }
        }
    }

    private void maybeShowReply(Message replyMessage, boolean showAsSeparatePart, ViewHolder viewHolder, Message message, BubbleColor bubbleColor) {
        TextView text = viewHolder.nonTextReplyContent.findViewById(R.id.reply_body);
        TextView author = viewHolder.nonTextReplyContent.findViewById(R.id.context_preview_author);
        ImageView contextPreviewImage = viewHolder.nonTextReplyContent.findViewById(R.id.context_preview_image);
        ImageView contextPreviewDoc = viewHolder.nonTextReplyContent.findViewById(R.id.context_preview_doc);
        ImageView contextPreviewAudio = viewHolder.nonTextReplyContent.findViewById(R.id.context_preview_audio);
        View iconsContainer = viewHolder.nonTextReplyContent.findViewById(R.id.icons_container);

        if (showAsSeparatePart && replyMessage != null) {
            viewHolder.nonTextReplyContent.setVisibility(View.VISIBLE);
            WeakReference<ReplyClickListener> listener = new WeakReference<>(replyClickListener);
            viewHolder.nonTextReplyContent.setOnClickListener(v -> {
                ReplyClickListener l = listener.get();
                if (l != null) {
                    l.onReplyClick(message);
                }
            });

            text.setVisibility(View.VISIBLE);

            setTextSize(text, this.bubbleDesign.messageTextSizeSp);
            if (viewHolder.senderName != null) {
                author.setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        viewHolder.senderName.getTextSize());
                author.setTypeface(viewHolder.senderName.getTypeface());
            }

            text.setTextColor(
                    ColorUtils.setAlphaComponent(
                            bubbleToOnSurfaceColor(text, bubbleColor),
                            190
                    )
            );

            author.setTextColor(bubbleToOnSurfaceVariant(author, bubbleColor));
            contextPreviewDoc.setColorFilter(bubbleToOnSurfaceVariant(contextPreviewDoc, bubbleColor));
            contextPreviewAudio.clearColorFilter();

            final SpannableStringBuilder replyPreview =
                    replyMessage.getBodyForReplyPreview(activity.xmppConnectionService);
            TypographyHelper.applyCompact(replyPreview);
            text.setText(replyPreview);

            if (message.getConversation().getMode() == Conversation.MODE_MULTI) {
                author.setVisibility(View.VISIBLE);
                author.setText(replyMessage.getAvatarName());
            } else {
                author.setVisibility(View.GONE);
            }

            if (replyMessage.getFileParams().width > 0
                    && replyMessage.getFileParams().height > 0
                    && shouldDisplayMediaPreview(replyMessage)) {
                iconsContainer.setVisibility(View.VISIBLE);
                contextPreviewImage.setVisibility(View.VISIBLE);

                if (!contextPreviewImage.getClipToOutline()) {
                    contextPreviewImage.setClipToOutline(true);
                    contextPreviewImage.setOutlineProvider(new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            float maxRadius = Integer.min(view.getWidth(), view.getHeight()) / 2f;
                            float radius =  Float.min(dpToPx(4), maxRadius);
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                        }
                    });
                }

                contextPreviewDoc.setVisibility(View.GONE);
                contextPreviewAudio.setVisibility(View.GONE);
                activity.loadBitmap(replyMessage, contextPreviewImage);
            } else if (replyMessage.getFileParams().runtime > 0) {
                iconsContainer.setVisibility(View.VISIBLE);
                contextPreviewImage.setVisibility(View.GONE);
                contextPreviewDoc.setVisibility(View.GONE);
                contextPreviewAudio.setVisibility(View.VISIBLE);
            } else if (replyMessage.isFileOrImage()) {
                iconsContainer.setVisibility(View.VISIBLE);
                contextPreviewImage.setVisibility(View.GONE);
                contextPreviewDoc.setVisibility(View.VISIBLE);
                contextPreviewAudio.setVisibility(View.GONE);
            } else {
                iconsContainer.setVisibility(View.GONE);
            }
        } else if (replyMessage != null) {
            viewHolder.nonTextReplyContent.setVisibility(View.VISIBLE);
            contextPreviewImage.setVisibility(View.GONE);
            contextPreviewDoc.setVisibility(View.GONE);
            contextPreviewAudio.setVisibility(View.GONE);
            text.setVisibility(View.GONE);
            iconsContainer.setVisibility(View.GONE);

            author.setTextColor(bubbleToOnSurfaceVariant(author, bubbleColor));

            if (message.getConversation().getMode() == Conversation.MODE_MULTI) {
                author.setVisibility(View.VISIBLE);
                author.setText(replyMessage.getAvatarName());
            } else {
                author.setVisibility(View.GONE);
            }
        } else {
            viewHolder.nonTextReplyContent.setVisibility(View.GONE);
        }
    }

    private void displayDownloadableMessage(
            ViewHolder viewHolder,
            final Message message,
            String ignoredTechnicalLabel,
            final BubbleColor bubbleColor) {
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        displayFileCard(viewHolder, message, bubbleColor, null);
        showDownloadAction(viewHolder, message);

        maybeShowReply(message.getReplyMessage(), true, viewHolder, message, bubbleColor);
    }

    private void displayIncomingTransferMessage(
            final ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        final Transferable transferable = message.getTransferable();
        final int transferStatus =
                transferable == null ? Transferable.STATUS_UNKNOWN : transferable.getStatus();

        if (transferStatus == Transferable.STATUS_CHECKING
                || transferStatus == Transferable.STATUS_DOWNLOADING) {
            displayBusyInfoMessage(
                    viewHolder,
                    activity.getString(R.string.incoming_transfer_preparing),
                    bubbleColor);
            maybeShowReply(message.getReplyMessage(), true, viewHolder, message, bubbleColor);
            return;
        }

        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        displayFileCard(viewHolder, message, bubbleColor, null);
        showDownloadAction(viewHolder, message);
        maybeShowReply(message.getReplyMessage(), true, viewHolder, message, bubbleColor);
    }

    private void showDownloadAction(
            final ViewHolder viewHolder, final Message message) {
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(R.string.incoming_transfer_download);
        viewHolder.download_button.setIconResource(R.drawable.ic_download_24dp);
        viewHolder.download_button.setOnClickListener(
                v -> ConversationFragment.downloadFile(activity, message));
        viewHolder.download_button.setOnLongClickListener(messageActionLongClickListener(message));
    }

    private void displayOpenableMessage(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        displayFileCard(viewHolder, message, bubbleColor, v -> openDownloadable(message));

        maybeShowReply(message.getReplyMessage(), true, viewHolder, message, bubbleColor);
    }

    private void displayFileCard(
            final ViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor,
            final View.OnClickListener clickListener) {
        viewHolder.fileCard.setVisibility(View.VISIBLE);
        viewHolder.fileCard.setOnClickListener(clickListener);
        viewHolder.fileCard.setOnLongClickListener(messageActionLongClickListener(message));

        String mime = message.getMimeType();
        if (Strings.isNullOrEmpty(mime) && message.getType() == Message.TYPE_IMAGE) {
            mime = "image/*";
        }
        if (Strings.isNullOrEmpty(mime)) {
            final String displayName = getFileDisplayName(message);
            final String extension = eu.siacs.conversations.utils.MimeUtils.extractRelevantExtension(displayName);
            if (!Strings.isNullOrEmpty(extension)) {
                mime = eu.siacs.conversations.utils.MimeUtils.guessMimeTypeFromExtension(extension);
            }
        }
        viewHolder.fileIcon.setImageResource(MediaAdapter.getImageDrawable(mime));
        ImageViewCompat.setImageTintList(
                viewHolder.fileIcon,
                ColorStateList.valueOf(
                        bubbleToOnSurfaceVariant(viewHolder.fileIcon, bubbleColor)));

        viewHolder.fileName.setText(getFileDisplayName(message));
        setTextColor(viewHolder.fileName, bubbleColor);

        Long size = message.getSecureMediaSizeBytes();
        if (size == null || size <= 0) {
            size = message.getFileParams().size;
        }
        final Transferable transferable = message.getTransferable();
        if ((size == null || size <= 0) && transferable != null) {
            size = transferable.getFileSize();
        }
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT && (size == null || size <= 0)) {
            final String localPath = message.getRelativeFilePath();
            if (!Strings.isNullOrEmpty(localPath)) {
                final File localFile = new File(localPath);
                if (localFile.isFile() && localFile.length() > 0) {
                    size = localFile.length();
                }
            }
        }
        if (size != null && size > 0) {
            viewHolder.fileSize.setText(UIHelper.filesizeToString(size));
            viewHolder.fileSize.setTextColor(
                    bubbleToOnSurfaceVariant(viewHolder.fileSize, bubbleColor));
            viewHolder.fileSize.setVisibility(View.VISIBLE);
        } else {
            viewHolder.fileSize.setVisibility(View.GONE);
        }
    }

    private void hideFileCard(final ViewHolder viewHolder) {
        if (viewHolder.fileCard != null) {
            viewHolder.fileCard.setVisibility(View.GONE);
            viewHolder.fileCard.setOnClickListener(null);
            viewHolder.fileCard.setOnLongClickListener(null);
        }
    }

    private String getFileDisplayName(final Message message) {
        final String secureName = message.getSecureMediaFileName();
        if (!Strings.isNullOrEmpty(secureName)) {
            return secureName;
        }
        String localName = null;
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            final String localPath = message.getRelativeFilePath();
            if (!Strings.isNullOrEmpty(localPath)) {
                localName =
                        FileBackend.userVisiblePlaintextFileName(
                                message.getUuid(), new File(localPath).getName());
                if (!Strings.isNullOrEmpty(localName) && !looksGeneratedFileName(localName)) {
                    return localName;
                }
            }
        }

        final String remoteUrl = message.getFileParams().url;
        if (!Strings.isNullOrEmpty(remoteUrl)) {
            try {
                final String lastSegment = Uri.parse(remoteUrl).getLastPathSegment();
                if (!Strings.isNullOrEmpty(lastSegment)) {
                    final String decoded = Uri.decode(lastSegment);
                    if (!Strings.isNullOrEmpty(decoded)) {
                        return decoded;
                    }
                }
            } catch (final Exception ignored) {
            }
        }

        if (!Strings.isNullOrEmpty(localName)) {
            return localName;
        }
        return activity.getString(R.string.file);
    }

    private static boolean looksGeneratedFileName(final String filename) {
        if (Strings.isNullOrEmpty(filename)) {
            return false;
        }
        final int dot = filename.lastIndexOf('.');
        final String stem = dot > 0 ? filename.substring(0, dot) : filename;
        try {
            UUID.fromString(stem);
            return true;
        } catch (final IllegalArgumentException ignored) {
            return false;
        }
    }

    private void displayLocationMessage(ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        hideFileCard(viewHolder);
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(R.string.show_location);
        viewHolder.download_button.setIcon(null);
        viewHolder.download_button.setOnClickListener(v -> showLocation(message));
        viewHolder.download_button.setOnLongClickListener(messageActionLongClickListener(message));

        maybeShowReply(message.getReplyMessage(), true, viewHolder, message, bubbleColor);
    }

    private void displayAudioMessage(ViewHolder viewHolder, Message message, final BubbleColor bubbleColor) {
        hideFileCard(viewHolder);
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        final RelativeLayout audioPlayer = viewHolder.audioPlayer;
        audioPlayer.setVisibility(View.VISIBLE);
        AudioPlayer.ViewHolder.get(audioPlayer).setBubbleColor(bubbleColor);
        this.audioPlayer.init(audioPlayer, message);
        audioPlayer.setOnLongClickListener(messageActionLongClickListener(message));

        maybeShowReply(message.getReplyMessage(), true, viewHolder, message, bubbleColor);
    }


    private void displayMediaCaption(
            final ViewHolder viewHolder,
            @Nullable final Message caption,
            final BubbleColor bubbleColor) {
        if (viewHolder.mediaCaption == null || viewHolder.mediaCaptionDivider == null) {
            return;
        }
        if (caption == null || caption.getBody().trim().isEmpty()) {
            viewHolder.mediaCaptionDivider.setVisibility(View.GONE);
            viewHolder.mediaCaption.setText(null);
            viewHolder.mediaCaption.setVisibility(View.GONE);
            viewHolder.mediaCaption.setMovementMethod(null);
            return;
        }

        final SpannableStringBuilder body = caption.getBodyForDisplaying(false);
        TypographyHelper.apply(body);
        applySimpleListHangingIndent(viewHolder.mediaCaption, body);
        setTextSize(viewHolder.mediaCaption, this.bubbleDesign.messageTextSizeSp);
        setTextColor(viewHolder.mediaCaption, bubbleColor);
        final Element captionMarkup = caption.getMessageMarkup();
        if (MessageMarkup.canApplyToDisplayedBody(caption, body)) {
            StylingHelper.formatMarkup(body, captionMarkup);
        } else if (captionMarkup == null && !caption.isStylingDisabled()) {
            StylingHelper.format(body, viewHolder.mediaCaption.getCurrentTextColor());
        }
        MyLinkify.addLinks(body, true);
        viewHolder.mediaCaption.setAutoLinkMask(0);
        viewHolder.mediaCaption.setText(body);
        viewHolder.mediaCaption.setMovementMethod(ClickableMovementMethod.getInstance());
        viewHolder.mediaCaption.setOnClickListener(
                view -> {
                    if (messageClickListener != null) {
                        messageClickListener.onMessageClick(caption);
                    }
                });
        viewHolder.mediaCaption.setOnTouchListener(
                new PlainTextBubbleTouchBridge(viewHolder.mediaCaption, selectionStatusProvider));
        viewHolder.mediaCaptionDivider.setVisibility(View.VISIBLE);
        viewHolder.mediaCaption.setVisibility(View.VISIBLE);
    }

    private int compactAlbumHeight(final int count, final int tileSize, final int gap) {
        if (count <= 2) {
            return tileSize;
        }
        if (count <= 4) {
            return 2 * tileSize + gap;
        }
        // Keep 5-10 media bounded to the exact height of the old 6-item 2x3 grid.
        return 3 * tileSize + 2 * gap;
    }

    private FrameLayout prepareCompactAlbumCanvas(
            final GridLayout grid,
            final int width,
            final int height) {
        grid.removeAllViews();
        grid.setRowCount(1);
        grid.setColumnCount(2);
        grid.setLayoutParams(new LinearLayout.LayoutParams(width, height));

        final int seamColor =
                MaterialColors.getColor(
                        grid,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest,
                        Color.BLACK);
        grid.setBackgroundColor(seamColor);
        MediaMessageChromeRenderer.applyConnectedMediaClip(grid, 16);

        final FrameLayout canvas = new FrameLayout(activity);
        final GridLayout.LayoutParams canvasParams =
                new GridLayout.LayoutParams(
                        GridLayout.spec(0), GridLayout.spec(0, 2));
        canvasParams.width = width;
        canvasParams.height = height;
        grid.addView(canvas, canvasParams);
        return canvas;
    }

    private int[] compactColumnHeights(
            final int totalHeight,
            final int count,
            final boolean leftColumn) {
        final float[] leftPattern = {1.15f, 0.85f, 1.05f, 0.95f, 1.0f};
        final float[] rightPattern = {0.85f, 1.15f, 0.95f, 1.05f, 1.0f};
        final float[] pattern = leftColumn ? leftPattern : rightPattern;
        float weightSum = 0f;
        for (int i = 0; i < count; i++) {
            weightSum += pattern[i % pattern.length];
        }
        final int[] result = new int[count];
        int consumed = 0;
        for (int i = 0; i < count; i++) {
            final int height =
                    i == count - 1
                            ? totalHeight - consumed
                            : Math.max(
                                    1,
                                    Math.round(
                                            totalHeight
                                                    * pattern[i % pattern.length]
                                                    / weightSum));
            result[i] = height;
            consumed += height;
        }
        return result;
    }

    private int compactOddLeadHeight(final int count, final int tileSize) {
        if (count == 5) {
            return Math.round(tileSize * 0.90f);
        }
        if (count == 7) {
            return Math.round(tileSize * 0.70f);
        }
        return Math.round(tileSize * 0.60f);
    }

    private void displayCompactMediaAlbum(
            final GridLayout grid,
            final List<Message> album,
            final int width,
            final int tileSize,
            final int gap) {
        final int count = album.size();
        final int height = compactAlbumHeight(count, tileSize, gap);
        final FrameLayout canvas = prepareCompactAlbumCanvas(grid, width, height);
        final int rightX = tileSize + gap;

        if (count == 2) {
            addCompactAlbumMessageTile(canvas, album, 0, 0, 0, tileSize, tileSize);
            addCompactAlbumMessageTile(canvas, album, 1, rightX, 0, tileSize, tileSize);
            return;
        }
        if (count == 3) {
            addCompactAlbumMessageTile(canvas, album, 0, 0, 0, tileSize, height);
            addCompactAlbumMessageTile(canvas, album, 1, rightX, 0, tileSize, tileSize);
            addCompactAlbumMessageTile(
                    canvas, album, 2, rightX, tileSize + gap, tileSize, tileSize);
            return;
        }
        if (count == 4) {
            for (int index = 0; index < 4; index++) {
                final int column = index % 2;
                final int row = index / 2;
                addCompactAlbumMessageTile(
                        canvas,
                        album,
                        index,
                        column == 0 ? 0 : rightX,
                        row * (tileSize + gap),
                        tileSize,
                        tileSize);
            }
            return;
        }

        renderCompactMessageStacks(canvas, album, width, height, tileSize, gap);
    }

    private void renderCompactMessageStacks(
            final FrameLayout canvas,
            final List<Message> album,
            final int width,
            final int height,
            final int tileSize,
            final int gap) {
        final int count = album.size();
        int firstStackIndex = 0;
        int stackTop = 0;
        if ((count & 1) == 1) {
            final int leadHeight = compactOddLeadHeight(count, tileSize);
            addCompactAlbumMessageTile(canvas, album, 0, 0, 0, width, leadHeight);
            firstStackIndex = 1;
            stackTop = leadHeight + gap;
        }

        final int perColumn = (count - firstStackIndex) / 2;
        final int stackHeight = height - stackTop;
        final int contentHeight = stackHeight - (perColumn - 1) * gap;
        final int[] leftHeights = compactColumnHeights(contentHeight, perColumn, true);
        final int[] rightHeights = compactColumnHeights(contentHeight, perColumn, false);
        final int rightX = tileSize + gap;

        int leftY = stackTop;
        int rightY = stackTop;
        for (int i = 0; i < perColumn; i++) {
            final int leftIndex = firstStackIndex + i * 2;
            final int rightIndex = leftIndex + 1;
            addCompactAlbumMessageTile(
                    canvas, album, leftIndex, 0, leftY, tileSize, leftHeights[i]);
            addCompactAlbumMessageTile(
                    canvas, album, rightIndex, rightX, rightY, tileSize, rightHeights[i]);
            leftY += leftHeights[i] + gap;
            rightY += rightHeights[i] + gap;
        }
    }

    private void addCompactAlbumMessageTile(
            final FrameLayout canvas,
            final List<Message> album,
            final int index,
            final int left,
            final int top,
            final int width,
            final int height) {
        final Message message = album.get(index);
        final FrameLayout tile = createCompactAlbumTile();
        final ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(
                image,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.loadBitmap(message, image);
        tile.setOnClickListener(v -> openMediaAlbum(album, index));
        tile.setOnLongClickListener(messageActionLongClickListener(message));
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        canvas.addView(tile, params);
    }

    private FrameLayout createCompactAlbumTile() {
        final FrameLayout tile = new FrameLayout(activity);
        final int surfaceColor =
                MaterialColors.getColor(
                        tile,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest,
                        Color.BLACK);
        tile.setBackgroundColor(surfaceColor);
        final int onSurface =
                MaterialColors.getColor(
                        tile,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.WHITE);
        tile.setForeground(
                new RippleDrawable(
                        ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 36)),
                        null,
                        new ColorDrawable(Color.WHITE)));
        return tile;
    }

    private void displayCompactPreparingAlbum(
            final GridLayout grid,
            final List<Attachment> attachments,
            final int width,
            final int tileSize,
            final int gap) {
        final int count = attachments.size();
        final int height = compactAlbumHeight(count, tileSize, gap);
        final FrameLayout canvas = prepareCompactAlbumCanvas(grid, width, height);
        final int rightX = tileSize + gap;
        final int previewSize =
                Math.max(
                        1,
                        Math.round(
                                activity.getResources()
                                        .getDimension(R.dimen.media_preview_size)));

        if (count == 2) {
            addCompactPreparingTile(
                    canvas, attachments.get(0), 0, 0, tileSize, tileSize, previewSize);
            addCompactPreparingTile(
                    canvas, attachments.get(1), rightX, 0, tileSize, tileSize, previewSize);
            return;
        }
        if (count == 3) {
            addCompactPreparingTile(
                    canvas, attachments.get(0), 0, 0, tileSize, height, previewSize);
            addCompactPreparingTile(
                    canvas, attachments.get(1), rightX, 0, tileSize, tileSize, previewSize);
            addCompactPreparingTile(
                    canvas,
                    attachments.get(2),
                    rightX,
                    tileSize + gap,
                    tileSize,
                    tileSize,
                    previewSize);
            return;
        }
        if (count == 4) {
            for (int index = 0; index < 4; index++) {
                final int column = index % 2;
                final int row = index / 2;
                addCompactPreparingTile(
                        canvas,
                        attachments.get(index),
                        column == 0 ? 0 : rightX,
                        row * (tileSize + gap),
                        tileSize,
                        tileSize,
                        previewSize);
            }
            return;
        }

        int firstStackIndex = 0;
        int stackTop = 0;
        if ((count & 1) == 1) {
            final int leadHeight = compactOddLeadHeight(count, tileSize);
            addCompactPreparingTile(
                    canvas, attachments.get(0), 0, 0, width, leadHeight, previewSize);
            firstStackIndex = 1;
            stackTop = leadHeight + gap;
        }
        final int perColumn = (count - firstStackIndex) / 2;
        final int stackHeight = height - stackTop;
        final int contentHeight = stackHeight - (perColumn - 1) * gap;
        final int[] leftHeights = compactColumnHeights(contentHeight, perColumn, true);
        final int[] rightHeights = compactColumnHeights(contentHeight, perColumn, false);
        int leftY = stackTop;
        int rightY = stackTop;
        for (int i = 0; i < perColumn; i++) {
            final int leftIndex = firstStackIndex + i * 2;
            final int rightIndex = leftIndex + 1;
            addCompactPreparingTile(
                    canvas,
                    attachments.get(leftIndex),
                    0,
                    leftY,
                    tileSize,
                    leftHeights[i],
                    previewSize);
            addCompactPreparingTile(
                    canvas,
                    attachments.get(rightIndex),
                    rightX,
                    rightY,
                    tileSize,
                    rightHeights[i],
                    previewSize);
            leftY += leftHeights[i] + gap;
            rightY += rightHeights[i] + gap;
        }
    }

    private void addCompactPreparingTile(
            final FrameLayout canvas,
            final Attachment attachment,
            final int left,
            final int top,
            final int width,
            final int height,
            final int previewSize) {
        final FrameLayout tile = createCompactAlbumTile();
        final ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(
                image,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadPreparingAttachmentPreview(attachment, image, previewSize);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        canvas.addView(tile, params);
    }

    private void displayMediaAlbum(
            final ViewHolder viewHolder,
            final List<Message> album,
            final BubbleColor bubbleColor) {
        hideFileCard(viewHolder);
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        maybeShowReply(album.get(0).getReplyMessage(), true, viewHolder, album.get(0), bubbleColor);

        final GridLayout grid = viewHolder.mediaAlbum;
        grid.removeAllViews();
        grid.setVisibility(View.VISIBLE);
        final int gap = dpToPx(1);
        final int width = (int) activity.getResources().getDimension(R.dimen.image_preview_width);
        final int tileSize = (width - gap) / 2;
        if (album.size() <= 10
                && album.get(0).getConversation().getMode() == Conversational.MODE_SINGLE) {
            displayCompactMediaAlbum(
                    grid, album, width, tileSize, gap);
            return;
        }
        final MediaAlbumLayoutPlanner.Plan plan =
                MediaAlbumLayoutPlanner.plan(album.size());
        final int rowCount = plan.getRowCount();
        final int height = rowCount * tileSize + (rowCount - 1) * gap;
        final LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(width, height);
        grid.setLayoutParams(layoutParams);
        grid.setRowCount(rowCount);
        final int seamColor =
                MaterialColors.getColor(
                        grid,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest,
                        Color.BLACK);
        grid.setBackgroundColor(seamColor);
        MediaMessageChromeRenderer.applyConnectedMediaClip(grid, 16);

        for (final MediaAlbumLayoutPlanner.Tile tile : plan.getTiles()) {
            final int columnSpan = tile.getColumnSpan();
            final int tileWidth = columnSpan == 2 ? width : tileSize;
            addAlbumTile(
                    grid,
                    album,
                    tile.getIndex(),
                    tile.getRow(),
                    tile.getColumn(),
                    columnSpan,
                    tileWidth,
                    tileSize,
                    gap);
        }
    }

    private void addAlbumTile(
            final GridLayout grid,
            final List<Message> album,
            final int index,
            final int row,
            final int column,
            final int columnSpan,
            final int width,
            final int height,
            final int gap) {
        final Message message = album.get(index);
        final FrameLayout tile = new FrameLayout(activity);
        final int surfaceColor =
                MaterialColors.getColor(
                        tile,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest,
                        Color.BLACK);
        tile.setBackgroundColor(surfaceColor);
        final int onSurface =
                MaterialColors.getColor(
                        tile,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.WHITE);
        tile.setForeground(
                new RippleDrawable(
                        ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 36)),
                        null,
                        new ColorDrawable(Color.WHITE)));
        final ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(
                image,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.loadBitmap(message, image);
        tile.setOnClickListener(v -> openMediaAlbum(album, index));
        tile.setOnLongClickListener(messageActionLongClickListener(message));
        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams(
                        GridLayout.spec(row), GridLayout.spec(column, columnSpan));
        params.width = width;
        params.height = height;
        final int rightMargin = column + columnSpan < 2 ? gap : 0;
        final int bottomMargin = row < grid.getRowCount() - 1 ? gap : 0;
        params.setMargins(0, 0, rightMargin, bottomMargin);
        grid.addView(tile, params);
    }


    private void openMediaAlbum(final List<Message> album, final int initialIndex) {
        MediaAlbumActivity.launch(activity, album, initialIndex);
    }

    static boolean shouldDisplayMediaPreview(final Message message) {
        final String mime = message.getMimeType();
        if (!Strings.isNullOrEmpty(mime)) {
            return mime.startsWith("image/") || mime.startsWith("video/");
        }
        return message.getType() == Message.TYPE_IMAGE;
    }

    static boolean shouldRenderMediaPreview(final Message message) {
        if (!shouldDisplayMediaPreview(message)) {
            return false;
        }
        final FileParams params = message.getFileParams();
        if (params.width > 0 && params.height > 0) {
            return true;
        }
        final String mime = message.getMimeType();
        // Committed authenticated media remains media even when best-effort dimension extraction
        // failed. Render a bounded placeholder and keep image/video routing connected instead of
        // demoting the attachment to a generic file card.
        return !Strings.isNullOrEmpty(mime)
                && (mime.startsWith("image/") || mime.startsWith("video/"));
    }

    static boolean shouldOpenInMediaViewer(final Message message) {
        final String mime = message.getMimeType();
        if (!Strings.isNullOrEmpty(mime)) {
            return mime.startsWith("image/") || mime.startsWith("video/");
        }
        return message.getType() == Message.TYPE_IMAGE;
    }

    static int clampPortraitPreviewWidth(final int width, final int height) {
        if (width <= 0 || height <= 0 || width >= height) {
            return width;
        }
        final int minimumWidth = Math.round(height * MIN_PORTRAIT_MEDIA_PREVIEW_ASPECT);
        return Math.max(width, minimumWidth);
    }

    private void displayMediaPreviewMessage(ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        hideFileCard(viewHolder);
        viewHolder.messageBody.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.VISIBLE);
        // A standalone media bubble must preserve the whole image. The preview width may be
        // widened for very tall portraits, but CENTER_CROP would then cut off the top/bottom.
        // Album tiles intentionally keep CENTER_CROP because their geometry is fixed.
        viewHolder.image.setScaleType(ImageView.ScaleType.FIT_CENTER);

        MediaMessageChromeRenderer.applyConnectedMediaClip(viewHolder.image, 16);

        maybeShowReply(message.getReplyMessage(), true, viewHolder, message, bubbleColor);
        final FileParams params = message.getFileParams();
        final String presentationMime = message.getMimeType();
        final boolean videoPreview =
                !Strings.isNullOrEmpty(presentationMime)
                        && presentationMime.startsWith("video/");
        final float target = activity.getResources().getDimension(R.dimen.image_preview_width);
        int scaledW;
        final int scaledH;
        if (params.width <= 0 || params.height <= 0) {
            // Authenticated image MIME is enough to keep media presentation stable. Missing
            // dimensions are a best-effort metadata failure, not a reason to show a file card.
            scaledW = (int) target;
            scaledH = (int) target;
        } else if (videoPreview) {
            // Presentation size is a UI contract, not a reflection of encoded resolution.
            // A 360p clip and a 1080p clip with the same aspect ratio occupy the same bubble.
            if (params.width <= params.height) {
                scaledW = (int) (params.width / ((double) params.height / target));
                scaledH = (int) target;
            } else {
                scaledW = (int) target;
                scaledH = (int) (params.height / ((double) params.width / target));
            }
        } else if (Math.max(params.height, params.width) * metrics.density <= target) {
            scaledW = (int) (params.width * metrics.density);
            scaledH = (int) (params.height * metrics.density);
        } else if (Math.max(params.height, params.width) <= target) {
            scaledW = params.width;
            scaledH = params.height;
        } else if (params.width <= params.height) {
            scaledW = (int) (params.width / ((double) params.height / target));
            scaledH = (int) target;
        } else {
            scaledW = (int) target;
            scaledH = (int) (params.height / ((double) params.width / target));
        }
        scaledW = clampPortraitPreviewWidth(scaledW, scaledH);
        final LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(scaledW, scaledH);
        viewHolder.image.setLayoutParams(layoutParams);
        // Some remote/MUC senders omit reliable width/height metadata. Let the verified decoded
        // bitmap correct the standalone bubble aspect once it becomes available.
        viewHolder.image.setTag(
                R.id.TAG_MEDIA_PREVIEW_TARGET_SIZE,
                Math.max(1, Math.round(target)));
        activity.loadBitmap(message, viewHolder.image);
        viewHolder.image.setOnClickListener(
                v -> {
                    if (shouldOpenInMediaViewer(message)) {
                        openMediaAlbum(Collections.singletonList(message), 0);
                    } else {
                        openDownloadable(message);
                    }
                });
        viewHolder.image.setOnLongClickListener(messageActionLongClickListener(message));
    }

    private void bindSingleCall(final ViewHolder viewHolder, final Message message) {
        final RtpSessionStatus status = RtpSessionStatus.of(message.getBody());
        final boolean received = message.getStatus() == Message.STATUS_RECEIVED;
        final int titleRes;
        if (received) {
            titleRes = status.successful ? R.string.incoming_call : R.string.missed_call;
        } else {
            titleRes = status.successful ? R.string.outgoing_call : R.string.call_no_answer;
        }

        viewHolder.status_message.setText(titleRes);
        final String time = formatMessageTime(message.getTimeSent());
        if (status.successful && status.duration > 0) {
            viewHolder.callMetadata.setText(
                    activity.getString(
                            R.string.call_time_with_duration,
                            time,
                            compactCallDuration(status.duration)));
        } else {
            viewHolder.callMetadata.setText(time);
        }

        setBackgroundTint(viewHolder.message_box, BubbleColor.SURFACE_HIGH);
        setTextColor(viewHolder.status_message, BubbleColor.SURFACE_HIGH);
        setTextColor(viewHolder.callMetadata, BubbleColor.SURFACE_HIGH);
        setImageTint(viewHolder.indicatorReceived, BubbleColor.SURFACE_HIGH);
        viewHolder.indicatorReceived.setImageResource(
                RtpSessionStatus.getDrawable(received, status.successful));
    }

    private String compactCallDuration(final long durationMillis) {
        final long totalSeconds = Math.max(0L, durationMillis / 1000L);
        if (totalSeconds < 60L) {
            return totalSeconds + "\u00A0" + activity.getString(R.string.call_duration_seconds_short);
        }
        final long totalMinutes = totalSeconds / 60L;
        if (totalMinutes < 60L) {
            return totalMinutes + "\u00A0" + activity.getString(R.string.call_duration_minutes_short);
        }
        final long hours = totalMinutes / 60L;
        final long minutes = totalMinutes % 60L;
        if (minutes == 0L) {
            return hours + "\u00A0" + activity.getString(R.string.call_duration_hours_short);
        }
        return hours
                + "\u00A0"
                + activity.getString(R.string.call_duration_hours_short)
                + "\u00A0"
                + minutes
                + "\u00A0"
                + activity.getString(R.string.call_duration_minutes_short);
    }

    private void bindCallGroup(final ViewHolder viewHolder, final List<Message> callRun) {
        final int count = callRun.size();
        final Message first = callRun.get(0);
        final Message last = callRun.get(count - 1);
        final String firstTime = formatMessageTime(first.getTimeSent());
        final String lastTime = formatMessageTime(last.getTimeSent());
        final String range =
                firstTime.equals(lastTime)
                        ? firstTime
                        : activity.getString(R.string.call_time_range, firstTime, lastTime);

        Message lastSuccessful = null;
        RtpSessionStatus lastSuccessfulStatus = null;
        for (int i = count - 1; i >= 0; i--) {
            final Message candidate = callRun.get(i);
            final RtpSessionStatus candidateStatus = RtpSessionStatus.of(candidate.getBody());
            if (candidateStatus.successful) {
                lastSuccessful = candidate;
                lastSuccessfulStatus = candidateStatus;
                break;
            }
        }

        int missedIncoming = 0;
        for (final Message candidate : callRun) {
            final RtpSessionStatus candidateStatus = RtpSessionStatus.of(candidate.getBody());
            if (!candidateStatus.successful
                    && candidate.getStatus() == Message.STATUS_RECEIVED) {
                missedIncoming++;
            }
        }

        if (lastSuccessful != null && lastSuccessfulStatus != null) {
            final boolean successfulReceived =
                    lastSuccessful.getStatus() == Message.STATUS_RECEIVED;
            viewHolder.status_message.setText(
                    successfulReceived ? R.string.incoming_call : R.string.outgoing_call);

            final String missed =
                    missedIncoming > 0
                            ? activity.getResources()
                                    .getQuantityString(
                                            R.plurals.call_missed_compact,
                                            missedIncoming,
                                            missedIncoming)
                            : null;
            if (lastSuccessfulStatus.duration > 0) {
                final String duration = compactCallDuration(lastSuccessfulStatus.duration);
                viewHolder.callMetadata.setText(
                        missed == null
                                ? activity.getString(
                                        R.string.call_group_result_duration,
                                        range,
                                        duration)
                                : activity.getString(
                                        R.string.call_group_result_duration_with_missed,
                                        range,
                                        duration,
                                        missed));
            } else {
                viewHolder.callMetadata.setText(
                        missed == null
                                ? range
                                : activity.getString(
                                        R.string.call_group_result_with_missed,
                                        range,
                                        missed));
            }
        } else if (missedIncoming > 0) {
            viewHolder.status_message.setText(
                    activity.getResources()
                            .getQuantityString(
                                    R.plurals.n_missed_calls,
                                    missedIncoming,
                                    missedIncoming));
            viewHolder.callMetadata.setText(range);
        } else {
            viewHolder.status_message.setText(R.string.call_no_answer);
            viewHolder.callMetadata.setText(range);
        }

        final RtpSessionStatus lastStatus = RtpSessionStatus.of(last.getBody());
        final boolean lastReceived = last.getStatus() == Message.STATUS_RECEIVED;
        setBackgroundTint(viewHolder.message_box, BubbleColor.SURFACE_HIGH);
        setTextColor(viewHolder.status_message, BubbleColor.SURFACE_HIGH);
        setTextColor(viewHolder.callMetadata, BubbleColor.SURFACE_HIGH);
        setImageTint(viewHolder.indicatorReceived, BubbleColor.SURFACE_HIGH);
        viewHolder.indicatorReceived.setImageResource(
                RtpSessionStatus.getDrawable(lastReceived, lastStatus.successful));
    }

    private void loadMoreMessages(Conversation conversation) {
        conversation.setLastClearHistory(0, null);
        activity.xmppConnectionService.updateConversation(conversation);
        conversation.setHasMessagesLeftOnServer(true);
        conversation.setFirstMamReference(null);
        long timestamp = conversation.getLastMessageTransmitted().getTimestamp();
        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }
        conversation.messagesLoaded.set(true);
        activity.xmppConnectionService
                .getMessageArchiveService()
                .query(conversation, new MamReference(0), timestamp, false);
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (getItemViewType(position) == ALBUM_CONTINUATION
                || getItemViewType(position) == CAPTION_CONTINUATION
                || getItemViewType(position) == CALL_GROUP_CONTINUATION) {
            final Space spacer = new Space(activity);
            spacer.setLayoutParams(new AbsListView.LayoutParams(1, 0));
            return spacer;
        }
        final Message message = getItem(position);
        if (message != null
                && message.getSecureMessagePayloadMode()
                        == eu.siacs.conversations.storage.secure.SecureMessagePayloadMode.PROTECTED
                && !message.hasVerifiedProtectedBody()
                && activity.xmppConnectionService != null) {
            activity.xmppConnectionService.scheduleProtectedTextRehydrateForVisibleMessage(message);
        }
        final boolean incomingTransferActive = isIncomingTransferActive(message);
        final boolean wasIncomingTransferActive =
                !incomingTransferActive
                        && activeIncomingTransferMessages.remove(message.getUuid());
        final boolean revealCommittedIncomingAttachment =
                wasIncomingTransferActive
                        && message.getTransferable() == null
                        && message.isFileOrImage();
        final boolean revealNewMessage =
                consumeMessageEnterAnimation(message) && !incomingTransferActive;
        if (incomingTransferActive) {
            activeIncomingTransferMessages.add(message.getUuid());
        }
        final boolean omemoEncryption = message.getEncryption() == Message.ENCRYPTION_AXOLOTL;
        final boolean isInValidSession = message.isValidInSession() && (!omemoEncryption || message.isTrusted());
        final Conversational conversation = message.getConversation();
        final Account account = conversation.getAccount();
        if (message.isFileOrImage()
                && !message.isSecureMediaPresentationMetadataResolved()) {
            SecureMessageMediaUiBridge.hydratePresentationMetadata(
                    activity, message, () -> notifyDataSetChanged());
        }
        final int type = getItemViewType(position);

        int oldPosition = -1;
        ViewHolder viewHolder;
        if (view == null) {
            viewHolder = new ViewHolder();
            viewHolder.position = position;
            switch (type) {
                case DATE_SEPARATOR:
                    view = activity.getLayoutInflater().inflate(R.layout.message_date_bubble, parent, false);
                    viewHolder.root = view;
                    viewHolder.status_message = view.findViewById(R.id.message_body);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    break;
                case RTP_SESSION:
                    view = activity.getLayoutInflater().inflate(R.layout.message_rtp_session, parent, false);
                    viewHolder.root = view;
                    viewHolder.status_message = view.findViewById(R.id.message_body);
                    viewHolder.callMetadata = view.findViewById(R.id.call_metadata);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    break;
                case SENT:
                    view = activity.getLayoutInflater().inflate(R.layout.message_sent, parent, false);
                    viewHolder.clicksInterceptor = view.findViewById(R.id.clicks_interceptor);
                    viewHolder.root = view;
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.fileCard = view.findViewById(R.id.file_card);
                    viewHolder.fileIcon = view.findViewById(R.id.file_icon);
                    viewHolder.fileName = view.findViewById(R.id.file_name);
                    viewHolder.fileSize = view.findViewById(R.id.file_size);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.mediaAlbum = view.findViewById(R.id.media_album);
                    viewHolder.mediaFooter = view.findViewById(R.id.media_footer);
                    viewHolder.mediaVisualContainer = view.findViewById(R.id.media_visual_container);
                    viewHolder.messageMetaRow = view.findViewById(R.id.message_meta_row);
                    viewHolder.messageStatusRow = view.findViewById(R.id.message_status_row);
                    viewHolder.mediaCaptionDivider = view.findViewById(R.id.media_caption_divider);
                    viewHolder.mediaCaption = view.findViewById(R.id.media_caption);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.busyIndicator = view.findViewById(R.id.message_busy_indicator);
                    viewHolder.nonTextReplyContent = view.findViewById(R.id.non_text_reply_content);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.reactions = view.findViewById(R.id.reactions);
                    view.setTag(R.id.TAG_DRAGGABLE, true);
                    break;
                case RECEIVED:
                    view = activity.getLayoutInflater().inflate(R.layout.message_received, parent, false);
                    viewHolder.clicksInterceptor = view.findViewById(R.id.clicks_interceptor);
                    viewHolder.root = view;
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.senderName = view.findViewById(R.id.message_sender);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.fileCard = view.findViewById(R.id.file_card);
                    viewHolder.fileIcon = view.findViewById(R.id.file_icon);
                    viewHolder.fileName = view.findViewById(R.id.file_name);
                    viewHolder.fileSize = view.findViewById(R.id.file_size);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.mediaAlbum = view.findViewById(R.id.media_album);
                    viewHolder.mediaFooter = view.findViewById(R.id.media_footer);
                    viewHolder.mediaVisualContainer = view.findViewById(R.id.media_visual_container);
                    viewHolder.messageMetaRow = view.findViewById(R.id.message_meta_row);
                    viewHolder.messageStatusRow = view.findViewById(R.id.message_status_row);
                    viewHolder.mediaCaptionDivider = view.findViewById(R.id.media_caption_divider);
                    viewHolder.mediaCaption = view.findViewById(R.id.media_caption);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.busyIndicator = view.findViewById(R.id.message_busy_indicator);
                    viewHolder.nonTextReplyContent = view.findViewById(R.id.non_text_reply_content);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.encryption = view.findViewById(R.id.message_encryption);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.reactions = view.findViewById(R.id.reactions);
                    view.setTag(R.id.TAG_DRAGGABLE, true);
                    break;
                case STATUS:
                    view = activity.getLayoutInflater().inflate(R.layout.message_status, parent, false);
                    viewHolder.root = view;
                    viewHolder.status_message = view.findViewById(R.id.status_message);
                    viewHolder.load_more_messages = view.findViewById(R.id.load_more_messages);
                    break;
                default:
                    throw new AssertionError("Unknown view type");
            }
            view.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) view.getTag();
            if (dragHelper != null && dragHelper.getCapturedView() == view) {
                dragHelper.abort();
            }

            if (viewHolder == null) {
                return view;
            } else {
                oldPosition = viewHolder.position;
                viewHolder.position = position;
            }
        }

        View highlighter = view.findViewById(R.id.highlighter);

        if (highlighter != null && oldPosition != position) {
            highlighter.setVisibility(View.INVISIBLE);
        }

        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
        final BubbleColor bubbleColor;
        if (received) {
            // Plain incoming traffic is already communicated by the open-lock indicator.
            // Reserve the warning bubble for protected traffic whose session/trust is invalid.
            if (message.getEncryption() == Message.ENCRYPTION_NONE || isInValidSession) {
                bubbleColor = BubbleColor.SURFACE;
            } else {
                bubbleColor = BubbleColor.WARNING;
            }
        } else {
            bubbleColor = colorfulBackground ? BubbleColor.PRIMARY : BubbleColor.SURFACE_HIGH;
        }

        if (type == DATE_SEPARATOR) {
            viewHolder.status_message.setText(
                    formatDateSeparatorLabel(activity, message.getTimeSent()));

            setBackgroundTint(viewHolder.message_box, BubbleColor.SURFACE_HIGH);
            setTextColor(viewHolder.status_message, BubbleColor.SURFACE_HIGH);
            viewHolder.message_box.setOnClickListener(v -> onDateSeparatorClickListener.onDateSeparatorClick(message.getTimeSent()));
            return view;
        } else if (type == RTP_SESSION) {
            final List<Message> callRun = getCallRun(position);
            if (shouldCollapseCallRun(callRun)) {
                bindCallGroup(viewHolder, callRun);
            } else {
                bindSingleCall(viewHolder, message);
            }
            return view;
        } else if (type == STATUS) {
            if ("LOAD_MORE".equals(message.getBody())) {
                viewHolder.status_message.setVisibility(View.GONE);
                viewHolder.load_more_messages.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setOnClickListener(v -> loadMoreMessages((Conversation) message.getConversation()));
            } else {
                viewHolder.status_message.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setVisibility(View.GONE);
                viewHolder.status_message.setText(message.getBody());
            }
            return view;
        }

        final boolean revealResolvedProtectedText =
                prepareProtectedTextRevealForBind(viewHolder, message);

        resetMessageBubbleInteraction(viewHolder);
        viewHolder.message_box.setOnLongClickListener(messageActionLongClickListener(message));

        if (viewHolder.mediaFooter != null) {
            viewHolder.mediaFooter.setOnLongClickListener(
                    v -> viewHolder.message_box.performLongClick());
        }

        View.OnLongClickListener messageItemLongClickListener = v -> {
            if (messageEmptyPartClickListener != null) {
                messageEmptyPartClickListener.onMessageEmptyPartLongClick(message);
            }

            return messageEmptyPartClickListener != null;
        };

        View.OnClickListener messageItemClickListener = v -> {
            if (messageEmptyPartClickListener != null) {
                messageEmptyPartClickListener.onMessageEmptyPartClick(message);
            }
        };

        viewHolder.root.setOnLongClickListener(messageItemLongClickListener);
        viewHolder.root.setOnClickListener(null);
        viewHolder.clicksInterceptor.setOnClickListener(messageItemClickListener);
        viewHolder.clicksInterceptor.setOnLongClickListener(messageItemLongClickListener);

        viewHolder.clicksInterceptor.setVisibility(
                (selectionStatusProvider != null && selectionStatusProvider.isSomethingSelected())
                        ? View.VISIBLE : View.GONE);
        if (selectionStatusProvider == null || !selectionStatusProvider.isSelected(message)) {
            viewHolder.root.setBackground(null);
        } else {
            viewHolder.root.setBackgroundColor(
                    ColorUtils.setAlphaComponent(
                            MaterialColors.getColor(
                                    viewHolder.root,
                                    com.google.android.material.R.attr.colorPrimaryContainer),
                            128)
            );
        }

        viewHolder.mediaAlbum.setVisibility(View.GONE);
        if (viewHolder.busyIndicator != null) {
            viewHolder.busyIndicator.setVisibility(View.GONE);
        }
        final boolean isOutgoingMediaPreparing =
                outgoingMediaPreparingPresentation != null
                        && outgoingMediaPreparingPresentation.contains(message);
        final String outgoingPreparingCaption =
                isOutgoingMediaPreparing
                        ? outgoingMediaPreparingPresentation.getCaption(message)
                        : null;
        if (!isOutgoingMediaPreparing) {
            displayMediaCaption(viewHolder, null, bubbleColor);
        }
        final List<Message> mediaAlbum = getMediaAlbum(position);
        final Transferable transferable = message.getTransferable();
        final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message);
        boolean renderedEmoji = false;
        if (message.isModerated()) {
            displayInfoMessage(viewHolder,
                    activity.getString(R.string.muc_message_deleted_by_moderator), bubbleColor);
        } else if (isOutgoingMediaPreparing) {
            displayOutgoingMediaPreparingMessage(
                    viewHolder,
                    outgoingPreparingCaption,
                    outgoingMediaPreparingPresentation.getAttachments(message),
                    bubbleColor,
                    message.getConversation().getMode() == Conversational.MODE_SINGLE);
        } else if (mediaAlbum.size() > 1) {
            displayMediaAlbum(viewHolder, mediaAlbum, bubbleColor);
        } else if (unInitiatedButKnownSize || message.isDeleted() || (transferable != null && transferable.getStatus() != Transferable.STATUS_UPLOADING)) {
            final String transferMime = message.getMimeType();
            final boolean audioTransfer = transferMime != null && transferMime.startsWith("audio/");
            if (unInitiatedButKnownSize || transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER) {
                displayDownloadableMessage(
                        viewHolder,
                        message,
                        audioTransfer
                                ? activity.getString(R.string.download_voice_message)
                                : activity.getString(
                                        R.string.download_x_file,
                                        UIHelper.getFileDescriptionString(activity, message)),
                        bubbleColor);
            } else if (transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER_CHECK_FILESIZE) {
                displayDownloadableMessage(
                        viewHolder,
                        message,
                        audioTransfer
                                ? activity.getString(R.string.download_voice_message)
                                : activity.getString(
                                        R.string.check_x_filesize,
                                        UIHelper.getFileDescriptionString(activity, message)),
                        bubbleColor);
            } else if (hasIncomingTransferPresentation(message)) {
                displayIncomingTransferMessage(viewHolder, message, bubbleColor);
            } else {
                displayInfoMessage(viewHolder, UIHelper.getMessagePreview(activity, message).first, bubbleColor);
            }
        } else if (message.isFileOrImage() && message.getEncryption() != Message.ENCRYPTION_PGP && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            if (shouldRenderMediaPreview(message)) {
                displayMediaPreviewMessage(viewHolder, message, bubbleColor);
            } else if (message.getFileParams().runtime > 0) {
                displayAudioMessage(viewHolder, message, bubbleColor);
            } else {
                displayOpenableMessage(viewHolder, message, bubbleColor);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            displayInfoMessage(
                    viewHolder,
                    activity.getString(R.string.legacy_encrypted_message_unsupported),
                    bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            displayInfoMessage(viewHolder, activity.getString(R.string.decryption_failed), bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE) {
            displayInfoMessage(viewHolder, activity.getString(R.string.not_encrypted_for_this_device), bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            displayInfoMessage(viewHolder, activity.getString(R.string.omemo_decryption_failed), bubbleColor);
        } else {
            if (message.isGeoUri()) {
                displayLocationMessage(viewHolder, message, bubbleColor);
            } else if (message.bodyIsOnlyEmojis() && message.getType() != Message.TYPE_PRIVATE) {
                renderedEmoji = true;
                displayEmojiMessage(viewHolder, message, message.getBody().trim(), bubbleColor);
            } else if (message.treatAsDownloadable()) {
                final String downloadableMime = message.getMimeType();
                if (downloadableMime != null && downloadableMime.startsWith("audio/")) {
                    displayDownloadableMessage(
                            viewHolder,
                            message,
                            activity.getString(R.string.download_voice_message),
                            bubbleColor);
                } else {
                    try {
                        final URI uri = new URI(message.getBody());
                        displayDownloadableMessage(viewHolder,
                                message,
                                activity.getString(R.string.check_x_filesize_on_host,
                                        UIHelper.getFileDescriptionString(activity, message),
                                        uri.getHost()),
                                bubbleColor);
                    } catch (Exception e) {
                        displayDownloadableMessage(viewHolder,
                                message,
                                activity.getString(R.string.check_x_filesize,
                                        UIHelper.getFileDescriptionString(activity, message)),
                                bubbleColor);
                    }
                }
            } else {
                displayTextMessage(
                        viewHolder,
                        message,
                        bubbleColor,
                        type,
                        revealResolvedProtectedText);
            }
        }

        final Message mediaCaption = isOutgoingMediaPreparing ? null : getMediaCaption(message);
        if (!isOutgoingMediaPreparing) {
            displayMediaCaption(viewHolder, mediaCaption, bubbleColor);
        }

        if (mediaCaption != null) {
            final View.OnLongClickListener mediaLongClickListener =
                    messageActionLongClickListener(message);

            final View.OnClickListener mediaCaptionClickListener =
                    v -> {
                        if (messageClickListener != null) {
                            messageClickListener.onMessageClick(mediaCaption);
                        }
                    };

            if (viewHolder.mediaFooter != null) {
                viewHolder.mediaFooter.setOnClickListener(mediaCaptionClickListener);
                viewHolder.mediaFooter.setOnLongClickListener(mediaLongClickListener);
            }

            if (viewHolder.mediaCaption != null) {
                viewHolder.mediaCaption.setOnClickListener(mediaCaptionClickListener);
                viewHolder.mediaCaption.setOnLongClickListener(mediaLongClickListener);

                viewHolder.mediaCaption.setOnTouchListener(
                        new PlainTextBubbleTouchBridge(viewHolder.mediaCaption, selectionStatusProvider));
            }

            if (viewHolder.mediaCaptionDivider != null) {
                viewHolder.mediaCaptionDivider.setOnLongClickListener(mediaLongClickListener);
            }
        }

        if (viewHolder.messageStatusRow != null) {
            viewHolder.messageStatusRow.setOnLongClickListener(
                    messageActionLongClickListener(message));
        }

        final MessageVisualGroupResolver.Position visualGroupPosition =
                MessageVisualGroupResolver.resolve(
                        message,
                        findPreviousVisibleBubble(position),
                        findNextVisibleBubble(position));
        final boolean startsVisualGroup = visualGroupPosition.startsGroup();
        final boolean endsVisualGroup = visualGroupPosition.endsGroup();

        if (type == RECEIVED && viewHolder.contact_picture != null) {
            final boolean groupConversation = conversation.getMode() == Conversation.MODE_MULTI;
            configureIncomingBubbleEndSpace(viewHolder, groupConversation);
            if (groupConversation) {
                if (endsVisualGroup) {
                    AvatarWorkerTask.loadAvatar(message, viewHolder.contact_picture, R.dimen.avatar);
                }
                viewHolder.contact_picture.setVisibility(
                        endsVisualGroup ? View.VISIBLE : View.INVISIBLE);
                viewHolder.contact_picture.setAlpha(1.0f);
                viewHolder.contact_picture.setOnClickListener(v -> {
                    if (MessageAdapter.this.mOnContactPictureClickedListener != null) {
                        MessageAdapter.this.mOnContactPictureClickedListener.onContactPictureClicked(message);
                    }
                });
                viewHolder.contact_picture.setOnLongClickListener(v -> {
                    if (MessageAdapter.this.mOnContactPictureLongClickedListener != null) {
                        MessageAdapter.this.mOnContactPictureLongClickedListener
                                .onContactPictureLongClicked(v, message);
                        return true;
                    }
                    return false;
                });

                final String displayName = UIHelper.getMessageDisplayName(message);
                if (!mForceNames && startsVisualGroup && displayName != null && !displayName.isEmpty()) {
                    viewHolder.senderName.setText(displayName);
                    viewHolder.senderName.setVisibility(View.VISIBLE);
                } else {
                    viewHolder.senderName.setVisibility(View.GONE);
                }
            } else {
                viewHolder.contact_picture.setVisibility(View.GONE);
                viewHolder.senderName.setVisibility(View.GONE);
            }
        }

        final int bubble;
        if (type == RECEIVED) {
            bubble = resolveBubbleBackground(true, visualGroupPosition);
            if (isInValidSession || message.getEncryption() == Message.ENCRYPTION_NONE) {
                viewHolder.encryption.setVisibility(View.GONE);
            } else {
                viewHolder.encryption.setVisibility(View.VISIBLE);
                if (omemoEncryption && !message.isTrusted()) {
                    viewHolder.encryption.setText(R.string.not_trusted);
                } else {
                    viewHolder.encryption.setText(
                            CryptoHelper.encryptionTypeToText(message.getEncryption()));
                }
            }
        } else {
            bubble = resolveBubbleBackground(false, visualGroupPosition);
        }

        ReactionRenderer.bind(
                viewHolder.reactions,
                message.getAggregatedReactions(),
                reactions -> sendReactions(message, reactions),
                emoji -> showDetailedReaction(message, emoji));

        final boolean hasMediaVisual =
                viewHolder.image.getVisibility() == View.VISIBLE
                        || viewHolder.mediaAlbum.getVisibility() == View.VISIBLE;
        final boolean sentStatus = message.getStatus() != Message.STATUS_RECEIVED;
        final boolean hasPreparingCaption =
                isOutgoingMediaPreparing
                        && outgoingPreparingCaption != null
                        && !outgoingPreparingCaption.trim().isEmpty();
        final boolean hasVisibleCaption = mediaCaption != null || hasPreparingCaption;
        final boolean statusOnMedia =
                shouldOverlayStatusOnMedia(hasMediaVisual, hasVisibleCaption, sentStatus);
        placeStatusRow(viewHolder, statusOnMedia, sentStatus);
        placeReactionRow(viewHolder, hasMediaVisual, sentStatus);
        ReactionRenderer.setMediaOverlayStyle(viewHolder.reactions, hasMediaVisual);

        final boolean hasMediaFooterSurface =
                hasMediaVisual && hasVisibleCaption;
        final int mediaVisualWidth = getMediaVisualWidth(viewHolder);
        MediaMessageChromeRenderer.bind(
                viewHolder.message_box,
                viewHolder.mediaFooter,
                hasMediaVisual,
                hasMediaFooterSurface,
                mediaVisualWidth,
                bubble,
                bubbleToColorStateList(viewHolder.mediaFooter, bubbleColor));
        if (hasMediaFooterSurface && mediaVisualWidth > 0) {
            // Media owns its width in both directions. Keep captions and metadata inside the
            // visual footprint so incoming portrait media and incoming galleries cannot widen
            // their backdrop beyond the image/album geometry.
            final LinearLayout.LayoutParams footerParams =
                    (LinearLayout.LayoutParams) viewHolder.mediaFooter.getLayoutParams();
            if (footerParams.width != mediaVisualWidth) {
                footerParams.width = mediaVisualWidth;
                viewHolder.mediaFooter.setLayoutParams(footerParams);
            }
            viewHolder.mediaFooter.setMinimumWidth(0);
        }

        final boolean standaloneEmoji =
                renderedEmoji
                        && viewHolder.reactions.getVisibility() != View.VISIBLE
                        && conversation.getMode() != Conversation.MODE_MULTI;
        if (standaloneEmoji) {
            viewHolder.message_box.setBackground(null);
            viewHolder.message_box.setBackgroundTintList(null);
        }

        displayStatus(
                viewHolder,
                message,
                type,
                bubbleColor,
                mediaAlbum,
                isOutgoingMediaPreparing);
        styleStatusRow(viewHolder, message, statusOnMedia || standaloneEmoji);
        ensureMetadataFitsBubble(viewHolder, hasMediaVisual, standaloneEmoji);
        resolveMediaOverlayCollision(viewHolder, hasMediaVisual, sentStatus);

        // 2dp rhythm inside one sender run; 6dp between visual groups.
        // Each row owns 1dp at the bottom. A group start owns 5dp at the top, otherwise 1dp.
        view.setPadding(
                view.getPaddingLeft(),
                dpToPx(startsVisualGroup ? 5 : 1),
                view.getPaddingRight(),
                dpToPx(1));

        if (viewHolder.message_box != null) {
            viewHolder.message_box.animate().cancel();
            viewHolder.message_box.setTranslationY(0f);
            if (revealCommittedIncomingAttachment) {
                // Preserve the existing media-commit reveal that already feels right on device.
                viewHolder.message_box.setAlpha(0.72f);
                viewHolder.message_box.animate().alpha(1f).setDuration(160L).start();
            } else if (revealNewMessage) {
                viewHolder.message_box.setAlpha(MESSAGE_ENTER_START_ALPHA);
                viewHolder.message_box.setTranslationY(dpToPx(MESSAGE_ENTER_OFFSET_DP));
                viewHolder.message_box
                        .animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(MESSAGE_ENTER_DURATION_MS)
                        .setInterpolator(MESSAGE_ENTER_EASING)
                        .start();
            } else {
                viewHolder.message_box.setAlpha(1f);
            }
        }

        return view;
    }

    @Nullable
    private Message findPreviousVisibleBubble(final int position) {
        for (int i = position - 1; i >= 0; i--) {
            final int candidateType = getItemViewType(i);
            if (candidateType == ALBUM_CONTINUATION || candidateType == CAPTION_CONTINUATION) {
                continue;
            }
            return candidateType == SENT || candidateType == RECEIVED ? getItem(i) : null;
        }
        return null;
    }

    @Nullable
    private Message findNextVisibleBubble(final int position) {
        for (int i = position + 1; i < getCount(); i++) {
            final int candidateType = getItemViewType(i);
            if (candidateType == ALBUM_CONTINUATION || candidateType == CAPTION_CONTINUATION) {
                continue;
            }
            return candidateType == SENT || candidateType == RECEIVED ? getItem(i) : null;
        }
        return null;
    }

    private void configureIncomingBubbleEndSpace(
            final ViewHolder viewHolder, final boolean groupConversation) {
        if (viewHolder.message_box == null) {
            return;
        }
        final ViewGroup.LayoutParams rawParams = viewHolder.message_box.getLayoutParams();
        if (!(rawParams instanceof RelativeLayout.LayoutParams params)) {
            return;
        }
        final int desiredEndMargin = dpToPx(groupConversation ? 0 : 44);
        if (params.getMarginEnd() != desiredEndMargin) {
            params.setMarginEnd(desiredEndMargin);
            viewHolder.message_box.setLayoutParams(params);
        }
    }

    @DrawableRes
    private static int resolveBubbleBackground(
            final boolean received,
            @NonNull final MessageVisualGroupResolver.Position position) {
        if (received) {
            return switch (position) {
                case FIRST -> R.drawable.background_message_bubble_received_first;
                case MIDDLE -> R.drawable.background_message_bubble_received_middle;
                case LAST -> R.drawable.background_message_bubble_received_last;
                case SINGLE -> R.drawable.background_message_bubble_received;
            };
        }
        return switch (position) {
            case FIRST -> R.drawable.background_message_bubble_sent_first;
            case MIDDLE -> R.drawable.background_message_bubble_sent_middle;
            case LAST -> R.drawable.background_message_bubble_sent_last;
            case SINGLE -> R.drawable.background_message_bubble_sent;
        };
    }

    private static int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    private boolean showDetailedReaction(final Message message, final String emoji) {
        final var c = message.getConversation();
        if (c instanceof Conversation conversation && c.getMode() == Conversational.MODE_MULTI) {
            final var reactions =
                    Collections2.filter(
                            message.getReactionsNew(), r -> r.normalizedReaction().equals(emoji));
            final var mucOptions = conversation.getMucOptions();
            final var users = mucOptions.findUsers(reactions);
            if (users.isEmpty()) {
                return true;
            }
            final MaterialAlertDialogBuilder dialogBuilder =
                    new MaterialAlertDialogBuilder(activity);
            dialogBuilder.setTitle(emoji);
            dialogBuilder.setMessage(UIHelper.concatNames(users));
            dialogBuilder.create().show();
            return true;
        } else {
            return false;
        }
    }

    private void sendReactions(final Message message, final Collection<String> reactions) {
        if (activity.xmppConnectionService.sendReactions(message, reactions)) {
            return;
        }
        Toast.makeText(activity, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show();
    }

    public FileBackend getFileBackend() {
        return activity.xmppConnectionService.getFileBackend();
    }

    private static void setBackgroundTint(final LinearLayout view, final BubbleColor bubbleColor) {
        view.setBackgroundTintList(bubbleToColorStateList(view, bubbleColor));
    }

    private static ColorStateList bubbleToColorStateList(
            final View view, final BubbleColor bubbleColor) {
        final @AttrRes int colorAttributeResId =
                switch (bubbleColor) {
                    case SURFACE -> R.attr.neoColorMessageIncomingSurface;
                    case SURFACE_HIGH -> R.attr.neoColorMessageIncomingElevatedSurface;
                    case PRIMARY -> com.google.android.material.R.attr.colorPrimaryContainer;
                    case SECONDARY -> com.google.android.material.R.attr.colorSecondaryContainer;
                    case TERTIARY -> com.google.android.material.R.attr.colorTertiaryContainer;
                    case WARNING -> com.google.android.material.R.attr.colorErrorContainer;
                };
        return ColorStateList.valueOf(MaterialColors.getColor(view, colorAttributeResId));
    }

    public static void setImageTint(final ImageView imageView, final BubbleColor bubbleColor) {
        ImageViewCompat.setImageTintList(
                imageView, bubbleToOnSurfaceColorStateList(imageView, bubbleColor));
    }

    private static void setReadStatusTint(
            final ImageView indicator, final BubbleColor bubbleColor) {
        final int base = bubbleToOnSurfaceColor(indicator, bubbleColor);
        ImageViewCompat.setImageTintList(
                indicator,
                ColorStateList.valueOf(readStatusTonalColor(indicator, base)));
    }

    private static @ColorInt int readStatusTonalColor(
            final View view, final @ColorInt int baseColor) {
        final int primary =
                MaterialColors.getColor(
                        view, androidx.appcompat.R.attr.colorPrimary);
        return ColorUtils.blendARGB(baseColor, primary, 0.18f);
    }

    private static @ColorInt int calmMediaOverlayMetadataColor() {
        // The dark overlay supplies contrast. 78% white keeps metadata legible without becoming
        // the brightest element on the photo/video.
        return ColorUtils.setAlphaComponent(Color.WHITE, 199);
    }

    public static void setImageTintError(final ImageView imageView) {
        ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(
                        MaterialColors.getColor(imageView, androidx.appcompat.R.attr.colorError)));
    }

    public static void setTextColor(final TextView textView, final BubbleColor bubbleColor) {
        final var color = bubbleToOnSurfaceColor(textView, bubbleColor);
        textView.setTextColor(color);
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            textView.setLinkTextColor(
                    MaterialColors.getColor(textView, androidx.appcompat.R.attr.colorPrimary));
        } else {
            textView.setLinkTextColor(color);
        }
    }

    private static void setTextSize(final TextView textView, final float targetSp) {
        final float targetPx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        targetSp,
                        textView.getResources().getDisplayMetrics());

        if (Math.abs(textView.getTextSize() - targetPx) > 0.5f) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSp);
        }
    }

    private static @ColorInt int bubbleToOnSurfaceVariant(
            final View view, final BubbleColor bubbleColor) {
        final @AttrRes int colorAttributeResId;
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            colorAttributeResId = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
            colorAttributeResId = bubbleToOnSurface(bubbleColor);
        }
        return MaterialColors.getColor(view, colorAttributeResId);
    }

    private static @ColorInt int bubbleToOnSurfaceColor(
            final View view, final BubbleColor bubbleColor) {
        return MaterialColors.getColor(view, bubbleToOnSurface(bubbleColor));
    }

    public static ColorStateList bubbleToOnSurfaceColorStateList(
            final View view, final BubbleColor bubbleColor) {
        return ColorStateList.valueOf(bubbleToOnSurfaceColor(view, bubbleColor));
    }

    private static @AttrRes int bubbleToOnSurface(final BubbleColor bubbleColor) {
        return switch (bubbleColor) {
            case SURFACE, SURFACE_HIGH -> com.google.android.material.R.attr.colorOnSurface;
            case PRIMARY -> com.google.android.material.R.attr.colorOnPrimaryContainer;
            case SECONDARY -> com.google.android.material.R.attr.colorOnSecondaryContainer;
            case TERTIARY -> com.google.android.material.R.attr.colorOnTertiaryContainer;
            case WARNING -> com.google.android.material.R.attr.colorOnErrorContainer;
        };
    }

    public enum BubbleColor {
        SURFACE,
        SURFACE_HIGH,
        PRIMARY,
        SECONDARY,
        TERTIARY,
        WARNING;

        private static final Collection<BubbleColor> SURFACES =
                Arrays.asList(BubbleColor.SURFACE, BubbleColor.SURFACE_HIGH);
    }

    private static class BubbleDesign {
        public final boolean colorfulChatBubbles;
        public final float messageTextSizeSp;

        private BubbleDesign(
                final boolean colorfulChatBubbles,
                final float messageTextSizeSp
        ) {
            this.colorfulChatBubbles = colorfulChatBubbles;
            this.messageTextSizeSp = messageTextSizeSp;
        }
    }

    public void stopAudioPlayer() {
        audioPlayer.stop();
    }

    public void unregisterListenerInAudioPlayer() {
        audioPlayer.unregisterListener();
    }

    public void startStopPending() {
        audioPlayer.startStopPending();
    }

    public void openDownloadable(final Message message) {
        SecureMessageMediaUiBridge.openOrFallback(
                activity,
                message,
                () -> openLegacyDownloadable(message));
    }

    private void openLegacyDownloadable(final Message message) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ConversationFragment.registerPendingMessage(activity, message);
            ActivityCompat.requestPermissions(
                    activity,
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    ConversationsActivity.REQUEST_OPEN_MESSAGE);
            return;
        }
        final DownloadableFile file =
                activity.xmppConnectionService.getFileBackend().getFile(message);
        ViewUtil.view(activity, file);
    }

    private void showLocation(Message message) {
        for (Intent intent : GeoHelper.createGeoIntentsFromMessage(activity, message)) {
            if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                getContext().startActivity(intent);
                return;
            }
        }
        Toast.makeText(activity, R.string.no_application_found_to_display_location, Toast.LENGTH_SHORT).show();
    }

    @ColorInt
    private int getOrCalculatePrimaryColor() {
        if (primaryColor != -1) return primaryColor;

        TypedValue typedValue = new TypedValue();
        getContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true);
        primaryColor = typedValue.data;

        return primaryColor;
    }

    public boolean refreshMessageHyphenationPreference() {
        final boolean enabled = new AppSettings(activity).isMessageHyphenationEnabled();
        if (enabled == this.messageHyphenationEnabled) {
            return false;
        }
        this.messageHyphenationEnabled = enabled;
        return true;
    }

    public void updatePreferences() {
        final AppSettings appSettings = new AppSettings(activity);
        this.messageHyphenationEnabled = appSettings.isMessageHyphenationEnabled();
        try {
            final AppearanceState appearanceState = AppearanceSnapshotReader.INSTANCE.from(activity);
            final boolean colorfulChatBubbles =
                    appearanceState.getSettings().getColorfulChatBubbles();
            final float messageTextSizeSp = appearanceState.getMessageTextSizeSp();
            this.bubbleDesign = new BubbleDesign(colorfulChatBubbles, messageTextSizeSp);
            verifyLegacyAppearanceEquivalence(appearanceState);
        } catch (final RuntimeException e) {
            this.bubbleDesign =
                    new BubbleDesign(
                            appSettings.isColorfulChatBubbles(),
                            appSettings.isLargeFont() ? 15f : TypographyPolicy.DEFAULT_MESSAGE_TEXT_SP);
            if (BuildConfig.DEBUG) {
                Log.w(Config.LOGTAG, "appearance snapshot unavailable; using legacy fallback", e);
            }
        }
    }

    private void verifyLegacyAppearanceEquivalence(final AppearanceState appearanceState) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        try {
            final AppSettings appSettings = new AppSettings(activity);
            final boolean legacyColorfulChatBubbles = appSettings.isColorfulChatBubbles();
            final boolean legacyLargeFont = appSettings.isLargeFont();
            final boolean stateLargeFont =
                    appearanceState.getMessageTextSizeSp()
                            > TypographyPolicy.DEFAULT_MESSAGE_TEXT_SP;
            final boolean colorfulChatBubblesMismatch =
                    legacyColorfulChatBubbles
                            != appearanceState.getSettings().getColorfulChatBubbles();
            final boolean largeFontMismatch = legacyLargeFont != stateLargeFont;
            if (colorfulChatBubblesMismatch || largeFontMismatch) {
                Log.w(
                        Config.LOGTAG,
                        "appearance consumer mismatch: colorfulChatBubbles legacy="
                                + legacyColorfulChatBubbles
                                + " state="
                                + appearanceState.getSettings().getColorfulChatBubbles()
                                + "; largeFont legacy="
                                + legacyLargeFont
                                + " state="
                                + stateLargeFont);
            }
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "appearance legacy equivalence unavailable", e);
        }
    }


    public void setHighlightedTerm(List<String> terms) {
        this.highlightedTerm = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    public interface OnContactPictureClicked {
        void onContactPictureClicked(Message message);
    }

    public interface OnContactPictureLongClicked {
        void onContactPictureLongClicked(View v, Message message);
    }

    public interface MessageEmptyPartClickListener {
        void onMessageEmptyPartClick(Message message);
        void onMessageEmptyPartLongClick(Message message);
    }
    public interface MessageClickListener {

        void onMessageClick(Message message);

    }
    public interface SelectionStatusProvider {
        boolean isSelected(Message message);
        boolean isSomethingSelected();
    }

    public interface MessageBoxSwipedListener {
        void onMessageBoxReleasedAfterSwipe(Message message);
        void onMessageBoxSwipedEnough();
    }

    public interface ReplyClickListener {
        void onReplyClick(Message message);
    }

    public interface OnDateSeparatorClickListener {
        void onDateSeparatorClick(long timestamp);
    }

    private static class ReplyClickableSpan extends ClickableSpan {
        private WeakReference<ReplyClickListener> replyClickListener;
        private WeakReference<Message> message;

        public ReplyClickableSpan(WeakReference<ReplyClickListener> replyClickListener, WeakReference<Message> message) {
            this.replyClickListener = replyClickListener;
            this.message = message;
        }

        @Override
        public void onClick(@NonNull View widget) {
            ReplyClickListener listener = replyClickListener.get();
            Message message = this.message.get();
            if (listener != null && message != null) {
                listener.onReplyClick(message);
            }
        }

        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            ds.setUnderlineText(false);
        }
    }

    private static class ViewHolder {
        public View root;

        public MaterialButton load_more_messages;
        public ImageView edit_indicator;
        public RelativeLayout audioPlayer;
        protected LinearLayout message_box;
        protected MaterialButton download_button;
        protected View fileCard;
        protected ImageView fileIcon;
        protected TextView fileName;
        protected TextView fileSize;
        protected ImageView image;
        protected GridLayout mediaAlbum;
        protected LinearLayout mediaFooter;
        protected ViewGroup mediaVisualContainer;
        protected LinearLayout messageMetaRow;
        protected LinearLayout messageStatusRow;
        protected View mediaCaptionDivider;
        protected TextView mediaCaption;
        protected ImageView indicator;
        protected ImageView indicatorReceived;
        protected TextView time;
        protected TextView messageBody;
        protected ProgressBar busyIndicator;
        protected ImageView contact_picture;
        protected TextView senderName;
        protected TextView status_message;
        protected TextView callMetadata;
        protected TextView encryption;
        protected ReactionFlowLayout reactions;
        protected Runnable protectedTextRevealRunnable;
        protected String protectedTextRevealMessageUuid;
        protected int protectedTextRevealFrame;

        protected View nonTextReplyContent;

        protected View clicksInterceptor;

        int position;
    }
}
