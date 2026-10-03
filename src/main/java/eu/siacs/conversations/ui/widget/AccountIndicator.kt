package eu.siacs.conversations.ui.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.XmppActivity

class AccountIndicator : View {
    private val indicatorDrawable = GradientDrawable()

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(
        context: Context?,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    fun setCircleColor(color: Int) {
        indicatorDrawable.shape = GradientDrawable.OVAL
        indicatorDrawable.setColor(color)
        background = indicatorDrawable
        updateVisibility()
    }

    fun setPillColor(color: Int) {
        indicatorDrawable.shape = GradientDrawable.RECTANGLE
        indicatorDrawable.cornerRadius = resources.displayMetrics.density * 99f
        indicatorDrawable.setColor(color)
        background = indicatorDrawable
        updateVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateVisibility()
    }

    private fun updateVisibility() {
        val enabled = (context as? XmppActivity)
            ?.xmppConnectionService?.preferences
            ?.getBoolean(
                "show_account_indicator",
                context.resources.getBoolean(R.bool.show_account_indicator)
            ) ?: false

        visibility = if (enabled) {
            VISIBLE
        } else {
            INVISIBLE
        }
    }
}