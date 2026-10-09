package eu.siacs.conversations.ui;

import static android.view.View.VISIBLE;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.color.MaterialColors;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityStartConversationBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.OnRosterUpdate;
import eu.siacs.conversations.ui.adapter.ListItemAdapter;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.JidDialog;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.widget.AccountIndicator;
import eu.siacs.conversations.ui.widget.SwipeRefreshListFragment;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.IrregularUnicodeDetector;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.XmppConnection;

public class StartConversationActivity extends XmppActivity
        implements XmppConnectionService.OnConversationUpdate,
                OnRosterUpdate,
                OnUpdateBlocklist,
                CreatePrivateGroupChatDialog.CreateConferenceDialogListener,
                JoinConferenceDialog.JoinConferenceDialogListener,
                SwipeRefreshLayout.OnRefreshListener,
                CreatePublicChannelDialog.CreatePublicChannelDialogListener {

    private static final String PREF_KEY_CONTACT_INTEGRATION_CONSENT =
            "contact_list_integration_consent";

    public static final String EXTRA_INVITE_URI = "eu.siacs.conversations.invite_uri";

    private final int REQUEST_SYNC_CONTACTS = 0x28cf;
    private final int REQUEST_CREATE_CONFERENCE = 0x39da;
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<String> mInitialSearchValue = new PendingItem<>();
    public int conference_context_id;
    public int contact_context_id;
    private final List<ListItem> contacts = new ArrayList<>();
    private ExpandableListItemAdapter mContactsAdapter;
    private TagsAdapter mTagsAdapter = new TagsAdapter();
    private final List<ListItem> conferences = new ArrayList<>();
    private ExpandableListItemAdapter mConferenceAdapter;
    private final ArrayList<String> mActivatedAccounts = new ArrayList<>();
    private EditText mSearchEditText;
    private final AtomicBoolean mRequestedContactsPermission = new AtomicBoolean(false);
    private boolean mHideOfflineContacts = false;
    private boolean createdByViewIntent = false;
    private StartConversationListAdapter mUnifiedAdapter;

    boolean groupingEnabled = false;

    private final TextWatcher mSearchTextWatcher =
            new TextWatcher() {

                @Override
                public void afterTextChanged(Editable editable) {
                    filter(editable.toString());
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
            };
    private final ListItemAdapter.OnTagClickedListener mOnTagClickedListener =
            new ListItemAdapter.OnTagClickedListener() {
                @Override
                public void onTagClicked(String tag) {
                    if (mSearchEditText != null) {
                        mSearchEditText.setText("");
                        mSearchEditText.append(tag);
                        mSearchEditText.requestFocus();
                        filter(tag);
                    }
                }
            };
    private Pair<Integer, Intent> mPostponedActivityResult;
    private Toast mToast;
    private final UiCallback<Conversation> mAdhocConferenceCallback =
            new UiCallback<>() {
                @Override
                public void success(final Conversation conversation) {
                    runOnUiThread(
                            () -> {
                                hideToast();
                                switchToConversation(conversation);
                            });
                }

                @Override
                public void error(final int errorCode, Conversation object) {
                    runOnUiThread(() -> replaceToast(getString(errorCode)));
                }

                @Override
                public void userInputRequired(PendingIntent pi, Conversation object) {}
            };
    private ActivityStartConversationBinding binding;
    private final TextView.OnEditorActionListener mSearchDone =
            (v, actionId, event) -> {
                final int total = contacts.size() + conferences.size();
                if (total == 1) {
                    if (!contacts.isEmpty()) {
                        openConversation((Contact) contacts.get(0));
                    } else {
                        openConversationsForBookmark((Bookmark) conferences.get(0));
                    }
                    return true;
                }
                final String input =
                        mSearchEditText == null ? "" : mSearchEditText.getText().toString().trim();
                if (total == 0 && isValidJid(input)) {
                    showCreateContactDialog(Jid.of(input).toString(), null);
                    return true;
                }
                SoftKeyboardUtils.hideSoftKeyboard(StartConversationActivity.this);
                return true;
            };

    public static void populateAccountSpinner(
            final Context context,
            final List<String> accounts,
            final Spinner spinner) {
        if (accounts.isEmpty()) {
            ArrayAdapter<String> adapter =
                    new ArrayAdapter<>(
                            context,
                            R.layout.item_autocomplete,
                            Collections.singletonList(context.getString(R.string.no_accounts)));
            adapter.setDropDownViewResource(R.layout.item_autocomplete);
            spinner.setAdapter(adapter);
            spinner.setEnabled(false);
        } else {
            final ArrayAdapter<String> adapter =
                    new ArrayAdapter<>(context, R.layout.item_autocomplete, accounts);
            adapter.setDropDownViewResource(R.layout.item_autocomplete);
            spinner.setAdapter(adapter);
            spinner.setEnabled(true);
        }
    }

    public static void launch(Context context) {
        final Intent intent = new Intent(context, StartConversationActivity.class);
        context.startActivity(intent);
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, StartConversationActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    private static boolean isViewIntent(final Intent i) {
        return i != null
                && (Intent.ACTION_VIEW.equals(i.getAction())
                        || Intent.ACTION_SENDTO.equals(i.getAction())
                        || i.hasExtra(EXTRA_INVITE_URI));
    }

    protected void hideToast() {
        if (mToast != null) {
            mToast.cancel();
        }
    }

    protected void replaceToast(String msg) {
        hideToast();
        mToast = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        mToast.show();
    }

    @Override
    public void onRosterUpdate() {
        this.refreshUi();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_start_conversation);
        setSupportActionBar((Toolbar) binding.toolbarLayout.findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());

        mConferenceAdapter = new ExpandableListItemAdapter(this, conferences);
        mContactsAdapter = new ExpandableListItemAdapter(this, contacts);
        mContactsAdapter.setOnTagClickedListener(this.mOnTagClickedListener);

        mUnifiedAdapter = new StartConversationListAdapter();
        binding.results.setLayoutManager(new LinearLayoutManager(this));
        binding.results.setAdapter(mUnifiedAdapter);

        mSearchEditText = binding.searchField;
        mSearchEditText.addTextChangedListener(mSearchTextWatcher);
        mSearchEditText.setOnEditorActionListener(mSearchDone);

        binding.addContactAction.setOnClickListener(
                v -> {
                    final String input = mSearchEditText.getText().toString().trim();
                    final String prefilled = isValidJid(input) ? Jid.of(input).toString() : null;
                    showCreateContactDialog(prefilled, null);
                });
        binding.newGroupAction.setOnClickListener(v -> showCreatePrivateGroupChatDialog());
        binding.channelsAction.setOnClickListener(v -> showChannelActions());

        binding.emptyPrimaryAction.setOnClickListener(
                v -> handleEmptyPrimaryAction());
        binding.emptySecondaryAction.setOnClickListener(
                v -> handleEmptySecondaryAction());

        final SharedPreferences preferences = getPreferences();
        this.mHideOfflineContacts =
                QuickConversationsService.isConversations()
                        && preferences.getBoolean("hide_offline", false);
        binding.onlineOnlyFilter.setVisibility(
                QuickConversationsService.isQuicksy() ? View.GONE : View.VISIBLE);
        binding.onlineOnlyFilter.setChecked(this.mHideOfflineContacts);
        binding.onlineOnlyFilter.setOnCheckedChangeListener(
                (button, checked) -> {
                    mHideOfflineContacts = checked;
                    preferences.edit().putBoolean("hide_offline", checked).apply();
                    filter(mSearchEditText.getText().toString());
                });

        final Intent intent;
        if (savedInstanceState == null) {
            intent = getIntent();
        } else {
            createdByViewIntent = savedInstanceState.getBoolean("created_by_view_intent", false);
            final String search = savedInstanceState.getString("search");
            if (search != null) {
                mInitialSearchValue.push(search);
            }
            intent = savedInstanceState.getParcelable("intent");
        }

        if (savedInstanceState == null) {
            groupingEnabled =
                    preferences.getBoolean(
                            SettingsActivity.GROUP_BY_TAGS,
                            getResources().getBoolean(R.bool.group_by_tags));
        } else {
            groupingEnabled = savedInstanceState.getBoolean("groupingEnabled");
        }

        if (isViewIntent(intent)) {
            pendingViewIntent.push(intent);
            createdByViewIntent = true;
            setIntent(createLauncherIntent(this));
        }

        mRequestedContactsPermission.set(
                savedInstanceState != null
                        && savedInstanceState.getBoolean("requested_contacts_permission", false));

        final String initialSearchValue = mInitialSearchValue.pop();
        if (initialSearchValue != null) {
            mSearchEditText.setText(initialSearchValue);
            mSearchEditText.setSelection(mSearchEditText.length());
        }
    }

    public static boolean isValidJid(final String input) {
        try {
            final Jid jid = Jid.ofUserInput(input);
            return !jid.isDomainJid();
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        Intent pendingIntent = pendingViewIntent.peek();
        savedInstanceState.putParcelable(
                "intent", pendingIntent != null ? pendingIntent : getIntent());
        savedInstanceState.putBoolean(
                "requested_contacts_permission", mRequestedContactsPermission.get());
        savedInstanceState.putBoolean("created_by_view_intent", createdByViewIntent);
        savedInstanceState.putBoolean("groupingEnabled", groupingEnabled);
        savedInstanceState.putString(
                "search",
                mSearchEditText != null ? mSearchEditText.getText().toString() : null);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        } else {
            if (pendingViewIntent.peek() == null) {
                askForContactsPermissions();
            }
        }
        if (pendingViewIntent.peek() == null) {
            if (askForContactsPermissions()) {
                return;
            }
            requestNotificationPermissionIfNeeded();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATION);
        }
    }

    @Override
    public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (xmppConnectionServiceBound) {
            processViewIntent(intent);
        } else {
            pendingViewIntent.push(intent);
        }
        setIntent(createLauncherIntent(this));
    }

    protected void openConversationForContact(int position) {
        openConversation(contacts.get(position));
    }

    protected void openConversation(ListItem item) {
        if (item instanceof Contact) {
            openConversationForContact((Contact) item);
        } else {
            openConversationsForBookmark((Bookmark) item);
        }
    }

    protected void openConversationForContact(Contact contact) {
        Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), null, false, false, true, null);
        SoftKeyboardUtils.hideSoftKeyboard(this);
        switchToConversation(conversation);
    }

    protected void openConversationForBookmark(int position) {
        Bookmark bookmark = (Bookmark) conferences.get(position);
        openConversationsForBookmark(bookmark);
    }

    protected void shareBookmarkUri() {
        shareBookmarkUri(conference_context_id);
    }

    protected void shareBookmarkUri(int position) {
        Bookmark bookmark = (Bookmark) conferences.get(position);
        shareAsChannel(this, bookmark.getJid().asBareJid().toString());
    }

    public static void shareAsChannel(final Context context, final String address) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_TEXT, "xmpp:" + address + "?join");
        shareIntent.setType("text/plain");
        try {
            context.startActivity(
                    Intent.createChooser(shareIntent, context.getText(R.string.share_uri_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    protected void openConversationsForBookmark(final Bookmark bookmark) {
        final Jid jid = bookmark.getFullJid();
        if (jid == null) {
            Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_SHORT).show();
            return;
        }
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        bookmark.getAccount(), jid, null, true, true, true, null);
        bookmark.setConversation(conversation);
        if (!bookmark.autojoin()) {
            bookmark.setAutojoin(true);
            xmppConnectionService.createBookmark(bookmark.getAccount(), bookmark);
        }
        SoftKeyboardUtils.hideSoftKeyboard(this);
        switchToConversation(conversation);
    }

    protected void openDetailsForContact() {
        int position = contact_context_id;
        Contact contact = (Contact) contacts.get(position);
        switchToContactDetails(contact);
    }

    protected void showQrForContact() {
        int position = contact_context_id;
        Contact contact = (Contact) contacts.get(position);
        showQrCode("xmpp:" + contact.getJid().asBareJid().toString());
    }

    protected void toggleContactBlock() {
        final int position = contact_context_id;
        BlockContactDialog.show(this, (Contact) contacts.get(position));
    }

    protected void deleteContact() {
        final int position = contact_context_id;
        final Contact contact = (Contact) contacts.get(position);
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.action_delete_contact);
        builder.setMessage(
                JidDialog.style(this, R.string.remove_contact_text, contact.getJid().toString()));
        builder.setPositiveButton(
                R.string.delete,
                (dialog, which) -> {
                    xmppConnectionService.deleteContactOnServer(contact);
                    filter(mSearchEditText.getText().toString());
                });
        builder.create().show();
    }

    protected void deleteConference() {
        final int position = conference_context_id;
        final Bookmark bookmark = (Bookmark) conferences.get(position);
        final var conversation = bookmark.getConversation();
        final boolean hasConversation = conversation != null;
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.delete_bookmark);
        if (hasConversation) {
            builder.setMessage(
                    JidDialog.style(
                            this,
                            R.string.remove_bookmark_and_close,
                            bookmark.getJid().toString()));
        } else {
            builder.setMessage(
                    JidDialog.style(this, R.string.remove_bookmark, bookmark.getJid().toString()));
        }
        builder.setPositiveButton(
                hasConversation ? R.string.delete_and_close : R.string.delete,
                (dialog, which) -> {
                    bookmark.setConversation(null);
                    final Account account = bookmark.getAccount();
                    xmppConnectionService.deleteBookmark(account, bookmark);
                    if (conversation != null) {
                        xmppConnectionService.archiveConversation(conversation);
                    }
                    filter(mSearchEditText.getText().toString());
                });
        builder.create().show();
    }

    private void showChannelActions() {
        final List<Integer> actions = new ArrayList<>();
        final List<CharSequence> labels = new ArrayList<>();
        if (!QuickConversationsService.isPlayStoreFlavor()) {
            actions.add(R.id.discover_public_channels);
            labels.add(getString(R.string.discover_channels));
        }
        actions.add(R.id.join_public_channel);
        labels.add(getString(R.string.join_public_channel));
        actions.add(R.id.create_public_channel);
        labels.add(getString(R.string.create_public_channel));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.start_conversation_channel_actions)
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (dialog, which) -> {
                            final int action = actions.get(which);
                            final String input =
                                    mSearchEditText == null
                                            ? ""
                                            : mSearchEditText.getText().toString().trim();
                            final String prefilled =
                                    isValidJid(input) ? Jid.of(input).toString() : null;
                            if (action == R.id.discover_public_channels) {
                                startActivity(new Intent(this, ChannelDiscoveryActivity.class));
                            } else if (action == R.id.join_public_channel) {
                                showJoinConferenceDialog(prefilled);
                            } else if (action == R.id.create_public_channel) {
                                showPublicChannelDialog();
                            }
                        })
                .show();
    }

    @SuppressLint("InflateParams")
    protected void showCreateContactDialog(final String prefilledJid, final Invite invite) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        EnterJidDialog dialog =
                EnterJidDialog.newInstance(
                        mActivatedAccounts,
                        getString(R.string.start_conversation),
                        getString(R.string.message),
                "Call",
                prefilledJid,
                invite == null ? null : invite.account,
                invite == null || !invite.hasFingerprints(),
                true,
                EnterJidDialog.SanityCheck.ALLOW_MUC
        );

        dialog.setOnEnterJidDialogPositiveListener(
                (accountJid, contactJid, call, save) -> {
                    if (!xmppConnectionServiceBound) {
                        return false;
                    }

            final Account account = xmppConnectionService.findAccountByJid(accountJid);
            if (account == null) {
                return true;
            }
            final Contact contact = account.getRoster().getContact(contactJid);

            if (invite != null && invite.getName() != null) {
                contact.setServerName(invite.getName());
            }

            if (contact.isSelf() || contact.showInRoster()) {
                        switchToConversationDoNotAppend(contact, invite == null ? null : invite.getBody(), call ? "call" : null);
                return true;
            }

            xmppConnectionService.checkIfMuc(account, contactJid, (isMuc) -> {
                if (isMuc) {
                    if (save) {
                        Bookmark bookmark = account.getBookmark(contactJid);
                        if (bookmark != null) {
                            openConversationsForBookmark(bookmark);
                        } else {
                            bookmark = new Bookmark(account, contactJid.asBareJid());
                            bookmark.setAutojoin(getBooleanPreference("autojoin", R.bool.autojoin));
                            final String nick = contactJid.getResource();
                            if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
                                bookmark.setNick(nick);
                            }
                            final Conversation conversation = xmppConnectionService
                                    .findOrCreateConversation(account, contactJid, null, true, true, true, null);
                            bookmark.setConversation(conversation);
                            xmppConnectionService.createBookmark(account, bookmark);
                            switchToConversationDoNotAppend(conversation, invite == null ? null : invite.getBody());
                        }
                    } else {
                        final Conversation conversation = xmppConnectionService.findOrCreateConversation(account, contactJid, null, true, true, true, null);
                        switchToConversationDoNotAppend(conversation, invite == null ? null : invite.getBody());
                    }
                } else {
                    if (save) {
                        final String preAuth = invite == null ? null : invite.getParameter(XmppUri.PARAMETER_PRE_AUTH);
                        xmppConnectionService.createContact(contact, true, preAuth);
                        if (invite != null && invite.hasFingerprints()) {
                            xmppConnectionService.verifyFingerprints(contact, invite.getFingerprints());
                        }
                    }
                    switchToConversationDoNotAppend(contact, invite == null ? null : invite.getBody(), call ? "call" : null);
                        }

                try {
                    dialog.dismiss();
                } catch (final IllegalStateException e) { }
            });

            return false;
        });
        dialog.show(ft, FRAGMENT_TAG_DIALOG);
    }

    @SuppressLint("InflateParams")
    protected void showJoinConferenceDialog(final String prefilledJid) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        JoinConferenceDialog joinConferenceFragment =
                JoinConferenceDialog.newInstance(prefilledJid, mActivatedAccounts);
        joinConferenceFragment.show(ft, FRAGMENT_TAG_DIALOG);
    }

    private void showCreatePrivateGroupChatDialog() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        String preferredAccount = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (preferredAccount == null && xmppConnectionService != null) {
            final Account preferred =
                    ProfileNavigation.preferredProfileAccount(this, xmppConnectionService);
            if (preferred != null) {
                preferredAccount = preferred.getJid().asBareJid().toString();
            }
        }
        if (!mActivatedAccounts.contains(preferredAccount)) {
            preferredAccount = null;
        }

        final CreatePrivateGroupChatDialog createConferenceFragment =
                CreatePrivateGroupChatDialog.newInstance(
                        mActivatedAccounts, preferredAccount);
        createConferenceFragment.show(ft, FRAGMENT_TAG_DIALOG);
    }

    private void showPublicChannelDialog() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        CreatePublicChannelDialog dialog =
                CreatePublicChannelDialog.newInstance(mActivatedAccounts);
        dialog.show(ft, FRAGMENT_TAG_DIALOG);
    }

    public static Account getSelectedAccount(
            final Context context, final Spinner spinner) {
        if (spinner == null || !spinner.isEnabled()) {
            return null;
        }
        if (context instanceof XmppActivity) {
            final Jid jid;
            try {
                jid = Jid.of(spinner.getSelectedItem().toString());
            } catch (final IllegalArgumentException e) {
                return null;
            }
            final XmppConnectionService service = ((XmppActivity) context).xmppConnectionService;
            if (service == null) {
                return null;
            }
            return service.findAccountByJid(jid);
        } else {
            return null;
        }
    }

    protected void switchToConversation(Contact contact) {
        Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), null, false, false, true, null);
        switchToConversation(conversation);
    }

    protected void switchToConversationDoNotAppend(Contact contact, String body) {
        Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), null, false, false, true, null);
        switchToConversationDoNotAppend(conversation, body);
    }

    @Override
    public void invalidateOptionsMenu() {
        super.invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.start_conversation, menu);
        final MenuItem qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code);
        qrCodeScanMenuItem.setVisible(isCameraFeatureAvailable());
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                navigateBack();
                return true;
            case R.id.action_scan_qr_code:
                UriHandlerActivity.scan(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH && !event.isLongPress()) {
            openSearch();
            return true;
        }
        int c = event.getUnicodeChar();
        if (c > 32) {
            if (mSearchEditText != null && !mSearchEditText.isFocused()) {
                openSearch();
                mSearchEditText.append(Character.toString((char) c));
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void openSearch() {
        if (mSearchEditText == null) {
            return;
        }
        mSearchEditText.requestFocus();
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == RESULT_OK) {
            if (xmppConnectionServiceBound) {
                this.mPostponedActivityResult = null;
                if (requestCode == REQUEST_CREATE_CONFERENCE) {
                    Account account = extractAccount(intent);
                    final String name =
                            intent.getStringExtra(ChooseContactActivity.EXTRA_GROUP_CHAT_NAME);
                    final List<Jid> jids = ChooseContactActivity.extractJabberIds(intent);
                    if (account != null && jids.size() > 0) {
                        if (xmppConnectionService.createAdhocConference(
                                account, name, jids, mAdhocConferenceCallback)) {
                            mToast =
                                    Toast.makeText(
                                            this, R.string.creating_conference, Toast.LENGTH_LONG);
                            mToast.show();
                        }
                    }
                }
            } else {
                this.mPostponedActivityResult = new Pair<>(requestCode, intent);
            }
        }
        super.onActivityResult(requestCode, resultCode, intent);
    }

    private boolean askForContactsPermissions() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (mRequestedContactsPermission.compareAndSet(false, true)) {
            final ImmutableList.Builder<String> permissionBuilder = new ImmutableList.Builder<>();
            permissionBuilder.add(Manifest.permission.READ_CONTACTS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionBuilder.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            final String[] permission = permissionBuilder.build().toArray(new String[0]);
            final String consent =
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                            .getString(PREF_KEY_CONTACT_INTEGRATION_CONSENT, null);
            final boolean requiresConsent =
                    (QuickConversationsService.isQuicksy()
                                    || QuickConversationsService.isPlayStoreFlavor())
                            && !"agreed".equals(consent);
            if (requiresConsent && "declined".equals(consent)) {
                Log.d(
                        Config.LOGTAG,
                        "not asking for contacts permission because consent has been declined");
                return false;
            }
            if (requiresConsent
                    || shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
                final AtomicBoolean requestPermission = new AtomicBoolean(false);
                if (QuickConversationsService.isQuicksy()) {
                    builder.setTitle(R.string.quicksy_wants_your_consent);
                    builder.setMessage(
                            Html.fromHtml(getString(R.string.sync_with_contacts_quicksy_static)));
                } else {
                    builder.setTitle(R.string.sync_with_contacts);
                    builder.setMessage(
                            getString(
                                    R.string.sync_with_contacts_long,
                                    getString(R.string.app_name)));
                }
                @StringRes int confirmButtonText;
                if (requiresConsent) {
                    confirmButtonText = R.string.agree_and_continue;
                } else {
                    confirmButtonText = R.string.next;
                }
                builder.setPositiveButton(
                        confirmButtonText,
                        (dialog, which) -> {
                            if (requiresConsent) {
                                PreferenceManager.getDefaultSharedPreferences(
                                                getApplicationContext())
                                        .edit()
                                        .putString(PREF_KEY_CONTACT_INTEGRATION_CONSENT, "agreed")
                                        .apply();
                            }
                            if (requestPermission.compareAndSet(false, true)) {
                                requestPermissions(permission, REQUEST_SYNC_CONTACTS);
                            }
                        });
                if (requiresConsent) {
                    builder.setNegativeButton(
                            R.string.decline,
                            (dialog, which) ->
                                    PreferenceManager.getDefaultSharedPreferences(
                                                    getApplicationContext())
                                            .edit()
                                            .putString(
                                                    PREF_KEY_CONTACT_INTEGRATION_CONSENT,
                                                    "declined")
                                            .apply());
                } else {
                    builder.setOnDismissListener(
                            dialog -> {
                                if (requestPermission.compareAndSet(false, true)) {
                                    requestPermissions(permission, REQUEST_SYNC_CONTACTS);
                                }
                            });
                }
                builder.setCancelable(requiresConsent);
                final AlertDialog dialog = builder.create();
                dialog.setCanceledOnTouchOutside(requiresConsent);
                dialog.setOnShowListener(
                        dialogInterface -> {
                            final TextView tv = dialog.findViewById(android.R.id.message);
                            if (tv != null) {
                                tv.setMovementMethod(LinkMovementMethod.getInstance());
                            }
                        });
                dialog.show();
            } else {
                requestPermissions(permission, REQUEST_SYNC_CONTACTS);
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
                if (requestCode == REQUEST_SYNC_CONTACTS && xmppConnectionServiceBound) {
                    if (QuickConversationsService.isQuicksy()) {
                        setRefreshing(true);
                    }
                    xmppConnectionService.loadPhoneContacts();
                    xmppConnectionService.startContactObserver();
                }
            }
    }

    private void configureHomeButton() {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        actionBar.setDisplayHomeAsUpEnabled(!createdByViewIntent);
    }

    @Override
    protected void onBackendConnected() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            xmppConnectionService.getQuickConversationsService().considerSyncBackground(false);
        }
        if (mPostponedActivityResult != null) {
            onActivityResult(
                    mPostponedActivityResult.first, RESULT_OK, mPostponedActivityResult.second);
            this.mPostponedActivityResult = null;
        }

        this.mActivatedAccounts.clear();
        this.mActivatedAccounts.addAll(AccountUtils.getEnabledAccounts(xmppConnectionService));
        configureHomeButton();
        Intent intent = pendingViewIntent.pop();
        if (intent != null && processViewIntent(intent)) {
            filter(null);
        } else {
            if (mSearchEditText != null) {
                filter(mSearchEditText.getText().toString());
            } else {
                filter(null);
            }
        }
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (fragment instanceof OnBackendConnected) {
            Log.d(Config.LOGTAG, "calling on backend connected on dialog");
            ((OnBackendConnected) fragment).onBackendConnected();
        }
        if (QuickConversationsService.isQuicksy()) {
            setRefreshing(xmppConnectionService.getQuickConversationsService().isSynchronizing());
        }
    }

    protected boolean processViewIntent(@NonNull Intent intent) {
        final String inviteUri = intent.getStringExtra(EXTRA_INVITE_URI);
        if (inviteUri != null) {
            final Invite invite = new Invite(inviteUri);
            invite.account = intent.getStringExtra(EXTRA_ACCOUNT);
            if (invite.isValidJid()) {
                return invite.invite();
            }
        }
        final String action = intent.getAction();
        if (action == null) {
            return false;
        }
        switch (action) {
            case Intent.ACTION_SENDTO:
            case Intent.ACTION_VIEW:
                Uri uri = intent.getData();
                if (uri != null) {
                    Invite invite =
                            new Invite(intent.getData(), intent.getBooleanExtra("scanned", false));
                    invite.account = intent.getStringExtra(EXTRA_ACCOUNT);
                    invite.forceDialog = intent.getBooleanExtra("force_dialog", false);
                    return invite.invite();
                } else {
                    return false;
                }
        }
        return false;
    }

    private boolean handleJid(Invite invite) {
        List<Contact> contacts =
                xmppConnectionService.findContacts(invite.getJid(), invite.account);
        if (invite.isAction(XmppUri.ACTION_JOIN)) {
            Conversation muc = xmppConnectionService.findFirstMuc(invite.getJid());
            if (muc != null && !invite.forceDialog) {
                switchToConversationDoNotAppend(muc, invite.getBody());
                return true;
            } else {
                showJoinConferenceDialog(invite.getJid().asBareJid().toString());
                return false;
            }
        } else if (contacts.isEmpty()) {
            showCreateContactDialog(invite.getJid().toString(), invite);
            return false;
        } else if (contacts.size() == 1) {
            Contact contact = contacts.get(0);
            if (!invite.isSafeSource() && invite.hasFingerprints()) {
                displayVerificationWarningDialog(contact, invite);
            } else {
                if (invite.hasFingerprints()) {
                    if (xmppConnectionService.verifyFingerprints(
                            contact, invite.getFingerprints())) {
                        Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT)
                                .show();
                    }
                }
                if (invite.account != null) {
                    xmppConnectionService.getShortcutService().report(contact);
                }
                switchToConversationDoNotAppend(contact, invite.getBody());
            }
            return true;
        } else {
            if (mSearchEditText != null) {
                mSearchEditText.setText("");
                mSearchEditText.append(invite.getJid().toString());
                openSearch();
                filter(invite.getJid().toString());
            } else {
                mInitialSearchValue.push(invite.getJid().toString());
            }
            return true;
        }
    }

    private void displayVerificationWarningDialog(final Contact contact, final Invite invite) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.verify_omemo_keys);
        View view = getLayoutInflater().inflate(R.layout.dialog_verify_fingerprints, null);
        final CheckBox isTrustedSource = view.findViewById(R.id.trusted_source);
        TextView warning = view.findViewById(R.id.warning);
        warning.setText(
                JidDialog.style(
                        this,
                        R.string.verifying_omemo_keys_trusted_source,
                        contact.getJid().asBareJid().toString(),
                        contact.getDisplayName()));
        builder.setView(view);
        builder.setPositiveButton(
                R.string.confirm,
                (dialog, which) -> {
                    if (isTrustedSource.isChecked() && invite.hasFingerprints()) {
                        xmppConnectionService.verifyFingerprints(contact, invite.getFingerprints());
                    }
                    switchToConversationDoNotAppend(contact, invite.getBody());
                });
        builder.setNegativeButton(
                R.string.cancel, (dialog, which) -> StartConversationActivity.this.finish());
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(dialog1 -> StartConversationActivity.this.finish());
        dialog.show();
    }

    protected void filter(String needle) {
        if (xmppConnectionServiceBound) {
            this.filterContacts(needle);
            this.filterConferences(needle);
            if (mUnifiedAdapter != null) {
                mUnifiedAdapter.rebuild();
            }
        }
    }

    protected void filterContacts(String needle) {
        this.contacts.clear();
        ArrayList<ListItem.Tag> tags = new ArrayList<>();
        final List<Account> accounts = xmppConnectionService.getAccounts();
        for (final Account account : accounts) {
            if (account.isEnabled()) {
                for (Contact contact : account.getRoster().getContacts()) {
                    Presence.Status s = contact.getShownStatus();
                    if (contact.showInContactList()
                            && contact.match(this, needle)
                            && (!this.mHideOfflineContacts
                                    || s.compareTo(Presence.Status.OFFLINE) < 0)) {
                        this.contacts.add(contact);
                        tags.addAll(contact.getTags(this));
                    }
                }

                final Contact self = new Contact(account.getSelfContact());
                self.setSystemName(getString(R.string.saved_messages));
                if (self.match(this, needle)) {
                    this.contacts.add(self);
                }

                for (Bookmark bookmark : account.getBookmarks()) {
                    if (bookmark.match(this, needle)) {
                        tags.addAll(bookmark.getTags(this));
                    }
                }
            }
        }

        Comparator<Map.Entry<ListItem.Tag,Integer>> sortTagsBy = Map.Entry.comparingByValue(Comparator.reverseOrder());
        sortTagsBy = sortTagsBy.thenComparing(entry -> entry.getKey().getName());

        mTagsAdapter.setTags(
                tags.stream()
                        .collect(Collectors.toMap((x) -> x, (t) -> 1, (c1, c2) -> c1 + c2))
                        .entrySet().stream()
                        .sorted(sortTagsBy)
                        .map(e -> e.getKey()).collect(Collectors.toList())
        );
        Collections.sort(this.contacts);

    }

    protected void filterConferences(String needle) {
        this.conferences.clear();
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.isEnabled()) {
                for (final Bookmark bookmark : account.getBookmarks()) {
                    if (bookmark.match(this, needle)) {
                        this.conferences.add(bookmark);
                    }
                }
            }
        }
        Collections.sort(this.conferences);
        if (mUnifiedAdapter != null) mUnifiedAdapter.rebuild();
    }

    @Override
    public void OnUpdateBlocklist(final Status status) {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        if (mSearchEditText != null) {
            filter(mSearchEditText.getText().toString());
        }
        configureHomeButton();
        if (QuickConversationsService.isQuicksy()) {
            setRefreshing(xmppConnectionService.getQuickConversationsService().isSynchronizing());
        }
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    private void navigateBack() {
        if (!createdByViewIntent
                && xmppConnectionService != null
                && !xmppConnectionService.isConversationsListEmpty(null)) {
            Intent intent = new Intent(this, ConversationsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        }
        finish();
    }

    @Override
    public void onCreateDialogPositiveClick(Spinner spinner, String name) {
        if (!xmppConnectionServiceBound) {
            return;
        }
        final Account account = getSelectedAccount(this, spinner);
        if (account == null) {
            return;
        }
        final Intent intent =
                ChooseContactActivity.createForNewGroup(this, account, name.trim());
        startActivityForResult(intent, REQUEST_CREATE_CONFERENCE);
    }

    @Override
    public void onJoinDialogPositiveClick(
            final Dialog dialog,
            final Spinner spinner,
            final TextInputLayout layout,
            final AutoCompleteTextView jid) {
        if (!xmppConnectionServiceBound) {
            return;
        }
        final Account account = getSelectedAccount(this, spinner);
        if (account == null) {
            return;
        }
        final String input = jid.getText().toString().trim();
        Jid conferenceJid;
        try {
            conferenceJid = Jid.ofUserInput(input);
        } catch (final IllegalArgumentException e) {
            final XmppUri xmppUri = new XmppUri(input);
            if (xmppUri.isValidJid() && xmppUri.isAction(XmppUri.ACTION_JOIN)) {
                final Editable editable = jid.getEditableText();
                editable.clear();
                editable.append(xmppUri.getJid().toString());
                conferenceJid = xmppUri.getJid();
            } else {
                layout.setError(getString(R.string.invalid_jid));
                return;
            }
        }
        final var existingBookmark = account.getBookmark(conferenceJid);
        if (existingBookmark != null) {
            openConversationsForBookmark(existingBookmark);
        } else {
            final var bookmark = new Bookmark(account, conferenceJid.asBareJid());
            bookmark.setAutojoin(true);
            final String nick = conferenceJid.getResource();
            if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
                bookmark.setNick(nick);
            }
            final Conversation conversation = xmppConnectionService
                    .findOrCreateConversation(account, conferenceJid, null, true, true, true, null);
            bookmark.setConversation(conversation);
            xmppConnectionService.createBookmark(account, bookmark);
            switchToConversation(conversation);
        }
        dialog.dismiss();
    }

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    @Override
    public void onRefresh() {
        Log.d(Config.LOGTAG, "user requested to refresh");
        if (QuickConversationsService.isQuicksy() && xmppConnectionService != null) {
            xmppConnectionService.getQuickConversationsService().considerSyncBackground(true);
        }
    }

    protected void startOtrChat() {
        int position = contact_context_id;
        Contact contact = (Contact) contacts.get(position);

        Conversation conversation = xmppConnectionService.findOrCreateConversation(contact.getAccount(), contact.getJid(), null, false, false, false, null);

        selectPresence(conversation,
                () -> {
                    Conversation c = xmppConnectionService.findOrCreateConversation(contact.getAccount(), contact.getJid(), null, false, false, false, conversation.getNextCounterpart());
                    conversation.setNextCounterpart(null);
                    if (c != null) {
                        switchToConversation(c);
                    }
                });
    }

    private void setRefreshing(boolean refreshing) {
        // The unified Material list has no pull-to-refresh chrome. Background contact sync
        // continues normally; data updates arrive through the existing roster callbacks.
    }

    @Override
    public void onCreatePublicChannel(Account account, String name, Jid address) {
        mToast = Toast.makeText(this, R.string.creating_channel, Toast.LENGTH_LONG);
        mToast.show();
        xmppConnectionService.createPublicChannel(
                account,
                name,
                address,
                new UiCallback<Conversation>() {
                    @Override
                    public void success(Conversation conversation) {
                        runOnUiThread(
                                () -> {
                                    hideToast();
                                    switchToConversation(conversation);
                                });
                    }

                    @Override
                    public void error(int errorCode, Conversation conversation) {
                        runOnUiThread(
                                () -> {
                                    replaceToast(getString(errorCode));
                                    switchToConversation(conversation);
                                });
                    }

                    @Override
                    public void userInputRequired(PendingIntent pi, Conversation object) {}
                });
    }

    public static class MyListFragment extends SwipeRefreshListFragment {
        private AdapterView.OnItemClickListener mOnItemClickListener;
        private int mResContextMenuConference;
        private int mResContextMenuContact;

        private boolean itemsFromContacts;

        public void setContextMenu(final int resConference, final int resContact) {
            this.mResContextMenuConference = resConference;
            this.mResContextMenuContact = resContact;
        }

        public void setItemsFromContacts(boolean itemsFromContacts) {
            this.itemsFromContacts = itemsFromContacts;
        }

        @Override
        public void onListItemClick(
                final ListView l, final View v, final int position, final long id) {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(l, v, position, id);
            }
        }

        public void setOnListItemClickListener(AdapterView.OnItemClickListener l) {
            this.mOnItemClickListener = l;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);

            if (getActivity() instanceof StartConversationActivity && ((StartConversationActivity) getActivity()).groupingEnabled) {
                FixedExpandableListView lv = new FixedExpandableListView(view.getContext());
                lv.setId(android.R.id.list);
                lv.setDrawSelectorOnTop(false);
                lv.setGroupIndicator(null);

                ListView oldList = view.findViewById(android.R.id.list);
                ViewGroup oldListParent = (ViewGroup) oldList.getParent();
                oldListParent.removeView(oldList);
                oldListParent.addView(lv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            return view;
        }

        @Override
        public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            registerForContextMenu(getListView());
            getListView().setFastScrollEnabled(true);
            getListView().setDivider(null);
            getListView().setDividerHeight(0);
        }

        @Override
        public void onCreateContextMenu(
                @NonNull final ContextMenu menu,
                @NonNull final View v,
                final ContextMenuInfo menuInfo) {
            super.onCreateContextMenu(menu, v, menuInfo);
            final StartConversationActivity activity = (StartConversationActivity) getActivity();
            if (activity == null) {
                return;
            }

            int position;

            if (menuInfo instanceof AdapterContextMenuInfo) {
                final AdapterView.AdapterContextMenuInfo acmi = (AdapterContextMenuInfo) menuInfo;
                position = acmi.position;
            } else {
                final ExpandableListView.ExpandableListContextMenuInfo acmi = (ExpandableListView.ExpandableListContextMenuInfo) menuInfo;
                position = (int) acmi.targetView.getTag(R.id.TAG_POSITION);
            }

            int resContextMenu;

            ListItem item;
            if (itemsFromContacts) {
                item = activity.contacts.get(position);
                if (item instanceof Bookmark) {
                    resContextMenu = mResContextMenuConference;
                } else {
                    resContextMenu = mResContextMenuContact;
                }
            } else {
                item = activity.conferences.get(position);
                resContextMenu = mResContextMenuConference;
            }

            activity.getMenuInflater().inflate(resContextMenu, menu);


            if (resContextMenu == R.menu.conference_context) {
                activity.conference_context_id = position;
                final Bookmark bookmark = (Bookmark) item;
                final Conversation conversation = bookmark.getConversation();
                final MenuItem share = menu.findItem(R.id.context_share_uri);
                final MenuItem delete = menu.findItem(R.id.context_delete_conference);
                if (conversation != null) {
                    delete.setTitle(R.string.delete_and_close);
                } else {
                    delete.setTitle(R.string.delete_bookmark);
                }
                share.setVisible(conversation == null || !conversation.isPrivateAndNonAnonymous());
            } else if (resContextMenu == R.menu.contact_context) {
                activity.contact_context_id = position;
                final Contact contact = (Contact) item;
                final MenuItem blockUnblockItem = menu.findItem(R.id.context_contact_block_unblock);
                final MenuItem showContactDetailsItem = menu.findItem(R.id.context_contact_details);
                final MenuItem deleteContactMenuItem = menu.findItem(R.id.context_delete_contact);
                final MenuItem startSecrectChat = menu.findItem(R.id.context_contact_start_secrect_chat);
                if (contact.isSelf()) {
                    showContactDetailsItem.setVisible(false);
                    startSecrectChat.setVisible(false);
                }

                deleteContactMenuItem.setVisible(
                        contact.showInRoster()
                                && !contact.getOption(Contact.Options.SYNCED_VIA_OTHER));
                final XmppConnection xmpp = contact.getAccount().getXmppConnection();
                if (xmpp != null && xmpp.getFeatures().blocking() && !contact.isSelf()) {
                    if (contact.isBlocked()) {
                        blockUnblockItem.setTitle(R.string.unblock_contact);
                    } else {
                        blockUnblockItem.setTitle(R.string.block_contact);
                    }
                } else {
                    blockUnblockItem.setVisible(false);
                }
            }
        }

        @Override
        public boolean onContextItemSelected(final MenuItem item) {
            StartConversationActivity activity = (StartConversationActivity) getActivity();
            if (activity == null) {
                return true;
            }
            switch (item.getItemId()) {
                case R.id.context_contact_details:
                    activity.openDetailsForContact();
                    break;
                case R.id.context_show_qr:
                    activity.showQrForContact();
                    break;
                case R.id.context_contact_block_unblock:
                    activity.toggleContactBlock();
                    break;
                case R.id.context_delete_contact:
                    activity.deleteContact();
                    break;
                case R.id.context_share_uri:
                    activity.shareBookmarkUri();
                    break;
                case R.id.context_delete_conference:
                    activity.deleteConference();
                case R.id.context_contact_start_secrect_chat:
                    activity.startOtrChat();
            }
            return true;
        }
    }

    public class ListPagerAdapter extends PagerAdapter {
        private final FragmentManager fragmentManager;
        private final MyListFragment[] fragments;

        ListPagerAdapter(FragmentManager fm) {
            fragmentManager = fm;
            fragments = new MyListFragment[2];
        }

        public void requestFocus(int pos) {
            if (fragments.length > pos) {
                fragments[pos].getListView().requestFocus();
            }
        }

        @Override
        public void destroyItem(
                @NonNull ViewGroup container, int position, @NonNull Object object) {
            FragmentTransaction trans = fragmentManager.beginTransaction();
            trans.remove(fragments[position]);
            trans.commit();
            fragments[position] = null;
        }

        @NonNull
        @Override
        public Fragment instantiateItem(@NonNull ViewGroup container, int position) {
            final Fragment fragment = getItem(position);
            final FragmentTransaction trans = fragmentManager.beginTransaction();
            trans.add(container.getId(), fragment, "fragment:" + position);
            try {
                trans.commit();
            } catch (IllegalStateException e) {
                // ignore
            }
            return fragment;
        }

        @Override
        public int getCount() {
            return fragments.length;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object fragment) {
            return ((Fragment) fragment).getView() == view;
        }

        @Nullable
        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return getResources().getString(R.string.contacts);
                case 1:
                    return getResources().getString(R.string.group_chats);
                default:
                    return super.getPageTitle(position);
            }
        }

        Fragment getItem(int position) {
            if (fragments[position] == null) {
                final MyListFragment listFragment = new MyListFragment();
                if (position == 1) {
                    listFragment.setListAdapter(mConferenceAdapter);
                    listFragment.setContextMenu(R.menu.conference_context, R.menu.contact_context);
                    listFragment.setItemsFromContacts(false);
                    listFragment.setOnListItemClickListener(
                            (arg0, arg1, p, arg3) -> openConversationForBookmark(p));
                } else {
                    listFragment.setListAdapter(mContactsAdapter);
                    listFragment.setContextMenu(R.menu.conference_context, R.menu.contact_context);
                    listFragment.setItemsFromContacts(true);
                    listFragment.setOnListItemClickListener(
                            (arg0, arg1, p, arg3) -> openConversationForContact(p));
                    if (QuickConversationsService.isQuicksy()) {
                        listFragment.setOnRefreshListener(StartConversationActivity.this);
                    }
                }
                fragments[position] = listFragment;
            }
            return fragments[position];
        }
    }

    public static void addInviteUri(Intent to, Intent from) {
        if (from != null && from.hasExtra(EXTRA_INVITE_URI)) {
            final String invite = from.getStringExtra(EXTRA_INVITE_URI);
            to.putExtra(EXTRA_INVITE_URI, invite);
        }
    }

    private class Invite extends XmppUri {

        public String account;

        boolean forceDialog = false;

        Invite(final String uri) {
            // String extras are never security provenance. Internal forwarding must not silently
            // turn an embedded OMEMO fingerprint into durable verification.
            super(Uri.parse(uri), false);
        }

        Invite(Uri uri, boolean safeSource) {
            super(uri, safeSource);
        }

        boolean invite() {
            if (!isValidJid()) {
                Toast.makeText(
                                StartConversationActivity.this,
                                R.string.invalid_jid,
                                Toast.LENGTH_SHORT)
                        .show();
                return false;
            }
            if (getJid() != null) {
                return handleJid(this);
            }
            return false;
        }
    }


    private enum EmptyStateAction {
        NONE,
        START_JID,
        ADD_CONTACT,
        SHOW_ALL
    }

    private EmptyStateAction emptyPrimaryAction = EmptyStateAction.NONE;
    private EmptyStateAction emptySecondaryAction = EmptyStateAction.NONE;

    private void updateEmptyState() {
        if (binding == null) {
            return;
        }

        final boolean empty = contacts.isEmpty() && conferences.isEmpty();
        binding.results.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) {
            binding.emptyActions.setVisibility(View.GONE);
            emptyPrimaryAction = EmptyStateAction.NONE;
            emptySecondaryAction = EmptyStateAction.NONE;
            return;
        }

        binding.emptyActions.setVisibility(View.GONE);
        binding.emptyPrimaryAction.setVisibility(View.GONE);
        binding.emptySecondaryAction.setVisibility(View.GONE);
        binding.emptyStateIcon.setVisibility(View.VISIBLE);
        emptyPrimaryAction = EmptyStateAction.NONE;
        emptySecondaryAction = EmptyStateAction.NONE;

        final String query =
                mSearchEditText == null ? "" : mSearchEditText.getText().toString().trim();

        if (!query.isEmpty() && isValidJid(query)) {
            binding.emptyStateIcon.setVisibility(View.GONE);
            binding.emptyActions.setVisibility(View.VISIBLE);
            binding.emptyStateTitle.setText(R.string.start_conversation_jid_title);
            binding.emptyStateText.setText(
                    query + "\n" + getString(R.string.start_conversation_jid_text));
            binding.emptyPrimaryAction.setText(R.string.start_conversation_start_jid);
            binding.emptyPrimaryAction.setVisibility(View.VISIBLE);
            binding.emptySecondaryAction.setText(R.string.start_conversation_add_jid);
            binding.emptySecondaryAction.setVisibility(View.VISIBLE);
            emptyPrimaryAction = EmptyStateAction.START_JID;
            emptySecondaryAction = EmptyStateAction.ADD_CONTACT;
            return;
        }

        if (!query.isEmpty()) {
            binding.emptyStateTitle.setText(R.string.start_conversation_no_results_title);
            binding.emptyStateText.setText(R.string.start_conversation_no_results_text);
            return;
        }

        if (mHideOfflineContacts) {
            binding.emptyActions.setVisibility(View.VISIBLE);
            binding.emptyStateTitle.setText(R.string.start_conversation_online_empty_title);
            binding.emptyStateText.setText(R.string.start_conversation_online_empty_text);
            binding.emptyPrimaryAction.setText(R.string.start_conversation_show_all);
            binding.emptyPrimaryAction.setVisibility(View.VISIBLE);
            emptyPrimaryAction = EmptyStateAction.SHOW_ALL;
            return;
        }

        binding.emptyActions.setVisibility(View.VISIBLE);
        binding.emptyStateTitle.setText(R.string.start_conversation_empty_title);
        binding.emptyStateText.setText(R.string.start_conversation_empty_text);
        binding.emptyPrimaryAction.setText(R.string.start_conversation_contact_action);
        binding.emptyPrimaryAction.setVisibility(View.VISIBLE);
        emptyPrimaryAction = EmptyStateAction.ADD_CONTACT;
    }

    private void handleEmptyPrimaryAction() {
        if (emptyPrimaryAction == EmptyStateAction.SHOW_ALL) {
            binding.onlineOnlyFilter.setChecked(false);
            return;
        }

        final String query =
                mSearchEditText == null ? "" : mSearchEditText.getText().toString().trim();
        final String prefilled = isValidJid(query) ? Jid.of(query).toString() : null;
        if (emptyPrimaryAction == EmptyStateAction.START_JID
                || emptyPrimaryAction == EmptyStateAction.ADD_CONTACT) {
            showCreateContactDialog(prefilled, null);
        }
    }

    private void handleEmptySecondaryAction() {
        if (emptySecondaryAction != EmptyStateAction.ADD_CONTACT) {
            return;
        }
        final String query =
                mSearchEditText == null ? "" : mSearchEditText.getText().toString().trim();
        showCreateContactDialog(isValidJid(query) ? Jid.of(query).toString() : null, null);
    }

    private void showUnifiedItemMenu(final View anchor, final ListItem item) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        if (item instanceof Bookmark) {
            final Bookmark bookmark = (Bookmark) item;
            conference_context_id = conferences.indexOf(bookmark);
            popup.inflate(R.menu.conference_context);
            final Conversation conversation = bookmark.getConversation();
            final MenuItem share = popup.getMenu().findItem(R.id.context_share_uri);
            final MenuItem delete = popup.getMenu().findItem(R.id.context_delete_conference);
            if (delete != null) {
                delete.setTitle(
                        conversation == null
                                ? R.string.delete_bookmark
                                : R.string.delete_and_close);
            }
            if (share != null) {
                share.setVisible(
                        conversation == null || !conversation.isPrivateAndNonAnonymous());
            }
        } else if (item instanceof Contact) {
            final Contact contact = (Contact) item;
            contact_context_id = contacts.indexOf(contact);
            popup.inflate(R.menu.contact_context);

            final MenuItem blockUnblockItem =
                    popup.getMenu().findItem(R.id.context_contact_block_unblock);
            final MenuItem showContactDetailsItem =
                    popup.getMenu().findItem(R.id.context_contact_details);
            final MenuItem deleteContactMenuItem =
                    popup.getMenu().findItem(R.id.context_delete_contact);
            final MenuItem startSecretChat =
                    popup.getMenu().findItem(R.id.context_contact_start_secrect_chat);

            if (contact.isSelf()) {
                if (showContactDetailsItem != null) {
                    showContactDetailsItem.setVisible(false);
                }
                if (startSecretChat != null) {
                    startSecretChat.setVisible(false);
                }
            }
            if (deleteContactMenuItem != null) {
                deleteContactMenuItem.setVisible(
                        contact.showInRoster()
                                && !contact.getOption(Contact.Options.SYNCED_VIA_OTHER));
            }
            final XmppConnection xmpp = contact.getAccount().getXmppConnection();
            if (blockUnblockItem != null) {
                if (xmpp != null && xmpp.getFeatures().blocking() && !contact.isSelf()) {
                    blockUnblockItem.setTitle(
                            contact.isBlocked()
                                    ? R.string.unblock_contact
                                    : R.string.block_contact);
                } else {
                    blockUnblockItem.setVisible(false);
                }
            }
        } else {
            return;
        }

        popup.setOnMenuItemClickListener(
                menuItem -> {
                    switch (menuItem.getItemId()) {
                        case R.id.context_contact_details:
                            openDetailsForContact();
                            return true;
                        case R.id.context_show_qr:
                            showQrForContact();
                            return true;
                        case R.id.context_contact_block_unblock:
                            toggleContactBlock();
                            return true;
                        case R.id.context_delete_contact:
                            deleteContact();
                            return true;
                        case R.id.context_share_uri:
                            shareBookmarkUri();
                            return true;
                        case R.id.context_delete_conference:
                            deleteConference();
                            return true;
                        case R.id.context_contact_start_secrect_chat:
                            startOtrChat();
                            return true;
                        default:
                            return false;
                    }
                });
        popup.show();
    }

    private final class StartConversationListAdapter
            extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_SECTION = 0;
        private static final int TYPE_ITEM = 1;

        private final List<Object> rows = new ArrayList<>();

        StartConversationListAdapter() {
            rebuild();
        }

        void rebuild() {
            rows.clear();
            if (!contacts.isEmpty()) {
                rows.add(getString(R.string.contacts));
                rows.addAll(contacts);
            }
            if (!conferences.isEmpty()) {
                rows.add(getString(R.string.group_chats));
                rows.addAll(conferences);
            }
            notifyDataSetChanged();
            updateEmptyState();
        }

        @Override
        public int getItemViewType(final int position) {
            return rows.get(position) instanceof ListItem ? TYPE_ITEM : TYPE_SECTION;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull final ViewGroup parent, final int viewType) {
            if (viewType == TYPE_SECTION) {
                final View view =
                        LayoutInflater.from(parent.getContext())
                                .inflate(R.layout.item_start_conversation_section, parent, false);
                return new RecyclerView.ViewHolder(view) {};
            }
            final View view =
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_start_conversation, parent, false);
            return new UnifiedItemViewHolder(view);
        }

        @Override
        public void onBindViewHolder(
                @NonNull final RecyclerView.ViewHolder holder, final int position) {
            final Object row = rows.get(position);
            if (!(row instanceof ListItem)) {
                ((TextView) holder.itemView).setText((CharSequence) row);
                return;
            }

            final ListItem item = (ListItem) row;
            final UnifiedItemViewHolder itemHolder = (UnifiedItemViewHolder) holder;

            itemHolder.name.setText(item.getDisplayName());
            if (item.getJid() != null) {
                itemHolder.jid.setVisibility(View.VISIBLE);
                itemHolder.jid.setText(
                        IrregularUnicodeDetector.style(
                                StartConversationActivity.this, item.getJid()));
            } else {
                itemHolder.jid.setVisibility(View.GONE);
            }

            AvatarWorkerTask.loadAvatar(item, itemHolder.avatar, R.dimen.avatar);

            Account account = null;
            if (item instanceof Contact) {
                final Contact contact = (Contact) item;
                itemHolder.presence.setStatus(contact);
                account = contact.getAccount();
            } else if (item instanceof Bookmark) {
                itemHolder.presence.setStatus(null);
                account = ((Bookmark) item).getAccount();
            }

            if (account != null
                    && xmppConnectionService != null
                    && xmppConnectionService.getAccounts().size() > 1) {
                itemHolder.accountIndicator.setCircleColor(
                        UIHelper.getAccountColor(
                                StartConversationActivity.this, account.getJid()));
            } else {
                itemHolder.accountIndicator.setCircleColor(Color.TRANSPARENT);
            }

            itemHolder.itemView.setOnClickListener(v -> openConversation(item));
            itemHolder.itemView.setOnLongClickListener(
                    v -> {
                        showUnifiedItemMenu(v, item);
                        return true;
                    });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class UnifiedItemViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView jid;
        final eu.siacs.conversations.ui.widget.AvatarView avatar;
        final eu.siacs.conversations.ui.widget.PresenceIndicator presence;
        final AccountIndicator accountIndicator;

        UnifiedItemViewHolder(final View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.contact_display_name);
            jid = itemView.findViewById(R.id.contact_jid);
            avatar = itemView.findViewById(R.id.contact_photo);
            presence = itemView.findViewById(R.id.presence_indicator);
            accountIndicator = itemView.findViewById(R.id.account_indicator);
        }
    }

    class TagsAdapter extends RecyclerView.Adapter<TagsAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            protected TextView tv;

            public ViewHolder(View v) {
                super(v);
                tv = (TextView) v;
                tv.setOnClickListener(view -> {
                    String needle = mSearchEditText.getText().toString();
                    String tag = tv.getText().toString();
                    String[] parts = needle.split("[,\\s]+");
                    if(needle.isEmpty()) {
                        needle = tag;
                    } else if (tag.toLowerCase(Locale.US).contains(parts[parts.length-1])) {
                        needle = needle.replace(parts[parts.length-1], tag);
                    } else {
                        needle += ", " + tag;
                    }
                    mSearchEditText.setText("");
                    mSearchEditText.append(needle);
                    filter(needle);
                });
            }

            public void setTag(ListItem.Tag tag) {
                tv.setText(tag.getName());
                tv.setBackgroundColor(tag.getColor());
            }
        }

        protected List<ListItem.Tag> tags = new ArrayList<>();

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
            View view = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.list_item_tag, null);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder viewHolder, int i) {
            viewHolder.setTag(tags.get(i));
        }

        @Override
        public int getItemCount() {
            return tags.size();
        }

        public void setTags(final List<ListItem.Tag> tags) {
            ListItem.Tag channelTag = new ListItem.Tag("Channel", UIHelper.getColorForName("Channel", true));
            String needle = mSearchEditText == null ? "" : mSearchEditText.getText().toString().toLowerCase(Locale.US).trim();
            HashSet<String> parts = new HashSet<>(Arrays.asList(needle.split("[,\\s]+")));
            this.tags = tags.stream().filter(
                    tag -> !tag.equals(channelTag) && !parts.contains(tag.getName().toLowerCase(Locale.US))
            ).collect(Collectors.toList());
            if (!parts.contains("channel") && tags.contains(channelTag)) this.tags.add(0, channelTag);
            notifyDataSetChanged();
        }
    }

    static class FixedExpandableListView extends ExpandableListView {
        public FixedExpandableListView(Context context) {
            super(context);
        }

        public FixedExpandableListView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public FixedExpandableListView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        public FixedExpandableListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        @Override
        public void setAdapter(ListAdapter adapter) {
            if (adapter instanceof ExpandableListAdapter) {
                setAdapter((ExpandableListAdapter) adapter);
            } else {
                super.setAdapter(adapter);
            }
        }

        @Override
        public void setOnItemClickListener(OnItemClickListener l) {
            setOnChildClickListener((parent, v, groupPosition, childPosition, id) -> {
                ExpandableListAdapter expandableAdapter = getExpandableListAdapter();

                if (!(expandableAdapter instanceof ExpandableListItemAdapter)) return false;

                ExpandableListItemAdapter adapter = (ExpandableListItemAdapter) expandableAdapter;

                Object child = expandableAdapter.getChild(groupPosition, childPosition);
                for(int i=0;i<adapter.getCount();i++) {
                    if (child == adapter.getItem(i)) {
                        l.onItemClick(parent, v, i, id);
                        return true;
                    }
                }

                return false;
            });
        }
    }

    class ExpandableListItemAdapter extends ListItemAdapter implements ExpandableListAdapter {

        private String generalTagName = activity.getString(R.string.contact_tag_general);
        private ListItem.Tag generalTag = new ListItem.Tag(generalTagName, UIHelper.getColorForName(generalTagName, true));

        private List<Object> tagsAndAccounts = new ArrayList<>();
        private Set<Account> expandedAccounts = new HashSet<>();

        private Map<Account, Map<ListItem.Tag, List<ListItem>>> groupedItems = new HashMap<>();

        public ExpandableListItemAdapter(XmppActivity activity, List<ListItem> objects) {
            super(activity, objects);

            registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    if (activity instanceof StartConversationActivity && ((StartConversationActivity) activity).groupingEnabled) {
                        tagsAndAccounts.clear();

                        List<Account> accounts = xmppConnectionService.getAccounts();

                        List<ListItem.Tag> tags = new ArrayList<>();
                        for (ListItem.Tag tag : mTagsAdapter.tags) {
                            if (!UIHelper.isStatusTag(activity, tag)) {
                                tags.add(tag);
                            }
                        }

                        groupedItems.clear();

                        for (Account account : accounts) {
                            if (accounts.size() > 1) {
                                tagsAndAccounts.add(account);
                            }

                            if (expandedAccounts.contains(account) || accounts.size() == 1) {
                                boolean generalTagAdded = false;
                                int initialPosition = tagsAndAccounts.size();

                                tagsAndAccounts.addAll(tags);

                                Map<ListItem.Tag, List<ListItem>> groupedItems = new HashMap<>();

                                for (int i = 0; i < ExpandableListItemAdapter.super.getCount(); i++) {
                                    ListItem item = getItem(i);
                                    List<ListItem.Tag> itemTags = item.getTags(activity);

                                    if (item instanceof Contact && !((Contact) item).getAccount().getJid().equals(account.getJid())) {
                                        continue;
                                    } else if (item instanceof Bookmark && !((Bookmark) item).getAccount().getJid().equals(account.getJid())) {
                                        continue;
                                    }

                                    if (itemTags.size() == 0 || (itemTags.size() == 1 && UIHelper.isStatusTag(activity, itemTags.get(0)))) {
                                        if (!generalTagAdded) {
                                            tagsAndAccounts.add(initialPosition, generalTag);
                                            generalTagAdded = true;
                                        }

                                        List<ListItem> group = groupedItems.computeIfAbsent(generalTag, tag -> new ArrayList<>());
                                        group.add(item);
                                    } else {
                                        for (ListItem.Tag itemTag : itemTags) {
                                            if (UIHelper.isStatusTag(activity, itemTag)) {
                                                continue;
                                            }

                                            List<ListItem> group = groupedItems.computeIfAbsent(itemTag, tag -> new ArrayList<>());
                                            group.add(item);
                                        }
                                    }
                                }

                                for (int i = tagsAndAccounts.size() - 1; i >= initialPosition; i--) {
                                    ListItem.Tag tag = (ListItem.Tag) tagsAndAccounts.get(i);
                                    if (groupedItems.get(tag) == null) {
                                        tagsAndAccounts.remove(tagsAndAccounts.lastIndexOf(tag));
                                    }
                                }

                                ExpandableListItemAdapter.this.groupedItems.put(account, groupedItems);
                            }
                        }
                    }
                }
            });
        }

        @Override
        public int getGroupCount() {
            return tagsAndAccounts.size();
        }

        @Override
        public int getChildrenCount(int groupPosition) {
            return getChildsList(groupPosition).size();
        }

        @Override
        public Object getGroup(int groupPosition) {
            return tagsAndAccounts.get(groupPosition);
        }

        @Override
        public Object getChild(int groupPosition, int childPosition) {
            return getChildsList(groupPosition).get(childPosition);
        }

        @Override
        public long getGroupId(int groupPosition) {
            return tagsAndAccounts.get(groupPosition).hashCode();
        }

        @Override
        public long getChildId(int groupPosition, int childPosition) {
            return getChildsList(groupPosition).get(childPosition).getJid().hashCode();
        }

        @Override
        public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
            Object obj = tagsAndAccounts.get(groupPosition);

            View v;
            if (obj instanceof ListItem.Tag) {
                ListItem.Tag tag = (ListItem.Tag) obj;

                v = activity.getLayoutInflater().inflate(R.layout.contact_group, parent, false);

                v.findViewById(R.id.arrow).setRotation(isExpanded ? 180 : 0);

                TextView tv = v.findViewById(R.id.text);
                tv.setText(activity.getString(R.string.contact_tag_with_total, tag.getName(), getChildrenCount(groupPosition)));
                tv.setBackgroundColor(tag.getColor());
            } else {
                Account acc = (Account) obj;

                v = activity.getLayoutInflater().inflate(R.layout.contact_account, parent, false);

                v.findViewById(R.id.arrow).setRotation(isExpanded ? 180 : 0);

                TextView tv = v.findViewById(R.id.text);
                tv.setText(acc.getJid().asBareJid().toString());

                Integer color = UIHelper.getColorForStatus(acc.getPresenceStatus());

                if (color != null) {
                    tv.setBackgroundColor(color);
                } else {
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
            }

            return v;
        }

        @Override
        public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
            ListItem item = getChildsList(groupPosition).get(childPosition);
            int position = super.getPosition(item);
            View view = super.getView(super.getPosition(item), convertView, parent);
            view.setTag(R.id.TAG_POSITION, position);
            return view;
        }

        @Override
        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }

        @Override
        public void onGroupExpanded(int groupPosition) {
            Object tagOrAccount = tagsAndAccounts.get(groupPosition);
            if (tagOrAccount instanceof Account) {
                expandedAccounts.add((Account) tagOrAccount);
                notifyDataSetChanged();
            }
        }

        @Override
        public void onGroupCollapsed(int groupPosition) {
            Object tagOrAccount = tagsAndAccounts.get(groupPosition);
            if (tagOrAccount instanceof Account) {
                expandedAccounts.remove((Account) tagOrAccount);
                notifyDataSetChanged();
            }
        }

        @Override
        public long getCombinedChildId(long groupId, long childId) {
            return 0x8000000000000000L | ((groupId & 0x7FFFFFFF) << 32) | (childId & 0xFFFFFFFF);
        }

        @Override
        public long getCombinedGroupId(long groupId) {
            return (groupId & 0x7FFFFFFF) << 32;
        }

        private List<ListItem> getChildsList(int groupPosition) {
            Object tagOrAccount = tagsAndAccounts.get(groupPosition);
            if (tagOrAccount instanceof Account) {
                return Collections.emptyList();
            } else {
                Account account = null;

                for (int i = groupPosition; i >= 0; i--) {
                    Object item = tagsAndAccounts.get(i);
                    if (item instanceof Account) {
                        account = (Account) item;
                        break;
                    }
                }

                if (account == null) {
                    return groupedItems.get(xmppConnectionService.getAccounts().get(0)).get((ListItem.Tag) tagOrAccount);
                } else {
                    return groupedItems.get(account).get((ListItem.Tag) tagOrAccount);
                }
            }
        }
    }
}
