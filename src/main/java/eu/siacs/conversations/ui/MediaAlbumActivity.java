package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Bundle;
import android.app.PendingIntent;
import android.net.Uri;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaReadCache;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaThumbnailReader;
import eu.siacs.conversations.storage.secure.SecureContentMetadata;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway;
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.actions.MessageAction;
import eu.siacs.conversations.ui.actions.MessageActionGroup;
import eu.siacs.conversations.ui.actions.MessageActionType;
import eu.siacs.conversations.ui.actions.reactions.QuickReaction;
import eu.siacs.conversations.ui.actions.reactions.QuickReactionResolver;
import eu.siacs.conversations.ui.actions.ui.MaterialMessageActionSheet;
import eu.siacs.conversations.ui.actions.ui.MessageActionSheet;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.utils.MimeUtils;

public class MediaAlbumActivity extends XmppActivity {

    private static final String EXTRA_CONVERSATION = "conversation";
    private static final String EXTRA_MESSAGES = "messages";
    private static final String EXTRA_INITIAL_POSITION = "initial_position";

    private final ArrayList<Message> messages = new ArrayList<>();
    private MediaPageAdapter pageAdapter;
    private ThumbnailAdapter thumbnailAdapter;
    private RecyclerView thumbnails;
    private ViewPager2 pager;
    private TextView counter;
    private FrameLayout chrome;
    private ImageButton backButton;
    private ImageButton actionsButton;
    private View topScrim;
    private View bottomScrim;
    private boolean chromeVisible = true;
    private boolean mediaZoomed;
    private boolean messageResolutionStarted;
    private final HashSet<String> viewerPrefetches = new HashSet<>();
    private final HashMap<String, ViewerSourcePolicy> viewerSourcePolicies = new HashMap<>();
    private final HashSet<AndroidSecureMessageMediaReadCache.Lease> activeVideoLeases =
            new HashSet<>();
    @Nullable private MediaPageAdapter.ViewHolder activeVideoHolder;

    enum ViewerSourcePolicy {
        SECURE,
        LEGACY,
        UNAVAILABLE
    }

    private static final class FitCenterVideoSurface extends SurfaceView {
        private int videoWidth;
        private int videoHeight;

        private FitCenterVideoSurface(@NonNull final Context context) {
            super(context);
        }

        private void setVideoSize(final int width, final int height) {
            if (width <= 0 || height <= 0) {
                return;
            }
            if (videoWidth == width && videoHeight == height) {
                return;
            }
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }

        private void clearVideoSize() {
            videoWidth = 0;
            videoHeight = 0;
            requestLayout();
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
            final int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
            final int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
            if (videoWidth <= 0
                    || videoHeight <= 0
                    || availableWidth <= 0
                    || availableHeight <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            final float scale =
                    Math.min(
                            availableWidth / (float) videoWidth,
                            availableHeight / (float) videoHeight);
            final int measuredWidth = Math.max(1, Math.round(videoWidth * scale));
            final int measuredHeight = Math.max(1, Math.round(videoHeight * scale));
            setMeasuredDimension(measuredWidth, measuredHeight);
        }
    }

    static ViewerSourcePolicy viewerSourcePolicy(
            final boolean secureRollout,
            final boolean secureRelationPresent,
            final boolean secureResolutionFailed) {
        if (!secureRollout) {
            return ViewerSourcePolicy.LEGACY;
        }
        if (secureResolutionFailed) {
            return ViewerSourcePolicy.UNAVAILABLE;
        }
        return secureRelationPresent ? ViewerSourcePolicy.SECURE : ViewerSourcePolicy.LEGACY;
    }

    private ViewerSourcePolicy resolveViewerSourcePolicy(@NonNull final Message message) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            return ViewerSourcePolicy.LEGACY;
        }
        synchronized (viewerSourcePolicies) {
            final ViewerSourcePolicy cached = viewerSourcePolicies.get(message.getUuid());
            if (cached != null) {
                return cached;
            }
        }
        try {
            final Conversations application = (Conversations) getApplication();
            final SecureContentStore store = application.getSecureContentStoreProvider().get();
            final SecureMessageMediaCoordinator coordinator =
                    new SecureMessageMediaCoordinator(
                            store, new SecureContentTransferGateway(store));
            final boolean secureRelationPresent =
                    coordinator.resolve(
                                    message.getConversation().getAccount().getUuid(),
                                    message.getUuid())
                            != null;
            final ViewerSourcePolicy resolved =
                    viewerSourcePolicy(true, secureRelationPresent, false);
            synchronized (viewerSourcePolicies) {
                viewerSourcePolicies.put(message.getUuid(), resolved);
            }
            return resolved;
        } catch (final Exception error) {
            // Resolution errors are ownership/integrity failures, not permission to expose a
            // legacy plaintext path. Do not cache the failure: a transient Store/provider error
            // may recover while the viewer remains open, but it must never trigger legacy fallback.
            return viewerSourcePolicy(true, false, true);
        }
    }

    public static void launch(
            final Context context, final List<Message> album, final int initialPosition) {
        if (album == null || album.isEmpty() || album.get(0).getConversation() == null) {
            return;
        }
        final ArrayList<String> messageUuids = new ArrayList<>();
        for (final Message message : album) {
            messageUuids.add(message.getUuid());
        }
        final Intent intent = new Intent(context, MediaAlbumActivity.class);
        intent.putExtra(EXTRA_CONVERSATION, album.get(0).getConversation().getUuid());
        intent.putStringArrayListExtra(EXTRA_MESSAGES, messageUuids);
        intent.putExtra(EXTRA_INITIAL_POSITION, initialPosition);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureViewerEdgeToEdge();

        final DismissibleLayout root = new DismissibleLayout(this);
        root.setBackgroundColor(Color.BLACK);
        final FrameLayout content = new FrameLayout(this);
        root.setDismissTarget(content);
        root.addView(
                content,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pager = new ViewPager2(this);
        pager.setOffscreenPageLimit(1);
        pageAdapter = new MediaPageAdapter();
        pager.setAdapter(pageAdapter);
        content.addView(
                pager,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        chrome = new FrameLayout(this);
        content.addView(
                chrome,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topScrim = new View(this);
        topScrim.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0xd9000000, 0x8f000000, 0x00000000}));
        final FrameLayout.LayoutParams topScrimParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(104), android.view.Gravity.TOP);
        chrome.addView(topScrim, topScrimParams);

        bottomScrim = new View(this);
        bottomScrim.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP,
                        new int[] {0xd9000000, 0x8f000000, 0x00000000}));
        final FrameLayout.LayoutParams bottomScrimParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(120), android.view.Gravity.BOTTOM);
        chrome.addView(bottomScrim, bottomScrimParams);

        backButton = createViewerIconButton(R.drawable.ic_arrow_back_24dp, R.string.back);
        backButton.setOnClickListener(view -> finish());
        final FrameLayout.LayoutParams backParams =
                new FrameLayout.LayoutParams(dp(48), dp(48));
        backParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        backParams.setMarginStart(dp(8));
        chrome.addView(backButton, backParams);

        counter = new TextView(this);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(14);
        counter.setGravity(android.view.Gravity.CENTER);
        counter.setPadding(dp(12), dp(6), dp(12), dp(6));
        counter.setBackground(roundedChromeBackground(0x78000000, 18));
        final FrameLayout.LayoutParams counterParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        counterParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        chrome.addView(counter, counterParams);

        actionsButton = createViewerIconButton(R.drawable.ic_more_horiz_24dp, R.string.media_actions);
        actionsButton.setOnClickListener(this::showMediaActions);
        final FrameLayout.LayoutParams actionParams =
                new FrameLayout.LayoutParams(dp(48), dp(48));
        actionParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        actionParams.setMarginEnd(dp(8));
        chrome.addView(actionsButton, actionParams);

        thumbnails = new RecyclerView(this);
        thumbnails.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        thumbnailAdapter = new ThumbnailAdapter();
        thumbnails.setAdapter(thumbnailAdapter);
        thumbnails.setOverScrollMode(View.OVER_SCROLL_NEVER);
        thumbnails.setPadding(dp(8), dp(8), dp(8), dp(8));
        thumbnails.setClipToPadding(false);
        thumbnails.setBackground(roundedChromeBackground(0xb0000000, 20));
        final FrameLayout.LayoutParams thumbnailParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(80), android.view.Gravity.BOTTOM);
        thumbnailParams.leftMargin = dp(12);
        thumbnailParams.rightMargin = dp(12);
        chrome.addView(thumbnails, thumbnailParams);

        pager.registerOnPageChangeCallback(
                new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(final int position) {
                        updateSelection(position);
                    }
                });

        setContentView(root);
        // BaseActivity#setContentView applies ordinary activity system-bar colors. Re-apply the
        // viewer contract afterwards so media actually renders behind transparent system bars.
        configureViewerEdgeToEdge();
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, insets) -> {
                    final Insets bars =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    applyViewerInsets(bars.top, bars.bottom);
                    return insets;
                });
        ViewCompat.requestApplyInsets(root);
    }

    private void configureViewerEdgeToEdge() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(Color.TRANSPARENT);
            final WindowManager.LayoutParams windowAttributes = getWindow().getAttributes();
            windowAttributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(windowAttributes);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        final WindowInsetsControllerCompat systemUi =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        systemUi.show(WindowInsetsCompat.Type.systemBars());
        systemUi.setAppearanceLightStatusBars(false);
        systemUi.setAppearanceLightNavigationBars(false);
    }

    @Override
    protected void onBackendConnected() {
        if (!messages.isEmpty() || messageResolutionStarted) {
            return;
        }
        final Intent intent = getIntent();
        final String conversationUuid =
                intent == null ? null : intent.getStringExtra(EXTRA_CONVERSATION);
        final ArrayList<String> messageUuids =
                intent == null ? null : intent.getStringArrayListExtra(EXTRA_MESSAGES);
        if (conversationUuid == null || messageUuids == null || messageUuids.isEmpty()) {
            finish();
            return;
        }
        final Conversation conversation =
                xmppConnectionService.findConversationByUuid(conversationUuid);
        if (conversation == null) {
            finish();
            return;
        }
        messageResolutionStarted = true;
        final int requestedInitialPosition =
                Math.max(
                        0,
                        Math.min(
                                intent.getIntExtra(EXTRA_INITIAL_POSITION, 0),
                                messageUuids.size() - 1));
        resolveViewerMessages(
                conversationUuid,
                conversation,
                messageUuids,
                requestedInitialPosition);
    }

    private void resolveViewerMessages(
            final String conversationUuid,
            final Conversation conversation,
            final List<String> messageUuids,
            final int requestedInitialPosition) {
        final Message[] resolved = new Message[messageUuids.size()];
        final AtomicInteger remaining = new AtomicInteger(messageUuids.size());
        for (int index = 0; index < messageUuids.size(); index++) {
            final int targetIndex = index;
            final String messageUuid = messageUuids.get(index);
            Message resident = conversation.findMessageWithFileAndUuid(messageUuid);
            if (resident == null) {
                resident = conversation.findMessageWithUuid(messageUuid);
            }
            if (resident != null) {
                resolved[targetIndex] = resident;
                completeViewerMessageResolution(
                        messageUuids,
                        resolved,
                        remaining,
                        requestedInitialPosition);
                continue;
            }
            xmppConnectionService.loadAttachmentMessage(
                    conversationUuid,
                    messageUuid,
                    message -> {
                        resolved[targetIndex] = message;
                        completeViewerMessageResolution(
                                messageUuids,
                                resolved,
                                remaining,
                                requestedInitialPosition);
                    });
        }
    }

    private void completeViewerMessageResolution(
            final List<String> requestedUuids,
            final Message[] resolved,
            final AtomicInteger remaining,
            final int requestedInitialPosition) {
        if (remaining.decrementAndGet() != 0) {
            return;
        }
        runOnUiThread(
                () -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    messages.clear();
                    final String selectedUuid =
                            requestedUuids.get(
                                    Math.max(
                                            0,
                                            Math.min(
                                                    requestedInitialPosition,
                                                    requestedUuids.size() - 1)));
                    int resolvedInitialPosition = RecyclerView.NO_POSITION;
                    for (final Message candidate : resolved) {
                        if (candidate == null) {
                            continue;
                        }
                        if (selectedUuid.equals(candidate.getUuid())) {
                            resolvedInitialPosition = messages.size();
                        }
                        messages.add(candidate);
                    }
                    if (messages.isEmpty()) {
                        finish();
                        return;
                    }
                    if (resolvedInitialPosition == RecyclerView.NO_POSITION) {
                        resolvedInitialPosition =
                                Math.max(
                                        0,
                                        Math.min(
                                                requestedInitialPosition,
                                                messages.size() - 1));
                    }
                    hydrateViewerPresentationMetadataThenBind(resolvedInitialPosition);
                });
    }

    private void hydrateViewerPresentationMetadataThenBind(final int initialPosition) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT
                || !messagesNeedSecurePresentationMetadata(messages)) {
            bindViewerMessages(initialPosition);
            return;
        }
        final ArrayList<Message> snapshot = new ArrayList<>(messages);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(final Void... ignored) {
                try {
                    final Conversations application = (Conversations) getApplication();
                    final SecureContentStore store =
                            application.getSecureContentStoreProvider().get();
                    final SecureMessageMediaCoordinator coordinator =
                            new SecureMessageMediaCoordinator(
                                    store, new SecureContentTransferGateway(store));
                    for (final Message message : snapshot) {
                        if (message == null
                                || message.isSecureMediaPresentationMetadataResolved()) {
                            continue;
                        }
                        try {
                            final SecureContentMetadata metadata =
                                    coordinator.resolveMetadata(
                                            message.getConversation().getAccount().getUuid(),
                                            message.getUuid());
                            if (metadata != null) {
                                final String presentationFileName =
                                        MimeUtils.resolvePresentationFileName(
                                                metadata.getFileName(), message.getBody());
                                message.setSecureMediaPresentationMetadata(
                                        metadata.getMimeType(),
                                        presentationFileName,
                                        metadata.getSizeBytes());
                                if (isVideoMessage(message)) {
                                    logVideoTelemetry(
                                            "metadata_hydrated",
                                            message,
                                            ViewerSourcePolicy.SECURE,
                                            null,
                                            null);
                                }
                                synchronized (viewerSourcePolicies) {
                                    viewerSourcePolicies.put(
                                            message.getUuid(), ViewerSourcePolicy.SECURE);
                                }
                            } else {
                                // resolveMetadata() returning null is the authoritative clean
                                // "no SCS relation" result used by the viewer source policy.
                                synchronized (viewerSourcePolicies) {
                                    viewerSourcePolicies.put(
                                            message.getUuid(), ViewerSourcePolicy.LEGACY);
                                }
                            }
                        } catch (final Exception ignoredPerMessage) {
                            // Source resolution later remains fail-closed for a broken secure
                            // relation. Metadata hydration must not let one bad page hide the rest
                            // of the viewer sequence.
                        }
                    }
                } catch (final Exception ignoredStoreFailure) {
                    // Store/provider initialization failure is handled by the ordinary source
                    // resolver when a page is opened.
                }
                return null;
            }

            @Override
            protected void onPostExecute(final Void ignored) {
                if (!isFinishing() && !isDestroyed()) {
                    bindViewerMessages(initialPosition);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    static boolean messagesNeedSecurePresentationMetadata(final List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (final Message message : messages) {
            if (message != null && !message.isSecureMediaPresentationMetadataResolved()) {
                return true;
            }
        }
        return false;
    }

    private void bindViewerMessages(final int initialPosition) {
        final boolean album = messages.size() > 1;
        counter.setVisibility(album ? View.VISIBLE : View.GONE);
        thumbnails.setVisibility(album ? View.VISIBLE : View.GONE);
        bottomScrim.setVisibility(album ? View.VISIBLE : View.GONE);
        pageAdapter.notifyDataSetChanged();
        thumbnailAdapter.notifyDataSetChanged();
        pager.setCurrentItem(initialPosition, false);
        updateSelection(initialPosition);
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onStop() {
        if (activeVideoHolder != null) {
            activeVideoHolder.releaseVideo();
            activeVideoHolder = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (activeVideoHolder != null) {
            activeVideoHolder.releaseVideo();
            activeVideoHolder = null;
        }
        synchronized (activeVideoLeases) {
            for (final AndroidSecureMessageMediaReadCache.Lease lease : activeVideoLeases) {
                try {
                    lease.close();
                } catch (final Exception ignored) {
                }
            }
            activeVideoLeases.clear();
        }
        super.onDestroy();
    }

    private ImageButton createViewerIconButton(
            final int iconRes, final int contentDescriptionRes) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        final GradientDrawable fill = roundedChromeBackground(0x70000000, 24);
        final GradientDrawable mask = roundedChromeBackground(Color.WHITE, 24);
        button.setBackground(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x36ffffff),
                        fill,
                        mask));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(getString(contentDescriptionRes));
        return button;
    }

    private GradientDrawable roundedChromeBackground(final int color, final int radiusDp) {
        final GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private void applyViewerInsets(final int topInset, final int bottomInset) {
        final int topControlMargin = topInset + dp(8);

        final FrameLayout.LayoutParams backParams =
                (FrameLayout.LayoutParams) backButton.getLayoutParams();
        backParams.topMargin = topControlMargin;
        backButton.setLayoutParams(backParams);

        final FrameLayout.LayoutParams actionParams =
                (FrameLayout.LayoutParams) actionsButton.getLayoutParams();
        actionParams.topMargin = topControlMargin;
        actionsButton.setLayoutParams(actionParams);

        final FrameLayout.LayoutParams counterParams =
                (FrameLayout.LayoutParams) counter.getLayoutParams();
        counterParams.topMargin = topInset + dp(16);
        counter.setLayoutParams(counterParams);

        final FrameLayout.LayoutParams topParams =
                (FrameLayout.LayoutParams) topScrim.getLayoutParams();
        topParams.height = topInset + dp(80);
        topScrim.setLayoutParams(topParams);

        final FrameLayout.LayoutParams thumbnailParams =
                (FrameLayout.LayoutParams) thumbnails.getLayoutParams();
        thumbnailParams.bottomMargin = bottomInset + dp(12);
        thumbnails.setLayoutParams(thumbnailParams);

        final FrameLayout.LayoutParams bottomParams =
                (FrameLayout.LayoutParams) bottomScrim.getLayoutParams();
        bottomParams.height = bottomInset + dp(108);
        bottomScrim.setLayoutParams(bottomParams);
    }

    private void updateSelection(final int position) {
        if (messages.isEmpty() || position < 0 || position >= messages.size()) {
            return;
        }
        mediaZoomed = false;
        pager.setUserInputEnabled(true);
        if (activeVideoHolder != null
                && activeVideoHolder.boundMessageUuid != null
                && !messages.get(position).getUuid().equals(activeVideoHolder.boundMessageUuid)) {
            activeVideoHolder.releaseVideo();
            activeVideoHolder = null;
        }
        counter.setText(
                String.format(Locale.getDefault(), "%d / %d", position + 1, messages.size()));
        thumbnailAdapter.setSelected(position);
        thumbnails.smoothScrollToPosition(position);
    }

    private void toggleChrome() {
        if (chromeVisible) {
            chrome.animate()
                    .alpha(0f)
                    .setDuration(160)
                    .withEndAction(() -> chrome.setVisibility(View.INVISIBLE))
                    .start();
        } else {
            chrome.setVisibility(View.VISIBLE);
            chrome.setAlpha(0f);
            chrome.animate().alpha(1f).setDuration(160).start();
        }
        chromeVisible = !chromeVisible;
    }

    private void showMediaActions(final View anchor) {
        final Message message = getCurrentMessage();
        if (message == null) {
            return;
        }
        final List<MessageAction> actions = new ArrayList<>();
        actions.add(
                new MessageAction(
                        MessageActionType.SAVE_TO_GALLERY,
                        R.string.save_to_gallery,
                        R.drawable.ic_photo_24dp,
                        MessageActionGroup.CONTENT,
                        false));
        actions.add(
                new MessageAction(
                        MessageActionType.SHARE,
                        R.string.media_action_share,
                        R.drawable.ic_share_24dp,
                        MessageActionGroup.PRIMARY,
                        false));
        actions.add(
                new MessageAction(
                        MessageActionType.INFO,
                        R.string.media_action_info,
                        R.drawable.ic_description_24dp,
                        MessageActionGroup.ORGANIZATION,
                        false));
        if (isRotatable(message)) {
            actions.add(
                    new MessageAction(
                            MessageActionType.MORE,
                            R.string.media_action_rotate_right,
                            R.drawable.ic_rotate_right_vector,
                            MessageActionGroup.ORGANIZATION,
                            false));
        }

        final MaterialMessageActionSheet sheet = new MaterialMessageActionSheet(this);
        sheet.show(
                actions,
                new QuickReactionResolver().resolve(),
                new MessageActionSheet.Listener() {
                    @Override
                    public void onActionSelected(final MessageAction action) {
                        switch (action.getType()) {
                            case SAVE_TO_GALLERY:
                                saveCurrentMedia(message);
                                break;
                            case SHARE:
                                ShareUtil.share(MediaAlbumActivity.this, message);
                                break;
                            case INFO:
                                showMediaInfo(message);
                                break;
                            case MORE:
                                pageAdapter.rotate(pager.getCurrentItem());
                                Toast.makeText(
                                                MediaAlbumActivity.this,
                                                R.string.media_preview_rotated,
                                                Toast.LENGTH_SHORT)
                                        .show();
                                break;
                            default:
                                break;
                        }
                    }

                    @Override
                    public void onQuickReactionSelected(final QuickReaction reaction) {
                        final Message reactionTarget = getReactionTarget();
                        if (reactionTarget == null) {
                            return;
                        }
                        final var reactions =
                                Reaction.toggle(
                                        reactionTarget.getAggregatedReactions().ourReactions,
                                        reaction.getEmoji());
                        if (xmppConnectionService.sendReactions(reactionTarget, reactions)) {
                            return;
                        }
                        Toast.makeText(
                                        MediaAlbumActivity.this,
                                        R.string.could_not_add_reaction,
                                        Toast.LENGTH_LONG)
                                .show();
                    }

                    @Override
                    public void onMoreReactionsSelected() {
                        final Message reactionTarget = getReactionTarget();
                        if (reactionTarget == null) {
                            return;
                        }
                        final Intent intent =
                                new Intent(MediaAlbumActivity.this, AddReactionActivity.class);
                        intent.putExtra(
                                "conversation", reactionTarget.getConversation().getUuid());
                        intent.putExtra("message", reactionTarget.getUuid());
                        startActivity(intent);
                    }
                });
    }

    private void saveCurrentMedia(final Message message) {
        xmppConnectionService.copyMediaToGallery(
                message,
                new UiCallback<>() {
                    @Override
                    public void success(final Integer ignored) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        MediaAlbumActivity.this,
                                                        R.string.save_to_gallery_success,
                                                        Toast.LENGTH_SHORT)
                                                .show());
                    }

                    @Override
                    public void error(final int errorCode, final Integer errorResId) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        MediaAlbumActivity.this,
                                                        errorResId,
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }

                    @Override
                    public void userInputRequired(
                            final PendingIntent pendingIntent, final Integer ignored) {}
                });
    }

    private void showMediaInfo(final Message message) {
        final Message.FileParams params = message.getFileParams();
        final StringBuilder info = new StringBuilder();
        appendInfo(info, R.string.media_info_name, getMediaDisplayName(message));
        if (params.width > 0 && params.height > 0) {
            appendInfo(
                    info,
                    R.string.media_info_resolution,
                    String.format(Locale.getDefault(), "%d × %d", params.width, params.height));
        }
        final Long size = resolveMediaSize(message, params);
        if (size != null && size > 0) {
            appendInfo(info, R.string.media_info_size, Formatter.formatFileSize(this, size));
        }
        if (params.runtime > 0) {
            appendInfo(info, R.string.media_info_duration, DateUtils.formatElapsedTime(params.runtime));
        }
        appendInfo(
                info,
                R.string.media_info_date,
                DateUtils.formatDateTime(
                        this,
                        message.getTimeSent(),
                        DateUtils.FORMAT_SHOW_DATE
                                | DateUtils.FORMAT_SHOW_TIME
                                | DateUtils.FORMAT_SHOW_YEAR
                                | DateUtils.FORMAT_ABBREV_MONTH));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.media_info_title)
                .setMessage(info)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String getMediaDisplayName(final Message message) {
        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            final String secureName = message.getSecureMediaFileName();
            if (secureName != null && !secureName.isEmpty()) {
                return secureName;
            }
        }
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            final String localPath = message.getRelativeFilePath();
            if (localPath != null && !localPath.isEmpty()) {
                final String localName = new File(localPath).getName();
                if (!localName.isEmpty()) {
                    return localName;
                }
            }
        }
        final String remoteUrl = message.getFileParams().url;
        if (remoteUrl != null && !remoteUrl.isEmpty()) {
            try {
                final String segment = Uri.parse(remoteUrl).getLastPathSegment();
                if (segment != null && !segment.isEmpty()) {
                    final String decoded = Uri.decode(segment);
                    if (!decoded.isEmpty()) {
                        return decoded;
                    }
                }
            } catch (final Exception ignored) {
            }
        }
        return message.getUuid();
    }

    private Long resolveMediaSize(
            final Message message, final Message.FileParams params) {
        if (params.size != null && params.size > 0) {
            return params.size;
        }
        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            return null;
        }
        final String localPath = message.getRelativeFilePath();
        if (localPath != null && !localPath.isEmpty()) {
            final File legacyFile = new File(localPath);
            if (legacyFile.isFile() && legacyFile.length() > 0) {
                return legacyFile.length();
            }
        }
        return null;
    }

    private void appendInfo(final StringBuilder target, final int label, final String value) {
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(getString(label)).append(": ").append(value);
    }

    private Message getCurrentMessage() {
        final int position = pager.getCurrentItem();
        return position >= 0 && position < messages.size() ? messages.get(position) : null;
    }

    @Nullable
    private Message getReactionTarget() {
        if (messages.isEmpty()) {
            return null;
        }
        // The chat renders one reaction row for the whole gallery. Keep viewer reactions on the
        // same anchor so swiping to photo 2+ cannot create hidden child-message reactions.
        return messages.size() > 1 ? messages.get(0) : getCurrentMessage();
    }

    private boolean isRotatable(final Message message) {
        final String mime = message.getMimeType();
        return mime != null && mime.startsWith("image/") && !"image/gif".equals(mime);
    }

    private static boolean isVideoMessage(final Message message) {
        final String mime = message.getMimeType();
        return mime != null && mime.startsWith("video/");
    }

    private static String videoLogValue(@Nullable final String value) {
        return value == null ? "-" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private void logVideoTelemetry(
            @NonNull final String event,
            @NonNull final Message message,
            @Nullable final ViewerSourcePolicy sourcePolicy,
            @Nullable final Uri uri,
            @Nullable final String detail) {
        String resolverMime = null;
        if (uri != null) {
            try {
                resolverMime = getContentResolver().getType(uri);
            } catch (final Exception error) {
                resolverMime = "error:" + error.getClass().getSimpleName();
            }
        }
        Log.i(
                Config.LOGTAG,
                "video-viewer event="
                        + event
                        + " message="
                        + message.getUuid()
                        + " mime="
                        + videoLogValue(message.getSecureMediaMimeType())
                        + " secureFileName="
                        + videoLogValue(message.getSecureMediaFileName())
                        + " effectiveMime="
                        + videoLogValue(message.getMimeType())
                        + " sourcePolicy="
                        + (sourcePolicy == null ? "-" : sourcePolicy.name())
                        + " uriScheme="
                        + (uri == null ? "-" : videoLogValue(uri.getScheme()))
                        + " resolverMime="
                        + videoLogValue(resolverMime)
                        + (detail == null ? "" : " " + detail));
    }

    private void releaseVideoLease(
            @Nullable final AndroidSecureMessageMediaReadCache.Lease lease) {
        if (lease == null) {
            return;
        }
        synchronized (activeVideoLeases) {
            activeVideoLeases.remove(lease);
        }
        try {
            lease.close();
        } catch (final Exception ignored) {
        }
    }

    private void loadBitmap(
            final Message message, final ImageView imageView, final int size, final boolean crop) {
        final String variant = crop ? "album-thumbnail-square" : "viewer-fit";
        imageView.setTag(message.getUuid());
        final Bitmap cached = xmppConnectionService.getCachedMessageBitmap(message, size, variant);
        if (cached != null) {
            renderBitmap(message, imageView, cached, crop);
            if (!crop) {
                prefetchAdjacentViewerPages(size);
            }
            return;
        }
        if (!crop) {
            final Bitmap chatPreview =
                    xmppConnectionService.getCachedMessageBitmap(message, size, "chat-fit");
            if (chatPreview != null) {
                // A verified chat bitmap is safe to show immediately. The worker below still
                // loads viewer-fit and replaces it only when this ImageView remains bound.
                renderBitmap(message, imageView, chatPreview, false);
                prefetchAdjacentViewerPages(size);
            } else {
                imageView.setImageDrawable(null);
                imageView.setBackgroundColor(0xff111111);
            }
        } else {
            imageView.setImageDrawable(null);
            imageView.setBackgroundColor(0xff111111);
        }
        new AlbumBitmapTask(imageView, size, crop, variant)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, message);
    }

    private void renderBitmap(
            final Message message, final ImageView imageView, final Bitmap bitmap, final boolean crop) {
        imageView.setImageBitmap(bitmap);
        imageView.setBackgroundColor(Color.TRANSPARENT);
        imageView.setScaleType(crop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (imageView instanceof PhotoView) {
            ((PhotoView) imageView).setRotationTo(pageAdapter.rotationFor(message.getUuid()));
        }
    }

    static int[] viewerPrefetchPositions(final int currentPosition, final int itemCount) {
        if (currentPosition < 0 || currentPosition >= itemCount) {
            return new int[0];
        }
        if (currentPosition == 0) {
            return itemCount > 1 ? new int[] {1} : new int[0];
        }
        if (currentPosition == itemCount - 1) {
            return new int[] {currentPosition - 1};
        }
        return new int[] {currentPosition - 1, currentPosition + 1};
    }

    private void prefetchAdjacentViewerPages(final int size) {
        for (final int position : viewerPrefetchPositions(pager.getCurrentItem(), messages.size())) {
            final Message candidate = messages.get(position);
            final String key =
                    XmppConnectionService.bitmapCacheKey(
                            candidate.getConversation().getAccount().getUuid(),
                            candidate.getUuid(),
                            size,
                            "viewer-fit");
            if (xmppConnectionService.getCachedMessageBitmap(candidate, size, "viewer-fit") != null) {
                continue;
            }
            synchronized (viewerPrefetches) {
                if (!viewerPrefetches.add(key)) {
                    continue;
                }
            }
            new AlbumBitmapTask(null, size, false, "viewer-fit", key)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, candidate);
        }
    }

    private void finishViewerPrefetch(final String key) {
        if (key == null) {
            return;
        }
        synchronized (viewerPrefetches) {
            viewerPrefetches.remove(key);
        }
    }

    private int dp(final int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class MediaPageAdapter extends RecyclerView.Adapter<MediaPageAdapter.ViewHolder> {

        private final HashMap<String, Integer> rotations = new HashMap<>();

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final FrameLayout root = new FrameLayout(parent.getContext());
            root.setLayoutParams(
                    new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            final PhotoView image = new PhotoView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(null);
            root.addView(
                    image,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            final FitCenterVideoSurface video =
                    new FitCenterVideoSurface(parent.getContext());
            video.setVisibility(View.GONE);
            root.addView(
                    video,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.Gravity.CENTER));

            final View videoTapTarget = new View(parent.getContext());
            videoTapTarget.setVisibility(View.GONE);
            videoTapTarget.setClickable(true);
            videoTapTarget.setFocusable(true);
            videoTapTarget.setContentDescription(getString(R.string.video));
            final FrameLayout.LayoutParams videoTapParams =
                    new FrameLayout.LayoutParams(dp(112), dp(112), android.view.Gravity.CENTER);
            root.addView(videoTapTarget, videoTapParams);

            final CircularProgressIndicator progress =
                    new CircularProgressIndicator(parent.getContext());
            progress.setIndeterminate(true);
            progress.setIndicatorSize(dp(32));
            progress.setTrackThickness(dp(3));
            progress.setVisibility(View.GONE);
            final FrameLayout.LayoutParams progressParams =
                    new FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.CENTER);
            root.addView(progress, progressParams);

            final ViewHolder holder =
                    new ViewHolder(root, image, video, videoTapTarget, progress);
            videoTapTarget.setOnClickListener(view -> holder.handleCenterVideoAction());
            image.setOnScaleChangeListener(
                    (scaleFactor, focusX, focusY) -> {
                        if (holder.videoMessage) {
                            return;
                        }
                        final int holderPosition = holder.getBindingAdapterPosition();
                        if (holderPosition == RecyclerView.NO_POSITION
                                || holderPosition != pager.getCurrentItem()) {
                            return;
                        }
                        mediaZoomed = image.getScale() > image.getMinimumScale() + 0.01f;
                        pager.setUserInputEnabled(!mediaZoomed);
                    });
            image.setOnDoubleTapListener(
                    new GestureDetector.OnDoubleTapListener() {
                        @Override
                        public boolean onSingleTapConfirmed(final MotionEvent event) {
                            image.performClick();
                            return true;
                        }

                        @Override
                        public boolean onDoubleTap(final MotionEvent event) {
                            if (holder.videoMessage) {
                                return false;
                            }
                            final float minimum = image.getMinimumScale();
                            final float maximum = image.getMaximumScale();
                            if (image.getScale() > minimum + 0.01f) {
                                image.setScale(minimum, true);
                            } else {
                                image.setScale(
                                        maximum,
                                        event.getX(),
                                        event.getY(),
                                        true);
                            }
                            return true;
                        }

                        @Override
                        public boolean onDoubleTapEvent(final MotionEvent event) {
                            return false;
                        }
                    });
            image.setOnClickListener(
                    view -> {
                        if (holder.videoMessage) {
                            return;
                        }
                        if (!mediaZoomed) {
                            toggleChrome();
                        }
                    });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
            final Message message = messages.get(position);
            holder.releaseVideo();
            holder.boundMessageUuid = message.getUuid();
            holder.videoMessage = isVideoMessage(message);
            holder.boundMessage = message;
            holder.video.setVisibility(View.GONE);
            holder.image.setVisibility(View.VISIBLE);
            holder.videoTapTarget.setVisibility(holder.videoMessage ? View.VISIBLE : View.GONE);
            holder.videoTapTarget.setEnabled(holder.videoMessage);
            holder.progress.setVisibility(View.GONE);
            holder.updateVideoControlsPosition();
            holder.image.setZoomable(!holder.videoMessage);
            holder.image.setContentDescription(
                    holder.videoMessage ? getString(R.string.video) : null);
            holder.image.setScale(1.0f, false);
            holder.image.setRotationTo(rotationFor(message.getUuid()));

            final int longestSide =
                    Math.max(
                            getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
            loadBitmap(message, holder.image, longestSide, false);

            // Video posters already contain the standard play overlay from the thumbnail
            // pipeline. A confirmed tap on the poster starts playback in this same page.
        }

        private void startVideo(@NonNull final ViewHolder holder, @NonNull final Message message) {
            if (!message.getUuid().equals(holder.boundMessageUuid) || holder.videoPreparing) {
                return;
            }
            logVideoTelemetry("start_requested", message, null, null, null);
            holder.videoTapTarget.setEnabled(false);
            holder.videoPlayPause.setVisibility(View.GONE);
            if (activeVideoHolder != null && activeVideoHolder != holder) {
                activeVideoHolder.releaseVideo();
            }
            activeVideoHolder = holder;
            holder.videoPreparing = true;
            holder.progress.setVisibility(View.VISIBLE);
            new VideoSourceTask(holder)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, message);
        }

        @Override
        public void onViewRecycled(@NonNull final ViewHolder holder) {
            holder.releaseVideo();
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        private void rotate(final int position) {
            final String messageUuid = messages.get(position).getUuid();
            rotations.put(messageUuid, (rotationFor(messageUuid) + 90) % 360);
            notifyItemChanged(position);
        }

        private int rotationFor(final String messageUuid) {
            final Integer rotation = rotations.get(messageUuid);
            return rotation == null ? 0 : rotation;
        }

        private final class ViewHolder extends RecyclerView.ViewHolder {
            private final PhotoView image;
            private final FitCenterVideoSurface video;
            private final View videoTapTarget;
            private final CircularProgressIndicator progress;
            @Nullable private AndroidSecureMessageMediaReadCache.Lease videoLease;
            @Nullable private String boundMessageUuid;
            @Nullable private Message boundMessage;
            @Nullable private MediaPlayer videoPlayer;
            private final LinearLayout videoControls;
            private final MaterialButton videoPlayPause;
            private final SeekBar videoSeek;
            private final TextView videoTime;
            @Nullable private VideoSource pendingVideoSource;
            @Nullable private Message pendingVideoMessage;
            @Nullable private ViewerSourcePolicy pendingVideoSourcePolicy;
            private boolean videoMessage;
            private boolean videoPreparing;
            private boolean videoPlayerPreparing;
            private boolean videoSurfaceReady;
            private boolean videoSeekTracking;
            private boolean videoSizeResolvedFromCallback;
            private int resolvedVideoWidth;
            private int resolvedVideoHeight;
            private final Runnable videoProgressUpdater = this::updateVideoProgress;
            private final Runnable videoControlsAutoHide = this::autoHideVideoControls;

            private ViewHolder(
                    @NonNull final FrameLayout root,
                    @NonNull final PhotoView image,
                    @NonNull final FitCenterVideoSurface video,
                    @NonNull final View videoTapTarget,
                    @NonNull final CircularProgressIndicator progress) {
                super(root);
                this.image = image;
                this.video = video;
                this.videoTapTarget = videoTapTarget;
                this.progress = progress;

                final Context context = root.getContext();
                videoControls = new LinearLayout(context);
                videoControls.setOrientation(LinearLayout.HORIZONTAL);
                videoControls.setGravity(android.view.Gravity.CENTER_VERTICAL);
                videoControls.setPadding(dp(12), dp(6), dp(10), dp(6));
                videoControls.setBackground(roundedChromeBackground(0xe61f1f1f, 28));
                videoControls.setElevation(dp(6));
                videoControls.setVisibility(View.GONE);
                final FrameLayout.LayoutParams videoControlsParams =
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.BOTTOM);
                videoControlsParams.leftMargin = dp(16);
                videoControlsParams.rightMargin = dp(16);
                videoControlsParams.bottomMargin = dp(24);
                root.addView(videoControls, videoControlsParams);

                videoPlayPause = new MaterialButton(context);
                videoPlayPause.setMinWidth(0);
                videoPlayPause.setMinHeight(0);
                videoPlayPause.setPadding(0, 0, 0, 0);
                videoPlayPause.setInsetTop(0);
                videoPlayPause.setInsetBottom(0);
                videoPlayPause.setIconPadding(0);
                videoPlayPause.setIconResource(R.drawable.ic_media_pause_48dp);
                videoPlayPause.setIconSize(dp(48));
                videoPlayPause.setIconTint(null);
                videoPlayPause.setBackgroundTintList(
                        ColorStateList.valueOf(Color.TRANSPARENT));
                videoPlayPause.setStrokeWidth(0);
                videoPlayPause.setContentDescription(getString(R.string.pause_video));
                videoPlayPause.setVisibility(View.GONE);
                videoPlayPause.setElevation(dp(8));
                final FrameLayout.LayoutParams playPauseParams =
                        new FrameLayout.LayoutParams(
                                dp(48), dp(48), android.view.Gravity.CENTER);
                root.addView(videoPlayPause, playPauseParams);

                videoSeek = new SeekBar(context);
                videoSeek.setMax(1000);
                videoSeek.setProgress(0);
                videoSeek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
                videoSeek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
                videoSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55ffffff));
                final LinearLayout.LayoutParams seekParams =
                        new LinearLayout.LayoutParams(0, dp(48), 1f);
                seekParams.rightMargin = dp(6);
                videoControls.addView(videoSeek, seekParams);

                videoTime = new TextView(context);
                videoTime.setTextColor(0xd9ffffff);
                videoTime.setTextSize(12);
                videoTime.setGravity(android.view.Gravity.CENTER);
                videoTime.setMinWidth(dp(86));
                videoTime.setText(formatVideoTime(0) + " / " + formatVideoTime(0));
                videoControls.addView(
                        videoTime,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));

                videoPlayPause.setOnClickListener(view -> handleCenterVideoAction());
                videoSeek.setOnSeekBarChangeListener(
                        new SeekBar.OnSeekBarChangeListener() {
                            @Override
                            public void onProgressChanged(
                                    final SeekBar seekBar,
                                    final int progressValue,
                                    final boolean fromUser) {
                                if (!fromUser) {
                                    return;
                                }
                                final MediaPlayer player = videoPlayer;
                                if (player == null) {
                                    return;
                                }
                                try {
                                    final int duration = Math.max(0, player.getDuration());
                                    final int preview =
                                            duration <= 0
                                                    ? 0
                                                    : (int)
                                                            ((duration
                                                                            * (long)
                                                                                    progressValue)
                                                                    / 1000L);
                                    videoTime.setText(
                                            formatVideoTime(preview)
                                                    + " / "
                                                    + formatVideoTime(duration));
                                } catch (final RuntimeException ignored) {
                                }
                            }

                            @Override
                            public void onStartTrackingTouch(final SeekBar seekBar) {
                                videoSeekTracking = true;
                                video.removeCallbacks(videoControlsAutoHide);
                                showVideoControls(false);
                            }

                            @Override
                            public void onStopTrackingTouch(final SeekBar seekBar) {
                                final MediaPlayer player = videoPlayer;
                                if (player != null) {
                                    try {
                                        final int duration = Math.max(0, player.getDuration());
                                        final int position =
                                                duration <= 0
                                                        ? 0
                                                        : (int)
                                                                ((duration
                                                                                * (long)
                                                                                        seekBar
                                                                                                .getProgress())
                                                                        / 1000L);
                                        player.seekTo(position);
                                    } catch (final RuntimeException ignored) {
                                    }
                                }
                                videoSeekTracking = false;
                                updateVideoProgress();
                                scheduleVideoControlsAutoHide();
                            }
                        });
                video.setOnClickListener(view -> toggleVideoControls());

                video.getHolder()
                        .addCallback(
                                new SurfaceHolder.Callback() {
                                    @Override
                                    public void surfaceCreated(
                                            @NonNull final SurfaceHolder surfaceHolder) {
                                        videoSurfaceReady = true;
                                        final MediaPlayer player = videoPlayer;
                                        if (player != null) {
                                            try {
                                                player.setDisplay(surfaceHolder);
                                            } catch (final RuntimeException ignored) {
                                            }
                                        } else {
                                            prepareVideoPlayerIfReady();
                                        }
                                    }

                                    @Override
                                    public void surfaceChanged(
                                            @NonNull final SurfaceHolder surfaceHolder,
                                            final int format,
                                            final int width,
                                            final int height) {}

                                    @Override
                                    public void surfaceDestroyed(
                                            @NonNull final SurfaceHolder surfaceHolder) {
                                        videoSurfaceReady = false;
                                        final MediaPlayer player = videoPlayer;
                                        if (player != null) {
                                            try {
                                                player.setDisplay(null);
                                            } catch (final RuntimeException ignored) {
                                            }
                                        }
                                    }
                                });
            }

            private void handleCenterVideoAction() {
                if (!videoMessage || boundMessage == null || videoPreparing) {
                    return;
                }
                if (videoPlayer == null) {
                    logVideoTelemetry("tap_target", boundMessage, null, null, null);
                    startVideo(this, boundMessage);
                    return;
                }
                toggleVideoPlayback();
            }

            private String formatVideoTime(final int milliseconds) {
                return DateUtils.formatElapsedTime(Math.max(0, milliseconds) / 1000L);
            }

            private boolean isVideoPlaying() {
                final MediaPlayer player = videoPlayer;
                if (player == null) {
                    return false;
                }
                try {
                    return player.isPlaying();
                } catch (final RuntimeException ignored) {
                    return false;
                }
            }

            private void updatePlayPauseButton() {
                if (isVideoPlaying()) {
                    videoPlayPause.setIconResource(R.drawable.ic_media_pause_48dp);
                    videoPlayPause.setContentDescription(getString(R.string.pause_video));
                } else {
                    videoPlayPause.setIconResource(R.drawable.ic_media_play_48dp);
                    videoPlayPause.setContentDescription(getString(R.string.play_video));
                }
            }

            private void toggleVideoPlayback() {
                final MediaPlayer player = videoPlayer;
                if (player == null) {
                    return;
                }
                try {
                    if (player.isPlaying()) {
                        player.pause();
                        updatePlayPauseButton();
                        showVideoControls(false);
                    } else {
                        player.start();
                        updatePlayPauseButton();
                        showVideoControls(true);
                        updateVideoProgress();
                    }
                } catch (final RuntimeException ignored) {
                }
            }

            private void toggleVideoControls() {
                if (videoControls.getVisibility() == View.VISIBLE
                        && videoControls.getAlpha() > 0.5f) {
                    if (isVideoPlaying()) {
                        hideVideoControls();
                    }
                } else {
                    showVideoControls(true);
                }
            }

            private void showVideoControls(final boolean autoHide) {
                video.removeCallbacks(videoControlsAutoHide);
                videoControls.animate().cancel();
                videoPlayPause.animate().cancel();
                if (videoControls.getVisibility() != View.VISIBLE) {
                    videoControls.setVisibility(View.VISIBLE);
                    videoControls.setAlpha(0f);
                    videoControls.setTranslationY(dp(8));
                }
                videoControls
                        .animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(140)
                        .start();
                if (videoPlayer != null) {
                    if (videoPlayPause.getVisibility() != View.VISIBLE) {
                        videoPlayPause.setVisibility(View.VISIBLE);
                        videoPlayPause.setAlpha(0f);
                        videoPlayPause.setScaleX(0.88f);
                        videoPlayPause.setScaleY(0.88f);
                    }
                    videoPlayPause
                            .animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(140)
                            .start();
                }
                updatePlayPauseButton();
                updateVideoProgress();
                if (autoHide) {
                    scheduleVideoControlsAutoHide();
                }
            }

            private void hideVideoControls() {
                if (videoSeekTracking) {
                    return;
                }
                video.removeCallbacks(videoControlsAutoHide);
                videoControls.animate().cancel();
                videoPlayPause.animate().cancel();
                videoControls
                        .animate()
                        .alpha(0f)
                        .translationY(dp(8))
                        .setDuration(140)
                        .withEndAction(
                                () -> {
                                    if (videoControls.getAlpha() <= 0.01f) {
                                        videoControls.setVisibility(View.GONE);
                                    }
                                })
                        .start();
                videoPlayPause
                        .animate()
                        .alpha(0f)
                        .scaleX(0.88f)
                        .scaleY(0.88f)
                        .setDuration(140)
                        .withEndAction(
                                () -> {
                                    if (videoPlayPause.getAlpha() <= 0.01f) {
                                        videoPlayPause.setVisibility(View.GONE);
                                    }
                                })
                        .start();
            }

            private void autoHideVideoControls() {
                if (!videoSeekTracking && isVideoPlaying()) {
                    hideVideoControls();
                }
            }

            private void scheduleVideoControlsAutoHide() {
                video.removeCallbacks(videoControlsAutoHide);
                if (!videoSeekTracking && isVideoPlaying()) {
                    video.postDelayed(videoControlsAutoHide, 2600L);
                }
            }

            private void updateVideoProgress() {
                video.removeCallbacks(videoProgressUpdater);
                final MediaPlayer player = videoPlayer;
                if (player == null) {
                    videoSeek.setProgress(0);
                    videoTime.setText(formatVideoTime(0) + " / " + formatVideoTime(0));
                    return;
                }
                try {
                    final int duration = Math.max(0, player.getDuration());
                    final int position = Math.max(0, player.getCurrentPosition());
                    if (!videoSeekTracking) {
                        videoSeek.setProgress(
                                duration <= 0
                                        ? 0
                                        : (int) Math.min(1000L, (position * 1000L) / duration));
                        videoTime.setText(
                                formatVideoTime(position) + " / " + formatVideoTime(duration));
                    }
                    if (player.isPlaying()) {
                        video.postDelayed(videoProgressUpdater, 250L);
                    }
                } catch (final RuntimeException ignored) {
                }
            }

            private void updateVideoControlsPosition() {
                final ViewGroup.LayoutParams raw = videoControls.getLayoutParams();
                if (!(raw instanceof FrameLayout.LayoutParams)) {
                    return;
                }
                final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
                final int desiredBottomMargin = dp(messages.size() > 1 ? 104 : 24);
                if (params.bottomMargin != desiredBottomMargin) {
                    params.bottomMargin = desiredBottomMargin;
                    videoControls.setLayoutParams(params);
                }
            }

            private void prepareVideo(
                    @NonNull final Message target,
                    @NonNull final VideoSource source,
                    @Nullable final ViewerSourcePolicy sourcePolicy) {
                pendingVideoMessage = target;
                pendingVideoSource = source;
                pendingVideoSourcePolicy = sourcePolicy;
                video.setVisibility(View.VISIBLE);
                video.requestFocus();
                final SurfaceHolder surfaceHolder = video.getHolder();
                videoSurfaceReady =
                        surfaceHolder.getSurface() != null
                                && surfaceHolder.getSurface().isValid();
                prepareVideoPlayerIfReady();
            }

            private void prepareVideoPlayerIfReady() {
                final Message target = pendingVideoMessage;
                final VideoSource source = pendingVideoSource;
                final ViewerSourcePolicy sourcePolicy = pendingVideoSourcePolicy;
                if (!videoSurfaceReady
                        || target == null
                        || source == null
                        || videoPlayer != null
                        || videoPlayerPreparing
                        || activeVideoHolder != this
                        || !target.getUuid().equals(boundMessageUuid)) {
                    return;
                }

                videoPlayerPreparing = true;
                MediaPlayer player = null;
                try {
                    player = new MediaPlayer();
                    player.setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build());
                    player.setScreenOnWhilePlaying(true);
                    player.setDisplay(video.getHolder());

                    try (AssetFileDescriptor descriptor =
                            getContentResolver().openAssetFileDescriptor(source.uri, "r")) {
                        if (descriptor == null) {
                            throw new IOException("Video source descriptor is unavailable");
                        }
                        final long declaredLength = descriptor.getDeclaredLength();
                        if (declaredLength < 0) {
                            player.setDataSource(descriptor.getFileDescriptor());
                        } else {
                            player.setDataSource(
                                    descriptor.getFileDescriptor(),
                                    descriptor.getStartOffset(),
                                    declaredLength);
                        }
                        logVideoTelemetry(
                                "fd_datasource_open",
                                target,
                                sourcePolicy,
                                source.uri,
                                "length=" + declaredLength);
                    }

                    final MediaPlayer preparedPlayer = player;
                    preparedPlayer.setOnVideoSizeChangedListener(
                            (mediaPlayer, width, height) -> {
                                if (activeVideoHolder != this
                                        || !target.getUuid().equals(boundMessageUuid)
                                        || width <= 0
                                        || height <= 0) {
                                    return;
                                }
                                videoSizeResolvedFromCallback = true;
                                resolvedVideoWidth = width;
                                resolvedVideoHeight = height;
                                logVideoTelemetry(
                                        "on_video_size_changed",
                                        target,
                                        sourcePolicy,
                                        source.uri,
                                        "size=" + width + "x" + height);
                                video.setVideoSize(width, height);
                            });
                    preparedPlayer.setOnPreparedListener(
                            mediaPlayer -> {
                                if (activeVideoHolder != this
                                        || !target.getUuid().equals(boundMessageUuid)) {
                                    releaseVideo();
                                    return;
                                }
                                logVideoTelemetry(
                                        "on_prepared", target, sourcePolicy, source.uri, null);
                                videoPlayerPreparing = false;
                                videoPreparing = false;
                                if (!videoSizeResolvedFromCallback) {
                                    final int preparedWidth = mediaPlayer.getVideoWidth();
                                    final int preparedHeight = mediaPlayer.getVideoHeight();
                                    if (preparedWidth > 0 && preparedHeight > 0) {
                                        resolvedVideoWidth = preparedWidth;
                                        resolvedVideoHeight = preparedHeight;
                                        logVideoTelemetry(
                                                "prepared_size_fallback",
                                                target,
                                                sourcePolicy,
                                                source.uri,
                                                "size="
                                                        + preparedWidth
                                                        + "x"
                                                        + preparedHeight);
                                        video.setVideoSize(preparedWidth, preparedHeight);
                                    }
                                } else if (resolvedVideoWidth > 0 && resolvedVideoHeight > 0) {
                                    // Some decoders dispatch the final display dimensions before
                                    // onPrepared. Do not overwrite them with getVideoWidth/Height,
                                    // which can still expose coded/pre-rotation dimensions here.
                                    video.setVideoSize(resolvedVideoWidth, resolvedVideoHeight);
                                }
                                videoTapTarget.setEnabled(true);
                                image.setVisibility(View.GONE);
                                progress.setVisibility(View.GONE);
                                updateVideoControlsPosition();
                                try {
                                    mediaPlayer.start();
                                    updatePlayPauseButton();
                                    showVideoControls(true);
                                } catch (final RuntimeException error) {
                                    logVideoTelemetry(
                                            "start_failure",
                                            target,
                                            sourcePolicy,
                                            source.uri,
                                            "error=" + error.getClass().getSimpleName());
                                    releaseVideo();
                                    Toast.makeText(
                                                    MediaAlbumActivity.this,
                                                    R.string.video_playback_failed,
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                }
                            });
                    preparedPlayer.setOnCompletionListener(mediaPlayer -> releaseVideo());
                    preparedPlayer.setOnErrorListener(
                            (mediaPlayer, what, extra) -> {
                                logVideoTelemetry(
                                        "on_error",
                                        target,
                                        sourcePolicy,
                                        source.uri,
                                        "what=" + what + " extra=" + extra);
                                releaseVideo();
                                Toast.makeText(
                                                MediaAlbumActivity.this,
                                                R.string.video_playback_failed,
                                                Toast.LENGTH_SHORT)
                                        .show();
                                return true;
                            });

                    videoPlayer = preparedPlayer;
                    pendingVideoSource = null;
                    pendingVideoMessage = null;
                    pendingVideoSourcePolicy = null;
                    logVideoTelemetry(
                            "prepare_async", target, sourcePolicy, source.uri, null);
                    preparedPlayer.prepareAsync();
                } catch (final Exception error) {
                    if (player != null) {
                        try {
                            player.release();
                        } catch (final RuntimeException ignored) {
                        }
                    }
                    videoPlayer = null;
                    videoPlayerPreparing = false;
                    logVideoTelemetry(
                            "fd_datasource_failure",
                            target,
                            sourcePolicy,
                            source.uri,
                            "error=" + error.getClass().getSimpleName());
                    releaseVideo();
                    Toast.makeText(
                                    MediaAlbumActivity.this,
                                    R.string.video_playback_failed,
                                    Toast.LENGTH_SHORT)
                            .show();
                }
            }

            private void releaseVideo() {
                if (activeVideoHolder == this) {
                    activeVideoHolder = null;
                }
                video.removeCallbacks(videoProgressUpdater);
                video.removeCallbacks(videoControlsAutoHide);
                videoControls.animate().cancel();
                videoPlayPause.animate().cancel();
                videoControls.setVisibility(View.GONE);
                videoControls.setAlpha(1f);
                videoControls.setTranslationY(0f);
                videoPlayPause.setVisibility(View.GONE);
                videoPlayPause.setAlpha(1f);
                videoPlayPause.setScaleX(1f);
                videoPlayPause.setScaleY(1f);
                videoSeekTracking = false;
                videoSizeResolvedFromCallback = false;
                resolvedVideoWidth = 0;
                resolvedVideoHeight = 0;
                videoSeek.setProgress(0);
                videoTime.setText(formatVideoTime(0) + " / " + formatVideoTime(0));
                videoPlayPause.setIconResource(R.drawable.ic_media_play_48dp);
                videoPlayPause.setContentDescription(getString(R.string.play_video));
                final MediaPlayer player = videoPlayer;
                videoPlayer = null;
                if (player != null) {
                    try {
                        player.reset();
                    } catch (final RuntimeException ignored) {
                    }
                    try {
                        player.release();
                    } catch (final RuntimeException ignored) {
                    }
                }
                pendingVideoSource = null;
                pendingVideoMessage = null;
                pendingVideoSourcePolicy = null;
                video.clearVideoSize();
                video.setVisibility(View.GONE);
                image.setVisibility(View.VISIBLE);
                videoTapTarget.setVisibility(videoMessage ? View.VISIBLE : View.GONE);
                videoTapTarget.setEnabled(videoMessage);
                progress.setVisibility(View.GONE);
                videoPreparing = false;
                videoPlayerPreparing = false;
                releaseVideoLease(videoLease);
                videoLease = null;
            }
        }
    }

    private final class VideoSource {
        private final Uri uri;
        @Nullable private final AndroidSecureMessageMediaReadCache.Lease lease;

        private VideoSource(
                @NonNull final Uri uri,
                @Nullable final AndroidSecureMessageMediaReadCache.Lease lease) {
            this.uri = uri;
            this.lease = lease;
        }
    }

    private final class VideoSourceTask extends AsyncTask<Message, Void, VideoSource> {
        private final WeakReference<MediaPageAdapter.ViewHolder> holderReference;
        @Nullable private Message message;
        @Nullable private ViewerSourcePolicy sourcePolicy;

        private VideoSourceTask(@NonNull final MediaPageAdapter.ViewHolder holder) {
            holderReference = new WeakReference<>(holder);
        }

        @Override
        protected VideoSource doInBackground(final Message... params) {
            if (params.length == 0) {
                return null;
            }
            message = params[0];
            sourcePolicy = resolveViewerSourcePolicy(message);
            logVideoTelemetry("source_policy", message, sourcePolicy, null, null);
            if (sourcePolicy == ViewerSourcePolicy.SECURE) {
                try {
                    final Conversations application = (Conversations) getApplication();
                    final AndroidSecureMessageMediaReadCache cache =
                            new AndroidSecureMessageMediaReadCache(
                                    MediaAlbumActivity.this,
                                    application.getSecureContentStoreProvider().get());
                    final AndroidSecureMessageMediaReadCache.Lease lease =
                            cache.acquire(
                                    message.getConversation().getAccount().getUuid(),
                                    message.getUuid());
                    if (lease == null) {
                        // We already resolved a committed secure relation. Losing it between the
                        // presence check and lease acquisition must fail closed, never fall back.
                        logVideoTelemetry(
                                "cache_acquire_failure",
                                message,
                                sourcePolicy,
                                null,
                                "result=null");
                        return null;
                    }
                    logVideoTelemetry(
                            "cache_acquire_success",
                            message,
                            sourcePolicy,
                            lease.getUri(),
                            null);
                    return new VideoSource(lease.getUri(), lease);
                } catch (final Exception error) {
                    logVideoTelemetry(
                            "cache_acquire_failure",
                            message,
                            sourcePolicy,
                            null,
                            "error=" + error.getClass().getSimpleName());
                    return null;
                }
            }
            if (sourcePolicy == ViewerSourcePolicy.UNAVAILABLE) {
                logVideoTelemetry("source_unavailable", message, sourcePolicy, null, null);
                return null;
            }

            // Secure rollout is a producer/default policy, not a declaration that every
            // historical attachment has already been migrated. A cleanly absent SCS relation may
            // still be backed by the legacy FileBackend and remains readable.
            final File file = xmppConnectionService.getFileBackend().getFile(message);
            if (!file.isFile()) {
                logVideoTelemetry(
                        "legacy_source_failure",
                        message,
                        sourcePolicy,
                        null,
                        "result=missing");
                return null;
            }
            try {
                final Uri uri = FileBackend.getUriForFile(MediaAlbumActivity.this, file);
                logVideoTelemetry("legacy_source_success", message, sourcePolicy, uri, null);
                return new VideoSource(uri, null);
            } catch (final Exception error) {
                logVideoTelemetry(
                        "legacy_source_failure",
                        message,
                        sourcePolicy,
                        null,
                        "error=" + error.getClass().getSimpleName());
                return null;
            }
        }

        @Override
        protected void onPostExecute(final VideoSource source) {
            final MediaPageAdapter.ViewHolder holder = holderReference.get();
            final Message target = message;
            if (holder == null
                    || target == null
                    || activeVideoHolder != holder
                    || !target.getUuid().equals(holder.boundMessageUuid)
                    || isFinishing()
                    || isDestroyed()) {
                if (source != null) {
                    releaseVideoLease(source.lease);
                }
                if (holder != null) {
                    holder.progress.setVisibility(View.GONE);
                    holder.videoPreparing = false;
                }
                return;
            }
            if (source == null) {
                logVideoTelemetry("source_failure", target, sourcePolicy, null, null);
                holder.progress.setVisibility(View.GONE);
                holder.videoPreparing = false;
                holder.videoTapTarget.setVisibility(
                        holder.videoMessage ? View.VISIBLE : View.GONE);
                holder.videoTapTarget.setEnabled(holder.videoMessage);
                if (activeVideoHolder == holder) {
                    activeVideoHolder = null;
                }
                Toast.makeText(
                                MediaAlbumActivity.this,
                                R.string.video_playback_failed,
                                Toast.LENGTH_SHORT)
                        .show();
                return;
            }

            holder.videoLease = source.lease;
            if (source.lease != null) {
                synchronized (activeVideoLeases) {
                    activeVideoLeases.add(source.lease);
                }
            }
            holder.prepareVideo(target, source, sourcePolicy);
        }
    }

    private final class ThumbnailAdapter extends RecyclerView.Adapter<ThumbnailAdapter.ViewHolder> {

        private int selectedPosition = RecyclerView.NO_POSITION;

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final FrameLayout tile = new FrameLayout(parent.getContext());
            final RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(64), dp(64));
            params.rightMargin = dp(6);
            tile.setLayoutParams(params);
            tile.setBackground(roundedChromeBackground(Color.TRANSPARENT, 10));
            tile.setClipToOutline(true);
            final ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(
                    image,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new ViewHolder(tile, image);
        }

        @Override
        public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
            final boolean selected = position == selectedPosition;
            if (selected) {
                final GradientDrawable outline =
                        roundedChromeBackground(Color.TRANSPARENT, 10);
                outline.setStroke(dp(2), Color.WHITE);
                holder.tile.setForeground(outline);
            } else {
                holder.tile.setForeground(null);
            }
            holder.tile.setScaleX(selected ? 1.0f : 0.94f);
            holder.tile.setScaleY(selected ? 1.0f : 0.94f);
            holder.tile.setAlpha(selected ? 1.0f : 0.78f);
            loadBitmap(messages.get(position), holder.image, dp(72), true);
            holder.tile.setOnClickListener(view -> pager.setCurrentItem(position, true));
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        private void setSelected(final int position) {
            if (selectedPosition == position) {
                return;
            }
            final int previous = selectedPosition;
            selectedPosition = position;
            if (previous != RecyclerView.NO_POSITION) {
                notifyItemChanged(previous);
            }
            notifyItemChanged(selectedPosition);
        }

        private final class ViewHolder extends RecyclerView.ViewHolder {
            private final FrameLayout tile;
            private final ImageView image;

            private ViewHolder(@NonNull final FrameLayout tile, @NonNull final ImageView image) {
                super(tile);
                this.tile = tile;
                this.image = image;
            }
        }
    }

    private final class AlbumBitmapTask extends AsyncTask<Message, Void, Bitmap> {

        @Nullable private final WeakReference<ImageView> imageView;
        private final int size;
        private final boolean crop;
        private final String variant;
        @Nullable private final String prefetchKey;
        @Nullable private Message targetMessage;
        private String messageUuid;

        private AlbumBitmapTask(
                final ImageView imageView, final int size, final boolean crop, final String variant) {
            this(imageView, size, crop, variant, null);
        }

        private AlbumBitmapTask(
                @Nullable final ImageView imageView,
                final int size,
                final boolean crop,
                final String variant,
                @Nullable final String prefetchKey) {
            this.imageView = imageView == null ? null : new WeakReference<>(imageView);
            this.size = size;
            this.crop = crop;
            this.variant = variant;
            this.prefetchKey = prefetchKey;
        }

        @Override
        protected Bitmap doInBackground(final Message... params) {
            if (isCancelled() || params.length == 0) {
                return null;
            }
            targetMessage = params[0];
            messageUuid = targetMessage.getUuid();
            final ViewerSourcePolicy sourcePolicy = resolveViewerSourcePolicy(targetMessage);
            if (sourcePolicy == ViewerSourcePolicy.SECURE) {
                try {
                    final Conversations application = (Conversations) getApplication();
                    final Bitmap bitmap;
                    if (isVideoMessage(targetMessage)) {
                        bitmap =
                                new AndroidSecureMessageMediaThumbnailReader(
                                                MediaAlbumActivity.this,
                                                application.getSecureContentStoreProvider().get())
                                        .load(
                                                targetMessage.getConversation()
                                                        .getAccount()
                                                        .getUuid(),
                                                targetMessage.getUuid(),
                                                Math.max(1, size),
                                                crop);
                    } else {
                        final AndroidSecureMessageMediaReadCache cache =
                                new AndroidSecureMessageMediaReadCache(
                                        MediaAlbumActivity.this,
                                        application.getSecureContentStoreProvider().get());
                        final AndroidSecureMessageMediaReadCache.Lease lease =
                                cache.acquire(
                                        targetMessage.getConversation().getAccount().getUuid(),
                                        targetMessage.getUuid());
                        if (lease == null) {
                            return null;
                        }
                        try {
                            bitmap = decodeSecureBitmap(lease, size, crop);
                        } finally {
                            lease.close();
                        }
                    }
                    if (bitmap != null) {
                        xmppConnectionService.cacheMessageBitmap(targetMessage, size, variant, bitmap);
                    }
                    return bitmap;
                } catch (final Exception e) {
                    // A resolved secure relation is authoritative. Reader/integrity failures are
                    // closed failures and never permission to read an old plaintext path.
                    return null;
                }
            }
            if (sourcePolicy == ViewerSourcePolicy.UNAVAILABLE) {
                return null;
            }
            try {
                return xmppConnectionService
                        .getFileBackend()
                        .getThumbnail(targetMessage, size, false, variant);
            } catch (final IOException e) {
                return null;
            }
        }

        private Bitmap decodeSecureBitmap(
                final AndroidSecureMessageMediaReadCache.Lease lease,
                final int targetSize,
                final boolean cropSquare) throws IOException {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(lease.getUri())) {
                if (input == null) {
                    throw new IOException("Secure media cache is unavailable");
                }
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sample = 1;
            final int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= Math.max(1, targetSize)) {
                sample *= 2;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            final Bitmap decoded;
            try (InputStream input = getContentResolver().openInputStream(lease.getUri())) {
                if (input == null) {
                    throw new IOException("Secure media cache is unavailable");
                }
                decoded = BitmapFactory.decodeStream(input, null, options);
            }
            if (decoded == null) {
                throw new IOException("Unable to decode secure media");
            }
            if (!cropSquare) {
                final int width = decoded.getWidth();
                final int height = decoded.getHeight();
                if (Math.max(width, height) <= targetSize || targetSize <= 0) {
                    return decoded;
                }
                final float scale = (float) targetSize / Math.max(width, height);
                final Bitmap scaled = Bitmap.createScaledBitmap(
                        decoded,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)),
                        true);
                if (scaled != decoded) {
                    decoded.recycle();
                }
                return scaled;
            }
            final int side = Math.min(decoded.getWidth(), decoded.getHeight());
            final int left = (decoded.getWidth() - side) / 2;
            final int top = (decoded.getHeight() - side) / 2;
            final Bitmap square = Bitmap.createBitmap(decoded, left, top, side, side);
            final Bitmap scaled = side == targetSize || targetSize <= 0
                    ? square
                    : Bitmap.createScaledBitmap(square, targetSize, targetSize, true);
            if (square != decoded) {
                decoded.recycle();
            }
            if (scaled != square) {
                square.recycle();
            }
            return scaled;
        }

        @Override
        protected void onPostExecute(final Bitmap bitmap) {
            if (prefetchKey != null) {
                finishViewerPrefetch(prefetchKey);
                return;
            }
            final ImageView image = imageView == null ? null : imageView.get();
            final Message boundMessage = targetMessage;
            if (isCancelled()
                    || image == null
                    || boundMessage == null
                    || messageUuid == null
                    || !messageUuid.equals(image.getTag())) {
                return;
            }
            if (bitmap == null) {
                image.setImageDrawable(null);
                image.setBackgroundColor(0xff111111);
                return;
            }
            renderBitmap(boundMessage, image, bitmap, crop);
            if (!crop) {
                prefetchAdjacentViewerPages(size);
            }
        }
    }

    private final class DismissibleLayout extends FrameLayout {

        private final int touchSlop;
        private float downX;
        private float downY;
        private boolean verticalDrag;
        private View dismissTarget;

        private DismissibleLayout(final Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private void setDismissTarget(final View target) {
            dismissTarget = target;
        }

        @Override
        public boolean onInterceptTouchEvent(final MotionEvent event) {
            if (dismissTarget == null) {
                return super.onInterceptTouchEvent(event);
            }

            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (verticalDrag) {
                    dismissTarget.animate().cancel();
                    dismissTarget.setTranslationY(0f);
                    dismissTarget.setAlpha(1f);
                }
                verticalDrag = false;
            } else if ((action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL)
                    && verticalDrag) {
                dismissTarget.animate().cancel();
                dismissTarget.setTranslationY(0f);
                dismissTarget.setAlpha(1f);
                verticalDrag = false;
            }

            if (mediaZoomed) {
                return super.onInterceptTouchEvent(event);
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    final float horizontal = Math.abs(event.getX() - downX);
                    final float vertical = Math.abs(event.getY() - downY);
                    if (vertical > touchSlop && vertical > horizontal) {
                        verticalDrag = true;
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(final MotionEvent event) {
            if (dismissTarget == null || !verticalDrag) {
                return super.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    final float translation = event.getY() - downY;
                    dismissTarget.setTranslationY(translation);
                    dismissTarget.setAlpha(1f - Math.min(0.55f, Math.abs(translation) / getHeight()));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    final float translationY = dismissTarget.getTranslationY();
                    if (Math.abs(translationY) > getHeight() / 5f) {
                        dismissTarget
                                .animate()
                                .translationY(translationY < 0 ? -getHeight() : getHeight())
                                .alpha(0f)
                                .setDuration(160)
                                .withEndAction(MediaAlbumActivity.this::finish)
                                .start();
                    } else {
                        dismissTarget.animate().translationY(0f).alpha(1f).setDuration(160).start();
                    }
                    verticalDrag = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
