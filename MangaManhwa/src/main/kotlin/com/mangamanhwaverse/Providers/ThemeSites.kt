package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.ThemeBasedProvider
import com.mangamanhwaverse.core.Themes

// 1. True Madara Sites (WordPress)
class Manhuanext : ThemeBasedProvider(Themes.Madara, "Manhuanext", "https://manhuanext.com", fallbackUrls = listOf("https://manhuanext.net", "https://manhuanext.to"))
class Toonily : ThemeBasedProvider(Themes.Madara, "Toonily", "https://toonily.com", fallbackUrls = listOf("https://toonily.me", "https://toonily.net"))
class MangaTX : ThemeBasedProvider(Themes.Madara, "MangaTX", "https://mangatx.to", fallbackUrls = listOf("https://mangatx.com", "https://mangatx.net"))
class MangaBob : ThemeBasedProvider(Themes.Madara, "MangaBob", "https://mangabob.com", fallbackUrls = listOf("https://mangabob.net"))

// 2. Moved to MangaThemesia (WordPress)
class LuminousScans : ThemeBasedProvider(Themes.MangaThemesia, "LuminousScans", "https://luminousscans.com", fallbackUrls = listOf("https://luminousscans.net"))
class CosmicScans : ThemeBasedProvider(Themes.MangaThemesia, "CosmicScans", "https://01.cosmicscans.to", fallbackUrls = listOf("https://cosmicscans.to"))
class IgnisComic : ThemeBasedProvider(Themes.MangaThemesia, "IgnisComic", "https://igniscomic.com", fallbackUrls = listOf("https://igniscomic.net"))

// 3. True MangaBox Sites (Aggregators)
class MangaKakalot : ThemeBasedProvider(Themes.MangaBox, "MangaKakalot", "https://mangakakalot.com", fallbackUrls = listOf("https://mangakakalot.tv"))
class MangaBat : ThemeBasedProvider(Themes.MangaBox, "MangaBat", "https://www.mangabats.com", fallbackUrls = listOf("https://mangabat.com"))
