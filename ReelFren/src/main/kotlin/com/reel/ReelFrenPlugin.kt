package com.reel

import android.app.AlertDialog
import android.content.Context
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        ReelFrenStore.init(context)
        ReelFrenCloudflare.prewarmIfNeeded()

        registerMainAPI(ReelFrenProvider())

        openSettings = {
            val activity = CommonActivity.activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                
                val providerNames = ReelFrenConfig.PROVIDERS_MAP.keys.toTypedArray()
                val providerSlugs = ReelFrenConfig.PROVIDERS_MAP.values.toTypedArray()
                
                val checkedStates = BooleanArray(providerSlugs.size) { i ->
                    ReelFrenStore.isProviderEnabled(providerSlugs[i])
                }

                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    
                    // Create a clean, separate container for the Verbose Logging toggle
                    val container = FrameLayout(activity)
                    val verboseCheckbox = CheckBox(activity).apply {
                        text = "Enable Verbose Logging (Debug)"
                        isChecked = ReelFrenLog.isVerbose
                    }
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, 
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(60, 20, 60, 20)
                    }
                    container.addView(verboseCheckbox, params)

                    AlertDialog.Builder(activity)
                        .setTitle("Select Providers")
                        .setMultiChoiceItems(providerNames, checkedStates) { _, which, isChecked ->
                            checkedStates[which] = isChecked
                        }
                        // This pins the CheckBox to the bottom of the dialog, visually separate from the list
                        .setView(container) 
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
                                
                                // Save Verbose Logging state from the standalone checkbox
                                val newVerboseState = verboseCheckbox.isChecked
                                if (ReelFrenLog.isVerbose != newVerboseState) {
                                    ReelFrenStore.setVerboseLogging(newVerboseState)
                                    changed = true
                                }

                                if (changed) {
                                    editor.apply()
                                    // Hot-Reload the provider tabs by re-registering
                                    registerMainAPI(ReelFrenProvider()) 
                                    Toast.makeText(activity, "Settings applied! Reload home page.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Clear Cookies") { _, _ ->
                            CoroutineScope(Dispatchers.IO).launch {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.CookieManager.getInstance().flush()
                                
                                val prefs = context.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
                                prefs.edit().clear().apply()
                                
                                CoroutineScope(Dispatchers.Main).launch {
                                    Toast.makeText(activity, "Cookies and CF Clearance cleared.", Toast.LENGTH_SHORT).show()
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
