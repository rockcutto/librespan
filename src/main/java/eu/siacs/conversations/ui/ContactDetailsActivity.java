package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.ContactFingerprintVerification;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.OmemoTrustUxStore;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlSession;
import eu.siacs.conversations.databinding.ActivityContactDetailsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.services.AbstractQuickConversationsService;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService.OnAccountUpdate;
import eu.siacs.conversations.services.XmppConnectionService.OnRosterUpdate;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.attachments.AttachmentEntry;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.GridManager;
import eu.siacs.conversations.ui.util.JidDialog;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.IrregularUnicodeDetector;
import eu.siacs.conversations.utils.PhoneNumberUtilWrapper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XEP0392Helper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.Media;

public class ContactDetailsActivity extends OmemoActivity
        implements OnAccountUpdate,
        OnRosterUpdate,
        OnUpdateBlocklist,
        OnKeyStatusUpdated,
        OnMediaLoaded {
    public static final String ACTION_VIEW_CONTACT = "view_contact";
    public static final String EXTRA_ANIMATE_FROM_HEADER = "animate_from_header";
    private static final int REQUEST_START_AUDIO_CALL = 0x213;
    private static final int REQUEST_START_VIDEO_CALL = 0x214;
    ActivityContactDetailsBinding binding;
    private MediaAdapter mMediaAdapter;

    private Contact contact;
    private final DialogInterface.OnClickListener removeFromRoster =
            new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    xmppConnectionService.deleteContactOnServer(contact);
                }
            };
    private Jid accountJid;
    private Jid contactJid;
    private boolean showDynamicTags = false;
    private boolean showLastSeen = false;
    private boolean showInactiveOmemo = false;
    private String messageFingerprint;
    private OmemoTrustUxStore omemoTrustUxStore;
    private int qrSelectedDeviceId;
    private String qrSelectedFingerprint;
    private String qrSelectedAccountUuid;

    @Override
    public void onRosterUpdate() {
        refreshUi();
    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    @Override
    public void OnUpdateBlocklist(final Status status) {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        invalidateOptionsMenu();
        populateView();
    }

    @Override
    protected String getShareableUri(boolean http) {
        return "xmpp:" + Uri.encode(contact.getJid().asBareJid().toString(), "@/+");
    }

    private void animateHeaderEntry() {
        final View card = findViewById(R.id.contact_identity_card);
        if (card == null) {
            return;
        }
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(140L).start();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showInactiveOmemo =
                savedInstanceState != null
                        && savedInstanceState.getBoolean("show_inactive_omemo", false);
        if (savedInstanceState != null) {
            qrSelectedDeviceId = savedInstanceState.getInt("qr_selected_device_id");
            qrSelectedFingerprint = savedInstanceState.getString("qr_selected_fingerprint");
            qrSelectedAccountUuid = savedInstanceState.getString("qr_selected_account_uuid");
        }
        if (getIntent().getAction().equals(ACTION_VIEW_CONTACT)) {
            try {
                this.accountJid = Jid.of(getIntent().getExtras().getString(EXTRA_ACCOUNT));
            } catch (final IllegalArgumentException ignored) {
            }
            try {
                this.contactJid = Jid.of(getIntent().getExtras().getString("contact"));
            } catch (final IllegalArgumentException ignored) {
            }
        }
        this.messageFingerprint = getIntent().getStringExtra("fingerprint");
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_contact_details);
        if (getIntent().getBooleanExtra(EXTRA_ANIMATE_FROM_HEADER, false)) {
            animateHeaderEntry();
        }
        this.omemoTrustUxStore = new OmemoTrustUxStore(this);

        binding.detailsContactName.setOnClickListener(v -> editContactDisplayName());
        binding.contactHeaderShare.setOnClickListener(v -> showContactShareActions());
        binding.contactBlockAction.setOnClickListener(
                v -> {
                    if (contact != null) {
                        BlockContactDialog.show(this, contact);
                    }
                });
        binding.contactDeleteAction.setOnClickListener(v -> showDeleteContactDialog());

        findViewById(R.id.contact_action_chat).setOnClickListener(v -> openContactChat());
        findViewById(R.id.contact_action_call).setOnClickListener(v -> startContactCall(false));
        final View soundAction = findViewById(R.id.contact_action_sound);
        soundAction.setOnClickListener(v -> toggleContactNotifications());

        binding.showInactiveDevices.setOnClickListener(
                v -> {
                    showInactiveOmemo = !showInactiveOmemo;
                    populateView();
                });
        binding.addContactButton.setOnClickListener(
                v -> {
                    if (contact == null || xmppConnectionService == null) {
                        return;
                    }
                    if (contact.showInRoster()
                            && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                        xmppConnectionService.sendPresencePacket(
                                contact.getAccount(),
                                xmppConnectionService
                                        .getPresenceGenerator()
                                        .sendPresenceUpdatesTo(contact));
                        contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
                        xmppConnectionService.syncRosterToDisk(contact.getAccount());
                        populateView();
                    } else {
                        showAddToRosterDialog(contact);
                    }
                });

        mMediaAdapter = new MediaAdapter(this, R.dimen.media_size);
        mMediaAdapter.setOnAttachmentClickListener(this::openContactMediaAttachment);
        this.binding.media.setAdapter(mMediaAdapter);
        GridManager.setupLayoutManager(this, this.binding.media, R.dimen.media_size);
    }

    private void openContactMediaAttachment(final Attachment attachment) {
        if (attachment == null || contact == null || xmppConnectionService == null) {
            return;
        }
        final Account account = contact.getAccount();
        final String selectedMessageUuid = attachment.getUuid().toString();

        xmppConnectionService.getAttachmentPage(
                account.getUuid(),
                contact.getJid().asBareJid(),
                AttachmentEntry.Category.MEDIA,
                null,
                12,
                page -> {
                    AttachmentEntry selected = null;
                    for (final AttachmentEntry entry : page.entries) {
                        if (selectedMessageUuid.equals(entry.messageUuid)) {
                            selected = entry;
                            break;
                        }
                    }
                    if (selected == null) {
                        openSingleContactMediaMessage(selectedMessageUuid);
                        return;
                    }

                    final ArrayList<AttachmentEntry> sequence = new ArrayList<>();
                    for (final AttachmentEntry entry : page.entries) {
                        if (selected.conversationUuid.equals(entry.conversationUuid)
                                && (entry.isImage() || entry.isVideo())) {
                            sequence.add(entry);
                        }
                    }
                    if (sequence.isEmpty()) {
                        openSingleContactMediaMessage(selectedMessageUuid);
                        return;
                    }

                    final Message[] resolved = new Message[sequence.size()];
                    final AtomicInteger remaining = new AtomicInteger(sequence.size());
                    for (int position = 0; position < sequence.size(); position++) {
                        final int targetPosition = position;
                        final AttachmentEntry entry = sequence.get(position);
                        xmppConnectionService.loadAttachmentMessage(
                                entry.conversationUuid,
                                entry.messageUuid,
                                message -> {
                                    resolved[targetPosition] = message;
                                    if (remaining.decrementAndGet() != 0) {
                                        return;
                                    }
                                    runOnUiThread(
                                            () -> {
                                                if (isFinishing() || isDestroyed()) {
                                                    return;
                                                }
                                                final ArrayList<Message> album = new ArrayList<>();
                                                int initialPosition = -1;
                                                for (int index = 0; index < resolved.length; index++) {
                                                    final Message candidate = resolved[index];
                                                    if (candidate == null) {
                                                        continue;
                                                    }
                                                    if (sequence.get(index).messageUuid.equals(
                                                            selectedMessageUuid)) {
                                                        initialPosition = album.size();
                                                    }
                                                    album.add(candidate);
                                                }
                                                if (album.isEmpty() || initialPosition < 0) {
                                                    openSingleContactMediaMessage(
                                                            selectedMessageUuid);
                                                    return;
                                                }
                                                MediaAlbumActivity.launch(
                                                        this, album, initialPosition);
                                            });
                                });
                    }
                });
    }

    private void openSingleContactMediaMessage(final String messageUuid) {
        final Conversation conversation = findContactConversation(false);
        if (conversation == null || xmppConnectionService == null) {
            runOnUiThread(() -> MediaBrowserActivity.launch(this, contact));
            return;
        }
        xmppConnectionService.loadAttachmentMessage(
                conversation.getUuid(),
                messageUuid,
                message ->
                        runOnUiThread(
                                () -> {
                                    if (isFinishing() || isDestroyed()) {
                                        return;
                                    }
                                    if (message != null) {
                                        MediaAlbumActivity.launch(
                                                this,
                                                Collections.singletonList(message),
                                                0);
                                    } else {
                                        MediaBrowserActivity.launch(this, contact);
                                    }
                                }));
    }

    private void toggleContactNotifications() {
        final Conversation conversation = findContactConversation(true);
        if (conversation == null) {
            return;
        }
        conversation.setMutedTill(conversation.isMuted() ? 0 : Long.MAX_VALUE);
        xmppConnectionService.updateConversation(conversation);
        updateContactSoundAction();
    }

    private Conversation findContactConversation(final boolean createIfMissing) {
        if (contact == null || xmppConnectionService == null) {
            return null;
        }
        for (final Conversation conversation : xmppConnectionService.getConversations()) {
            if (conversation.getMode() == Conversation.MODE_SINGLE
                    && conversation.getNextCounterpart() == null
                    && conversation.getAccount() == contact.getAccount()
                    && conversation.getJid().asBareJid().equals(contact.getJid().asBareJid())) {
                return conversation;
            }
        }
        if (!createIfMissing) {
            return null;
        }
        return xmppConnectionService.findOrCreateConversation(
                contact.getAccount(),
                contact.getJid().asBareJid(),
                null,
                false,
                false,
                true,
                null);
    }

    private void updateContactSoundAction() {
        final Conversation conversation = findContactConversation(false);
        final boolean enabled = conversation == null || !conversation.isMuted();
        final View soundAction = findViewById(R.id.contact_action_sound);
        soundAction.setSelected(enabled);
        final int soundLabel =
                enabled ? R.string.contact_card_sound_on : R.string.contact_card_sound_off;
        ViewCompat.setStateDescription(soundAction, getString(soundLabel));
        ((android.widget.ImageView) findViewById(R.id.contact_action_sound_icon))
                .setImageResource(
                        enabled
                                ? R.drawable.ic_notifications_24dp
                                : R.drawable.ic_notifications_off_24dp);
        ((TextView) findViewById(R.id.contact_action_sound_label)).setText(soundLabel);
    }

    private void openContactChat() {
        if (contact == null || xmppConnectionService == null) {
            return;
        }
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(),
                        contact.getJid().asBareJid(),
                        null,
                        false,
                        false,
                        true,
                        null);
        switchToConversation(conversation);
    }

    private void startContactCall(final boolean video) {
        if (contact == null || xmppConnectionService == null) {
            return;
        }
        if (mUseTor || contact.getAccount().isOnion()) {
            Toast.makeText(this, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show();
            return;
        }

        final ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (video) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        final ArrayList<String> missingPermissions = new ArrayList<>();
        for (final String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            requestPermissions(
                    missingPermissions.toArray(new String[0]),
                    video ? REQUEST_START_VIDEO_CALL : REQUEST_START_AUDIO_CALL);
            return;
        }

        placeContactCall(video);
    }

    private void placeContactCall(final boolean video) {
        if (contact == null || xmppConnectionService == null) {
            return;
        }
        if (xmppConnectionService.getJingleConnectionManager().isBusy()) {
            Toast.makeText(this, R.string.only_one_call_at_a_time, Toast.LENGTH_LONG).show();
            return;
        }

        CallIntegrationConnectionService.placeCall(
                xmppConnectionService,
                contact.getAccount(),
                contact.getJid().asBareJid(),
                video
                        ? ImmutableSet.of(Media.AUDIO, Media.VIDEO)
                        : ImmutableSet.of(Media.AUDIO));
    }

    private void editContactDisplayName() {
        if (contact == null || xmppConnectionService == null || !contact.showInRoster()) {
            return;
        }

        final Uri systemAccount = contact.getSystemAccount();
        if (systemAccount != null) {
            final Intent intent = new Intent(Intent.ACTION_EDIT);
            intent.setDataAndType(systemAccount, Contacts.CONTENT_ITEM_TYPE);
            intent.putExtra("finishActivityOnSaveCompleted", true);
            try {
                startActivity(intent);
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(
                                ContactDetailsActivity.this,
                                R.string.no_application_found_to_view_contact,
                                Toast.LENGTH_SHORT)
                        .show();
            }
            return;
        }

        quickEdit(
                contact.getServerName(),
                R.string.action_edit_contact,
                value -> {
                    contact.setServerName(value.trim());
                    xmppConnectionService.pushContactToServer(contact);
                    refreshUi();
                    return null;
                },
                true);
    }

    private void showContactShareActions() {
        if (contact == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.contact_card_share_title)
                .setItems(
                        new CharSequence[] {
                                getString(R.string.contact_card_share_xmpp),
                                getString(R.string.contact_card_show_qr)
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

    private void showDeleteContactDialog() {
        if (contact == null || !contact.showInRoster()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setNegativeButton(getString(R.string.cancel), null)
                .setTitle(getString(R.string.action_delete_contact))
                .setMessage(
                        JidDialog.style(
                                this,
                                R.string.remove_contact_text,
                                contact.getJid().toString()))
                .setPositiveButton(getString(R.string.delete), removeFromRoster)
                .create()
                .show();
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        savedInstanceState.putBoolean("show_inactive_omemo", showInactiveOmemo);
        savedInstanceState.putInt("qr_selected_device_id", qrSelectedDeviceId);
        savedInstanceState.putString("qr_selected_fingerprint", qrSelectedFingerprint);
        savedInstanceState.putString("qr_selected_account_uuid", qrSelectedAccountUuid);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        } else {
            final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            this.showDynamicTags = preferences.getBoolean(SettingsActivity.SHOW_DYNAMIC_TAGS, getResources().getBoolean(R.bool.show_dynamic_tags));
            this.showLastSeen = preferences.getBoolean("show_contact_status", getResources().getBoolean(R.bool.show_contact_status));
        }
        binding.mediaWrapper.setVisibility(View.GONE);
        mMediaAdapter.setAttachments(Collections.emptyList());
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length == 0) {
            return;
        }


        if (requestCode == REQUEST_START_AUDIO_CALL
                || requestCode == REQUEST_START_VIDEO_CALL) {
            boolean allGranted = true;
            String firstDenied = null;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    if (i < permissions.length) {
                        firstDenied = permissions[i];
                    }
                    break;
                }
            }

            if (allGranted) {
                placeContactCall(requestCode == REQUEST_START_VIDEO_CALL);
            } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                Toast.makeText(this, R.string.no_camera_permission, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.no_microphone_permission, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        // Contact actions are represented directly in the Material 3 card.
        return false;
    }

    private void populateView() {
        if (contact == null) {
            return;
        }
        invalidateOptionsMenu();
        binding.detailsContactName.setText(contact.getDisplayName());

        updateContactSoundAction();

        final XmppConnection contactConnection = contact.getAccount().getXmppConnection();
        final boolean canBlock =
                contactConnection != null && contactConnection.getFeatures().blocking();
        binding.contactBlockAction.setVisibility(canBlock ? View.VISIBLE : View.GONE);
        if (canBlock) {
            binding.contactBlockAction.setText(
                    contact.isBlocked()
                            ? R.string.action_unblock_contact
                            : R.string.action_block_contact);
        }
        binding.contactDeleteAction.setVisibility(
                contact.showInRoster() ? View.VISIBLE : View.GONE);
        binding.contactAdminActions.setVisibility(
                canBlock || contact.showInRoster() ? View.VISIBLE : View.GONE);
        if (contact.showInRoster()) {
            final boolean hasPendingPresenceRequest =
                    contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
            binding.addContactButton.setVisibility(
                    hasPendingPresenceRequest ? View.VISIBLE : View.GONE);
            if (hasPendingPresenceRequest) {
                binding.addContactButton.setText(R.string.allow_presence_subscription);
            }

            List<String> statusMessages = contact.getPresences().getStatusMessages();
            if (statusMessages.size() == 0) {
                binding.statusMessage.setVisibility(View.GONE);
            } else if (statusMessages.size() == 1) {
                final String message = statusMessages.get(0);
                binding.statusMessage.setVisibility(View.VISIBLE);
                final Spannable span = new SpannableString(message);
                if (Emoticons.isOnlyEmoji(message)) {
                    span.setSpan(
                            new RelativeSizeSpan(2.0f),
                            0,
                            message.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                binding.statusMessage.setText(span);
            } else {
                StringBuilder builder = new StringBuilder();
                binding.statusMessage.setVisibility(View.VISIBLE);
                int s = statusMessages.size();
                for (int i = 0; i < s; ++i) {
                    builder.append(statusMessages.get(i));
                    if (i < s - 1) {
                        builder.append("\n");
                    }
                }
                binding.statusMessage.setText(builder);
            }
        } else {
            binding.addContactButton.setVisibility(View.VISIBLE);
            binding.addContactButton.setText(R.string.add_contact);
            binding.statusMessage.setVisibility(View.GONE);
        }

        if (contact.isBlocked() && !this.showDynamicTags) {
            binding.detailsLastseen.setVisibility(View.VISIBLE);
            binding.detailsLastseen.setText(R.string.contact_blocked);
        } else {
            if (showLastSeen
                    && contact.getLastseen() > 0
                    && contact.getPresences().allOrNonSupport(Namespace.IDLE)) {
                binding.detailsLastseen.setVisibility(View.VISIBLE);
                binding.detailsLastseen.setText(
                        UIHelper.lastseen(
                                getApplicationContext(),
                                contact.isActive(),
                                contact.getLastseen(), false));
            } else {
                binding.detailsLastseen.setVisibility(View.GONE);
            }
        }

        binding.detailsContactjid.setText(IrregularUnicodeDetector.style(this, contact.getJid()));
        binding.detailsContactjid.setOnClickListener(v -> ShareUtil.copyJidToClipboard(ContactDetailsActivity.this, contact.getJid()));

        final String account = contact.getAccount().getJid().asBareJid().toString();
        final boolean showAccountContext =
                xmppConnectionService != null && xmppConnectionService.getAccounts().size() > 1;
        binding.detailsAccount.setVisibility(showAccountContext ? View.VISIBLE : View.GONE);
        if (showAccountContext) {
            binding.detailsAccount.setText(getString(R.string.using_account, account));
        }
        AvatarWorkerTask.loadAvatar(
                contact, binding.detailsContactBadge, R.dimen.publish_avatar_size);
        binding.detailsContactBadge.setOnClickListener(this::onAvatarClicked);
        binding.presenceIndicator.setStatus(contact);

        binding.detailsContactKeys.removeAllViews();
        boolean hasKeys = false;
        int activeDevices = 0;
        int attentionDevices = 0;
        int changedDevices = 0;
        final LayoutInflater inflater = getLayoutInflater();
        final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
        if (Config.supportOmemo() && axolotlService != null) {
            final Collection<XmppAxolotlSession> sessions =
                    axolotlService.findSessionsForContact(contact);
            boolean anyActive = false;
            for (XmppAxolotlSession session : sessions) {
                anyActive = session.getTrust().isActive();
                if (anyActive) {
                    break;
                }
            }
            boolean skippedInactive = false;
            boolean showsInactive = false;
            for (final XmppAxolotlSession session : sessions) {
                final FingerprintStatus trust = session.getTrust();
                if (trust.isCompromised()) {
                    continue;
                }
                hasKeys = true;
                final OmemoTrustUxStore.State uxState = omemoTrustUxStore.observe(
                        contact.getAccount(), contact.getJid().asBareJid(), session);
                if (trust.isActive()) {
                    activeDevices++;
                    if (uxState == OmemoTrustUxStore.State.VERIFIED) {
                    } else {
                        attentionDevices++;
                        if (uxState == OmemoTrustUxStore.State.CHANGED) {
                            changedDevices++;
                        }
                    }
                } else if (anyActive) {
                    if (showInactiveOmemo) {
                        showsInactive = true;
                    } else {
                        skippedInactive = true;
                        continue;
                    }
                }
                final boolean highlight =
                        messageFingerprint != null && messageFingerprint.equals(session.getFingerprint());
                addContactFingerprintRow(
                        binding.detailsContactKeys,
                        contact.getAccount(),
                        contact.getJid().asBareJid(),
                        session,
                        uxState,
                        highlight,
                        omemoTrustUxStore);
            }

            if (activeDevices == 0) {
                binding.encryptionStatus.setText(R.string.trust_ux_summary_no_devices);
            } else {
                binding.encryptionStatus.setText(
                        getResources().getQuantityString(
                                R.plurals.trust_ux_summary_omemo_devices,
                                activeDevices,
                                activeDevices));
            }

            if (changedDevices > 0) {
                binding.unverifiedWarningText.setText(
                        axolotlService.hasVerifiedKeys(contact.getJid().asBareJid().toString())
                                ? R.string.trust_ux_warning_changed_blocked
                                : R.string.trust_ux_warning_changed);
                applyTrustWarningColors(true);
                binding.unverifiedWarning.setVisibility(View.VISIBLE);
            } else if (attentionDevices > 0) {
                binding.unverifiedWarningText.setText(
                        axolotlService.hasVerifiedKeys(contact.getJid().asBareJid().toString())
                                ? R.string.trust_ux_warning_unverified_blocked
                                : R.string.trust_ux_warning_unverified);
                applyTrustWarningColors(false);
                binding.unverifiedWarning.setVisibility(View.VISIBLE);
            } else {
                binding.unverifiedWarning.setVisibility(View.GONE);
            }

            if (showsInactive || skippedInactive) {
                binding.showInactiveDevices.setText(
                        showsInactive
                                ? R.string.hide_inactive_devices
                                : R.string.show_inactive_devices);
                binding.showInactiveDevices.setVisibility(View.VISIBLE);
            } else {
                binding.showInactiveDevices.setVisibility(View.GONE);
            }
        } else {
            binding.showInactiveDevices.setVisibility(View.GONE);
            binding.unverifiedWarning.setVisibility(View.GONE);
            binding.encryptionStatus.setText(R.string.trust_ux_summary_unavailable);
        }
        final boolean omemoAvailable = Config.supportOmemo() && axolotlService != null;
        // QR verification is launched from a concrete device row. A contact QR can contain
        // multiple identities, so a contact-wide action cannot safely select a trust target.
        binding.scanButton.setVisibility(View.GONE);
        // Keep the security card visible even when no local sessions exist yet. This is
        // especially important after restore/reinstall: the user must see that device keys are
        // being fetched instead of seeing the whole OMEMO section disappear.
        binding.keysWrapper.setVisibility(omemoAvailable ? View.VISIBLE : View.GONE);

        final List<ListItem.Tag> tagList = contact.getTags(this);
        final boolean hasMetaTags =
                contact.isBlocked() || contact.getShownStatus() != Presence.Status.OFFLINE;
        if ((tagList.isEmpty() && !hasMetaTags) || !this.showDynamicTags) {
            binding.tags.setVisibility(View.GONE);
        } else {
            binding.tags.setVisibility(View.VISIBLE);
            if (binding.tags.getChildCount() > 0) {
                binding.tags.removeViews(1, binding.tags.getChildCount() - 1);
            }
            final ImmutableList.Builder<Integer> viewIdBuilder = new ImmutableList.Builder<>();
            for (final ListItem.Tag tag : tagList) {
                final String name = tag.getName();
                final TextView tv =
                        (TextView) inflater.inflate(R.layout.item_tag, binding.tags, false);
                tv.setText(name);
                tv.setBackgroundTintList(
                        ColorStateList.valueOf(
                                MaterialColors.harmonizeWithPrimary(
                                        this, XEP0392Helper.rgbFromNick(name))));
                final int id = ViewCompat.generateViewId();
                tv.setId(id);
                viewIdBuilder.add(id);
                binding.tags.addView(tv);
            }
            if (contact.isBlocked()) {
                final TextView tv =
                        (TextView) inflater.inflate(R.layout.item_tag, binding.tags, false);
                tv.setText(R.string.blocked);
                tv.setBackgroundTintList(
                        ColorStateList.valueOf(
                                MaterialColors.harmonizeWithPrimary(
                                        tv.getContext(),
                                        ContextCompat.getColor(
                                                tv.getContext(), R.color.grey800))));
                final int id = ViewCompat.generateViewId();
                tv.setId(id);
                viewIdBuilder.add(id);
                binding.tags.addView(tv);
            } else {
                final Presence.Status status = contact.getShownStatus();
                if (status != Presence.Status.OFFLINE) {
                    final TextView tv =
                            (TextView) inflater.inflate(R.layout.item_tag, binding.tags, false);
                    UIHelper.setStatus(tv, status);
                    final int id = ViewCompat.generateViewId();
                    tv.setId(id);
                    viewIdBuilder.add(id);
                    binding.tags.addView(tv);
                }
            }
        }
    }

    private void applyTrustWarningColors(final boolean changedKey) {
        final int container =
                MaterialColors.getColor(
                        binding.unverifiedWarning,
                        changedKey
                                ? com.google.android.material.R.attr.colorErrorContainer
                                : com.google.android.material.R.attr.colorTertiaryContainer);
        final int content =
                MaterialColors.getColor(
                        binding.unverifiedWarning,
                        changedKey
                                ? com.google.android.material.R.attr.colorOnErrorContainer
                                : com.google.android.material.R.attr.colorOnTertiaryContainer);
        final int icon =
                MaterialColors.getColor(
                        binding.unverifiedWarning,
                        changedKey
                                ? com.google.android.material.R.attr.colorError
                                : com.google.android.material.R.attr.colorTertiary);
        binding.unverifiedWarning.setCardBackgroundColor(container);
        binding.unverifiedWarningText.setTextColor(content);
        binding.unverifiedWarningIcon.setImageTintList(ColorStateList.valueOf(icon));
    }

    private void onAvatarClicked(final View view) {
        final var contact = this.contact;
        if (contact == null) {
            return;
        }
        final var avatar = contact.getAvatar();
        if (avatar == null) {
            return;
        }
        final var intent = new Intent(this, ViewProfilePictureActivity.class);
        intent.setData(Uri.fromParts("avatar", avatar.getFilename(), null));
        intent.putExtra(ViewProfilePictureActivity.EXTRA_DISPLAY_NAME, contact.getDisplayName());
        intent.putExtra(ViewProfilePictureActivity.EXTRA_ACCOUNT_UUID, contact.getAccount().getUuid());
        startActivity(intent);
    }

    public void onBackendConnected() {
        if (accountJid != null && contactJid != null) {
            Account account = xmppConnectionService.findAccountByJid(accountJid);
            if (account == null) {
                return;
            }
            this.contact = account.getRoster().getContact(contactJid);
            final AxolotlService axolotlService = account.getAxolotlService();
            if (Config.supportOmemo() && axolotlService != null) {
                axolotlService.refreshContactDevices(this.contact);
            }
            if (mPendingFingerprintVerificationUri != null) {
                processFingerprintVerification(mPendingFingerprintVerificationUri);
                mPendingFingerprintVerificationUri = null;
            }

            mMediaAdapter.setAccountUuid(account.getUuid());
            final int limit = GridManager.getCurrentColumnCount(this.binding.media);
            xmppConnectionService.getAttachments(
                    account, contact.getJid().asBareJid(), limit, this);
            this.binding.showMedia.setOnClickListener(
                    (v) -> MediaBrowserActivity.launch(this, contact));
            populateView();
        }
    }

    @Override
    public void onKeyStatusUpdated(AxolotlService.FetchStatus report) {
        refreshUi();
    }

    @Override
    protected void processFingerprintVerification(XmppUri uri) {
        try {
            if (contact == null || uri.getJid() == null || !uri.hasFingerprints()
                    || qrSelectedAccountUuid == null || qrSelectedDeviceId <= 0
                    || qrSelectedFingerprint == null
                    || !qrSelectedAccountUuid.equals(contact.getAccount().getUuid())) {
                showInvalidQr();
                return;
            }
            final Jid bareJid = contact.getJid().asBareJid();
            final AxolotlService axolotl = contact.getAccount().getAxolotlService();
            if (axolotl == null) {
                showInvalidQr();
                return;
            }
            final List<XmppUri.Fingerprint> targets = new ArrayList<>();
            for (final XmppUri.Fingerprint fp : uri.getFingerprints()) {
                if (fp.type != XmppUri.FingerprintType.OMEMO) {
                    continue;
                }
                if (fp.getDeviceId() != qrSelectedDeviceId) {
                    continue;
                }
                if (!ContactFingerprintVerification.matchesTarget(
                        bareJid.toString(), uri.getJid().asBareJid().toString(),
                        fp.getDeviceId(), fp.fingerprint,
                        qrSelectedDeviceId, qrSelectedFingerprint)
                        || !axolotl.matchesContactFingerprint(
                        bareJid, fp.getDeviceId(),
                        ContactFingerprintVerification.normalizedQrFingerprint(fp.fingerprint))) {
                    showInvalidQr();
                    return;
                }
                targets.add(fp);
            }
            if (targets.isEmpty()) {
                showInvalidQr();
                return;
            }
            for (final XmppUri.Fingerprint fp : targets) {
                final String fingerprint =
                        ContactFingerprintVerification.normalizedQrFingerprint(fp.fingerprint);
                if (!axolotl.verifyContactFingerprint(bareJid, fp.getDeviceId(), fingerprint)) {
                    Toast.makeText(this, R.string.could_not_verify_fingerprint, Toast.LENGTH_SHORT).show();
                    refreshUi();
                    return;
                }
                omemoTrustUxStore.markVerified(
                        contact.getAccount(), bareJid, fp.getDeviceId(), fingerprint);
            }
            Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show();
            refreshUi();
        } finally {
            clearQrSelection();
        }
    }

    @Override
    protected void scanContactFingerprint(Account account, Jid contactJid,
                                          int deviceId, String fingerprint) {
        qrSelectedAccountUuid = account.getUuid();
        qrSelectedDeviceId = deviceId;
        qrSelectedFingerprint = fingerprint;
        ScanActivity.scan(this);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == ScanActivity.REQUEST_SCAN_QR_CODE && resultCode != RESULT_OK) {
            clearQrSelection();
        }
    }

    private void clearQrSelection() {
        qrSelectedDeviceId = 0;
        qrSelectedFingerprint = null;
        qrSelectedAccountUuid = null;
    }

    private void showInvalidQr() {
        Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMediaLoaded(List<Attachment> attachments) {
        runOnUiThread(
                () -> {
                    int limit = GridManager.getCurrentColumnCount(binding.media);
                    mMediaAdapter.setAttachments(
                            attachments.subList(0, Math.min(limit, attachments.size())));
                    binding.mediaWrapper.setVisibility(
                            attachments.size() > 0 ? View.VISIBLE : View.GONE);
                });
    }
}
