package com.sf3farm

import android.content.Context

/** Cumulative wins per guid (survives restarts; engine reports per-run counts). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("farm", Context.MODE_PRIVATE)
    private val seen = mutableMapOf<String, Long>()

    @Synchronized
    fun addWins(guid: String, runWins: Long) {
        val prev = seen[guid]
        if (prev == null || runWins > prev) {
            val delta = runWins - (prev ?: 0L)
            if (delta > 0) {
                sp.edit().putLong("w_$guid", getWins(guid) + delta).apply()
            }
            seen[guid] = runWins
        }
    }

    @Synchronized
    fun getWins(guid: String): Long = sp.getLong("w_$guid", 0L)

    @Synchronized
    fun resetRun(guid: String) {
        seen.remove(guid)
    }

    @Synchronized
    fun resetAllRun() = seen.clear()
}
