package eu.siacs.conversations.storage.secure

import android.content.ActivityNotFoundException
import android.util.Log
import android.widget.Toast
import eu.siacs.conversations.Config
import eu.siacs.conversations.Conversations
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.XmppActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * UI-facing secure media bridge for generic open/view operations.
 *
 * Secure relation resolution and plaintext export always happen off the UI thread. Legacy fallback
 * is invoked only when no secure relation exists. Any secure relation error fails closed.
 */
object SecureMessageMediaUiBridge {
    private val ioExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "secure-media-view").apply { isDaemon = true }
        }

    /**
     * Rehydrates UI-only attachment facts from the account-scoped Store after a process restart.
     * The metadata is cached only on the in-memory Message; paths and plaintext files are never
     * persisted or consulted.
     */
    @JvmStatic
    fun hydratePresentationMetadata(
        activity: XmppActivity,
        message: Message,
        onLoaded: Runnable,
    ) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT || message.isSecureMediaPresentationMetadataResolved()) {
            return
        }
        val accountUuid = message.conversation.account.uuid
        val messageUuid = message.uuid
        ioExecutor.execute {
            val metadata =
                try {
                    val application = activity.application as Conversations
                    SecureMessageMediaCoordinator(application.secureContentStoreProvider.get())
                        .resolveMetadata(accountUuid, messageUuid)
                } catch (error: Exception) {
                    Log.w(Config.LOGTAG, "unable to resolve secure media presentation metadata", error)
                    null
                }
            if (metadata == null) {
                // No COMMITTED secure relation yet. Keep the producer/parser presentation facts
                // intact while the attachment is offered or downloading. A missing/incomplete
                // Store relation is not resolved metadata and must never demote an image to a
                // generic file before secure commit.
                return@execute
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                message.setSecureMediaPresentationMetadata(
                    metadata.mimeType,
                    metadata.fileName,
                    metadata.sizeBytes,
                )
                onLoaded.run()
            }
        }
    }

    @JvmStatic
    fun openOrFallback(
        activity: XmppActivity,
        accountUuid: String,
        messageUuid: String,
        mimeType: String?,
        legacyFallback: Runnable,
    ) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            legacyFallback.run()
            return
        }
        ioExecutor.execute {
            try {
                val application = activity.application as Conversations
                val store = application.secureContentStoreProvider.get()
                val externalization = SecureMessageMediaExternalization(activity, store)
                val viewIntent =
                    externalization.prepareViewIntent(accountUuid, messageUuid, mimeType)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    if (viewIntent == null) {
                        legacyFallback.run()
                    } else {
                        try {
                            activity.startActivity(viewIntent)
                        } catch (error: ActivityNotFoundException) {
                            Toast.makeText(
                                activity,
                                R.string.no_application_found_to_open_file,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (error: SecurityException) {
                            Log.w(Config.LOGTAG, "unable to grant secure media view export", error)
                            Toast.makeText(
                                activity,
                                R.string.no_application_found_to_open_file,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(Config.LOGTAG, "unable to prepare secure media browser view", error)
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(
                            activity,
                            R.string.no_application_found_to_open_file,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    @JvmStatic
    fun openOrFallback(
        activity: XmppActivity,
        message: Message,
        legacyFallback: Runnable,
    ) {
        if (!Config.SECURE_CONTENT_MEDIA_ROLLOUT) {
            legacyFallback.run()
            return
        }
        val accountUuid = message.conversation.account.uuid
        val messageUuid = message.uuid
        ioExecutor.execute {
            try {
                val application = activity.application as Conversations
                val store = application.secureContentStoreProvider.get()
                val metadata = SecureMessageMediaCoordinator(store)
                    .resolveMetadata(accountUuid, messageUuid)
                if (metadata != null) {
                    message.setSecureMediaPresentationMetadata(
                        metadata.mimeType,
                        metadata.fileName,
                        metadata.sizeBytes,
                    )
                }
                val externalization =
                    SecureMessageMediaExternalization(
                        activity,
                        store,
                    )
                val viewIntent =
                    externalization.prepareViewIntent(
                        accountUuid,
                        messageUuid,
                        metadata?.mimeType ?: message.mimeType,
                    )
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    if (viewIntent == null) {
                        legacyFallback.run()
                    } else {
                        try {
                            activity.startActivity(viewIntent)
                        } catch (error: ActivityNotFoundException) {
                            Toast.makeText(
                                    activity,
                                    R.string.no_application_found_to_open_file,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        } catch (error: SecurityException) {
                            Log.w(Config.LOGTAG, "unable to grant secure media view export", error)
                            Toast.makeText(
                                    activity,
                                    R.string.no_application_found_to_open_file,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(Config.LOGTAG, "unable to prepare secure media view", error)
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(
                                activity,
                                R.string.no_application_found_to_open_file,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
            }
        }
    }
}
