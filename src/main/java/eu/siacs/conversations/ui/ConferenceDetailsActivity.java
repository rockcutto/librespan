package eu.siacs.conversations.ui;

import static eu.siacs.conversations.entities.Bookmark.printableValue;
import static eu.siacs.conversations.utils.StringUtils.changed;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SwitchCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMucDetailsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.User;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.OnConversationUpdate;
import eu.siacs.conversations.services.XmppConnectionService.OnMucRosterUpdate;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.adapter.UserPreviewAdapter;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.GridManager;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.MucConfiguration;
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.xmpp.Jid;
import me.drakeet.support.toast.ToastCompat;

public class ConferenceDetailsActivity extends XmppActivity
        implements OnConversationUpdate,
                OnMucRosterUpdate,
                XmppConnectionService.OnAffiliationChanged,
                XmppConnectionService.OnConfigurationPushed,
                XmppConnectionService.OnRoomDestroy,
                TextWatcher,
                OnMediaLoaded {
    public static final String ACTION_VIEW_MUC = "view_muc";

    private Conversation mConversation;
    private ActivityMucDetailsBinding binding;
    private MediaAdapter mMediaAdapter;
    private UserPreviewAdapter mUserPreviewAdapter;
    private String uuid = null;

    private boolean mAdvancedMode = false;
    private static final int COLLAPSED_SUBJECT_MAX_LINES = 8;
    private boolean mSubjectExpanded = false;
    private boolean mSubjectCanExpand = false;
    private String mRenderedSubject = null;

    private final UiCallback<Conversation> renameCallback =
            new UiCallback<Conversation>() {
                @Override
                public void success(Conversation object) {
                    displayToast(getString(R.string.your_nick_has_been_changed));
                    runOnUiThread(
                            () -> {
                                updateView();
                            });
                }

                @Override
                public void error(final int errorCode, Conversation object) {
                    displayToast(getString(errorCode));
                }

                @Override
                public void userInputRequired(PendingIntent pi, Conversation object) {}
            };

    public static void open(final Activity activity, final Conversation conversation) {
        Intent intent = new Intent(activity, ConferenceDetailsActivity.class);
        intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);

        Conversation parentConversation = conversation.getParentConversation();

        String uuid;
        if (parentConversation != null) {
            uuid = parentConversation.getUuid();
        } else {
            uuid = conversation.getUuid();
        }

        intent.putExtra("uuid", uuid);

        Jid counterpart = conversation.getNextCounterpart();

        if (counterpart != null) {
            intent.putExtra("counterpart", counterpart.toString());
        }

        activity.startActivity(intent);
    }

    private final OnClickListener mNotifyStatusClickListener =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    final MaterialAlertDialogBuilder builder =
                            new MaterialAlertDialogBuilder(ConferenceDetailsActivity.this);
                    builder.setTitle(R.string.pref_notification_settings);
                    String[] choices = {
                        getString(R.string.notify_on_all_messages),
                        getString(R.string.notify_only_when_highlighted),
                        getString(R.string.notify_never)
                    };
                    final AtomicInteger choice;
                    if (mConversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0)
                            == Long.MAX_VALUE) {
                        choice = new AtomicInteger(2);
                    } else {
                        choice = new AtomicInteger(mConversation.alwaysNotify() ? 0 : 1);
                    }
                    builder.setSingleChoiceItems(
                            choices, choice.get(), (dialog, which) -> choice.set(which));
                    builder.setNegativeButton(R.string.cancel, null);
                    builder.setPositiveButton(
                            R.string.ok,
                            (dialog, which) -> {
                                if (choice.get() == 2) {
                                    mConversation.setMutedTill(Long.MAX_VALUE);
                                } else {
                                    mConversation.setMutedTill(0);
                                    mConversation.setAttribute(
                                            Conversation.ATTRIBUTE_ALWAYS_NOTIFY,
                                            String.valueOf(choice.get() == 0));
                                }
                                xmppConnectionService.updateConversation(mConversation);
                                updateView();
                            });
                    builder.create().show();
                }
            };

    private final OnClickListener mChangeConferenceSettings =
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    final MucOptions mucOptions = mConversation.getMucOptions();
                    final MaterialAlertDialogBuilder builder =
                            new MaterialAlertDialogBuilder(ConferenceDetailsActivity.this);
                    MucConfiguration configuration =
                            MucConfiguration.get(
                                    ConferenceDetailsActivity.this, mAdvancedMode, mucOptions);
                    builder.setTitle(configuration.title);
                    final boolean[] values = configuration.values;
                    final View optionsView =
                            LayoutInflater.from(ConferenceDetailsActivity.this)
                                    .inflate(R.layout.dialog_binary_settings, null, false);
                    final LinearLayout optionsContainer =
                            optionsView.findViewById(R.id.binary_settings_container);
                    for (int i = 0; i < configuration.names.length; i++) {
                        final int index = i;
                        final View row =
                                LayoutInflater.from(ConferenceDetailsActivity.this)
                                        .inflate(
                                                R.layout.item_binary_setting,
                                                optionsContainer,
                                                false);
                        final TextView title = row.findViewById(R.id.binary_setting_title);
                        final SwitchCompat toggle = row.findViewById(R.id.binary_setting_switch);
                        title.setText(configuration.names[index]);
                        toggle.setOnCheckedChangeListener(null);
                        toggle.setChecked(values[index]);
                        toggle.setOnCheckedChangeListener(
                                (button, checked) -> values[index] = checked);
                        row.setOnClickListener(clickedView -> toggle.toggle());
                        optionsContainer.addView(row);
                    }
                    builder.setView(optionsView);
                    builder.setNegativeButton(R.string.cancel, null);
                    builder.setPositiveButton(
                            R.string.confirm,
                            (dialog, which) -> {
                                final Bundle options = configuration.toBundle(values);
                                options.putString("muc#roomconfig_persistentroom", "1");
                                if (options.containsKey("muc#roomconfig_allowinvites")) {
                                    options.putString(
                                            "{http://prosody.im/protocol/muc}roomconfig_allowmemberinvites",
                                            options.getString("muc#roomconfig_allowinvites"));
                                }
                                xmppConnectionService.pushConferenceConfiguration(
                                        mConversation, options, ConferenceDetailsActivity.this);
                            });
                    builder.create().show();
                }
            };

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    @Override
    public void onMucRosterUpdate() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        updateView();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_muc_details);
        this.binding.changeConferenceButton.setOnClickListener(this.mChangeConferenceSettings);
        this.binding.editNickButton.setOnClickListener(
                v ->
                        quickEdit(
                                mConversation.getMucOptions().getActualNick(),
                                R.string.nickname,
                                value -> {
                                    if (xmppConnectionService.renameInMuc(
                                            mConversation, value, renameCallback)) {
                                        return null;
                                    } else {
                                        return getString(R.string.invalid_muc_nick);
                                    }
                                }));
        this.mAdvancedMode = false;
        this.binding.mucInfoMore.setVisibility(View.GONE);
        this.binding.notificationStatusAction.setOnClickListener(this.mNotifyStatusClickListener);
        this.binding.yourPhoto.setOnClickListener(
                v -> {
                    final MucOptions mucOptions = mConversation.getMucOptions();
                    if (!mucOptions.hasVCards()) {
                        Toast.makeText(
                                        this,
                                        R.string.host_does_not_support_group_chat_avatars,
                                        Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    if (!mucOptions
                            .getSelf()
                            .getAffiliation()
                            .ranks(MucOptions.Affiliation.OWNER)) {
                        Toast.makeText(
                                        this,
                                        R.string.only_the_owner_can_change_group_chat_avatar,
                                        Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    final Intent intent =
                            new Intent(this, PublishGroupChatProfilePictureActivity.class);
                    intent.putExtra("uuid", mConversation.getUuid());
                    startActivity(intent);
                });
        this.binding.editMucNameButton.setContentDescription(getString(R.string.more_options));
        this.binding.groupHeaderShare.setOnClickListener(v -> showGroupShareActions());
        this.binding.editMucNameButton.setOnClickListener(this::onGroupHeaderActionClicked);
        this.binding.mucTitle.setOnClickListener(
                view -> {
                    if (canEditMucDetails()) {
                        onMucEditButtonClicked(view);
                    }
                });
        this.binding.mucSubject.setOnClickListener(
                view -> {
                    if (mConversation != null
                            && mConversation.getMucOptions().canChangeSubject()) {
                        onMucEditButtonClicked(view);
                    }
                });
        this.binding.mucSubjectAdd.setOnClickListener(
                view -> {
                    if (mConversation != null
                            && mConversation.getMucOptions().canChangeSubject()) {
                        onMucEditButtonClicked(view);
                    }
                });
        this.binding.mucSubjectExpand.setOnClickListener(
                view -> setSubjectExpanded(!mSubjectExpanded));
        this.binding.mucScroll.addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (bottom != oldBottom && this.binding.mucEditSubject.hasFocus()) {
                        this.binding.mucEditSubject.post(this::ensureDescriptionEditorVisible);
                    }
                });
        this.binding.mucEditTitle.addTextChangedListener(this);
        this.binding.mucEditSubject.addTextChangedListener(this);
        this.binding.mucEditSubject.addTextChangedListener(
                new StylingHelper.MessageEditorStyler(this.binding.mucEditSubject));
        this.binding.editTags.addTextChangedListener(this);
        this.mMediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        this.mUserPreviewAdapter = new UserPreviewAdapter();
        this.binding.media.setAdapter(mMediaAdapter);
        this.binding.users.setAdapter(mUserPreviewAdapter);
        GridManager.setupLayoutManager(this, this.binding.media, R.dimen.media_size);
        GridManager.setupLayoutManager(this, this.binding.users, R.dimen.media_size);
        this.binding.invite.setOnClickListener(v -> inviteToConversation(mConversation));
        final View.OnClickListener openMucUsers =
                v -> {
                    if (mConversation == null) {
                        return;
                    }
                    Intent intent = new Intent(this, MucUsersActivity.class);
                    intent.putExtra("uuid", mConversation.getUuid());
                    startActivity(intent);
                };
        this.binding.showUsersAction.setOnClickListener(openMucUsers);
        this.binding.mucAdminModerationRow.setOnClickListener(openMucUsers);
        this.binding.mucAdminPermissionsRow.setOnClickListener(this.mChangeConferenceSettings);
    }

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }
        binding.mediaWrapper.setVisibility(View.GONE);
        mMediaAdapter.setAttachments(Collections.emptyList());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (menuItem.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case R.id.action_edit_muc_details:
                onMucEditButtonClicked(binding.editMucNameButton);
                break;
            case R.id.action_leave_muc:
                leaveMuc();
                break;
            case R.id.action_save_as_bookmark:
                saveAsBookmark();
                break;
            case R.id.action_delete_bookmark:
                deleteBookmark();
                break;
            case R.id.action_destroy_room:
                destroyRoom();
                break;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    private void configureCustomNotifications(final Conversation conversation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || conversation.getMode() != Conversational.MODE_MULTI) {
            return;
        }
        final var shortcut =
                xmppConnectionService
                        .getShortcutService()
                        .getShortcutInfo(conversation.getMucOptions(), conversation.getNextCounterpart());
        configureCustomNotification(shortcut);
    }

    @Override
    public boolean onContextItemSelected(@NonNull final MenuItem item) {
        final User user = mUserPreviewAdapter.getSelectedUser();
        if (user == null) {
            Toast.makeText(this, R.string.unable_to_perform_this_action, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!MucDetailsContextMenuHelper.onContextItemSelected(
                item, mUserPreviewAdapter.getSelectedUser(), this)) {
            return super.onContextItemSelected(item);
        }
        return true;
    }

    private void bindAdvancedModeSwitch() {
        final boolean advancedAccess = hasAdvancedMucAccess();
        this.mAdvancedMode = advancedAccess;
        this.binding.advancedModeRow.setVisibility(View.GONE);
        this.binding.mucInfoMore.setVisibility(View.GONE);
    }

    private boolean hasAdvancedMucAccess() {
        if (mConversation == null) {
            return false;
        }
        final User self = mConversation.getMucOptions().getSelf();
        return self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)
                || self.getRole().ranks(MucOptions.Role.MODERATOR);
    }

    private boolean canEditMucDetails() {
        if (mConversation == null) {
            return false;
        }
        final MucOptions mucOptions = mConversation.getMucOptions();
        final Bookmark bookmark = mConversation.getBookmark();
        final boolean canEditRoom =
                mucOptions.online()
                        && (mucOptions.getSelf()
                                        .getAffiliation()
                                        .ranks(MucOptions.Affiliation.OWNER)
                                || mucOptions.canChangeSubject());
        final boolean canEditBookmark =
                bookmark != null
                        && mConversation.getAccount().getXmppConnection() != null
                        && mConversation
                                .getAccount()
                                .getXmppConnection()
                                .getFeatures()
                                .bookmarks2();
        return canEditRoom || canEditBookmark;
    }

    private void onGroupHeaderActionClicked(final View view) {
        if (binding.mucEditor.getVisibility() == View.VISIBLE) {
            onMucEditButtonClicked(view);
        } else {
            showGroupOverflow(view);
        }
    }

    private void showGroupShareActions() {
        if (mConversation == null || mConversation.isPrivateAndNonAnonymous()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.group_card_share_title)
                .setItems(
                        new CharSequence[] {
                                getString(R.string.group_card_share_xmpp),
                                getString(R.string.group_card_show_qr)
                        },
                        (dialog, which) -> {
                            if (which == 0) {
                                shareLink(false);
                            } else {
                                showQrCode();
                            }
                        })
                .show();
    }

    private void showGroupOverflow(final View anchor) {
        final PopupMenu popupMenu =
                new PopupMenu(
                        new android.view.ContextThemeWrapper(
                                this, R.style.ThemeOverlay_App_PopupMenu),
                        anchor);
        popupMenu.inflate(R.menu.muc_details);
        if (popupMenu.getMenu() instanceof androidx.appcompat.view.menu.MenuBuilder) {
            ((androidx.appcompat.view.menu.MenuBuilder) popupMenu.getMenu())
                    .setGroupDividerEnabled(true);
        }
        configureGroupMenu(popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(
                item -> {
                    onOptionsItemSelected(item);
                    return true;
                });
        popupMenu.show();
    }

    private void configureGroupMenu(final Menu menu) {
        final boolean groupChat =
                mConversation != null && mConversation.isPrivateAndNonAnonymous();

        final MenuItem editDetails = menu.findItem(R.id.action_edit_muc_details);
        if (editDetails != null) {
            editDetails.setVisible(canEditMucDetails());
        }

        final MenuItem leaveMuc = menu.findItem(R.id.action_leave_muc);
        if (leaveMuc != null) {
            final boolean rootMuc =
                    mConversation != null && mConversation.getNextCounterpart() == null;
            leaveMuc.setVisible(
                    rootMuc
                            && mConversation.getStatus() != Conversation.STATUS_ARCHIVED
                            && !mConversation.isMucExplicitlyLeft());
            leaveMuc.setTitle(
                    groupChat
                            ? R.string.conversation_menu_leave_conference
                            : R.string.conversation_menu_leave_channel);
        }

        final MenuItem saveBookmark = menu.findItem(R.id.action_save_as_bookmark);
        final MenuItem deleteBookmark = menu.findItem(R.id.action_delete_bookmark);
        if (mConversation != null && mConversation.getBookmark() != null) {
            if (saveBookmark != null) {
                saveBookmark.setVisible(false);
            }
            if (deleteBookmark != null) {
                deleteBookmark.setVisible(true);
                deleteBookmark.setTitle(R.string.group_menu_remove_saved);
            }
        } else {
            if (saveBookmark != null) {
                saveBookmark.setVisible(true);
                saveBookmark.setTitle(
                        groupChat
                                ? R.string.group_menu_save_group
                                : R.string.group_menu_save_channel);
            }
            if (deleteBookmark != null) {
                deleteBookmark.setVisible(false);
            }
        }

        final MenuItem destroy = menu.findItem(R.id.action_destroy_room);
        if (destroy != null) {
            destroy.setTitle(
                    groupChat
                            ? R.string.group_menu_delete_group_for_all
                            : R.string.group_menu_delete_channel);
            destroy.setVisible(
                    mConversation != null
                            && mConversation
                                    .getMucOptions()
                                    .getSelf()
                                    .getAffiliation()
                                    .ranks(MucOptions.Affiliation.OWNER));
        }
    }

    public void onMucEditButtonClicked(View v) {
        if (this.binding.mucEditor.getVisibility() == View.GONE) {
            final MucOptions mucOptions = mConversation.getMucOptions();
            final boolean focusSubject =
                    v != null
                            && (v.getId() == R.id.muc_subject
                                    || v.getId() == R.id.muc_subject_add);
            final boolean focusTitle = v != null && v.getId() == R.id.muc_title;

            this.binding.mucEditor.setVisibility(View.VISIBLE);
            this.binding.mucEditorDetails.setVisibility(View.VISIBLE);
            this.binding.mucDisplay.setVisibility(View.GONE);
            this.binding.mucSubjectBlock.setVisibility(View.GONE);
            this.binding.tags.setVisibility(View.GONE);
            this.binding.editMucNameButton.setImageResource(R.drawable.ic_cancel_24dp);
            this.binding.editMucNameButton.setContentDescription(getString(R.string.cancel));

            final String name = mucOptions.getName();
            this.binding.mucEditTitle.setText("");
            final boolean owner =
                    mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER);
            if (owner || printableValue(name)) {
                this.binding.mucEditTitle.setVisibility(View.VISIBLE);
                if (name != null) {
                    this.binding.mucEditTitle.append(name);
                }
            } else {
                this.binding.mucEditTitle.setVisibility(View.GONE);
            }
            this.binding.mucEditTitle.setEnabled(owner);

            final String subject = mucOptions.getSubject();
            this.binding.mucEditSubject.setText("");
            if (subject != null) {
                this.binding.mucEditSubject.append(subject);
            }
            this.binding.mucEditSubject.setEnabled(mucOptions.canChangeSubject());

            final Bookmark bookmark = mConversation.getBookmark();
            if (bookmark != null
                    && mConversation.getAccount().getXmppConnection().getFeatures().bookmarks2()) {
                for (final ListItem.Tag group : bookmark.getGroupTags()) {
                    binding.editTags.addObjectSync(group);
                }
                ArrayList<ListItem.Tag> tags = new ArrayList<>();
                for (final Account account : xmppConnectionService.getAccounts()) {
                    for (Contact contact : account.getRoster().getContacts()) {
                        tags.addAll(contact.getTags(this));
                    }
                    for (Bookmark bmark : account.getBookmarks()) {
                        tags.addAll(bmark.getTags(this));
                    }
                }
                Comparator<Map.Entry<ListItem.Tag, Integer>> sortTagsBy =
                        Map.Entry.comparingByValue(Comparator.reverseOrder());
                sortTagsBy =
                        sortTagsBy.thenComparing(entry -> entry.getKey().getName());

                ArrayAdapter<ListItem.Tag> adapter =
                        new ArrayAdapter<>(
                                this,
                                android.R.layout.simple_list_item_1,
                                tags.stream()
                                        .collect(
                                                Collectors.toMap(
                                                        (x) -> x,
                                                        (t) -> 1,
                                                        (c1, c2) -> c1 + c2))
                                        .entrySet()
                                        .stream()
                                        .sorted(sortTagsBy)
                                        .map(e -> e.getKey())
                                        .collect(Collectors.toList()));
                binding.editTags.setAdapter(adapter);
                this.binding.editTags.setVisibility(View.VISIBLE);
            } else {
                this.binding.editTags.setVisibility(View.GONE);
            }

            if (mucOptions.canChangeSubject() && (focusSubject || !owner)) {
                focusMucEditorField(this.binding.mucEditSubject, true);
            } else if (owner && focusTitle) {
                focusMucEditorField(this.binding.mucEditTitle, false);
            }
        } else {
            String subject =
                    this.binding.mucEditSubject.isEnabled()
                            ? this.binding.mucEditSubject.getEditableText().toString().trim()
                            : null;
            String name =
                    this.binding.mucEditTitle.isEnabled()
                            ? this.binding.mucEditTitle.getEditableText().toString().trim()
                            : null;
            onMucInfoUpdated(subject, name);
            final Bookmark bookmark = mConversation.getBookmark();
            if (bookmark != null
                    && mConversation.getAccount().getXmppConnection().getFeatures().bookmarks2()) {
                bookmark.setGroups(
                        binding.editTags.getObjects().stream()
                                .map(tag -> tag.getName())
                                .collect(Collectors.toList()));
                xmppConnectionService.createBookmark(bookmark.getAccount(), bookmark);
            }

            SoftKeyboardUtils.hideSoftKeyboard(this);
            hideEditor();
            updateView();
        }
    }

    private void focusMucEditorField(final View field, final boolean keepDescriptionVisible) {
        field.requestFocus();
        field.post(
                () -> {
                    final android.view.inputmethod.InputMethodManager inputMethodManager =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (inputMethodManager != null) {
                        inputMethodManager.showSoftInput(
                                field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                    if (keepDescriptionVisible) {
                        field.postDelayed(this::ensureDescriptionEditorVisible, 180);
                    }
                });
    }

    private void ensureDescriptionEditorVisible() {
        if (binding == null
                || binding.mucEditorDetails.getVisibility() != View.VISIBLE
                || !binding.mucEditSubject.hasFocus()) {
            return;
        }
        final android.graphics.Rect visibleRect = new android.graphics.Rect();
        binding.mucEditSubject.getDrawingRect(visibleRect);
        binding.mucEditSubject.requestRectangleOnScreen(visibleRect, true);
    }

    private void bindSubject(final String subject) {
        if (binding.mucEditor.getVisibility() == View.VISIBLE) {
            binding.mucSubjectBlock.setVisibility(View.GONE);
            return;
        }

        if (!printableValue(subject)) {
            mRenderedSubject = null;
            mSubjectExpanded = false;
            mSubjectCanExpand = false;
            final boolean canAddDescription =
                    mConversation != null && mConversation.getMucOptions().canChangeSubject();
            binding.mucSubject.setVisibility(View.GONE);
            binding.mucSubjectAdd.setVisibility(
                    canAddDescription ? View.VISIBLE : View.GONE);
            binding.mucSubjectBlock.setVisibility(
                    canAddDescription ? View.VISIBLE : View.GONE);
            binding.mucSubjectExpand.setVisibility(View.GONE);
            return;
        }

        if (!subject.equals(mRenderedSubject)) {
            mRenderedSubject = subject;
            mSubjectExpanded = false;
            mSubjectCanExpand = false;
        }

        binding.mucSubjectAdd.setVisibility(View.GONE);
        binding.mucSubject.setVisibility(View.VISIBLE);
        final SpannableStringBuilder spannable = new SpannableStringBuilder(subject);
        StylingHelper.format(spannable, binding.mucSubject.getCurrentTextColor());
        MyLinkify.addLinks(spannable, false);
        binding.mucSubject.setText(spannable);
        binding.mucSubject.setAutoLinkMask(0);
        binding.mucSubject.setMovementMethod(LinkMovementMethod.getInstance());
        binding.mucSubjectBlock.setVisibility(View.VISIBLE);
        applySubjectExpansionState();

        binding.mucSubject.post(
                () -> {
                    if (binding == null || !subject.equals(mRenderedSubject)) {
                        return;
                    }
                    if (!mSubjectExpanded) {
                        final android.text.Layout layout = binding.mucSubject.getLayout();
                        if (layout != null
                                && layout.getLineCount() >= COLLAPSED_SUBJECT_MAX_LINES) {
                            final int lastVisibleLine = COLLAPSED_SUBJECT_MAX_LINES - 1;
                            final int ellipsisCount = layout.getEllipsisCount(lastVisibleLine);
                            final int visibleEnd = layout.getLineVisibleEnd(lastVisibleLine);
                            mSubjectCanExpand =
                                    ellipsisCount > 0 || visibleEnd < binding.mucSubject.length();
                        } else {
                            mSubjectCanExpand = false;
                        }
                    }
                    applySubjectExpansionState();
                });
    }

    private void setSubjectExpanded(final boolean expanded) {
        if (!mSubjectCanExpand) {
            return;
        }
        mSubjectExpanded = expanded;
        applySubjectExpansionState();
    }

    private void applySubjectExpansionState() {
        binding.mucSubject.setMaxLines(
                mSubjectExpanded ? Integer.MAX_VALUE : COLLAPSED_SUBJECT_MAX_LINES);
        binding.mucSubject.setEllipsize(
                mSubjectExpanded ? null : TextUtils.TruncateAt.END);
        binding.mucSubjectExpand.setText(
                mSubjectExpanded ? R.string.show_less : R.string.show_more);
        binding.mucSubjectExpand.setVisibility(
                mSubjectCanExpand ? View.VISIBLE : View.GONE);
    }

    private void hideEditor() {
        this.binding.mucEditor.setVisibility(View.GONE);
        this.binding.mucEditorDetails.setVisibility(View.GONE);
        this.binding.mucDisplay.setVisibility(View.VISIBLE);
        this.binding.editMucNameButton.setImageResource(R.drawable.ic_more_horiz_24dp);
        this.binding.editMucNameButton.setContentDescription(getString(R.string.more_options));
    }

    private void onMucInfoUpdated(String subject, String name) {
        final MucOptions mucOptions = mConversation.getMucOptions();
        if (mucOptions.canChangeSubject() && changed(mucOptions.getSubject(), subject)) {
            xmppConnectionService.pushSubjectToConference(mConversation, subject);
        }
        if (mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)
                && changed(mucOptions.getName(), name)) {
            Bundle options = new Bundle();
            options.putString("muc#roomconfig_persistentroom", "1");
            options.putString("muc#roomconfig_roomname", StringUtils.nullOnEmpty(name));
            xmppConnectionService.pushConferenceConfiguration(mConversation, options, this);
        }
    }

    @Override
    protected String getShareableUri(boolean http) {
        return mConversation == null
                ? null
                : "xmpp:" + mConversation.getJid().asBareJid() + "?join";
    }

    @Override
    public void onMediaLoaded(final List<Attachment> attachments) {
        runOnUiThread(
                () -> {
                    final int limit = GridManager.getCurrentColumnCount(binding.media);
                    mMediaAdapter.setAttachments(
                            attachments.subList(0, Math.min(limit, attachments.size())));
                    binding.mediaWrapper.setVisibility(
                            attachments.isEmpty() ? View.GONE : View.VISIBLE);
                });
    }

    private void leaveMuc() {
        if (mConversation == null || mConversation.getNextCounterpart() != null) {
            return;
        }
        xmppConnectionService.archiveConversation(mConversation);
        finish();
    }

    protected void saveAsBookmark() {
        xmppConnectionService.saveConversationAsBookmark(mConversation, mConversation.getMucOptions().getName());
    }

    protected void deleteBookmark() {
        final Account account = mConversation.getAccount();
        final Bookmark bookmark = mConversation.getBookmark();
        bookmark.setConversation(null);
        xmppConnectionService.deleteBookmark(account, bookmark);
        updateView();
    }

    protected void destroyRoom() {
        final boolean groupChat = mConversation != null && mConversation.isPrivateAndNonAnonymous();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(
                groupChat
                        ? R.string.group_menu_delete_group_for_all
                        : R.string.group_menu_delete_channel);
        builder.setMessage(
                groupChat
                        ? R.string.group_delete_group_dialog
                        : R.string.group_delete_channel_dialog);
        builder.setPositiveButton(
                R.string.delete,
                (dialog, which) -> {
                    xmppConnectionService.destroyRoom(
                            mConversation, ConferenceDetailsActivity.this);
                });
        builder.setNegativeButton(R.string.cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    @Override
    protected void onBackendConnected() {
        if (mPendingConferenceInvite != null) {
            handleConferenceInviteResult(mPendingConferenceInvite.execute(this));
            mPendingConferenceInvite = null;
        }
        if (getIntent().getAction().equals(ACTION_VIEW_MUC)) {
            this.uuid = getIntent().getExtras().getString("uuid");
        }
        if (uuid != null) {
            this.mConversation = xmppConnectionService.findConversationByUuid(uuid);
            if (this.mConversation != null) {
                mMediaAdapter.setAccountUuid(this.mConversation.getAccount().getUuid());
                final int limit = GridManager.getCurrentColumnCount(this.binding.media);
                xmppConnectionService.getAttachments(this.mConversation, limit, this);
                this.binding.showMedia.setOnClickListener(
                        (v) -> MediaBrowserActivity.launch(this, mConversation));
                updateView();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (this.binding.mucEditor.getVisibility() == View.VISIBLE) {
            hideEditor();
            updateView();
        } else {
            super.onBackPressed();
        }
    }

    private void updateView() {
        if (mConversation == null) {
            return;
        }

        String counterpart = getIntent().getStringExtra("counterpart");
        if (counterpart != null) {
            binding.mucPmInfo.setVisibility(View.VISIBLE);

            Jid counterpartJid = Jid.of(counterpart);

            binding.mucPmCounterpartName.setText(getResources().getString(R.string.muc_private_conversation_title, counterpartJid.getResource(), mConversation.getName()));
            binding.mucPmCounterpartJid.setText(counterpart);
            binding.mucPmCounterpartJid.setOnClickListener(v -> ShareUtil.copyJidToClipboard(ConferenceDetailsActivity.this, counterpartJid));

            Conversation conversation = xmppConnectionService.find(mConversation.getAccount(), mConversation.getJid(), counterpartJid);

            if (conversation != null) {
                binding.mucPmCounterpartAvatar.setVisibility(View.VISIBLE);
                AvatarWorkerTask.loadAvatar(conversation, binding.mucPmCounterpartAvatar, R.dimen.avatar_on_details_screen_size);
            } else {
                binding.mucPmCounterpartAvatar.setVisibility(View.GONE);
            }
        }

        final MucOptions mucOptions = mConversation.getMucOptions();
        final User self = mucOptions.getSelf();
        final String account = mConversation.getAccount().getJid().asBareJid().toString();
        final Bookmark bookmark = mConversation.getBookmark();
        bindAdvancedModeSwitch();
        this.binding.editMucNameButton.setVisibility(View.VISIBLE);
        final boolean showAccountContext =
                xmppConnectionService != null && xmppConnectionService.getAccounts().size() > 1;
        this.binding.detailsAccount.setVisibility(showAccountContext ? View.VISIBLE : View.GONE);
        if (showAccountContext) {
            this.binding.detailsAccount.setText(getString(R.string.using_account, account));
        }
        if (mConversation.isPrivateAndNonAnonymous()) {
            this.binding.jid.setText(
                    getString(R.string.hosted_on, mConversation.getJid().getDomain()));
            this.binding.jid.setVisibility(View.VISIBLE);
        } else {
            this.binding.jid.setVisibility(View.GONE);
        }
        this.binding.groupHeaderShare.setVisibility(
                mConversation.isPrivateAndNonAnonymous() ? View.GONE : View.VISIBLE);
        AvatarWorkerTask.loadAvatar(
                mConversation, binding.yourPhoto, R.dimen.avatar_on_details_screen_size);
        String roomName = mucOptions.getName();
        String subject = mucOptions.getSubject();
        final boolean hasTitle;
        if (printableValue(roomName)) {
            this.binding.mucTitle.setText(roomName);
            this.binding.mucTitle.setVisibility(View.VISIBLE);
            hasTitle = true;
        } else if (!printableValue(subject)) {
            this.binding.mucTitle.setText(mConversation.getName());
            hasTitle = true;
            this.binding.mucTitle.setVisibility(View.VISIBLE);
        } else {
            hasTitle = false;
            this.binding.mucTitle.setVisibility(View.GONE);
        }

        this.binding.mucJid.setText(mConversation.getJid().asBareJid().toString());
        binding.mucJid.setOnClickListener(v -> ShareUtil.copyJidToClipboard(ConferenceDetailsActivity.this, mConversation.getJid().asBareJid()));

        bindSubject(subject);
        this.binding.mucYourNick.setText(mucOptions.getActualNick());
        if (mucOptions.online()) {
            this.binding.usersWrapper.setVisibility(View.VISIBLE);
            this.binding.mucInfoMore.setVisibility(View.GONE);
            this.binding.mucRole.setVisibility(View.VISIBLE);
            this.binding.mucRole.setText(getSelfRoleStatus(self));

            final boolean adminAccess =
                    self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN);
            final boolean moderatorAccess =
                    self.getRole().ranks(MucOptions.Role.MODERATOR);
            final boolean managementAccess = adminAccess || moderatorAccess;
            final boolean ownerAccess =
                    self.getAffiliation().ranks(MucOptions.Affiliation.OWNER);
            this.binding.mucAdminCard.setVisibility(
                    managementAccess ? View.VISIBLE : View.GONE);
            if (managementAccess) {
                this.binding.mucAdminSummary.setText(
                        ownerAccess
                                ? R.string.muc_admin_owner_summary
                                : adminAccess
                                        ? R.string.muc_admin_admin_summary
                                        : R.string.muc_admin_moderator_summary);
                this.binding.mucAdminPermissionsRow.setVisibility(
                        ownerAccess ? View.VISIBLE : View.GONE);
            }

            if (managementAccess) {
                this.binding.mucSettings.setVisibility(View.GONE);
                this.binding.changeConferenceButton.setVisibility(View.GONE);
            } else if (!mucOptions.isPrivateAndNonAnonymous() && mucOptions.nonanonymous()) {
                this.binding.mucSettings.setVisibility(View.VISIBLE);
                this.binding.mucConferenceType.setText(
                        R.string.group_chat_will_make_your_jabber_id_public);
                this.binding.changeConferenceButton.setVisibility(View.INVISIBLE);
            } else {
                this.binding.mucSettings.setVisibility(View.GONE);
                this.binding.changeConferenceButton.setVisibility(View.GONE);
            }
        } else {
            this.binding.usersWrapper.setVisibility(View.GONE);
            this.binding.mucAdminCard.setVisibility(View.GONE);
            this.binding.mucInfoMore.setVisibility(View.GONE);
            this.binding.mucSettings.setVisibility(View.GONE);
            this.binding.mucRole.setVisibility(View.GONE);
            this.binding.changeConferenceButton.setVisibility(View.INVISIBLE);
        }

        long mutedTill = mConversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
        if (mutedTill == Long.MAX_VALUE) {
            this.binding.notificationStatusText.setText(R.string.group_card_notify_muted);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_off_24dp);
        } else if (System.currentTimeMillis() < mutedTill) {
            this.binding.notificationStatusText.setText(R.string.group_card_notify_paused);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_paused_24dp);
        } else if (mConversation.alwaysNotify()) {
            this.binding.notificationStatusText.setText(R.string.group_card_notify_all);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_24dp);
        } else {
            this.binding.notificationStatusText.setText(R.string.group_card_notify_mentions);
            this.binding.notificationStatusButton.setImageResource(R.drawable.ic_notifications_none_24dp);
        }
        final List<User> users = mucOptions.getUsers();
        Collections.sort(
                users,
                (a, b) -> {
                    if (b.getAffiliation().outranks(a.getAffiliation())) {
                        return 1;
                    } else if (a.getAffiliation().outranks(b.getAffiliation())) {
                        return -1;
                    } else {
                        if (a.getAvatar() != null && b.getAvatar() == null) {
                            return -1;
                        } else if (a.getAvatar() == null && b.getAvatar() != null) {
                            return 1;
                        } else {
                            return a.getComparableName().compareToIgnoreCase(b.getComparableName());
                        }
                    }
                });
        this.mUserPreviewAdapter.submitList(
                MucOptions.sub(users, GridManager.getCurrentColumnCount(binding.users)));
        this.binding.invite.setVisibility(mucOptions.canInvite() ? View.VISIBLE : View.GONE);
        this.binding.showUsersAction.setVisibility(users.size() > 0 ? View.VISIBLE : View.GONE);
        this.binding.showUsers.setText(
                getString(R.string.group_card_members_count, users.size()));
        this.binding.usersWrapper.setVisibility(
                users.size() > 0 || mucOptions.canInvite() ? View.VISIBLE : View.GONE);
        if (users.size() == 0) {
            this.binding.noUsersHints.setText(
                    mucOptions.isPrivateAndNonAnonymous()
                            ? R.string.no_users_hint_group_chat
                            : R.string.no_users_hint_channel);
            this.binding.noUsersHints.setVisibility(View.VISIBLE);
        } else {
            this.binding.noUsersHints.setVisibility(View.GONE);
        }

        if (bookmark == null || binding.mucEditor.getVisibility() == View.VISIBLE) {
            binding.tags.setVisibility(View.GONE);
            return;
        }

        List<ListItem.Tag> tagList = bookmark.getTags(this);
        if (tagList.size() == 0) {
            binding.tags.setVisibility(View.GONE);
        } else {
            final LayoutInflater inflater = getLayoutInflater();
            binding.tags.setVisibility(View.VISIBLE);
            binding.tags.removeAllViewsInLayout();
            for (final ListItem.Tag tag : tagList) {
                final TextView tv = (TextView) inflater.inflate(R.layout.list_item_tag, binding.tags, false);
                tv.setText(tag.getName());
                tv.setBackgroundColor(tag.getColor());
                binding.tags.addView(tv);
            }
        }
    }

    public static String getStatus(Context context, User user, final boolean advanced) {
        final String affiliation = context.getString(user.getAffiliation().getResId());
        if (!advanced) {
            return affiliation;
        }
        final String role = context.getString(user.getRole().getResId());
        return affiliation.equals(role)
                ? affiliation
                : String.format("%s (%s)", affiliation, role);
    }

    private String getSelfRoleStatus(final User user) {
        final String role = getString(user.getRole().getResId());
        if (user.getRole() == MucOptions.Role.VISITOR
                && mConversation != null
                && mConversation.getMucOptions().moderated()) {
            return getString(R.string.muc_your_role_read_only, role);
        }
        if (hasAdvancedMucAccess() && this.mAdvancedMode) {
            return getString(
                    R.string.muc_your_role_advanced,
                    role,
                    getString(user.getAffiliation().getResId()));
        }
        return getString(R.string.muc_your_role, role);
    }

    private String getStatus(User user) {
        return getStatus(
                this,
                user,
                hasAdvancedMucAccess() && this.mAdvancedMode);
    }

    @Override
    public void onAffiliationChangedSuccessful(Jid jid) {
        refreshUi();
    }

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, jid.asBareJid().toString()));
    }

    @Override
    public void onRoomDestroySucceeded() {
        finish();
    }

    @Override
    public void onRoomDestroyFailed() {
        final boolean groupChat = mConversation != null && mConversation.isPrivateAndNonAnonymous();
        displayToast(
                getString(
                        groupChat
                                ? R.string.could_not_destroy_room
                                : R.string.could_not_destroy_channel));
    }

    @Override
    public void onPushSucceeded() {
        displayToast(getString(R.string.modified_conference_options));
    }

    @Override
    public void onPushFailed() {
        displayToast(getString(R.string.could_not_modify_conference_options));
    }

    private void displayToast(final String msg) {
        runOnUiThread(
                () -> {
                    if (isFinishing()) {
                        return;
                    }
                    ToastCompat.makeText(this, msg, Toast.LENGTH_SHORT).show();
                });
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(Editable s) {
        if (mConversation == null) {
            return;
        }
        final MucOptions mucOptions = mConversation.getMucOptions();
        if (this.binding.mucEditor.getVisibility() == View.VISIBLE) {
            boolean subjectChanged =
                    changed(
                            binding.mucEditSubject.getEditableText().toString(),
                            mucOptions.getSubject());
            boolean nameChanged =
                    changed(
                            binding.mucEditTitle.getEditableText().toString(),
                            mucOptions.getName());
            final Bookmark bookmark = mConversation.getBookmark();
            if (subjectChanged || nameChanged || (bookmark != null && mConversation.getAccount().getXmppConnection().getFeatures().bookmarks2())) {
                this.binding.editMucNameButton.setImageResource(R.drawable.ic_done_24dp);
                this.binding.editMucNameButton.setContentDescription(getString(R.string.save));
            } else {
                this.binding.editMucNameButton.setImageResource(R.drawable.ic_cancel_24dp);
                this.binding.editMucNameButton.setContentDescription(getString(R.string.cancel));
            }
        }
    }
}
