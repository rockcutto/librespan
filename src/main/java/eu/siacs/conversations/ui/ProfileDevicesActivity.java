package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlSession;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.navigation.ProfileAccountResolution;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.UIHelper;

public class ProfileDevicesActivity extends OmemoActivity
        implements XmppConnectionService.OnAccountUpdate {

    private LinearLayout devices;
    private View accountContext;
    private MaterialCardView accountColor;
    private ImageView accountAvatar;
    private ImageView accountSwitch;
    private TextView accountLabel;
    private TextView deviceCount;
    private TextView emptyState;
    private MaterialButton showQr;
    private MaterialButton scanQr;
    private Account account;
    private boolean hasOtherDevices;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_devices);
        setSupportActionBar(findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }

        devices = findViewById(R.id.profile_devices_list);
        accountContext = findViewById(R.id.profile_devices_account_context);
        accountColor = findViewById(R.id.profile_devices_account_color);
        accountAvatar = findViewById(R.id.profile_devices_account_avatar);
        accountSwitch = findViewById(R.id.profile_devices_account_switch);
        accountLabel = findViewById(R.id.profile_devices_account);
        deviceCount = findViewById(R.id.profile_devices_count);
        emptyState = findViewById(R.id.profile_devices_empty);

        accountContext.setOnClickListener(view -> showAccountPicker());

        showQr = findViewById(R.id.profile_devices_show_qr);
        showQr.setOnClickListener(
                view -> {
                    if (account != null) {
                        showQrCode(account.getShareableUri());
                    }
                });

        scanQr = findViewById(R.id.profile_devices_scan_qr);
        scanQr.setOnClickListener(view -> ScanActivity.scan(this));
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.profile_devices, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final MenuItem clearList = menu.findItem(R.id.action_profile_devices_clear_list);
        if (clearList != null) {
            clearList.setVisible(hasOtherDevices);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_profile_devices_reset_keys) {
            showResetOmemoDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_profile_devices_clear_list) {
            showClearDevicesDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackendConnected() {
        resolveAccountAndRefresh();
        if (mPendingFingerprintVerificationUri != null && account != null) {
            processFingerprintVerification(mPendingFingerprintVerificationUri);
            mPendingFingerprintVerificationUri = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (xmppConnectionService != null) {
            refreshUi();
        }
    }

    @Override
    public void onAccountUpdate() {
        runOnUiThread(
                () -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    resolveAccountAndRefresh();
                });
    }

    private void resolveAccountAndRefresh() {
        final ProfileAccountResolution resolution =
                ProfileNavigation.resolveAccount(xmppConnectionService, getIntent());
        if (resolution instanceof ProfileAccountResolution.Resolved) {
            account = ((ProfileAccountResolution.Resolved) resolution).getAccount();
            refreshUiReal();
            return;
        }
        account = null;
        if (resolution instanceof ProfileAccountResolution.MissingContextualAccount) {
            finish();
            return;
        }
        refreshUiReal();
    }

    private void showAccountPicker() {
        if (xmppConnectionService == null) {
            return;
        }
        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        if (accounts.size() <= 1) {
            return;
        }
        AccountChoiceBottomSheet.show(
                this,
                xmppConnectionService,
                R.string.profile_accounts_picker_title,
                accounts,
                this::switchAccount);
    }

    private void switchAccount(final Account target) {
        if (target == null
                || (account != null && target.getUuid().equals(account.getUuid()))) {
            return;
        }
        account = target;
        setIntent(ProfileNavigation.contextualDevicesIntent(this, target.getUuid()));
        ProfileNavigation.rememberProfileAccount(this, target.getUuid());
        refreshUiReal();
    }

    @Override
    protected void refreshUiReal() {
        if (account == null || xmppConnectionService == null || devices == null) {
            return;
        }

        devices.removeAllViews();
        final AxolotlService axolotlService = account.getAxolotlService();
        final int ownDeviceId = axolotlService.getOwnDeviceId();
        final Set<Integer> announcedDeviceIds = new HashSet<>();
        final Set<Integer> knownDeviceIds = axolotlService.getOwnDeviceIds();
        if (knownDeviceIds != null) {
            announcedDeviceIds.addAll(knownDeviceIds);
        }
        announcedDeviceIds.remove(ownDeviceId);

        final Map<Integer, XmppAxolotlSession> knownOwnSessions = new HashMap<>();
        final Set<Integer> ids = new HashSet<>();
        ids.add(ownDeviceId);
        ids.addAll(announcedDeviceIds);
        for (final XmppAxolotlSession session : axolotlService.findOwnSessions()) {
            if (session == null
                    || session.getFingerprint() == null
                    || session.getTrust().isCompromised()) {
                continue;
            }
            final int deviceId = session.getRemoteAddress().getDeviceId();
            if (deviceId == ownDeviceId) {
                continue;
            }
            knownOwnSessions.put(deviceId, session);
            ids.add(deviceId);
        }

        final List<Integer> orderedIds = new ArrayList<>(ids);
        Collections.sort(orderedIds);
        orderedIds.remove(Integer.valueOf(ownDeviceId));
        orderedIds.add(0, ownDeviceId);

        // Server-side actions only make sense for devices still announced in PEP. Historical
        // sessions remain visible below as inactive so this screen matches the contact/device UI.
        hasOtherDevices = !announcedDeviceIds.isEmpty();
        final boolean hasKnownOtherDevices = orderedIds.size() > 1;
        invalidateOptionsMenu();

        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        final boolean multiAccount = accounts.size() > 1;
        final int avatarSize =
                Math.round(32f * getResources().getDisplayMetrics().density);
        accountContext.setVisibility(View.VISIBLE);
        accountAvatar.setImageBitmap(
                xmppConnectionService.getAvatarService().get(account, avatarSize));
        accountColor.setCardBackgroundColor(
                UIHelper.getAccountColor(this, account.getJid()));
        accountLabel.setText(account.getJid().asBareJid().toString());
        accountSwitch.setVisibility(multiAccount ? View.VISIBLE : View.GONE);
        accountContext.setClickable(multiAccount);
        accountContext.setFocusable(multiAccount);

        showQr.setVisibility(View.VISIBLE);
        scanQr.setVisibility(isCameraFeatureAvailable() ? View.VISIBLE : View.GONE);
        deviceCount.setText(
                getResources()
                        .getQuantityString(
                                R.plurals.profile_devices_count,
                                orderedIds.size(),
                                orderedIds.size()));
        emptyState.setVisibility(hasKnownOtherDevices ? View.GONE : View.VISIBLE);

        for (final Integer deviceId : orderedIds) {
            final boolean ownDevice = deviceId == ownDeviceId;
            final boolean announcedDevice = !ownDevice && announcedDeviceIds.contains(deviceId);
            final XmppAxolotlSession knownSession = knownOwnSessions.get(deviceId);
            final int titleRes =
                    ownDevice ? R.string.profile_this_device : R.string.profile_other_device;
            final View row =
                    getLayoutInflater().inflate(R.layout.item_profile_device, devices, false);
            final TextView title = row.findViewById(R.id.profile_device_title);
            final TextView keyStatus = row.findViewById(R.id.profile_device_status);

            title.setText(titleRes);
            final int initialStatus =
                    ownDevice
                            ? R.string.profile_device_status_this
                            : R.string.profile_device_status_loading;
            setDeviceStatus(keyStatus, initialStatus);
            row.setOnClickListener(
                    view -> showDeviceDetails(titleRes, deviceId, null, announcedDevice));
            devices.addView(row);

            if (!ownDevice && !announcedDevice && knownSession != null) {
                // A removed/expired device can disappear from the current PEP list while its
                // local identity history is intentionally retained. Surface that history as
                // inactive instead of claiming there are no other devices.
                setDeviceStatus(keyStatus, R.string.profile_device_status_inactive);
                final String fingerprint = knownSession.getFingerprint();
                row.setOnClickListener(
                        view ->
                                showDeviceDetails(
                                        titleRes, deviceId, fingerprint, true));
                continue;
            }

            axolotlService.fetchOwnDeviceFingerprint(
                    deviceId,
                    value ->
                            runOnUiThread(
                                    () -> {
                                        if (isFinishing() || isDestroyed()) {
                                            return;
                                        }

                                        if (value == null) {
                                            setDeviceStatus(
                                                    keyStatus,
                                                    R.string.profile_device_status_unavailable);
                                            row.setOnClickListener(
                                                    view ->
                                                            showDeviceDetails(
                                                                    titleRes,
                                                                    deviceId,
                                                                    null,
                                                                    announcedDevice));
                                            return;
                                        }

                                        int statusRes = R.string.profile_device_status_this;
                                        FingerprintStatus trust = null;
                                        if (!ownDevice) {
                                            trust = axolotlService.getFingerprintTrust(value);
                                            statusRes = getTrustStatus(trust);
                                        }
                                        setDeviceStatus(keyStatus, statusRes);

                                        final String fingerprint = value;
                                        row.setOnClickListener(
                                                view ->
                                                        showDeviceDetails(
                                                                titleRes,
                                                                deviceId,
                                                                fingerprint,
                                                                announcedDevice));

                                    }));
        }
    }

    private void setDeviceStatus(final TextView view, final int statusRes) {
        view.setText(statusRes);
        final int colorAttribute;
        if (statusRes == R.string.profile_device_status_needs_confirmation) {
            colorAttribute = com.google.android.material.R.attr.colorError;
        } else if (statusRes == R.string.profile_device_status_verified
                || statusRes == R.string.profile_device_status_this
                || statusRes == R.string.profile_device_status_active) {
            colorAttribute = com.google.android.material.R.attr.colorPrimary;
        } else {
            colorAttribute = com.google.android.material.R.attr.colorOnSurfaceVariant;
        }
        view.setTextColor(MaterialColors.getColor(view, colorAttribute));
    }

    private void showDeviceDetails(
            final int titleRes,
            final int deviceId,
            final String fingerprint,
            final boolean removable) {
        final String formattedFingerprint =
                fingerprint == null
                        ? getString(R.string.profile_device_fingerprint_unavailable)
                        : formatFingerprint(fingerprint);

        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(titleRes)
                        .setMessage(
                                getString(
                                        R.string.profile_device_details_message,
                                        deviceId,
                                        formattedFingerprint))
                        .setPositiveButton(android.R.string.ok, null);

        if (fingerprint != null) {
            builder.setNeutralButton(
                    R.string.copy_fingerprint,
                    (dialog, which) -> {
                        if (copyTextToClipboard(
                                formattedFingerprint, R.string.omemo_fingerprint)) {
                            Toast.makeText(
                                            this,
                                            R.string.toast_message_omemo_fingerprint,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
        }
        if (account != null
                && removable
                && deviceId != account.getAxolotlService().getOwnDeviceId()) {
            builder.setNegativeButton(
                    R.string.profile_device_remove,
                    (dialog, which) -> showRemoveDeviceDialog(deviceId));
        }
        builder.show();
    }

    private void showRemoveDeviceDialog(final int deviceId) {
        if (account == null
                || deviceId == account.getAxolotlService().getOwnDeviceId()) {
            return;
        }
        final Account targetAccount = account;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_device_remove_title)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setMessage(R.string.profile_device_remove_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.profile_device_remove,
                        (dialog, which) ->
                                targetAccount
                                        .getAxolotlService()
                                        .removeOwnPepDevice(
                                                deviceId,
                                                result ->
                                                        runOnUiThread(
                                                                () -> {
                                                                    if (isFinishing()
                                                                            || isDestroyed()) {
                                                                        return;
                                                                    }
                                                                    final int messageRes;
                                                                    switch (result) {
                                                                        case SUCCESS:
                                                                            messageRes =
                                                                                    R.string
                                                                                            .profile_device_remove_success;
                                                                            break;
                                                                        case PARTIAL:
                                                                            messageRes =
                                                                                    R.string
                                                                                            .profile_device_remove_partial;
                                                                            break;
                                                                        case FAILED:
                                                                        default:
                                                                            messageRes =
                                                                                    R.string
                                                                                            .profile_device_remove_failed;
                                                                            break;
                                                                    }
                                                                    if (result
                                                                            == AxolotlService
                                                                                    .OwnDeviceRemovalResult
                                                                                    .FAILED) {
                                                                        Toast.makeText(
                                                                                        this,
                                                                                        messageRes,
                                                                                        Toast.LENGTH_LONG)
                                                                                .show();
                                                                    } else {
                                                                        final Snackbar snackbar =
                                                                                Snackbar.make(
                                                                                        findViewById(
                                                                                                android.R.id
                                                                                                        .content),
                                                                                        messageRes,
                                                                                        Snackbar.LENGTH_LONG);
                                                                        if (canChangePassword(
                                                                                targetAccount)) {
                                                                            snackbar.setAction(
                                                                                    R.string
                                                                                            .change_password,
                                                                                    view ->
                                                                                            openChangePassword(
                                                                                                    targetAccount));
                                                                        }
                                                                        snackbar.show();
                                                                    }
                                                                    if (result
                                                                                    != AxolotlService
                                                                                            .OwnDeviceRemovalResult
                                                                                            .FAILED
                                                                            && account
                                                                                    == targetAccount) {
                                                                        refreshUi();
                                                                    }
                                                                })))
                .show();
    }

    @Override
    protected void processFingerprintVerification(final eu.siacs.conversations.utils.XmppUri uri) {
        if (account != null
                && account.getJid().asBareJid().equals(uri.getJid())
                && uri.hasFingerprints()) {
            final boolean changed =
                    xmppConnectionService.verifyFingerprints(account, uri.getFingerprints());
            Toast.makeText(
                            this,
                            changed
                                    ? R.string.verified_fingerprints
                                    : R.string.qr_verification_accepted,
                            Toast.LENGTH_SHORT)
                    .show();
            refreshUi();
        } else {
            Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show();
        }
    }

    private int getTrustStatus(final FingerprintStatus status) {
        if (status == null) {
            return R.string.profile_device_status_unavailable;
        }
        if (status.isVerified()) {
            return R.string.profile_device_status_verified;
        }
        if (status.isUnverified()) {
            return R.string.profile_device_status_needs_confirmation;
        }
        return status.isActive()
                ? R.string.profile_device_status_active
                : R.string.profile_device_status_inactive;
    }

    private void showResetOmemoDialog() {
        if (account == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.neocont_omemo_reset_confirm_title)
                .setMessage(R.string.neocont_omemo_reset_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.neocont_omemo_reset_confirm_action,
                        (dialog, which) -> {
                            account.getAxolotlService().regenerateKeys(true);
                            refreshUi();
                        })
                .show();
    }

    private boolean canChangePassword(final Account targetAccount) {
        return targetAccount != null
                && targetAccount.isOnlineAndConnected()
                && targetAccount.getXmppConnection() != null
                && targetAccount.getXmppConnection().getFeatures().register();
    }

    private void openChangePassword(final Account targetAccount) {
        if (!canChangePassword(targetAccount)) {
            return;
        }
        final android.content.Intent intent =
                new android.content.Intent(this, ChangePasswordActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, targetAccount.getJid().toString());
        startActivity(intent);
    }

    private void showClearDevicesDialog() {
        if (account == null || xmppConnectionService == null || !hasOtherDevices) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_devices_clear_list)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setMessage(R.string.clear_other_devices_desc)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.accept,
                        (dialog, which) -> account.getAxolotlService().wipeOtherPepDevices())
                .show();
    }

    private String formatFingerprint(final String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return "";
        }
        final String printable =
                fingerprint.startsWith("05") && fingerprint.length() > 2
                        ? fingerprint.substring(2)
                        : fingerprint;
        return CryptoHelper.prettifyFingerprint(printable);
    }
}
