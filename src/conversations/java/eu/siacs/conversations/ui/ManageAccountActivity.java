package eu.siacs.conversations.ui;


import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.security.KeyChain;
import android.security.KeyChainAliasCallback;
import android.util.Pair;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogEnterPasswordBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.OnAccountUpdate;
import eu.siacs.conversations.ui.adapter.AccountAdapter;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;

import static eu.siacs.conversations.utils.PermissionUtils.allGranted;
import static eu.siacs.conversations.utils.PermissionUtils.writeGranted;

public class ManageAccountActivity extends XmppActivity implements OnAccountUpdate,
        KeyChainAliasCallback,
        XmppConnectionService.OnAccountCreated,
        AccountAdapter.OnTglAccountState {

    private final String STATE_SELECTED_ACCOUNT = "selected_account";

    protected Account selectedAccount = null;
    protected Jid selectedAccountJid = null;

    protected final List<Account> accountList = new ArrayList<>();
    protected ListView accountListView;
    protected AccountAdapter mAccountAdapter;
    protected AtomicBoolean mInvokedAddAccount = new AtomicBoolean(false);

    protected Pair<Integer, Intent> mPostponedActivityResult = null;

    private AccountAdapter.ColorSelectorListener colorSelectorListener =
            (accountJid, currentColor) -> showAccountColorDialog(accountJid, currentColor);

    private final AccountAdapter.AccountActionListener accountActionListener =
            this::showAccountActions;

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        synchronized (this.accountList) {
            accountList.clear();
            accountList.addAll(xmppConnectionService.getAccounts());
        }
        mAccountAdapter.notifyDataSetChanged();
        final View container = findViewById(R.id.manage_accounts_container);
        final View empty = findViewById(R.id.manage_accounts_empty);
        final boolean isEmpty = accountList.isEmpty();
        container.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        final android.widget.TextView summary = findViewById(R.id.manage_accounts_summary);
        summary.setText(
                isEmpty
                        ? getString(R.string.profile_accounts_summary)
                        : getString(R.string.neocont_accounts_count, accountList.size()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_manage_accounts_neocont);
        if (savedInstanceState != null) {
            String jid = savedInstanceState.getString(STATE_SELECTED_ACCOUNT);
            if (jid != null) {
                try {
                    this.selectedAccountJid = Jid.of(jid);
                } catch (IllegalArgumentException e) {
                    this.selectedAccountJid = null;
                }
            }
        }

        accountListView = findViewById(R.id.account_list);
        this.mAccountAdapter =
                new AccountAdapter(
                        this, accountList, colorSelectorListener, accountActionListener);
        accountListView.setAdapter(this.mAccountAdapter);
        accountListView.setOnItemClickListener(
                (arg0, view, position, arg3) -> {
                    final Account account = accountList.get(position);
                    if (needsPasswordRecovery(account)) {
                        showAccountPasswordRecoveryDialog(account);
                    } else {
                        switchToAccount(account);
                    }
                });
        registerForContextMenu(accountListView);
        findViewById(R.id.manage_accounts_add).setOnClickListener(view -> launchAddAccount());
        findViewById(R.id.manage_accounts_empty_add)
                .setOnClickListener(view -> launchAddAccount());
        findViewById(R.id.manage_accounts_more)
                .setOnClickListener(this::showGlobalAccountActions);

    }

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }

    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        if (selectedAccount != null) {
            savedInstanceState.putString(STATE_SELECTED_ACCOUNT, selectedAccount.getJid().asBareJid().toString());
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        colorSelectorListener = null;
        mAccountAdapter.colorSelectorListener = null;
        mAccountAdapter.accountActionListener = null;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        ManageAccountActivity.this.getMenuInflater().inflate(
                R.menu.manageaccounts_context, menu);
        AdapterContextMenuInfo acmi = (AdapterContextMenuInfo) menuInfo;
        this.selectedAccount = accountList.get(acmi.position);

        configureAccountActionMenu(menu, this.selectedAccount);
        menu.setHeaderTitle(this.selectedAccount.getJid().asBareJid().toString());
    }

    @Override
    protected void onBackendConnected() {
        if (selectedAccountJid != null) {
            this.selectedAccount = xmppConnectionService.findAccountByJid(selectedAccountJid);
        }
        refreshUiReal();
        if (this.mPostponedActivityResult != null) {
            this.onActivityResult(mPostponedActivityResult.first, RESULT_OK, mPostponedActivityResult.second);
        }
        if (Config.X509_VERIFICATION && this.accountList.size() == 0) {
            if (mInvokedAddAccount.compareAndSet(false, true)) {
                addAccountFromKey();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (selectedAccount != null && performAccountAction(item.getItemId(), selectedAccount)) {
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    protected void deleteAccount(final Account account) {
        super.deleteAccount(account);
        this.selectedAccount = null;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case R.id.action_add_account:
                launchAddAccount();
                break;
            case R.id.action_disable_all:
                disableAllAccounts();
                break;
            case R.id.action_enable_all:
                enableAllAccounts();
                break;
            case R.id.action_add_account_with_cert:
                addAccountFromKey();
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0) {
            if (allGranted(grantResults)) {
            } else {
                Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
            }
        }
        if (writeGranted(grantResults, permissions)) {
            if (xmppConnectionService != null) {
                xmppConnectionService.restartFileObserver();
            }
        }
    }

    @Override
    public boolean onNavigateUp() {
        if (xmppConnectionService.getConversations().size() == 0) {
            Intent contactsIntent = new Intent(this,
                    StartConversationActivity.class);
            contactsIntent.setFlags(
                    // if activity exists in stack, pop the stack and go back to it
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            // otherwise, make a new task for it
                            Intent.FLAG_ACTIVITY_NEW_TASK |
                            // don't use the new activity animation; finish
                            // animation runs instead
                            Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(contactsIntent);
            finish();
            return true;
        } else {
            return super.onNavigateUp();
        }
    }

    @Override
    public void onClickTglAccountState(Account account, boolean enable) {
        if (enable) {
            enableAccount(account);
        } else {
            disableAccount(account);
        }
    }

    private void launchAddAccount() {
        if (Config.X509_VERIFICATION) {
            addAccountFromKey();
        } else {
            startActivity(new Intent(this, EditAccountActivity.class));
        }
    }

    private void showGlobalAccountActions(final View anchor) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        popup.inflate(R.menu.manageaccounts);
        final Menu menu = popup.getMenu();
        menu.findItem(R.id.action_add_account).setVisible(false);
        final MenuItem certificate = menu.findItem(R.id.action_add_account_with_cert);
        certificate.setVisible(true);
        final MenuItem enableAll = menu.findItem(R.id.action_enable_all);
        enableAll.setVisible(accountsLeftToEnable());
        final MenuItem disableAll = menu.findItem(R.id.action_disable_all);
        disableAll.setVisible(accountsLeftToDisable());
        popup.setOnMenuItemClickListener(
                item -> {
                    onOptionsItemSelected(item);
                    return true;
                });
        popup.show();
    }

    private void showAccountActions(final Account account, final View anchor) {
        selectedAccount = account;
        final PopupMenu popup = new PopupMenu(this, anchor);
        popup.inflate(R.menu.manageaccounts_context);
        configureAccountActionMenu(popup.getMenu(), account);
        popup.setOnMenuItemClickListener(
                item -> performAccountAction(item.getItemId(), account));
        popup.show();
    }

    private void configureAccountActionMenu(final Menu menu, final Account account) {
        menu.findItem(R.id.mgmt_account_publish_avatar).setVisible(account.isEnabled());

        final boolean canChangePassword =
                account.isOnlineAndConnected()
                        && account.getXmppConnection().getFeatures().register();
        menu.findItem(R.id.action_change_password_on_server).setVisible(canChangePassword);

        menu.findItem(R.id.mgmt_account_edit).setVisible(needsPasswordRecovery(account));
    }

    private boolean needsPasswordRecovery(final Account account) {
        final String runtimePassword = account.getPassword();
        return account.getPrivateKeyAlias() == null
                && (account.unauthorized()
                        || !account.isSecretVaultHydrated()
                        || runtimePassword == null
                        || runtimePassword.isEmpty());
    }

    private boolean performAccountAction(final int itemId, final Account account) {
        switch (itemId) {
            case R.id.mgmt_account_edit:
                showAccountPasswordRecoveryDialog(account);
                return true;
            case R.id.mgmt_account_publish_avatar:
                publishAvatar(account);
                return true;
            case R.id.action_change_password_on_server:
                gotoChangePassword(account);
                return true;
            case R.id.mgmt_account_delete:
                deleteAccount(account);
                return true;
            default:
                return false;
        }
    }

    private void showAccountPasswordRecoveryDialog(final Account account) {
        final DialogEnterPasswordBinding passwordBinding =
                DataBindingUtil.inflate(
                        LayoutInflater.from(this),
                        R.layout.dialog_enter_password,
                        null,
                        false);
        passwordBinding.explain.setText(
                getString(
                        R.string.account_password_recovery_explain,
                        account.getJid().asBareJid().toString()));

        final AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.account_password_recovery_title)
                        .setView(passwordBinding.getRoot())
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.connect, null)
                        .create();

        dialog.setOnShowListener(
                ignored ->
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        view -> {
                                            final String password =
                                                    passwordBinding.accountPassword
                                                            .getEditableText()
                                                            .toString();
                                            if (password.isEmpty()) {
                                                passwordBinding.accountPasswordLayout.setError(
                                                        getString(R.string.please_enter_password));
                                                return;
                                            }
                                            passwordBinding.accountPasswordLayout.setError(null);

                                            account.setPassword(password);
                                            account.setOption(Account.OPTION_DISABLED, false);
                                            account.setOption(Account.OPTION_SOFT_DISABLED, false);
                                            final XmppConnection connection =
                                                    account.getXmppConnection();
                                            if (connection != null) {
                                                connection.resetEverything();
                                            }

                                            if (!xmppConnectionService.updateAccount(account)) {
                                                account.clearTransientAuthenticationSecrets();
                                                passwordBinding.accountPassword.getEditableText().clear();
                                                Toast.makeText(
                                                                ManageAccountActivity.this,
                                                                R.string.unable_to_update_account,
                                                                Toast.LENGTH_SHORT)
                                                        .show();
                                                return;
                                            }
                                            passwordBinding.accountPassword.getEditableText().clear();
                                            dialog.dismiss();
                                        }));
        dialog.show();
    }

    private void showAccountColorDialog(final Jid accountJid, final int currentColor) {
        final View content =
                LayoutInflater.from(this).inflate(R.layout.dialog_accent_palette, null, false);
        final GridLayout grid = content.findViewById(R.id.accent_palette_grid);
        final String[] actualColors = getResources().getStringArray(R.array.themeAccentColorsV2);
        final String[] pickerColors =
                getResources().getStringArray(R.array.themeAccentPickerColorsV2);
        final AlertDialog dialog =
                new MaterialAlertDialogBuilder(this).setView(content).create();

        final int count = Math.min(actualColors.length, pickerColors.length);
        final int swatchSize = dpToPx(40);
        final int swatchMargin = dpToPx(6);
        for (int i = 0; i < count; i++) {
            final int actualColor = Color.parseColor(actualColors[i]);
            final int displayColor = Color.parseColor(pickerColors[i]);
            final boolean selected = actualColor == currentColor;

            final FrameLayout cell = new FrameLayout(this);
            final GridLayout.LayoutParams cellParams =
                    new GridLayout.LayoutParams(
                            GridLayout.spec(GridLayout.UNDEFINED, 1f),
                            GridLayout.spec(GridLayout.UNDEFINED, 1f));
            cellParams.width = 0;
            cellParams.height = swatchSize + swatchMargin * 2;
            cell.setLayoutParams(cellParams);

            final View swatch = new View(this);
            final FrameLayout.LayoutParams swatchParams =
                    new FrameLayout.LayoutParams(swatchSize, swatchSize);
            swatchParams.gravity = Gravity.CENTER;
            swatch.setLayoutParams(swatchParams);

            final GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(displayColor);
            background.setStroke(
                    dpToPx(selected ? 3 : 1),
                    selected
                            ? com.google.android.material.color.MaterialColors.getColor(
                                    swatch,
                                    com.google.android.material.R.attr.colorOnSurface)
                            : com.google.android.material.color.MaterialColors.getColor(
                                    swatch,
                                    com.google.android.material.R.attr.colorOutlineVariant));
            swatch.setBackground(background);
            swatch.setContentDescription(
                    getString(R.string.neocont_account_color_accessibility)
                            + " "
                            + (i + 1));
            swatch.setOnClickListener(
                    view -> {
                        UIHelper.overrideAccountColor(
                                this, accountJid.asBareJid().toString(), actualColor);
                        // The account placeholder is bitmap-cached. Drop only this account so its
                        // gradient immediately follows the newly selected account color.
                        if (xmppConnectionService != null) {
                            final Account selectedAccount =
                                    xmppConnectionService.findAccountByJid(accountJid);
                            if (selectedAccount != null) {
                                xmppConnectionService.getAvatarService().clear(selectedAccount);
                            }
                        }
                        refreshAccountColorImmediately();
                        dialog.dismiss();
                        refreshUiReal();
                    });
            cell.addView(swatch);
            grid.addView(cell);
        }
        dialog.show();
    }

    private void refreshAccountColorImmediately() {
        if (mAccountAdapter != null) {
            mAccountAdapter.notifyDataSetChanged();
        }
        if (accountListView != null) {
            // ListView can keep already attached child views after notifyDataSetChanged().
            // Force those visible rows through getView() now so the color pill, avatar backing
            // circle and any color-derived placeholder update before the picker disappears.
            accountListView.invalidateViews();
        }
    }

    private int dpToPx(final int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void addAccountFromKey() {
        try {
            KeyChain.choosePrivateKeyAlias(this, this, null, null, null, -1, null);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.device_does_not_support_certificates, Toast.LENGTH_LONG).show();
        }
    }

    private void publishAvatar(Account account) {
        Intent intent = new Intent(getApplicationContext(),
                AvatarCropEditorActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        startActivity(intent);
    }

    private void disableAllAccounts() {
        List<Account> list = new ArrayList<>();
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (account.isEnabled()) {
                    list.add(account);
                }
            }
        }
        for (Account account : list) {
            disableAccount(account);
        }
    }

    private boolean accountsLeftToDisable() {
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (account.isEnabled()) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean accountsLeftToEnable() {
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (!account.isEnabled()) {
                    return true;
                }
            }
            return false;
        }
    }

    private void enableAllAccounts() {
        List<Account> list = new ArrayList<>();
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (!account.isEnabled()) {
                    list.add(account);
                }
            }
        }
        for (Account account : list) {
            enableAccount(account);
        }
    }

    private void disableAccount(Account account) {
        account.setOption(Account.OPTION_DISABLED, true);
        if (!xmppConnectionService.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
        }
    }

    private void enableAccount(Account account) {
        account.setOption(Account.OPTION_DISABLED, false);
        account.setOption(Account.OPTION_SOFT_DISABLED, false);
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.resetEverything();
        }
        if (!xmppConnectionService.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
        }
    }

    private void gotoChangePassword(Account selectedAccount) {
        final Intent changePasswordIntent = new Intent(this, ChangePasswordActivity.class);
        changePasswordIntent.putExtra(EXTRA_ACCOUNT, selectedAccount.getJid().toString());
        startActivity(changePasswordIntent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void alias(final String alias) {
        if (alias != null) {
            xmppConnectionService.createAccountFromKey(alias, this);
        }
    }

    @Override
    public void onAccountCreated(final Account account) {
        final Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra("jid", account.getJid().asBareJid().toString());
        intent.putExtra("init", true);
        startActivity(intent);
    }

    @Override
    public void informUser(final int r) {
        runOnUiThread(() -> Toast.makeText(ManageAccountActivity.this, r, Toast.LENGTH_LONG).show());
    }
}
