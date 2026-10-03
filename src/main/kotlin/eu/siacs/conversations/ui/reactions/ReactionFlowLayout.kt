package eu.siacs.conversations.ui.reactions

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class ReactionFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {
    private val horizontalGap = dp(3)
    private val verticalGap = dp(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        var lineWidth = 0
        var lineHeight = 0
        var desiredWidth = 0
        var desiredHeight = paddingTop + paddingBottom
        var hasVisibleChild = false
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val gap = if (lineWidth == 0) 0 else horizontalGap
            if (lineWidth > 0 && availableWidth != Int.MAX_VALUE && lineWidth + gap + childWidth > availableWidth) {
                desiredWidth = maxOf(desiredWidth, lineWidth)
                desiredHeight += lineHeight + verticalGap
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth += gap + childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
            hasVisibleChild = true
        }
        if (hasVisibleChild) {
            desiredWidth = maxOf(desiredWidth, lineWidth)
            desiredHeight += lineHeight
        }
        setMeasuredDimension(
            resolveSize(desiredWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = if (isRtl) right - left - paddingRight else paddingLeft
        var y = paddingTop
        var lineWidth = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val gap = if (lineWidth == 0) 0 else horizontalGap
            if (lineWidth > 0 && lineWidth + gap + childWidth > contentWidth) {
                y += lineHeight + verticalGap
                x = if (isRtl) right - left - paddingRight else paddingLeft
                lineWidth = 0
                lineHeight = 0
            }
            if (isRtl) {
                child.layout(x - childWidth, y, x, y + childHeight)
                x -= childWidth + horizontalGap
            } else {
                child.layout(x, y, x + childWidth, y + childHeight)
                x += childWidth + horizontalGap
            }
            lineWidth += if (lineWidth == 0) childWidth else horizontalGap + childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
