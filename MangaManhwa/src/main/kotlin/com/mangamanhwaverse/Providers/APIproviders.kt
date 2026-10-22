package com.mangamanhwaverse.providers

import com.lagradost.cloudstream3.*
import com.mangamanhwaverse.core.MangaManhwaProvider
import org.jsoup.nodes.Document
import java.net.URLEncoder

open class NextGenWebProvider(
    override var name: String,
    override var mainUrl: String,
    private val popularPath: String = "/series?page={page}&order=popular",
    private val latestPath: String = "/series?page={page}&order=update",
    private val trendingPath: String = "/series?page={page}&order=rating",
    private val completedPath: String = "/series?page={page}&status=completed",
    private val newPath: String = "/series?page={page}&order=latest",
    private val searchPath: String = "/series?page={page}&searchTerm={query}",
    private val seriesPathRegex: Regex = Regex("""/(?:series|comics|manga)/[^/"'#?]+""")
) : MangaManhwaProvider() {

    override val baseUrl: String get() = mainUrl
    override val requiresCf: Boolean = true

    protected open suspend fun fetchCategory(pathTemplate: String, page: Int, mode: String): List<SearchResponse> {
        if (pathTemplate.isBlank()) return emptyList()
        val doc = fetch(pathTemplate.replace("{page}", "$page")) ?: return emptyList()
        return parseSeriesCards(doc, mode)
    }

    override suspend fun popular(page: Int): List<SearchResponse> = fetchCategory(popularPath, page, "popular")
    override suspend fun latest(page: Int): List<SearchResponse> = fetchCategory(latestPath, page, "latest")
    override suspend fun trending(page: Int): List<SearchResponse> = fetchCategory(trendingPath, page, "trending")
    override suspend fun completed(page: Int): List<SearchResponse> = fetchCategory(completedPath, page, "completed")
    override suspend fun newReleases(page: Int): List<SearchResponse> = fetchCategory(newPath, page, "new")

    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = fetch(searchPath.replace("{page}", "$page").replace("{query}", encoded)) ?: return emptyList()
        return parseSeriesCards(doc, "search").filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        val episodes = chaptersFromDoc(url, doc)

        val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?.substringBefore(" - $name")
            ?.substringBefore(" - Asura")
            ?.substringBefore(" - Flame")
            ?.substringBefore(" - Hive")
            ?.substringBefore(" | ")
            ?.replace(Regex("""\s+Manhwa$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("Read Free", true) }

        val domTitle = cleanTitleElement(doc.selectFirst("span.text-xl.font-bold, h1:not(.sr-only), .post-title h1"))
            ?.replace(Regex("""\s+Manhwa$""", RegexOption.IGNORE_CASE), "")
            ?.takeIf { it.isNotBlank() && !it.startsWith("Read Free", true) }

        val title = ogTitle ?: domTitle ?: titleFromSlug(url)

        val ogPoster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("logo", true) }?.let(::abs)
        val domPoster = doc.selectFirst("img[alt*='poster' i], img[alt*='cover' i], .grid img")?.let(::extractImg)?.let(::abs)
        val poster = ogPoster ?: domPoster

        val plot = doc.selectFirst("span.font-medium.text-sm, div[class*='synopsis'] p, .summary__content p, meta[property='og:description']")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim()

        log("✅ Load: Title='$title' | Chapters=${episodes.size}")
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
            .mapNotNull(::extractImg)
            .map(::abs)
            .distinct()

        if (domImages.isNotEmpty()) return domImages

        val scriptHtml = doc.select("script").joinToString("\n") { it.data() }
        return Regex("""https?://[^"'\s\\]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
            .findAll(scriptHtml)
            .map { it.value }
            .filter { !it.contains("logo", true) && !it.contains("cover", true) && !it.contains("avatar", true) }
            .distinct()
            .toList()
    }

    protected open fun parseSeriesCards(doc: Document, mode: String = "popular"): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!seriesPathRegex.containsMatchIn(href) || href.contains("/chapter", true)) return@forEach

            val imgEl = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val poster = imgEl?.let(::extractImg)?.let(::abs)

            val rawTitle = a.selectFirst("span.block.font-bold, h3, h4, .title")?.text()?.trim()
                ?: imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() && !it.equals("poster", true) && !it.equals("cover", true) }
                ?: a.attr("title").trim()
                ?: a.selectFirst("span")?.text()?.trim()
                ?: ""

            val title = rawTitle.takeIf {
                it.isNotBlank() &&
                it.length > 1 &&
                !it.startsWith("Chapter", true) &&
                !it.startsWith("Ch.", true) &&
                !it.equals("Manhwa", true) &&
                !it.equals("Manga", true) &&
                !it.equals("Manhua", true)
            } ?: titleFromSlug(href)

            results.add(newAnimeSearchResponse(title, abs(href), TvType.Anime).apply { posterUrl = poster })
        }

        // Fallback to __NEXT_DATA__ JSON state (FlameComics) with per-category filtering/sorting
        if (results.isEmpty()) {
            val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (!nextData.isNullOrBlank()) {
                data class FlameEntry(val id: Int, val title: String, val cover: String, val status: String, val views: Int)
                val entries = mutableListOf<FlameEntry>()
                val objRegex = Regex("""\{[^{}]*"series_id"\s*:\s*(\d+)[^{}]*\}""")
                objRegex.findAll(nextData).forEach { m ->
                    val block = m.value
                    val id = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@forEach
                    val cover = Regex(""""cover"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
                    val status = Regex(""""status"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
                    val views = Regex(""""views"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    entries.add(FlameEntry(id, title, cover, status, views))
                }

                val sorted = when (mode) {
                    "popular" -> entries.sortedByDescending { it.views }
                    "new" -> entries.sortedByDescending { it.id }
                    "completed" -> entries.filter { it.status.equals("Completed", true) || it.status.equals("Dropped", true) }
                    "trending" -> entries.shuffled()
                    else -> entries // "latest" preserves default update order
                }

                sorted.forEach { e ->
                    results.add(
                        newAnimeSearchResponse(e.title, abs("/series/${e.id}"), TvType.Anime).apply {
                            if (e.cover.isNotBlank()) posterUrl = if (e.cover.startsWith("http")) e.cover else abs(e.cover)
                        }
                    )
                }
            }
        }

        return results.distinctBy { it.url }
    }

    protected open fun chaptersFromDoc(mangaUrl: String, doc: Document): List<Episode> {
        val eps = mutableListOf<Episode>()
        val cleanMangaUrl = abs(mangaUrl).trimEnd('/')
        val slugPath = cleanMangaUrl.substringAfter(baseUrl.trimEnd('/'))
        val baseSlug = slugPath.substringAfterLast("/").replace(Regex("""-[0-9a-f]{8}$""", RegexOption.IGNORE_CASE), "")

        // 1. Standard DOM chapter links filtered strictly to the current series slug
        doc.select("a[href*='chapter'], div.chapter-list a, a.chapter-link").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") return@forEach
            if (baseSlug.length > 2 && !href.contains(baseSlug, ignoreCase = true)) return@forEach

            val chapNumMatch = Regex("""chapter[/\-_\s]*([\d.]+)""", RegexOption.IGNORE_CASE).find(href)
            val name = chapNumMatch?.let { "Chapter ${it.groupValues[1]}" }
                ?: a.selectFirst("h3, span")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.text().trim()

            if (name.isNotBlank()) {
                eps.add(newEpisode(abs(href)) { this.name = name })
            }
        }

        // 2. Fallback to __NEXT_DATA__ chapter objects (FlameComics)
        if (eps.isEmpty()) {
            val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (!nextData.isNullOrBlank()) {
                val chapRegex = Regex(""""chapter"\s*:\s*"?([\d.]+)"?[^}]*?"token"\s*:\s*"([^"]+)"""")
                chapRegex.findAll(nextData).forEach { m ->
                    val chapNum = m.groupValues[1]
                    val token = m.groupValues[2]
                    eps.add(newEpisode("$cleanMangaUrl/$token#chapter-$chapNum") { this.name = "Chapter $chapNum" })
                }
            }
        }

        return eps.distinctBy { it.data }.reversed()
    }
}

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

class AsuraScans : NextGenWebProvider(
    name = "AsuraScans",
    mainUrl = "https://asurascans.com",
    popularPath = "/browse?page={page}&order=popular",
    latestPath = "/browse?page={page}&order=update",
    trendingPath = "/browse?page={page}&order=rating",
    completedPath = "/browse?page={page}&status=completed",
    newPath = "/browse?page={page}&order=latest",
    searchPath = "/browse?page={page}&search={query}"
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
