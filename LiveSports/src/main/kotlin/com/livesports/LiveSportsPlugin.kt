package com.livesports

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class LiveSportsPlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("LiveSports", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        IPTVProvider.context = context
        LiveSportsEvents.context = context

        // Always register live events provider
        registerMainAPI(LiveSportsEvents())

        // Fetch available IPTV playlists from remote config
        val providers = runBlocking { LiveSportsProviderManager.fetchProviders() }

        val enabledProviders = providers.filter { provider ->
            val title = provider["title"] as? String
            title != null && (sharedPref?.getBoolean(title, false) == true)
        }

        enabledProviders.forEach { provider ->
            val title = provider["title"] as String
            val playlistUrl = provider["catLink"] as String
            registerMainAPI(IPTVProvider(title, playlistUrl))
        }

        val activity = context as AppCompatActivity
        openSettings = {
            SettingsDialog(this, sharedPref, providers.mapNotNull { it["title"] as? String })
                .show(activity.supportFragmentManager, "LiveSportsSettings")
        }
    }
}
