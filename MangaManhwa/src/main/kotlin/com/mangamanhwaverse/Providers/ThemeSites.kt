package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.ThemeBasedProvider
import com.mangamanhwaverse.core.Themes

// 1. Madara & Toonily Sites
class Manhuanext : ThemeBasedProvider(Themes.Madara, "Manhuanext", "https://manhuanext.com")
class Toonily : ThemeBasedProvider(Themes.Toonily, "Toonily", "https://toonily.com", fallbackUrls = listOf("https://toonily.me"))

// 2. MangaBox Sites
class MangaKakalot : ThemeBasedProvider(Themes.MangaBox, "MangaKakalot", "https://www.mangakakalot.gg", fallbackUrls = listOf("https://mangakakalot.gg"))
class MangaBat : ThemeBasedProvider(Themes.MangaBox, "MangaBat", "https://www.mangabats.com", fallbackUrls = listOf("https://mangabat.com"))
