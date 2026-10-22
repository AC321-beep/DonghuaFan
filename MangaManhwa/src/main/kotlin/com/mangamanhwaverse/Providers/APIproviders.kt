package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document

// Common base or individual implementations sharing the Next.js / JSON data extraction pattern
class FlameComics : MangaManhwaProvider() {
    override var name = "FlameComics"
    override var mainUrl = "https://flamecomics.xyz"

    override suspend fun popular(page: Int): List<SearchResponse> {
        // Can parse search/catalog endpoints or JSON state
        val doc = fetch(mainUrl) ?: return emptyList()
        return parseNextGenList(doc)
    }

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val doc = fetch("$mainUrl/search?query=$query") ?: return emptyList()
        return parseNextGenList(doc)
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        // Next.js state extraction (e.g., parsing __NEXT_DATA__)
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        // Extract chapters safely from parsed JSON structure
        return emptyList() // Implement JSON mapping block here
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        return doc.select("div.reader-area img, .ts-main-image").mapNotNull { img(it) }
    }

    private fun parseNextGenList(doc: Document): List<SearchResponse> {
        return doc.select("a.group, div.series-card a").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = a.selectFirst("h3, .title, span")?.text()?.trim() ?: a.text().trim()
            val poster = a.selectFirst("img")?.let { img(it) }
            newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster }
        }.distinctBy { it.url }
    }
}

class AsuraScans : MangaManhwaProvider() {
    override var name = "AsuraScans"
    override var mainUrl = "https://asuracomic.net"

    override suspend fun popular(page: Int): List<SearchResponse> = searchPage("", page)
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val doc = fetch("$mainUrl/browse?page=$page&name=$query") ?: return emptyList()
        return doc.select(".grid > a").mapNotNull { a ->
            val href = a.attr("href")
            val title = a.selectFirst("div:nth-of-type(2) > span:nth-of-type(1)")?.text()?.trim() ?: return@mapNotNull null
            val poster = a.selectFirst("img")?.let { img(it) }
            newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster }
        }
    }
    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        return doc.select(".chapter-role, a.chapter-link, .flex.justify-between").mapNotNull { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val name = a.text().trim()
            newEpisode(abs(href)) { this.name = name }
        }.reversed()
    }
    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        return doc.select("img.mx-auto, .reader-area img").mapNotNull { img(it) }
    }
}

class HiveScans : MangaManhwaProvider() {
    override var name = "HiveScans"
    override var mainUrl = "https://hivescans.com"
    override suspend fun popular(page: Int): List<SearchResponse> = emptyList()
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = emptyList()
    override suspend fun chapters(mangaUrl: String): List<Episode> = emptyList()
    override suspend fun pages(chapterUrl: String): List<String> = emptyList()
}

class NyxScans : MangaManhwaProvider() {
    override var name = "NyxScans"
    override var mainUrl = "https://nyxscans.com"
    override suspend fun popular(page: Int): List<SearchResponse> = emptyList()
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = emptyList()
    override suspend fun chapters(mangaUrl: String): List<Episode> = emptyList()
    override suspend fun pages(chapterUrl: String): List<String> = emptyList()
}
