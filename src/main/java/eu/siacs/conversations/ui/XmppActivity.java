package eu.siacs.conversations.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Html;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.BoolRes;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.ImmutableSet;

import net.java.otr4j.session.SessionID;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogAddReactionBinding;
import eu.siacs.conversations.databinding.DialogQuickeditBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Presences;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.services.BarcodeProvider;
import eu.siacs.conversations.services.EmojiInitializationService;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.XmppConnectionBinder;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaThumbnailReader;
import eu.siacs.conversations.ui.appearance.ChatWallpaperPresets;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.PresenceSelector;
import eu.siacs.conversations.ui.util.SettingsUtils;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.utils.SignupUtils;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;

public abstract class XmppActivity extends ActionBarActivity {

    public static final String EXTRA_ACCOUNT = "account";
    protected static final int REQUEST_INVITE_TO_CONVERSATION = 0x0102;
    protected static final int REQUEST_BATTERY_OP = 0x49ff;
    protected static final int REQUEST_POST_NOTIFICATION = 0x50ff;
    public XmppConnectionService xmppConnectionService;
    public boolean xmppConnectionServiceBound = false;

    protected static final String FRAGMENT_TAG_DIALOG = "dialog";

    private boolean isCameraFeatureAvailable = false;

    protected int mTheme;
    protected Integer mThemeOverrideStyle;
    protected boolean mUsingEnterKey = false;
    protected boolean mUseTor = false;
    protected Toast mToast;
    protected ConferenceInvite mPendingConferenceInvite = null;
    protected ServiceConnection mConnection =
            new ServiceConnection() {

                @Override
                public void onServiceConnected(ComponentName className, IBinder service) {
                    XmppConnectionBinder binder = (XmppConnectionBinder) service;
                    xmppConnectionService = binder.getService();
                    xmppConnectionServiceBound = true;
                    registerListeners();
                    onBackendConnected();
                }

                @Override
                public void onServiceDisconnected(ComponentName arg0) {
                    xmppConnectionServiceBound = false;
                }
            };
    private DisplayMetrics metrics;
    private long mLastUiRefresh = 0;
    private final Handler mRefreshUiHandler = new Handler();
    private final Runnable mRefreshUiRunnable =
            () -> {
                mLastUiRefresh = SystemClock.elapsedRealtime();
                refreshUiReal();
            };
    private final UiCallback<Conversation> adhocCallback =
            new UiCallback<Conversation>() {
                @Override
                public void success(final Conversation conversation) {
                    runOnUiThread(
                            () -> {
                                switchToConversation(conversation);
                                hideToast();
                            });
                }

                @Override
                public void error(final int errorCode, Conversation object) {
                    runOnUiThread(() -> replaceToast(getString(errorCode)));
                }

                @Override
                public void userInputRequired(PendingIntent pi, Conversation object) {}
            };

    public static boolean cancelPotentialWork(Message message, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Message oldMessage = bitmapWorkerTask.message;
            if (oldMessage == null || message != oldMessage) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    protected void hideToast() {
        final var toast = this.mToast;
        if (toast == null) {
            return;
        }
        toast.cancel();
    }

    protected void replaceToast(String msg) {
        replaceToast(msg, true);
    }

    protected void replaceToast(String msg, boolean showlong) {
        hideToast();
        mToast = Toast.makeText(this, msg, showlong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mToast.show();
    }

    protected final void refreshUi() {
        final long diff = SystemClock.elapsedRealtime() - mLastUiRefresh;
        if (diff > Config.REFRESH_UI_INTERVAL) {
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
            runOnUiThread(mRefreshUiRunnable);
        } else {
            final long next = Config.REFRESH_UI_INTERVAL - diff;
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
            mRefreshUiHandler.postDelayed(mRefreshUiRunnable, next);
        }
    }

    protected abstract void refreshUiReal();

    @Override
    public void onStart() {
        super.onStart();
        if (!xmppConnectionServiceBound) {
            connectToBackend();
        } else {
            this.registerListeners();
            this.onBackendConnected();
        }
        this.mUsingEnterKey = usingEnterKey();
        this.mUseTor = useTor();

        if (!Objects.equals(mThemeOverrideStyle, ThemeHelper.findThemeOverrideStyle(this))) {
            ChatWallpaperPresets.resetProcessPrewarm();
            recreate();
            return;
        }

        if (!Objects.equals(mTheme, ThemeHelper.find(this))) {
            ChatWallpaperPresets.resetProcessPrewarm();
            recreate();
        }
    }

    public void connectToBackend() {
        Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction("ui");
        try {
            startService(intent);
        } catch (IllegalStateException e) {
            Log.w(Config.LOGTAG, "unable to start service from " + getClass().getSimpleName());
        }
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (xmppConnectionServiceBound) {
            this.unregisterListeners();
            unbindService(mConnection);
            xmppConnectionServiceBound = false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    protected void configureCustomNotification(final ShortcutInfoCompat shortcut) {
        final var notificationManager = getSystemService(NotificationManager.class);
        final var channel =
                notificationManager.getNotificationChannel(
                        NotificationService.MESSAGES_NOTIFICATION_CHANNEL, shortcut.getId());
        if (channel != null && channel.getConversationId() != null) {
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut);
            openNotificationSettings(shortcut);
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.custom_notifications)
                    .setMessage(R.string.custom_notifications_enable)
                    .setPositiveButton(
                            R.string.continue_btn,
                            (d, w) -> {
                                NotificationService.createConversationChannel(this, shortcut);
                                ShortcutManagerCompat.pushDynamicShortcut(this, shortcut);
                                openNotificationSettings(shortcut);
                            })
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                    .show();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    protected void openNotificationSettings(final ShortcutInfoCompat shortcut) {
        final var intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        intent.putExtra(
                Settings.EXTRA_CHANNEL_ID, NotificationService.MESSAGES_NOTIFICATION_CHANNEL);
        intent.putExtra(Settings.EXTRA_CONVERSATION_ID, shortcut.getId());
        startActivity(intent);
    }

    public void addReaction(final Message message, Consumer<Collection<String>> callback) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        final var layoutInflater = this.getLayoutInflater();
        final DialogAddReactionBinding viewBinding =
                DataBindingUtil.inflate(layoutInflater, R.layout.dialog_add_reaction, null, false);
        builder.setView(viewBinding.getRoot());
        final var dialog = builder.create();
        for (final String emoji : Reaction.SUGGESTIONS) {
            final Button button =
                    (Button)
                            layoutInflater.inflate(
                                    R.layout.item_emoji_button, viewBinding.emojis, false);
            viewBinding.emojis.addView(button);
            button.setText(emoji);
            button.setOnClickListener(
                    v -> {
                        final var aggregated = message.getAggregatedReactions();
                        if (aggregated.ourReactions.contains(emoji)) {
                            callback.accept(aggregated.ourReactions);
                        } else {
                            final ImmutableSet.Builder<String> reactionBuilder =
                                    new ImmutableSet.Builder<>();
                            reactionBuilder.addAll(aggregated.ourReactions);
                            reactionBuilder.add(emoji);
                            callback.accept(reactionBuilder.build());
                        }
                        dialog.dismiss();
                    });
        }
        viewBinding.more.setOnClickListener(
                v -> {
                    dialog.dismiss();
                    final var intent = new Intent(this, AddReactionActivity.class);
                    intent.putExtra("conversation", message.getConversation().getUuid());
                    intent.putExtra("message", message.getUuid());
                    startActivity(intent);
                });
        dialog.show();
    }

    protected void deleteAccount(final Account account) {
        this.deleteAccount(account, null);
    }

    protected void deleteAccount(final Account account, final Runnable postDelete) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        final View dialogView = getLayoutInflater().inflate(R.layout.dialog_delete_account, null);
        final CheckBox deleteFromServer = dialogView.findViewById(R.id.delete_from_server);
        final boolean canDeleteFromServer =
                account != null
                        && account.isOnlineAndConnected()
                        && account.getXmppConnection() != null
                        && account.getXmppConnection().getFeatures().register();
        deleteFromServer.setVisibility(canDeleteFromServer ? View.VISIBLE : View.GONE);
        deleteFromServer.setChecked(false);
        builder.setView(dialogView);
        builder.setTitle(R.string.manage_account_remove_device_title);
        builder.setPositiveButton(getString(R.string.delete), null);
        builder.setNegativeButton(getString(R.string.cancel), null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(
                dialogInterface -> {
                    final Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    button.setOnClickListener(
                            v -> {
                                final boolean unregister = deleteFromServer.isChecked();
                                deleteFromServer.setEnabled(false);
                                button.setText(R.string.please_wait);
                                button.setEnabled(false);
                                if (unregister) {
                                    xmppConnectionService.unregisterAccountForRemoval(
                                            account,
                                            result ->
                                                    runOnUiThread(
                                                            () -> {
                                                                if (result
                                                                        == XmppConnectionService
                                                                                .ServerAccountRemovalResult
                                                                                .SUCCESS) {
                                                                    completeAccountRemoval(
                                                                            dialog, postDelete);
                                                                    return;
                                                                }
                                                                resetAccountRemovalDialog(
                                                                        deleteFromServer, button);
                                                                final int message =
                                                                        result
                                                                                        == XmppConnectionService
                                                                                                .ServerAccountRemovalResult
                                                                                                .SERVER_FAILED
                                                                                ? R.string
                                                                                        .could_not_delete_account_from_server
                                                                                : R.string
                                                                                        .manage_account_remove_local_after_server_failed;
                                                                Toast.makeText(
                                                                                this,
                                                                                message,
                                                                                Toast.LENGTH_LONG)
                                                                        .show();
                                                            }));
                                } else {
                                    xmppConnectionService.deleteAccount(
                                            account,
                                            result ->
                                                    runOnUiThread(
                                                            () -> {
                                                                if (result
                                                                        == XmppConnectionService
                                                                                .AccountRemovalResult
                                                                                .SUCCESS) {
                                                                    completeAccountRemoval(
                                                                            dialog, postDelete);
                                                                } else {
                                                                    resetAccountRemovalDialog(
                                                                            deleteFromServer,
                                                                            button);
                                                                    Toast.makeText(
                                                                                    this,
                                                                                    R.string
                                                                                            .manage_account_remove_failed,
                                                                                    Toast.LENGTH_LONG)
                                                                            .show();
                                                                }
                                                            }));
                                }
                            });
                });
        dialog.show();
    }

    private void resetAccountRemovalDialog(
            final CheckBox deleteFromServer, final Button button) {
        deleteFromServer.setEnabled(true);
        button.setText(R.string.delete);
        button.setEnabled(true);
    }

    private void completeAccountRemoval(
            final AlertDialog dialog, final Runnable postDelete) {
        dialog.dismiss();
        if (postDelete != null) {
            postDelete.run();
        }
        if (xmppConnectionService.getAccounts().size() == 0
                && Config.MAGIC_CREATE_DOMAIN != null) {
            final Intent intent = SignupUtils.getSignUpIntent(this);
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
    }

    protected abstract void onBackendConnected();

    protected void registerListeners() {
        if (this instanceof XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.setOnConversationListChangedListener(
                    (XmppConnectionService.OnConversationUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.setOnAccountListChangedListener(
                    (XmppConnectionService.OnAccountUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.setOnCaptchaRequestedListener(
                    (XmppConnectionService.OnCaptchaRequested) this);
        }
        if (this instanceof XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.setOnRosterUpdateListener(
                    (XmppConnectionService.OnRosterUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.setOnMucRosterUpdateListener(
                    (XmppConnectionService.OnMucRosterUpdate) this);
        }
        if (this instanceof OnUpdateBlocklist) {
            this.xmppConnectionService.setOnUpdateBlocklistListener((OnUpdateBlocklist) this);
        }
        if (this instanceof XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.setOnShowErrorToastListener(
                    (XmppConnectionService.OnShowErrorToast) this);
        }
        if (this instanceof OnKeyStatusUpdated) {
            this.xmppConnectionService.setOnKeyStatusUpdatedListener((OnKeyStatusUpdated) this);
        }
        if (this instanceof XmppConnectionService.OnJingleRtpConnectionUpdate) {
            this.xmppConnectionService.setOnRtpConnectionUpdateListener(
                    (XmppConnectionService.OnJingleRtpConnectionUpdate) this);
        }
    }

    protected void unregisterListeners() {
        if (this instanceof XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.removeOnConversationListChangedListener(
                    (XmppConnectionService.OnConversationUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.removeOnAccountListChangedListener(
                    (XmppConnectionService.OnAccountUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.removeOnCaptchaRequestedListener(
                    (XmppConnectionService.OnCaptchaRequested) this);
        }
        if (this instanceof XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.removeOnRosterUpdateListener(
                    (XmppConnectionService.OnRosterUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.removeOnMucRosterUpdateListener(
                    (XmppConnectionService.OnMucRosterUpdate) this);
        }
        if (this instanceof OnUpdateBlocklist) {
            this.xmppConnectionService.removeOnUpdateBlocklistListener((OnUpdateBlocklist) this);
        }
        if (this instanceof XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.removeOnShowErrorToastListener(
                    (XmppConnectionService.OnShowErrorToast) this);
        }
        if (this instanceof OnKeyStatusUpdated) {
            this.xmppConnectionService.removeOnNewKeysAvailableListener((OnKeyStatusUpdated) this);
        }
        if (this instanceof XmppConnectionService.OnJingleRtpConnectionUpdate) {
            this.xmppConnectionService.removeRtpConnectionUpdateListener(
                    (XmppConnectionService.OnJingleRtpConnectionUpdate) this);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, eu.siacs.conversations.ui.SettingsActivity.class));
                break;
            case R.id.action_accounts:
                AccountUtils.launchManageAccounts(this);
                break;
            case android.R.id.home:
                finish();
                break;
            case R.id.action_show_qr_code:
                showQrCode();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    public void selectPresence(
            final Conversation conversation, final PresenceSelector.OnPresenceSelected listener) {
        final Contact contact = conversation.getContact();

        if (conversation.hasValidOtrSession()) {
            SessionID id = conversation.getOtrSession().getSessionID();
            Jid jid;
            try {
                jid = Jid.of(id.getAccountID() + "/" + id.getUserID());
            } catch (IllegalArgumentException e) {
                jid = null;
            }
            conversation.setNextCounterpart(jid);
            listener.onPresenceSelected();
        } else if (contact.showInRoster() || contact.isSelf()) {
            final Presences presences = contact.getPresences();
            if (presences.size() == 0) {
                if (contact.isSelf()) {
                    conversation.setNextCounterpart(null);
                    listener.onPresenceSelected();
                } else if (!contact.getOption(Contact.Options.TO)
                        && !contact.getOption(Contact.Options.ASKING)
                        && contact.getAccount().getStatus() == Account.State.ONLINE) {
                    showAskForPresenceDialog(contact);
                } else if (!contact.getOption(Contact.Options.TO)
                        || !contact.getOption(Contact.Options.FROM)) {
                    PresenceSelector.warnMutualPresenceSubscription(this, conversation, listener);
                } else {
                    conversation.setNextCounterpart(null);
                    listener.onPresenceSelected();
                }
            } else if (presences.size() == 1) {
                final String presence = presences.toResourceArray()[0];
                conversation.setNextCounterpart(
                        PresenceSelector.getNextCounterpart(contact, presence));
                listener.onPresenceSelected();
            } else {
                PresenceSelector.showPresenceSelectionDialog(this, conversation, listener);
            }
        } else {
            showAddToRosterDialog(conversation.getContact());
        }
    }

    @SuppressLint("UnsupportedChromeOsCameraSystemFeature")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Resolve the final app theme before AppCompat restores/inflates any view hierarchy.
        // Otherwise restored RecyclerView rows can get one frame of manifest/default typography
        // before the LibreSpan theme (including the bundled Onest family) is applied.
        this.mTheme = findTheme();
        setTheme(this.mTheme);
        Integer override = ThemeHelper.findThemeOverrideStyle(this);
        if (override != null) {
            getTheme().applyStyle(override, true);
            mThemeOverrideStyle = override;
        }

        super.onCreate(savedInstanceState);
        metrics = getResources().getDisplayMetrics();
        ExceptionHelper.init(getApplicationContext());
        EmojiInitializationService.execute(this);
        this.isCameraFeatureAvailable =
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        ThemeHelper.applyMaterialColors(this);
        // Pay the full-screen anti-banding cost once, after the effective theme/accent exists and
        // before any chat can be opened. ConversationFragment only consumes this cached raster.
        ChatWallpaperPresets.prewarmOncePerProcess(this);
        this.isCameraFeatureAvailable =
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    protected boolean isCameraFeatureAvailable() {
        return this.isCameraFeatureAvailable;
    }

    public boolean isDarkTheme() {
        return ThemeHelper.isDark(mTheme);
    }

    public int getThemeResource(int r_attr_name, int r_drawable_def) {
        int[] attrs = {r_attr_name};
        TypedArray ta = this.getTheme().obtainStyledAttributes(attrs);
        int res = ta.getResourceId(0, r_drawable_def);
        ta.recycle();
        return res;
    }

    protected boolean isOptimizingBattery() {
        final PowerManager pm = getSystemService(PowerManager.class);
        return !pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    protected boolean isAffectedByDataSaver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null
                    && cm.isActiveNetworkMetered()
                    && Compatibility.getRestrictBackgroundStatus(cm)
                            == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
        } else {
            return false;
        }
    }

    private boolean usingEnterKey() {
        return getBooleanPreference("display_enter_key", R.bool.display_enter_key);
    }

    private boolean useTor() {
        return QuickConversationsService.isConversations()
                && getBooleanPreference("use_tor", R.bool.use_tor);
    }

    protected SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    protected boolean getBooleanPreference(String name, @BoolRes int res) {
        return getPreferences().getBoolean(name, getResources().getBoolean(res));
    }

    public void startCommand(final Account account, final Jid jid, final String node) {
        Intent intent = new Intent(this, ConversationsActivity.class);
        intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        intent.putExtra(
                ConversationsActivity.EXTRA_CONVERSATION,
                xmppConnectionService
                        .findOrCreateConversation(account, jid, null, false, false, false, null)
                        .getUuid());
        intent.putExtra(ConversationsActivity.EXTRA_POST_INIT_ACTION, "command");
        intent.putExtra(ConversationsActivity.EXTRA_NODE, node);
        intent.putExtra(ConversationsActivity.EXTRA_JID, (CharSequence) jid);
        intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    public void switchToConversation(Conversation conversation) {
        switchToConversation(conversation, null);
    }

    public void switchToConversationOnMessage(Conversation conversation, String messageUuid) {
        switchToConversation(conversation, null, false, null, false, false, null, messageUuid);
    }

    public void switchToConversationAndQuote(Conversation conversation, String text) {
        switchToConversation(conversation, text, true, null, false, false);
    }

    public void switchToConversation(Conversation conversation, String text) {
        switchToConversation(conversation, text, false, null, false, false);
    }

    public void switchToConversationDoNotAppend(Conversation conversation, String text) {
        switchToConversation(conversation, text, false, null, false, true);
    }

    protected void switchToConversationDoNotAppend(Contact contact, String body, String postInit) {
        Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), null, false, false, true, null);
        switchToConversation(conversation, body, false, null, false, true, postInit, null);
    }

    public void highlightInMuc(Conversation conversation, String nick) {
        switchToConversation(conversation, null, false, nick, false, false);
    }

    public void privateMsgInMuc(Conversation conversation, String nick) {
        Conversation c =
                xmppConnectionService.findOrCreateConversation(
                        conversation.getAccount(),
                        conversation.getJid(),
                        null,
                        true,
                        true,
                        false,
                        conversation.getJid().withResource(nick));
        switchToConversation(c, null, false, nick, true, false);
    }

    public void switchToConversation(
            Conversation conversation,
            String text,
            boolean asQuote,
            String nick,
            boolean pm,
            boolean doNotAppend) {
        switchToConversation(conversation, text, asQuote, nick, pm, doNotAppend, null, null);
    }

    public void switchToConversation(
            Conversation conversation,
            String text,
            boolean asQuote,
            String nick,
            boolean pm,
            boolean doNotAppend,
            String postInit,
            String messageUuid) {
        if (conversation == null) return;
        Intent intent = new Intent(this, ConversationsActivity.class);
        intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        if (text != null) {
            intent.putExtra(Intent.EXTRA_TEXT, text);
            if (asQuote) {
                intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, true);
            }
        }
        if (nick != null) {
            intent.putExtra(ConversationsActivity.EXTRA_NICK, nick);
            intent.putExtra(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, pm);
        }
        if (doNotAppend) {
            intent.putExtra(ConversationsActivity.EXTRA_DO_NOT_APPEND, true);
        }
        if (messageUuid != null) {
            intent.putExtra(ConversationsActivity.EXTRA_MESSAGE_UUID, messageUuid);
        }
        intent.putExtra(ConversationsActivity.EXTRA_POST_INIT_ACTION, postInit);
        intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    public void switchToContactDetails(Contact contact) {
        switchToContactDetails(contact, null);
    }

    public void switchToContactDetails(Contact contact, String messageFingerprint) {
        Intent intent = new Intent(this, ContactDetailsActivity.class);
        intent.setAction(ContactDetailsActivity.ACTION_VIEW_CONTACT);
        intent.putExtra(EXTRA_ACCOUNT, contact.getAccount().getJid().asBareJid().toString());
        intent.putExtra("contact", contact.getJid().toString());
        intent.putExtra("fingerprint", messageFingerprint);
        startActivity(intent);
    }

    public void switchToAccount(Account account, String fingerprint) {
        switchToAccount(account, false, fingerprint);
    }

    public void switchToAccount(Account account) {
        switchToAccount(account, false, null);
    }

    public void switchToAccount(Account account, boolean init, String fingerprint) {
        Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra("jid", account.getJid().asBareJid().toString());
        intent.putExtra("init", init);
        if (init) {
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        if (fingerprint != null) {
            intent.putExtra("fingerprint", fingerprint);
        }
        startActivity(intent);
        if (init) {
            overridePendingTransition(0, 0);
        }
    }

    protected void delegateUriPermissionsToService(Uri uri) {
        Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startService(intent);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "unable to delegate uri permission", e);
        }
    }

    protected void inviteToConversation(Conversation conversation) {
        startActivityForResult(
                ChooseContactActivity.create(this, conversation), REQUEST_INVITE_TO_CONVERSATION);
    }

    protected void displayErrorDialog(final int errorCode) {
        runOnUiThread(
                () -> {
                    final MaterialAlertDialogBuilder builder =
                            new MaterialAlertDialogBuilder(XmppActivity.this);
                    builder.setTitle(getString(R.string.error));
                    builder.setMessage(errorCode);
                    builder.setNeutralButton(R.string.accept, null);
                    builder.create().show();
                });
    }

    protected void showAddToRosterDialog(final Contact contact) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(contact.getJid().toString());
        builder.setMessage(getString(R.string.not_in_roster));
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(
                getString(R.string.add_contact),
                (dialog, which) -> xmppConnectionService.createContact(contact, true));
        builder.create().show();
    }

    private void showAskForPresenceDialog(final Contact contact) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(contact.getJid().toString());
        builder.setMessage(R.string.request_presence_updates);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(
                R.string.request_now,
                (dialog, which) -> {
                    if (xmppConnectionServiceBound) {
                        xmppConnectionService.sendPresencePacket(
                                contact.getAccount(),
                                xmppConnectionService
                                        .getPresenceGenerator()
                                        .requestPresenceUpdatesFrom(contact));
                    }
                });
        builder.create().show();
    }

    protected void quickEdit(String previousValue, @StringRes int hint, OnValueEdited callback) {
        quickEdit(previousValue, callback, hint, false, false);
    }

    protected void quickEdit(
            String previousValue,
            @StringRes int hint,
            OnValueEdited callback,
            boolean permitEmpty) {
        quickEdit(previousValue, callback, hint, false, permitEmpty);
    }

    protected void quickPasswordEdit(String previousValue, OnValueEdited callback) {
        quickEdit(previousValue, callback, R.string.password, true, false);
    }

    @SuppressLint("InflateParams")
    private void quickEdit(
            final String previousValue,
            final OnValueEdited callback,
            final @StringRes int hint,
            boolean password,
            boolean permitEmpty) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        final DialogQuickeditBinding binding =
                DataBindingUtil.inflate(
                        getLayoutInflater(), R.layout.dialog_quickedit, null, false);
        if (password) {
            binding.inputEditText.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        builder.setPositiveButton(R.string.accept, null);
        if (hint != 0) {
            binding.inputEditText.setHint(getString(hint));
        }
        binding.inputEditText.requestFocus();
        if (previousValue != null) {
            binding.inputEditText.getText().append(previousValue);
        }
        builder.setView(binding.getRoot());
        builder.setNegativeButton(R.string.cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> SoftKeyboardUtils.showKeyboard(binding.inputEditText));
        dialog.show();
        View.OnClickListener clickListener =
                v -> {
                    String value = binding.inputEditText.getText().toString();
                    if (!value.equals(previousValue) && (!value.trim().isEmpty() || permitEmpty)) {
                        String error = callback.onValueEdited(value);
                        if (error != null) {
                            binding.inputLayout.setError(error);
                            return;
                        }
                    }
                    SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText);
                    dialog.dismiss();
                };
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(clickListener);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                .setOnClickListener(
                        (v -> {
                            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText);
                            dialog.dismiss();
                        }));
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(
                dialog1 -> SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText));
    }

    protected boolean hasStoragePermission(int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
                return false;
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INVITE_TO_CONVERSATION && resultCode == RESULT_OK) {
            mPendingConferenceInvite = ConferenceInvite.parse(data);
            if (xmppConnectionServiceBound && mPendingConferenceInvite != null) {
                handleConferenceInviteResult(mPendingConferenceInvite.execute(this));
                mPendingConferenceInvite = null;
            }
        }
    }

    void handleConferenceInviteResult(final ConferenceInvite.Result result) {
        if (result == null) {
            return;
        }
        switch (result) {
            case CREATING_CONFERENCE:
                mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
                mToast.show();
                break;
            case INVITES_SENT:
                mToast = Toast.makeText(this, R.string.group_invites_sent, Toast.LENGTH_SHORT);
                mToast.show();
                break;
            case FAILED:
                mToast = Toast.makeText(this, R.string.group_invite_failed, Toast.LENGTH_SHORT);
                mToast.show();
                break;
        }
    }

    public boolean copyTextToClipboard(String text, int labelResId) {
        ClipboardManager mClipBoardManager =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String label = getResources().getString(labelResId);
        if (mClipBoardManager != null) {
            ClipData mClipData = ClipData.newPlainText(label, text);
            mClipBoardManager.setPrimaryClip(mClipData);
            return true;
        }
        return false;
    }

    protected boolean manuallyChangePresence() {
        return getBooleanPreference(
                AppSettings.MANUALLY_CHANGE_PRESENCE, R.bool.manually_change_presence);
    }

    protected String getShareableUri() {
        return getShareableUri(false);
    }

    protected String getShareableUri(boolean http) {
        return null;
    }

    protected void shareLink(boolean http) {
        String uri = getShareableUri(http);
        if (uri == null || uri.isEmpty()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, getShareableUri(http));
        try {
            startActivity(Intent.createChooser(intent, getText(R.string.share_uri_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SettingsUtils.applyScreenshotSetting(this);
    }

    protected int findTheme() {
        return ThemeHelper.find(this);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public boolean onMenuOpened(int id, Menu menu) {
        if (id == AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR && menu != null) {
            MenuDoubleTabUtil.recordMenuOpen();
        }
        return super.onMenuOpened(id, menu);
    }

    protected void showQrCode() {
        showQrCode(getShareableUri());
    }

    protected void showQrCode(final String uri) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        final Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        final int width = Math.min(size.x, size.y);
        final int black;
        final int white;
        if (Activities.isNightMode(this)) {
            black =
                    MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSurfaceContainerHighest,
                            "No surface color configured");
            white =
                    MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSurfaceInverse,
                            "No inverse surface color configured");
        } else {
            black =
                    MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSurfaceInverse,
                            "No inverse surface color configured");
            white =
                    MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSurfaceContainerHighest,
                            "No surface color configured");
        }
        final var bitmap = BarcodeProvider.create2dBarcodeBitmap(uri, width, black, white);
        final ImageView view = new ImageView(this);
        view.setBackgroundColor(white);
        view.setImageBitmap(bitmap);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(view);
        builder.create().show();
    }

    protected Account extractAccount(Intent intent) {
        final String jid = intent != null ? intent.getStringExtra(EXTRA_ACCOUNT) : null;
        try {
            return jid != null ? xmppConnectionService.findAccountByJid(Jid.of(jid)) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public AvatarService avatarService() {
        return xmppConnectionService.getAvatarService();
    }

    public void loadBitmap(Message message, ImageView imageView) {
        Bitmap bm = null;
        final String messageUuid = message.getUuid();
        final int targetSize = (int) (metrics.density * 288);
        // The cache is process-memory only. A secure cache hit avoids any Store read, temporary
        // plaintext lease or bitmap decode; a miss is handled by the verified worker below.
        bm = xmppConnectionService.getCachedMessageBitmap(message, targetSize, "chat-fit");
        if (bm != null) {
            cancelPotentialWork(message, imageView);
            imageView.setTag(R.id.TAG_MESSAGE_THUMBNAIL_ID, messageUuid);
            imageView.setImageBitmap(bm);
            applyStandaloneMediaPreviewAspect(imageView, bm);
            imageView.setBackgroundColor(0x00000000);
        } else if (cancelPotentialWork(message, imageView)) {
            final Object boundUuid = imageView.getTag(R.id.TAG_MESSAGE_THUMBNAIL_ID);
            final Drawable current = imageView.getDrawable();
            final Bitmap retained =
                    messageUuid.equals(boundUuid) && current instanceof BitmapDrawable
                            ? ((BitmapDrawable) current).getBitmap()
                            : null;
            imageView.setTag(R.id.TAG_MESSAGE_THUMBNAIL_ID, messageUuid);
            if (retained == null) {
                imageView.setBackgroundColor(0xff333333);
            }
            // Keep a bitmap that already belongs to this exact message visible while the verified
            // secure reader refreshes it. A different message never inherits a recycled bitmap.
            final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
            imageView.setImageDrawable(new AsyncDrawable(getResources(), retained, task));
            try {
                task.execute(message);
            } catch (final RejectedExecutionException ignored) {
                ignored.printStackTrace();
            }
        }
    }

    private static void applyStandaloneMediaPreviewAspect(
            final ImageView imageView, final Bitmap bitmap) {
        if (imageView == null || bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return;
        }
        final Object targetTag = imageView.getTag(R.id.TAG_MEDIA_PREVIEW_TARGET_SIZE);
        if (!(targetTag instanceof Integer)) {
            return;
        }
        final int target = Math.max(1, (Integer) targetTag);
        final int width;
        final int height;
        if (bitmap.getWidth() <= bitmap.getHeight()) {
            height = target;
            width =
                    Math.max(
                            1,
                            (int)
                                    Math.round(
                                            bitmap.getWidth()
                                                    / ((double) bitmap.getHeight() / target)));
        } else {
            width = target;
            height =
                    Math.max(
                            1,
                            (int)
                                    Math.round(
                                            bitmap.getHeight()
                                                    / ((double) bitmap.getWidth() / target)));
        }
        final ViewGroup.LayoutParams params = imageView.getLayoutParams();
        if (params != null && (params.width != width || params.height != height)) {
            params.width = width;
            params.height = height;
            imageView.setLayoutParams(params);
        }
    }

    protected interface OnValueEdited {
        String onValueEdited(String value);
    }

    public static class ConferenceInvite {
        public enum Result {
            INVITES_SENT,
            CREATING_CONFERENCE,
            FAILED
        }

        private String uuid;
        private final List<Jid> jids = new ArrayList<>();

        public static ConferenceInvite parse(Intent data) {
            if (data == null) {
                return null;
            }
            ConferenceInvite invite = new ConferenceInvite();
            invite.uuid = data.getStringExtra(ChooseContactActivity.EXTRA_CONVERSATION);
            if (invite.uuid == null) {
                return null;
            }
            invite.jids.addAll(ChooseContactActivity.extractJabberIds(data));
            return invite;
        }

        public Result execute(final XmppActivity activity) {
            if (activity == null || activity.xmppConnectionService == null || jids.isEmpty()) {
                return Result.FAILED;
            }
            final XmppConnectionService service = activity.xmppConnectionService;
            final Conversation conversation = service.findConversationByUuid(this.uuid);
            if (conversation == null) {
                return Result.FAILED;
            }
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                for (final Jid jid : jids) {
                    // TODO use direct invites for public conferences
                    service.invite(conversation, jid.asBareJid());
                }
                return Result.INVITES_SENT;
            } else {
                final ArrayList<Jid> conferenceMembers = new ArrayList<>(jids);
                conferenceMembers.add(conversation.getJid().asBareJid());
                return service.createAdhocConference(
                                conversation.getAccount(),
                                null,
                                conferenceMembers,
                                activity.adhocCallback)
                        ? Result.CREATING_CONFERENCE
                        : Result.FAILED;
            }
        }
    }

    static class BitmapWorkerTask extends AsyncTask<Message, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Message message = null;

        private BitmapWorkerTask(ImageView imageView) {
            this.imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(Message... params) {
            if (isCancelled()) {
                return null;
            }
            message = params[0];
            final XmppActivity activity = find(imageViewReference);
            if (activity == null || activity.xmppConnectionService == null) {
                return null;
            }
            final int targetSize = (int) (activity.metrics.density * 288);
            if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                try {
                    final Conversations application = (Conversations) activity.getApplication();
                    final AndroidSecureMessageMediaThumbnailReader secureReader =
                            new AndroidSecureMessageMediaThumbnailReader(
                                    activity,
                                    application.getSecureContentStoreProvider().get());
                    final Bitmap secure =
                            secureReader.load(
                                    message.getConversation().getAccount().getUuid(),
                                    message.getUuid(),
                                    targetSize,
                                    false);
                    if (secure != null) {
                        activity.xmppConnectionService.cacheMessageBitmap(
                                message, targetSize, "chat-fit", secure);
                        return secure;
                    }
                } catch (final Exception e) {
                    // Known secure relation but failed verification/decode: fail closed. Only a
                    // null result (no secure relation) is allowed to reach transition legacy data.
                    Log.w(Config.LOGTAG, "unable to read secure media thumbnail", e);
                    return null;
                }
            }
            try {
                return activity.xmppConnectionService
                        .getFileBackend()
                        .getThumbnail(message, targetSize, false);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(final Bitmap bitmap) {
            if (!isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null
                        && message != null
                        && message.getUuid().equals(imageView.getTag(R.id.TAG_MESSAGE_THUMBNAIL_ID))) {
                    imageView.setImageBitmap(bitmap);
                    applyStandaloneMediaPreviewAspect(imageView, bitmap);
                    imageView.setBackgroundColor(bitmap == null ? 0xff333333 : 0x00000000);
                }
            }
        }
    }

    private static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        private AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        private BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    public static XmppActivity find(@NonNull WeakReference<ImageView> viewWeakReference) {
        final View view = viewWeakReference.get();
        return view == null ? null : find(view);
    }

    public static XmppActivity find(@NonNull final View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof XmppActivity) {
                return (XmppActivity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
