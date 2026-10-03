package eu.siacs.conversations.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMediaBrowserBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway;
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageMediaUiBridge;
import eu.siacs.conversations.ui.adapter.AttachmentListAdapter;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.actions.MessageAction;
import eu.siacs.conversations.ui.actions.MessageActionGroup;
import eu.siacs.conversations.ui.actions.MessageActionType;
import eu.siacs.conversations.ui.actions.reactions.QuickReaction;
import eu.siacs.conversations.ui.actions.ui.MaterialMessageActionSheet;
import eu.siacs.conversations.ui.actions.ui.MessageActionSheet;
import eu.siacs.conversations.ui.attachments.AttachmentEntry;
import eu.siacs.conversations.ui.attachments.AttachmentPage;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.GridManager;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.xmpp.Jid;

public class MediaBrowserActivity extends XmppActivity {

    private static final int PAGE_SIZE = 60;

    private ActivityMediaBrowserBinding binding;
    private MediaAdapter mediaAdapter;
    private AttachmentListAdapter listAdapter;
    private final Map<String, AttachmentEntry> mediaEntriesByMessage = new HashMap<>();
    private final ArrayList<AttachmentEntry> mediaEntries = new ArrayList<>();

    private String accountUuid;
    private String jid;
    private AttachmentEntry.Category category = AttachmentEntry.Category.MEDIA;
    private AttachmentPage.Cursor cursor;
    private boolean hasMore = true;
    private boolean loading;
    private int loadGeneration;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_media_browser);
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setTitle(R.string.attachments_title);
        final Intent launchIntent = getIntent();
        final String launchTitle =
                launchIntent == null ? null : launchIntent.getStringExtra("title");
        if (launchTitle != null && !launchTitle.trim().isEmpty()) {
            binding.toolbar.setSubtitle(launchTitle);
        }

        mediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        mediaAdapter.setOnAttachmentClickListener(this::openMediaAttachment);
        mediaAdapter.setOnAttachmentLongClickListener(this::showMediaAttachmentActions);
        listAdapter = new AttachmentListAdapter(this);
        listAdapter.setOnAttachmentActionListener(this::showAttachmentActions);

        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.attachments_media));
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.attachments_files));
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.attachments_audio));
        binding.tabs.addOnTabSelectedListener(
                new TabLayout.OnTabSelectedListener() {
                    @Override
                    public void onTabSelected(final TabLayout.Tab tab) {
                        switchCategory(categoryForPosition(tab.getPosition()));
                    }

                    @Override
                    public void onTabUnselected(final TabLayout.Tab tab) {}

                    @Override
                    public void onTabReselected(final TabLayout.Tab tab) {
                        binding.media.scrollToPosition(0);
                    }
                });

        binding.media.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(
                            @NonNull final RecyclerView recyclerView,
                            final int dx,
                            final int dy) {
                        updateMediaPeriod();
                        if (dy <= 0 || loading || !hasMore) {
                            return;
                        }
                        final RecyclerView.LayoutManager layoutManager =
                                recyclerView.getLayoutManager();
                        if (!(layoutManager instanceof LinearLayoutManager)) {
                            return;
                        }
                        final LinearLayoutManager linear = (LinearLayoutManager) layoutManager;
                        final int lastVisible = linear.findLastVisibleItemPosition();
                        final int count =
                                recyclerView.getAdapter() == null
                                        ? 0
                                        : recyclerView.getAdapter().getItemCount();
                        if (count > 0 && lastVisible >= count - 8) {
                            loadNextPage();
                        }
                    }
                });

        configureCategoryUi();
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onStop() {
        if (listAdapter != null) {
            listAdapter.stopAudio();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (listAdapter != null) {
            listAdapter.release();
        }
        super.onDestroy();
    }

    @Override
    protected void onBackendConnected() {
        final Intent intent = getIntent();
        accountUuid = intent == null ? null : intent.getStringExtra("account");
        jid = intent == null ? null : intent.getStringExtra("jid");
        if (accountUuid == null || jid == null) {
            finish();
            return;
        }
        mediaAdapter.setAccountUuid(accountUuid);
        resetAndLoad();
    }

    private void switchCategory(final AttachmentEntry.Category selected) {
        if (category == selected && cursor != null) {
            return;
        }
        if (category == AttachmentEntry.Category.AUDIO
                && selected != AttachmentEntry.Category.AUDIO) {
            listAdapter.stopAudio();
        }
        category = selected;
        loadGeneration++;
        configureCategoryUi();
        resetAndLoad();
    }

    private void configureCategoryUi() {
        if (category == AttachmentEntry.Category.MEDIA) {
            binding.media.setAdapter(mediaAdapter);
            GridManager.setupLayoutManager(this, binding.media, R.dimen.browser_media_size);
        } else {
            binding.media.setAdapter(listAdapter);
            binding.media.setLayoutManager(new LinearLayoutManager(this));
        }
        binding.empty.setVisibility(View.GONE);
        binding.loading.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.periodCard.setVisibility(
                category == AttachmentEntry.Category.MEDIA
                                && mediaAdapter != null
                                && mediaAdapter.getItemCount() > 0
                        ? View.VISIBLE
                        : View.GONE);
    }

    private void resetAndLoad() {
        cursor = null;
        hasMore = true;
        loading = false;
        binding.loading.setVisibility(View.GONE);
        binding.periodCard.setVisibility(View.GONE);
        if (category == AttachmentEntry.Category.MEDIA) {
            mediaEntriesByMessage.clear();
            mediaEntries.clear();
            mediaAdapter.setAttachments(java.util.Collections.emptyList());
        } else {
            listAdapter.setEntries(java.util.Collections.emptyList());
        }
        if (accountUuid != null && jid != null) {
            loadNextPage();
        }
    }

    private void loadNextPage() {
        if (loading || !hasMore || accountUuid == null || jid == null) {
            return;
        }
        loading = true;
        binding.loading.setVisibility(View.VISIBLE);
        final int generation = loadGeneration;
        final AttachmentEntry.Category requestedCategory = category;
        xmppConnectionService.getAttachmentPage(
                accountUuid,
                Jid.of(jid),
                requestedCategory,
                cursor,
                PAGE_SIZE,
                page ->
                        runOnUiThread(
                                () -> {
                                    if (generation != loadGeneration
                                            || requestedCategory != category
                                            || isFinishing()
                                            || isDestroyed()) {
                                        return;
                                    }
                                    loading = false;
                                    binding.loading.setVisibility(View.GONE);
                                    cursor = page.nextCursor;
                                    hasMore = page.hasMore;
                                    appendPage(page.entries);
                                    updateEmptyState();
                                    if (page.entries.isEmpty() && hasMore) {
                                        loadNextPage();
                                    }
                                }));
    }

    private void appendPage(final List<AttachmentEntry> entries) {
        if (category == AttachmentEntry.Category.MEDIA) {
            for (final AttachmentEntry entry : entries) {
                mediaEntriesByMessage.put(entry.messageUuid, entry);
            }
            final List<Attachment> visibleAttachments = toMediaAttachments(entries);
            for (final Attachment attachment : visibleAttachments) {
                final AttachmentEntry visibleEntry =
                        mediaEntriesByMessage.get(attachment.getUuid().toString());
                if (visibleEntry != null) {
                    mediaEntries.add(visibleEntry);
                }
            }
            mediaAdapter.appendAttachments(visibleAttachments);
            binding.media.post(this::updateMediaPeriod);
        } else {
            listAdapter.appendEntries(entries);
        }
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
            final File file = xmppConnectionService.getFileBackend().getFileForPath(entry.legacyPath);
            if (file.isFile()) {
                result.add(Attachment.of(uuid, file, entry.mimeType));
            }
        }
        return result;
    }

    private void openMediaAttachment(final Attachment attachment) {
        final AttachmentEntry entry =
                mediaEntriesByMessage.get(attachment.getUuid().toString());
        if (entry == null) {
            openMediaExternally(attachment, null);
            return;
        }
        if (entry.isImage() || entry.isVideo()) {
            openMediaSequence(entry);
            return;
        }
        openMediaExternally(attachment, entry);
    }

    private void openMediaSequence(final AttachmentEntry selectedEntry) {
        final ArrayList<AttachmentEntry> sequence = new ArrayList<>();
        for (final AttachmentEntry entry : mediaEntries) {
            if (selectedEntry.conversationUuid.equals(entry.conversationUuid)
                    && (entry.isImage() || entry.isVideo())) {
                sequence.add(entry);
            }
        }
        if (sequence.isEmpty()) {
            return;
        }

        final Message[] resolved = new Message[sequence.size()];
        final AtomicInteger remaining = new AtomicInteger(sequence.size());
        for (int position = 0; position < sequence.size(); position++) {
            final int targetPosition = position;
            final AttachmentEntry entry = sequence.get(position);
            xmppConnectionService.loadAttachmentMessage(
                    entry.conversationUuid,
                    entry.messageUuid,
                    message -> {
                        resolved[targetPosition] = message;
                        if (remaining.decrementAndGet() != 0) {
                            return;
                        }
                        runOnUiThread(
                                () -> {
                                    if (isFinishing() || isDestroyed()) {
                                        return;
                                    }
                                    final ArrayList<Message> album = new ArrayList<>();
                                    int initialPosition = RecyclerView.NO_POSITION;
                                    for (int index = 0; index < resolved.length; index++) {
                                        final Message candidate = resolved[index];
                                        if (candidate == null) {
                                            continue;
                                        }
                                        if (sequence.get(index).messageUuid.equals(
                                                selectedEntry.messageUuid)) {
                                            initialPosition = album.size();
                                        }
                                        album.add(candidate);
                                    }
                                    if (album.isEmpty()
                                            || initialPosition == RecyclerView.NO_POSITION) {
                                        showOperationError(R.string.file_deleted);
                                        return;
                                    }
                                    MediaAlbumActivity.launch(this, album, initialPosition);
                                });
                    });
        }
    }

    private void openMediaExternally(
            final Attachment attachment,
            final AttachmentEntry entry) {
        if (entry != null && entry.isSecure()) {
            SecureMessageMediaUiBridge.openOrFallback(
                    this,
                    entry.accountUuid,
                    entry.messageUuid,
                    entry.mimeType,
                    () -> showOperationError(R.string.file_deleted));
            return;
        }
        ViewUtil.view(this, attachment);
    }

    private void showMediaAttachmentActions(final Attachment attachment) {
        final AttachmentEntry entry =
                mediaEntriesByMessage.get(attachment.getUuid().toString());
        if (entry != null) {
            showAttachmentActions(entry);
        }
    }

    private void showAttachmentActions(final AttachmentEntry entry) {
        final List<MessageAction> actions = new ArrayList<>();
        actions.add(
                new MessageAction(
                        MessageActionType.FORWARD,
                        R.string.message_action_forward,
                        R.drawable.ic_forward_24dp,
                        MessageActionGroup.PRIMARY,
                        false));
        actions.add(
                new MessageAction(
                        MessageActionType.SHOW_IN_CHAT,
                        R.string.attachments_action_show_in_chat,
                        R.drawable.ic_chat_24dp,
                        MessageActionGroup.ORGANIZATION,
                        false));
        if (entry.category == AttachmentEntry.Category.MEDIA) {
            actions.add(
                    new MessageAction(
                            MessageActionType.SAVE_TO_GALLERY,
                            R.string.save_to_gallery,
                            R.drawable.ic_photo_24dp,
                            MessageActionGroup.CONTENT,
                            false));
        } else {
            actions.add(
                    new MessageAction(
                            MessageActionType.SAVE_TO_DOWNLOADS,
                            R.string.save_to_downloads,
                            R.drawable.ic_download_24dp,
                            MessageActionGroup.CONTENT,
                            false));
        }
        actions.add(
                new MessageAction(
                        MessageActionType.DELETE_FILE,
                        R.string.attachments_action_delete,
                        R.drawable.ic_delete_24dp,
                        MessageActionGroup.DANGER,
                        true));

        final MaterialMessageActionSheet sheet = new MaterialMessageActionSheet(this);
        sheet.show(
                actions,
                Collections.emptyList(),
                new MessageActionSheet.Listener() {
                    @Override
                    public void onActionSelected(final MessageAction action) {
                        dispatchAttachmentAction(entry, action);
                    }

                    @Override
                    public void onQuickReactionSelected(final QuickReaction reaction) {}

                    @Override
                    public void onMoreReactionsSelected() {}
                });
    }

    private void dispatchAttachmentAction(
            final AttachmentEntry entry, final MessageAction action) {
        xmppConnectionService.loadAttachmentMessage(
                entry.conversationUuid,
                entry.messageUuid,
                message ->
                        runOnUiThread(
                                () -> {
                                    if (message == null || isFinishing() || isDestroyed()) {
                                        showOperationError(R.string.file_deleted);
                                        return;
                                    }
                                    switch (action.getType()) {
                                        case FORWARD:
                                            ShareUtil.forward(this, message);
                                            break;
                                        case SHOW_IN_CHAT:
                                            if (message.getConversation() instanceof Conversation) {
                                                switchToConversationOnMessage(
                                                        (Conversation) message.getConversation(),
                                                        message.getUuid());
                                            }
                                            break;
                                        case SAVE_TO_DOWNLOADS:
                                            saveAttachmentToDownloads(message);
                                            break;
                                        case SAVE_TO_GALLERY:
                                            saveAttachmentToGallery(message);
                                            break;
                                        case DELETE_FILE:
                                            confirmDeleteAttachment(message);
                                            break;
                                        default:
                                            break;
                                    }
                                }));
    }

    private void saveAttachmentToGallery(
            final eu.siacs.conversations.entities.Message message) {
        xmppConnectionService.copyMediaToGallery(
                message,
                new UiCallback<Integer>() {
                    @Override
                    public void success(final Integer ignored) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        MediaBrowserActivity.this,
                                                        R.string.save_to_gallery_success,
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }

                    @Override
                    public void error(final int errorCode, final Integer resId) {
                        runOnUiThread(
                                () ->
                                        showOperationError(
                                                resId == null
                                                        ? R.string.error_io_exception
                                                        : resId));
                    }

                    @Override
                    public void userInputRequired(
                            final PendingIntent pendingIntent, final Integer object) {}
                });
    }

    private void saveAttachmentToDownloads(final eu.siacs.conversations.entities.Message message) {
        xmppConnectionService.copyAttachmentToDownloadsFolder(
                message,
                new UiCallback<Integer>() {
                    @Override
                    public void success(final Integer ignored) {
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        MediaBrowserActivity.this,
                                                        R.string.save_to_downloads_success,
                                                        Toast.LENGTH_LONG)
                                                .show());
                    }

                    @Override
                    public void error(final int errorCode, final Integer resId) {
                        runOnUiThread(
                                () ->
                                        showOperationError(
                                                resId == null
                                                        ? R.string.error_io_exception
                                                        : resId));
                    }

                    @Override
                    public void userInputRequired(
                            final PendingIntent pendingIntent, final Integer object) {}
                });
    }

    private void showOperationError(@StringRes final int messageRes) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void confirmDeleteAttachment(final eu.siacs.conversations.entities.Message message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_file_dialog)
                .setMessage(R.string.delete_file_dialog_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.confirm,
                        (dialog, which) -> deleteAttachment(message))
                .show();
    }

    private void deleteAttachment(final eu.siacs.conversations.entities.Message message) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            if (xmppConnectionService.getFileBackend().deleteFile(message)) {
                markAttachmentDeleted(message);
            }
            return;
        }
        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(
                () -> {
                    try {
                        final Conversations application = (Conversations) getApplication();
                        final SecureContentStore store =
                                application.getSecureContentStoreProvider().get();
                        final SecureMessageMediaCoordinator coordinator =
                                new SecureMessageMediaCoordinator(
                                        store, new SecureContentTransferGateway(store));
                        final String account =
                                message.getConversation().getAccount().getUuid();
                        final var secureBinding =
                                coordinator.resolve(account, message.getUuid());
                        if (secureBinding == null) {
                            if (xmppConnectionService.getFileBackend().deleteFile(message)) {
                                runOnUiThread(() -> markAttachmentDeleted(message));
                            }
                            return;
                        }

                        final DownloadableFile legacyFile =
                                xmppConnectionService.getFileBackend().getFile(message);
                        if (legacyFile.exists()
                                && !xmppConnectionService.getFileBackend().deleteFile(message)
                                && legacyFile.exists()) {
                            throw new IOException(
                                    "Unable to delete legacy plaintext before secure media retirement");
                        }
                        coordinator.retire(secureBinding);
                        runOnUiThread(() -> markAttachmentDeleted(message));
                    } catch (final Exception error) {
                        runOnUiThread(
                                () -> showOperationError(R.string.error_io_exception));
                    }
                });
    }

    private void markAttachmentDeleted(final eu.siacs.conversations.entities.Message message) {
        message.setDeleted(true);
        xmppConnectionService.evictPreview(message.getUuid());
        xmppConnectionService.updateMessage(message, false);

        final String messageUuid = message.getUuid();
        mediaEntriesByMessage.remove(messageUuid);
        for (int index = mediaEntries.size() - 1; index >= 0; index--) {
            if (messageUuid.equals(mediaEntries.get(index).messageUuid)) {
                mediaEntries.remove(index);
                break;
            }
        }
        if (category == AttachmentEntry.Category.MEDIA) {
            mediaAdapter.removeAttachmentByUuid(messageUuid);
        } else {
            listAdapter.removeByMessageUuid(messageUuid);
        }
        updateEmptyState();
    }

    private void updateEmptyState() {
        final int count =
                binding.media.getAdapter() == null ? 0 : binding.media.getAdapter().getItemCount();
        binding.empty.setVisibility(count == 0 && !hasMore ? View.VISIBLE : View.GONE);
    }
    private void updateMediaPeriod() {
        if (category != AttachmentEntry.Category.MEDIA
                || binding == null
                || mediaAdapter == null
                || mediaAdapter.getItemCount() == 0) {
            if (binding != null) {
                binding.periodCard.setVisibility(View.GONE);
            }
            return;
        }
        final RecyclerView.LayoutManager manager = binding.media.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            binding.periodCard.setVisibility(View.GONE);
            return;
        }
        final int position = ((LinearLayoutManager) manager).findFirstVisibleItemPosition();
        final Attachment attachment = mediaAdapter.getAttachmentAt(position);
        if (attachment == null) {
            binding.periodCard.setVisibility(View.GONE);
            return;
        }
        final AttachmentEntry entry =
                mediaEntriesByMessage.get(attachment.getUuid().toString());
        if (entry == null || entry.timeSent <= 0) {
            binding.periodCard.setVisibility(View.GONE);
            return;
        }
        String period =
                new SimpleDateFormat("LLLL yyyy", Locale.getDefault())
                        .format(new Date(entry.timeSent));
        if (!period.isEmpty()) {
            period =
                    period.substring(0, 1).toUpperCase(Locale.getDefault())
                            + period.substring(1);
        }
        binding.period.setText(period);
        binding.periodCard.setVisibility(View.VISIBLE);
    }


    private static AttachmentEntry.Category categoryForPosition(final int position) {
        switch (position) {
            case 1:
                return AttachmentEntry.Category.FILE;
            case 2:
                return AttachmentEntry.Category.AUDIO;
            case 0:
            default:
                return AttachmentEntry.Category.MEDIA;
        }
    }

    public static void launch(final Context context, final Contact contact) {
        launch(
                context,
                contact.getAccount(),
                contact.getJid().asBareJid().toString(),
                contact.getDisplayName());
    }

    public static void launch(final Context context, final Conversation conversation) {
        launch(
                context,
                conversation.getAccount(),
                conversation.getJid().asBareJid().toString(),
                conversation.getName().toString());
    }

    private static void launch(
            final Context context,
            final Account account,
            final String jid,
            final String title) {
        final Intent intent = new Intent(context, MediaBrowserActivity.class);
        intent.putExtra("account", account.getUuid());
        intent.putExtra("jid", jid);
        intent.putExtra("title", title);
        context.startActivity(intent);
    }
}
