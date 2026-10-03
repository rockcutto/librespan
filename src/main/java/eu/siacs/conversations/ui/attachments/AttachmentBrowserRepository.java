package eu.siacs.conversations.ui.attachments;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.AttachmentMessageRecord;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.storage.secure.SecureContentObject;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.xmpp.Jid;

/**
 * Read-only attachment browser repository.
 *
 * Message DB owns ordering and conversation/account scoping. Secure Content owns committed secure
 * media metadata and content availability. Legacy FileBackend is consulted only when no secure
 * media relation exists for the message.
 */
public final class AttachmentBrowserRepository {

    private static final int RAW_BATCH = 80;
    private static final int MAX_RAW_BATCHES_PER_PAGE = 8;

    private final DatabaseBackend databaseBackend;
    private final FileBackend fileBackend;
    private final SecureContentStore secureContentStore;

    public AttachmentBrowserRepository(
            final DatabaseBackend databaseBackend,
            final FileBackend fileBackend,
            final SecureContentStore secureContentStore) {
        this.databaseBackend = databaseBackend;
        this.fileBackend = fileBackend;
        this.secureContentStore = secureContentStore;
    }

    public AttachmentPage loadPage(
            final String accountUuid,
            final Jid jid,
            final AttachmentEntry.Category category,
            @Nullable final AttachmentPage.Cursor cursor,
            final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("attachment page limit must be positive");
        }

        final SecureIndex secureIndex = buildSecureIndex(accountUuid);
        final ArrayList<AttachmentEntry> entries = new ArrayList<>(limit);

        AttachmentPage.Cursor scanCursor = cursor;
        boolean hasMore = false;
        int batches = 0;

        while (entries.size() < limit && batches++ < MAX_RAW_BATCHES_PER_PAGE) {
            final List<AttachmentMessageRecord> records =
                    databaseBackend.getAttachmentMessageRecords(
                            accountUuid,
                            jid,
                            scanCursor == null ? Long.MAX_VALUE : scanCursor.timeSent,
                            scanCursor == null ? null : scanCursor.messageUuid,
                            RAW_BATCH);
            if (records.isEmpty()) {
                hasMore = false;
                break;
            }

            int consumed = 0;
            for (final AttachmentMessageRecord record : records) {
                consumed++;
                scanCursor = new AttachmentPage.Cursor(record.timeSent, record.messageUuid);
                final AttachmentEntry entry = resolve(accountUuid, record, secureIndex);
                if (entry != null && entry.category == category) {
                    entries.add(entry);
                    if (entries.size() >= limit) {
                        break;
                    }
                }
            }

            hasMore = consumed < records.size() || records.size() == RAW_BATCH;
            if (!hasMore || entries.size() >= limit) {
                break;
            }
        }

        return new AttachmentPage(entries, scanCursor, hasMore);
    }

    private SecureIndex buildSecureIndex(final String accountUuid) {
        final Map<String, SecureContentObject> byMessage = new HashMap<>();
        final Set<String> blocked = new HashSet<>();
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            return new SecureIndex(byMessage, blocked);
        }
        try {
            for (final SecureContentObject object : secureContentStore.findByAccount(accountUuid)) {
                if (!SecureMessageMediaCoordinator.NAMESPACE.equals(object.getNamespace())
                        || object.getMessageUuid() == null) {
                    continue;
                }
                final String messageUuid = object.getMessageUuid();
                if (!object.isReaderVisible()
                        || blocked.contains(messageUuid)
                        || byMessage.containsKey(messageUuid)) {
                    byMessage.remove(messageUuid);
                    blocked.add(messageUuid);
                    continue;
                }
                byMessage.put(messageUuid, object);
            }
        } catch (final RuntimeException error) {
            // Store enumeration failure must never authorize legacy fallback for messages that may
            // already have secure media. Fail the page closed instead.
            blocked.add("*");
            byMessage.clear();
        }
        return new SecureIndex(byMessage, blocked);
    }

    @Nullable
    private AttachmentEntry resolve(
            final String accountUuid,
            final AttachmentMessageRecord record,
            final SecureIndex secureIndex) {
        if (secureIndex.blocked.contains("*") || secureIndex.blocked.contains(record.messageUuid)) {
            return null;
        }

        final FileParams params = parseFileParams(record.body);
        final SecureContentObject secure = secureIndex.byMessage.get(record.messageUuid);
        if (secure != null) {
            final String storedMime = secure.getMetadata().getMimeType();
            final String storedName = secure.getMetadata().getFileName();
            final String name = MimeUtils.resolvePresentationFileName(storedName, record.body);
            final String mime = MimeUtils.resolvePresentationMime(storedMime, name);
            final Long secureSize = secure.getMetadata().getSizeBytes();
            final AttachmentEntry.Category category =
                    AttachmentEntry.classify(
                            mime,
                            record.type,
                            params.width,
                            params.height,
                            params.durationMillis);
            return new AttachmentEntry(
                    accountUuid,
                    record.conversationUuid,
                    record.messageUuid,
                    category,
                    AttachmentEntry.Source.SECURE,
                    mime,
                    name,
                    null,
                    secureSize == null ? params.sizeBytes : secureSize,
                    params.durationMillis,
                    params.width,
                    params.height,
                    record.timeSent);
        }

        if (Strings.isNullOrEmpty(record.relativeFilePath)) {
            return null;
        }
        final File file = fileBackend.getFileForPath(record.relativeFilePath);
        if (fileBackend.isInsidePlaintextMediaNamespace(file)
                && !fileBackend.isAccountScopedPlaintextMediaFile(accountUuid, file)) {
            return null;
        }
        if (!file.isFile()) {
            return null;
        }
        final String visibleName =
                FileBackend.userVisiblePlaintextFileName(record.messageUuid, file.getName());
        final String physicalMime =
                MimeUtils.guessMimeTypeFromExtension(
                        MimeUtils.extractRelevantExtension(record.relativeFilePath));
        final String mime = AttachmentEntry.resolvePresentationMime(physicalMime, visibleName);
        final AttachmentEntry.Category category =
                AttachmentEntry.classify(
                        mime,
                        record.type,
                        params.width,
                        params.height,
                        params.durationMillis);
        return new AttachmentEntry(
                accountUuid,
                record.conversationUuid,
                record.messageUuid,
                category,
                AttachmentEntry.Source.LEGACY,
                mime,
                visibleName,
                record.relativeFilePath,
                file.length() > 0 ? file.length() : params.sizeBytes,
                params.durationMillis,
                params.width,
                params.height,
                record.timeSent);
    }

    private static FileParams parseFileParams(@Nullable final String body) {
        final FileParams params = new FileParams();
        final String[] parts = body == null ? new String[0] : body.split("\\|");
        try {
            switch (parts.length) {
                case 5:
                    params.durationMillis = parseInt(parts[4]);
                    // fall through
                case 4:
                    params.width = parseInt(parts[2]);
                    params.height = parseInt(parts[3]);
                    // fall through
                case 2:
                    params.sizeBytes = parseLong(parts[1]);
                    break;
                case 3:
                    params.sizeBytes = parseLong(parts[0]);
                    params.width = parseInt(parts[1]);
                    params.height = parseInt(parts[2]);
                    break;
                case 1:
                    params.sizeBytes = parseLong(parts[0]);
                    break;
                default:
                    break;
            }
        } catch (final RuntimeException ignored) {
            return new FileParams();
        }
        return params;
    }

    private static int parseInt(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (final Exception ignored) {
            return 0;
        }
    }

    private static long parseLong(final String value) {
        try {
            return Long.parseLong(value);
        } catch (final Exception ignored) {
            return 0L;
        }
    }

    private static final class FileParams {
        private long sizeBytes;
        private int width;
        private int height;
        private int durationMillis;
    }

    private static final class SecureIndex {
        private final Map<String, SecureContentObject> byMessage;
        private final Set<String> blocked;

        private SecureIndex(
                final Map<String, SecureContentObject> byMessage,
                final Set<String> blocked) {
            this.byMessage = byMessage;
            this.blocked = blocked;
        }
    }
}
