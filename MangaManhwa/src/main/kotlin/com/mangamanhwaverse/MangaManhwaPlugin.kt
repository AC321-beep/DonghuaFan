package com.mangamanhwaverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.mangamanhwaverse.providers.*

@CloudstreamPlugin
class MangaManhwaPlugin : Plugin() {
    override fun load(context: Context) {
        // Madara theme sources
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

        // MangaThemesia theme sources
        registerMainAPI(ManhwaFreakXyz())
        registerMainAPI(IgnisComic())
        registerMainAPI(MangaKakalot())
        registerMainAPI(MangaBat())
        registerMainAPI(MangaReader())
        registerMainAPI(MangaPanda())
        registerMainAPI(MangaFox())
        registerMainAPI(ManhwaWorld())
        registerMainAPI(NyxScans())

        // API-based sources
        registerMainAPI(MangaDex())
        registerMainAPI(TempleScan())
    }
}
