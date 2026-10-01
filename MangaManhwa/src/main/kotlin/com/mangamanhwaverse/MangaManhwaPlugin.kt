package com.mangamanhwaverse

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MangaManhwaPlugin : Plugin() {
    override fun load(context: Context) {
        AppContext.ctx = context
        Settings.init(context)

        val all: List<MainAPI> = listOf(
            // Madara (15)
            Manhuanext(), HunlightScans(), Toonily(), KunManga(), MangaTX(),
            ReaperScans(), FlameComics(), LuminousScans(), AquaManga(),
            CosmicScans(), Disasterscans(), VyvyManga(), MangaBob(),
            HiveScans(), AsuraScans(),
            // MangaThemesia (9)
            ManhwaFreakXyz(), IgnisComic(), MangaKakalot(), MangaBat(),
            MangaReader(), MangaPanda(), MangaFox(), ManhwaWorld(), NyxScans(),
            // API (2)
            MangaDex(), ComixProvider()
        )

        ProviderRegistry.names = all.map { it.name }
        val disabled = Settings.disabledProviders()
        all.filter { it.name !in disabled }.forEach { registerMainAPI(it) }

        this.openSettings = { ctx -> Settings.show(ctx) }
    }
}
