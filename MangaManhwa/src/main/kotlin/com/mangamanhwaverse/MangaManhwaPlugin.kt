package com.mangamanhwaverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.mangamanhwaverse.providers.*

object AppContext {
    var ctx: Context? = null
}

object ProviderRegistry {
    val providers = listOf(
        // ThemeSites
        Manhuanext(), Toonily(), MangaTX(), MangaBob(),
        LuminousScans(), CosmicScans(), IgnisComic(),
        MangaKakalot(), MangaBat(),
        
        // API / Custom
        FlameComics(), AsuraScans(), HiveScans(), NyxScans(),
        MangaDex(), OmegaScans(), ComixProvider()
    )

    val names: List<String> get() = providers.map { it.name }
}

@CloudstreamPlugin
class MangaManhwaPlugin : Plugin() {
    override fun load(context: Context) {
        AppContext.ctx = context
        Settings.init(context)

        if (!Settings.hasInitializedProviderSet()) {
            Settings.setEnabledProviderNames(
                if (Settings.allProvidersEnabled()) ProviderRegistry.names.toSet()
                else emptySet()
            )
        }

        ProviderRegistry.providers
            .filter { Settings.isProviderEnabled(it.name) }
            .forEach { registerMainAPI(it) }

        this.openSettings = { ctx -> Settings.show(ctx) }
    }
}
