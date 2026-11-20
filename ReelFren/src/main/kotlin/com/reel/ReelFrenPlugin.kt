package com.reel

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
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
        
        ReelFrenLog.isVerbose = context.getSharedPreferences("reelfren_prefs", Context.MODE_PRIVATE)
            ?.getBoolean("verbose_logging", false) ?: false
            
        registerMainAPI(ReelFrenProvider())
    }

    companion object {
        fun showSettingsDialog(context: Context) {
            val providers = ReelFrenConfig.PROVIDERS_MAP.toList()
            
            val names = providers.map { it.first }.toMutableList().apply {
                add("") // Visual spacer
                add("\uD83D\uDD27 Enable Verbose Logging (Debug)") 
            }.toTypedArray()
            
            val isVerbose = ReelFrenLog.isVerbose
                
            val checked = providers.map { (_, slug) -> 
                ReelFrenStore.isProviderEnabled(slug) 
            }.toBooleanArray().toMutableList().apply {
                add(false) // Spacer is uncheckable
                add(isVerbose) 
            }.toBooleanArray()

            AlertDialog.Builder(context)
                .setTitle("ReelFren Settings")
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Save") { _, _ ->
                    val editor = ReelFrenStore.providerPrefsEditor()
                    if (editor != null) {
                        var changed = false
                        
                        // Save Providers
                        for (i in providers.indices) {
                            val (_, slug) = providers[i]
                            val wasEnabled = ReelFrenStore.isProviderEnabled(slug)
                            val isNowEnabled = checked[i]
                            if (wasEnabled != isNowEnabled) {
                                ReelFrenStore.setProviderEnabled(editor, slug, isNowEnabled)
                                changed = true
                            }
                        }
                        
                        // Save Verbose Logging
                        val newVerboseState = checked[checked.size - 1]
                        if (isVerbose != newVerboseState) {
                            ReelFrenLog.isVerbose = newVerboseState
                            editor.putBoolean("verbose_logging", newVerboseState)
                            changed = true
                        }

                        if (changed) {
                            editor.apply()
                            // Note: Cloudstream requires an app restart to reload the UI tabs on the homepage
                            Toast.makeText(context, "Settings saved! Restart Cloudstream to apply tabs.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear Cookies") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val act = ReelFrenCloudflare.activity()
                        if (act != null) {
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            android.webkit.CookieManager.getInstance().flush()
                        }
                        val prefs = context.getSharedPreferences("reelfren_store", Context.MODE_PRIVATE)
                        prefs.edit().clear().apply()
                        
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "Cookies and CF Clearance cleared.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }
}
