package eu.siacs.conversations.ui;

import static eu.siacs.conversations.utils.PermissionUtils.writeGranted;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.KeyChain;
import android.security.KeyChainAliasCallback;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.databinding.DataBindingUtil;
import com.google.common.base.Strings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityWelcomeBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.InstallReferrerUtils;
import eu.siacs.conversations.utils.SignupUtils;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;
import java.util.List;

public class WelcomeActivity extends XmppActivity
        implements XmppConnectionService.OnAccountCreated, KeyChainAliasCallback {

    private XmppUri inviteUri;

    public static void launch(AppCompatActivity activity) {
        Intent intent = new Intent(activity, WelcomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    public void onInstallReferrerDiscovered(final Uri referrer) {
        Log.d(Config.LOGTAG, "welcome activity: on install referrer discovered " + referrer);
        if ("xmpp".equalsIgnoreCase(referrer.getScheme())) {
            final XmppUri xmppUri = new XmppUri(referrer);
            runOnUiThread(() -> processXmppUri(xmppUri));
        } else {
            Log.i(Config.LOGTAG, "install referrer was not an XMPP uri");
        }
    }

    private void processXmppUri(final XmppUri xmppUri) {
        if (!xmppUri.isValidJid()) {
            return;
        }
        final String preAuth = xmppUri.getParameter(XmppUri.PARAMETER_PRE_AUTH);
        final Jid jid = xmppUri.getJid();
        final Intent intent;
        if (xmppUri.isAction(XmppUri.ACTION_REGISTER)) {
            intent = SignupUtils.getTokenRegistrationIntent(this, jid, preAuth);
        } else if (xmppUri.isAction(XmppUri.ACTION_ROSTER)
                && "y".equals(xmppUri.getParameter(XmppUri.PARAMETER_IBR))) {
            intent = SignupUtils.getTokenRegistrationIntent(this, jid.getDomain(), preAuth);
            intent.putExtra(StartConversationActivity.EXTRA_INVITE_URI, xmppUri.toString());
        } else {
            intent = null;
        }
        if (intent != null) {
            startActivity(intent);
            finish();
            return;
        }
        this.inviteUri = xmppUri;
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {}

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }
        new InstallReferrerUtils(this);
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            setIntent(intent);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        ActivityWelcomeBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_welcome);
        final Toolbar toolbar = binding.toolbarLayout.findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        configureActionBar(getSupportActionBar(), false);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(null);
        }

        // Keep the onboarding surface visually continuous with the dark Mist palette.
        toolbar.setBackgroundColor(Color.TRANSPARENT);
        final View appBar = binding.toolbarLayout.findViewById(R.id.app_bar_layout);
        appBar.setBackgroundColor(Color.TRANSPARENT);
        appBar.setElevation(0f);
        getWindow().setStatusBarColor(Color.rgb(16, 20, 22));
        getWindow().setNavigationBarColor(Color.rgb(23, 34, 40));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int systemUi = getWindow().getDecorView().getSystemUiVisibility();
            systemUi &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                systemUi &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(systemUi);
        }
        binding.registerNewAccount.setOnClickListener(v -> {
            final Intent intent = new Intent(this, PickServerActivity.class);
            addInviteUri(intent);
            startActivity(intent);
        });
        binding.useExisting.setOnClickListener(v -> {
            final List<Account> accounts = xmppConnectionService.getAccounts();
            Intent intent = new Intent(WelcomeActivity.this, EditAccountActivity.class);
            intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, false);
            if (accounts.size() == 1) {
                intent.putExtra("jid", accounts.get(0).getJid().asBareJid().toString());
                intent.putExtra("init", true);
            } else if (accounts.size() >= 1) {
                intent = AccountUtils.manageAccountsIntent(WelcomeActivity.this);
            }
            addInviteUri(intent);
            startActivity(intent);
        });
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.welcome_menu, menu);
        final MenuItem scan = menu.findItem(R.id.action_scan_qr_code);
        scan.setVisible(Compatibility.hasFeatureCamera(this));
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_scan_qr_code:
                UriHandlerActivity.scan(this, true);
                break;
            case R.id.action_add_account_with_cert:
                addAccountFromKey();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void addAccountFromKey() {
        try {
            KeyChain.choosePrivateKeyAlias(this, this, null, null, null, -1, null);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.device_does_not_support_certificates, Toast.LENGTH_LONG)
                    .show();
        }
    }

    @Override
    public void alias(final String alias) {
        if (Strings.isNullOrEmpty(alias)) {
            runOnUiThread(
                    () ->
                            Toast.makeText(
                                            this,
                                            R.string.no_certificate_selected,
                                            Toast.LENGTH_LONG)
                                    .show());
            return;
        }
        xmppConnectionService.createAccountFromKey(alias, this);
    }

    @Override
    public void onAccountCreated(final Account account) {
        final Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra("jid", account.getJid().asBareJid().toString());
        intent.putExtra("init", true);
        addInviteUri(intent);
        startActivity(intent);
    }

    @Override
    public void informUser(final int r) {
        runOnUiThread(() -> Toast.makeText(this, r, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
        if (writeGranted(grantResults, permissions)) {
            if (xmppConnectionService != null) {
                xmppConnectionService.restartFileObserver();
            }
        }
    }

    public void addInviteUri(Intent to) {
        final Intent from = getIntent();
        if (from != null && from.hasExtra(StartConversationActivity.EXTRA_INVITE_URI)) {
            final String invite = from.getStringExtra(StartConversationActivity.EXTRA_INVITE_URI);
            to.putExtra(StartConversationActivity.EXTRA_INVITE_URI, invite);
        } else if (this.inviteUri != null) {
            Log.d(Config.LOGTAG, "injecting referrer uri into on-boarding flow");
            to.putExtra(StartConversationActivity.EXTRA_INVITE_URI, this.inviteUri.toString());
        }
    }
}
