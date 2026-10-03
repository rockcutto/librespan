package eu.siacs.conversations.ui.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.FontRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.common.base.Optional;
import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.io.File;
import java.util.UUID;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemConversationBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.utils.IrregularUnicodeDetector;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;

public class ConversationAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_ACCOUNT = 0;
    private static final int VIEW_TYPE_TAG = 1;
    private static final int VIEW_TYPE_CONVERSATION = 2;

    private static final String EXPANDED_ACCOUNTS_KEY = "expandedAccounts";

    private static final String EXPANDED_TAG_KEY_PREFIX = "expandedTags_";

    private final XmppActivity activity;
    private final Typeface conversationTitleTypeface;
    private final Typeface conversationUnreadTitleTypeface;
    private final Typeface conversationBodyTypeface;
    private final Typeface conversationBodyItalicTypeface;
    private final List<Conversation> conversations;
    private final List<Conversation> filteredConversations = new ArrayList<>();
    private String filterQuery = "";
    private List<ConversationRowState> renderedRows = new ArrayList<>();
    private boolean renderedRowsInitialized = false;
    private OnConversationClickListener listener;
    private OnConversationLongClickListener longClickListener;

    private ListItem.Tag generalTag;

    private List<Object> items = new ArrayList<>();
    private Map<Account, Set<String>> expandedItems = new HashMap<>();
    private boolean expandedItemsRestored = false;

    private Map<Account, Map<ListItem.Tag, Set<Conversation>>> groupedItems = new HashMap<>();

    private boolean groupingEnabled = false;

    SharedPreferences prefs;

    private static Typeface requireBundledTypeface(
            final Context context, @FontRes final int fontResource) {
        final Typeface typeface = ResourcesCompat.getFont(context, fontResource);
        if (typeface == null) {
            throw new IllegalStateException("Bundled typeface is unavailable: " + fontResource);
        }
        return typeface;
    }

    public ConversationAdapter(XmppActivity activity, List<Conversation> conversations, List<ListItem.Tag> tags) {
        this.activity = activity;
        this.conversationTitleTypeface = requireBundledTypeface(activity, R.font.onest_medium);
        this.conversationUnreadTitleTypeface = requireBundledTypeface(activity, R.font.onest_semibold);
        this.conversationBodyTypeface = requireBundledTypeface(activity, R.font.onest_regular);
        this.conversationBodyItalicTypeface =
                Typeface.create(this.conversationBodyTypeface, Typeface.ITALIC);
        this.conversations = conversations;

        String generalTagName = activity.getString(R.string.contact_tag_general);
        generalTag = new ListItem.Tag(generalTagName, UIHelper.getColorForName(generalTagName, true));

        registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                if (groupingEnabled) {
                    items.clear();
                    groupedItems.clear();

                    List<Account> accounts = activity.xmppConnectionService.getAccounts();


                    if (!expandedItemsRestored) {
                        prefs = activity.getSharedPreferences("expansionPrefs", Context.MODE_PRIVATE);
                        Set<String> expandedAccounts = new HashSet<>(prefs.getStringSet(EXPANDED_ACCOUNTS_KEY, Collections.emptySet()));

                        if (accounts.size() == 1) {
                            expandedAccounts.add(accounts.get(0).getUuid());
                        }

                        for (String id : expandedAccounts) {
                            Set<String> expandedTags = new HashSet<>(prefs.getStringSet(EXPANDED_TAG_KEY_PREFIX + id, Collections.emptySet()));
                            Account account = activity.xmppConnectionService.findAccountByUuid(id);
                            expandedItems.put(account, expandedTags);
                        }

                        expandedItemsRestored = true;
                    }

                    for (Account account : accounts) {
                        if (accounts.size() > 1) {
                            items.add(account);
                        }

                        boolean accountExpanded = accounts.size() == 1 || expandedItems.containsKey(account);

                        boolean generalTagAdded = false;
                        int initialPosition = items.size();

                        Set<String> expandedTags = expandedItems.getOrDefault(account, Collections.emptySet());

                        Map<ListItem.Tag, Set<Conversation>> groupedItems = new HashMap<>();

                        Map<Conversation, List<ListItem.Tag>> tagsToConversationCache = new HashMap<>();

                        for (int i = 0; i < conversations.size(); i++) {
                            Conversation item = conversations.get(i);

                            if (item.getAccount() != account) continue;

                            List<ListItem.Tag> itemTags = item.getContact().getTags(activity);

                            if (item.getBookmark() != null) {
                                itemTags.addAll(item.getBookmark().getTags(activity));
                            }

                            tagsToConversationCache.put(item, itemTags);

                            if (itemTags.size() == 0 || (itemTags.size() == 1 && UIHelper.isStatusTag(activity, itemTags.get(0)))) {
                                if (accountExpanded && !generalTagAdded) {
                                    items.add(initialPosition, generalTag);
                                    generalTagAdded = true;
                                }

                                if (accountExpanded && expandedTags.contains(generalTag.getName().toLowerCase(Locale.US))) {
                                    items.add(item);
                                }

                                Set<Conversation> group = groupedItems.computeIfAbsent(generalTag, t -> new HashSet<>());
                                group.add(item);
                            }
                        }

                        for (ListItem.Tag tag : tags) {
                            if (UIHelper.isStatusTag(activity, tag)) {
                                continue;
                            }

                            if (accountExpanded) {
                                items.add(tag);
                            }

                            for (int i = 0; i < conversations.size(); i++) {
                                Conversation item = conversations.get(i);

                                if (item.getAccount() != account) continue;

                                List<ListItem.Tag> itemTags = tagsToConversationCache.get(item);

                                if (itemTags.contains(tag)) {
                                    if (accountExpanded && expandedTags.contains(tag.getName().toLowerCase(Locale.US))) {
                                        items.add(item);
                                    }

                                    Set<Conversation> group = groupedItems.computeIfAbsent(tag, t -> new HashSet<>());

                                    group.add(item);
                                }
                            }

                            if (accountExpanded && groupedItems.get(tag) == null) {
                                items.remove(items.size() - 1);
                            }
                        }

                        ConversationAdapter.this.groupedItems.put(account, groupedItems);
                    }
                }
            }
        });
    }

    public void setFilter(@Nullable String query) {
        filterQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filteredConversations.clear();
        if (!filterQuery.isEmpty()) {
            for (Conversation conversation : conversations) {
                if (matchesFilter(conversation, filterQuery)) {
                    filteredConversations.add(conversation);
                }
            }
        }
        dispatchConversationDiff();
    }

    private void dispatchConversationDiff() {
        if (groupingEnabled && !isFiltering()) {
            renderedRowsInitialized = false;
            renderedRows.clear();
            notifyDataSetChanged();
            return;
        }

        final List<ConversationRowState> nextRows = new ArrayList<>();
        for (final Conversation conversation : displayedConversations()) {
            nextRows.add(ConversationRowState.capture(activity, conversation));
        }

        if (!renderedRowsInitialized) {
            renderedRows = nextRows;
            renderedRowsInitialized = true;
            notifyDataSetChanged();
            return;
        }

        final List<ConversationRowState> previousRows = renderedRows;
        renderedRows = nextRows;
        final DiffUtil.DiffResult diff =
                DiffUtil.calculateDiff(
                        new DiffUtil.Callback() {
                            @Override
                            public int getOldListSize() {
                                return previousRows.size();
                            }

                            @Override
                            public int getNewListSize() {
                                return nextRows.size();
                            }

                            @Override
                            public boolean areItemsTheSame(
                                    final int oldItemPosition, final int newItemPosition) {
                                return previousRows
                                        .get(oldItemPosition)
                                        .uuid
                                        .equals(nextRows.get(newItemPosition).uuid);
                            }

                            @Override
                            public boolean areContentsTheSame(
                                    final int oldItemPosition, final int newItemPosition) {
                                return previousRows
                                        .get(oldItemPosition)
                                        .equals(nextRows.get(newItemPosition));
                            }
                        },
                        true);
        diff.dispatchUpdatesTo(this);
    }

    public boolean isFiltering() {
        return !filterQuery.isEmpty();
    }

    private boolean matchesFilter(Conversation conversation, String query) {
        final StringBuilder searchable = new StringBuilder();
        final CharSequence name = conversation.getName();
        if (name != null) {
            searchable.append(name).append(' ');
        }
        final Contact contact = conversation.getContact();
        if (contact != null && contact.getJid() != null) {
            searchable.append(contact.getJid()).append(' ');
        }
        final Message latest = conversation.getLatestMessage();
        if (latest != null) {
            final String residentVerifiedBody = latest.getVerifiedProtectedBodyOrNull();
            final String latestBody =
                    residentVerifiedBody != null ? residentVerifiedBody : latest.getBody();
            if (latestBody != null) {
                searchable.append(latestBody);
            }
        }
        return searchable.toString().toLowerCase(Locale.ROOT).contains(query);
    }

    private List<Conversation> displayedConversations() {
        return isFiltering() ? filteredConversations : conversations;
    }

    public boolean isConversationViewHolder(RecyclerView.ViewHolder viewHolder) {
        return viewHolder.getItemViewType() == VIEW_TYPE_CONVERSATION;
    }

    public boolean isGroupingEnabled() {
        return groupingEnabled;
    }

    @Nullable
    public Conversation getConversation(int position) {
        if (groupingEnabled && !isFiltering()) {
           Object item = items.get(position);
           if (item instanceof Conversation) {
               return (Conversation) item;
           } else {
               return null;
           }
        } else {
           return displayedConversations().get(position);
        }
    }

    public void setGroupingEnabled(boolean groupingEnabled) {
        if (groupingEnabled != this.groupingEnabled) {
            this.groupingEnabled = groupingEnabled;
            renderedRowsInitialized = false;
            renderedRows.clear();
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (!groupingEnabled || isFiltering()) {
            return VIEW_TYPE_CONVERSATION;
        } else {
            Object item = items.get(position);

            if (item instanceof Account) {
                return VIEW_TYPE_ACCOUNT;
            } else if (item instanceof ListItem.Tag) {
                return VIEW_TYPE_TAG;
            } else {
                return VIEW_TYPE_CONVERSATION;
            }
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ACCOUNT) {
            return new AccountViewHolder(parent);
        } else if (viewType == VIEW_TYPE_TAG) {
            return new TagViewHolder(parent);
        } else {
            return new ConversationViewHolder(
                    DataBindingUtil.inflate(
                            LayoutInflater.from(parent.getContext()),
                            R.layout.item_conversation,
                            parent,
                            false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        if (groupingEnabled && !isFiltering()) {
            if (viewHolder instanceof ConversationViewHolder) {
                bindConversation((ConversationViewHolder) viewHolder, (Conversation) items.get(position));
            } else if (viewHolder instanceof TagViewHolder) {
                bindTag((TagViewHolder) viewHolder, (ListItem.Tag) items.get(position), position);
            } else {
                bindAccount((AccountViewHolder) viewHolder, (Account) items.get(position));
            }
        } else {
            bindConversation((ConversationViewHolder) viewHolder, displayedConversations().get(position));
        }
    }

    private String conversationAttachmentLabel(final Message message) {
        final String mime = message.getMimeType();

        // Media keeps the compact semantic label requested for the chat list. File/document
        // attachments instead prefer their user-visible filename when one is available.
        if (!Strings.isNullOrEmpty(mime)
                && (mime.startsWith("image/")
                        || mime.startsWith("video/")
                        || mime.startsWith("audio/"))) {
            return StringUtils.capitalize(UIHelper.getFileDescriptionString(activity, message));
        }
        if (Strings.isNullOrEmpty(mime) && message.getType() == Message.TYPE_IMAGE) {
            return StringUtils.capitalize(UIHelper.getFileDescriptionString(activity, message));
        }

        final String fileName = conversationAttachmentFileName(message);
        if (!Strings.isNullOrEmpty(fileName)) {
            return fileName;
        }

        final String description = UIHelper.getFileDescriptionString(activity, message);
        final String humanReadable =
                !Strings.isNullOrEmpty(mime) && mime.equals(description)
                        ? activity.getString(R.string.file)
                        : description;
        return StringUtils.capitalize(humanReadable);
    }

    @Nullable
    private String conversationAttachmentFileName(final Message message) {
        final String secureName = message.getSecureMediaFileName();
        if (!Strings.isNullOrEmpty(secureName) && !looksGeneratedAttachmentName(secureName)) {
            return new File(secureName).getName();
        }

        String localName = null;
        final String localPath = message.getRelativeFilePath();
        if (!Strings.isNullOrEmpty(localPath)) {
            localName =
                    FileBackend.userVisiblePlaintextFileName(
                            message.getUuid(), new File(localPath).getName());
            if (!Strings.isNullOrEmpty(localName) && !looksGeneratedAttachmentName(localName)) {
                return localName;
            }
        }

        final String remoteUrl = message.getFileParams().url;
        if (!Strings.isNullOrEmpty(remoteUrl)) {
            try {
                final String lastSegment = Uri.parse(remoteUrl).getLastPathSegment();
                if (!Strings.isNullOrEmpty(lastSegment)) {
                    final String decoded = Uri.decode(lastSegment);
                    if (!Strings.isNullOrEmpty(decoded)
                            && !looksGeneratedAttachmentName(decoded)) {
                        return new File(decoded).getName();
                    }
                }
            } catch (final Exception ignored) {
                // Filename is presentation metadata only; fall through to the type label.
            }
        }
        return null;
    }

    private static boolean looksGeneratedAttachmentName(final String filename) {
        if (Strings.isNullOrEmpty(filename)) {
            return false;
        }
        final String leaf = new File(filename).getName();
        final int dot = leaf.lastIndexOf('.');
        final String stem = dot > 0 ? leaf.substring(0, dot) : leaf;
        try {
            UUID.fromString(stem);
            return true;
        } catch (final IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public int getItemCount() {
        if (groupingEnabled && !isFiltering()) {
            return items.size();
        } else {
            return displayedConversations().size();
        }
    }

    private void bindAccount(AccountViewHolder viewHolder, Account account) {
        viewHolder.text.setText(activity.getString(R.string.contact_tag_with_total, account.getJid().asBareJid().toString(), getChildCount(account, null)));

        Integer color = UIHelper.getColorForStatus(account.getPresenceStatus());

        viewHolder.itemView.setBackgroundColor(Color.TRANSPARENT);
        viewHolder.text.setTextColor(
                color != null
                        ? color
                        : MaterialColors.getColor(
                                viewHolder.text,
                                com.google.android.material.R.attr.colorOnSurfaceVariant));

        viewHolder.arrow.setRotation(expandedItems.containsKey(account) ? 180 : 0);

        viewHolder.itemView.setOnClickListener(v -> {
            if (expandedItems.containsKey(account)) {
                expandedItems.remove(account);
            } else {
                expandedItems.put(account, new HashSet<>());
            }

            Set<String> expandedAccounts = new HashSet<>();

            for (Account a : expandedItems.keySet()) {
                expandedAccounts.add(a.getUuid());
            }


            prefs.edit().putStringSet(EXPANDED_ACCOUNTS_KEY, expandedAccounts).apply();

            notifyDataSetChanged();
        });
    }

    private void bindTag(TagViewHolder viewHolder, ListItem.Tag tag, int position) {
        Account account = findAccountForTag(position);
        viewHolder.text.setText(activity.getString(R.string.contact_tag_with_total, tag.getName(), getChildCount(account, tag)));
        viewHolder.text.setBackgroundColor(Color.TRANSPARENT);
        viewHolder.text.setTextColor(tag.getColor());

        viewHolder.arrow.setRotation(expandedItems.computeIfAbsent(account, a -> new HashSet<>()).contains(tag.getName().toLowerCase(Locale.US)) ? 180 : 0);

        viewHolder.itemView.setOnClickListener(v -> {
           Set<String> expandedTags = expandedItems.computeIfAbsent(account, a -> new HashSet<>());
           if (expandedTags.contains(tag.getName().toLowerCase(Locale.US))) {
               expandedTags.remove(tag.getName().toLowerCase(Locale.US));
           } else {
               expandedTags.add(tag.getName().toLowerCase(Locale.US));
           }

            prefs.edit().putStringSet(EXPANDED_TAG_KEY_PREFIX + account.getUuid(), expandedItems.get(account)).apply();

           notifyDataSetChanged();
        });
    }

    private static void setConversationPreviewSize(final View view, final int sizeDp) {
        final int size =
                Math.round(sizeDp * view.getResources().getDisplayMetrics().density);
        final ViewGroup.LayoutParams layoutParams = view.getLayoutParams();

        if (layoutParams.width != size || layoutParams.height != size) {
            layoutParams.width = size;
            layoutParams.height = size;
            view.setLayoutParams(layoutParams);
        }
    }

    private static void setConversationTitleIcon(
            final TextView view, @DrawableRes final Integer drawableRes) {
        if (drawableRes == null) {
            view.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        final Drawable drawable = AppCompatResources.getDrawable(view.getContext(), drawableRes);
        if (drawable == null) {
            view.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        final int size =
                Math.round(16f * view.getResources().getDisplayMetrics().density);
        drawable.mutate();
        drawable.setBounds(0, 0, size, size);
        drawable.setTint(
                MaterialColors.getColor(
                        view,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
        view.setCompoundDrawablesRelative(null, null, drawable, null);
    }
    @Nullable
    private static String getContactStatusMessage(@Nullable final Contact contact) {
        if (contact == null) {
            return null;
        }
        final List<String> statusMessages = contact.getPresences().getStatusMessages();
        if (statusMessages.isEmpty()) {
            return null;
        }
        final String status = statusMessages.get(0);
        if (Strings.isNullOrEmpty(status)) {
            return null;
        }
        final String normalized = status.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void bindConversation(ConversationViewHolder viewHolder, Conversation conversation) {
        if (conversation == null) {
            return;
        }

        CharSequence name = conversation.getName();
        if (conversation.getNextCounterpart() != null && conversation.hasPermanentCounterpart()) {
            final String counterpartResource = conversation.getNextCounterpart().getResource();
            if (!Strings.isNullOrEmpty(counterpartResource)) {
                if (conversation.getMode() == Conversational.MODE_MULTI) {
                    name =
                            viewHolder.binding
                                    .getRoot()
                                    .getResources()
                                    .getString(
                                            R.string.muc_private_conversation_title,
                                            counterpartResource,
                                            conversation.getName());
                } else {
                    name =
                            viewHolder.binding
                                    .getRoot()
                                    .getResources()
                                    .getString(
                                            R.string.secret_chat_title,
                                            conversation.getName(),
                                            counterpartResource);
                }
            }
        }

        if (conversation.withSelf()) {
            name = viewHolder.binding.getRoot().getResources().getString(R.string.saved_messages);
        }

        if (name instanceof Jid) {
            viewHolder.binding.conversationName.setText(
                    IrregularUnicodeDetector.style(activity, (Jid) name));
        } else {
            viewHolder.binding.conversationName.setText(name);
        }

        if (conversation == ConversationFragment.getConversation(activity)) {
            viewHolder.binding.frame.setBackgroundResource(
                    R.drawable.background_selected_item_conversation);
            // viewHolder.binding.frame.setBackgroundColor(MaterialColors.getColor(viewHolder.binding.frame, com.google.android.material.R.attr.colorSurfaceDim));
        } else {
            viewHolder.binding.frame.setBackgroundColor(
                    MaterialColors.getColor(
                            viewHolder.binding.frame,
                            com.google.android.material.R.attr.colorSurface));
        }

        final Message message = conversation.getLatestMessage();
        final int status = message.getStatus();
        final int unreadCount = conversation.unreadCount();
        final boolean isRead = conversation.isRead();
        final Conversation.Draft draft = isRead ? conversation.getDraft() : null;


        final @DrawableRes Integer messageStatusDrawable =
                MessageAdapter.getMessageStatusAsDrawable(message, status);
        if (message.getType() == Message.TYPE_RTP_SESSION) {
            viewHolder.binding.messageStatus.setVisibility(View.GONE);
        } else if (messageStatusDrawable == null) {
            if (status <= Message.STATUS_RECEIVED) {
                viewHolder.binding.messageStatus.setVisibility(View.GONE);
            } else {
                viewHolder.binding.messageStatus.setVisibility(View.INVISIBLE);
            }
        } else {
            viewHolder.binding.messageStatus.setImageResource(messageStatusDrawable);
            if (status == Message.STATUS_SEND_DISPLAYED) {
                viewHolder.binding.messageStatus.setImageResource(R.drawable.ic_done_all_bold_24dp);
                ImageViewCompat.setImageTintList(
                        viewHolder.binding.messageStatus,
                        ColorStateList.valueOf(
                                MaterialColors.getColor(
                                        viewHolder.binding.messageStatus,
                                        androidx.appcompat.R.attr.colorPrimary)));
            } else {
                ImageViewCompat.setImageTintList(
                        viewHolder.binding.messageStatus,
                        ColorStateList.valueOf(
                                MaterialColors.getColor(
                                        viewHolder.binding.messageStatus,
                                        androidx.appcompat.R.attr.colorControlNormal)));
            }
            viewHolder.binding.messageStatus.setVisibility(View.VISIBLE);
        }

        if (unreadCount > 0) {
            viewHolder.binding.unreadCount.setVisibility(View.VISIBLE);
            viewHolder.binding.unreadCount.setUnreadCount(unreadCount);
        } else {
            viewHolder.binding.unreadCount.setVisibility(View.GONE);
        }

        viewHolder.binding.conversationName.setTypeface(
                isRead ? conversationTitleTypeface : conversationUnreadTitleTypeface);

        final boolean isMuc = conversation.getMode() == Conversation.MODE_MULTI;
        final long mutedTill =
                conversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
        final boolean isMuted = mutedTill >= System.currentTimeMillis();

        if (isMuc) {
            // MUC identity belongs with the avatar, in the same semantic slot where a direct chat
            // shows presence. The title-end slot is reserved for notification state.
            viewHolder.binding.presenceIndicator.setVisibility(View.GONE);
            viewHolder.binding.presenceIndicator.setStatus(null);
            viewHolder.binding.conversationTypeIndicator.setVisibility(View.VISIBLE);
            viewHolder.binding.conversationTypeIndicator.setImageResource(
                    R.drawable.ic_group_selected_16);
            viewHolder.binding.contactStatusMessage.setText(null);
            viewHolder.binding.contactStatusMessage.setVisibility(View.GONE);
            setConversationTitleIcon(
                    viewHolder.binding.conversationName,
                    isMuted ? R.drawable.ic_notifications_off_24dp : null);
        } else {
            viewHolder.binding.conversationTypeIndicator.setVisibility(View.GONE);
            viewHolder.binding.presenceIndicator.setVisibility(View.VISIBLE);
            final Contact contact = conversation.getContact();
            viewHolder.binding.presenceIndicator.setStatus(contact);
            final String statusMessage =
                    conversation.withSelf() ? null : getContactStatusMessage(contact);
            if (Strings.isNullOrEmpty(statusMessage)) {
                viewHolder.binding.contactStatusMessage.setText(null);
                viewHolder.binding.contactStatusMessage.setVisibility(View.GONE);
            } else {
                viewHolder.binding.contactStatusMessage.setText(statusMessage);
                viewHolder.binding.contactStatusMessage.setVisibility(View.VISIBLE);
            }
            setConversationTitleIcon(
                    viewHolder.binding.conversationName,
                    conversation.hasPermanentCounterpart()
                            ? R.drawable.ic_secret_chat_16dp
                            : null);
        }

        Contact contact = conversation.getContact();

        Account account = conversation.getAccount();
        final boolean showAccountIndicator =
                account != null
                        && activity.xmppConnectionService.getAccounts().size() > 1
                        && !ProfileNavigation.isConversationListScopedToProfileAccount(activity);

        viewHolder.accountIndicatorDrawable.setColor(
                showAccountIndicator
                        ? UIHelper.getAccountColor(activity, account.getJid())
                        : Color.TRANSPARENT);

        if (draft != null) {
            viewHolder.binding.conversationLastmsgImg.setVisibility(View.GONE);
            viewHolder.binding.conversationLastmsg.setText(draft.getMessage());
            viewHolder.binding.senderName.setText(R.string.draft);
            viewHolder.binding.senderName.setVisibility(View.VISIBLE);
            viewHolder.binding.conversationLastmsg.setTypeface(conversationBodyTypeface);
            viewHolder.binding.senderName.setTypeface(conversationBodyItalicTypeface);
        } else {
            final boolean fileAvailable = !message.isDeleted();
            final boolean showPreviewText;
            if (fileAvailable
                    && (message.isFileOrImage()
                    || message.treatAsDownloadable()
                    || message.isGeoUri())) {

                // Attachment rows use one compact pattern: thumbnail/type icon + text.
                // Images therefore read as [thumbnail] "Image", while video/audio/files keep
                // their corresponding icon/thumbnail and UIHelper description.
                showPreviewText = true;
                viewHolder.binding.conversationLastmsgImg.setVisibility(View.VISIBLE);

                if (MessageAdapter.shouldDisplayMediaPreview(message)) {

                    viewHolder.binding.conversationLastmsgImg.setImageResource(
                            R.drawable.ic_image_24dp);

                    setConversationPreviewSize(
                            viewHolder.binding.conversationLastmsgImg, 20);
                    viewHolder.binding.conversationLastmsgImg.setScaleType(
                            android.widget.ImageView.ScaleType.CENTER_CROP);
                    ImageViewCompat.setImageTintList(
                            viewHolder.binding.conversationLastmsgImg, null);
                    viewHolder.binding.conversationLastmsgImg.clearColorFilter();
                    viewHolder.binding.conversationLastmsgImg.setClipToOutline(true);

                    // Width/height enrichment is best effort for secure media. The committed
                    // image/video relation itself is enough to request a verified thumbnail.
                    activity.loadBitmap(
                            message,
                            viewHolder.binding.conversationLastmsgImg);

                } else {
                    final var attachment = Attachment.of(message);
                    final @DrawableRes int imageResource =
                            MediaAdapter.getImageDrawable(attachment);

                    viewHolder.binding.conversationLastmsgImg.setImageResource(imageResource);
                    setConversationPreviewSize(
                            viewHolder.binding.conversationLastmsgImg, 18);
                    viewHolder.binding.conversationLastmsgImg.setScaleType(
                            android.widget.ImageView.ScaleType.CENTER_INSIDE);
                    ImageViewCompat.setImageTintList(
                            viewHolder.binding.conversationLastmsgImg,
                            ColorStateList.valueOf(
                                    MaterialColors.getColor(
                                            viewHolder.binding.conversationLastmsgImg,
                                            com.google.android.material.R.attr.colorOnSurfaceVariant)));
                }

            } else {
                viewHolder.binding.conversationLastmsgImg.setVisibility(View.GONE);
                showPreviewText = true;
            }
            final Pair<CharSequence, Boolean> preview =
                    UIHelper.getMessagePreview(
                            activity,
                            message,
                            viewHolder.binding.conversationLastmsg.getCurrentTextColor());
            if (showPreviewText) {
                final CharSequence previewText;
                if (fileAvailable
                        && message.isFileOrImage()
                        && message.getTransferable() == null) {
                    previewText = conversationAttachmentLabel(message);
                } else if (fileAvailable && message.isFileOrImage()) {
                    previewText = StringUtils.capitalize(preview.first.toString());
                } else {
                    previewText = preview.first;
                }
                viewHolder.binding.conversationLastmsg.setText(UIHelper.shorten(previewText));
            } else {
                viewHolder.binding.conversationLastmsgImg.setContentDescription(preview.first);
            }
            viewHolder.binding.conversationLastmsg.setVisibility(
                    showPreviewText ? View.VISIBLE : View.GONE);
            final boolean stableAttachmentLabel =
                    fileAvailable
                            && message.isFileOrImage()
                            && message.getTransferable() == null;
            if (preview.second && !stableAttachmentLabel) {
                viewHolder.binding.conversationLastmsg.setTypeface(conversationBodyItalicTypeface);
            } else {
                viewHolder.binding.conversationLastmsg.setTypeface(conversationBodyTypeface);
            }
            viewHolder.binding.senderName.setTypeface(conversationBodyTypeface);
            if (status == Message.STATUS_RECEIVED) {
                if (conversation.getMode() == Conversation.MODE_MULTI) {
                    viewHolder.binding.senderName.setVisibility(View.VISIBLE);
                    final var displayName = UIHelper.getMessageDisplayName(message);
                    final var displayNameParts = displayName.split("\\s+");
                    // Skip when nickname only consists of blank chars
                    if (displayNameParts.length == 0) {
                        viewHolder.binding.senderName.setText(String.format("%s:", displayName));
                    } else {
                        viewHolder.binding.senderName.setText(
                                String.format("%s:", displayNameParts[0]));
                    }
                } else {
                    viewHolder.binding.senderName.setVisibility(View.GONE);
                }
            } else {
                viewHolder.binding.senderName.setVisibility(View.GONE);
            }
        }

        final Optional<OngoingRtpSession> ongoingCall;
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            ongoingCall = Optional.absent();
        } else {
            ongoingCall =
                    activity.xmppConnectionService
                            .getJingleConnectionManager()
                            .getOngoingRtpConnection(conversation.getContact());
        }

        if (ongoingCall.isPresent()) {
            viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
            viewHolder.binding.notificationStatus.setImageResource(
                    R.drawable.ic_phone_in_talk_24dp);
        } else if (isMuc) {
            // MUC mute is shown beside the title; do not consume preview/unread space for it.
            viewHolder.binding.notificationStatus.setVisibility(View.GONE);
        } else if (mutedTill == Long.MAX_VALUE) {
            viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
            viewHolder.binding.notificationStatus.setImageResource(
                    R.drawable.ic_notifications_off_24dp);
        } else if (isMuted) {
            viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
            viewHolder.binding.notificationStatus.setImageResource(
                    R.drawable.ic_notifications_paused_24dp);
        } else {
            viewHolder.binding.notificationStatus.setVisibility(View.GONE);
        }

        long timestamp;
        if (draft != null) {
            timestamp = draft.getTimestamp();
        } else {
            timestamp = conversation.getLatestMessage().getTimeSent();
        }
        viewHolder.binding.pinnedOnTop.setVisibility(
                conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)
                        ? View.VISIBLE
                        : View.GONE);
        viewHolder.binding.conversationLastupdate.setText(
                UIHelper.readableTimeDifference(activity, timestamp, false));
        AvatarWorkerTask.loadAvatar(
                conversation,
                viewHolder.binding.conversationImage,
                R.dimen.avatar_on_conversation_overview);
        viewHolder.itemView.setOnClickListener(v -> listener.onConversationClick(v, conversation));
        viewHolder.itemView.setOnLongClickListener(
                v ->
                        longClickListener != null
                                && longClickListener.onConversationLongClick(v, conversation));
    }

    public void setConversationClickListener(OnConversationClickListener listener) {
        this.listener = listener;
    }

    public void setConversationLongClickListener(OnConversationLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void insert(Conversation c, int position) {
        final int target = Math.min(Math.max(position, 0), conversations.size());
        conversations.add(target, c);
        setFilter(filterQuery);
    }

    public void remove(Conversation conversation, int position) {
        conversations.remove(conversation);
        setFilter(filterQuery);
    }

    @Nullable
    private Account findAccountForTag(int position) {
        Account account = null;

        if (activity.xmppConnectionService.getAccounts().size() == 1) {
            return activity.xmppConnectionService.getAccounts().get(0);
        }

        for (int i = position; i >= 0; i--) {
            Object prev = items.get(i);
            if (prev instanceof Account) {
                account = (Account) prev;
                break;
            }
        }

        return account;
    }

    private int getChildCount(Account account, @Nullable ListItem.Tag tag) {
        if (tag == null) {
            int res = 0;

            for (Conversation c : conversations) {
                if (c.getAccount() == account) {
                    res++;
                }
            }

            return res;
        } else {
            if (account == null) {
                return 0;
            }

            Map<ListItem.Tag, Set<Conversation>> childTags = groupedItems.get(account);
            if (childTags == null) {
                return 0;
            }

            Set<Conversation> childConversations = childTags.get(tag);
            if (childConversations == null) {
                return 0;
            }

            return childConversations.size();
        }
    }

    private static final class ConversationRowState {
        final String uuid;
        final String name;
        final String counterpart;
        final String latestUuid;
        final String latestBody;
        final String latestMime;
        final String latestSecureName;
        final String latestRelativePath;
        final String latestSender;
        final String draft;
        final String presence;
        final String avatarKey;
        final int status;
        final int type;
        final int unreadCount;
        final int transferStatus;
        final long timestamp;
        final boolean read;
        final boolean deleted;
        final boolean muted;
        final boolean pinned;
        final boolean selected;
        final boolean permanentCounterpart;
        final boolean showAccountIndicator;

        private ConversationRowState(
                final String uuid,
                final String name,
                final String counterpart,
                final String latestUuid,
                final String latestBody,
                final String latestMime,
                final String latestSecureName,
                final String latestRelativePath,
                final String latestSender,
                final String draft,
                final String presence,
                final String avatarKey,
                final int status,
                final int type,
                final int unreadCount,
                final int transferStatus,
                final long timestamp,
                final boolean read,
                final boolean deleted,
                final boolean muted,
                final boolean pinned,
                final boolean selected,
                final boolean permanentCounterpart,
                final boolean showAccountIndicator) {
            this.uuid = uuid;
            this.name = name;
            this.counterpart = counterpart;
            this.latestUuid = latestUuid;
            this.latestBody = latestBody;
            this.latestMime = latestMime;
            this.latestSecureName = latestSecureName;
            this.latestRelativePath = latestRelativePath;
            this.latestSender = latestSender;
            this.draft = draft;
            this.presence = presence;
            this.avatarKey = avatarKey;
            this.status = status;
            this.type = type;
            this.unreadCount = unreadCount;
            this.transferStatus = transferStatus;
            this.timestamp = timestamp;
            this.read = read;
            this.deleted = deleted;
            this.muted = muted;
            this.pinned = pinned;
            this.selected = selected;
            this.permanentCounterpart = permanentCounterpart;
            this.showAccountIndicator = showAccountIndicator;
        }

        static ConversationRowState capture(
                final XmppActivity activity, final Conversation conversation) {
            final Message latest = conversation.getLatestMessage();
            final boolean read = conversation.isRead();
            final Conversation.Draft draft = read ? conversation.getDraft() : null;
            final Contact contact = conversation.getContact();
            final String presence =
                    contact == null
                            ? ""
                            : String.valueOf(contact.getShownStatus())
                                    + '|'
                                    + String.valueOf(getContactStatusMessage(contact))
                                    + '|'
                                    + conversation.getAccount().isOnlineAndConnected();
            final String avatarKey;
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                avatarKey = String.valueOf(conversation.getMucOptions().getAvatar());
            } else if (contact == null) {
                avatarKey = "";
            } else {
                avatarKey =
                        String.valueOf(contact.getAvatarFilename())
                                + '|'
                                + String.valueOf(contact.getProfilePhoto());
            }
            final int transferStatus =
                    latest == null || latest.getTransferable() == null
                            ? Integer.MIN_VALUE
                            : latest.getTransferable().getStatus();
            final String latestSender =
                    latest == null ? "" : UIHelper.getMessageDisplayName(latest);
            final long timestamp =
                    draft != null
                            ? draft.getTimestamp()
                            : (latest == null ? 0L : latest.getTimeSent());
            return new ConversationRowState(
                    conversation.getUuid(),
                    String.valueOf(conversation.getName()),
                    String.valueOf(conversation.getNextCounterpart()),
                    latest == null ? "" : latest.getUuid(),
                    latest == null ? "" : latest.getBody(),
                    latest == null ? "" : String.valueOf(latest.getMimeType()),
                    latest == null ? "" : String.valueOf(latest.getSecureMediaFileName()),
                    latest == null ? "" : String.valueOf(latest.getRelativeFilePath()),
                    latestSender,
                    draft == null ? "" : draft.getMessage(),
                    presence,
                    avatarKey,
                    latest == null ? Integer.MIN_VALUE : latest.getStatus(),
                    latest == null ? Integer.MIN_VALUE : latest.getType(),
                    conversation.unreadCount(),
                    transferStatus,
                    timestamp,
                    read,
                    latest != null && latest.isDeleted(),
                    conversation.isMuted(),
                    conversation.getBooleanAttribute(
                            Conversation.ATTRIBUTE_PINNED_ON_TOP, false),
                    conversation == ConversationFragment.getConversation(activity),
                    conversation.hasPermanentCounterpart(),
                    activity.xmppConnectionService.getAccounts().size() > 1
                            && !ProfileNavigation.isConversationListScopedToProfileAccount(activity));
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ConversationRowState)) {
                return false;
            }
            final ConversationRowState that = (ConversationRowState) other;
            return status == that.status
                    && type == that.type
                    && unreadCount == that.unreadCount
                    && transferStatus == that.transferStatus
                    && timestamp == that.timestamp
                    && read == that.read
                    && deleted == that.deleted
                    && muted == that.muted
                    && pinned == that.pinned
                    && selected == that.selected
                    && permanentCounterpart == that.permanentCounterpart
                    && showAccountIndicator == that.showAccountIndicator
                    && Objects.equals(uuid, that.uuid)
                    && Objects.equals(name, that.name)
                    && Objects.equals(counterpart, that.counterpart)
                    && Objects.equals(latestUuid, that.latestUuid)
                    && Objects.equals(latestBody, that.latestBody)
                    && Objects.equals(latestMime, that.latestMime)
                    && Objects.equals(latestSecureName, that.latestSecureName)
                    && Objects.equals(latestRelativePath, that.latestRelativePath)
                    && Objects.equals(latestSender, that.latestSender)
                    && Objects.equals(draft, that.draft)
                    && Objects.equals(presence, that.presence)
                    && Objects.equals(avatarKey, that.avatarKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    uuid,
                    name,
                    counterpart,
                    latestUuid,
                    latestBody,
                    latestMime,
                    latestSecureName,
                    latestRelativePath,
                    latestSender,
                    draft,
                    presence,
                    avatarKey,
                    status,
                    type,
                    unreadCount,
                    transferStatus,
                    timestamp,
                    read,
                    deleted,
                    muted,
                    pinned,
                    selected,
                    permanentCounterpart,
                    showAccountIndicator);
        }
    }

    public interface OnConversationClickListener {
        void onConversationClick(View view, Conversation conversation);
    }

    public interface OnConversationLongClickListener {
        boolean onConversationLongClick(View view, Conversation conversation);
    }

    public static class ConversationViewHolder extends RecyclerView.ViewHolder {
        public final ItemConversationBinding binding;
        private final GradientDrawable accountIndicatorDrawable;

        private ConversationViewHolder(ItemConversationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            final int avatarSize =
                    binding.getRoot()
                            .getResources()
                            .getDimensionPixelSize(R.dimen.avatar_on_conversation_overview);
            final ViewGroup.LayoutParams accountIndicatorLayoutParams =
                    binding.accountIndicator.getLayoutParams();
            accountIndicatorLayoutParams.width = avatarSize;
            accountIndicatorLayoutParams.height = avatarSize;
            binding.accountIndicator.setLayoutParams(accountIndicatorLayoutParams);
            final float density = binding.getRoot().getResources().getDisplayMetrics().density;
            binding.accountIndicator.setTranslationX(13f * density);
            binding.accountIndicator.setTranslationY(3f * density);

            this.accountIndicatorDrawable = new GradientDrawable();
            this.accountIndicatorDrawable.setShape(GradientDrawable.OVAL);
            this.accountIndicatorDrawable.setColor(Color.TRANSPARENT);
            binding.accountIndicator.setBackground(this.accountIndicatorDrawable);

            binding.conversationLastmsgImg.setClipToOutline(true);
            binding.conversationLastmsgImg.setOutlineProvider(
                    new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            outline.setRoundRect(
                                    0,
                                    0,
                                    view.getWidth(),
                                    view.getHeight(),
                                    6f
                            );
                        }
                    });

            binding.getRoot().setLongClickable(true);
        }
    }

    static class AccountViewHolder extends RecyclerView.ViewHolder {
        private TextView text;
        private View arrow;

        private AccountViewHolder(ViewGroup parent) {
            super(LayoutInflater.from(parent.getContext()).inflate(R.layout.contact_account, parent, false));
            text = itemView.findViewById(R.id.text);
            arrow = itemView.findViewById(R.id.arrow);
        }
    }

    static class TagViewHolder extends RecyclerView.ViewHolder {
        private TextView text;
        private View arrow;

        private TagViewHolder(ViewGroup parent) {
            super(LayoutInflater.from(parent.getContext()).inflate(R.layout.contact_group, parent, false));
            text = itemView.findViewById(R.id.text);
            arrow = itemView.findViewById(R.id.arrow);
        }
    }
}
