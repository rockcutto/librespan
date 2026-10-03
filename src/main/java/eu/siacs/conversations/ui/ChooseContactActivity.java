package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;

public class ChooseContactActivity extends AbstractSearchableListItemActivity
        implements MultiChoiceModeListener, AdapterView.OnItemClickListener {
    public static final String EXTRA_TITLE_RES_ID = "extra_title_res_id";
    public static final String EXTRA_GROUP_CHAT_NAME = "extra_group_chat_name";
    public static final String EXTRA_SELECT_MULTIPLE = "extra_select_multiple";
    public static final String EXTRA_SHOW_ENTER_JID = "extra_show_enter_jid";
    public static final String EXTRA_CONVERSATION = "extra_conversation";
    private static final String EXTRA_INVITE_MODE = "extra_invite_mode";
    private static final String EXTRA_FILTERED_CONTACTS = "extra_filtered_contacts";
    private final ArrayList<String> mActivatedAccounts = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private Set<String> filterContacts;

    private boolean showEnterJid = false;
    private boolean startSearching = false;
    private boolean multiple = false;
    private boolean inviteMode = false;

    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();

    public static Intent create(Activity activity, Conversation conversation) {
        final Intent intent = new Intent(activity, ChooseContactActivity.class);
        List<String> contacts = new ArrayList<>();
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            for (MucOptions.User user : conversation.getMucOptions().getUsers(false)) {
                Jid jid = user.getRealJid();
                if (jid != null) {
                    contacts.add(jid.asBareJid().toString());
                }
            }
        } else {
            contacts.add(conversation.getJid().asBareJid().toString());
        }
        // Never offer the account itself as a member candidate.
        contacts.add(conversation.getAccount().getJid().asBareJid().toString());
        intent.putExtra(EXTRA_FILTERED_CONTACTS, contacts.toArray(new String[0]));
        intent.putExtra(EXTRA_CONVERSATION, conversation.getUuid());
        intent.putExtra(EXTRA_SELECT_MULTIPLE, true);
        intent.putExtra(EXTRA_SHOW_ENTER_JID, true);
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            intent.putExtra(EXTRA_INVITE_MODE, true);
            intent.putExtra(
                    EXTRA_GROUP_CHAT_NAME,
                    conversation.getName() == null ? null : conversation.getName().toString());
        }
        intent.putExtra(EXTRA_ACCOUNT, conversation.getAccount().getJid().asBareJid().toString());
        return intent;
    }

    public static Intent createForNewGroup(
            final Activity activity, final Account account, final String groupName) {
        final Intent intent = new Intent(activity, ChooseContactActivity.class);
        intent.putExtra(
                EXTRA_FILTERED_CONTACTS,
                new String[] {account.getJid().asBareJid().toString()});
        intent.putExtra(EXTRA_SELECT_MULTIPLE, true);
        intent.putExtra(EXTRA_SHOW_ENTER_JID, true);
        intent.putExtra(EXTRA_INVITE_MODE, true);
        intent.putExtra(EXTRA_GROUP_CHAT_NAME, groupName);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        return intent;
    }

    public static List<Jid> extractJabberIds(Intent result) {
        final List<Jid> jabberIds = new ArrayList<>();
        if (result == null) {
            return jabberIds;
        }
        try {
            if (result.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false)) {
                final String[] toAdd = result.getStringArrayExtra("contacts");
                if (toAdd == null) {
                    return jabberIds;
                }
                for (String item : toAdd) {
                    if (item != null) {
                        jabberIds.add(Jid.of(item).asBareJid());
                    }
                }
            } else {
                final String contact = result.getStringExtra("contact");
                if (contact != null) {
                    jabberIds.add(Jid.of(contact).asBareJid());
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Return the valid addresses already collected.
        }
        return jabberIds;
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filterContacts = new HashSet<>();
        if (savedInstanceState != null) {
            String[] selectedContacts = savedInstanceState.getStringArray("selected_contacts");
            if (selectedContacts != null) {
                selected.clear();
                selected.addAll(Arrays.asList(selectedContacts));
            }
        }

        String[] contacts = getIntent().getStringArrayExtra(EXTRA_FILTERED_CONTACTS);
        if (contacts != null) {
            Collections.addAll(filterContacts, contacts);
        }

        final Intent intent = getIntent();
        inviteMode = intent.getBooleanExtra(EXTRA_INVITE_MODE, false);
        multiple = intent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false);

        if (multiple && !inviteMode) {
            getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
            getListView().setMultiChoiceModeListener(this);
        } else {
            getListView().setChoiceMode(ListView.CHOICE_MODE_NONE);
        }

        getListView().setOnItemClickListener(this);
        this.showEnterJid = intent.getBooleanExtra(EXTRA_SHOW_ENTER_JID, false);
        this.binding.fab.setOnClickListener(this::onFabClicked);

        if (inviteMode) {
            configureInviteMode(intent);
        } else if (this.showEnterJid) {
            this.binding.fab.show();
        } else {
            binding.fab.setImageResource(R.drawable.ic_navigate_next_24dp);
        }

        final SharedPreferences preferences = getPreferences();
        this.startSearching = intent.getBooleanExtra("direct_search", false) && preferences.getBoolean("start_searching", getResources().getBoolean(R.bool.start_searching));

        getListItemAdapter().refreshSettings();
        getListItemAdapter().setOnTagClickedListener((tag) -> {
            if (mMenuSearchView != null) {
                mMenuSearchView.expandActionView();
                mSearchEditText.setText("");
                mSearchEditText.append(tag);
                filterContacts(tag);
            }
        });
    }

    private void configureInviteMode(final Intent intent) {
        binding.fab.hide();
        binding.inviteActionBar.setVisibility(View.VISIBLE);
        binding.inviteByAddress.setVisibility(showEnterJid ? View.VISIBLE : View.GONE);
        binding.inviteByAddress.setOnClickListener(view -> showEnterJidDialog(null));
        binding.inviteAdd.setOnClickListener(view -> submitSelection());

        final String groupName = intent.getStringExtra(EXTRA_GROUP_CHAT_NAME);
        if (Strings.isNullOrEmpty(groupName)) {
            binding.inviteContext.setVisibility(View.GONE);
        } else {
            binding.inviteContext.setText(getString(R.string.group_add_members_context, groupName));
            binding.inviteContext.setVisibility(View.VISIBLE);
        }

        getListItemAdapter().setSelectionState(selected);
        updateInviteSelectionUi();
    }

    private void updateInviteSelectionUi() {
        if (!inviteMode) {
            return;
        }
        final int count = selected.size();
        binding.inviteAdd.setEnabled(count > 0);
        binding.inviteAdd.setText(
                count > 0
                        ? getString(R.string.group_add_members_action_count, count)
                        : getString(R.string.group_add_members_action));
        getListItemAdapter().notifyDataSetChanged();
    }

    private void onFabClicked(View v) {
        if (selected.isEmpty()) {
            showEnterJidDialog(null);
        } else {
            submitSelection();
        }
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.setTitle(getTitleFromIntent());
        binding.chooseContactList.setFastScrollEnabled(false);
        binding.fab.setImageResource(R.drawable.ic_navigate_next_24dp);
        binding.fab.show();
        final View view = getSearchEditText();
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (view != null && imm != null) {
            imm.hideSoftInputFromWindow(
                    getSearchEditText().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        this.binding.fab.setImageResource(R.drawable.ic_person_add_24dp);
        if (this.showEnterJid) {
            this.binding.fab.show();
        } else {
            this.binding.fab.hide();
        }
        binding.chooseContactList.setFastScrollEnabled(true);
        selected.clear();
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        return false;
    }

    private void submitSelection() {
        final Intent request = getIntent();
        final Intent data = new Intent();
        data.putExtra("contacts", getSelectedContactJids());
        data.putExtra(EXTRA_SELECT_MULTIPLE, true);
        data.putExtra(EXTRA_ACCOUNT, request.getStringExtra(EXTRA_ACCOUNT));
        copy(request, data);
        setResult(RESULT_OK, data);
        finish();
    }

    private static void copy(Intent from, Intent to) {
        to.putExtra(EXTRA_CONVERSATION, from.getStringExtra(EXTRA_CONVERSATION));
        to.putExtra(EXTRA_GROUP_CHAT_NAME, from.getStringExtra(EXTRA_GROUP_CHAT_NAME));
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        if (selected.size() != 0) {
            getListView().playSoundEffect(SoundEffectConstants.CLICK);
        }
        getListItemAdapter().notifyDataSetChanged();
        Contact item = (Contact) getListItems().get(position);
        final String bare = item.getJid().asBareJid().toString();
        if (checked) {
            selected.add(bare);
        } else {
            selected.remove(bare);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            try {
                bar.setTitle(getTitleFromIntent());
            } catch (Exception e) {
                bar.setTitle(R.string.title_activity_choose_contact);
            }
        }
    }

    public @StringRes int getTitleFromIntent() {
        final Intent intent = getIntent();
        boolean multiple = intent != null && intent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false);
        if (intent != null && intent.getBooleanExtra(EXTRA_INVITE_MODE, false)) {
            return R.string.group_add_members_title;
        }
        @StringRes
        int fallback =
                multiple
                        ? R.string.title_activity_choose_contacts
                        : R.string.title_activity_choose_contact;
        return intent != null ? intent.getIntExtra(EXTRA_TITLE_RES_ID, fallback) : fallback;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        final Intent i = getIntent();
        boolean showEnterJid = i != null && i.getBooleanExtra(EXTRA_SHOW_ENTER_JID, false);
        menu.findItem(R.id.action_scan_qr_code)
                .setVisible(isCameraFeatureAvailable() && showEnterJid);
        MenuItem mMenuSearchView = menu.findItem(R.id.action_search);
        if (startSearching) {
            mMenuSearchView.expandActionView();
        }
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putStringArray("selected_contacts", getSelectedContactJids());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (multiple) {
            return false;
        } else {
            List<ListItem> items = getListItems();
            if (items.size() == 1) {
                onListItemClicked(items.get(0));
                return true;
            }
            return false;
        }
    }

    protected void filterContacts(final String needle) {
        getListItems().clear();
        if (xmppConnectionService == null) {
            getListItemAdapter().notifyDataSetChanged();
            return;
        }

        final Set<String> seen = new HashSet<>();

        // In invite mode, every other enabled local account is a valid participant JID.
        // Add those self identities first so they are not hidden by a duplicate roster entry from
        // another account. The account that owns the conference is already present in
        // filterContacts and remains excluded.
        if (inviteMode) {
            for (final Account account : xmppConnectionService.getAccounts()) {
                if (!account.isEnabled()) {
                    continue;
                }
                final Contact self = new Contact(account.getSelfContact());
                final String bare = self.getJid().asBareJid().toString();
                if (!filterContacts.contains(bare)
                        && self.match(this, needle)
                        && seen.add(bare)) {
                    getListItems().add(self);
                }
            }
        }

        for (final Account account : xmppConnectionService.getAccounts()) {
            if (!account.isEnabled()) {
                continue;
            }
            for (final Contact contact : account.getRoster().getContacts()) {
                final String bare = contact.getJid().asBareJid().toString();
                if (contact.showInContactList()
                        && !filterContacts.contains(bare)
                        && contact.match(this, needle)
                        && (!inviteMode || seen.add(bare))) {
                    getListItems().add(contact);
                }
            }

            if (!inviteMode) {
                final Contact self = new Contact(account.getSelfContact());
                self.setSystemName(getString(R.string.saved_messages));
                if (self.match(this, needle)) {
                    getListItems().add(self);
                }
            }
        }
        Collections.sort(getListItems());
        getListItemAdapter().notifyDataSetChanged();

        if (!inviteMode) {
            for (int i = 0; i < getListItemAdapter().getCount(); i++) {
                getListView().setItemChecked(
                        i,
                        selected.contains(
                                getListItemAdapter().getItem(i).getJid().asBareJid().toString()));
            }
        }
    }

    private String[] getSelectedContactJids() {
        return selected.toArray(new String[0]);
    }

    public void refreshUiReal() {
        // nothing to do. This Activity doesn't implement any listeners
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_scan_qr_code:
                ScanActivity.scan(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void showEnterJidDialog(XmppUri uri) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        Jid jid = uri == null ? null : uri.getJid();
        final ArrayList<String> availableAccounts = new ArrayList<>();
        if (inviteMode) {
            final String targetAccount = getIntent().getStringExtra(EXTRA_ACCOUNT);
            if (!Strings.isNullOrEmpty(targetAccount)) {
                availableAccounts.add(targetAccount);
            }
        } else {
            availableAccounts.addAll(mActivatedAccounts);
        }
        EnterJidDialog dialog =
                EnterJidDialog.newInstance(
                        availableAccounts,
                        getString(inviteMode ? R.string.group_add_members_address_title : R.string.enter_contact),
                        getString(inviteMode ? R.string.group_add_members_action : R.string.select),
                        null,
                jid == null ? null : jid.asBareJid().toString(),
                getIntent().getStringExtra(EXTRA_ACCOUNT),
                true,
                false,
                EnterJidDialog.SanityCheck.NO
        );

        dialog.setOnEnterJidDialogPositiveListener(
                (accountJid, contactJid, x, y) -> {
                    final String bareContact = contactJid.asBareJid().toString();
                    if (inviteMode && (filterContacts.contains(bareContact) || selected.contains(bareContact))) {
                        Toast.makeText(
                                        this,
                                        R.string.group_add_member_already_present,
                                        Toast.LENGTH_SHORT)
                                .show();
                        return false;
                    }
                    final Intent request = getIntent();
                    final Intent data = new Intent();
                    if (inviteMode) {
                        selected.add(bareContact);
                        data.putExtra("contacts", getSelectedContactJids());
                        data.putExtra(EXTRA_SELECT_MULTIPLE, true);
                        data.putExtra(
                                EXTRA_ACCOUNT,
                                request.getStringExtra(EXTRA_ACCOUNT));
                    } else {
                        data.putExtra("contact", bareContact);
                        data.putExtra(EXTRA_ACCOUNT, accountJid.toString());
                        data.putExtra(EXTRA_SELECT_MULTIPLE, false);
                    }
                    copy(request, data);
                    setResult(RESULT_OK, data);
                    finish();

                    return true;
                });

        dialog.show(ft, "dialog");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, intent);
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    private void handleActivityResult(ActivityResult activityResult) {
        if (activityResult != null
                && activityResult.resultCode == RESULT_OK
                && activityResult.requestCode == ScanActivity.REQUEST_SCAN_QR_CODE
                && activityResult.data != null) {
            String result = activityResult.data.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
            XmppUri uri = new XmppUri(Strings.nullToEmpty(result));
            if (uri.isValidJid()) {
                showEnterJidDialog(uri);
            }
        }
    }

    @Override
    protected void onBackendConnected() {
        filterContacts();
        if (inviteMode) {
            binding.chooseContactList.setEmptyView(binding.inviteEmpty);
        }
        this.mActivatedAccounts.clear();
        final String inviteAccount =
                inviteMode ? getIntent().getStringExtra(EXTRA_ACCOUNT) : null;
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (!account.isEnabled()) {
                continue;
            }
            final String accountJid = account.getJid().asBareJid().toString();
            if (!inviteMode || accountJid.equals(inviteAccount)) {
                this.mActivatedAccounts.add(accountJid);
            }
        }
        ActivityResult activityResult = this.postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }
        final Fragment fragment =
                getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (fragment instanceof OnBackendConnected) {
            ((OnBackendConnected) fragment).onBackendConnected();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        ScanActivity.onRequestPermissionResult(this, requestCode, grantResults);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (inviteMode) {
            final ListItem item = getListItemAdapter().getItem(position);
            if (item == null || item.getJid() == null) {
                return;
            }
            final String bare = item.getJid().asBareJid().toString();
            if (!selected.add(bare)) {
                selected.remove(bare);
            }
            updateInviteSelectionUi();
            return;
        }

        if (multiple) {
            final String bare =
                    getListItemAdapter().getItem(position).getJid().asBareJid().toString();
            if (getListView().isItemChecked(position)) {
                selected.add(bare);
            } else {
                selected.remove(bare);
            }

            if (selected.isEmpty()) {
                this.binding.fab.setImageResource(R.drawable.ic_person_add_24dp);
                if (this.showEnterJid) {
                    this.binding.fab.show();
                } else {
                    this.binding.fab.hide();
                }
            } else {
                binding.fab.setImageResource(R.drawable.ic_navigate_next_24dp);
                binding.fab.show();
            }

            return;
        }
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(
                getSearchEditText().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        final ListItem mListItem = getListItems().get(position);
        onListItemClicked(mListItem);
    }

    private void onListItemClicked(ListItem item) {
        final Intent request = getIntent();
        final Intent data = new Intent();
        data.putExtra("contact", item.getJid().toString());
        String account = request.getStringExtra(EXTRA_ACCOUNT);
        if (account == null && item instanceof Contact) {
            account = ((Contact) item).getAccount().getJid().asBareJid().toString();
        }
        data.putExtra(EXTRA_ACCOUNT, account);
        data.putExtra(EXTRA_SELECT_MULTIPLE, false);
        copy(request, data);
        setResult(RESULT_OK, data);
        finish();
    }
}
