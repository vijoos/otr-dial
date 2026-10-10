package com.example.otrdial

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One scrolling surface. Expensive row artwork is created only as a row enters the viewport. */
class ScreenList(context: Context) : RecyclerView(context) {
    private val rows = mutableListOf<() -> View>()
    private val keys = mutableListOf<String?>()
    private val rowAdapter = object : Adapter<Holder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(FrameLayout(context).apply { layoutParams = LayoutParams(-1,-2) })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.box.removeAllViews()
            val view = rows[position](); (view.parent as? ViewGroup)?.removeView(view)
            holder.box.addView(view, FrameLayout.LayoutParams(-1,view.layoutParams?.height ?: -2).apply { val old=view.layoutParams as? ViewGroup.MarginLayoutParams; if(old!=null) setMargins(old.leftMargin,old.topMargin,old.rightMargin,old.bottomMargin) })
        }
        override fun onViewRecycled(holder: Holder) { holder.box.removeAllViews() }
    }
    class Holder(val box: FrameLayout): ViewHolder(box)
    init { layoutManager = LinearLayoutManager(context); adapter = rowAdapter; itemAnimator = null; setItemViewCacheSize(2) }
    fun clearRows() { val count=rows.size; rows.clear(); keys.clear(); rowAdapter.notifyItemRangeRemoved(0,count) }
    fun add(view: View) { addLazy { view } }
    fun add(view: View, params: ViewGroup.LayoutParams) { view.layoutParams=params; add(view) }
    fun addLazy(key: String? = null, factory: () -> View) { rows.add(factory); keys.add(key); rowAdapter.notifyItemInserted(rows.lastIndex) }
    fun refresh(key: String) { keys.forEachIndexed { index, value -> if(value==key) rowAdapter.notifyItemChanged(index) } }
    fun state() = layoutManager?.onSaveInstanceState()
    fun restore(state: android.os.Parcelable?) { if(state != null) layoutManager?.onRestoreInstanceState(state) }
}
