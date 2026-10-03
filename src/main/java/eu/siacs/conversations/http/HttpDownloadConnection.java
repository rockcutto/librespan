package eu.siacs.conversations.http;

import static eu.siacs.conversations.http.HttpConnectionManager.EXECUTOR;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Longs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import javax.net.ssl.SSLHandshakeException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.storage.secure.SecureIncomingLegacyMediaPublication;
import eu.siacs.conversations.storage.secure.SecureIncomingMediaCompletionPolicy;
import eu.siacs.conversations.storage.secure.SecureMessageMediaFileParamsUpdater;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileWriterException;
import eu.siacs.conversations.utils.MimeUtils;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpDownloadConnection implements Transferable {

    private static final int MAX_HTTP_DOWNLOAD_ATTEMPTS = 4;
    private static final String SECURE_INCOMING_CACHE_DIRECTORY = "SecureIncomingMedia";

    private final Message message;
    private final HttpConnectionManager mHttpConnectionManager;
    private final XmppConnectionService mXmppConnectionService;
    private HttpUrl mUrl;
    private DownloadableFile file;
    private int mStatus = Transferable.STATUS_UNKNOWN;
    private boolean acceptedAutomatically = false;
    private int mProgress = 0;
    private Call mostRecentCall;
    @Nullable private String receivedContentDisposition;

    HttpDownloadConnection(Message message, HttpConnectionManager manager) {
        this.message = message;
        this.mHttpConnectionManager = manager;
        this.mXmppConnectionService = manager.getXmppConnectionService();
    }

    @Override
    public boolean start() {
        if (mXmppConnectionService.hasInternetConnection()) {
            if (this.mStatus == STATUS_OFFER_CHECK_FILESIZE) {
                checkFileSize(true);
            } else {
                download(true);
            }
            return true;
        } else {
            return false;
        }
    }

    public void init(boolean interactive) {
        if (message.isDeleted()) {
            if (message.getType() == Message.TYPE_PRIVATE_FILE) {
                message.setType(Message.TYPE_PRIVATE);
            } else if (message.isFileOrImage()) {
                message.setType(Message.TYPE_TEXT);
            }
            message.setOob(true);
            message.setDeleted(false);
            mXmppConnectionService.updateMessage(message);
        }
        this.message.setTransferable(this);
        try {
            final Message.FileParams fileParams = message.getFileParams();
            if (message.hasFileOnRemoteHost()) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else if (message.isOOb() && fileParams.url != null && fileParams.size != null) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else {
                mUrl = AesGcmURL.of(message.getBody().split("\n")[0]);
            }
            final AbstractConnectionManager.Extension extension = AbstractConnectionManager.Extension.of(mUrl.encodedPath());
            if (message.getEncryption() != Message.ENCRYPTION_AXOLOTL) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            final String ext = extension.getExtension();
            final String filename =
                    Strings.isNullOrEmpty(ext)
                            ? message.getUuid()
                            : String.format("%s.%s", message.getUuid(), ext);
            final String mimeFromExtension =
                    Strings.isNullOrEmpty(ext) ? null : MimeUtils.guessMimeTypeFromExtension(ext);
            final boolean voiceOrAudio =
                    (mimeFromExtension != null && mimeFromExtension.startsWith("audio/"))
                            || (message.getMimeType() != null
                                    && message.getMimeType().startsWith("audio/"));
            if (voiceOrAudio && Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                // Keep the existing secure-rollout staging identity unchanged. Plaintext rollout
                // below uses the common account-scoped Media/Audio policy.
                final File recordingsDirectory =
                        new File(mXmppConnectionService.getFilesDir(), "Recordings");
                message.setRelativeFilePath(
                        new File(recordingsDirectory, filename).getAbsolutePath());
            } else {
                final String resolvedMime =
                        mimeFromExtension != null ? mimeFromExtension : message.getMimeType();
                mXmppConnectionService
                        .getFileBackend()
                        .setupRelativeFilePath(message, filename, resolvedMime);
            }
            setupFile();
            if (this.message.getEncryption() == Message.ENCRYPTION_AXOLOTL && this.file.getKey() == null) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            final Long knownFileSize = message.getFileParams().size;
            Log.d(Config.LOGTAG, "knownFileSize: " + knownFileSize + ", body=" + message.getBody());
            if (knownFileSize != null && interactive) {
                final long wireSize =
                        this.file.getKey() != null
                                ? knownFileSize + 16
                                : knownFileSize;
                this.file.setExpectedSize(wireSize);
                download(true);
            } else {
                // Non-interactive receive always performs the cheap metadata probe first. Besides
                // the wire size this gives the file card Content-Type/Content-Disposition before
                // the auto-download decision is made.
                checkFileSize(interactive);
            }
        } catch (final IllegalArgumentException e) {
            this.cancel();
        }
    }

    static boolean isAutoDownloadStorageReady(
            final boolean secureContentMediaRollout,
            final boolean audio,
            final boolean hasLegacyStoragePermission) {
        // Both media modes write only to app-private storage. Secure rollout stages plaintext in
        // private cache before Store commit; plaintext rollout writes directly to private Media.
        // Shared/external storage permission is therefore irrelevant to receive readiness.
        return true;
    }

    private void maybeAutoDownloadKnownSize(final long wireSize, final boolean fromMessageMetadata) {
        final long configuredLimit = mHttpConnectionManager.getAutoAcceptFileSize();
        final boolean withinConfiguredLimit = configuredLimit > 0 && wireSize <= configuredLimit;
        final boolean storageReady =
                isAutoDownloadStorageReady(
                        Config.SECURE_CONTENT_MEDIA_ROLLOUT,
                        false,
                        mHttpConnectionManager.hasStoragePermission());
        if (storageReady
                && withinConfiguredLimit
                && mXmppConnectionService.isDataSaverDisabled()) {
            acceptedAutomatically = true;
            Log.d(
                    Config.LOGTAG,
                    "auto-downloading file"
                            + " size="
                            + wireSize
                            + " configuredLimit="
                            + configuredLimit
                            + (fromMessageMetadata ? " (message metadata)" : " (HTTP probe)"));
            download(false);
        } else {
            acceptedAutomatically = false;
            changeStatus(STATUS_OFFER);
            mXmppConnectionService.getNotificationService().push(message);
        }
    }

    private void setupFile() {
        final String reference = mUrl.fragment();
        if (reference != null && AesGcmURL.IV_KEY.matcher(reference).matches()) {
            this.file = new DownloadableFile(mXmppConnectionService.getCacheDir(), message.getUuid());
            this.file.setKeyAndIv(CryptoHelper.hexToBytes(reference));
            Log.d(Config.LOGTAG, "create temporary OMEMO encrypted file: " + this.file.getAbsolutePath() + "(" + message.getMimeType() + ")");
        } else if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            this.file = getSecureIncomingPlaintextStagingFile();
        } else {
            this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
        }
    }

    private DownloadableFile getSecureIncomingPlaintextStagingFile() {
        final File directory =
                new File(mXmppConnectionService.getCacheDir(), SECURE_INCOMING_CACHE_DIRECTORY);
        return new DownloadableFile(directory, message.getUuid());
    }

    private void cleanupSecureIncomingPlaintextStagingFile() {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            return;
        }
        final File stagingFile = getSecureIncomingPlaintextStagingFile();
        if (stagingFile.exists() && !stagingFile.delete()) {
            Log.w(Config.LOGTAG, "unable to delete incoming HTTP staging file");
        }
    }

    private void download(final boolean interactive) {
        EXECUTOR.execute(new FileDownloader(interactive));
    }

    private void checkFileSize(final boolean interactive) {
        EXECUTOR.execute(new FileSizeChecker(interactive));
    }

    @Override
    public void cancel() {
        final Call call = this.mostRecentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        cleanupSecureIncomingPlaintextStagingFile();
        mHttpConnectionManager.finishConnection(this);
        message.setTransferable(null);
        if (message.isFileOrImage()) {
            message.setDeleted(true);
        }
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void decryptFile() throws IOException {
        final DownloadableFile outputFile =
                Config.SECURE_CONTENT_MEDIA_ROLLOUT
                        ? getSecureIncomingPlaintextStagingFile()
                        : mXmppConnectionService.getFileBackend().getFile(message, true);

        if (outputFile.getParentFile().mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directories for " + outputFile.getAbsolutePath());
        }

        if (!outputFile.createNewFile()) {
            Log.w(Config.LOGTAG, "unable to create output file " + outputFile.getAbsolutePath());
        }

        final InputStream is = new FileInputStream(this.file);

        outputFile.setKey(this.file.getKey());
        outputFile.setIv(this.file.getIv());
        final OutputStream os = AbstractConnectionManager.createOutputStream(outputFile, false, true);

        ByteStreams.copy(is, os);

        FileBackend.close(is);
        FileBackend.close(os);

        if (!file.delete()) {
            Log.w(Config.LOGTAG, "unable to delete temporary OMEMO encrypted file " + file.getAbsolutePath());
        }
    }

    private void finish() {
        message.setTransferable(null);
        mHttpConnectionManager.finishConnection(this);
        final boolean notify = acceptedAutomatically && !message.isRead();
        mHttpConnectionManager.updateConversationUi(true);

        if (!SecureIncomingMediaCompletionPolicy.shouldScanLegacyMedia(
                Config.SECURE_CONTENT_MEDIA_ROLLOUT)) {
            if (notify) {
                mXmppConnectionService.getNotificationService().push(message);
            }
            return;
        }

        final DownloadableFile file = mXmppConnectionService.getFileBackend().getFile(message, true);
        if (mXmppConnectionService.getFileBackend().isAppPrivateMediaFile(file)) {
            if (notify) {
                mXmppConnectionService.getNotificationService().push(message);
            }
            return;
        }
        mXmppConnectionService.getFileBackend().updateMediaScanner(file, () -> {
            if (notify) {
                mXmppConnectionService.getNotificationService().push(message);
            }
        });
    }

    /**
     * Transitional receive boundary. HTTP resume/decryption still finishes into the existing
     * legacy file for now, but successful plaintext is immediately published into the durable
     * Secure Content relation and the legacy copy is retired before the transfer becomes visible
     * as complete.
     */
    private void publishIncomingMediaToSecureStore() throws SecureMediaPublicationException {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            return;
        }

        final DownloadableFile stagingFile = getSecureIncomingPlaintextStagingFile();
        final Conversations application =
                (Conversations) mXmppConnectionService.getApplication();
        final var store = application.getSecureContentStoreProvider().get();
        final SecureIncomingLegacyMediaPublication publication =
                new SecureIncomingLegacyMediaPublication(store);
        final SecureMessageMediaFileParamsUpdater fileParamsUpdater =
                new SecureMessageMediaFileParamsUpdater(mXmppConnectionService, store);
        final String accountUuid = message.getConversation().getAccount().getUuid();
        final String url;
        final String ref = mUrl.fragment();
        if (ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()) {
            url = AesGcmURL.toAesGcmUrl(mUrl);
        } else {
            url = mUrl.toString();
        }
        try {
            publication.publish(
                    accountUuid,
                    message.getUuid(),
                    message.getMimeType(),
                    resolveIncomingFileName(
                            message.getSecureMediaFileName(),
                            receivedContentDisposition,
                            url),
                    stagingFile);
            fileParamsUpdater.update(message, url);
            mXmppConnectionService.updateMessage(message);
            if (!publication.retireIfPublished(accountUuid, message.getUuid(), stagingFile)) {
                throw new IOException("Secure incoming media publication did not commit");
            }
        } catch (final IOException e) {
            throw new SecureMediaPublicationException(e);
        }
    }

    @Nullable
    static String resolveIncomingFileName(
            @Nullable final String protocolFileName,
            @Nullable final String contentDisposition,
            @Nullable final String url) {
        final String protocolName = sanitizeIncomingFileName(protocolFileName);
        if (protocolName != null) {
            return protocolName;
        }
        final String headerName = contentDispositionFileName(contentDisposition);
        if (headerName != null) {
            return headerName;
        }
        if (Strings.isNullOrEmpty(url)) {
            return null;
        }
        try {
            return sanitizeIncomingFileName(Uri.parse(url).getLastPathSegment());
        } catch (final Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static String contentDispositionFileName(@Nullable final String contentDisposition) {
        if (Strings.isNullOrEmpty(contentDisposition)) {
            return null;
        }
        String plain = null;
        for (final String part : contentDisposition.split(";")) {
            final int separator = part.indexOf('=');
            if (separator < 1) {
                continue;
            }
            final String name = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if ("filename*".equalsIgnoreCase(name)) {
                final int charsetPrefix = value.indexOf("''");
                return sanitizeIncomingFileName(
                        charsetPrefix >= 0 ? Uri.decode(value.substring(charsetPrefix + 2)) : value);
            }
            if ("filename".equalsIgnoreCase(name)) {
                plain = value;
            }
        }
        return sanitizeIncomingFileName(plain);
    }

    @Nullable
    private static String sanitizeIncomingFileName(@Nullable final String candidate) {
        if (Strings.isNullOrEmpty(candidate)) {
            return null;
        }
        final String normalized = candidate.trim().replace('\\', '/');
        final String fileName = new File(normalized).getName();
        if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName)
                || fileName.indexOf(0) >= 0) {
            return null;
        }
        return fileName;
    }

    private void decryptIfNeeded() throws IOException {
        if (file.getKey() != null && file.getIv() != null) {
            decryptFile();
        }
    }

    private void changeStatus(int status) {
        this.mStatus = status;
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void showToastForException(final Exception e) {
        final Call call = mostRecentCall;
        final boolean cancelled = call != null && call.isCanceled();
        if (e == null || cancelled) {
            return;
        }
        if (e instanceof java.net.UnknownHostException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_server_not_found);
        } else if (e instanceof java.net.ConnectException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_connect);
        } else if (e instanceof FileWriterException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_write_file);
        } else if (e instanceof InvalidFileException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_invalid_file);
        } else {
            mXmppConnectionService.showErrorToastInUi(R.string.file_transmission_failed);
        }
    }

    private void updateProgress(long i) {
        this.mProgress = (int) i;
        mHttpConnectionManager.updateConversationUi(false);
    }

    @Override
    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public Long getFileSize() {
        if (this.file != null) {
            return this.file.getExpectedSize();
        } else {
            return null;
        }
    }

    @Override
    public int getProgress() {
        return this.mProgress;
    }

    public Message getMessage() {
        return message;
    }

    private class FileSizeChecker implements Runnable {

        private final boolean interactive;

        FileSizeChecker(boolean interactive) {
            this.interactive = interactive;
        }


        @Override
        public void run() {
            check();
        }

        private void retrieveFailed(@Nullable final Exception e) {
            // A failed HEAD/Range probe is recoverable. Keep the remote message intact and leave
            // the same Transferable in OFFER_CHECK_FILESIZE so one tap retries instead of marking
            // the attachment as deleted.
            HttpDownloadConnection.this.acceptedAutomatically = false;
            changeStatus(STATUS_OFFER_CHECK_FILESIZE);
            if (interactive) {
                showToastForException(e);
            } else {
                HttpDownloadConnection.this.mXmppConnectionService.getNotificationService().push(message);
            }
        }

        private void check() {
            long size = -1;
            Exception lastFailure = null;
            for (int attempt = 1; attempt <= MAX_HTTP_DOWNLOAD_ATTEMPTS; attempt++) {
                try {
                    size = retrieveFileSize();
                    lastFailure = null;
                    break;
                } catch (final Exception e) {
                    lastFailure = e;
                    Log.d(
                            Config.LOGTAG,
                            "HTTP file size check failed on attempt "
                                    + attempt
                                    + "/"
                                    + MAX_HTTP_DOWNLOAD_ATTEMPTS,
                            e);
                    if (attempt < MAX_HTTP_DOWNLOAD_ATTEMPTS) {
                        sleepBeforeRetry(attempt);
                    }
                }
            }
            if (lastFailure != null || size < 0) {
                retrieveFailed(lastFailure);
                return;
            }
            final Message.FileParams fileParams = message.getFileParams();
            // Message file metadata stores the cleartext size. HTTP probes see the encrypted
            // payload (GCM adds 16 bytes), so do not persist the wire size as if it were the
            // original file size; otherwise a later retry adds another 16 bytes and can never
            // reach 100%.
            final boolean encryptedPayload = file.getKey() != null;
            final long cleartextSize = encryptedPayload && size >= 16 ? size - 16 : size;
            FileBackend.updateFileParams(message, fileParams.url, cleartextSize);
            if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                message.setSecureMediaPresentationMetadata(
                        message.getMimeType(),
                        message.getSecureMediaFileName(),
                        cleartextSize);
            }
            message.setOob(true);
            mXmppConnectionService.databaseBackend.updateMessage(message, true);
            file.setExpectedSize(size);
            message.resetFileParams();
            maybeAutoDownloadKnownSize(size, false);
        }

        private long retrieveFileSize() throws IOException {
            Log.d(Config.LOGTAG, "retrieve file size. interactive:" + interactive);
            changeStatus(STATUS_CHECKING);
            final OkHttpClient client = mHttpConnectionManager.buildHttpClient(
                    mUrl,
                    message.getConversation().getAccount(),
                    interactive
            );

            IOException headFailure = null;
            final Request headRequest = new Request.Builder()
                    .url(URL.stripFragment(mUrl))
                    .addHeader("Accept-Encoding", "identity")
                    .head()
                    .build();
            mostRecentCall = client.newCall(headRequest);
            try (final Response response = mostRecentCall.execute()) {
                if (response.code() >= 200 && response.code() < 300) {
                    maybeUpdateFilenameFromResponseHeaders(
                            response.header("Content-Disposition"),
                            response.header("Content-Type"));
                    final String contentLength = response.header("Content-Length");
                    if (!Strings.isNullOrEmpty(contentLength)) {
                        final long size = Long.parseLong(contentLength, 10);
                        if (size >= 0) {
                            return size;
                        }
                    }
                    headFailure = new IOException("no usable content-length found in HEAD response");
                } else {
                    headFailure = new IOException("HTTP HEAD status " + response.code());
                }
            } catch (final IOException | NumberFormatException e) {
                headFailure = e instanceof IOException ? (IOException) e : new IOException(e);
            }

            // Some HTTP Upload frontends do not implement HEAD correctly. Probe one byte with GET
            // instead of forcing the user to tap "check size" and then failing with file not found.
            Log.d(Config.LOGTAG, "HEAD size probe failed; falling back to ranged GET", headFailure);
            final Request rangeRequest = new Request.Builder()
                    .url(URL.stripFragment(mUrl))
                    .addHeader("Accept-Encoding", "identity")
                    .addHeader("Range", "bytes=0-0")
                    .get()
                    .build();
            mostRecentCall = client.newCall(rangeRequest);
            try (final Response response = mostRecentCall.execute()) {
                throwOnInvalidCode(response);
                maybeUpdateFilenameFromResponseHeaders(
                        response.header("Content-Disposition"),
                        response.header("Content-Type"));
                final String contentRange = response.header("Content-Range");
                if (!Strings.isNullOrEmpty(contentRange)) {
                    final int slash = contentRange.lastIndexOf('/');
                    if (slash >= 0 && slash + 1 < contentRange.length()) {
                        final String total = contentRange.substring(slash + 1).trim();
                        if (!"*".equals(total)) {
                            final long size = Long.parseLong(total, 10);
                            if (size >= 0) {
                                return size;
                            }
                        }
                    }
                }
                // If the server ignored Range and returned 200, Content-Length is the full file.
                if (response.code() == 200) {
                    final String contentLength = response.header("Content-Length");
                    if (!Strings.isNullOrEmpty(contentLength)) {
                        final long size = Long.parseLong(contentLength, 10);
                        if (size >= 0) {
                            return size;
                        }
                    }
                }
                throw new IOException("server did not report file size via HEAD or ranged GET");
            } catch (final NumberFormatException e) {
                throw new IOException(e);
            }
        }


    }

    private void maybeUpdateFilenameFromResponseHeaders(
            final String contentDisposition, final String contentType) {
        final String headerName = contentDispositionFileName(contentDisposition);
        final int parameterSeparator =
                contentType == null ? -1 : contentType.indexOf(';');
        final String normalizedContentType =
                contentType == null
                        ? null
                        : (parameterSeparator >= 0
                                        ? contentType.substring(0, parameterSeparator)
                                        : contentType)
                                .trim();
        final String mime =
                !Strings.isNullOrEmpty(normalizedContentType)
                        ? normalizedContentType
                        : (headerName == null
                                ? null
                                : MimeUtils.guessMimeTypeFromExtension(
                                        MimeUtils.extractRelevantExtension(headerName)));

        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            message.setSecureMediaPresentationMetadata(
                    !Strings.isNullOrEmpty(mime) ? mime : message.getMimeType(),
                    headerName != null ? headerName : message.getSecureMediaFileName(),
                    message.getSecureMediaSizeBytes());
            return;
        }

        if (headerName != null) {
            mXmppConnectionService
                    .getFileBackend()
                    .setupRelativeFilePath(message, headerName, mime);
            setupFile();
            return;
        }
        maybeUpdateFilenameFromContentType(contentType);
    }

    private void maybeUpdateFilenameFromContentType(final String contentType) {
        final AbstractConnectionManager.Extension extension =
                AbstractConnectionManager.Extension.of(mUrl.encodedPath());
        if (Strings.isNullOrEmpty(extension.getExtension()) && contentType != null) {
            final int parameterSeparator = contentType.indexOf(';');
            final String normalizedContentType =
                    (parameterSeparator >= 0
                                    ? contentType.substring(0, parameterSeparator)
                                    : contentType)
                            .trim();
            final String fileExtension =
                    MimeUtils.guessExtensionFromMimeType(normalizedContentType);
            if (fileExtension != null) {
                mXmppConnectionService
                        .getFileBackend()
                        .setupRelativeFilePath(
                                message,
                                String.format("%s.%s", message.getUuid(), fileExtension),
                                normalizedContentType);
                Log.d(
                        Config.LOGTAG,
                        "rewriting name after not finding extension in url but in content type");
                setupFile();
            }
        }
    }


    private class FileDownloader implements Runnable {

        private final boolean interactive;

        public FileDownloader(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            Exception lastFailure = null;
            for (int attempt = 1; attempt <= MAX_HTTP_DOWNLOAD_ATTEMPTS; attempt++) {
                try {
                    changeStatus(STATUS_DOWNLOADING);
                    download();
                    decryptIfNeeded();
                    if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                        publishIncomingMediaToSecureStore();
                    } else {
                        updateImageBounds();
                    }
                    finish();
                    return;
                } catch (final SSLHandshakeException e) {
                    changeStatus(STATUS_OFFER);
                    return;
                } catch (final Exception e) {
                    lastFailure = e;
                    Log.d(
                            Config.LOGTAG,
                            message.getConversation().getAccount().getJid().asBareJid()
                                    + ": HTTP download failed on attempt "
                                    + attempt
                                    + "/"
                                    + MAX_HTTP_DOWNLOAD_ATTEMPTS,
                            e);
                    if (!isRetryableDownloadFailure(e)
                            || attempt >= MAX_HTTP_DOWNLOAD_ATTEMPTS) {
                        break;
                    }
                    sleepBeforeRetry(attempt);
                }
            }
            HttpDownloadConnection.this.acceptedAutomatically = false;
            // Keep the message and remote URL available for a one-tap retry. A transient HTTP
            // failure must not turn the attachment into a locally deleted message.
            changeStatus(STATUS_OFFER);
            if (interactive) {
                showToastForException(lastFailure);
            } else {
                HttpDownloadConnection.this.mXmppConnectionService
                        .getNotificationService()
                        .push(message);
            }
        }

        private void download() throws Exception {
            final OkHttpClient client = mHttpConnectionManager.buildHttpClient(
                    mUrl,
                    message.getConversation().getAccount(),
                    interactive
            );

            final Request.Builder requestBuilder = new Request.Builder().url(URL.stripFragment(mUrl));

            final long expected = file.getExpectedSize();
            final boolean tryResume = file.exists() && file.getSize() > 0 && file.getSize() < expected;
            final long resumeSize;
            if (tryResume) {
                resumeSize = file.getSize();
                Log.d(Config.LOGTAG, "http download trying resume after " + resumeSize + " of " + expected);
                requestBuilder.addHeader("Range", String.format(Locale.ENGLISH, "bytes=%d-", resumeSize));
            } else {
                resumeSize = 0;
            }
            final Request request = requestBuilder.build();
            mostRecentCall = client.newCall(request);
            long transmitted = 0;
            try (final Response response = mostRecentCall.execute()) {
                throwOnInvalidCode(response);
                receivedContentDisposition = response.header("Content-Disposition");
                if (!tryResume) {
                    maybeUpdateFilenameFromResponseHeaders(
                            receivedContentDisposition, response.header("Content-Type"));
                }
                if (response.body() == null) {
                    throw new IOException("HTTP response contained no body");
                }
                final String contentRange = response.header("Content-Range");
                final boolean serverResumed =
                        tryResume
                                && contentRange != null
                                && contentRange.startsWith("bytes " + resumeSize + "-");
                final OutputStream rawOutputStream;
                if (tryResume && serverResumed) {
                    Log.d(Config.LOGTAG, "server resumed");
                    transmitted = file.getSize();
                    updateProgress(Math.round(((double) transmitted / expected) * 100));
                    rawOutputStream =
                            AbstractConnectionManager.createOutputStream(file, true, false);
                } else {
                    final String contentLength = response.header("Content-Length");
                    final Long parsedLength =
                            Strings.isNullOrEmpty(contentLength)
                                    ? null
                                    : Longs.tryParse(contentLength);
                    if (parsedLength != null && expected != parsedLength) {
                        Log.d(
                                Config.LOGTAG,
                                "content-length reported on GET ("
                                        + parsedLength
                                        + ") did not match expected size ("
                                        + expected
                                        + ")");
                    }
                    if (file.getParentFile() != null) {
                        file.getParentFile().mkdirs();
                    }
                    Log.d(Config.LOGTAG, "creating file: " + file.getAbsolutePath());
                    if (!file.exists() && !file.createNewFile()) {
                        throw new FileWriterException(file);
                    }
                    rawOutputStream =
                            AbstractConnectionManager.createOutputStream(file, false, false);
                }
                if (rawOutputStream == null) {
                    throw new FileWriterException(file);
                }
                try (final InputStream inputStream = response.body().byteStream();
                        final OutputStream outputStream = rawOutputStream) {
                    int count;
                    final byte[] buffer = new byte[16 * 1024];
                    while ((count = inputStream.read(buffer)) != -1) {
                        transmitted += count;
                        try {
                            outputStream.write(buffer, 0, count);
                        } catch (final IOException e) {
                            throw new FileWriterException(file);
                        }
                        if (expected > 0 && transmitted > expected) {
                            throw new InvalidFileException(
                                    String.format("File exceeds expected size of %d", expected));
                        }
                        if (expected > 0) {
                            updateProgress(
                                    Math.round(((double) transmitted / expected) * 100));
                        }
                    }
                    outputStream.flush();
                }
            }
            if (expected > 0 && transmitted != expected) {
                throw new IOException(
                        "incomplete HTTP download: received "
                                + transmitted
                                + " of "
                                + expected
                                + " bytes");
            }
        }

        private void updateImageBounds() {
            final boolean privateMessage = message.isPrivateMessage();
            message.setType(privateMessage ? Message.TYPE_PRIVATE_FILE : Message.TYPE_FILE);
            final String url;
            final String ref = mUrl.fragment();
            if (ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()) {
                url = AesGcmURL.toAesGcmUrl(mUrl);
            } else {
                url = mUrl.toString();
            }
            mXmppConnectionService.getFileBackend().updateFileParams(message, url);
            mXmppConnectionService.updateMessage(message);
        }

    }

    private static boolean isRetryableDownloadFailure(final Exception e) {
        return e instanceof IOException
                && !(e instanceof FileWriterException)
                && !(e instanceof InvalidFileException)
                && !(e instanceof SecureMediaPublicationException)
                && !(e instanceof SSLHandshakeException);
    }

    private static void sleepBeforeRetry(final int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void throwOnInvalidCode(final Response response) throws IOException {
        final int code = response.code();
        if (code < 200 || code >= 300) {
            throw new IOException(String.format(Locale.ENGLISH, "HTTP Status code was %d", code));
        }
    }

    private static class InvalidFileException extends IOException {

        private InvalidFileException(final String message) {
            super(message);
        }

    }

    private static class SecureMediaPublicationException extends IOException {

        private SecureMediaPublicationException(final Throwable cause) {
            super("Unable to publish incoming media to Secure Content Store", cause);
        }

    }
}
