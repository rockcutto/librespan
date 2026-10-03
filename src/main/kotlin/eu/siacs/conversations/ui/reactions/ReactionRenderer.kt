package eu.siacs.conversations.ui.reactions

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Reaction
import java.util.Locale
import java.util.function.Consumer
import java.util.function.Function

object ReactionRenderer {
    private const val TEXT_SIZE_SP = 13f

    @JvmStatic
    fun bind(
        container: ReactionFlowLayout,
        aggregated: Reaction.Aggregated,
        onModifiedReactions: Consumer<Collection<String>>,
        onDetailsClicked: Function<String, Boolean>,
    ) {
        val reactions = aggregated.reactions
        if (reactions == null || reactions.isEmpty()) {
            hideChildrenFrom(container, 0)
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        for (index in reactions.indices) {
            val reaction = reactions[index]
            val emoji = reaction.key
            val count = reaction.value
            val view =
                if (index < container.childCount) container.getChildAt(index) as AppCompatTextView
                else createReactionView(container).also { container.addView(it) }
            val label = if (count == 1) emoji else String.format(Locale.ENGLISH, "%s %d", emoji, count)
            view.visibility = View.VISIBLE
            view.text = label
            view.contentDescription = label
            view.setOnClickListener {
                onModifiedReactions.accept(Reaction.toggle(aggregated.ourReactions, emoji))
            }
            view.setOnLongClickListener { onDetailsClicked.apply(emoji) == true }
        }
        hideChildrenFrom(container, reactions.size)
    }

    @JvmStatic
    fun setMediaOverlayStyle(
        container: ReactionFlowLayout,
        overlay: Boolean,
    ) {
        for (index in 0 until container.childCount) {
            val view = container.getChildAt(index) as? AppCompatTextView ?: continue
            if (overlay) {
                view.setBackgroundResource(R.drawable.background_message_status_overlay)
                view.setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 199))
                view.setPadding(view.dp(5), view.dp(2), view.dp(5), view.dp(2))
            } else {
                view.background = null
                TextViewCompat.setTextAppearance(
                    view,
                    androidx.appcompat.R.style.TextAppearance_AppCompat_Body2,
                )
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                view.setPadding(view.dp(3), view.dp(1), view.dp(3), view.dp(1))
            }
        }
    }

    private fun createReactionView(container: ReactionFlowLayout) =
        AppCompatTextView(container.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            TextViewCompat.setTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Body2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(3), dp(1), dp(3), dp(1))
            isClickable = true
            isLongClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

    private fun hideChildrenFrom(container: ReactionFlowLayout, start: Int) {
        for (index in start until container.childCount) container.getChildAt(index).visibility = View.GONE
    }

    private fun View.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
