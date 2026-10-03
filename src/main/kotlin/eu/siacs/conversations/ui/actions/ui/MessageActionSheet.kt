package eu.siacs.conversations.ui.actions.ui

import android.app.Activity
import eu.siacs.conversations.ui.actions.MessageAction
import eu.siacs.conversations.ui.actions.reactions.QuickReaction
import eu.siacs.conversations.ui.actions.reactions.QuickReactionItem

interface MessageActionSheet {

    /**
     * Activity host when the concrete sheet owns one. Controllers may use this for narrowly scoped
     * Android integration while non-Android/test sheets keep the default null implementation.
     */
    fun activityOrNull(): Activity? = null

    fun show(
        actions: List<MessageAction>,
        reactions: List<QuickReactionItem>,
        listener: Listener
    )

    interface Listener {

        fun onActionSelected(
            action: MessageAction
        )

        fun onQuickReactionSelected(
            reaction: QuickReaction
        )

        fun onMoreReactionsSelected()
    }
}
