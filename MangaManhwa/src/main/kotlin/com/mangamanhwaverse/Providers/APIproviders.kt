package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class FlameComics : MangaManhwaProvider() {
    override var name = "FlameComics"
    override var mainUrl = "https://flamecomics.xyz"
    override val baseUrl: String get() = mainUrl

    override suspend fun popular(page: Int): List<SearchResponse> = emptyList()
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = emptyList()
    override suspend fun chapters(mangaUrl: String): List<Episode> = emptyList()
    override suspend fun pages(chapterUrl: String): List<String> = emptyList()
}

class AsuraScans : MangaManhwaProvider() {
    override var name = "AsuraScans"
    override var mainUrl = "https://asuracomic.net"
    override val baseUrl: String get() = mainUrl

    override suspend fun popular(page: Int): List<SearchResponse> = searchPage("", page)
    
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val doc = fetch("$mainUrl/browse?page=$page&name=$query") ?: return emptyList()
        return doc.select(".grid > a").mapNotNull<Element, SearchResponse> { a ->
            val href = a.attr("href")
            val title = a.selectFirst("div:nth-of-type(2) > span:nth-of-type(1)")?.text()?.trim() ?: return@mapNotNull null
            val poster = a.selectFirst("img")?.let { extractImg(it) }
            newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster }
        }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        return doc.select(".chapter-role, a.chapter-link").mapNotNull<Element, Episode> { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val name = a.text().trim()
            newEpisode(abs(href)) { this.name = name }
        }.reversed()
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        return doc.select("img.mx-auto, .reader-area img").mapNotNull<Element, String> { extractImg(it) }
    }
}

class HiveScans : MangaManhwaProvider() {
    override var name = "HiveScans"
    override var mainUrl = "https://hivescans.com"
    override val baseUrl: String get() = mainUrl

    override suspend fun popular(page: Int): List<SearchResponse> = emptyList()
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = emptyList()
    override suspend fun chapters(mangaUrl: String): List<Episode> = emptyList()
    override suspend fun pages(chapterUrl: String): List<String> = emptyList()
}

class NyxScans : MangaManhwaProvider() {
    override var name = "NyxScans"
    override var mainUrl = "https://nyxscans.com"
    override val baseUrl: String get() = mainUrl

    override suspend fun popular(page: Int): List<SearchResponse> = emptyList()
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = emptyList()
    override suspend fun chapters(mangaUrl: String): List<Episode> = emptyList()
    override suspend fun pages(chapterUrl: String): List<String> = emptyList()
}

// Local helper to safely extract image attributes
private fun extractImg(el: Element): String? {
    return el.attr("data-src").takeIf { it.isNotBlank() }
        ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
        ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
}
