package eu.siacs.conversations.http;

import static eu.siacs.conversations.http.HttpConnectionManager.EXECUTOR;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.primitives.Longs;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway;
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageMediaIngress;
import eu.siacs.conversations.storage.secure.SecureMessageMediaFileParamsUpdater;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.MimeUtils;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * HTTP receive path for Secure Content media.
 *
 * Network bytes are streamed directly into SecureContentStore. No DownloadableFile, persistent
 * plaintext staging path, media scan, or FileBackend fallback participates once this path is
 * selected. XEP-0454/AES-GCM payloads are authenticated/decrypted while streaming into the Store.
 */
final class SecureHttpDownloadConnection extends HttpDownloadConnection {

    private static final long AUDIO_AUTO_DOWNLOAD_MAX_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_HTTP_DOWNLOAD_ATTEMPTS = 4;
    private static final int PREFLIGHT_READ_TIMEOUT_SECONDS = 5;
    private static final int PREFLIGHT_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int PREFLIGHT_CALL_TIMEOUT_SECONDS = 8;

    private final Message secureMessage;
    private final HttpConnectionManager manager;
    private final XmppConnectionService service;

    private HttpUrl url;
    private int status = Transferable.STATUS_UNKNOWN;
    private long expectedWireSize = -1L;
    private long expectedPlaintextSize = -1L;
    private int progress = 0;
    private boolean acceptedAutomatically = false;
    @Nullable private Call mostRecentCall;
    @Nullable private String probedContentType;
    @Nullable private String probedContentDisposition;

    SecureHttpDownloadConnection(final Message message, final HttpConnectionManager manager) {
        super(message, manager);
        this.secureMessage = message;
        this.manager = manager;
        this.service = manager.getXmppConnectionService();
    }

    @Override
    public boolean start() {
        if (!service.hasInternetConnection()) {
            return false;
        }
        if (status == STATUS_OFFER_CHECK_FILESIZE) {
            checkFileSize(true);
        } else {
            download(true);
        }
        return true;
    }

    @Override
    public void init(final boolean interactive) {
        if (secureMessage.isDeleted()) {
            if (secureMessage.getType() == Message.TYPE_PRIVATE_FILE) {
                secureMessage.setType(Message.TYPE_PRIVATE);
            } else if (secureMessage.isFileOrImage()) {
                secureMessage.setType(Message.TYPE_TEXT);
            }
            secureMessage.setOob(true);
            secureMessage.setDeleted(false);
            service.updateMessage(secureMessage);
        }
        secureMessage.setTransferable(this);
        try {
            final Message.FileParams fileParams = secureMessage.getFileParams();
            if (secureMessage.hasFileOnRemoteHost()) {
                url = AesGcmURL.of(fileParams.url);
            } else if (secureMessage.isOOb() && fileParams.url != null && fileParams.size != null) {
                url = AesGcmURL.of(fileParams.url);
            } else {
                url = AesGcmURL.of(secureMessage.getBody().split("\\n")[0]);
            }
            if (secureMessage.getEncryption() != Message.ENCRYPTION_AXOLOTL) {
                secureMessage.setEncryption(Message.ENCRYPTION_NONE);
            }

            final Long knownPlaintextSize = fileParams.size;
            if (knownPlaintextSize != null && knownPlaintextSize >= 0) {
                expectedPlaintextSize = knownPlaintextSize;
                expectedWireSize = isWireEncrypted() ? knownPlaintextSize + 16L : knownPlaintextSize;
            }

            // Recovery/restart fast path: a committed exact secure relation is already the source
            // of truth. Never fall back to a legacy path or redownload it.
            if (resolveExistingSecureMedia()) {
                finishSuccessfulDownload();
                return;
            }

            if (knownPlaintextSize != null && (interactive || isAudioMessage())) {
                if (interactive) {
                    download(true);
                } else {
                    maybeAutoDownloadKnownSize(expectedWireSize);
                }
            } else {
                checkFileSize(interactive);
            }
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "unable to initialize secure HTTP download", e);
            acceptedAutomatically = false;
            progress = 0;
            changeStatus(STATUS_OFFER);
            if (interactive) {
                showToastForException(e);
            }
        }
    }

    private boolean resolveExistingSecureMedia() throws IOException {
        final SecureContentStore store = secureStore();
        final SecureMessageMediaCoordinator coordinator =
                new SecureMessageMediaCoordinator(store, new SecureContentTransferGateway(store));
        return coordinator.resolve(accountUuid(), secureMessage.getUuid()) != null;
    }

    private SecureContentStore secureStore() throws IOException {
        if (!(service.getApplication() instanceof Conversations)) {
            throw new IOException("Secure Content Store application provider is unavailable");
        }
        return ((Conversations) service.getApplication()).getSecureContentStoreProvider().get();
    }

    private String accountUuid() {
        return secureMessage.getConversation().getAccount().getUuid();
    }

    private boolean isAudioMessage() {
        final String mime = secureMessage.getMimeType();
        return mime != null && mime.startsWith("audio/");
    }

    private boolean isWireEncrypted() {
        final String fragment = url == null ? null : url.fragment();
        return fragment != null && AesGcmURL.IV_KEY.matcher(fragment).matches();
    }

    private void maybeAutoDownloadKnownSize(final long wireSize) {
        final boolean audio = isAudioMessage();
        final long configuredLimit = manager.getAutoAcceptFileSize();
        final boolean withinConfiguredLimit = configuredLimit > 0 && wireSize <= configuredLimit;
        final boolean withinAudioLimit = audio && wireSize <= AUDIO_AUTO_DOWNLOAD_MAX_BYTES;
        // Secure storage is app-internal and requires no shared-storage permission.
        if ((withinConfiguredLimit || withinAudioLimit) && service.isDataSaverDisabled()) {
            acceptedAutomatically = true;
            download(false);
        } else {
            acceptedAutomatically = false;
            changeStatus(STATUS_OFFER);
            service.getNotificationService().push(secureMessage);
        }
    }

    private void download(final boolean interactive) {
        EXECUTOR.execute(new SecureDownloader(interactive));
    }

    private void checkFileSize(final boolean interactive) {
        EXECUTOR.execute(() -> {
            Exception failure = null;
            try {
                final long wireSize = retrieveFileSize(interactive);
                expectedWireSize = wireSize;
                expectedPlaintextSize = isWireEncrypted() && wireSize >= 16L ? wireSize - 16L : wireSize;
                final Message.FileParams fileParams = secureMessage.getFileParams();
                final String probeMime = resolveMimeType(probedContentType);
                final String probeFileName =
                        resolveIncomingFileName(
                                secureMessage.getSecureMediaFileName(),
                                probedContentDisposition,
                                url.toString());
                secureMessage.setSecureMediaPresentationMetadata(
                        probeMime,
                        probeFileName,
                        expectedPlaintextSize);
                FileBackend.updateFileParams(secureMessage, fileParams.url, expectedPlaintextSize);
                secureMessage.setOob(true);
                service.databaseBackend.updateMessage(secureMessage, true);
                secureMessage.resetFileParams();
                maybeAutoDownloadKnownSize(wireSize);
                return;
            } catch (final Exception e) {
                failure = e;
                Log.d(Config.LOGTAG, "secure HTTP size preflight failed", e);
            }

            acceptedAutomatically = false;
            // Preflight is intentionally single-shot. If HEAD plus ranged GET cannot establish
            // size quickly, return control to the user instead of keeping the card busy.
            changeStatus(STATUS_OFFER);
            if (interactive) {
                showToastForException(failure);
            } else {
                service.getNotificationService().push(secureMessage);
            }
        });
    }

    private long retrieveFileSize(final boolean interactive) throws IOException {
        changeStatus(STATUS_CHECKING);
        final OkHttpClient client =
                manager.buildHttpClient(
                                url,
                                secureMessage.getConversation().getAccount(),
                                PREFLIGHT_READ_TIMEOUT_SECONDS,
                                interactive)
                        .newBuilder()
                        .connectTimeout(PREFLIGHT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .callTimeout(PREFLIGHT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build();

        IOException headFailure = null;
        final Request headRequest = new Request.Builder()
                .url(URL.stripFragment(url))
                .addHeader("Accept-Encoding", "identity")
                .head()
                .build();
        mostRecentCall = client.newCall(headRequest);
        try (final Response response = mostRecentCall.execute()) {
            if (response.code() >= 200 && response.code() < 300) {
                captureProbeMetadata(response);
                final String contentLength = response.header("Content-Length");
                if (!Strings.isNullOrEmpty(contentLength)) {
                    final Long parsed = Longs.tryParse(contentLength);
                    if (parsed != null && parsed >= 0) {
                        return parsed;
                    }
                }
                headFailure = new IOException("no usable content-length found in HEAD response");
            } else {
                headFailure = new IOException("HTTP HEAD status " + response.code());
            }
        } catch (final IOException e) {
            headFailure = e;
        }

        Log.d(Config.LOGTAG, "secure HEAD size probe failed; falling back to ranged GET", headFailure);
        final Request rangeRequest = new Request.Builder()
                .url(URL.stripFragment(url))
                .addHeader("Accept-Encoding", "identity")
                .addHeader("Range", "bytes=0-0")
                .get()
                .build();
        mostRecentCall = client.newCall(rangeRequest);
        try (final Response response = mostRecentCall.execute()) {
            throwOnInvalidCode(response);
            captureProbeMetadata(response);
            final String contentRange = response.header("Content-Range");
            if (!Strings.isNullOrEmpty(contentRange)) {
                final int slash = contentRange.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < contentRange.length()) {
                    final Long parsed = Longs.tryParse(contentRange.substring(slash + 1).trim());
                    if (parsed != null && parsed >= 0) {
                        return parsed;
                    }
                }
            }
            if (response.code() == 200) {
                final Long parsed = Longs.tryParse(Strings.nullToEmpty(response.header("Content-Length")));
                if (parsed != null && parsed >= 0) {
                    return parsed;
                }
            }
            throw new IOException("server did not report file size via HEAD or ranged GET");
        }
    }

    private void captureProbeMetadata(final Response response) {
        probedContentType = response.header("Content-Type");
        probedContentDisposition = response.header("Content-Disposition");
    }

    @Override
    public void cancel() {
        final Call call = mostRecentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        manager.finishConnection(this);
        secureMessage.setTransferable(null);
        if (secureMessage.isFileOrImage()) {
            secureMessage.setDeleted(true);
        }
        manager.updateConversationUi(true);
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public Long getFileSize() {
        return expectedPlaintextSize >= 0 ? expectedPlaintextSize : null;
    }

    @Override
    public int getProgress() {
        return progress;
    }

    @Override
    public Message getMessage() {
        return secureMessage;
    }

    private void changeStatus(final int newStatus) {
        status = newStatus;
        manager.updateConversationUi(true);
    }

    private void updateProgress(final long transmittedPlaintext) {
        if (expectedPlaintextSize > 0) {
            progress = (int) Math.min(100L, Math.round(((double) transmittedPlaintext / expectedPlaintextSize) * 100.0));
        }
        manager.updateConversationUi(false);
    }

    private void showToastForException(@Nullable final Exception error) {
        final Call call = mostRecentCall;
        if (error == null || (call != null && call.isCanceled())) {
            return;
        }
        if (error instanceof java.net.UnknownHostException) {
            service.showErrorToastInUi(R.string.download_failed_server_not_found);
        } else if (error instanceof java.net.ConnectException) {
            service.showErrorToastInUi(R.string.download_failed_could_not_connect);
        } else {
            service.showErrorToastInUi(R.string.file_transmission_failed);
        }
    }

    private void finishSuccessfulDownload() throws IOException {
        final String remoteUrl = isWireEncrypted() ? AesGcmURL.toAesGcmUrl(url) : url.toString();
        /*
         * The Store commit is the receive authority. Rebuild all render-critical params from the
         * committed relation before the message is persisted or the UI is notified. The legacy
         * helper only records URL/size and leaves image type/dimensions unavailable.
         */
        new SecureMessageMediaFileParamsUpdater(service, secureStore())
                .update(secureMessage, remoteUrl);
        secureMessage.setOob(true);
        service.updateMessage(secureMessage);
        secureMessage.setTransferable(null);
        manager.finishConnection(this);
        final boolean notify = acceptedAutomatically && !secureMessage.isRead();
        manager.updateConversationUi(true);
        if (notify) {
            service.getNotificationService().push(secureMessage);
        }
    }

    private final class SecureDownloader implements Runnable {
        private final boolean interactive;

        private SecureDownloader(final boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            try {
                progress = 0;
                changeStatus(STATUS_DOWNLOADING);
                if (resolveExistingSecureMedia()) {
                    finishSuccessfulDownload();
                    return;
                }
                downloadResumable();
                finishSuccessfulDownload();
            } catch (final SSLHandshakeException e) {
                progress = 0;
                changeStatus(STATUS_OFFER);
            } catch (final Exception e) {
                Log.d(Config.LOGTAG, "secure HTTP download failed", e);
                acceptedAutomatically = false;
                progress = 0;
                changeStatus(STATUS_OFFER);
                if (interactive) {
                    showToastForException(e);
                } else {
                    service.getNotificationService().push(secureMessage);
                }
            }
        }

        /**
         * Keeps one Store write session and one wire-decryption state alive while HTTP reconnects.
         *
         * A network interruption therefore resumes from the exact wire byte offset instead of
         * discarding already authenticated plaintext and restarting a large attachment from zero.
         * The Store remains commit-only: the object is not reader-visible until the complete
         * response has been authenticated, the writer has closed, and session.commit() succeeds.
         */
        private void downloadResumable() throws Exception {
            final SecureContentStore store = secureStore();
            final SecureMessageMediaIngress ingress = new SecureMessageMediaIngress(store);
            final WireDecoder decoder = new WireDecoder();

            SecureMessageMediaIngress.Session session = null;
            OutputStream secureOutput = null;
            long wireReceived = 0L;
            IOException lastNetworkFailure = null;

            try {
                for (int attempt = 1; attempt <= MAX_HTTP_DOWNLOAD_ATTEMPTS; attempt++) {
                    final long requestOffset = wireReceived;
                    final OkHttpClient client =
                            manager.buildHttpClient(
                                    url,
                                    secureMessage.getConversation().getAccount(),
                                    interactive);
                    final Request.Builder requestBuilder =
                            new Request.Builder()
                                    .url(URL.stripFragment(url))
                                    .addHeader("Accept-Encoding", "identity")
                                    .get();
                    if (requestOffset > 0L) {
                        requestBuilder.addHeader(
                                "Range",
                                String.format(
                                        Locale.ENGLISH,
                                        "bytes=%d-",
                                        requestOffset));
                    }
                    mostRecentCall = client.newCall(requestBuilder.build());

                    try (final Response response = mostRecentCall.execute()) {
                        throwOnInvalidCode(response);
                        if (requestOffset > 0L
                                && !isResumeContentRange(
                                        response.header("Content-Range"),
                                        requestOffset)) {
                            throw new NonRetryableDownloadException(
                                    "HTTP server did not honor secure media resume offset "
                                            + requestOffset);
                        }
                        if (response.body() == null) {
                            throw new IOException("HTTP response contained no body");
                        }

                        final String contentLength = response.header("Content-Length");
                        final Long responseWireSize =
                                Strings.isNullOrEmpty(contentLength)
                                        ? null
                                        : Longs.tryParse(contentLength);

                        if (requestOffset == 0L
                                && expectedWireSize <= 0L
                                && responseWireSize != null
                                && responseWireSize >= 0L) {
                            expectedWireSize = responseWireSize;
                            expectedPlaintextSize =
                                    isWireEncrypted() && responseWireSize >= 16L
                                            ? responseWireSize - 16L
                                            : responseWireSize;
                        }

                        if (expectedWireSize > 0L && responseWireSize != null) {
                            final long expectedRemaining = expectedWireSize - requestOffset;
                            if (expectedRemaining < 0L
                                    || responseWireSize.longValue() != expectedRemaining) {
                                throw new NonRetryableDownloadException(
                                        "HTTP content-length mismatch: received "
                                                + responseWireSize
                                                + " expected remaining "
                                                + expectedRemaining);
                            }
                        }

                        if (session == null) {
                            final String mime =
                                    resolveMimeType(response.header("Content-Type"));
                            final Long expected =
                                    expectedPlaintextSize >= 0L
                                            ? expectedPlaintextSize
                                            : null;
                            session =
                                    ingress.begin(
                                            accountUuid(),
                                            secureMessage.getUuid(),
                                            mime,
                                            expected,
                                            secureMessage.getSecureMediaFileName());
                            secureOutput = session.openPlaintextOutputStream();
                        }

                        final byte[] buffer = new byte[32 * 1024];
                        try (InputStream rawInput = response.body().byteStream()) {
                            int count;
                            while ((count = rawInput.read(buffer)) != -1) {
                                if (expectedWireSize > 0L
                                        && wireReceived + count > expectedWireSize) {
                                    throw new NonRetryableDownloadException(
                                            "secure HTTP download exceeded expected wire size");
                                }
                                try {
                                    decoder.write(buffer, 0, count, secureOutput);
                                } catch (final IOException | RuntimeException e) {
                                    throw new NonRetryableDownloadException(
                                            "unable to write secure media stream",
                                            e);
                                }
                                wireReceived += count;
                                if (expectedPlaintextSize > 0L
                                        && decoder.plaintextBytes()
                                                > expectedPlaintextSize) {
                                    throw new NonRetryableDownloadException(
                                            "secure HTTP download exceeded expected plaintext size");
                                }
                                updateProgress(decoder.plaintextBytes());
                            }
                        }

                        if (expectedWireSize > 0L && wireReceived < expectedWireSize) {
                            throw new IOException(
                                    "secure HTTP response ended early at "
                                            + wireReceived
                                            + " of "
                                            + expectedWireSize
                                            + " wire bytes");
                        }

                        try {
                            decoder.finish(secureOutput);
                            if (expectedPlaintextSize > 0L
                                    && decoder.plaintextBytes()
                                            != expectedPlaintextSize) {
                                throw new NonRetryableDownloadException(
                                        "secure HTTP plaintext size mismatch: received "
                                                + decoder.plaintextBytes()
                                                + " expected "
                                                + expectedPlaintextSize);
                            }
                            updateProgress(decoder.plaintextBytes());
                            secureOutput.flush();
                            secureOutput.close();
                            secureOutput = null;
                            session.commit();
                            session = null;
                            return;
                        } catch (final NonRetryableDownloadException e) {
                            throw e;
                        } catch (final Exception e) {
                            throw new NonRetryableDownloadException(
                                    "unable to finalize secure media receive",
                                    e);
                        }
                    } catch (final NonRetryableDownloadException e) {
                        throw e;
                    } catch (final SSLHandshakeException e) {
                        throw e;
                    } catch (final IOException e) {
                        lastNetworkFailure = e;
                        Log.d(
                                Config.LOGTAG,
                                "secure HTTP receive reconnect "
                                        + attempt
                                        + "/"
                                        + MAX_HTTP_DOWNLOAD_ATTEMPTS
                                        + " at wire byte "
                                        + wireReceived,
                                e);
                        if (attempt >= MAX_HTTP_DOWNLOAD_ATTEMPTS) {
                            throw e;
                        }
                        sleepBeforeRetry(attempt);
                    }
                }

                if (lastNetworkFailure != null) {
                    throw lastNetworkFailure;
                }
                throw new IOException("secure HTTP download did not complete");
            } finally {
                if (secureOutput != null) {
                    try {
                        secureOutput.close();
                    } catch (final Exception ignored) {
                        // Session abort below remains authoritative.
                    }
                }
                if (session != null) {
                    session.abort();
                }
            }
        }
    }

    static boolean isResumeContentRange(
            @Nullable final String contentRange,
            final long expectedOffset) {
        return expectedOffset > 0L
                && contentRange != null
                && contentRange
                        .toLowerCase(Locale.US)
                        .startsWith("bytes " + expectedOffset + "-");
    }

    private final class WireDecoder {
        @Nullable private final AEADBlockCipher cipher;
        private long plaintextBytes = 0L;
        private boolean finished = false;

        private WireDecoder() throws IOException {
            if (!isWireEncrypted()) {
                cipher = null;
                return;
            }
            final String fragment = url.fragment();
            if (fragment == null || !AesGcmURL.IV_KEY.matcher(fragment).matches()) {
                throw new IOException("invalid authenticated media fragment");
            }
            final byte[] combined = CryptoHelper.hexToBytes(fragment);
            final KeyIv keyIv = splitKeyAndIv(combined);
            try {
                final AEADBlockCipher initialized =
                        new GCMBlockCipher(new AESEngine());
                initialized.init(
                        false,
                        new AEADParameters(
                                new KeyParameter(keyIv.key),
                                128,
                                keyIv.iv));
                cipher = initialized;
            } catch (final Exception e) {
                throw new IOException(
                        "unable to initialize authenticated media decryption",
                        e);
            }
        }

        private long plaintextBytes() {
            return plaintextBytes;
        }

        private void write(
                final byte[] input,
                final int offset,
                final int length,
                final OutputStream output)
                throws IOException {
            if (finished) {
                throw new IOException("wire decoder is already final");
            }
            if (cipher == null) {
                output.write(input, offset, length);
                plaintextBytes += length;
                return;
            }
            try {
                final byte[] decoded =
                        new byte[Math.max(1, cipher.getOutputSize(length))];
                final int produced =
                        cipher.processBytes(
                                input,
                                offset,
                                length,
                                decoded,
                                0);
                if (produced > 0) {
                    output.write(decoded, 0, produced);
                    plaintextBytes += produced;
                }
            } catch (final RuntimeException e) {
                throw new IOException(
                        "unable to decrypt authenticated media chunk",
                        e);
            }
        }

        private void finish(final OutputStream output) throws IOException {
            if (finished) {
                return;
            }
            finished = true;
            if (cipher == null) {
                return;
            }
            try {
                final byte[] decoded =
                        new byte[Math.max(1, cipher.getOutputSize(0))];
                final int produced = cipher.doFinal(decoded, 0);
                if (produced > 0) {
                    output.write(decoded, 0, produced);
                    plaintextBytes += produced;
                }
            } catch (final InvalidCipherTextException | RuntimeException e) {
                throw new IOException(
                        "authenticated media verification failed",
                        e);
            }
        }
    }

    private static final class NonRetryableDownloadException extends IOException {
        private NonRetryableDownloadException(final String message) {
            super(message);
        }

        private NonRetryableDownloadException(
                final String message,
                final Throwable cause) {
            super(message, cause);
        }
    }

    private String resolveMimeType(@Nullable final String responseContentType) {
        final String current = secureMessage.getMimeType();
        if (!Strings.isNullOrEmpty(current)) {
            return current;
        }
        if (!Strings.isNullOrEmpty(responseContentType)) {
            final int separator = responseContentType.indexOf(';');
            return separator < 0 ? responseContentType.trim() : responseContentType.substring(0, separator).trim();
        }
        final String extension =
                eu.siacs.conversations.services.AbstractConnectionManager.Extension
                        .of(url.encodedPath())
                        .getExtension();
        return Strings.isNullOrEmpty(extension) ? null : MimeUtils.guessMimeTypeFromExtension(extension);
    }

    private static KeyIv splitKeyAndIv(final byte[] combined) throws IOException {
        final byte[] key = new byte[32];
        final byte[] iv;
        final int keyOffset;
        if (combined.length == 48) {
            iv = new byte[16];
            keyOffset = 16;
        } else if (combined.length == 44) {
            iv = new byte[12];
            keyOffset = 12;
        } else if (combined.length >= 32) {
            iv = new byte[] {
                    0x00, 0x01, 0x02, 0x03,
                    0x04, 0x05, 0x06, 0x07,
                    0x08, 0x09, 0x0a, 0x0b,
                    0x0c, 0x0d, 0x0e, 0x0f
            };
            keyOffset = 0;
        } else {
            throw new IOException("invalid AES-GCM media fragment length");
        }
        if (keyOffset > 0) {
            System.arraycopy(combined, 0, iv, 0, iv.length);
        }
        System.arraycopy(combined, keyOffset, key, 0, 32);
        return new KeyIv(key, iv);
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

    private static final class KeyIv {
        private final byte[] key;
        private final byte[] iv;

        private KeyIv(final byte[] key, final byte[] iv) {
            this.key = key;
            this.iv = iv;
        }
    }
}
