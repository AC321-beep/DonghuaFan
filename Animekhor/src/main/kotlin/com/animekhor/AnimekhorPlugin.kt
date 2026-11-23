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
        AnimekhorLogger.init(context)
        CFState.initializeGlobalUA(context)

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
                    }
                    .setPositiveButton("Done", null)
                    .show()
            }
        }

        registerMainAPI(AnimekhorProvider())
    }
}
