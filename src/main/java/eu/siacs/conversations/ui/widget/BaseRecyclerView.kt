package eu.siacs.conversations.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ContextMenu.ContextMenuInfo
import android.view.View
import android.widget.AdapterView.AdapterContextMenuInfo
import androidx.recyclerview.widget.RecyclerView


class BaseRecyclerView : RecyclerView {
    private var adapterContextMenuInfo: AdapterContextMenuInfo? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    override fun getContextMenuInfo(): ContextMenuInfo? {
        return adapterContextMenuInfo
    }

    override fun showContextMenuForChild(originalView: View): Boolean {
        adapterContextMenuInfo = AdapterContextMenuInfo(
            originalView,
            getChildAdapterPosition(originalView),
            getChildItemId(originalView)
        )
        return super.showContextMenuForChild(originalView)
    }
}