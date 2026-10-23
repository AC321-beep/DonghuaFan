package com.mangamanhwaverse.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.mangamanhwaverse.core.MangaManhwaProvider
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

// ==========================================
// Next.js JSON Data Models
// ==========================================
data class NextData(@JsonProperty("props") val props: Props?)
data class Props(@JsonProperty("pageProps") val pageProps: PageProps?)
data class PageProps(
    @JsonProperty("series") val seriesDetail: SeriesNode?,
    @JsonProperty("latestEntries") val latestEntries: EntryBlock?,
    @JsonProperty("popularEntries") val popularEntries: EntryBlock?,
    @JsonProperty("staffPicks") val staffPicks: EntryBlock?,
    @JsonProperty("latestNovels") val latestNovels: EntryBlock?,
    @JsonProperty("novels") val novels: EntryBlock?
)
data class EntryBlock(@JsonProperty("blocks") val blocks: List<Block>?)
data class Block(@JsonProperty("series") val series: List<SeriesNode>?)
data class SeriesNode(
    @JsonProperty("series_id") val seriesId: Int?,
    @JsonProperty("novel_id") val novelId: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("chapters") val chapters: List<ChapterNode>?
)
data class ChapterNode(
    @JsonProperty("chapter") val chapter: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("token") val token: String?
)

// ==========================================
// Base Provider
// ==========================================
open class NextGenWebProvider(
    override var name: String,
    override var mainUrl: String,
    private val popularPath: String = "/series?page={page}&order=popular",
    private val latestPath: String = "/series?page={page}&order=update",
    private val trendingPath: String = "/series?page={page}&order=rating",
    private val completedPath: String = "/series?page={page}&status=completed",
    private val newPath: String = "/series?page={page}&order=latest",
    private val searchPath: String = "/series?page={page}&searchTerm={query}",
    private val seriesPathRegex: Regex = Regex("""/(?:series|comics|manga|novel)/[^/"'#?]+""")
) : MangaManhwaProvider() {

    override val baseUrl: String get() = mainUrl
    override val requiresCf: Boolean = true

    protected open suspend fun fetchCategory(pathTemplate: String, page: Int, mode: String): List<SearchResponse> {
        if (pathTemplate.isBlank()) return emptyList()
        val doc = fetch(pathTemplate.replace("{page}", "$page")) ?: return emptyList()
        return parseSeriesCards(doc, mode, page)
    }

    override suspend fun popular(page: Int): List<SearchResponse> = fetchCategory(popularPath, page, "popular")
    override suspend fun latest(page: Int): List<SearchResponse> = fetchCategory(latestPath, page, "latest")
    override suspend fun trending(page: Int): List<SearchResponse> = fetchCategory(trendingPath, page, "trending")
    override suspend fun completed(page: Int): List<SearchResponse> = fetchCategory(completedPath, page, "completed")
    override suspend fun newReleases(page: Int): List<SearchResponse> = fetchCategory(newPath, page, "new")

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = fetch(searchPath.replace("{page}", "$page").replace("{query}", encoded)) ?: return emptyList()
        return parseSeriesCards(doc, "search", page).filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        val episodes = chaptersFromDoc(url, doc)

        // Title parsing via Next.js JSON or fallback to DOM
        val nextDataStr = doc.selectFirst("script#__NEXT_DATA__")?.data()
        var title = ""
        var poster = ""
        var plot = ""

        if (!nextDataStr.isNullOrBlank()) {
            try {
                val nextData = parseJson<NextData>(nextDataStr)
                val series = nextData.props?.pageProps?.seriesDetail
                if (series != null) {
                    title = series.title ?: ""
                    val id = series.seriesId ?: series.novelId
                    val isNovel = series.novelId != null
                    val folder = if (isNovel) "novels" else "series"
                    poster = "https://cdn.flamecomics.xyz/uploads/images/$folder/$id/${series.cover}"
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // Fallback for Astro / standard DOM
        if (title.isBlank()) {
            title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ") ?: titleFromSlug(url)
            poster = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        }
        
        plot = doc.selectFirst("meta[property='og:description']")?.attr("content") ?: ""

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val doc = fetch(mangaUrl) ?: return emptyList()
        return chaptersFromDoc(mangaUrl, doc)
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        val domImages = doc.select("img.mx-auto, div#readerarea img, .reader-area img, main img")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } ?: it.attr("data-src") }
            .map { cleanNextJsImage(abs(it)) }
            .distinct()

        if (domImages.isNotEmpty()) return domImages

        val scriptHtml = doc.select("script").joinToString("\n") { it.data() }
        return Regex("""https?://[^"'\s\\]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
            .findAll(scriptHtml)
            .map { cleanNextJsImage(it.value) }
            .filter { !it.contains("logo", true) && !it.contains("cover", true) }
            .distinct()
            .toList()
    }

    // ==========================================
    // Core Parsing Logic
    // ==========================================
    protected open fun parseSeriesCards(doc: Document, mode: String = "popular", page: Int = 1): List<SearchResponse> {
        val nextDataStr = doc.selectFirst("script#__NEXT_DATA__")?.data()

        // 1. Next.js JSON Extraction (Flame Comics)
        if (!nextDataStr.isNullOrBlank()) {
            val responses = mutableListOf<SearchResponse>()
            try {
                val nextData = parseJson<NextData>(nextDataStr)
                val pageProps = nextData.props?.pageProps
                
                // Select block based on mode
                val targetBlocks = when (mode) {
                    "latest" -> listOf(pageProps?.latestEntries, pageProps?.latestNovels)
                    "popular", "trending" -> listOf(pageProps?.popularEntries, pageProps?.staffPicks)
                    "new", "completed", "search" -> listOf(pageProps?.latestEntries, pageProps?.latestNovels, pageProps?.novels) // Dump all available
                    else -> listOf(pageProps?.latestEntries)
                }

                targetBlocks.filterNotNull().forEach { entryBlock ->
                    entryBlock.blocks?.forEach { block ->
                        block.series?.forEach { series ->
                            val id = series.seriesId ?: series.novelId ?: return@forEach
                            val isNovel = series.novelId != null
                            val folder = if (isNovel) "novels" else "series"
                            val route = if (isNovel) "novel" else "series"
                            
                            val url = "$mainUrl/$route/$id"
                            val img = "https://cdn.flamecomics.xyz/uploads/images/$folder/$id/${series.cover}"
                            
                            responses.add(newAnimeSearchResponse(series.title ?: "Unknown", url, TvType.Anime) {
                                this.posterUrl = img
                            })
                        }
                    }
                }
                return responses.distinctBy { it.url }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Fallback Astro/DOM Parsing (AsuraScans / Legacy sites)
        val parsedCards = mutableListOf<SearchResponse>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!seriesPathRegex.containsMatchIn(href) || href.contains("/chapter", true)) return@forEach

            val fullUrl = abs(href)
            val imgEl = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            var poster = imgEl?.attr("src")?.takeIf { it.isNotBlank() } ?: imgEl?.attr("data-src")
            poster = poster?.let { cleanNextJsImage(abs(it)) }

            val title = a.selectFirst("h3, h4, .title")?.text()?.trim()
                ?: imgEl?.attr("alt")?.trim()
                ?: titleFromSlug(href)

            if (title.isNotBlank() && title.length > 2 && !title.equals("poster", true)) {
                parsedCards.add(newAnimeSearchResponse(title, fullUrl, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return parsedCards.distinctBy { it.url }
    }

    protected open fun chaptersFromDoc(mangaUrl: String, doc: Document): List<Episode> {
        val eps = mutableListOf<Episode>()
        val nextDataStr = doc.selectFirst("script#__NEXT_DATA__")?.data()

        // 1. Next.js JSON Extraction (Flame Comics exact token linking)
        if (!nextDataStr.isNullOrBlank()) {
            try {
                val nextData = parseJson<NextData>(nextDataStr)
                val series = nextData.props?.pageProps?.seriesDetail
                if (series != null) {
                    val id = series.seriesId ?: series.novelId
                    val isNovel = series.novelId != null
                    val route = if (isNovel) "novel" else "series"

                    series.chapters?.forEach { ch ->
                        val token = ch.token ?: return@forEach
                        val chapterUrl = "$mainUrl/$route/$id/$token"
                        val name = if (!ch.title.isNullOrBlank()) "Chapter ${ch.chapter} - ${ch.title}" else "Chapter ${ch.chapter}"
                        
                        eps.add(newEpisode(chapterUrl) { 
                            this.name = name 
                        })
                    }
                    return eps.reversed()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Fallback Astro/DOM Parsing (AsuraScans)
        doc.select("a[href*='chapter'], div.chapter-list a, a.chapter-link").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") return@forEach

            val name = a.selectFirst("h3, span")?.text()?.trim() ?: a.text().trim()
            eps.add(newEpisode(abs(href)) { this.name = name })
        }
        return eps.distinctBy { it.data }.reversed()
    }

    // Helper: Next.js obscures image links dynamically, this unpacks the raw CDN link.
    private fun cleanNextJsImage(url: String): String {
        return if (url.contains("/_next/image?url=")) {
            val encodedUrl = url.substringAfter("url=").substringBefore("&")
            URLDecoder.decode(encodedUrl, "UTF-8")
        } else {
            url
        }
    }
}

// ==========================================
// Implemented Source Classes
// ==========================================

class FlameComics : NextGenWebProvider(
    name = "FlameComics",
    mainUrl = "https://flamecomics.xyz",
    popularPath = "/browse",
    latestPath = "/browse",
    trendingPath = "/browse",
    completedPath = "/browse",
    newPath = "/browse",
    searchPath = "/browse?search={query}"
)

// Updated to Asura's new Astro dot-com domain with exact query params
class AsuraScans : NextGenWebProvider(
    name = "AsuraScans",
    mainUrl = "https://asurascans.com",
    popularPath = "/browse/comics?page={page}&order=bookmarks",
    latestPath = "/browse/comics?page={page}&order=update",
    trendingPath = "/browse/comics?page={page}&order=rating",
    completedPath = "/browse/comics?page={page}&status=completed",
    newPath = "/browse/comics?page={page}&order=desc",
    searchPath = "/browse/comics?page={page}&search={query}"
)

class HiveScans : NextGenWebProvider(
    name = "HiveScans",
    mainUrl = "https://hivetoons.org",
    popularPath = "/series?page={page}&order=popular",
    latestPath = "/series?page={page}&order=update",
    trendingPath = "/series?page={page}&order=views",
    completedPath = "/series?page={page}&status=completed",
    newPath = "/series?page={page}&order=latest",
    searchPath = "/series?page={page}&searchTerm={query}"
)

class NyxScans : NextGenWebProvider(
    name = "NyxScans",
    mainUrl = "https://nyxscans.com",
    popularPath = "/series?page={page}&order=popular",
    latestPath = "/series?page={page}&order=update",
    trendingPath = "/series?page={page}&order=views",
    completedPath = "/series?page={page}&status=completed",
    newPath = "/series?page={page}&order=latest",
    searchPath = "/series?page={page}&searchTerm={query}"
)
