package com.animekhor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Switch
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimekhorPlugin : Plugin() {
    override fun load(context: Context) {
        AnimekhorLogger.init(context)
        CFState.initializeGlobalUA(context)

        openSettings = { ctx ->
            val activity = ctx as? Activity ?: CommonActivity.activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val switchView = Switch(activity).apply {
                    text = "Enable Verbose Logging"
                    isChecked = AnimekhorLogger.isVerbose
                    setPadding(50, 50, 50, 50)
                    setOnCheckedChangeListener { _, isChecked ->
                        AnimekhorLogger.setVerbose(activity, isChecked)
                    }
                }
                AlertDialog.Builder(activity)
                    .setTitle("AnimeKhor Settings")
                    .setView(switchView)
                    .setPositiveButton("Done", null)
                    .show()
            }
        }

        registerMainAPI(AnimekhorProvider())
    }
}
