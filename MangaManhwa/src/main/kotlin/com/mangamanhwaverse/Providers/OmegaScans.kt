package com.mangamanhwaverse.providers

import com.mangamanhwaverse.core.MangaManhwaProvider
import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class OmegaScans : MangaManhwaProvider() {
    override var name = "OmegaScans"
    override var mainUrl = "https://omegascans.org"
    override val baseUrl = "https://omegascans.org"
    override var lang = "en"

    override val mainPage = mainPageOf("hot" to "Latest Updates")

    override suspend fun hot(page: Int): List<SearchResponse> = fetch("$baseUrl/")?.let(::parseSeriesGrid) ?: emptyList()
    override suspend fun popular(page: Int): List<SearchResponse> = hot(page)
    override suspend fun latest(page: Int): List<SearchResponse> = hot(page)

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        return fetch("$baseUrl/series?site_query=$enc")?.let(::parseSeriesGrid) ?: emptyList()
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        return parseChapters(doc)
    }

    // Helper to extract chapters from a Document (avoids double fetching)
    private fun parseChapters(doc: Document): List<Episode> {
        val eps = mutableListOf<Episode>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            // Match /series/{slug}/chapter-{num}
            if (!href.matches(Regex("^/series/[^/]+/[^/]+$"))) return@forEach
            
            val chapterName = a.selectFirst("span")?.text()?.trim() ?: href.substringAfterLast("/")
            val fullUrl = abs(href)
            if (eps.none { it.data == fullUrl }) {
                eps.add(newEpisode(fullUrl) { this.name = chapterName })
            }
        }
        
        // Sort newest first (handles chapter-57-5 as 57.5)
        val num = Regex("""(\d+(?:[.\-]\d+)?)""")
        eps.sortByDescending { ep -> 
            num.find(ep.name ?: "")?.value?.replace('-', '.')?.toFloatOrNull() ?: 0f 
        }
        return eps
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        val images = mutableListOf<String>()
        
        doc.select("img").forEach { img ->
            val src = extractImage(img) ?: return@forEach
            // STRICT FILTER: Only take actual chapter images from the uploads folder.
            // This ignores the site logo, user avatars, and recommendation covers.
            if (!src.contains("/uploads/series/")) return@forEach
            if (images.contains(src)) return@forEach
            images.add(src)
        }
        return images
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        
        // 1. Extract Title
        val title = doc.selectFirst("h1")?.text()?.trim() ?: titleFromSlug(url)
        
        // 2. Extract Plot
        val plot = doc.selectFirst("div.text-muted-foreground > div")?.text()?.trim()
            ?: doc.selectFirst("p")?.text()?.trim()
        
        // 3. Extract Cover Image (looks for the actual poster, not the blurred background)
        val poster = doc.selectFirst("img[srcset*='media.omegascans.org'], img[src*='media.omegascans.org']")
            ?.let(::extractImage)?.let(::abs)
        
        // 4. Extract Status
        val statusStr = doc.select("span.uppercase").firstOrNull { 
            it.text() == "Ongoing" || it.text() == "Completed" || it.text() == "Dropped" || it.text() == "Coming Soon"
        }?.text()

        // 5. Parse Chapters directly from the fetched page (avoids 2nd fetch failure)
        val episodes = parseChapters(doc)
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            
                this.showStatus = when (statusStr?.lowercase()) {
                "completed", "finished" -> ShowStatus.Completed
                "ongoing", "releasing", "coming soon" -> ShowStatus.Ongoing
                else -> null
            }
            
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    private fun parseSeriesGrid(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { a ->
        val href = a.attr("href").trim()
        if (!href.matches(Regex("^/series/[^/]+$"))) return@mapNotNull null
        val title = a.selectFirst("h5")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: a.parent()?.parent()?.selectFirst("img") ?: return@mapNotNull null
        val poster = extractImage(img) ?: return@mapNotNull null
        newAnimeSearchResponse(title, abs(href), TvType.Anime) { this.posterUrl = poster }
    }.distinctBy { it.url }

    private fun extractImage(img: Element?): String? {
        img ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }
        if (src.isBlank()) return null
        
        if (src.contains("/_next/image")) {
            return src.substringAfter("?", "").split("&").firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=")
                ?.let { URLDecoder.decode(it.replace("&amp;", "&"), "UTF-8") }
                ?.takeIf { it.isNotBlank() }
        }
        return if (src.startsWith("http")) src else abs(src)
    }
}
