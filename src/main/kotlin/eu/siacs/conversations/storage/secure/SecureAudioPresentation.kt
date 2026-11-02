package eu.siacs.conversations.storage.secure

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import eu.siacs.conversations.Config
import eu.siacs.conversations.Conversations
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.security.cryptolock.SecureContentCryptoSessionRuntimeV1
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.utils.AudioAttachmentPresentation
import eu.siacs.conversations.utils.AudioAttachmentPresentation.Metadata
import eu.siacs.conversations.utils.AudioAttachmentPresentation.Tracks
import java.util.concurrent.Executors

/** SCS-only, memory-only enrichment. All lease and decoder work runs off the UI thread. */
object SecureAudioPresentation {
    private val cache = AudioAttachmentPresentation.Cache(64)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "secure-audio-metadata").apply { isDaemon = true }
    }

    private fun key(account: String, message: String) = "$account:$message"
    private fun epoch(context: Context) = SecureContentCryptoSessionRuntimeV1.snapshot(context).epoch
    private fun available(context: Context) =
        Config.SECURE_CONTENT_MEDIA_ROLLOUT &&
            SecureContentCryptoSessionRuntimeV1.isBackgroundCryptoAvailable(context)

    @JvmStatic
    fun clear() = cache.clear()

    @JvmStatic
    fun peek(context: Context, account: String, message: String): Metadata? =
        cache.peek(key(account, message), epoch(context), available(context))

    @JvmStatic
    fun peek(context: Context, message: Message): Metadata? =
        peek(context, message.conversation.account.uuid, message.uuid)

    @JvmStatic
    fun isAudio(context: Context, message: Message): Boolean =
        message.isAudioAttachment(peek(context, message)?.tracks ?: Tracks.UNKNOWN)

    /** Called from the browser's existing worker, including after process restart. */
    @JvmStatic
    fun load(
        context: Context,
        store: SecureContentStore,
        account: String,
        message: String,
        mime: String?,
    ): Metadata? {
        val cached = peek(context, account, message)
        if (cached != null) return cached
        if (!AudioAttachmentPresentation.needsInspection(mime)) return null
        val scheduledEpoch = epoch(context)
        val ticket = cache.begin(key(account, message), scheduledEpoch, available(context))
        if (ticket == null) {
            if (available(context)) cache.awaitPending(key(account, message), scheduledEpoch)
            return peek(context, account, message)
        }
        return read(context, store, account, message, mime, ticket)
    }

    /** Reserve before queueing, so repeated row binds never queue duplicate metadata reads. */
    @JvmStatic
    fun hydrate(activity: XmppActivity, message: Message, onLoaded: Runnable) {
        if (!AudioAttachmentPresentation.needsInspection(message.mimeType)) return
        val context = activity.applicationContext
        val account = message.conversation.account.uuid
        val messageUuid = message.uuid
        val mime = message.mimeType
        val ticket = cache.begin(key(account, messageUuid), epoch(context), available(context)) ?: return
        executor.execute {
            val result = try {
                val store = (activity.application as Conversations).secureContentStoreProvider.get()
                read(context, store, account, messageUuid, mime, ticket)
            } catch (_: Exception) {
                cache.cancel(ticket)
                null
            }
            if (result != null) {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed &&
                        ticket.epoch == epoch(context) && available(context) &&
                        peek(context, account, messageUuid) === result &&
                        message.isFileOrImage && !message.isDeleted && !message.isRetracted && !message.isModerated
                    ) {
                        // Adapter rebinds its current data; no captured row is mutated here.
                        onLoaded.run()
                    }
                }
            }
        }
    }

    private fun read(
        context: Context,
        store: SecureContentStore,
        account: String,
        message: String,
        mime: String?,
        ticket: AudioAttachmentPresentation.Ticket,
    ): Metadata? {
        return cache.load(ticket, { epoch(context) }, { available(context) }) {
            val lease = AndroidSecureMessageMediaReadCache(context, store).acquire(account, message)
            if (lease == null) null else object : AudioAttachmentPresentation.Source {
                override fun read(): Metadata = inspect(context, lease.uri, mime)
                override fun close() = lease.close()
            }
        }
    }

    internal fun inspect(context: Context, uri: Uri, mime: String?): Metadata {
        val oggTracks = if (mime == "application/ogg" || mime == "audio/ogg" || mime == "audio/opus") {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    AudioAttachmentPresentation.inspectOgg(input)
                } ?: Tracks.UNKNOWN
            } catch (_: Exception) { Tracks.UNKNOWN }
        } else null
        // Full track enumeration, rather than duration or a failed video thumbnail, determines
        // ambiguous Ogg identity. A video track always wins, including mixed A/V containers.
        var tracks = Tracks.UNKNOWN
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                var audio = false
                var video = false
                for (index in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    audio = audio || mime?.startsWith("audio/") == true
                    video = video || mime?.startsWith("video/") == true
                }
                tracks = when {
                    video -> Tracks.VIDEO
                    audio -> Tracks.AUDIO
                    else -> Tracks.UNKNOWN
                }
            }
        } catch (_: Exception) {
            // UNKNOWN stays a generic file for application/ogg, even if duration is readable.
        } finally {
            try { extractor.release() } catch (_: Exception) { /* best effort */ }
        }
        if (oggTracks != null) {
            tracks = when {
                oggTracks == Tracks.VIDEO -> Tracks.VIDEO
                oggTracks == Tracks.AUDIO && tracks == Tracks.AUDIO -> Tracks.AUDIO
                else -> Tracks.UNKNOWN
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                return Metadata(
                    tracks,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0,
                )
            }
        } catch (_: Exception) {
            // Metadata failure must not remove Play from an otherwise identified audio file.
        } finally {
            try { retriever.release() } catch (_: Exception) { /* best effort */ }
        }
        return Metadata(tracks, null, null, 0)
    }
}
