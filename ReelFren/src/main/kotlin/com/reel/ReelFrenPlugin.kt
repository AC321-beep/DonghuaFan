package com.reel

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        ReelFrenStore.init(context)
        ReelFrenCloudflare.prewarmIfNeeded()

        registerMainAPI(Reelfren())
        registerExtractorAPI(ReelFrenDirectLinkExtractor())

        openSettings = {
            val activity = CommonActivity.activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val providerNames = ReelFrenConfig.PROVIDERS_MAP.keys.toTypedArray()
                val providerSlugs = ReelFrenConfig.PROVIDERS_MAP.values.toTypedArray()

                val checkedStates = BooleanArray(providerSlugs.size) { index ->
                    ReelFrenStore.isProviderEnabled(providerSlugs[index])
                }

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(activity)
                        .setTitle("Select Providers")
                        .setMultiChoiceItems(providerNames, checkedStates) { _, which, isChecked ->
                            checkedStates[which] = isChecked
                        }
                        .setPositiveButton("Save") { _, _ ->
                            ReelFrenStore.providerPrefsEditor()?.let { editor ->
                                for (i in providerSlugs.indices) {
                                    ReelFrenStore.setProviderEnabled(editor, providerSlugs[i], checkedStates[i])
                                }
                                editor.apply()
                            }
                            Toast.makeText(
                                activity,
                                "Providers updated! Reload home page to apply.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else {
                Toast.makeText(context, "Cannot open settings right now", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
