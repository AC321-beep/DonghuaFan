package com.livesports

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class LiveSportsPlugin : Plugin() {
    override fun load(context: Context) {
        IPTVProvider.context = context
        LiveSportsEvents.context = context

        registerMainAPI(LiveSportsEvents())

        val providers = runBlocking { LiveSportsProviderManager.fetchProviders() }

        val sharedPref = context.getSharedPreferences("LiveSports", Context.MODE_PRIVATE)

        val enabledProviders = providers.filter { provider ->
            val title = provider["title"] as? String
            title != null && (sharedPref.getBoolean(title, false))
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
