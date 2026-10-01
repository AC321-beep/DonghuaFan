package com.mangamanhwaverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MangaManhwaPlugin : Plugin() {
    override fun load(context: Context) {
        // Madara theme
        registerMainAPI(Manhuanext())
        registerMainAPI(HunlightScans())
        registerMainAPI(Toonily())
        registerMainAPI(KunManga())
        registerMainAPI(MangaTX())
        registerMainAPI(ReaperScans())
        registerMainAPI(FlameComics())
        registerMainAPI(LuminousScans())
        registerMainAPI(AquaManga())
        registerMainAPI(CosmicScans())
        registerMainAPI(Disasterscans())
        registerMainAPI(VyvyManga())
        registerMainAPI(MangaBob())
        registerMainAPI(HiveScans())
        registerMainAPI(AsuraScans())

        // MangaThemesia theme
        registerMainAPI(ManhwaFreakXyz())
        registerMainAPI(IgnisComic())
        registerMainAPI(MangaKakalot())
        registerMainAPI(MangaBat())
        registerMainAPI(MangaReader())
        registerMainAPI(MangaPanda())
        registerMainAPI(MangaFox())
        registerMainAPI(ManhwaWorld())
        registerMainAPI(NyxScans())

        // API-based
        registerMainAPI(MangaDex())
    }
}
