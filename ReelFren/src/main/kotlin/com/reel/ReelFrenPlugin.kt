package com.reel

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize the Store (SharedPreferences & UAs)
        ReelFrenStore.init(context)
        
        // Register the provider exactly once on boot
        registerMainAPI(ReelFrenProvider())

        openSettings = {
            val activity = CommonActivity.activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                
                val providerNames = ReelFrenConfig.PROVIDERS_MAP.keys.toMutableList()
                val providerSlugs = ReelFrenConfig.PROVIDERS_MAP.values.toMutableList()

                // Appending clearly marked toggle to the bottom of the list without a spacer.
                providerNames.add("🔧 [DEBUG] Enable Verbose Logging")

                val checkedStates = BooleanArray(providerNames.size)
                
                // Load Provider States
                for (i in providerSlugs.indices) {
                    checkedStates[i] = ReelFrenStore.isProviderEnabled(providerSlugs[i])
                }
                
                // Load Verbose Logging State (Last Item)
                checkedStates[providerNames.size - 1] = ReelFrenLog.isVerbose

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    
                    AlertDialog.Builder(activity)
                        .setTitle("ReelFren Settings")
                        .setMultiChoiceItems(providerNames.toTypedArray(), checkedStates) { _, which, isChecked ->
                            checkedStates[which] = isChecked
                        }
                        .setPositiveButton("Save") { _, _ ->
                            val editor = ReelFrenStore.providerPrefsEditor()
                            if (editor != null) {
                                var changed = false
                                
                                // Save Providers
                                for (i in providerSlugs.indices) {
                                    val slug = providerSlugs[i]
                                    val wasEnabled = ReelFrenStore.isProviderEnabled(slug)
                                    val isNowEnabled = checkedStates[i]
                                    
                                    if (wasEnabled != isNowEnabled) {
                                        ReelFrenStore.setProviderEnabled(editor, slug, isNowEnabled)
                                        changed = true
                                    }
                                }
                                
                                // Save Verbose Logging (Last Item)
                                val newVerboseState = checkedStates[providerNames.size - 1]
                                if (ReelFrenLog.isVerbose != newVerboseState) {
                                    ReelFrenStore.setVerboseLogging(newVerboseState)
                                    changed = true
                                }

                                if (changed) {
                                    editor.apply()
                                    
                                    // [FIX] Removed registerMainAPI() here to prevent UI duplication.
                                    // ReelFrenProvider uses `get() =` so changes apply automatically.
                                    Toast.makeText(activity, "Settings saved! Please reload the Home Page.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Clear Cookies") { _, _ ->
                            CoroutineScope(Dispatchers.IO).launch {
                                // 1. Wipe WebView Cookies
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.CookieManager.getInstance().flush()
                                
                                // 2. Wipe persistent SharedPreferences for the plugin
                                val prefs = context.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
                                prefs.edit().clear().apply()
                                
                                // 3. Notify user on Main thread
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(activity, "Cookies and CF Clearance cleared. Please restart the app.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .show()
                }
            } else {
                Toast.makeText(context, "Cannot open settings right now", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
