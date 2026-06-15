package com.livesports

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LiveSportsPlugin : Plugin() {
    override fun load(context: Context) {
        // 1. Share the context with our providers
        IPTVProvider.context = context
        LiveSportsEvents.context = context

        // 2. Register the main Live Events scraper
        registerMainAPI(LiveSportsEvents())

        // 3. Load SharedPreferences to see which IPTV lists the user enabled
        val sharedPref = context.getSharedPreferences("LiveSports", Context.MODE_PRIVATE)

        // 4. Hardcoded list of categories to prevent NetworkOnMainThread crashes during app boot
        val playlistNames = listOf(
            "TATA PLAY", "HOTSTAR", "JIO IND", "SONY IN", 
            "SUN DIRECT", "VOOT IND", "ZEE5", "ICC TV", 
            "JIOTV+ S2", "T SPORTS"
        )
        
        val enabledPlaylists = playlistNames.filter { sharedPref.getBoolean(it, false) }
        
        // 5. Register an IPTVProvider instance for each enabled category
        enabledPlaylists.forEach { title ->
            // Pass a generic URL since IPTVProvider dynamically handles the logic
            registerMainAPI(IPTVProvider(title, "https://fifabd.site/OPLLX7/LIVE2.m3u"))
        }

        // 6. Set up the Settings UI
        val activity = context as AppCompatActivity
        openSettings = {
            SettingsDialog(this, sharedPref, playlistNames)
                .show(activity.supportFragmentManager, "LiveSportsSettings")
        }
    }
}
