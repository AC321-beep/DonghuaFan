package com.animekhor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimekhorPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize preferences and lock the User-Agent early
        AnimekhorLogger.init(context)
        CFState.initializeGlobalUA(context)
        AnimekhorLogger.i("Plugin", "Loaded AnimeKhor (verbose=${AnimekhorLogger.isVerbose})")

        // Required by Cloudstream: Exposes the gear icon in the extensions menu
        openSettings = { ctx ->
            val activity = ctx as? Activity ?: CommonActivity.activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                AlertDialog.Builder(activity)
                    .setTitle("AnimeKhor Settings")
                    .setMultiChoiceItems(
                        arrayOf("Enable Verbose Logging"),
                        booleanArrayOf(AnimekhorLogger.isVerbose)
                    ) { _, _, isChecked ->
                        AnimekhorLogger.setVerbose(activity, isChecked)
                        AnimekhorLogger.i("Settings", "Verbose logging set to $isChecked")
                    }
                    .setPositiveButton("Done", null)
                    .show()
            }
        }

        registerMainAPI(AnimekhorProvider())
    }
}
