/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.databinding.DataBindingUtil;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.java.otr4j.session.SessionStatus;


import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.databinding.ActivityConversationsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.interfaces.OnConversationArchived;
import eu.siacs.conversations.ui.interfaces.OnConversationRead;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.interfaces.OnConversationsListItemUpdated;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.ChatChromeTint;
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.util.ToolbarUtils;
import eu.siacs.conversations.ui.widget.PresenceIndicator;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.utils.PhoneNumberUtilWrapper;
import eu.siacs.conversations.utils.QuickLoader;
import eu.siacs.conversations.utils.SignupUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;
import io.michaelrocks.libphonenumber.android.NumberParseException;
import me.drakeet.support.toast.ToastCompat;

public class ConversationsActivity extends XmppActivity
        implements OnConversationSelected,
                OnConversationArchived,
                OnConversationsListItemUpdated,
                OnConversationRead,
                XmppConnectionService.OnAccountUpdate,
                XmppConnectionService.OnConversationUpdate,
                XmppConnectionService.OnRosterUpdate,
                OnUpdateBlocklist,
                XmppConnectionService.OnShowErrorToast,
                XmppConnectionService.OnAffiliationChanged {

    public static final String ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.action.VIEW";
    public static final String EXTRA_CONVERSATION = "conversationUuid";
    public static final String EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid";
    public static final String EXTRA_AS_QUOTE = "eu.siacs.conversations.as_quote";
    public static final String EXTRA_NICK = "nick";
    public static final String EXTRA_IS_PRIVATE_MESSAGE = "pm";
    public static final String EXTRA_DO_NOT_APPEND = "do_not_append";
    public static final String EXTRA_POST_INIT_ACTION = "post_init_action";
    public static final String POST_ACTION_RECORD_VOICE = "record_voice";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_NODE = "node";
    public static final String EXTRA_JID = "jid";
    public static final String EXTRA_MESSAGE_UUID = "messageUuid";

    private static final List<String> VIEW_AND_SHARE_ACTIONS =
            Arrays.asList(
                    ACTION_VIEW_CONVERSATION, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE);

    public static final int REQUEST_OPEN_MESSAGE = 0x9876;
    public static final int REQUEST_PLAY_PAUSE = 0x5432;

    // secondary fragment (when holding the conversation, must be initialized before refreshing the
    // overview fragment
    private static final @IdRes int[] FRAGMENT_ID_NOTIFICATION_ORDER = {
        R.id.secondary_fragment, R.id.main_fragment
    };
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
    private ActivityConversationsBinding binding;
    private boolean mActivityPaused = true;
    private final AtomicBoolean mRedirectInProcess = new AtomicBoolean(false);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTitleRunnable = this::invalidateActionBarTitle;
    private boolean showLastSeen = false;

    private boolean refreshForNewCaps = false;

    // Shared edge-to-edge state for the conversations overview and open conversation.
    private boolean chatEdgeToEdgeEnabled = false;
    private int toolbarBasePaddingLeft;
    private int toolbarBasePaddingTop;
    private int toolbarBasePaddingRight;
    private int toolbarBasePaddingBottom;
    private int defaultConversationHeaderColor;

    // The same toolbar instance is reused for the overview and an open conversation.
    // Preserve the theme-resolved overview typography before the conversation mode mutates it.
    private float overviewToolbarTitleSizePx = Float.NaN;
    @Nullable private android.graphics.Typeface overviewToolbarTitleTypeface;
    private int overviewToolbarTitleMaxLines = -1;
    @Nullable private android.text.TextUtils.TruncateAt overviewToolbarTitleEllipsize;

    private static boolean isViewOrShareIntent(Intent i) {
        Log.d(Config.LOGTAG, "action: " + (i == null ? null : i.getAction()));
        return i != null
                && VIEW_AND_SHARE_ACTIONS.contains(i.getAction())
                && i.hasExtra(EXTRA_CONVERSATION);
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, ConversationsActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    @Override
    protected void refreshUiReal() {
        invalidateOptionsMenu();
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id);
        }
        invalidateActionBarTitle();
        refreshForNewCaps = false;
    }

    @Override
    protected void onBackendConnected() {
        if (performRedirectIfNecessary(true)) {
            return;
        }
        xmppConnectionService.getNotificationService().setIsInForeground(true);
        final Intent intent = pendingViewIntent.pop();
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding.secondaryFragment != null) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment);
                }
                invalidateActionBarTitle();
                return;
            }
        }
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            notifyFragmentOfBackendConnected(id);
        }

        final ActivityResult activityResult = postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }

        invalidateActionBarTitle();
        if (binding.secondaryFragment != null
                && ConversationFragment.getConversation(this) == null) {
            Conversation conversation = ConversationsOverviewFragment.getSuggestion(this);
            if (conversation != null) {
                openConversation(conversation, null);
            }
        }
        showDialogsIfMainIsOverview();
    }

    private boolean performRedirectIfNecessary(boolean noAnimation) {
        return performRedirectIfNecessary(null, noAnimation);
    }

    private boolean performRedirectIfNecessary(
            final Conversation ignore, final boolean noAnimation) {
        if (xmppConnectionService == null) {
            return false;
        }
        boolean isConversationsListEmpty = xmppConnectionService.isConversationsListEmpty(ignore);
        if (isConversationsListEmpty && mRedirectInProcess.compareAndSet(false, true)) {
            final Intent intent = SignupUtils.getRedirectionIntent(this);
            if (noAnimation) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            }
            runOnUiThread(
                    () -> {
                        startActivity(intent);
                        if (noAnimation) {
                            overridePendingTransition(0, 0);
                        }
                    });
        }
        return mRedirectInProcess.get();
    }

    private void showDialogsIfMainIsOverview() {
        if (xmppConnectionService == null) {
            return;
        }
        final Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            if (ExceptionHelper.checkForCrash(this)) {
                return;
            }
            if (openBatteryOptimizationDialogIfNeeded()) {
                return;
            }
            requestNotificationPermissionIfNeeded();
        }
    }

    private String getBatteryOptimizationPreferenceKey() {
        @SuppressLint("HardwareIds")
        String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return "show_battery_optimization" + (device == null ? "" : device);
    }

    private void setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
    }

    private boolean openBatteryOptimizationDialogIfNeeded() {
        if (isOptimizingBattery()
                && getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)) {
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.battery_optimizations_enabled);
            builder.setMessage(
                    getString(
                            R.string.battery_optimizations_enabled_dialog,
                            getString(R.string.app_name)));
            builder.setPositiveButton(
                    R.string.next,
                    (dialog, which) -> {
                        final Intent intent =
                                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        final Uri uri = Uri.parse("package:" + getPackageName());
                        intent.setData(uri);
                        try {
                            startActivityForResult(intent, REQUEST_BATTERY_OP);
                        } catch (final ActivityNotFoundException e) {
                            Toast.makeText(
                                            this,
                                            R.string.device_does_not_support_battery_op,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
            builder.setOnDismissListener(dialog -> setNeverAskForBatteryOptimizationsAgain());
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
            return true;
        }
        return false;
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

    private void notifyFragmentOfBackendConnected(@IdRes int id) {
        final Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment instanceof OnBackendConnected callback) {
            callback.onBackendConnected();
        }
    }

    private void refreshFragment(@IdRes int id) {
        final Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment instanceof XmppFragment xmppFragment) {
            xmppFragment.refresh();
            if (refreshForNewCaps) ((XmppFragment) fragment).refreshForNewCaps();
        }
    }

    private boolean processViewIntent(final Intent intent) {
        final String uuid = intent.getStringExtra(EXTRA_CONVERSATION);
        final Conversation conversation =
                uuid != null ? xmppConnectionService.findConversationByUuidReliable(uuid) : null;
        if (conversation == null) {
            Log.d(Config.LOGTAG, "unable to view conversation with uuid:" + uuid);
            return false;
        }
        openConversation(conversation, intent.getExtras());
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switch (requestCode) {
                    case REQUEST_OPEN_MESSAGE:
                        refreshUiReal();
                        ConversationFragment.openPendingMessage(this);
                        break;
                    case REQUEST_PLAY_PAUSE:
                        ConversationFragment.startStopPending(this);
                        break;
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    private void handleActivityResult(final ActivityResult activityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
        } else {
            handleNegativeActivityResult(activityResult.requestCode);
        }
        if (activityResult.requestCode == REQUEST_BATTERY_OP) {
            // the result code is always 0 even when battery permission were granted
            requestNotificationPermissionIfNeeded();
            XmppConnectionService.toggleForegroundService(xmppConnectionService);
        }
    }

    private void handleNegativeActivityResult(int requestCode) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        switch (requestCode) {
            case REQUEST_BATTERY_OP:
                setNeverAskForBatteryOptimizationsAgain();
                break;
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        // No external crypto provider activity results are required anymore.
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConversationMenuConfigurator.reloadFeatures(this);
        OmemoSetting.load(this);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations);
        toolbarBasePaddingLeft = binding.toolbarMatteZone.getPaddingLeft();
        toolbarBasePaddingTop = binding.toolbarMatteZone.getPaddingTop();
        toolbarBasePaddingRight = binding.toolbarMatteZone.getPaddingRight();
        toolbarBasePaddingBottom = binding.toolbarMatteZone.getPaddingBottom();
        defaultConversationHeaderColor =
                binding.conversationHeaderIsland.getCardBackgroundColor().getDefaultColor();
        final MaterialToolbar conversationToolbar =
                (MaterialToolbar) binding.toolbarLayout.findViewById(R.id.toolbar);
        setSupportActionBar(conversationToolbar);
        final ActionBar conversationActionBar = getSupportActionBar();
        configureActionBar(conversationActionBar);
        if (conversationActionBar != null) {
            conversationActionBar.setHomeAsUpIndicator(R.drawable.ic_header_back);
            // Ensure the title TextView exists while it still has the theme's overview style.
            conversationActionBar.setTitle(R.string.app_name);
            captureOverviewToolbarTextStyle(conversationToolbar);
        }
        conversationToolbar.setOverflowIcon(
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        this, R.drawable.ic_header_overflow));
        this.getFragmentManager().addOnBackStackChangedListener(this::invalidateActionBarTitle);
        this.getFragmentManager().addOnBackStackChangedListener(this::showDialogsIfMainIsOverview);
        this.initializeFragments();
        this.invalidateActionBarTitle();
        final Intent intent;
        if (savedInstanceState == null) {
            intent = getIntent();
        } else {
            intent = savedInstanceState.getParcelable("intent");
        }
        if (isViewOrShareIntent(intent)) {
            final String quickConversationUuid = intent.getStringExtra(EXTRA_CONVERSATION);
            if (quickConversationUuid != null) {
                QuickLoader.set(this, quickConversationUuid);
            }
            pendingViewIntent.push(intent);
            setIntent(createLauncherIntent(this));
        }

        hideNavigationBar();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_conversations, menu);
        configureConversationSearch(menu);
        updateChatListMenuItems(menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        updateChatListMenuItems(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    private void configureConversationSearch(final Menu menu) {
        final MenuItem searchMenuItem = menu.findItem(R.id.action_search);
        if (searchMenuItem == null || searchMenuItem.getActionView() == null) {
            return;
        }
        final EditText searchInput =
                searchMenuItem.getActionView().findViewById(R.id.search_field);
        final View searchMessagesAction =
                searchMenuItem.getActionView().findViewById(R.id.search_messages_action_inline);
        searchInput.setHint(R.string.search_conversations_hint);
        searchInput.setContentDescription(getString(R.string.search_conversations_hint));
        final Runnable openMessageSearch =
                () -> {
                    final String query = searchInput.getText().toString().trim();
                    if (query.isEmpty()) {
                        return;
                    }
                    final Intent intent = new Intent(this, SearchActivity.class);
                    intent.putExtra(SearchActivity.EXTRA_SEARCH_TERM, query);
                    startActivity(intent);
                };
        if (searchMessagesAction != null) {
            searchMessagesAction.setEnabled(false);
            searchMessagesAction.setOnClickListener(view -> openMessageSearch.run());
        }
        searchInput.setOnEditorActionListener(
                (view, actionId, event) -> {
                    if (actionId != EditorInfo.IME_ACTION_SEARCH) {
                        return false;
                    }
                    openMessageSearch.run();
                    return !searchInput.getText().toString().trim().isEmpty();
                });
        searchInput.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            final CharSequence text,
                            final int start,
                            final int count,
                            final int after) {}

                    @Override
                    public void onTextChanged(
                            final CharSequence text,
                            final int start,
                            final int before,
                            final int count) {
                        final String query = text == null ? "" : text.toString();
                        filterConversations(query);
                        if (searchMessagesAction != null) {
                            searchMessagesAction.setEnabled(!query.trim().isEmpty());
                        }
                    }

                    @Override
                    public void afterTextChanged(final Editable editable) {}
                });
        searchMenuItem.setOnActionExpandListener(
                new MenuItem.OnActionExpandListener() {
                    @Override
                    public boolean onMenuItemActionExpand(final MenuItem item) {
                        searchInput.post(
                                () -> {
                                    searchInput.requestFocus();
                                    final InputMethodManager inputMethodManager =
                                            (InputMethodManager)
                                                    getSystemService(Context.INPUT_METHOD_SERVICE);
                                    if (inputMethodManager != null) {
                                        inputMethodManager.showSoftInput(
                                                searchInput, InputMethodManager.SHOW_IMPLICIT);
                                    }
                                });
                        return true;
                    }

                    @Override
                    public boolean onMenuItemActionCollapse(final MenuItem item) {
                        searchInput.setText("");
                        filterConversations("");
                        return true;
                    }
                });
    }

    private void updateChatListMenuItems(final Menu menu) {
        final Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        final boolean visible = fragment instanceof ConversationsOverviewFragment;
        final MenuItem searchMenuItem = menu.findItem(R.id.action_search);
        if (searchMenuItem != null) {
            searchMenuItem.setVisible(visible);
        }
        final MenuItem settingsMenuItem = menu.findItem(R.id.action_settings);
        if (settingsMenuItem != null) {
            settingsMenuItem.setVisible(visible);
        }
        updateProfileMenuItem(menu);
    }

    private void filterConversations(final String query) {
        final Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            ((ConversationsOverviewFragment) fragment).setConversationFilter(query);
        }
    }

    private void updateProfileMenuItem(final Menu menu) {
        final MenuItem profileMenuItem = menu.findItem(R.id.action_profile);
        if (profileMenuItem == null) {
            return;
        }
        final Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        final boolean visible = fragment instanceof ConversationsOverviewFragment;
        profileMenuItem.setVisible(visible);
        if (!visible) {
            return;
        }

        profileMenuItem.setIcon(R.drawable.ic_person_24dp);
        profileMenuItem.setTitle(R.string.profile_navigation_title);
        final View actionView = profileMenuItem.getActionView();
        if (actionView != null) {
            actionView.setOnClickListener(v -> onOptionsItemSelected(profileMenuItem));
            bindProfileAccountAction(actionView, null, null);
        }
        if (xmppConnectionService == null) {
            return;
        }

        final Account profileAccount =
                ProfileNavigation.preferredProfileAccount(this, xmppConnectionService);
        if (profileAccount == null) {
            return;
        }

        final int avatarSize =
                Math.round(getResources().getDisplayMetrics().density * 28f);
        final Bitmap source =
                xmppConnectionService.getAvatarService().get(profileAccount, avatarSize);
        if (source != null) {
            final RoundedBitmapDrawable avatar =
                    RoundedBitmapDrawableFactory.create(getResources(), source);
            avatar.setCircular(true);
            avatar.setAntiAlias(true);
            profileMenuItem.setIcon(avatar);
        }

        final String description =
                getString(R.string.profile_navigation_title)
                        + " · "
                        + profileAccount.getJid().asBareJid()
                        + " · "
                        + getString(profileAccount.getStatus().getReadableId());
        profileMenuItem.setTitle(description);
        if (actionView != null) {
            bindProfileAccountAction(actionView, profileAccount, source);
            actionView.setContentDescription(description);
        }
    }

    private void bindProfileAccountAction(
            @NonNull final View actionView,
            @Nullable final Account account,
            @Nullable final Bitmap source) {
        final ImageView avatarView = actionView.findViewById(R.id.profile_account_avatar);

        avatarView.clearAnimation();
        avatarView.clearColorFilter();
        avatarView.setAlpha(1f);

        if (source == null) {
            avatarView.setImageResource(R.drawable.ic_person_24dp);
            ImageViewCompat.setImageTintList(
                    avatarView,
                    ColorStateList.valueOf(
                            com.google.android.material.color.MaterialColors.getColor(
                                    avatarView,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant)));
        } else {
            final RoundedBitmapDrawable avatar =
                    RoundedBitmapDrawableFactory.create(getResources(), source);
            avatar.setCircular(true);
            avatar.setAntiAlias(true);
            avatarView.setImageDrawable(avatar);
            ImageViewCompat.setImageTintList(avatarView, null);
        }

        if (account == null) {
            return;
        }

        final Account.State state = account.getStatus();
        if (state == Account.State.ONLINE) {
            return;
        }

        if (isConnectingProfileState(state)) {
            // Connection updates can refresh the menu frequently. A restarted infinite alpha
            // animation reads as screen flicker, so keep a calm, stable connecting state.
            avatarView.setAlpha(0.78f);
            return;
        }

        if (source != null) {
            final ColorMatrix grayscale = new ColorMatrix();
            grayscale.setSaturation(0f);
            avatarView.setColorFilter(new ColorMatrixColorFilter(grayscale));
        }
        avatarView.setAlpha(0.56f);
    }

    private static boolean isConnectingProfileState(final Account.State state) {
        return state == Account.State.CONNECTING
                || state == Account.State.REGISTRATION_SUCCESSFUL
                || state == Account.State.REGISTRATION_PLEASE_WAIT;
    }

    @Override
    public void onConversationSelected(Conversation conversation) {
        clearPendingViewIntent();
        if (ConversationFragment.getConversation(this) == conversation) {
            Log.d(
                    Config.LOGTAG,
                    "ignore onConversationSelected() because conversation is already open");
            return;
        }
        openConversation(conversation, null);
    }

    public void clearPendingViewIntent() {
        if (pendingViewIntent.clear()) {
            Log.e(Config.LOGTAG, "cleared pending view intent");
        }
    }

    public boolean navigationBarVisible() {
        return findViewById(R.id.bottom_navigation).getVisibility() == View.VISIBLE;
    }

    public boolean showNavigationBar() {
        findViewById(R.id.bottom_navigation).setVisibility(View.GONE);
        return false;
    }

    public void hideNavigationBar() {
        findViewById(R.id.bottom_navigation).setVisibility(View.GONE);
    }

    @Nullable
    public View getFragmentHostView() {
        if (binding.secondaryFragment != null) {
            return binding.secondaryFragment;
        } else {
            return binding.mainFragment;
        }
    }

    private void displayToast(final String msg) {
        runOnUiThread(
                () -> Toast.makeText(ConversationsActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onAffiliationChangedSuccessful(Jid jid) {}

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, jid.asBareJid().toString()));
    }

    private void openConversation(Conversation conversation, Bundle extras) {
        final FragmentManager fragmentManager = getFragmentManager();
        executePendingTransactions(fragmentManager);
        ConversationFragment conversationFragment =
                (ConversationFragment) fragmentManager.findFragmentById(R.id.secondary_fragment);
        final boolean mainNeedsRefresh;
        boolean toolbarUpdateHandledByBackStack = false;
        if (conversationFragment == null) {
            mainNeedsRefresh = false;
            final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
            if (mainFragment instanceof ConversationFragment) {
                conversationFragment = (ConversationFragment) mainFragment;
            } else {
                conversationFragment = new ConversationFragment();
                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                fragmentTransaction.replace(R.id.main_fragment, conversationFragment);
                fragmentTransaction.addToBackStack(null);
                try {
                    fragmentTransaction.commit();
                    // The back-stack callback will update the toolbar after the new fragment is
                    // actually addressable. Updating it here would briefly restore the overview.
                    toolbarUpdateHandledByBackStack = true;
                } catch (IllegalStateException e) {
                    Log.w(Config.LOGTAG, "sate loss while opening conversation", e);
                    // allowing state loss is probably fine since view intents et all are already
                    // stored and a click can probably be 'ignored'
                    return;
                }
            }
        } else {
            mainNeedsRefresh = true;
        }
        conversationFragment.reInit(conversation, extras == null ? new Bundle() : extras);
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment);
        }
        if (!toolbarUpdateHandledByBackStack) {
            invalidateActionBarTitle();
        }
    }

    private static void executePendingTransactions(final FragmentManager fragmentManager) {
        try {
            fragmentManager.executePendingTransactions();
        } catch (final Exception e) {
            Log.e(Config.LOGTAG, "unable to execute pending fragment transactions");
        }
    }

    public boolean onXmppUriClicked(Uri uri) {
        XmppUri xmppUri = new XmppUri(uri);
        if (xmppUri.isValidJid() && !xmppUri.hasFingerprints()) {
            final Conversation conversation =
                    xmppConnectionService.findUniqueConversationByJid(xmppUri);
            if (conversation != null) {
                if (xmppUri.isAction("command")) {
                    startCommand(conversation.getAccount(), xmppUri.getJid(), xmppUri.getParameter("node"));
                } else {
                    Bundle extras = new Bundle();
                    extras.putString(Intent.EXTRA_TEXT, xmppUri.getBody());
                    if (xmppUri.isAction("message")) extras.putString(EXTRA_POST_INIT_ACTION, "message");
                    openConversation(conversation, extras);
                }
                return true;
            }
        }
        return false;
    }

    public boolean onTelUriClicked(Uri uri, Account acct) {
        final String tel;
        try {
            tel = PhoneNumberUtilWrapper.normalize(this, uri.getSchemeSpecificPart());
        } catch (final IllegalArgumentException | NumberParseException | NullPointerException e) {
            return false;
        }

        Set<String> gateways = new HashSet<>();
        for (Account account : (acct == null ? xmppConnectionService.getAccounts() : List.of(acct))) {
            for (Contact contact : account.getRoster().getContacts()) {
                if (contact.getPresences().anyIdentity("gateway", "pstn") || contact.getPresences().anyIdentity("gateway", "sms")) {
                    if (acct == null) acct = account;
                    gateways.add(contact.getJid().asBareJid().toString());
                }
            }
        }

        for (String gateway : gateways) {
            if (onXmppUriClicked(Uri.parse("xmpp:" + tel + "@" + gateway))) return true;
        }

        if (gateways.size() == 1 && acct != null) {
            openConversation(xmppConnectionService.findOrCreateConversation(acct, Jid.ofLocalAndDomain(tel, gateways.iterator().next()), null, false, false, true, null), null);
            return true;
        }

        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                FragmentManager fm = getFragmentManager();
                if (fm.getBackStackEntryCount() > 0) {
                    QuickLoader.clear(this);
                    try {
                        fm.popBackStack();
                    } catch (IllegalStateException e) {
                        Log.w(Config.LOGTAG, "Unable to pop back stack after pressing home button");
                    }
                    return true;
                }
                break;
            case R.id.action_profile:
                if (xmppConnectionService == null) {
                    startActivity(ProfileNavigation.globalProfileIntent(this));
                } else {
                    startActivity(
                            ProfileNavigation.profileIntentForHome(
                                    this, xmppConnectionService));
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && keyEvent.isCtrlPressed()) {
            final ConversationFragment conversationFragment = ConversationFragment.get(this);
            if (conversationFragment != null && conversationFragment.onArrowUpCtrlPressed()) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        final Intent pendingIntent = pendingViewIntent.peek();
        savedInstanceState.putParcelable(
                "intent", pendingIntent != null ? pendingIntent : getIntent());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        mRedirectInProcess.set(false);
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        this.showLastSeen = preferences.getBoolean("show_contact_status", getResources().getBoolean(R.bool.show_contact_status));

        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setSelectedItemId(R.id.chats);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (isViewOrShareIntent(intent)) {
            if (xmppConnectionService != null) {
                clearPendingViewIntent();
                processViewIntent(intent);
            } else {
                pendingViewIntent.push(intent);
            }
        }
        setIntent(createLauncherIntent(this));
    }

    @Override
    public void onBackPressed() {
        final Fragment mainFragment =
                getFragmentManager().findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment
                && getFragmentManager().getBackStackEntryCount() > 0) {
            QuickLoader.clear(this);
        }
        super.onBackPressed();
    }

    @Override
    public void onPause() {
        this.mActivityPaused = true;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        this.mActivityPaused = false;
        invalidateOptionsMenu();
        handler.post(this::showDialogsIfMainIsOverview);
    }

    private void initializeFragments() {
        final FragmentManager fragmentManager = getFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        final Fragment secondaryFragment =
                fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (mainFragment != null) {
            if (binding.secondaryFragment != null) {
                if (mainFragment instanceof ConversationFragment) {
                    getFragmentManager().popBackStack();
                    transaction.remove(mainFragment);
                    transaction.commit();
                    fragmentManager.executePendingTransactions();
                    transaction = fragmentManager.beginTransaction();
                    transaction.replace(R.id.secondary_fragment, mainFragment);
                    transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
                    transaction.commit();
                    return;
                }
            } else {
                if (secondaryFragment instanceof ConversationFragment) {
                    transaction.remove(secondaryFragment);
                    transaction.commit();
                    getFragmentManager().executePendingTransactions();
                    transaction = fragmentManager.beginTransaction();
                    transaction.replace(R.id.main_fragment, secondaryFragment);
                    transaction.addToBackStack(null);
                    transaction.commit();
                    return;
                }
            }
        } else {
            transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
        }
        if (binding.secondaryFragment != null && secondaryFragment == null) {
            transaction.replace(R.id.secondary_fragment, new ConversationFragment());
        }
        transaction.commit();
    }

    private static final String EMPTY_HEADER_SUBTITLE = "\u00A0";
    private static final int DEFAULT_TOOLBAR_HEIGHT_DP = 52;
    private static final int CONVERSATION_TOOLBAR_HEIGHT_DP = 52;

    @Nullable
    private Conversation getToolbarConversation(@NonNull final FragmentManager fragmentManager) {
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment conversationFragment) {
            final Conversation conversation = conversationFragment.getConversation();
            if (conversation != null) {
                return conversation;
            }
        }

        final Fragment secondaryFragment =
                fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment conversationFragment) {
            return conversationFragment.getConversation();
        }
        return null;
    }

    private void setToolbarHeight(@NonNull final MaterialToolbar toolbar, final int heightDp) {
        final int height =
                Math.round(getResources().getDisplayMetrics().density * heightDp);
        final android.view.ViewGroup.LayoutParams params = toolbar.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            toolbar.setLayoutParams(params);
        }
        toolbar.setMinimumHeight(height);
    }

    @Nullable
    private CharSequence getContactPresenceSubtitle(@Nullable final Contact contact) {
        if (contact == null
                || contact.getAccount() == null
                || !contact.getAccount().isOnlineAndConnected()) {
            return null;
        }
        switch (contact.getShownStatus()) {
            case CHAT:
            case ONLINE:
                return getString(R.string.online_right_now_short);
            case AWAY:
                return getString(R.string.presence_away);
            case XA:
                return getString(R.string.presence_xa);
            case DND:
                return getString(R.string.presence_dnd);
            case OFFLINE:
            default:
                return null;
        }
    }

    private CharSequence getSingleConversationSubtitle(
            @NonNull final Conversation conversation) {
        handler.removeCallbacks(refreshTitleRunnable);

        if (conversation.getNextCounterpart() != null
                && conversation.hasPermanentCounterpart()) {
            return conversation.getNextCounterpart().getResource();
        }
        if (conversation.withSelf()) {
            return EMPTY_HEADER_SUBTITLE;
        }

        final Contact contact = conversation.getContact();
        if (contact != null && !contact.showInRoster()) {
            return getString(R.string.add_contact);
        }
        if (conversation.getIncomingChatState()
                == eu.siacs.conversations.xmpp.chatstate.ChatState.COMPOSING) {
            return getString(R.string.conversation_header_writing);
        }
        final CharSequence presenceSubtitle = getContactPresenceSubtitle(contact);
        if (presenceSubtitle != null) {
            final CharSequence statusMessage = getContactStatusMessage(contact);
            // Presence is already communicated by the avatar indicator. Keep the subtitle for
            // human-provided status text instead of repeating "online / away / dnd".
            return statusMessage == null ? EMPTY_HEADER_SUBTITLE : statusMessage;
        }
        if (showLastSeen
                && contact != null
                && contact.getLastseen() > 0
                && contact.getPresences()
                        .allOrNonSupport(eu.siacs.conversations.xml.Namespace.IDLE)) {
            handler.postDelayed(refreshTitleRunnable, 5000L);
            return UIHelper.lastseen(this, false, contact.getLastseen(), true);
        }
        return EMPTY_HEADER_SUBTITLE;
    }

    @Nullable
    private CharSequence getContactStatusMessage(@Nullable final Contact contact) {
        if (contact == null) {
            return null;
        }
        final List<String> statusMessages = contact.getPresences().getStatusMessages();
        if (statusMessages.isEmpty()) {
            return null;
        }
        final String status = statusMessages.get(0);
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void captureOverviewToolbarTextStyle(@NonNull final MaterialToolbar toolbar) {
        if (!Float.isNaN(overviewToolbarTitleSizePx)) {
            return;
        }
        final TextView title = ToolbarUtils.getTitleTextView(toolbar);
        if (title == null) {
            return;
        }
        overviewToolbarTitleSizePx = title.getTextSize();
        overviewToolbarTitleTypeface = title.getTypeface();
        overviewToolbarTitleMaxLines = title.getMaxLines();
        overviewToolbarTitleEllipsize = title.getEllipsize();
    }

    private void restoreOverviewToolbarTextStyle(@NonNull final MaterialToolbar toolbar) {
        final TextView title = ToolbarUtils.getTitleTextView(toolbar);
        if (title == null) {
            return;
        }
        if (Float.isNaN(overviewToolbarTitleSizePx)) {
            captureOverviewToolbarTextStyle(toolbar);
            return;
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, overviewToolbarTitleSizePx);
        title.setTypeface(overviewToolbarTitleTypeface);
        title.setMaxLines(overviewToolbarTitleMaxLines);
        title.setEllipsize(overviewToolbarTitleEllipsize);
    }

    private void styleConversationToolbarText(@NonNull final MaterialToolbar toolbar) {
        final TextView title = ToolbarUtils.getTitleTextView(toolbar);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            final android.graphics.Typeface medium =
                    androidx.core.content.res.ResourcesCompat.getFont(
                            this, R.font.onest_medium);
            if (medium != null) {
                title.setTypeface(medium);
            }
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }

        final TextView subtitle = ToolbarUtils.getSubtitleTextView(toolbar);
        if (subtitle != null) {
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            final android.graphics.Typeface regular =
                    androidx.core.content.res.ResourcesCompat.getFont(
                            this, R.font.onest_regular);
            if (regular != null) {
                subtitle.setTypeface(regular);
            }
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    private void updateConversationToolbarAvatar(
            @NonNull final MaterialToolbar toolbar, @Nullable final Conversation conversation) {
        if (conversation == null || xmppConnectionService == null) {
            toolbar.setLogo(null);
            return;
        }

        final float density = getResources().getDisplayMetrics().density;
        final int avatarSize = Math.round(density * 40f);
        final Bitmap bitmap = avatarService().get(conversation, avatarSize, false);
        if (bitmap == null) {
            toolbar.setLogo(null);
            return;
        }

        final Bitmap toolbarAvatar = createToolbarAvatarBitmap(bitmap, conversation, avatarSize);
        toolbar.setLogo(new BitmapDrawable(getResources(), toolbarAvatar));
        toolbar.setLogoDescription(
                getString(R.string.avatar_for_x, conversation.getAvatarName()));

        final ImageView logoView = ToolbarUtils.getLogoImageView(toolbar);
        if (logoView != null) {
            final Toolbar.LayoutParams params = (Toolbar.LayoutParams) logoView.getLayoutParams();
            // Keep identical avatar geometry for direct and group conversations.
            params.width = Math.round(density * 48f);
            params.height = Math.round(density * 48f);
            params.setMarginEnd(Math.round(density * 6f));
            logoView.setScaleType(ImageView.ScaleType.CENTER);
            logoView.setLayoutParams(params);
            logoView.setOnClickListener(v -> openConversationDetails(conversation));
        }
    }

    private Bitmap createToolbarAvatarBitmap(
            @NonNull final Bitmap source,
            @NonNull final Conversation conversation,
            final int avatarSize) {
        final Bitmap result =
                Bitmap.createBitmap(avatarSize, avatarSize, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);

        final RoundedBitmapDrawable avatar =
                RoundedBitmapDrawableFactory.create(getResources(), source);
        avatar.setCircular(true);
        avatar.setAntiAlias(true);
        avatar.setBounds(0, 0, avatarSize, avatarSize);
        avatar.draw(canvas);

        // Use the same presence semantics and colors as PresenceIndicator in the contact list.
        if (conversation.getMode() == Conversation.MODE_SINGLE
                && xmppConnectionService != null
                && xmppConnectionService.getBooleanPreference(
                        "show_contact_status", R.bool.show_contact_status)) {
            final Contact contact = conversation.getContact();
            final Presence.Status status =
                    contact != null
                                    && contact.getAccount() != null
                                    && contact.getAccount().isOnlineAndConnected()
                            ? contact.getShownStatus()
                            : null;
            if (status != null) {
                final float density = getResources().getDisplayMetrics().density;
                final float radius = 6f * density;
                final float offset = 2f * density;
                final float center = avatarSize - offset - radius;
                PresenceIndicator.drawBadge(canvas, this, status, center, center);
            }
        }
        return result;
    }

    public void applyConversationChrome(
            final int topVisibleColor,
            final int bottomVisibleColor) {
        if (binding == null) {
            return;
        }
        final int onSurface =
                com.google.android.material.color.MaterialColors.getColor(
                        binding.getRoot(), com.google.android.material.R.attr.colorOnSurface);
        binding.conversationHeaderIsland.setCardBackgroundColor(
                ChatChromeTint.resolveIslandColor(
                        defaultConversationHeaderColor, topVisibleColor, onSurface));
        binding.overviewHeaderProtection.setVisibility(View.GONE);
        binding.overviewHeaderProtection.setBackground(null);
        if (!chatEdgeToEdgeEnabled) {
            return;
        }

        final boolean darkStatusIcons =
                ChatChromeTint.shouldUseDarkSystemIcons(topVisibleColor);
        final boolean darkNavigationIcons =
                ChatChromeTint.shouldUseDarkSystemIcons(bottomVisibleColor);
        binding.statusBarProtection.setBackground(
                ChatChromeTint.createSystemBarProtection(
                        topVisibleColor, darkStatusIcons, true));

        final WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(darkStatusIcons);
        controller.setAppearanceLightNavigationBars(darkNavigationIcons);
    }

    private void applyOverviewChrome() {
        if (binding == null) {
            return;
        }
        final int surface =
                com.google.android.material.color.MaterialColors.getColor(
                        binding.getRoot(), com.google.android.material.R.attr.colorSurface);
        final boolean darkIcons = ChatChromeTint.shouldUseDarkSystemIcons(surface);
        binding.conversationHeaderIsland.setCardBackgroundColor(defaultConversationHeaderColor);
        binding.statusBarProtection.setVisibility(View.VISIBLE);
        binding.statusBarProtection.setBackground(
                ChatChromeTint.createSystemBarProtection(surface, darkIcons, true));
        binding.overviewHeaderProtection.setVisibility(View.VISIBLE);
        binding.overviewHeaderProtection.setBackground(
                ChatChromeTint.createOverviewHeaderProtection(surface));

        final WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(darkIcons);
        controller.setAppearanceLightNavigationBars(darkIcons);
    }

    private void setChatEdgeToEdgeEnabled(final boolean enabled) {
        if (binding == null || chatEdgeToEdgeEnabled == enabled) {
            return;
        }
        chatEdgeToEdgeEnabled = enabled;
        WindowCompat.setDecorFitsSystemWindows(getWindow(), !enabled);
        ViewCompat.requestApplyInsets(binding.getRoot());

        if (enabled) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getWindow().setStatusBarContrastEnforced(false);
                getWindow().setNavigationBarContrastEnforced(false);
            }
            binding.statusBarProtection.setVisibility(View.VISIBLE);
            ViewCompat.setOnApplyWindowInsetsListener(
                    binding.toolbarMatteZone,
                    (view, insets) -> {
                        final androidx.core.graphics.Insets statusBars =
                                insets.getInsets(WindowInsetsCompat.Type.statusBars());
                        final androidx.core.graphics.Insets cutout =
                                insets.getInsets(WindowInsetsCompat.Type.displayCutout());
                        final int top = Math.max(statusBars.top, cutout.top);
                        final int left = Math.max(statusBars.left, cutout.left);
                        final int right = Math.max(statusBars.right, cutout.right);
                        view.setPadding(
                                toolbarBasePaddingLeft + left,
                                toolbarBasePaddingTop + top,
                                toolbarBasePaddingRight + right,
                                toolbarBasePaddingBottom);

                        final android.view.ViewGroup.LayoutParams protectionParams =
                                binding.statusBarProtection.getLayoutParams();
                        // The matte should finish with the compact conversation island:
                        // 6dp outer matte + the shared 52dp conversation toolbar.
                        final int protectionHeight =
                                top
                                        + getResources()
                                                .getDimensionPixelSize(
                                                        R.dimen.conversation_header_chrome_height);
                        if (protectionParams.height != protectionHeight) {
                            protectionParams.height = protectionHeight;
                            binding.statusBarProtection.setLayoutParams(protectionParams);
                        }
                        return insets;
                    });
            ViewCompat.requestApplyInsets(binding.toolbarMatteZone);
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(binding.toolbarMatteZone, null);
            binding.toolbarMatteZone.setPadding(
                    toolbarBasePaddingLeft,
                    toolbarBasePaddingTop,
                    toolbarBasePaddingRight,
                    toolbarBasePaddingBottom);
            binding.conversationHeaderIsland.setCardBackgroundColor(defaultConversationHeaderColor);
            binding.statusBarProtection.setVisibility(View.GONE);
            binding.statusBarProtection.setBackground(null);

            final int surface =
                    com.google.android.material.color.MaterialColors.getColor(
                            binding.getRoot(), com.google.android.material.R.attr.colorSurface);
            getWindow().setStatusBarColor(surface);
            getWindow().setNavigationBarColor(surface);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getWindow().setStatusBarContrastEnforced(true);
                getWindow().setNavigationBarContrastEnforced(true);
            }
            final boolean lightIcons =
                    ColorUtils.calculateLuminance(surface) > 0.56d;
            final WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(lightIcons);
            controller.setAppearanceLightNavigationBars(lightIcons);
        }
    }

    private void invalidateActionBarTitle() {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }

        final MaterialToolbar toolbar =
                (MaterialToolbar) binding.toolbarLayout.findViewById(R.id.toolbar);
        final FragmentManager fragmentManager = getFragmentManager();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        final Conversation conversation = getToolbarConversation(fragmentManager);
        if (conversation != null) {
            setChatEdgeToEdgeEnabled(true);
            binding.overviewHeaderProtection.setVisibility(View.GONE);
            binding.overviewHeaderProtection.setBackground(null);
            final boolean singleConversation =
                    conversation.getMode() == Conversation.MODE_SINGLE;
            setToolbarHeight(toolbar, CONVERSATION_TOOLBAR_HEIGHT_DP);
            updateConversationToolbarAvatar(toolbar, conversation);
            if (conversation.getNextCounterpart() != null && conversation.hasPermanentCounterpart()) {
                if (conversation.getMode() == Conversational.MODE_MULTI) {
                    actionBar.setTitle(getString(R.string.muc_private_conversation_title, conversation.getNextCounterpart().getResource(), conversation.getName()));
                } else {
                    actionBar.setTitle(getString(R.string.secret_chat_title_no_resource, conversation.getName()));
                }
            } else {
                actionBar.setTitle(
                        conversation.withSelf()
                                ? getString(R.string.saved_messages)
                                : conversation.getName());
            }
            actionBar.setDisplayHomeAsUpEnabled(mainFragment instanceof ConversationFragment);

            final com.google.common.base.Optional<OngoingRtpSession> ongoingCall =
                    singleConversation && xmppConnectionService != null
                            ? xmppConnectionService
                                    .getJingleConnectionManager()
                                    .getOngoingRtpConnection(conversation.getContact())
                            : com.google.common.base.Optional.absent();

            if (ongoingCall.isPresent()) {
                final OngoingRtpSession session = ongoingCall.get();
                actionBar.setSubtitle(
                        session.getMedia().contains(Media.VIDEO)
                                ? R.string.ongoing_video_call
                                : R.string.ongoing_call);
                handler.postDelayed(refreshTitleRunnable, 1000L);
                ToolbarUtils.setActionBarOnClickListener(
                        toolbar, ignored -> returnToOngoingCall(session));
                styleConversationToolbarText(toolbar);
                return;
            }

            if (conversation.getMode() == Conversation.MODE_MULTI && conversation.getNextCounterpart() == null) {
                int usersCount = conversation.getMucOptions().getUserCount();
                if (usersCount > 0) {
                    actionBar.setSubtitle(getResources().getQuantityString(R.plurals.x_participants, conversation.getMucOptions().getUserCount(), conversation.getMucOptions().getUserCount()));
                } else {
                    actionBar.setSubtitle("");
                }

                handler.postDelayed(refreshTitleRunnable, 5000L);
            } else if (conversation.getMode() == Conversation.MODE_SINGLE) {
                actionBar.setSubtitle(getSingleConversationSubtitle(conversation));
            } else {
                actionBar.setSubtitle("");
                handler.removeCallbacks(refreshTitleRunnable);
            }

            styleConversationToolbarText(toolbar);
            // Set listeners after subtitle creation so avatar, title and subtitle all use the
            // existing profile/details route.
            ToolbarUtils.setActionBarOnClickListener(
                    toolbar,
                    (v) -> openConversationDetails(conversation)
            );
            configureConversationSubtitleAction(toolbar, conversation);
            return;
        }

        handler.removeCallbacks(refreshTitleRunnable);
        setChatEdgeToEdgeEnabled(true);
        applyOverviewChrome();
        setToolbarHeight(toolbar, DEFAULT_TOOLBAR_HEIGHT_DP);
        updateConversationToolbarAvatar(toolbar, null);
        actionBar.setTitle(R.string.app_name);
        actionBar.setSubtitle("");
        restoreOverviewToolbarTextStyle(toolbar);
        actionBar.setDisplayHomeAsUpEnabled(false);
        ToolbarUtils.resetActionBarOnClickListeners(toolbar);
    }

    private void configureConversationSubtitleAction(
            @NonNull final MaterialToolbar toolbar,
            @NonNull final Conversation conversation) {
        final TextView subtitle = ToolbarUtils.getSubtitleTextView(toolbar);
        if (subtitle == null) {
            return;
        }

        subtitle.setTextColor(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant));
        subtitle.setContentDescription(null);

        if (conversation.getMode() != Conversation.MODE_SINGLE || conversation.withSelf()) {
            return;
        }

        final Contact contact = conversation.getContact();
        if (contact == null || contact.showInRoster()) {
            return;
        }

        subtitle.setTextColor(
                StyledAttributes.getColor(
                        this, com.google.android.material.R.attr.colorPrimary));
        subtitle.setContentDescription(getString(R.string.add_contact));
        subtitle.setOnClickListener(v -> showAddToRosterDialog(contact));
    }

    private void returnToOngoingCall(final OngoingRtpSession session) {
        startActivity(RtpSessionActivity.createOngoingCallIntent(this, session));
    }

    private void openConversationDetails(final Conversation conversation) {
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            ConferenceDetailsActivity.open(this, conversation);
        } else {
            final Contact contact = conversation.getContact();
            if (contact.isSelf()) {
                startActivity(
                        ProfileNavigation.contextualProfileIntent(
                                this, conversation.getAccount().getUuid()));
            } else {
                openContactDetailsFromHeader(contact);
            }
        }
    }

    private void openContactDetailsFromHeader(final Contact contact) {
        final Intent intent = new Intent(this, ContactDetailsActivity.class);
        intent.setAction(ContactDetailsActivity.ACTION_VIEW_CONTACT);
        intent.putExtra(EXTRA_ACCOUNT, contact.getAccount().getJid().asBareJid().toString());
        intent.putExtra("contact", contact.getJid().toString());
        intent.putExtra(ContactDetailsActivity.EXTRA_ANIMATE_FROM_HEADER, true);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    public void verifyOtrSessionDialog(final Conversation conversation, View view) {
        if (!conversation.hasValidOtrSession() || conversation.getOtrSession().getSessionStatus() != SessionStatus.ENCRYPTED) {
            ToastCompat.makeText(this, R.string.otr_session_not_started, Toast.LENGTH_LONG).show();
            return;
        }
        if (view == null) {
            return;
        }
        PopupMenu popup = new PopupMenu(this, view);
        popup.inflate(R.menu.verification_choices);
        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == R.id.blind_trust) {
                conversation.verifyOtrFingerprint();
                xmppConnectionService.syncRosterToDisk(conversation.getAccount());
                refreshUiReal();
                return true;
            }

            Intent intent = new Intent(ConversationsActivity.this, VerifyOTRActivity.class);
            intent.setAction(VerifyOTRActivity.ACTION_VERIFY_CONTACT);
            intent.putExtra("contact", conversation.getContact().getJid().asBareJid().toString());
            intent.putExtra("counterpart", conversation.getNextCounterpart().toString());
            intent.putExtra(EXTRA_ACCOUNT, conversation.getAccount().getJid().asBareJid().toString());
            switch (menuItem.getItemId()) {
                case R.id.ask_question:
                    intent.putExtra("mode", VerifyOTRActivity.MODE_ASK_QUESTION);
                    break;
            }
            startActivity(intent);
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
            return true;
        });
        popup.show();
    }

    @Override
    public void onConversationArchived(Conversation conversation) {
        if (performRedirectIfNecessary(conversation, false)) {
            return;
        }
        final FragmentManager fragmentManager = getFragmentManager();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment) {
            QuickLoader.clear(this);
            try {
                fragmentManager.popBackStack();
            } catch (final IllegalStateException e) {
                Log.w(
                        Config.LOGTAG,
                        "state loss while popping back state after archiving conversation",
                        e);
                // this usually means activity is no longer active; meaning on the next open we will
                // run through this again
            }
            return;
        }
        final Fragment secondaryFragment =
                fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment) {
            if (((ConversationFragment) secondaryFragment).getConversation() == conversation) {
                Conversation suggestion =
                        ConversationsOverviewFragment.getSuggestion(this, conversation);
                if (suggestion != null) {
                    openConversation(suggestion, null);
                }
            }
        }
    }

    @Override
    public void onConversationsListItemUpdated() {
        Fragment fragment = getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            ((ConversationsOverviewFragment) fragment).refresh();
        }
    }

    @Override
    public void switchToConversation(Conversation conversation) {
        Log.d(Config.LOGTAG, "override");
        openConversation(conversation, null);
    }

    @Override
    public void onConversationRead(Conversation conversation, String upToUuid) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid);
        } else {
            Log.d(Config.LOGTAG, "ignoring read callback. mActivityPaused=" + mActivityPaused);
        }
    }

    @Override
    public void onAccountUpdate() {
        this.refreshUi();
        handler.post(
                () -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    invalidateOptionsMenu();
                    showDialogsIfMainIsOverview();
                });
    }

    @Override
    public void onConversationUpdate(boolean newCaps) {
        if (performRedirectIfNecessary(false)) {
            return;
        }
        if (SecureContentCryptoSessionRuntimeV1.requiresAuthentication(this)) {
            retireVisibleSecurePresentation();
            return;
        }
        refreshForNewCaps = newCaps;
        this.refreshUi();
    }

    private void retireVisibleSecurePresentation() {
        runOnUiThread(
                () -> {
                    for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
                        final Fragment fragment = getFragmentManager().findFragmentById(id);
                        if (fragment instanceof ConversationFragment) {
                            ((ConversationFragment) fragment).retireSecurePresentationState();
                        }
                    }
                });
    }

    @Override
    public void onRosterUpdate() {
        refreshForNewCaps = true;
        this.refreshUi();
    }

    @Override
    public void OnUpdateBlocklist(OnUpdateBlocklist.Status status) {
        this.refreshUi();
    }

    @Override
    public void onShowErrorToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }
}
