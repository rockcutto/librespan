package eu.siacs.conversations.ui.actions.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.actions.MessageAction

class MessageActionAdapter(
    private val actions: List<MessageAction>,
    private val listener: (MessageAction) -> Unit
) : RecyclerView.Adapter<MessageActionAdapter.ViewHolder>() {


    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val view = LayoutInflater.from(parent.context)
            .inflate(
                R.layout.item_message_action,
                parent,
                false
            )

        return ViewHolder(view)
    }


    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        val action = actions[position]

        holder.icon.setImageResource(action.icon)
        holder.title.setText(action.title)

        holder.itemView.setOnClickListener {
            listener(action)
        }
    }


    override fun getItemCount(): Int {
        return actions.size
    }


    class ViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val icon: ImageView =
            view.findViewById(R.id.action_icon)

        val title: TextView =
            view.findViewById(R.id.action_title)
    }
}