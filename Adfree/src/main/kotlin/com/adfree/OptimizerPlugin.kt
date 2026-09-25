package com.net.optimizer

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@CloudstreamPlugin
class OptimizerPlugin : Plugin() {
    private val TAG = "NetOpt"
    private var pluginContext: Context? = null

    override fun load(context: Context) {
        Log.i(TAG, "Initializing Universal Ad & Donation Blocker...")
        pluginContext = context
        
        FilterStore.init(context)
        SystemInterceptor.inject(context)
        
        // --- DYNAMIC BLOCKING ---
        sweepSharedPreferences(context)
        dynamicReflectionInjection()
        
        try {
            registerMainAPI(TrafficHandler())
        } catch (t: Throwable) {
            Log.e(TAG, "Relay registration failed", t)
        }

        sanitizeActions()

        openSettings = {
            try {
                SettingsDialog(context) { Log.i(TAG, "Settings saved.") }.show()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open settings", t)
            }
        }
    }

    /**
     * 1. UNIVERSAL SHARED PREFERENCES SWEEPER
     * Scans all preference files for donation/ad related keywords and neutralizes them.
     */
    private fun sweepSharedPreferences(context: Context) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (!prefsDir.exists()) return

            val keywords = listOf("donation", "ad", "popup", "promo", "support", "cooldown")
            val futureTimestamp = 4084108800000L // Jan 1, 2099
            val currentMonth = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date())

            prefsDir.listFiles()?.forEach { file ->
                val fileName = file.nameWithoutExtension.lowercase()
                if (keywords.any { fileName.contains(it) }) {
                    Log.i(TAG, "Neutralizing preference file: $fileName")
                    val prefs = context.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE)
                    val editor = prefs.edit()

                    prefs.all.forEach { (key, value) ->
                        when (value) {
                            is Long -> {
                                // Force timestamps to the future
                                if (key.contains("last_shown") || key.contains("time") || key.contains("date")) {
                                    editor.putLong(key, futureTimestamp)
                                }
                            }
                            is Int -> {
                                // Force cooldowns to max
                                if (key.contains("cooldown") || key.contains("hours")) {
                                    editor.putInt(key, 999999)
                                }
                            }
                            is Boolean -> {
                                // Disable enabled/shown flags
                                if (key.contains("enabled") || key.contains("show") || key.contains("launching")) {
                                    editor.putBoolean(key, false)
                                }
                            }
                            is String -> {
                                // Suppress "Goal Achieved" monthly popups
                                if (key.contains("achieved_shown_month")) {
                                    editor.putString(key, currentMonth)
                                }
                            }
                        }
                    }
                    editor.apply()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sweep SharedPreferences", t)
        }
    }

    /**
     * 2. DYNAMIC REFLECTION INJECTION
     * Scans all loaded providers for DonationManager, AdManager, or PopupManager classes
     * and forcibly disables them.
     */
    private fun dynamicReflectionInjection() {
        try {
            val providers = APIHolder.allProviders.toList()
            for (provider in providers) {
                var clazz: Class<*>? = provider.javaClass
                while (clazz != null && clazz != Any::class.java) {
                    for (field in clazz.declaredFields) {
                        val typeName = field.type.name.lowercase()
                        // Look for Donation, Ad, or Popup managers
                        if (typeName.contains("donation") || typeName.contains("admanager") || 
                            typeName.contains("popup") || typeName.contains("support")) {
                            
                            field.isAccessible = true
                            val instance = field.get(provider) ?: continue
                            
                            // Try to disable the manager
                            disableManagerInstance(instance)
                        }
                    }
                    clazz = clazz.superclass
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Dynamic reflection injection failed", t)
        }
    }

    private fun disableManagerInstance(instance: Any) {
        try {
            val clazz = instance.javaClass
            
            // 1. Disable boolean flags (enabled, isLaunching, isDialogShowing, testMode)
            for (field in clazz.declaredFields) {
                if (field.type == Boolean::class.javaPrimitiveType) {
                    field.isAccessible = true
                    val name = field.name.lowercase()
                    if (name.contains("enabled") || name.contains("launching") || 
                        name.contains("showing") || name.contains("test")) {
                        field.setBoolean(instance, false)
                    }
                }
            }

            // 2. Force cooldown hours to max
            for (field in clazz.declaredFields) {
                if (field.type == Int::class.javaPrimitiveType) {
                    field.isAccessible = true
                    if (field.name.lowercase().contains("cooldown")) {
                        field.setInt(instance, 999999)
                    }
                }
            }

            // 3. Look for nested DonationConfig objects and disable them
            for (field in clazz.declaredFields) {
                if (field.type.name.contains("Config")) {
                    field.isAccessible = true
                    val config = field.get(instance) ?: continue
                    val configClass = config.javaClass
                    try {
                        val enabledField = configClass.getDeclaredField("enabled")
                        enabledField.isAccessible = true
                        enabledField.setBoolean(config, false)
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            // Ignore and continue
        }
    }

    private fun sanitizeActions() {
        val actions = VideoClickActionHolder.allVideoClickActions
        val toRemove = actions.filter { action ->
            val className = action::class.java.name.lowercase()
            className.contains("malicious") || className.contains("redirect") || 
            className.contains("adnetwork") || className.contains("support") ||
            className.contains("donation") || className.contains("cncverse") ||
            className.contains("popup") || className.contains("promo")
        }
        toRemove.forEach { actions.remove(it) }
    }
}
