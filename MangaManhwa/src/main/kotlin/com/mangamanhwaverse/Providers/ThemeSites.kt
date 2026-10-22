package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.ThemeBasedProvider
import com.mangamanhwaverse.core.Themes

// 1. Madara & Toonily Sites
class Manhuanext : ThemeBasedProvider(Themes.Madara, "Manhuanext", "https://manhuanext.com")
class Toonily : ThemeBasedProvider(Themes.Toonily, "Toonily", "https://toonily.com", fallbackUrls = listOf("https://toonily.me"))
class MangaTX : ThemeBasedProvider(Themes.Madara, "MangaTX", "https://mangatx.to")
class MangaBob : ThemeBasedProvider(Themes.Madara, "MangaBob", "https://mangabob.com")

// 2. MangaThemesia Sites
class CosmicScans : ThemeBasedProvider(Themes.MangaThemesia, "CosmicScans", "https://01.cosmicscans.to", fallbackUrls = listOf("https://cosmicscans.to"))

// 3. MangaBox Sites
class MangaKakalot : ThemeBasedProvider(Themes.MangaBox, "MangaKakalot", "https://www.mangakakalot.gg", fallbackUrls = listOf("https://mangakakalot.gg"))
class MangaBat : ThemeBasedProvider(Themes.MangaBox, "MangaBat", "https://www.mangabats.com", fallbackUrls = listOf("https://mangabat.com"))
