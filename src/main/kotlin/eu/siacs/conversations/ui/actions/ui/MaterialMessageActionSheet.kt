package eu.siacs.conversations.ui.actions.ui

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.actions.MessageAction
import eu.siacs.conversations.ui.actions.reactions.QuickReactionItem
import eu.siacs.conversations.ui.actions.reactions.QuickReaction
import eu.siacs.conversations.ui.actions.ui.adapter.MessageActionAdapter
import eu.siacs.conversations.ui.actions.ui.adapter.QuickReactionAdapter

class MaterialMessageActionSheet(
    private val activity: Activity
) : MessageActionSheet {

    override fun activityOrNull(): Activity = activity

    override fun show(
        actions: List<MessageAction>,
        reactions: List<QuickReactionItem>,
        listener: MessageActionSheet.Listener
    ) {

        val view = LayoutInflater.from(activity)
            .inflate(
                R.layout.dialog_message_actions,
                null
            )

        val reactionsList =
            view.findViewById<RecyclerView>(
                R.id.quick_reactions_list
            )

        val actionsList =
            view.findViewById<RecyclerView>(
                R.id.message_actions_list
            )


        reactionsList.layoutManager =
            LinearLayoutManager(
                activity,
                LinearLayoutManager.HORIZONTAL,
                false
            )

        actionsList.layoutManager =
            LinearLayoutManager(activity)


        val dialog: AlertDialog =
            MaterialAlertDialogBuilder(activity)
                .setView(view)
                .create()

        var selectionHandled = false
        fun dispatchSelection(callback: () -> Unit) {
            if (selectionHandled) return
            selectionHandled = true
            dialog.dismiss()
            if (!activity.isFinishing
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                        || !activity.isDestroyed)
            ) {
                callback()
            }
        }

        val onActionSelected: (MessageAction) -> Unit = { action ->
            dispatchSelection { listener.onActionSelected(action) }
        }

        reactionsList.adapter =
            QuickReactionAdapter(
                reactions,
                { reaction ->
                    dispatchSelection { listener.onQuickReactionSelected(reaction) }
                },
                {
                    dispatchSelection { listener.onMoreReactionsSelected() }
                }
            )


        actionsList.adapter = MessageActionAdapter(actions, onActionSelected)

        if (reactions.isEmpty()) {
            reactionsList.visibility = View.GONE
        }

        dialog.show()
    }
}
