package eu.siacs.conversations.ui.actions

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.storage.secure.SecureMessageMediaSaveBridge
import eu.siacs.conversations.storage.secure.SecureMessageMediaUiBridge
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.actions.reactions.QuickReaction
import eu.siacs.conversations.ui.actions.reactions.QuickReactionResolver
import eu.siacs.conversations.ui.actions.ui.MessageActionSheet

class MessageActionController(
    private val sheet: MessageActionSheet,
    private val host: Host,
    private val actionResolver: MessageActionResolver = MessageActionResolver(),
    private val reactionResolver: QuickReactionResolver = QuickReactionResolver()
) {

    interface Host {
        fun onMessageAction(message: Message, action: MessageAction)

        fun onQuickReaction(message: Message, reaction: QuickReaction)

        fun onMoreReactions(message: Message)
    }

    fun show(message: Message) = show(message, false, false)

    fun show(message: Message, ownsMediaFile: Boolean) =
        show(message, ownsMediaFile, false)

    fun show(
        message: Message,
        ownsMediaFile: Boolean,
        canModerateMessage: Boolean
    ) {

        val actions =
            actionResolver.resolve(message, ownsMediaFile, canModerateMessage)

        if (actions.isEmpty()) {
            return
        }

        val reactions =
            reactionResolver.resolve()


        sheet
            .show(
                actions,
                reactions,
                object : MessageActionSheet.Listener {


                    override fun onActionSelected(
                        action: MessageAction
                    ) {
                        dispatchAction(message, action)
                    }


                    override fun onQuickReactionSelected(reaction: QuickReaction) {
                        host.onQuickReaction(message, reaction)
                    }

                    override fun onMoreReactionsSelected() {
                        host.onMoreReactions(message)
                    }
                }
            )
    }

    private fun dispatchAction(message: Message, action: MessageAction) {
        val activity = sheet.activityOrNull() as? XmppActivity
        if (activity == null) {
            host.onMessageAction(message, action)
            return
        }
        when (action.type) {
            MessageActionType.OPEN_WITH ->
                SecureMessageMediaUiBridge.openOrFallback(
                    activity,
                    message,
                    Runnable { host.onMessageAction(message, action) },
                )
            MessageActionType.SAVE_TO_DOWNLOADS ->
                SecureMessageMediaSaveBridge.saveWithStandardFeedbackOrFallback(
                    activity,
                    message,
                    Runnable { host.onMessageAction(message, action) },
                )
            else -> host.onMessageAction(message, action)
        }
    }
}
