package eu.siacs.conversations.ui.actions.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.actions.reactions.QuickReaction
import eu.siacs.conversations.ui.actions.reactions.QuickReactionItem

class QuickReactionAdapter(
    private val reactions: List<QuickReactionItem>,
    private val listener: (QuickReaction) -> Unit,
    private val moreListener: () -> Unit
) : RecyclerView.Adapter<QuickReactionAdapter.ViewHolder>() {


    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(
                R.layout.item_quick_reaction,
                parent,
                false
            )

        return ViewHolder(view)
    }


    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        when (val item = reactions[position]) {

            is QuickReactionItem.Reaction -> {
                holder.text.text = item.reaction.emoji

                holder.itemView.setOnClickListener {
                    listener(item.reaction)
                }
            }

            QuickReactionItem.More -> {
                holder.text.text = "+"

                holder.itemView.setOnClickListener {
                    moreListener()
                }
            }
        }
    }


    override fun getItemCount(): Int {
        return reactions.size
    }


    class ViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val text: TextView =
            view.findViewById(R.id.reaction_text)
    }
}