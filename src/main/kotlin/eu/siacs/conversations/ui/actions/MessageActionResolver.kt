package eu.siacs.conversations.ui.actions

import android.content.Context
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.Transferable
import eu.siacs.conversations.http.HttpDownloadConnection
import eu.siacs.conversations.utils.MessageUtils
import eu.siacs.conversations.xmpp.jingle.JingleFileTransferConnection

class MessageActionResolver(@Suppress("UNUSED_PARAMETER") context: Context? = null) {

    fun resolve(message: Message): List<MessageAction> = resolve(message, false, false)

    fun resolve(message: Message, ownsMediaFile: Boolean): List<MessageAction> =
        resolve(message, ownsMediaFile, false)

    fun resolve(
        message: Message,
        ownsMediaFile: Boolean,
        canModerateMessage: Boolean
    ): List<MessageAction> {
        if (!isLegacyContextMenuEligible(message)) {
            return emptyList()
        }

        val transferable = message.transferable
        val unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message)
        val showError =
            message.status == Message.STATUS_SEND_FAILED
                    && message.errorMessage != null
                    && !Message.ERROR_MESSAGE_CANCELLED.equals(message.errorMessage)

        return buildList {
            if (canReply(message, showError)) {
                add(
                    MessageAction(
                        type = MessageActionType.REPLY,
                        title = R.string.reply,
                        icon = R.drawable.ic_reply_24dp,
                        group = MessageActionGroup.PRIMARY
                    )
                )
            }

            if (canEdit(message, showError)) {
                add(
                    MessageAction(
                        type = MessageActionType.EDIT,
                        title = R.string.edit,
                        icon = R.drawable.ic_edit_24dp,
                        group = MessageActionGroup.PRIMARY
                    )
                )
            }

            if (canShare(message, transferable, unInitiatedButKnownSize)) {
                add(
                    MessageAction(
                        type = MessageActionType.FORWARD,
                        title = R.string.message_action_forward,
                        icon = R.drawable.ic_forward_24dp,
                        group = MessageActionGroup.PRIMARY
                    )
                )
            }

            if (canCopy(message, transferable, unInitiatedButKnownSize)) {
                add(
                    MessageAction(
                        type = MessageActionType.COPY,
                        title = R.string.message_action_copy_text,
                        icon = R.drawable.content_copy_24dp,
                        group = MessageActionGroup.PRIMARY
                    )
                )
            }

            if (canSaveToSavedMessages(message, transferable, unInitiatedButKnownSize)) {
                add(
                    MessageAction(
                        type = MessageActionType.SAVE_TO_SAVED_MESSAGES,
                        title = R.string.save_to_saved_messages,
                        icon = R.drawable.ic_bookmark_24dp,
                        group = MessageActionGroup.ORGANIZATION
                    )
                )
            }

            val cancelable = canCancelTransfer(message, transferable)
            if (canOpenWith(message)) {
                add(
                    MessageAction(
                        type = MessageActionType.OPEN_WITH,
                        title = R.string.open_with,
                        icon = R.drawable.ic_open_with_24dp,
                        group = MessageActionGroup.CONTENT
                    )
                )
            }
            if (canSaveToGallery(message, cancelable)) {
                add(
                    MessageAction(
                        type = MessageActionType.SAVE_TO_GALLERY,
                        title = R.string.save_to_gallery,
                        icon = R.drawable.ic_photo_24dp,
                        group = MessageActionGroup.CONTENT
                    )
                )
            }
            if (canSaveToDownloads(message, cancelable)) {
                add(
                    MessageAction(
                        type = MessageActionType.SAVE_TO_DOWNLOADS,
                        title = R.string.save_to_downloads,
                        icon = R.drawable.ic_download_24dp,
                        group = MessageActionGroup.CONTENT
                    )
                )
            }

            if (message.status == Message.STATUS_SEND_FAILED) {
                add(
                    MessageAction(
                        type = MessageActionType.RETRY,
                        title = R.string.send_again,
                        icon = R.drawable.ic_refresh_24dp,
                        group = MessageActionGroup.PRIMARY
                    )
                )
                if (canRetryAsP2p(message)) {
                    add(
                        MessageAction(
                            type = MessageActionType.RETRY_AS_P2P,
                            title = R.string.retry_with_p2p,
                            icon = R.drawable.ic_link_24dp,
                            group = MessageActionGroup.PRIMARY
                        )
                    )
                }
            }

            if (showError) {
                add(
                    MessageAction(
                        type = MessageActionType.SHOW_ERROR,
                        title = R.string.show_error_message,
                        icon = R.drawable.ic_error_24dp,
                        group = MessageActionGroup.ORGANIZATION
                    )
                )
            }

            if (cancelable) {
                add(
                    MessageAction(
                        type = MessageActionType.CANCEL_TRANSFER,
                        title = R.string.cancel_transmission,
                        icon = R.drawable.ic_cancel_24dp,
                        group = MessageActionGroup.ORGANIZATION
                    )
                )
            }

            add(
                MessageAction(
                    type = MessageActionType.SELECT,
                    title = R.string.select,
                    icon = R.drawable.ic_select_24dp,
                    group = MessageActionGroup.ORGANIZATION
                )
            )

            if (canModerateMessage) {
                add(
                    MessageAction(
                        type = MessageActionType.MODERATE_MESSAGE,
                        title = R.string.moderate_delete,
                        icon = R.drawable.ic_shield_x_24dp,
                        group = MessageActionGroup.DANGER,
                        destructive = true
                    )
                )
            } else if (canDeleteLocally(message)) {
                add(
                    MessageAction(
                        type = MessageActionType.DELETE_LOCALLY,
                        title = R.string.delete,
                        icon = R.drawable.ic_delete_24dp,
                        group = MessageActionGroup.DANGER,
                        destructive = true
                    )
                )
            }
        }
    }

    private fun isLegacyContextMenuEligible(message: Message): Boolean {
        if (message.type == Message.TYPE_STATUS || message.type == Message.TYPE_RTP_SESSION) {
            return false
        }
        if (message.encryption == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
            || message.encryption == Message.ENCRYPTION_AXOLOTL_FAILED
        ) {
            return false
        }
        val transferable = message.transferable
        return message.status != Message.STATUS_RECEIVED
                || transferable == null
                || (transferable.status != Transferable.STATUS_CANCELLED
                && transferable.status != Transferable.STATUS_FAILED)
    }

    private fun canReply(message: Message, showError: Boolean): Boolean {
        val encrypted = message.encryption == Message.ENCRYPTION_DECRYPTION_FAILED
                || message.encryption == Message.ENCRYPTION_PGP
        return !showError
                && !encrypted
                && !MessageUtils.prepareQuote(message).isEmpty()
    }

    private fun canEdit(message: Message, showError: Boolean): Boolean {
        return !showError
                && message.isCorrectableMessage
    }

    private fun canShare(
        message: Message,
        transferable: Transferable?,
        unInitiatedButKnownSize: Boolean
    ): Boolean {
        val receiving = message.status == Message.STATUS_RECEIVED
                && (transferable is JingleFileTransferConnection
                || transferable is HttpDownloadConnection)
        return (message.isFileOrImage() && !message.isDeleted && !receiving)
                || (message.type == Message.TYPE_TEXT
                && !message.treatAsDownloadable()
                && !unInitiatedButKnownSize
                && transferable == null)
    }

    private fun canCopy(
        message: Message,
        transferable: Transferable?,
        unInitiatedButKnownSize: Boolean
    ): Boolean {
        return isLegacyPlainText(message, transferable, unInitiatedButKnownSize)
    }

    private fun canSaveToSavedMessages(
        message: Message,
        transferable: Transferable?,
        unInitiatedButKnownSize: Boolean
    ): Boolean {
        val sourceConversation = message.conversation as? Conversation ?: return false
        return canShare(message, transferable, unInitiatedButKnownSize)
                && !sourceConversation.withSelf()
    }

    private fun canDeleteLocally(message: Message): Boolean {
        return message.conversation is Conversation
    }

    private fun canOpenWith(message: Message): Boolean {
        val mime = if (message.isFileOrImage()) message.getMimeType() else null
        return shouldOfferOpenWith(mime)
    }

    private fun canSaveToGallery(message: Message, cancelable: Boolean): Boolean {
        if (!message.isFileOrImage()) {
            return false
        }
        val params = message.getFileParams()
        return shouldOfferSaveToGallery(
            isFileOrImage = true,
            isDeleted = message.isDeleted,
            cancelable = cancelable,
            messageType = message.type,
            mime = message.getMimeType(),
            width = params.width,
            height = params.height
        )
    }

    private fun canSaveToDownloads(message: Message, cancelable: Boolean): Boolean {
        if (!message.isFileOrImage()) {
            return false
        }
        val params = message.getFileParams()
        return shouldOfferSaveToDownloads(
            isFileOrImage = true,
            isDeleted = message.isDeleted,
            cancelable = cancelable,
            messageType = message.type,
            mime = message.getMimeType(),
            width = params.width,
            height = params.height
        )
    }

    private fun canRetryAsP2p(message: Message): Boolean {
        val conversation = message.conversation as? Conversation ?: return false
        return message.isFileOrImage()
                && !message.hasFileOnRemoteHost()
                && conversation.mode == Conversation.MODE_SINGLE
                && !conversation.getContact().getPresences().isEmpty()
    }

    private fun canCancelTransfer(message: Message, transferable: Transferable?): Boolean {
        val waitingOfferedSending = message.status == Message.STATUS_WAITING
                || message.status == Message.STATUS_UNSEND
                || message.status == Message.STATUS_OFFERED
        return (transferable != null && !message.isDeleted)
                || (waitingOfferedSending && message.needsUploading())
    }

    private fun isLegacyPlainText(
        message: Message,
        transferable: Transferable?,
        unInitiatedButKnownSize: Boolean
    ): Boolean {
        val encrypted = message.encryption == Message.ENCRYPTION_DECRYPTION_FAILED
                || message.encryption == Message.ENCRYPTION_PGP
        return !message.isFileOrImage()
                && !encrypted
                && !message.isGeoUri
                && !message.treatAsDownloadable()
                && !unInitiatedButKnownSize
                && transferable == null
    }
}

internal fun shouldOfferOpenWith(mime: String?): Boolean {
    return mime != null && mime.startsWith("audio/")
}

internal fun shouldOfferSaveToGallery(
    isFileOrImage: Boolean,
    isDeleted: Boolean,
    cancelable: Boolean,
    messageType: Int,
    mime: String?,
    width: Int,
    height: Int
): Boolean {
    if (!isFileOrImage || isDeleted || cancelable) {
        return false
    }
    if (!mime.isNullOrBlank()) {
        return mime.startsWith("image/") || mime.startsWith("video/")
    }
    return messageType == Message.TYPE_IMAGE || (width > 0 && height > 0)
}

internal fun shouldOfferSaveToDownloads(
    isFileOrImage: Boolean,
    isDeleted: Boolean,
    cancelable: Boolean,
    messageType: Int,
    mime: String?,
    width: Int,
    height: Int
): Boolean {
    if (!isFileOrImage || isDeleted || cancelable) {
        return false
    }
    if (!mime.isNullOrBlank()) {
        return !(mime.startsWith("image/") || mime.startsWith("video/"))
    }
    return messageType != Message.TYPE_IMAGE && !(width > 0 && height > 0)
}
