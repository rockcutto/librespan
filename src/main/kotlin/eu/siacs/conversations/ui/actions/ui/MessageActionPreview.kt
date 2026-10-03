package eu.siacs.conversations.ui.actions.ui

import android.app.Activity
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.actions.MessageAction
import eu.siacs.conversations.ui.actions.MessageActionGroup
import eu.siacs.conversations.ui.actions.MessageActionType
import eu.siacs.conversations.ui.actions.reactions.QuickReaction
import eu.siacs.conversations.ui.actions.reactions.QuickReactionResolver

object MessageActionPreview {

    @JvmStatic
    fun show(activity: Activity) {

        val actions = listOf(

            MessageAction(
                type = MessageActionType.REPLY,
                title = R.string.reply,
                icon = R.drawable.ic_reply_24dp,
                group = MessageActionGroup.PRIMARY
            ),

            MessageAction(
                type = MessageActionType.SAVE,
                title = R.string.save,
                icon = R.drawable.ic_save_24dp,
                group = MessageActionGroup.CONTENT
            ),

            MessageAction(
                type = MessageActionType.DELETE,
                title = R.string.delete,
                icon = R.drawable.ic_delete_24dp,
                group = MessageActionGroup.DANGER,
                destructive = true
            )
        )


        MaterialMessageActionSheet(activity)
            .show(
                actions,
                QuickReactionResolver().resolve(),
                object : MessageActionSheet.Listener {

                    override fun onActionSelected(
                        action: MessageAction
                    ) {
                        // preview only
                    }


                    override fun onQuickReactionSelected(
                        reaction: QuickReaction
                    ) {
                        // preview only
                    }


                    override fun onMoreReactionsSelected() {
                        // preview only
                    }
                }
            )
    }
}