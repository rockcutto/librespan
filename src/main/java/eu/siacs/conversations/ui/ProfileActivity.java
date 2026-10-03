package eu.siacs.conversations.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.PresenceTemplate;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.navigation.ProfileAccountResolution;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.widget.AccountIndicator;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.UIHelper;

public class ProfileActivity extends XmppActivity
        implements XmppConnectionService.OnAccountUpdate {

    private Account account;
    private View identityCard;
    private ImageView avatar;
    private AccountIndicator avatarAccountIndicator;
    private ImageView accountSwitch;
    private LinearLayout accountIndicator;
    private TextView name;
    private TextView jid;
    private TextView status;
    private TextView statusMessage;
    private View changePassword;
    private TextView changePasswordSummary;
    private GestureDetector accountSwipeDetector;
    private boolean accountSwitchAnimationRunning;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        identityCard = findViewById(R.id.profile_identity_card);
        avatar = findViewById(R.id.profile_avatar);
        avatarAccountIndicator = findViewById(R.id.profile_avatar_account_indicator);
        accountSwitch = findViewById(R.id.profile_account_switch);
        accountIndicator = findViewById(R.id.profile_account_indicator);
        name = findViewById(R.id.profile_name);
        jid = findViewById(R.id.profile_jid);
        status = findViewById(R.id.profile_status);
        statusMessage = findViewById(R.id.profile_status_message);
        changePassword = findViewById(R.id.profile_change_password);
        changePasswordSummary = findViewById(R.id.profile_change_password_summary);

        avatar.setOnClickListener(view -> openAvatarEditor());
        name.setOnClickListener(view -> editDisplayName());
        jid.setOnClickListener(view -> showAccountPicker());
        accountSwitch.setOnClickListener(view -> showAccountPicker());
        findViewById(R.id.profile_personal_status).setOnClickListener(view -> editStatusMessage());
        findViewById(R.id.profile_action_qr).setOnClickListener(view -> showAccountQrCode());
        findViewById(R.id.profile_action_invite).setOnClickListener(view -> startEasyInvite());
        findViewById(R.id.profile_security)
                .setOnClickListener(view -> openSettings("neocont_privacy"));
        findViewById(R.id.profile_devices)
                .setOnClickListener(
                        view -> {
                            if (account == null) {
                                return;
                            }
                            startActivity(
                                    ProfileNavigation.contextualDevicesIntent(
                                            this, account.getUuid()));
                        });
        changePassword.setOnClickListener(view -> openChangePassword());

        accountSwipeDetector =
                new GestureDetector(
                        this,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(final MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onFling(
                                    final MotionEvent start,
                                    final MotionEvent end,
                                    final float velocityX,
                                    final float velocityY) {
                                if (start == null
                                        || end == null
                                        || !isInsideIdentityCard(start)
                                        || accountSwitchAnimationRunning) {
                                    return false;
                                }

                                final float dx = end.getRawX() - start.getRawX();
                                final float dy = end.getRawY() - start.getRawY();
                                if (Math.abs(dx) < dp(72)
                                        || Math.abs(dx) < Math.abs(dy) * 1.35f
                                        || Math.abs(velocityX) < dp(300)) {
                                    return false;
                                }

                                switchAccountRelative(dx < 0 ? 1 : -1);
                                return false;
                            }
                        });
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        if (accountSwipeDetector != null) {
            accountSwipeDetector.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
            return;
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
    protected void onBackendConnected() {
        resolveAccountAndRefresh();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (xmppConnectionService != null) {
            resolveAccountAndRefresh();
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
                ProfileNavigation.resolveAccount(this, xmppConnectionService, getIntent());
        if (resolution instanceof ProfileAccountResolution.Resolved) {
            account = ((ProfileAccountResolution.Resolved) resolution).getAccount();
            ProfileNavigation.rememberProfileAccount(this, account.getUuid());
            refreshUiReal();
            return;
        }
        account = null;
        if (resolution instanceof ProfileAccountResolution.MissingContextualAccount) {
            finish();
            return;
        }
        AccountUtils.launchManageAccounts(this);
        finish();
    }

    @Override
    protected void refreshUiReal() {
        if (account == null || xmppConnectionService == null) {
            return;
        }

        final String displayName = account.getDisplayName();
        name.setText(
                TextUtils.isEmpty(displayName)
                        ? getString(R.string.profile_name_not_set)
                        : displayName);
        jid.setText(account.getJid().asBareJid().toString());

        final boolean connected = account.getStatus() == Account.State.ONLINE;
        status.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
        if (connected) {
            status.setText(getPresenceLabel(account.getPresenceStatus()));
        }

        final String personalStatus = account.getPresenceStatusMessage();
        statusMessage.setText(
                TextUtils.isEmpty(personalStatus)
                        ? getString(R.string.profile_status_not_set)
                        : personalStatus);

        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        final boolean multiAccount = accounts.size() > 1;
        accountSwitch.setVisibility(multiAccount ? View.VISIBLE : View.GONE);
        jid.setClickable(multiAccount);
        jid.setFocusable(multiAccount);
        avatarAccountIndicator.setCircleColor(
                multiAccount
                        ? UIHelper.getAccountColor(this, account.getJid())
                        : Color.TRANSPARENT);
        renderAccountIndicator(accounts);
        updateChangePasswordState();

        final int avatarSize =
                getResources().getDimensionPixelSize(R.dimen.publish_avatar_size);
        avatar.setImageBitmap(xmppConnectionService.getAvatarService().get(account, avatarSize));
    }

    private void renderAccountIndicator(final List<Account> accounts) {
        accountIndicator.removeAllViews();
        if (accounts.size() <= 1 || account == null) {
            accountIndicator.setVisibility(View.GONE);
            return;
        }

        accountIndicator.setVisibility(View.VISIBLE);
        for (final Account candidate : accounts) {
            final boolean selected = candidate.getUuid().equals(account.getUuid());
            final View marker = new View(this);
            final int height = dp(7);
            final int width = selected ? dp(18) : height;
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
            params.setMarginStart(dp(3));
            params.setMarginEnd(dp(3));
            marker.setLayoutParams(params);

            final GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(dp(4));
            shape.setColor(
                    ColorUtils.setAlphaComponent(
                            UIHelper.getAccountColor(this, candidate.getJid()),
                            selected ? 255 : 105));
            marker.setBackground(shape);
            accountIndicator.addView(marker);
        }
    }

    private void showAccountPicker() {
        if (xmppConnectionService == null) {
            return;
        }
        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        if (accounts.size() <= 1) {
            return;
        }

        final AdaptiveBottomSheet.Sheet sheet =
                AdaptiveBottomSheet.create(this, R.string.profile_accounts_picker_title);
        final int currentIndex = indexOfAccount(accounts, account);

        AccountChoiceBottomSheet.addAccountGroup(
                this,
                xmppConnectionService,
                sheet,
                accounts,
                account,
                candidate -> {
                    sheet.dismiss();
                    if (account != null
                            && candidate.getUuid().equals(account.getUuid())) {
                        return;
                    }
                    final int targetIndex = indexOfAccount(accounts, candidate);
                    final int direction =
                            currentIndex >= 0 && targetIndex >= 0 && targetIndex < currentIndex
                                    ? -1
                                    : 1;
                    switchProfileAccount(candidate, direction);
                });

        addProfileChatScope(sheet);
        sheet.addAction(
                R.drawable.ic_accounts_selected_24,
                R.string.profile_manage_accounts_title,
                () -> AccountUtils.launchManageAccounts(this));
        sheet.show();
    }

    private void addProfileChatScope(final AdaptiveBottomSheet.Sheet sheet) {
        final TextView section = new TextView(this);
        section.setText(R.string.profile_chat_scope_title);
        section.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        section.setTextColor(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        final LinearLayout.LayoutParams sectionParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionParams.setMargins(dp(8), dp(16), dp(8), dp(8));
        sheet.getContent().addView(section, sectionParams);

        final int allId = View.generateViewId();
        final int currentId = View.generateViewId();
        final MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);

        final MaterialButton all =
                createProfileChatScopeButton(allId, R.string.profile_chat_scope_all);
        final MaterialButton current =
                createProfileChatScopeButton(currentId, R.string.profile_chat_scope_current);
        group.addView(all, new LinearLayout.LayoutParams(0, dp(44), 1f));
        group.addView(current, new LinearLayout.LayoutParams(0, dp(44), 1f));

        final boolean scopedToProfile =
                ProfileNavigation.isConversationListScopedToProfileAccount(this);
        group.check(scopedToProfile ? currentId : allId);
        group.addOnButtonCheckedListener(
                (toggleGroup, checkedId, isChecked) -> {
                    if (!isChecked) {
                        return;
                    }
                    ProfileNavigation.setConversationListScopedToProfileAccount(
                            this, checkedId == currentId);
                });

        final LinearLayout.LayoutParams groupParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        groupParams.setMargins(dp(4), 0, dp(4), dp(10));
        sheet.getContent().addView(group, groupParams);
    }

    private MaterialButton createProfileChatScopeButton(
            final int id, final int labelRes) {
        final MaterialButton button = new MaterialButton(this);
        button.setId(id);
        button.setText(labelRes);
        button.setAllCaps(false);
        button.setCheckable(true);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(44));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(16));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);

        final int primaryContainer =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimaryContainer);
        final int surface =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorSurfaceContainerLow);
        final int onPrimaryContainer =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnPrimaryContainer);
        final int onSurfaceVariant =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant);
        final int outline =
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOutlineVariant);

        final int[][] states = {
            new int[] {android.R.attr.state_checked},
            new int[] {}
        };
        button.setBackgroundTintList(
                new ColorStateList(
                        states,
                        new int[] {
                            primaryContainer,
                            ColorUtils.blendARGB(surface, Color.TRANSPARENT, 0f)
                        }));
        button.setTextColor(
                new ColorStateList(
                        states,
                        new int[] {onPrimaryContainer, onSurfaceVariant}));
        button.setStrokeColor(ColorStateList.valueOf(outline));
        button.setStrokeWidth(dp(1));
        return button;
    }

    private void switchAccountRelative(final int direction) {
        if (xmppConnectionService == null || account == null) {
            return;
        }
        final List<Account> accounts = new ArrayList<>(xmppConnectionService.getAccounts());
        if (accounts.size() <= 1) {
            return;
        }
        final int currentIndex = indexOfAccount(accounts, account);
        if (currentIndex < 0) {
            return;
        }
        final int targetIndex = currentIndex + direction;
        if (targetIndex < 0 || targetIndex >= accounts.size()) {
            return;
        }
        switchProfileAccount(accounts.get(targetIndex), direction);
    }

    private void switchProfileAccount(final Account target, final int direction) {
        if (target == null
                || account == null
                || target.getUuid().equals(account.getUuid())
                || accountSwitchAnimationRunning) {
            return;
        }

        accountSwitchAnimationRunning = true;
        identityCard.animate().cancel();
        final float outTranslation = (direction >= 0 ? -1f : 1f) * dp(28);
        identityCard
                .animate()
                .translationX(outTranslation)
                .alpha(0.35f)
                .setDuration(90L)
                .withEndAction(
                        () -> {
                            account = target;
                            setIntent(
                                    ProfileNavigation.contextualProfileIntent(
                                            this, target.getUuid()));
                            ProfileNavigation.rememberProfileAccount(this, target.getUuid());
                            refreshUiReal();

                            identityCard.setTranslationX(-outTranslation);
                            identityCard.setAlpha(0.35f);
                            identityCard
                                    .animate()
                                    .translationX(0f)
                                    .alpha(1f)
                                    .setDuration(130L)
                                    .withEndAction(
                                            () -> accountSwitchAnimationRunning = false)
                                    .start();
                        })
                .start();
    }

    private int indexOfAccount(final List<Account> accounts, final Account target) {
        if (target == null) {
            return -1;
        }
        for (int i = 0; i < accounts.size(); ++i) {
            if (accounts.get(i).getUuid().equals(target.getUuid())) {
                return i;
            }
        }
        return -1;
    }

    private boolean isInsideIdentityCard(final MotionEvent event) {
        if (identityCard == null) {
            return false;
        }
        final Rect bounds = new Rect();
        return identityCard.getGlobalVisibleRect(bounds)
                && bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean canChangePassword() {
        return account != null
                && account.isOnlineAndConnected()
                && account.getXmppConnection() != null
                && account.getXmppConnection().getFeatures().register();
    }

    private void updateChangePasswordState() {
        if (changePassword == null || changePasswordSummary == null || account == null) {
            return;
        }
        final boolean available = canChangePassword();
        changePassword.setEnabled(available);
        changePassword.setClickable(available);
        changePassword.setAlpha(available ? 1f : 0.55f);
        if (!account.isOnlineAndConnected() || account.getXmppConnection() == null) {
            changePasswordSummary.setText(R.string.profile_change_password_offline_summary);
        } else if (!account.getXmppConnection().getFeatures().register()) {
            changePasswordSummary.setText(R.string.profile_change_password_unsupported_summary);
        } else {
            changePasswordSummary.setText(R.string.profile_change_password_summary);
        }
    }

    private void openChangePassword() {
        if (!canChangePassword()) {
            return;
        }
        final Intent intent = new Intent(this, ChangePasswordActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toString());
        startActivity(intent);
    }

    private void openAvatarEditor() {
        if (account == null) {
            return;
        }
        final Intent intent = new Intent(this, AvatarCropEditorActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        startActivity(intent);
    }

    private void showAccountQrCode() {
        if (account != null) {
            showQrCode(account.getShareableUri());
        }
    }

    private void editDisplayName() {
        if (account == null || xmppConnectionService == null) {
            return;
        }
        final Account currentAccount = account;
        quickEdit(
                currentAccount.getDisplayName(),
                R.string.your_name,
                value -> {
                    currentAccount.setDisplayName(value.trim());
                    xmppConnectionService.publishDisplayName(currentAccount);
                    refreshUi();
                    return null;
                },
                true);
    }

    private void editStatusMessage() {
        if (account == null || xmppConnectionService == null) {
            return;
        }
        final Account currentAccount = account;
        final String current = currentAccount.getPresenceStatusMessage();
        quickEdit(
                current == null ? "" : current,
                R.string.profile_status_title,
                value -> {
                    final PresenceTemplate template =
                            new PresenceTemplate(Presence.Status.ONLINE, value.trim());
                    xmppConnectionService.changeStatus(currentAccount, template, null);
                    refreshUi();
                    return null;
                },
                true);
    }

    private void startEasyInvite() {
        if (account == null || xmppConnectionService == null || !account.isEnabled()) {
            Toast.makeText(this, R.string.no_active_accounts_support_this, Toast.LENGTH_LONG).show();
            return;
        }
        EasyOnboardingInviteActivity.launch(account, this);
    }

    private int getPresenceLabel(final Presence.Status presence) {
        if (presence == null) {
            return R.string.presence_online;
        }
        switch (presence) {
            case CHAT:
                return R.string.presence_chat;
            case AWAY:
                return R.string.presence_away;
            case XA:
                return R.string.presence_xa;
            case DND:
                return R.string.presence_dnd;
            case OFFLINE:
                return R.string.account_status_offline;
            case ONLINE:
            default:
                return R.string.presence_online;
        }
    }

    private void openSettings(final String page) {
        final Intent intent = new Intent(this, SettingsActivity.class);
        if (!TextUtils.isEmpty(page)) {
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra(SettingsFragment.EXTRA_PAGE, page);
        }
        startActivity(intent);
    }
}
