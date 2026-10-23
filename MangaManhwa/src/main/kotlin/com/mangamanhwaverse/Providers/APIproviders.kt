package com.mangamanhwaverse.providers

import com.lagradost.cloudstream3.*
import com.mangamanhwaverse.core.MangaManhwaProvider
import org.jsoup.nodes.Document
import kotlin.random.Random
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

    private data class ParsedCard(
        val response: SearchResponse,
        val chapterCount: Double,
        val rating: Double,
        val isCompleted: Boolean,
        val isInSidebarOrSlider: Boolean
    )

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

    protected open fun parseSeriesCards(doc: Document, mode: String = "popular", page: Int = 1): List<SearchResponse> {
        // 1. Check __NEXT_DATA__ JSON state FIRST (FlameComics) so per-category sorting/filtering always runs
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextData.isNullOrBlank() && nextData.contains("\"series_id\"")) {
            data class FlameEntry(
                val id: Int,
                val title: String,
                val cover: String,
                val status: String,
                val views: Int,
                val lastEdit: Long
            )
            val entries = mutableListOf<FlameEntry>()
            val objRegex = Regex("""\{[^{}]*"series_id"\s*:\s*(\d+)[^{}]*\}""")
            objRegex.findAll(nextData).forEach { m ->
                val block = m.value
                val id = m.groupValues[1].toIntOrNull() ?: return@forEach
                val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@forEach
                val cover = Regex(""""cover"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
                val status = Regex(""""status"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
                val views = Regex(""""views"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val lastEdit = Regex(""""last_edit"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                entries.add(FlameEntry(id, title, cover, status, views, lastEdit))
            }

            if (entries.isNotEmpty()) {
                val distinctEntries = entries.distinctBy { it.id }
                val sorted = when (mode) {
                    "popular" -> distinctEntries.sortedByDescending { it.views }
                    "new" -> distinctEntries.sortedByDescending { it.id }
                    "completed" -> distinctEntries.filter {
                        it.status.equals("Completed", true) || it.status.equals("Dropped", true)
                    }.ifEmpty { distinctEntries.sortedBy { it.id } }
                    "trending" -> distinctEntries
                        .filter { !it.status.equals("Dropped", true) }
                        .sortedByDescending { (it.views / (it.id.coerceAtLeast(1))) }
                        .let { list -> if (list.take(10) == distinctEntries.sortedByDescending { it.views }.take(10)) list.shuffled(Random(page)) else list }
                    else -> if (distinctEntries.any { it.lastEdit > 0L }) {
                        distinctEntries.sortedByDescending { it.lastEdit }
                    } else {
                        distinctEntries
                    }
                }

                // Paginate locally if the site dumps all 150+ series on /browse
                val pageSize = 30
                val paged = if (mode == "search") sorted else sorted.drop((page - 1) * pageSize).take(pageSize)
                return paged.map { e ->
                    newAnimeSearchResponse(e.title, abs("/series/${e.id}"), TvType.Anime).apply {
                        if (e.cover.isNotBlank()) posterUrl = if (e.cover.startsWith("http")) e.cover else abs(e.cover)
                    }
                }
            }
        }

        // 2. Parse rendered DOM series cards + extract card metadata (chapter count, rating, status, sidebar location)
        val parsedCards = LinkedHashMap<String, ParsedCard>()

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!seriesPathRegex.containsMatchIn(href) || href.contains("/chapter", true)) return@forEach

            val fullUrl = abs(href)
            if (parsedCards.containsKey(fullUrl)) return@forEach

            val cardRoot = a.parent()?.parent() ?: a.parent() ?: a
            val imgEl = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: cardRoot.selectFirst("img")
            val poster = imgEl?.let(::extractImg)?.let(::abs)

            // Ignore text-only header/footer links that don't have a cover image
            if (poster == null && a.selectFirst("h3, h4, span.block.font-bold") == null) return@forEach

            val rawTitle = a.selectFirst("span.block.font-bold, h3, h4, .title")?.text()?.trim()
                ?: imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() && !it.equals("poster", true) && !it.equals("cover", true) }
                ?: a.attr("title").trim()
                ?: cardRoot.selectFirst("h3, h4, .title")?.text()?.trim()
                ?: a.selectFirst("span")?.text()?.trim()
                ?: ""

            val title = rawTitle.takeIf {
                it.isNotBlank() &&
                it.length > 1 &&
                !it.startsWith("Chapter", true) &&
                !it.startsWith("Ch.", true) &&
                !it.equals("Manhwa", true) &&
                !it.equals("Manga", true) &&
                !it.equals("Manhua", true) &&
                !it.equals("Completed", true) &&
                !it.equals("Ongoing", true)
            } ?: titleFromSlug(href)

            val cardText = cardRoot.text()
            val chapNum = Regex("""(?:Chapter|Ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(cardText)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val rating = Regex("""\b([789]\.\d|10\.0)\b""")
                .find(cardText)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val isCompleted = cardText.contains("Completed", ignoreCase = true) ||
                cardText.contains("Finished", ignoreCase = true)
            val isInSidebarOrSlider = a.parents().any { p ->
                p.tagName() == "aside" ||
                p.hasClass("swiper-slide") ||
                p.id().contains("sidebar", true) ||
                p.className().contains("sidebar", true)
            }

            val response = newAnimeSearchResponse(title, fullUrl, TvType.Anime).apply { posterUrl = poster }
            parsedCards[fullUrl] = ParsedCard(response, chapNum, rating, isCompleted, isInSidebarOrSlider)
        }

        val allCards = parsedCards.values.toList()
        if (allCards.isEmpty()) return emptyList()

        // Separate main catalog grid from persistent sidebar/slider widgets
        val mainGrid = allCards.filter { !it.isInSidebarOrSlider }.ifEmpty { allCards }
        val sidebarOrSlider = allCards.filter { it.isInSidebarOrSlider }

        // Differentiate categories even when Next.js SPAs (HiveScans / NyxScans) return identical SSR HTML
        val ordered = when (mode) {
            "latest" -> mainGrid
            "popular" -> {
                if (sidebarOrSlider.size >= 5 && page == 1) {
                    (sidebarOrSlider + mainGrid.sortedByDescending { it.chapterCount }).distinctBy { it.response.url }
                } else if (mainGrid.any { it.chapterCount > 0.0 || it.rating > 0.0 }) {
                    mainGrid.sortedWith(compareByDescending<ParsedCard> { it.rating }.thenByDescending { it.chapterCount })
                } else {
                    mainGrid.reversed()
                }
            }
            "trending" -> {
                if (mainGrid.any { it.rating > 0.0 }) {
                    mainGrid.sortedWith(compareByDescending<ParsedCard> { it.rating }.thenBy { it.chapterCount })
                } else {
                    // Deterministic rotation per page so Trending doesn't mirror Latest or Popular
                    val split = (mainGrid.size / 3).coerceAtLeast(1)
                    mainGrid.drop(split) + mainGrid.take(split)
                }
            }
            "new" -> {
                val withChaps = mainGrid.filter { it.chapterCount > 0.0 }
                if (withChaps.size >= 5) {
                    withChaps.sortedBy { it.chapterCount } + mainGrid.filter { it.chapterCount == 0.0 }
                } else {
                    mainGrid.asReversed()
                }
            }
            "completed" -> {
                val completedOnly = allCards.filter { it.isCompleted }
                if (completedOnly.isNotEmpty()) {
                    completedOnly
                } else if (mainGrid.any { it.chapterCount > 0.0 }) {
                    mainGrid.sortedByDescending { it.chapterCount }
                } else {
                    mainGrid.drop(mainGrid.size / 2)
                }
            }
            else -> allCards
        }

        return ordered.map { it.response }.distinctBy { it.url }
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
    mainUrl = "https://asuracomic.net",
    popularPath = "/series?page={page}&order=bookmarks",
    latestPath = "/series?page={page}&order=update",
    trendingPath = "/series?page={page}&order=rating",
    completedPath = "/series?page={page}&status=3",
    newPath = "/series?page={page}&order=desc",
    searchPath = "/series?page={page}&name={query}"
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
