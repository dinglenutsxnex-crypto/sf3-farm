package com.sf3farm

import android.content.Context

/** Absolute server-side win totals per guid (survives restarts). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("farm", Context.MODE_PRIVATE)

    @Synchronized
    fun setServerWins(guid: String, total: Long) {
        if (total > getWins(guid)) {
            sp.edit().putLong("w_$guid", total).apply()
        }
    }

    @Synchronized
    fun getWins(guid: String): Long = sp.getLong("w_$guid", 0L)
}
