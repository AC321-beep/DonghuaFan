package com.reel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    
    private fun resolveActivity(context: Context): Activity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return CommonActivity.activity
    }

    private fun syncAndRegisterProvider() {
        runCatching {
            synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.removeAll { it.name == "ReelFren" }
            }
            
            val apisField = APIHolder::class.java.getDeclaredField("apis").apply { isAccessible = true }
            val currentApis = apisField.get(APIHolder)
            
            if (currentApis is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                val mutableApis = currentApis as MutableList<MainAPI>
                synchronized(mutableApis) {
                    mutableApis.removeAll { it.name == "ReelFren" }
                }
            } else if (currentApis is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val updated = (currentApis as List<MainAPI>).filter { it.name != "ReelFren" }
                apisField.set(APIHolder, updated)
            }
        }
        registerMainAPI(ReelFrenProvider())
    }

    override fun load(context: Context) {
        ReelFrenStore.init(context)
        ReelFrenCloudflare.prewarmIfNeeded()

        syncAndRegisterProvider()

        openSettings = {
            val activity = resolveActivity(context)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                
                val providerNames = ReelFrenConfig.PROVIDERS_MAP.keys.toMutableList()
                val providerSlugs = ReelFrenConfig.PROVIDERS_MAP.values.toMutableList()

                providerNames.add("🔧 [DEBUG] Enable Verbose Logging")

                val checkedStates = BooleanArray(providerNames.size)
                
                for (i in providerSlugs.indices) {
                    checkedStates[i] = ReelFrenStore.isProviderEnabled(providerSlugs[i])
                }
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
                                
                                for (i in providerSlugs.indices) {
                                    val slug = providerSlugs[i]
                                    val wasEnabled = ReelFrenStore.isProviderEnabled(slug)
                                    val isNowEnabled = checkedStates[i]
                                    
                                    if (wasEnabled != isNowEnabled) {
                                        ReelFrenStore.setProviderEnabled(editor, slug, isNowEnabled)
                                        changed = true
                                    }
                                }
                                
                                val newVerboseState = checkedStates[providerNames.size - 1]
                                if (ReelFrenLog.isVerbose != newVerboseState) {
                                    ReelFrenStore.setVerboseLogging(newVerboseState)
                                    changed = true
                                }

                                if (changed) {
                                    editor.apply()
                                    syncAndRegisterProvider() 
                                    
                                    Toast.makeText(activity, "Settings applied! Reloading...", Toast.LENGTH_SHORT).show()
                                    
                                    // FIX: 200ms delay gives the UI time to close the AlertDialog before wiping the activity
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        runCatching { activity.recreate() }
                                    }, 200)
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
