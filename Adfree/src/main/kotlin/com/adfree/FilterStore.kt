package com.adfree

import android.content.Context
import android.content.SharedPreferences

/**
 * Plugin state only. Nothing here touches hosts, providers, or ad networks —
 * the plugin's job is to neutralize stale donation/popup prefs, and every
 * other concern was removed to keep it off the provider and video paths.
 */
object FilterStore {
    private const val PREFS_NAME      = "net_opt_config"
    private const val KEY_ENABLED     = "blocker_enabled"
    private const val KEY_LAST_SWEEP  = "last_sweep_ms"
    private const val KEY_SWEEP_COUNT = "sweep_count"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, true) ?: true
        set(v) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }

    fun recordSweep(filesTouched: Int) {
        val p = prefs ?: return
        p.edit()
            .putLong(KEY_LAST_SWEEP, System.currentTimeMillis())
            .putInt(KEY_SWEEP_COUNT, p.getInt(KEY_SWEEP_COUNT, 0) + filesTouched)
            .apply()
    }

    fun lastSweepMs(): Long = prefs?.getLong(KEY_LAST_SWEEP, 0L) ?: 0L
    fun totalSweeps(): Int  = prefs?.getInt(KEY_SWEEP_COUNT, 0) ?: 0
}
