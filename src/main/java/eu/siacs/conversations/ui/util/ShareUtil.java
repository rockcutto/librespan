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

package eu.siacs.conversations.ui.util;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.common.base.Strings;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.MediaAttachmentRelations;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.storage.secure.AndroidSecureContentExport;
import eu.siacs.conversations.storage.secure.AndroidSecureMessageMediaExporter;
import eu.siacs.conversations.storage.secure.ContentExportOperation;
import eu.siacs.conversations.storage.secure.SecureContentMetadata;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway;
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.ShareWithActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;

public class ShareUtil {

    public static void share(final XmppActivity activity, final Message message) {
        createMessageShareIntentAsync(
                activity,
                message,
                intent -> launchShareIntent(activity, intent, false));
    }

    public static void forward(final XmppActivity activity, final Message message) {
        createMessageShareIntentAsync(
                activity,
                message,
                intent -> launchShareIntent(activity, intent, true));
    }

    private static void launchShareIntent(
            final XmppActivity activity, final Intent shareIntent, final boolean forward) {
        if (shareIntent == null || activity.isFinishing()) {
            return;
        }
        if (forward) {
            shareIntent.removeExtra(ConversationsActivity.EXTRA_AS_QUOTE);
            shareIntent.setClass(activity, ShareWithActivity.class);
        }
        try {
            activity.startActivity(
                    forward
                            ? shareIntent
                            : Intent.createChooser(
                                    shareIntent, activity.getText(R.string.share_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                            activity,
                            R.string.no_application_found_to_open_file,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private interface IntentCallback {
        void onReady(Intent intent);
    }

    private static void createMessageShareIntentAsync(
            final XmppActivity activity,
            final Message message,
            final IntentCallback callback) {
        if (!message.isFileOrImage() || !Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            callback.onReady(createLegacyMessageShareIntent(activity, message));
            return;
        }

        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(
                () -> {
                    final Intent intent;
                    try {
                        intent = createSecureOrLegacyMediaShareIntent(activity, message);
                    } catch (final Exception e) {
                        Log.e(Config.LOGTAG, "unable to prepare secure media share", e);
                        activity.runOnUiThread(
                                () -> Toast.makeText(
                                                activity,
                                                R.string.file_transmission_failed,
                                                Toast.LENGTH_SHORT)
                                        .show());
                        return;
                    }
                    activity.runOnUiThread(() -> callback.onReady(intent));
                });
    }

    private static Intent createSecureOrLegacyMediaShareIntent(
            final XmppActivity activity, final Message message) throws Exception {
        final Conversations application = (Conversations) activity.getApplication();
        final SecureContentStore store = application.getSecureContentStoreProvider().get();
        final SecureMessageMediaCoordinator coordinator =
                new SecureMessageMediaCoordinator(
                        store, new SecureContentTransferGateway(store));
        final String accountUuid = message.getConversation().getAccount().getUuid();
        final SecureContentMetadata metadata =
                coordinator.resolveMetadata(accountUuid, message.getUuid());
        final AndroidSecureMessageMediaExporter exporter =
                new AndroidSecureMessageMediaExporter(activity, store);
        final AndroidSecureContentExport export =
                exporter.prepare(
                        accountUuid,
                        message.getUuid(),
                        ContentExportOperation.SHARE);
        if (export == null) {
            return createLegacyMessageShareIntent(activity, message);
        }
        if (metadata == null) {
            throw new IllegalStateException(
                    "Secure media export exists without authenticated metadata");
        }
        return createMediaShareIntent(message, export.getUri(), metadata.getMimeType());
    }

    private static Intent createLegacyMessageShareIntent(
            final XmppActivity activity, final Message message) {
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        if (message.isGeoUri()) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, message.getBody());
            shareIntent.setType("text/plain");
        } else if (!message.isFileOrImage()) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, message.getBodyForDisplaying().toString());
            shareIntent.setType("text/plain");
            shareIntent.putExtra(
                    ConversationsActivity.EXTRA_AS_QUOTE,
                    message.getStatus() == Message.STATUS_RECEIVED);
        } else {
            final DownloadableFile file =
                    activity.xmppConnectionService.getFileBackend().getFile(message);
            try {
                return createMediaShareIntent(
                        message, FileBackend.getUriForFile(activity, file), null);
            } catch (SecurityException e) {
                Toast.makeText(
                                activity,
                                activity.getString(
                                        R.string.no_permission_to_access_x, file.getAbsolutePath()),
                                Toast.LENGTH_SHORT)
                        .show();
                return null;
            }
        }
        return shareIntent;
    }

    private static Intent createMediaShareIntent(
            final Message message, final Uri uri, final String secureMimeType) {
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final Message caption = MediaAttachmentRelations.getCaption(message);
        if (caption != null) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, caption.getBodyForDisplaying().toString());
        }
        String mime =
                Strings.isNullOrEmpty(secureMimeType)
                        ? message.getMimeType()
                        : secureMimeType;
        if (mime == null) {
            mime = "*/*";
        }
        shareIntent.setType(mime);
        return shareIntent;
    }

    public static void share(XmppActivity activity, String text) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        shareIntent.setType("text/plain");
        try {
            activity.startActivity(Intent.createChooser(shareIntent, activity.getText(R.string.share_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    public static void share(Application application, String text) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        shareIntent.setType("text/plain");
        try {
            application.startActivity(Intent.createChooser(shareIntent, application.getText(R.string.share_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(application, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    public static void share(final XmppActivity activity, final List<Message> messages) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            shareMultipleLegacy(activity, messages);
            return;
        }
        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(
                () -> {
                    try {
                        final Intent shareIntent = createMultipleShareIntent(activity, messages);
                        activity.runOnUiThread(
                                () -> {
                                    try {
                                        activity.startActivity(
                                                Intent.createChooser(
                                                        shareIntent,
                                                        activity.getText(R.string.share_with)));
                                    } catch (ActivityNotFoundException e) {
                                        Toast.makeText(
                                                        activity,
                                                        R.string.no_application_found_to_open_file,
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                    }
                                });
                    } catch (final Exception e) {
                        Log.e(Config.LOGTAG, "unable to prepare secure multi-share", e);
                        activity.runOnUiThread(
                                () -> Toast.makeText(
                                                activity,
                                                R.string.file_transmission_failed,
                                                Toast.LENGTH_SHORT)
                                        .show());
                    }
                });
    }

    private static Intent createMultipleShareIntent(
            final XmppActivity activity, final List<Message> messages) throws Exception {
        final Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final ArrayList<Uri> files = new ArrayList<>();
        final HashSet<String> mimeTypeFirstParts = new HashSet<>();
        final HashSet<String> mimeTypeSecondParts = new HashSet<>();
        final Conversations application = (Conversations) activity.getApplication();
        final SecureContentStore store = application.getSecureContentStoreProvider().get();
        final SecureMessageMediaCoordinator coordinator =
                new SecureMessageMediaCoordinator(
                        store, new SecureContentTransferGateway(store));
        final AndroidSecureMessageMediaExporter exporter =
                new AndroidSecureMessageMediaExporter(activity, store);

        for (Message message : messages) {
            if (!message.isFileOrImage()) {
                continue;
            }
            final AndroidSecureContentExport export =
                    exporter.prepare(
                            message.getConversation().getAccount().getUuid(),
                            message.getUuid(),
                            ContentExportOperation.SHARE);
            final Uri uri;
            if (export != null) {
                uri = export.getUri();
                final SecureContentMetadata metadata =
                        coordinator.resolveMetadata(
                                message.getConversation().getAccount().getUuid(),
                                message.getUuid());
                if (metadata == null) {
                    throw new IllegalStateException(
                            "Secure media export exists without authenticated metadata");
                }
                collectMime(metadata.getMimeType(), mimeTypeFirstParts, mimeTypeSecondParts);
            } else {
                final DownloadableFile file =
                        activity.xmppConnectionService.getFileBackend().getFile(message);
                uri = FileBackend.getUriForFile(activity, file);
                collectMime(message.getMimeType(), mimeTypeFirstParts, mimeTypeSecondParts);
            }
            files.add(uri);
        }

        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setType(resolveMime(mimeTypeFirstParts, mimeTypeSecondParts));
        appendMultipleText(activity, messages, shareIntent);
        if (files.isEmpty()) {
            shareIntent.setType("text/plain");
        }
        return shareIntent;
    }

    private static void shareMultipleLegacy(
            final XmppActivity activity, final List<Message> messages) {
        final Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final ArrayList<Uri> files = new ArrayList<>();
        File firstFile = null;
        final HashSet<String> mimeTypeFirstParts = new HashSet<>();
        final HashSet<String> mimeTypeSecondParts = new HashSet<>();

        for (Message message : messages) {
            if (message.isFileOrImage()) {
                final DownloadableFile file =
                        activity.xmppConnectionService.getFileBackend().getFile(message);
                if (firstFile == null) {
                    firstFile = file;
                }
                collectMime(
                        message.getMimeType(), mimeTypeFirstParts, mimeTypeSecondParts);
                files.add(FileBackend.getUriForFile(activity, file));
            }
        }

        try {
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
        } catch (SecurityException e) {
            final String filePath = firstFile == null ? "" : firstFile.getAbsolutePath();
            Toast.makeText(
                            activity,
                            activity.getString(R.string.no_permission_to_access_x, filePath),
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setType(resolveMime(mimeTypeFirstParts, mimeTypeSecondParts));
        appendMultipleText(activity, messages, shareIntent);
        if (files.isEmpty()) {
            shareIntent.setType("text/plain");
        }
        try {
            activity.startActivity(Intent.createChooser(shareIntent, activity.getText(R.string.share_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    private static void collectMime(
            final String mimeType,
            final HashSet<String> firstParts,
            final HashSet<String> secondParts) {
        final String[] split = Strings.nullToEmpty(mimeType).split("/");
        if (split.length == 2) {
            firstParts.add(split[0]);
            secondParts.add(split[1]);
        }
    }

    private static String resolveMime(
            final HashSet<String> firstParts, final HashSet<String> secondParts) {
        if (firstParts.size() == 1 && secondParts.size() == 1) {
            return firstParts.iterator().next() + "/" + secondParts.iterator().next();
        } else if (firstParts.size() == 1) {
            return firstParts.iterator().next() + "/*";
        } else {
            return "*/*";
        }
    }

    private static void appendMultipleText(
            final XmppActivity activity,
            final List<Message> messages,
            final Intent shareIntent) {
        final StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (sb.length() != 0) {
                sb.append("\n\n");
            }
            sb.append(message.getAvatarName());
            sb.append(", ");
            sb.append(
                    DateUtils.formatDateTime(
                            activity,
                            message.getTimeSent(),
                            DateUtils.FORMAT_SHOW_TIME
                                    | DateUtils.FORMAT_SHOW_DATE
                                    | DateUtils.FORMAT_SHOW_YEAR
                                    | DateUtils.FORMAT_ABBREV_MONTH));
            sb.append(": ");
            if (message.isGeoUri()) {
                sb.append(message.getBody());
            } else if (!message.isFileOrImage()) {
                sb.append(message.getBodyForDisplaying());
            }
        }
        shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
    }

    public static void copyToClipboard(XmppActivity activity, Message message) {
        if (activity.copyTextToClipboard(message.getBodyForDisplaying().toString(), R.string.message)) {
            Toast.makeText(activity, R.string.message_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    public static void copyToClipboard(XmppActivity activity, StringBuilder sb) {
        if (activity.copyTextToClipboard(sb.toString(), R.string.message)) {
            Toast.makeText(activity, R.string.message_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    public static void copyUrlToClipboard(XmppActivity activity, Message message) {
        final String url;
        final int resId;
        if (message.isGeoUri()) {
            resId = R.string.location;
            url = message.getBody();
        } else if (message.hasFileOnRemoteHost()) {
            resId = R.string.file_url;
            url = message.getFileParams().url;
        } else {
            final Message.FileParams fileParams = message.getFileParams();
            url =
                    (fileParams != null && fileParams.url != null)
                            ? fileParams.url
                            : message.getBody().trim();
            resId = R.string.file_url;
        }
        if (activity.copyTextToClipboard(url, resId)) {
            Toast.makeText(activity, R.string.url_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    public static void copyLinkToClipboard(final Context context, final String url) {
        final Uri uri = Uri.parse(url);
        if ("xmpp".equals(uri.getScheme())) {
            try {
                final Jid jid = new XmppUri(uri).getJid();
                if (copyTextToClipboard(context, jid.asBareJid().toString(), R.string.account_settings_jabber_id)) {
                    Toast.makeText(context, R.string.jabber_id_copied_to_clipboard, Toast.LENGTH_SHORT).show();
                }
            } catch (final Exception e) { }
        } else {
            if (copyTextToClipboard(context, url, R.string.web_address)) {
                Toast.makeText(context, R.string.url_copied_to_clipboard, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static boolean copyTextToClipboard(Context context, String text, int labelResId) {
        ClipboardManager mClipBoardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String label = context.getResources().getString(labelResId);
        if (mClipBoardManager != null) {
            ClipData mClipData = ClipData.newPlainText(label, text);
            mClipBoardManager.setPrimaryClip(mClipData);
            return true;
        }
        return false;
    }

    public static void copyLinkToClipboard(final XmppActivity activity, final Message message) {
        final SpannableStringBuilder body = message.getBodyForDisplaying();
        for (final String url : MyLinkify.extractLinks(body)) {
            final Uri uri = Uri.parse(url);
            if ("xmpp".equals(uri.getScheme())) {
                try {
                    final Jid jid = new XmppUri(uri).getJid();
                    if (activity.copyTextToClipboard(
                            jid.asBareJid().toString(), R.string.account_settings_jabber_id)) {
                        Toast.makeText(
                                        activity,
                                        R.string.jabber_id_copied_to_clipboard,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                    return;
                } catch (final Exception e) {
                    return;
                }
            } else {
                if (activity.copyTextToClipboard(url, R.string.web_address)) {
                    Toast.makeText(activity, R.string.url_copied_to_clipboard, Toast.LENGTH_SHORT)
                            .show();
                }
                return;
            }
        }
    }

    public static String getLinkScheme(final SpannableStringBuilder body) {
        MyLinkify.addLinks(body, false);
        for (final String url : MyLinkify.extractLinks(body)) {
            final Uri uri = Uri.parse(url);
            if ("xmpp".equals(uri.getScheme())) {
                return uri.getScheme();
            } else {
                return "http";
            }
        }
        return null;
    }

    public static void copyJidToClipboard(final XmppActivity activity, Jid jid) {
        if (copyTextToClipboard(activity, jid.toString(), R.string.account_settings_jabber_id)) {
            Toast.makeText(activity, R.string.jabber_id_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }
}
