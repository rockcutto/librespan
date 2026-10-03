package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.databinding.DataBindingUtil;

import com.google.common.collect.Collections2;
import com.google.common.collect.Ordering;

import java.util.ArrayList;
import java.util.Locale;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityMucUsersBinding;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.UserAdapter;
import eu.siacs.conversations.ui.util.MucDetailsContextMenuHelper;
import eu.siacs.conversations.xmpp.Jid;

public class MucUsersActivity extends XmppActivity implements XmppConnectionService.OnMucRosterUpdate, XmppConnectionService.OnAffiliationChanged, MenuItem.OnActionExpandListener, TextWatcher {

    private UserAdapter userAdapter;
    private ActivityMucUsersBinding binding;

    private Conversation mConversation = null;

    private EditText mSearchEditText;

    private ArrayList<MucOptions.User> allUsers = new ArrayList<>();

    @Override
    protected void refreshUiReal() {
    }

    @Override
    protected void onBackendConnected() {
        final Intent intent = getIntent();
        final String uuid = intent == null ? null : intent.getStringExtra("uuid");
        if (uuid != null) {
            mConversation = xmppConnectionService.findConversationByUuid(uuid);
        }
        if (mConversation != null && binding != null) {
            final MucOptions.User self = mConversation.getMucOptions().getSelf();
            final boolean canManage =
                    self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)
                            || self.getRole().ranks(MucOptions.Role.MODERATOR);
            binding.mucAdminUsersHint.setVisibility(canManage ? View.VISIBLE : View.GONE);
            if (self.getAffiliation() == MucOptions.Affiliation.OWNER) {
                xmppConnectionService.refreshMucOwnerAdminAffiliations(mConversation);
            }
        }
        loadAndSubmitUsers();
    }

    private void loadAndSubmitUsers() {
        if (mConversation != null) {
            final MucOptions mucOptions = mConversation.getMucOptions();
            allUsers = mucOptions.getUsers();
            if (mucOptions.canSelfRevokeOwner()) {
                allUsers.add(mucOptions.getSelf());
            }
            submitFilteredList(mSearchEditText != null ? mSearchEditText.getText().toString() : null);
        }
    }

    private void submitFilteredList(final String search) {
        if (TextUtils.isEmpty(search)) {
            userAdapter.submitList(Ordering.natural().immutableSortedCopy(allUsers));
        } else {
            final String needle = search.toLowerCase(Locale.getDefault());
            userAdapter.submitList(
                    Ordering.natural()
                            .immutableSortedCopy(
                                    Collections2.filter(
                                            this.allUsers,
                                            user -> {
                                                final String name = user.getName();
                                                final Contact contact = user.getContact();
                                                return name != null
                                                                && name.toLowerCase(
                                                                                Locale.getDefault())
                                                                        .contains(needle)
                                                        || contact != null
                                                                && contact.getDisplayName()
                                                                        .toLowerCase(
                                                                                Locale.getDefault())
                                                                        .contains(needle);
                                            })));
        }
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (!MucDetailsContextMenuHelper.onContextItemSelected(item, userAdapter.getSelectedUser(), this)) {
            return super.onContextItemSelected(item);
        }
        return true;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_muc_users);
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar(), true);
        setTitle(R.string.muc_admin_people);
        this.userAdapter = new UserAdapter(true);
        binding.list.setAdapter(this.userAdapter);
    }


    @Override
    public void onMucRosterUpdate() {
        loadAndSubmitUsers();
    }

     private void displayToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onAffiliationChangedSuccessful(Jid jid) {
        loadAndSubmitUsers();
    }

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, jid.asBareJid().toString()));
    }

    public void onUserClicked(final View anchor, final MucOptions.User user) {
        if (mConversation == null || user == null) {
            return;
        }
        final MucOptions mucOptions = mConversation.getMucOptions();
        final MucOptions.User self = mucOptions.getSelf();
        final boolean targetIsSelf =
                user.realJidMatchesAccount()
                        || (user.getFullJid() != null
                                && user.getFullJid().equals(self.getFullJid()));
        final boolean canManage =
                targetIsSelf
                        ? mucOptions.canSelfRevokeOwner()
                        : (self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)
                                || self.getRole().ranks(MucOptions.Role.MODERATOR));
        if (!canManage) {
            highlightInMuc(user.getConversation(), user.getName());
            return;
        }

        final PopupMenu popupMenu =
                new PopupMenu(
                        new android.view.ContextThemeWrapper(
                                this, R.style.ThemeOverlay_App_PopupMenu),
                        anchor);
        popupMenu.inflate(R.menu.muc_details_context);
        MucDetailsContextMenuHelper.configureMucDetailsContextMenu(
                this, popupMenu.getMenu(), mConversation, user);
        if (popupMenu.getMenu() instanceof androidx.appcompat.view.menu.MenuBuilder) {
            ((androidx.appcompat.view.menu.MenuBuilder) popupMenu.getMenu())
                    .setGroupDividerEnabled(true);
        }
        popupMenu.setOnMenuItemClickListener(
                item -> MucDetailsContextMenuHelper.onContextItemSelected(item, user, this));
        popupMenu.show();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.muc_users_activity, menu);
        final MenuItem menuSearchView = menu.findItem(R.id.action_search);
        final View mSearchView = menuSearchView.getActionView();
        mSearchEditText = mSearchView.findViewById(R.id.search_field);
        mSearchEditText.addTextChangedListener(this);
        mSearchEditText.setHint(R.string.search_participants);
        menuSearchView.setOnActionExpandListener(this);
        return true;
    }

    @Override
    public boolean onMenuItemActionExpand(MenuItem item) {
        mSearchEditText.post(() -> {
            mSearchEditText.requestFocus();
            final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
        });
        return true;
    }

    @Override
    public boolean onMenuItemActionCollapse(MenuItem item) {
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(mSearchEditText.getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        mSearchEditText.setText("");
        submitFilteredList("");
        return true;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }

    @Override
    public void afterTextChanged(Editable s) {
        submitFilteredList(s.toString());
    }
}
