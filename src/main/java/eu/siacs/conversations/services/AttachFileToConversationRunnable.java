package eu.siacs.conversations.services;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.storage.secure.SecureContentStore;
import eu.siacs.conversations.storage.secure.SecureContentTransferGateway;
import eu.siacs.conversations.storage.secure.SecureMessageMediaCoordinator;
import eu.siacs.conversations.storage.secure.SecureMessageMediaFileParamsUpdater;
import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentPolicy;
import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentPreparation;
import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentStaging;
import eu.siacs.conversations.storage.secure.SecureOutgoingAttachmentStagingRetirer;
import eu.siacs.conversations.storage.secure.SecureOutgoingVideoTranscodingPolicy;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.util.VideoAttachmentStaging;
import eu.siacs.conversations.ui.util.VoiceRecordingStaging;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.TranscoderStrategies;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class AttachFileToConversationRunnable implements Runnable, TranscoderListener {

    private final XmppConnectionService mXmppConnectionService;
    private final Message message;
    private final Uri uri;
    private final String type;
    @Nullable private final String mimeType;
    private final UiCallback<Message> callback;
    @Nullable private final String mediaSendBatchId;
    @Nullable private final String mediaCaptionId;
    @Nullable private final SecureOutgoingAttachmentStagingRetirer stagingRetirer;
    private final boolean isVideoMessage;
    private final long originalFileSize;
    private int currentProgress = -1;
    @Nullable private File transcodedVideoStaging;

    AttachFileToConversationRunnable(
            XmppConnectionService xmppConnectionService,
            Uri uri,
            String type,
            Message message,
            UiCallback<Message> callback,
            @Nullable String mediaSendBatchId,
            @Nullable String mediaCaptionId,
            @Nullable SecureOutgoingAttachmentStagingRetirer stagingRetirer) {
        this.uri = uri;
        this.type = type;
        this.mXmppConnectionService = xmppConnectionService;
        this.message = message;
        this.callback = callback;
        this.mediaSendBatchId = mediaSendBatchId;
        this.mediaCaptionId = mediaCaptionId;
        this.stagingRetirer = stagingRetirer;
        this.mimeType =
                MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        final int autoAcceptFileSize =
                mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize);
        this.originalFileSize = FileBackend.getFileSize(mXmppConnectionService, uri);
        this.isVideoMessage =
                (this.mimeType != null && this.mimeType.startsWith("video/"))
                        && originalFileSize > autoAcceptFileSize
                        && !"uncompressed".equals(getVideoCompression())
                        && !VideoAttachmentStaging.isControlledUri(
                                mXmppConnectionService, uri);
    }

    boolean isVideoMessage() {
        return this.isVideoMessage;
    }

    private void processAsFile() {
        if (Config.SECURE_CONTENT_MEDIA_ROLLOUT
                && (SecureOutgoingAttachmentPolicy.isSimpleFile(mimeType)
                        || SecureOutgoingAttachmentPolicy.isImageAttachment(mimeType)
                        || SecureOutgoingAttachmentPolicy.isVideoAttachment(mimeType)
                        || SecureOutgoingAttachmentPolicy.isInlineVoiceRecording(
                                mimeType,
                                VoiceRecordingStaging.isControlledUri(
                                        mXmppConnectionService, uri)))) {
            processAsSecureFile();
        } else {
            processAsLegacyFile();
        }
    }

    private boolean isAppOwnedContentUri() {
        return ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                && FileBackend.getAuthority(mXmppConnectionService).equals(uri.getAuthority());
    }

    private void processAsSecureFile() {
        processAsSecureFile(uri, mimeType, originalFileSize, stagingRetirer);
    }

    /** Publishes a producer-owned URI only after Secure Content commits the exact message binding. */
    private void processAsSecureFile(
            final Uri sourceUri,
            @Nullable final String sourceMimeType,
            final long sourceSize,
            @Nullable final SecureOutgoingAttachmentStagingRetirer sourceStagingRetirer) {
        SecureContentStore store = null;
        SecureOutgoingAttachmentPreparation.PreparedAttachment prepared = null;
        try {
            synchronized (mXmppConnectionService.secureContentMediaMutationLock()) {
                if (!mXmppConnectionService.isSecureContentAccountAvailableForMutation(message)) {
                    throw new IOException("Account cleanup is in progress");
                }
                final Conversations application =
                        (Conversations) mXmppConnectionService.getApplication();
                store = application.getSecureContentStoreProvider().get();
                final Long expectedSize = sourceSize > 0 ? sourceSize : null;
                prepared =
                        SecureOutgoingAttachmentPreparation.create(
                                        mXmppConnectionService.getContentResolver(), store)
                                .prepareUriAttachment(
                                        message.getConversation().getAccount().getUuid(),
                                        message.getUuid(),
                                        sourceUri,
                                        sourceMimeType,
                                        expectedSize);
                new SecureMessageMediaFileParamsUpdater(mXmppConnectionService, store)
                        .update(message, null);
                SecureOutgoingAttachmentStaging.retireAfterCommittedPublication(
                        sourceStagingRetirer);
            }
        } catch (final Exception e) {
            if (store != null && prepared != null && prepared.getCreated()) {
                try {
                    new SecureMessageMediaCoordinator(
                                    store, new SecureContentTransferGateway(store))
                            .retire(prepared.getBinding());
                } catch (final Exception cleanupError) {
                    Log.w(
                            Config.LOGTAG,
                            "unable to retire failed secure outgoing attachment",
                            cleanupError);
                }
            }
            // A failed publication is retryable. The UI restores the original attachment back to
            // the composer, so retiring caller-owned staging here would leave a visible draft whose
            // content URI points to a deleted file. Producer staging is retired only after a
            // committed publication; abandoned leftovers remain bounded by the staging sweeper.
            retireFailedVideoStaging();
            Log.e(Config.LOGTAG, "unable to prepare secure outgoing attachment", e);
            mXmppConnectionService.failMediaSendBatchMessage(
                    message, mediaSendBatchId, mediaCaptionId);
            callback.error(R.string.error_io_exception, message);
            return;
        }
        // The UI callback runs before publication so its main-thread removal of the
        // transient bubble is ordered ahead of the one atomic real-media update below.
        callback.success(message);
        mXmppConnectionService.sendPreparedMediaMessage(
                message, mediaSendBatchId, mediaCaptionId);
    }

    private void processAsLegacyFile() {
        // Plaintext rollout still owns its media. Never retain a direct path to a user-selected
        // external file; copy every outgoing attachment into account-scoped app-private storage
        // so send, retry, cleanup and the attachment browser all use one stable ownership model.
        final boolean appOwnedContentUri = isAppOwnedContentUri();
        try {
            if (appOwnedContentUri && type != null && type.startsWith("audio/")) {
                mXmppConnectionService
                        .getFileBackend()
                        .copyVoiceRecordingToPrivateStorage(message, uri, type);
            } else {
                mXmppConnectionService
                        .getFileBackend()
                        .copyFileToPrivateStorage(message, uri, type);
            }
            mXmppConnectionService.getFileBackend().updateFileParams(message);
            if (stagingRetirer != null && !stagingRetirer.retire()) {
                Log.w(Config.LOGTAG, "unable to retire copied outgoing attachment staging");
            }
            mXmppConnectionService.sendPreparedMediaMessage(message, mediaSendBatchId, mediaCaptionId);
            callback.success(message);
        } catch (final FileBackend.FileCopyException e) {
            mXmppConnectionService.failMediaSendBatchMessage(message, mediaSendBatchId, mediaCaptionId);
            callback.error(e.getResId(), message);
        }
    }

    private void fallbackToProcessAsFile() {
        if (SecureOutgoingVideoTranscodingPolicy.usesPrivateStaging(
                Config.SECURE_CONTENT_MEDIA_ROLLOUT)) {
            retireFailedVideoStaging();
        } else {
            final var file = mXmppConnectionService.getFileBackend().getFile(message);
            if (file.exists() && file.delete()) {
                Log.d(Config.LOGTAG, "deleted preexisting file " + file.getAbsolutePath());
            }
        }
        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(this::processAsFile);
    }

    private void processAsVideo() throws FileNotFoundException {
        Log.d(Config.LOGTAG, "processing file as video");
        mXmppConnectionService.startOngoingVideoTranscodingForegroundNotification();
        final File transcoderOutput;
        if (SecureOutgoingVideoTranscodingPolicy.usesPrivateStaging(
                Config.SECURE_CONTENT_MEDIA_ROLLOUT)) {
            transcoderOutput =
                    VideoAttachmentStaging.createTranscodedFile(
                            mXmppConnectionService, message.getUuid());
            transcodedVideoStaging = transcoderOutput;
        } else {
            mXmppConnectionService
                    .getFileBackend()
                    .setupRelativeFilePath(
                            message, String.format("%s.%s", message.getUuid(), "mp4"));
            transcoderOutput = mXmppConnectionService.getFileBackend().getFile(message);
        }
        if (Objects.requireNonNull(transcoderOutput.getParentFile()).mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directory for video file");
        }

        final boolean highQuality = "720".equals(getVideoCompression());

        final Future<Void> future;
        try {
            future =
                    Transcoder.into(transcoderOutput.getAbsolutePath())
                            .addDataSource(mXmppConnectionService, uri)
                            .setVideoTrackStrategy(
                                    highQuality
                                            ? TranscoderStrategies.VIDEO_720P
                                            : TranscoderStrategies.VIDEO_360P)
                            .setAudioTrackStrategy(
                                    highQuality
                                            ? TranscoderStrategies.AUDIO_HQ
                                            : TranscoderStrategies.AUDIO_MQ)
                            .setListener(this)
                            .transcode();
        } catch (final RuntimeException e) {
            mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
            fallbackToProcessAsFile();
            return;
        }
        try {
            future.get();
        } catch (final InterruptedException e) {
            throw new AssertionError(e);
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof Error) {
                mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
                fallbackToProcessAsFile();
            } else {
                Log.d(Config.LOGTAG, "ignoring execution exception. Handled by onTranscodeFiled()");
            }
        }
    }

    private void retireFailedVideoStaging() {
        final File staging = transcodedVideoStaging;
        transcodedVideoStaging = null;
        if (staging != null && !VideoAttachmentStaging.retire(mXmppConnectionService, staging)) {
            Log.w(Config.LOGTAG, "unable to retire failed outgoing video staging");
        }
    }

    @Override
    public void onTranscodeProgress(double progress) {
        final int p = (int) Math.round(progress * 100);
        if (p > currentProgress) {
            currentProgress = p;
            mXmppConnectionService
                    .getNotificationService()
                    .updateFileAddingNotification(p, message);
        }
    }

    @Override
    public void onTranscodeCompleted(int successCode) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        final File file =
                SecureOutgoingVideoTranscodingPolicy.usesPrivateStaging(
                                Config.SECURE_CONTENT_MEDIA_ROLLOUT)
                        ? transcodedVideoStaging
                        : mXmppConnectionService.getFileBackend().getFile(message);
        if (file == null) {
            fallbackToProcessAsFile();
            return;
        }
        final long convertedFileSize = file.length();
        Log.d(
                Config.LOGTAG,
                "originalFileSize=" + originalFileSize + " convertedFileSize=" + convertedFileSize);
        if (originalFileSize != 0 && convertedFileSize >= originalFileSize) {
            if (SecureOutgoingVideoTranscodingPolicy.usesPrivateStaging(
                    Config.SECURE_CONTENT_MEDIA_ROLLOUT)) {
                if (!VideoAttachmentStaging.retire(mXmppConnectionService, file)) {
                    Log.w(Config.LOGTAG, "unable to retire oversized outgoing video staging");
                    mXmppConnectionService.failMediaSendBatchMessage(
                            message, mediaSendBatchId, mediaCaptionId);
                    callback.error(R.string.error_io_exception, message);
                    return;
                }
                transcodedVideoStaging = null;
                fallbackToProcessAsFile();
                return;
            }
            if (file.delete()) {
                Log.d(
                        Config.LOGTAG,
                        "original file size was smaller. deleting and processing as file");
                fallbackToProcessAsFile();
                return;
            } else {
                Log.d(Config.LOGTAG, "unable to delete converted file");
            }
        }
        if (SecureOutgoingVideoTranscodingPolicy.usesPrivateStaging(
                Config.SECURE_CONTENT_MEDIA_ROLLOUT)) {
            processAsSecureFile(
                    FileBackend.getUriForFile(mXmppConnectionService, file),
                    "video/mp4",
                    convertedFileSize,
                    () -> VideoAttachmentStaging.retire(mXmppConnectionService, file));
            return;
        }
        mXmppConnectionService.getFileBackend().updateFileParams(message);
        mXmppConnectionService.sendPreparedMediaMessage(message, mediaSendBatchId, mediaCaptionId);
        callback.success(message);
    }

    @Override
    public void onTranscodeCanceled() {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        fallbackToProcessAsFile();
    }

    @Override
    public void onTranscodeFailed(@NonNull final Throwable exception) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        Log.d(Config.LOGTAG, "video transcoding failed", exception);
        fallbackToProcessAsFile();
    }

    @Override
    public void run() {
        if (this.isVideoMessage()) {
            try {
                processAsVideo();
            } catch (final FileNotFoundException e) {
                processAsFile();
            }
        } else {
            processAsFile();
        }
    }

    private String getVideoCompression() {
        return getVideoCompression(mXmppConnectionService);
    }

    public static String getVideoCompression(final Context context) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString(
                "video_compression", context.getResources().getString(R.string.video_compression));
    }
}
