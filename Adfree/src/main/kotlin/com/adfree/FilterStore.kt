package com.adfree

import android.content.Context
import android.content.SharedPreferences

object FilterStore {
    private const val PREFS_NAME  = "net_opt_config"
    private const val KEY_ENABLED = "blocker_enabled"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Master toggle. Off = plugin does nothing at all. */
    var enabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, true) ?: true
        set(v) { prefs?.edit()?.putBoolean(KEY_ENABLED, v)?.apply() }
}
