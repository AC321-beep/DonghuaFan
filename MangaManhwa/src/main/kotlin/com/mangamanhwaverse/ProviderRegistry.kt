package com.mangamanhwaverse

object ProviderRegistry {

    val providers: List<MangaManhwaProvider> = listOf(
        // Madara
        Manhuanext(),
        Toonily(),
        MangaTX(),
        FlameComics(),
        LuminousScans(),
        CosmicScans(),
        Disasterscans(),
        VyvyManga(),
        MangaBob(),
        HiveScans(),
        AsuraScans(),
        // MangaThemesia
        IgnisComic(),
        MangaKakalot(),
        MangaBat(),
        MangaReader(),
        MangaPanda(),
        MangaFox(),
        ManhwaWorld(),
        NyxScans(),
        // API
        MangaDex(),
        ComixProvider()
    )

    val names: List<String> get() = providers.map { it.name }
}
