package com.sf3farm

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AccountAdapter(
    private var items: List<AccountUi>,
    private val onToggle: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<AccountAdapter.H>() {

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val cb: CheckBox = v.findViewById(R.id.cb_sel)
        val tv: TextView = v.findViewById(R.id.tv_info)
    }

    fun submit(next: List<AccountUi>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): H {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_account, p, false)
        return H(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: H, pos: Int) {
        val a = items[pos]
        h.cb.setOnCheckedChangeListener(null)
        h.cb.isChecked = a.acct.selected
        h.cb.text = a.acct.name.ifEmpty { a.acct.guid.take(8) }
        val rank = a.rank?.let { "#$it" } ?: "-"
        h.tv.text = "wins=${a.wins} fails=${a.fails} rating=${a.rating ?: "-"} rank=$rank ${a.status}"
        h.cb.setOnCheckedChangeListener { _, sel -> onToggle(a.acct.guid, sel) }
    }
}
