package eu.siacs.conversations.http;

import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway;
import eu.siacs.conversations.storage.secure.SecureMediaPerfTrace;
import eu.siacs.conversations.storage.secure.SecureMessageMediaFileParamsUpdater;
import eu.siacs.conversations.storage.secure.SecureOutgoingLegacyMediaRetirement;
import eu.siacs.conversations.utils.CryptoHelper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HttpUploadConnection implements Transferable, AbstractConnectionManager.ProgressListener {

    private static final int MAX_UPLOAD_ATTEMPTS = 3;

    static final List<String> WHITE_LISTED_HEADERS = Arrays.asList(
            "Authorization",
            "Cookie",
            "Expires"
    );

    private final HttpConnectionManager mHttpConnectionManager;
    private final XmppConnectionService mXmppConnectionService;
    private final Method method;
    private boolean delayed = false;
    private DownloadableFile file;
    private final Message message;
    private SlotRequester.Slot slot;
    private byte[] key = null;

    /**
     * Disabled-by-default secure Store source for one no-wire-protection upload attempt.
     *
     * The legacy DownloadableFile remains only for display metadata and compatibility consumers;
     * the HTTP body itself is sourced from the committed Store object when this is non-null.
     */
    @Nullable private SecureContentUploadRoute secureContentUploadRoute;
    @Nullable private SecureContentUploadDescriptor secureContentUploadDescriptor;

    private long transmitted = 0;
    private Call mostRecentCall;
    private ListenableFuture<SlotRequester.Slot> slotFuture;
    private Account account;
    private int uploadAttempt = 0;
    private volatile boolean cancelled = false;
    private volatile long slotRequestStartedNanos = 0L;

    public HttpUploadConnection(Message message, Method method, HttpConnectionManager httpConnectionManager) {
        this.message = message;
        this.method = method;
        this.mHttpConnectionManager = httpConnectionManager;
        this.mXmppConnectionService = httpConnectionManager.getXmppConnectionService();
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public int getStatus() {
        return STATUS_UPLOADING;
    }

    @Override
    public Long getFileSize() {
        if (secureContentUploadDescriptor != null) {
            return secureContentUploadDescriptor.getPlaintextSizeBytes()
                    + (key == null ? 0 : 16);
        }
        return file == null ? null : file.getExpectedSize();
    }

    @Override
    public int getProgress() {
        final Long expectedSize = getFileSize();
        if (expectedSize == null || expectedSize <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round((((double) transmitted) / expectedSize) * 100));
    }

    @Override
    public void cancel() {
        this.cancelled = true;
        final ListenableFuture<SlotRequester.Slot> slotFuture = this.slotFuture;
        if (slotFuture != null && !slotFuture.isDone()) {
            if (slotFuture.cancel(true)) {
                Log.d(Config.LOGTAG,"cancelled slot requester");
            }
        }
        final Call call = this.mostRecentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
            Log.d(Config.LOGTAG,"cancelled HTTP request");
        }
    }

    private void fail(String errorMessage) {
        finish();
        final Call call = this.mostRecentCall;
        final Future<SlotRequester.Slot> slotFuture = this.slotFuture;
        final boolean cancelled = (call != null && call.isCanceled()) || (slotFuture != null && slotFuture.isCancelled());
        mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED, cancelled ? Message.ERROR_MESSAGE_CANCELLED : errorMessage);
    }

    private void finish() {
        clearSecureContentUploadRoute();
        mHttpConnectionManager.finishUploadConnection(this);
        message.setTransferable(null);
    }

    /**
     * Clears connection-local upload handles only. The route stages durable message-owned secure
     * media, so terminal HTTP outcomes must not retire the Store relation.
     */
    private void clearSecureContentUploadRoute() {
        this.secureContentUploadRoute = null;
        this.secureContentUploadDescriptor = null;
    }

    /**
     * Retires the transition plaintext only after the secure media relation is committed and the
     * HTTP upload has completed successfully. Failed/cancelled uploads keep the legacy source so
     * existing alternative retry transports are not broken during rollout.
     */
    private void retireLegacyPlaintextAfterSecureUpload() throws IOException {
        if (secureContentUploadDescriptor == null || file == null) {
            return;
        }
        final Conversations application = (Conversations) mXmppConnectionService.getApplication();
        final SecureOutgoingLegacyMediaRetirement retirement =
                new SecureOutgoingLegacyMediaRetirement(
                        application.getSecureContentStoreProvider().get());
        final boolean hadLegacyPlaintext = file.exists();
        if (!retirement.retireIfSecurePublished(
                message.getConversation().getAccount().getUuid(), message.getUuid(), file)) {
            throw new IOException(
                    "secure upload completed without a committed secure media relation");
        }
        if (hadLegacyPlaintext) {
            mXmppConnectionService.getFileBackend().updateMediaScanner(file);
        }
    }

    public void init(boolean delay) {
        SecureMediaPerfTrace.gapSinceLastEvent(message.getUuid(), "upload_handoff_wait");
        final Account account = message.getConversation().getAccount();
        this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
        final String legacyMime = this.file.getMimeType();
        this.delayed = delay;
        final boolean requiresWireProtection =
                Config.ENCRYPT_ON_HTTP_UPLOADED
                        || message.getEncryption() == Message.ENCRYPTION_AXOLOTL;
        if (requiresWireProtection) {
            this.key = new byte[44];
            SECURE_RANDOM.nextBytes(this.key);
            this.file.setKeyAndIv(this.key);
        }
        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            try {
                prepareExistingSecureContentUpload(account);
            } catch (final Exception e) {
                Log.e(Config.LOGTAG, "unable to reuse secure content upload source", e);
                fail(
                        mXmppConnectionService.getString(
                                eu.siacs.conversations.R.string.file_transmission_failed));
                return;
            }
        }

        final long originalFileSize;
        final String mime;
        if (secureContentUploadDescriptor == null) {
            originalFileSize = file.getSize();
            mime = legacyMime;
            if (!file.exists() || !file.isFile() || originalFileSize <= 0) {
                Log.e(
                        Config.LOGTAG,
                        "http upload preflight failed: local file missing or empty");
                fail(
                        mXmppConnectionService.getString(
                                eu.siacs.conversations.R.string.error_file_not_found));
                return;
            }
            if (Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
                try {
                    prepareSecureContentUploadSource(account, mime, originalFileSize);
                } catch (final Exception e) {
                    Log.e(Config.LOGTAG, "unable to stage secure content upload source", e);
                    fail(
                            mXmppConnectionService.getString(
                                    eu.siacs.conversations.R.string.file_transmission_failed));
                    return;
                }
            }
        } else {
            originalFileSize = secureContentUploadDescriptor.getPlaintextSizeBytes();
            mime = secureContentUploadDescriptor.getMimeType();
        }
        final long expectedUploadSize =
                (secureContentUploadDescriptor == null
                                ? originalFileSize
                                : secureContentUploadDescriptor.getPlaintextSizeBytes())
                        + (file.getKey() != null ? 16 : 0);
        this.file.setExpectedSize(expectedUploadSize);
        final long serverMax =
                account.getXmppConnection() == null
                        ? -1
                        : account.getXmppConnection().getFeatures().getMaxHttpUploadSize();
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": HTTP upload preflight file="
                        + (secureContentUploadDescriptor == null
                                ? file.getName()
                                : secureContentUploadDescriptor.getDisplayFilename())
                        + " mime="
                        + mime
                        + " raw="
                        + originalFileSize
                        + " payload="
                        + expectedUploadSize
                        + " serverMax="
                        + serverMax);
        if (serverMax > 0 && expectedUploadSize > serverMax) {
            fail(mXmppConnectionService.getString(eu.siacs.conversations.R.string.file_too_large));
            return;
        }
        message.resetFileParams();
        this.account = account;
        message.setTransferable(this);
        mXmppConnectionService.markMessage(message, Message.STATUS_UNSEND);
        requestFreshSlotAndUpload();
    }

    /**
     * Reuses a committed Store relation before any legacy plaintext existence/size preflight.
     * A present-but-invalid secure relation fails closed and is never replaced by FileBackend.
     */
    private void prepareExistingSecureContentUpload(final Account account) throws IOException {
        final Conversations application = (Conversations) mXmppConnectionService.getApplication();
        final SecureContentStore store = application.getSecureContentStoreProvider().get();
        final SecureContentUploadRoute route =
                new SecureContentUploadRoute(new SecureContentTransferGateway(store));
        final long resolveStarted = System.nanoTime();
        final SecureContentUploadDescriptor descriptor;
        try {
            descriptor = route.reuseExisting(account.getUuid(), message.getUuid());
        } finally {
            SecureMediaPerfTrace.stage(
                    message.getUuid(),
                    "upload_source_resolve",
                    System.nanoTime() - resolveStarted);
        }
        if (descriptor != null) {
            this.secureContentUploadRoute = route;
            this.secureContentUploadDescriptor = descriptor;
        }
    }

    /**
     * Stages one legacy-selected source into the Store. The resulting HTTP body is Store-backed;
     * no path or File is passed beyond this migration boundary.
     *
     * This no-wire route intentionally does not replace XEP-0454/Axolotl HTTP protection. Those
     * uploads stay on the legacy route until a compatible WireProtectionAdapter is implemented.
     */
    private void prepareSecureContentUploadSource(
            final Account account, final String mime, final long plaintextSize) throws IOException {
        if (file == null) {
            throw new IOException("secure content source is unavailable");
        }
        final Conversations application = (Conversations) mXmppConnectionService.getApplication();
        final SecureContentStore store = application.getSecureContentStoreProvider().get();
        final SecureContentUploadRoute route =
                new SecureContentUploadRoute(new SecureContentTransferGateway(store));
        try (final FileInputStream source = new FileInputStream(file)) {
            this.secureContentUploadDescriptor =
                    route.stage(
                            account.getUuid(),
                            message.getUuid(),
                            FileBackend.userVisiblePlaintextFileName(
                                    message.getUuid(), file.getName()),
                            mime,
                            plaintextSize,
                            source);
        }
        this.secureContentUploadRoute = route;
    }

    private void requestFreshSlotAndUpload() {
        SecureMediaPerfTrace.gapSinceLastEvent(message.getUuid(), "upload_init_misc");
        final Account currentAccount = this.account;
        if (currentAccount == null) {
            fail(mXmppConnectionService.getString(eu.siacs.conversations.R.string.file_transmission_failed));
            return;
        }
        if (isCancelled()) {
            fail(Message.ERROR_MESSAGE_CANCELLED);
            return;
        }
        this.transmitted = 0;
        this.uploadAttempt++;
        final String mime = this.file == null ? null : this.file.getMimeType();
        Log.d(
                Config.LOGTAG,
                currentAccount.getJid().asBareJid()
                        + ": requesting HTTP upload slot, attempt "
                        + uploadAttempt
                        + "/"
                        + MAX_UPLOAD_ATTEMPTS);
        this.slotRequestStartedNanos = System.nanoTime();
        this.slotFuture =
                secureContentUploadDescriptor == null
                        ? new SlotRequester(mXmppConnectionService)
                                .request(
                                        method,
                                        currentAccount,
                                        new HttpUploadSlotRequest(
                                                FileBackend.userVisiblePlaintextFileName(
                                                        message.getUuid(), file.getName()),
                                                mime,
                                                file.getExpectedSize()))
                        : new SlotRequester(mXmppConnectionService)
                                .request(
                                        method,
                                        currentAccount,
                                        secureContentUploadRoute.slotRequest(
                                                secureContentUploadDescriptor,
                                                secureContentUploadDescriptor
                                                                .getPlaintextSizeBytes()
                                                        + (key == null ? 0 : 16)));
        Futures.addCallback(
                this.slotFuture,
                new FutureCallback<SlotRequester.Slot>() {
                    @Override
                    public void onSuccess(@Nullable final SlotRequester.Slot result) {
                        final long slotStarted = HttpUploadConnection.this.slotRequestStartedNanos;
                        HttpUploadConnection.this.slotRequestStartedNanos = 0L;
                        if (slotStarted != 0L) {
                            SecureMediaPerfTrace.stage(
                                    message.getUuid(),
                                    "upload_slot_wait",
                                    System.nanoTime() - slotStarted);
                        }
                        if (result == null) {
                            retryOrFail("upload service returned an empty slot", null);
                            return;
                        }
                        HttpUploadConnection.this.slot = result;
                        if (key != null && !result.get.isHttps()) {
                            Log.e(
                                    Config.LOGTAG,
                                    "refusing encrypted HTTP upload slot with non-HTTPS GET URL: "
                                            + result.get.host()
                                            + ":"
                                            + result.get.port());
                            fail(
                                    mXmppConnectionService.getString(
                                            eu.siacs.conversations.R.string.file_transmission_failed));
                            return;
                        }
                        try {
                            HttpUploadConnection.this.upload();
                        } catch (final Exception e) {
                            retryOrFail("unable to start HTTP upload", e);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        final long slotStarted = HttpUploadConnection.this.slotRequestStartedNanos;
                        HttpUploadConnection.this.slotRequestStartedNanos = 0L;
                        if (slotStarted != 0L) {
                            SecureMediaPerfTrace.stage(
                                    message.getUuid(),
                                    "upload_slot_wait",
                                    System.nanoTime() - slotStarted);
                        }
                        retryOrFail("unable to request HTTP upload slot", throwable);
                    }
                },
                MoreExecutors.directExecutor());
    }

    private boolean isCancelled() {
        final Call call = this.mostRecentCall;
        final Future<SlotRequester.Slot> future = this.slotFuture;
        return cancelled
                || (call != null && call.isCanceled())
                || (future != null && future.isCancelled());
    }

    private void retryOrFail(final String stage, @Nullable final Throwable throwable) {
        if (isCancelled()) {
            fail(Message.ERROR_MESSAGE_CANCELLED);
            return;
        }
        if (throwable == null) {
            Log.w(Config.LOGTAG, stage + " (attempt " + uploadAttempt + ")");
        } else {
            Log.w(Config.LOGTAG, stage + " (attempt " + uploadAttempt + ")", throwable);
        }
        if (uploadAttempt < MAX_UPLOAD_ATTEMPTS) {
            HttpConnectionManager.EXECUTOR.execute(
                    () -> {
                        try {
                            Thread.sleep(300L * uploadAttempt);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        if (!isCancelled()) {
                            requestFreshSlotAndUpload();
                        }
                    });
            return;
        }
        final String detail = throwable == null ? null : throwable.getMessage();
        fail(
                detail == null || detail.trim().isEmpty()
                        ? mXmppConnectionService.getString(
                                eu.siacs.conversations.R.string.file_transmission_failed)
                        : detail);
    }

    private boolean shouldRetryHttpCode(final int code) {
        return code == 403
                || code == 408
                || code == 425
                || code == 429
                || (code >= 500 && code < 600);
    }

    private void upload() {
        final OkHttpClient client = mHttpConnectionManager.buildHttpClient(
                slot.put,
                message.getConversation().getAccount(),
                0,
                true
        );
        final RequestBody requestBody =
                secureContentUploadDescriptor == null
                        ? AbstractConnectionManager.requestBody(file, this)
                        : secureContentUploadRoute.requestBody(
                                secureContentUploadDescriptor,
                                file.getKey(),
                                file.getIv(),
                                this::onProgress);
        final Request request = new Request.Builder()
                .url(slot.put)
                .put(requestBody)
                .headers(slot.headers)
                .build();
        if (secureContentUploadDescriptor == null &&
                (!file.exists() || !file.isFile() || file.length() <= 0)) {
            fail(mXmppConnectionService.getString(eu.siacs.conversations.R.string.error_file_not_found));
            return;
        }
        Log.d(
                Config.LOGTAG,
                "uploading file to " + slot.put.host() + " (attempt " + uploadAttempt + ")");
        this.mostRecentCall = client.newCall(request);
        this.mostRecentCall.enqueue(
                new Callback() {
                    @Override
                    public void onFailure(@NonNull final Call call, final IOException e) {
                        SecureMediaPerfTrace.gapSinceLastEvent(
                                message.getUuid(), "upload_response_wait");
                        if (call.isCanceled()) {
                            fail(Message.ERROR_MESSAGE_CANCELLED);
                        } else {
                            retryOrFail("HTTP upload failed", e);
                        }
                    }

                    @Override
                    public void onResponse(
                            @NonNull final Call call, @NonNull final Response response) {
                        SecureMediaPerfTrace.gapSinceLastEvent(
                                message.getUuid(), "upload_response_wait");
                        try (response) {
                            final int code = response.code();
                            if (code >= 200 && code < 300) {
                                final long expectedPayloadSize =
                                        secureContentUploadDescriptor == null
                                                ? file.getExpectedSize()
                                                : secureContentUploadDescriptor
                                                                .getPlaintextSizeBytes()
                                                        + (key == null ? 0 : 16);
                                if (expectedPayloadSize > 0 && transmitted != expectedPayloadSize) {
                                    Log.e(
                                            Config.LOGTAG,
                                            "HTTP upload byte-count mismatch: transmitted="
                                                    + transmitted
                                                    + " expected="
                                                    + expectedPayloadSize);
                                    fail(
                                            mXmppConnectionService.getString(
                                                    eu.siacs.conversations.R.string.file_transmission_failed));
                                    return;
                                }
                                Log.d(
                                        Config.LOGTAG,
                                        "finished uploading file on attempt "
                                                + uploadAttempt
                                                + " bytes="
                                                + transmitted);
                                final String get;
                                if (key != null) {
                                    try {
                                        get =
                                                AesGcmURL.toAesGcmUrl(
                                                        slot.get
                                                                .newBuilder()
                                                                .fragment(CryptoHelper.bytesToHex(key))
                                                                .build());
                                    } catch (final IllegalArgumentException e) {
                                        Log.e(
                                                Config.LOGTAG,
                                                "refusing malformed/insecure XEP-0454 upload URL",
                                                e);
                                        fail(
                                                mXmppConnectionService.getString(
                                                        eu.siacs.conversations.R.string.file_transmission_failed));
                                        return;
                                    }
                                    Log.d(
                                            Config.LOGTAG,
                                            "prepared XEP-0454 media URL: host="
                                                    + slot.get.host()
                                                    + " port="
                                                    + slot.get.port()
                                                    + " fragmentBytes="
                                                    + key.length
                                                    + " mime="
                                                    + file.getMimeType()
                                                    + " clear="
                                                    + file.getSize()
                                                    + " encrypted="
                                                    + file.getExpectedSize());
                                } else {
                                    get = slot.get.toString();
                                }
                                if (secureContentUploadDescriptor != null) {
                                    try {
                                        final Conversations application =
                                                (Conversations)
                                                        mXmppConnectionService.getApplication();
                                        new SecureMessageMediaFileParamsUpdater(
                                                        mXmppConnectionService,
                                                        application
                                                                .getSecureContentStoreProvider()
                                                                .get())
                                                .update(message, get);
                                        retireLegacyPlaintextAfterSecureUpload();
                                    } catch (final Exception e) {
                                        Log.e(Config.LOGTAG, "unable to retire legacy upload plaintext", e);
                                        fail(
                                                mXmppConnectionService.getString(
                                                        eu.siacs.conversations.R.string.file_transmission_failed));
                                        return;
                                    }
                                } else {
                                    mXmppConnectionService
                                            .getFileBackend()
                                            .updateFileParams(message, get);
                                    if (file != null && file.exists()) {
                                        mXmppConnectionService
                                                .getFileBackend()
                                                .updateMediaScanner(file);
                                    }
                                }
                                finish();
                                if (!message.isPrivateMessage()) {
                                    message.setCounterpart(
                                            message.getConversation().getJid().asBareJid());
                                }
                                mXmppConnectionService.resendMessage(message, delayed);
                            } else if (shouldRetryHttpCode(code)) {
                                retryOrFail("HTTP upload returned status " + code, null);
                            } else {
                                fail("HTTP upload returned status " + code);
                            }
                        }
                    }
                });
    }

    public Message getMessage() {
        return message;
    }

    @Override
    public void onProgress(final long progress) {
        this.transmitted = progress;
        mHttpConnectionManager.updateConversationUi(false);
    }
}
