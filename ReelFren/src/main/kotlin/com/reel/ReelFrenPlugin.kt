package com.reel

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize your existing stores
        ReelFrenStore.init(context)
        ReelFrenCloudflare.init(context)
        
        // Pass context to the provider so the filter works
        Reelfren.setContext(context)

        // Register the provider and extractor
        registerMainAPI(Reelfren())
        registerExtractorAPI(ReelFrenDirectLinkExtractor())

        // ── SETTINGS DIALOG ──────────────────────────────────────────────────
        openSettings = {
            val activity = CommonActivity.activity
            if (activity == null) {
                Toast.makeText(context, "Cannot open settings right now", Toast.LENGTH_SHORT).show()
                return@Plugin
            }

            // The exact same map used in Reelfren.kt
            val providersMap = linkedMapOf(
                "PineDrama"       to "pinedrama",
                "Shorten"         to "shorten",
                "HappyShort"      to "happyshort",
                "Vigloo"          to "vigloo",
                "ReelShort"       to "reelshort",
                "DramaBox"        to "dramabox",
                "WeTV"            to "wetv",
                "MovieBox"        to "moviebox",
                "MovieBox Shorts" to "movieboxshorts",
                "ShortMax"        to "shortmax",
                "FlexTV"          to "flextv",
                "iQIYI"           to "iqiyi",
                "FilmBox"         to "filmbox"
            )

            val providerNames = providersMap.keys.toTypedArray()
            val providerSlugs = providersMap.values.toTypedArray()
            
            val prefs = context.getSharedPreferences("reelfren_prefs", Context.MODE_PRIVATE)
            
            // Read current states (default to true)
            val checkedStates = BooleanArray(providerNames.size) { index ->
                prefs.getBoolean("provider_${providerSlugs[index]}", true)
            }

            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("Select Providers")
                    .setMultiChoiceItems(providerNames, checkedStates) { _, which, isChecked ->
                        checkedStates[which] = isChecked
                    }
                    .setPositiveButton("Save") { _, _ ->
                        val editor = prefs.edit()
                        for (i in providerNames.indices) {
                            editor.putBoolean("provider_${providerSlugs[i]}", checkedStates[i])
                        }
                        editor.apply()
                        
                        Toast.makeText(activity, "Providers updated! Restart extension to apply.", Toast.LENGTH_LONG).show()
                        // CloudStream automatically reloads the extension when settings are closed
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
