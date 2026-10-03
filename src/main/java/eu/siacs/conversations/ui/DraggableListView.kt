package eu.siacs.conversations.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ListAdapter
import android.widget.ListView
import androidx.customview.widget.ViewDragHelper

class DraggableListView : ListView {
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var dragHelper: ViewDragHelper? = null

    fun clearDragHelper() {
        dragHelper = null;
    }

    override fun setAdapter(adapter: ListAdapter?) {
        super.setAdapter(adapter)
        val dragHelperCallback = if (adapter is DraggableAdapter) {
            adapter.getDragCallback()
        } else {
            null
        }

        dragHelper = if (dragHelperCallback != null) {
            ViewDragHelper.create(this, 0.8f, dragHelperCallback)
        } else {
            null
        }

        if (adapter is DraggableAdapter) {
            adapter.setViewDragHelper(dragHelper)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dragHelper?.shouldInterceptTouchEvent(ev) == true) {
            return true
        }

        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val res = dragHelper?.viewDragState != ViewDragHelper.STATE_DRAGGING && super.onTouchEvent(ev)
        return if (res) {
            true
        } else {
            dragHelper?.processTouchEvent(ev)
            dragHelper?.viewDragState == ViewDragHelper.STATE_DRAGGING
        }
    }

    interface DraggableAdapter {
        fun getDragCallback(): ViewDragHelper.Callback?
        fun setViewDragHelper(helper: ViewDragHelper?)
    }
}