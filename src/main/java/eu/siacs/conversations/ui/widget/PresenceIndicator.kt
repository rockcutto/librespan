package eu.siacs.conversations.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Presence
import eu.siacs.conversations.ui.XmppActivity
import eu.siacs.conversations.ui.util.StyledAttributes
import eu.siacs.conversations.utils.UIHelper

class PresenceIndicator : View {
    private var status: Presence.Status? = null

    private var enabled = false

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

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    fun setStatus(contact: Contact?) {
        val status = contact?.shownStatus?.takeIf {
            contact.account?.isOnlineAndConnected == true
        }
        if (status != this.status) {
            this.status = status
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        enabled = (context as? XmppActivity)
            ?.xmppConnectionService?.preferences
            ?.getBoolean("show_contact_status", context.resources.getBoolean(R.bool.show_contact_status)) ?: false
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabled) {
            return
        }

        super.onDraw(canvas)
        drawBadge(canvas, context, status, width / 2f, height / 2f)
    }

    companion object {
        private const val BADGE_DIAMETER_DP = 12f
        private const val BADGE_BORDER_DP = 2f

        @JvmStatic
        fun drawBadge(
            canvas: Canvas,
            context: Context,
            status: Presence.Status?,
            centerX: Float,
            centerY: Float
        ) {
            val statusColor = UIHelper.getColorForStatus(context, status) ?: return
            val density = context.resources.displayMetrics.density
            val radius = BADGE_DIAMETER_DP * 0.5f * density
            val border = BADGE_BORDER_DP * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = StyledAttributes.getColor(
                    context,
                    com.google.android.material.R.attr.colorSurface
                )
            }

            canvas.drawCircle(centerX, centerY, radius, paint)
            paint.color = statusColor
            canvas.drawCircle(centerX, centerY, (radius - border).coerceAtLeast(0f), paint)
        }
    }
}
