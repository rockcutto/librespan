package eu.siacs.conversations.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.DatabaseBackendProvider;
import eu.siacs.conversations.services.ShortcutService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.ConversationAdapter;
import eu.siacs.conversations.xmpp.Jid;

public class ShareWithActivity extends XmppActivity
        implements XmppConnectionService.OnConversationUpdate {

    private static final int REQUEST_STORAGE_PERMISSION = 0x733f32;
    private static final int MAXIMUM_SHARE_URIS = 32;
    private Conversation mPendingConversation = null;

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    private static class Share {
        public String type;
        ArrayList<Uri> uris = new ArrayList<>();
        public String account;
        public String contact;
        public String text;
        public boolean asQuote = false;
    }

    private Share share;

    private static final int REQUEST_START_NEW_CONVERSATION = 0x0501;
    private ConversationAdapter mAdapter;
    private final List<Conversation> mConversations = new ArrayList<>();

    private final List<ListItem.Tag> tags = new ArrayList<>();


    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_START_NEW_CONVERSATION && resultCode == RESULT_OK) {
            share.contact = data.getStringExtra("contact");
            share.account = data.getStringExtra(EXTRA_ACCOUNT);
        }
        if (xmppConnectionServiceBound
                && share != null
                && share.contact != null
                && share.account != null) {
            share();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            @NonNull final String[] permissions,
            @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_STORAGE_PERMISSION) {
                    if (this.mPendingConversation != null) {
                        share(this.mPendingConversation);
                    } else {
                        Log.d(Config.LOGTAG, "unable to find stored conversation");
                    }
                }
            } else {
                Toast.makeText(
                                this,
                                getString(
                                        R.string.no_storage_permission,
                                        getString(R.string.app_name)),
                                Toast.LENGTH_SHORT)
                        .show();
            }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_with);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setHomeButtonEnabled(false);
        }

        setTitle(getString(R.string.title_activity_sharewith));

        RecyclerView mListView = findViewById(R.id.choose_conversation_list);
        mAdapter = new ConversationAdapter(this, this.mConversations, this.tags);
        mListView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        mListView.setAdapter(mAdapter);
        mAdapter.setConversationClickListener((view, conversation) -> share(conversation));
        final var intent = getIntent();
        final var shortcutId = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID);
        this.share = new Share();
        if (shortcutId != null) {
            final var conversation = shortcutIdToConversation(shortcutId);
            if (conversation != null) {
                // we have everything we need. Jump into chat
                if (populateShare(intent)) {
                    share(conversation);
                } else {
                    finish();
                }
            }
        }
    }

    private String shortcutIdToConversation(final String shortcutId) {
        final var shortcut =
                Iterables.tryFind(
                        ShortcutManagerCompat.getDynamicShortcuts(this),
                        si -> si.getId().equals(shortcutId));
        if (shortcut.isPresent()) {
            final var extras = shortcut.get().getExtras();
            if (extras == null) {
                return shortcutIdToConversationFallback(shortcutId);
            } else {
                final var conversation = extras.getString(ConversationsActivity.EXTRA_CONVERSATION);
                if (Strings.isNullOrEmpty(conversation)) {
                    return shortcutIdToConversationFallback(shortcutId);
                } else {
                    return conversation;
                }
            }
        } else {
            return shortcutIdToConversationFallback(shortcutId);
        }
    }

    private String shortcutIdToConversationFallback(final String shortcutId) {
        final var parts =
                Splitter.on(ShortcutService.ID_SEPARATOR).limit(2).splitToList(shortcutId);
        if (parts.size() == 2) {
            final var account = Jid.of(parts.get(0));
            final var jid = Jid.of(parts.get(1));
            final var database = DatabaseBackendProvider.getInstance(getApplicationContext());
            return database.findConversationUuid(account, jid);
        } else {
            return null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.share_with, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_add:
                final Intent intent =
                        new Intent(getApplicationContext(), ChooseContactActivity.class);
                intent.putExtra("direct_search", true);
                startActivityForResult(intent, REQUEST_START_NEW_CONVERSATION);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();
        final Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        if (!populateShare(intent)) {
            finish();
            return;
        }
        if (xmppConnectionServiceBound) {
            xmppConnectionService.populateWithOrderedConversations(
                    mConversations, this.share.uris.isEmpty(), false);
        }
    }

    private boolean populateShare(final Intent intent) {
        this.share.uris.clear();
        this.share.type = null;
        this.share.text = null;
        this.share.asQuote = false;

        final String type = intent.getType();
        final String action = intent.getAction();
        final Uri data = intent.getData();
        if (Intent.ACTION_SEND.equals(action)) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            final boolean asQuote =
                    intent.getBooleanExtra(ConversationsActivity.EXTRA_AS_QUOTE, false);

            if (data != null && "geo".equals(data.getScheme())) {
                this.share.uris.add(data);
                return true;
            }
            if (uri != null) {
                if (Strings.isNullOrEmpty(type) || !isReadableContentUri(uri)) {
                    return rejectShare();
                }
                this.share.uris.add(uri);
                this.share.type = type;
                this.share.text = text;
                return true;
            }
            if (text != null) {
                this.share.text = text;
                this.share.asQuote = asQuote;
                return true;
            }
            return rejectShare();
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            this.share.text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (Strings.isNullOrEmpty(type)
                    || uris == null
                    || uris.isEmpty()
                    || uris.size() > MAXIMUM_SHARE_URIS) {
                return rejectShare();
            }

            boolean skippedUri = false;
            for (final Uri uri : uris) {
                if (isReadableContentUri(uri)) {
                    this.share.uris.add(uri);
                } else {
                    skippedUri = true;
                }
            }
            if (this.share.uris.isEmpty()) {
                return rejectShare();
            }
            if (skippedUri) {
                showShareError();
            }
            this.share.type = type;
            return true;
        }
        return rejectShare();
    }

    private boolean isReadableContentUri(final Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return false;
        }
        try (final ParcelFileDescriptor ignored =
                getContentResolver().openFileDescriptor(uri, "r")) {
            return ignored != null;
        } catch (final IOException | SecurityException e) {
            return false;
        }
    }

    private boolean rejectShare() {
        showShareError();
        return false;
    }

    private void showShareError() {
        Toast.makeText(this, R.string.sharing_application_not_grant_permission, Toast.LENGTH_SHORT)
                .show();
    }

    @Override
    protected void onBackendConnected() {
        if (xmppConnectionServiceBound
                && share != null
                && ((share.contact != null && share.account != null))) {
            share();
            return;
        }
        refreshUiReal();
    }

    private void share() {
        final Conversation conversation;
        Account account;
        try {
            account = xmppConnectionService.findAccountByJid(Jid.of(share.account));
        } catch (final IllegalArgumentException e) {
            account = null;
        }
        if (account == null) {
            return;
        }

            try {
                conversation = xmppConnectionService.findOrCreateConversation(account, Jid.of(share.contact), null, false, false, true, null);
            } catch (final IllegalArgumentException e) {
                return;
            }
        share(conversation);
    }

    private void share(final Conversation conversation) {
        if (!share.uris.isEmpty() && !hasStoragePermission(REQUEST_STORAGE_PERMISSION)) {
            mPendingConversation = conversation;
            return;
        }
        share(conversation.getUuid());
    }

    private void share(final String conversation) {
        final Intent intent = new Intent(this, ConversationsActivity.class);
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation);
        if (!share.uris.isEmpty()) {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, share.uris);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (share.type != null) {
                intent.putExtra(ConversationsActivity.EXTRA_TYPE, share.type);
            }
            if (share.text != null) {
                intent.putExtra(Intent.EXTRA_TEXT, share.text);
            }
        } else if (share.text != null) {
            intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
            intent.putExtra(Intent.EXTRA_TEXT, share.text);
            intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, share.asQuote);
        }
        try {
            startActivity(intent);
        } catch (final SecurityException e) {
            Toast.makeText(
                            this,
                            R.string.sharing_application_not_grant_permission,
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        finish();
    }

    public void refreshUiReal() {
        //TODO inject desired order to not resort on refresh
        xmppConnectionService.populateWithOrderedConversations(mConversations, this.share != null && this.share.uris.size() == 0, false);
        refreshTags();
        mAdapter.notifyDataSetChanged();
    }

    private void refreshTags() {
        this.mAdapter.setGroupingEnabled(xmppConnectionService.getPreferences().getBoolean("conversationsGroupByTags", false));

        if (mAdapter.isGroupingEnabled()) {
            List<ListItem.Tag> tags = new ArrayList<>();
            final List<Account> accounts = xmppConnectionService.getAccounts();
            for (final Account account : accounts) {
                if (account.isEnabled()) {
                    for (Contact contact : account.getRoster().getContacts()) {
                        if (contact.showInContactList()) {
                            tags.addAll(contact.getTags(this));
                        }
                    }

                    for (Bookmark bookmark : account.getBookmarks()) {
                        tags.addAll(bookmark.getTags(this));
                    }
                }
            }

            Comparator<Map.Entry<ListItem.Tag, Integer>> sortTagsBy = Map.Entry.comparingByValue(Comparator.reverseOrder());
            sortTagsBy = sortTagsBy.thenComparing(entry -> entry.getKey().getName());

            this.tags.clear();
            this.tags.addAll(
                    tags.stream()
                            .collect(Collectors.toMap((x) -> x, (t) -> 1, (c1, c2) -> c1 + c2))
                            .entrySet().stream()
                            .sorted(sortTagsBy)
                            .map(e -> e.getKey()).collect(Collectors.toList())
            );

            ListItem.Tag channelTag = null;
            int channelTagIndex = 0;

            for (ListItem.Tag tag : this.tags) {
                if (tag.getName().equals("Channel")) {
                    channelTag = tag;
                    break;
                }
                channelTagIndex++;
            }

            if (channelTag != null) {
                this.tags.remove(channelTagIndex);
                this.tags.add(0, channelTag);
            }
        }
    }
}
