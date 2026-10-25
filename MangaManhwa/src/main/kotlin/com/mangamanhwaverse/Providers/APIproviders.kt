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
// Next.js JSON Data Models (Flame Comics)
// ==========================================
data class NextData(@JsonProperty("props") val props: Props?)
data class Props(@JsonProperty("pageProps") val pageProps: PageProps?)
data class PageProps(
    @JsonProperty("series") val seriesDetail: SeriesNode?,
    @JsonProperty("chapters") val chapters: List<ChapterNode>?
)
data class SeriesNode(
    @JsonProperty("series_id") val seriesId: Int?,
    @JsonProperty("novel_id") val novelId: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("chapters") val chapters: List<ChapterNode>?
)
data class ChapterNode(
    @JsonProperty("chapter") val chapter: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("token") val token: String?
)

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
        val episodes = chapters(url)

        // 1. Next.js JSON Extraction (Flame Comics exact data)
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
                    plot = series.description?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""

                    val id = series.seriesId ?: series.novelId
                    val folder = if (series.novelId != null) "novels" else "series"
                    poster = "https://cdn.flamecomics.xyz/uploads/images/$folder/$id/${series.cover}"
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Fallback DOM Parsing (vComics & Astro)
        if (title.isBlank()) {
            val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.substringBefore(" - $name")
                ?.substringBefore(" - Asura")
                ?.substringBefore(" - Flame")
                ?.substringBefore(" - Nyx")
                ?.substringBefore(" | ")
                ?.replace(Regex("""\s+Manhwa$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.startsWith("Read Free", true) }

            val domTitle = cleanTitleElement(doc.selectFirst("h1[itemProp='name'], span.text-xl.font-bold, h1:not(.sr-only), .post-title h1"))
                ?.replace(Regex("""\s+Manhwa$""", RegexOption.IGNORE_CASE), "")
                ?.takeIf { it.isNotBlank() && !it.startsWith("Read Free", true) }

            title = ogTitle ?: domTitle ?: titleFromSlug(url)

            poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() && !it.contains("logo", true) }?.let(::abs)
                ?: doc.selectFirst("img[alt*='Cover' i], img[alt*='poster' i], .grid img")?.let(::extractImg)?.let(::abs) ?: ""

            plot = doc.selectFirst("div[itemprop='description'], span.font-medium.text-sm, div[class*='synopsis'] p, .summary__content p, meta[property='og:description']")
                ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim() ?: ""
        }

        poster = cleanNextJsImage(poster)

        log("✅ Load: Title='$title' | Chapters=${episodes.size}")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val allEpisodes = LinkedHashMap<String, Episode>()
        var currentUrl = abs(mangaUrl)
        var pageNum = 1

        while (currentUrl.isNotBlank() && pageNum <= 25) {
            val doc = fetch(currentUrl) ?: break
            val pageEps = chaptersFromDoc(mangaUrl, doc)
            if (pageEps.isEmpty()) break

            var addedNew = false
            pageEps.forEach { ep ->
                if (!allEpisodes.containsKey(ep.data)) {
                    addedNew = true
                } else {
                    allEpisodes.remove(ep.data)
                }
                allEpisodes[ep.data] = ep
            }
            if (!addedNew) break

            val nextUrl = findNextChapterPageUrl(doc, currentUrl, pageNum + 1) ?: break
            currentUrl = nextUrl
            pageNum++
        }

        val rawList = backfillMissingTailChapters(allEpisodes.values.toList())
        return rawList.reversed()
    }

    override suspend fun pages(chapterUrl: String): List<String> {
        val doc = fetch(chapterUrl) ?: return emptyList()
        val domImages = doc.select("img.mx-auto, div#readerarea img, .reader-area img, .comic-body-container img, main img")
            .mapNotNull(::extractImg)
            .map { cleanNextJsImage(abs(it)) }
            .distinct()

        if (domImages.isNotEmpty()) return domImages

        val scriptHtml = doc.select("script").joinToString("\n") { it.data() }
        return Regex("""https?://[^"'\s\\]+\.(?:webp|jpg|jpeg|png)""", RegexOption.IGNORE_CASE)
            .findAll(scriptHtml)
            .map { cleanNextJsImage(it.value) }
            .filter { !it.contains("logo", true) && !it.contains("cover", true) && !it.contains("avatar", true) }
            .distinct()
            .toList()
    }

    private fun cleanNextJsImage(url: String): String {
        return if (url.contains("/_next/image?url=")) {
            val encodedUrl = url.substringAfter("url=").substringBefore("&")
            URLDecoder.decode(encodedUrl, "UTF-8")
        } else {
            url
        }
    }

    protected open fun parseSeriesCards(doc: Document, mode: String = "popular", page: Int = 1): List<SearchResponse> {
        // 1. Next.js JSON Extraction (Flame Comics local sorting)
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (name.contains("Flame", ignoreCase = true) && !nextData.isNullOrBlank() && nextData.contains("\"series_id\"")) {
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
            val novelObjRegex = Regex("""\{[^{}]*"novel_id"\s*:\s*(\d+)[^{}]*\}""")

            fun extractEntries(regex: Regex, isNovel: Boolean) {
                regex.findAll(nextData).forEach { m ->
                    val block = m.value
                    val id = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@forEach
                    var cover = Regex(""""cover"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""

                    if (cover.isNotBlank() && !cover.startsWith("http")) {
                        val folder = if (isNovel) "novels" else "series"
                        cover = "https://cdn.flamecomics.xyz/uploads/images/$folder/$id/$cover"
                    }

                    val status = Regex(""""status"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
                    val views = Regex(""""views"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val lastEdit = Regex(""""last_edit"\s*:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    entries.add(FlameEntry(id, title, cover, status, views, lastEdit))
                }
            }

            extractEntries(objRegex, false)
            extractEntries(novelObjRegex, true)

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

                val pageSize = 30
                val paged = if (mode == "search") sorted else sorted.drop((page - 1) * pageSize).take(pageSize)
                return paged.map { e ->
                    newAnimeSearchResponse(e.title, abs("/series/${e.id}"), TvType.Anime).apply {
                        if (e.cover.isNotBlank()) posterUrl = e.cover
                    }
                }
            }
        }

        // 2. DOM Parsing (Asura, Hive, Nyx)
        val parsedCards = LinkedHashMap<String, ParsedCard>()
        val chapCountRegex = Regex(
            """(?:(?:Chapter|Ch\.?)\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*(?:Chapters?|Chs?\.?))""",
            RegexOption.IGNORE_CASE
        )

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (!seriesPathRegex.containsMatchIn(href) || href.contains("/chapter", true)) return@forEach

            val fullUrl = abs(href)
            if (parsedCards.containsKey(fullUrl)) return@forEach

            val cardRoot = a.parent()?.parent() ?: a.parent() ?: a
            val imgEl = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: cardRoot.selectFirst("img")
            var poster = imgEl?.let(::extractImg)?.let(::abs)
            poster = poster?.let { cleanNextJsImage(it) }

            if (poster == null && a.selectFirst("h3, h4, span.block.font-bold") == null) return@forEach

            val rawTitle = a.selectFirst("h3.line-clamp-2, span.block.font-bold, h3, h4, .title")?.text()?.trim()
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
            val chapMatch = chapCountRegex.find(cardText)
            val chapNum = (chapMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: chapMatch?.groupValues?.getOrNull(2))?.toDoubleOrNull() ?: 0.0
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

        val mainGrid = allCards.filter { !it.isInSidebarOrSlider }.ifEmpty { allCards }
        val sidebarOrSlider = allCards.filter { it.isInSidebarOrSlider }

        val ordered = when (mode) {
            "latest", "search" -> mainGrid
            "popular" -> {
                if (sidebarOrSlider.size >= 5 && page == 1) {
                    (sidebarOrSlider + mainGrid.sortedByDescending { it.chapterCount }).distinctBy { it.response.url }
                } else if (mainGrid.any { it.chapterCount > 0.0 || it.rating > 0.0 }) {
                    mainGrid.sortedWith(compareByDescending<ParsedCard> { it.chapterCount }.thenByDescending { it.rating })
                } else {
                    mainGrid.reversed()
                }
            }
            "trending" -> {
                if (mainGrid.any { it.rating > 0.0 }) {
                    mainGrid.sortedWith(compareByDescending<ParsedCard> { it.rating }.thenByDescending { it.chapterCount })
                } else {
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

    /**
     * Extracts raw (un-reversed) chapters from a single Document.
     * Reversing is handled by the caller after multi-page aggregation and tail backfilling.
     */
    protected open fun chaptersFromDoc(mangaUrl: String, doc: Document): List<Episode> {
        val epsMap = LinkedHashMap<String, Episode>()
        val cleanMangaUrl = abs(mangaUrl).trimEnd('/')
        val slugPath = cleanMangaUrl.substringAfter(baseUrl.trimEnd('/'))
        val baseSlug = slugPath.substringAfterLast("/").replace(Regex("""-[0-9a-f]{8}$""", RegexOption.IGNORE_CASE), "")

        // 1. Next.js JSON Extraction (Flame Comics exact token linking)
        val nextDataStr = doc.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextDataStr.isNullOrBlank() && nextDataStr.contains("\"token\"")) {
            try {
                val nextData = parseJson<NextData>(nextDataStr)
                val series = nextData.props?.pageProps?.seriesDetail
                val chaptersList = nextData.props?.pageProps?.chapters ?: series?.chapters

                if (series != null && chaptersList != null) {
                    val id = series.seriesId ?: series.novelId
                    val route = if (series.novelId != null) "novel" else "series"

                    chaptersList.forEach { ch ->
                        val token = ch.token ?: return@forEach
                        val chapterUrl = "$mainUrl/$route/$id/$token"
                        val chNum = ch.chapter ?: ""
                        val name = if (!ch.title.isNullOrBlank()) "Chapter $chNum - ${ch.title}" else "Chapter $chNum"

                        epsMap[chapterUrl] = newEpisode(chapterUrl) {
                            this.name = name
                        }
                    }
                    if (epsMap.isNotEmpty()) return epsMap.values.toList()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Scoped DOM Chapter Container Links (Asura, Hive, Nyx)
        val scopedElements = doc.select(
            "section[data-series-tab-panel='chapters'] a[href], " +
            "div#chapterlist a[href], " +
            "div.eplister a[href], " +
            "ul.clstyle a[href], " +
            "div.chapter-list a[href], " +
            ".chapter-wrapper a[href]"
        )

        val isScopedContainer = scopedElements.isNotEmpty()
        val chapterElements = if (isScopedContainer) {
            scopedElements
        } else {
            doc.select(
                "div.flex.flex-col > a[href*='chapter'], " +
                "div.flex.flex-col > a[href*='ch-'], " +
                "a[href*='chapter'], a.chapter-link, a[href*='ch-'], a[href*='ep-'], a[href*='episode']"
            )
        }

        chapterElements.forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#" || href.startsWith("javascript:", true) || href.contains("All Chapters", ignoreCase = true)) return@forEach

            // Exclude sidebar/recommendation cards when using unscoped fallback selectors
            if (!isScopedContainer) {
                val inExcludedBlock = a.parents().any { p ->
                    p.tagName() == "aside" ||
                    p.className().contains("sidebar", true) ||
                    p.text().startsWith("Recommended Series", true) ||
                    p.text().startsWith("You May Also Like", true)
                }
                if (inExcludedBlock) return@forEach
                if (baseSlug.length > 3 && href.contains("/comics/") && !href.contains(baseSlug, ignoreCase = true)) return@forEach
            }

            val fullUrl = abs(href)
            if (fullUrl == cleanMangaUrl || fullUrl == "$cleanMangaUrl/") return@forEach

            val textLower = a.text().lowercase().trim()
            if (textLower.contains("read first") || textLower.contains("read latest") || textLower == "first chapter" || textLower == "start reading") return@forEach

            val vComicsName = a.selectFirst("span.font-medium, span.text-sm, p.font-medium, p.text-sm, .epcur")?.text()?.trim()
            val backupName = a.selectFirst("h3, span.chapternum")?.text()?.trim() ?: a.text().trim()

            var name = vComicsName.takeIf { !it.isNullOrBlank() } ?: backupName
            name = name.substringBefore(" ago")
                .substringBefore(" days")
                .substringBefore(" months")
                .substringBefore(" years")
                .substringBefore(" hours")
                .trim()

            if (name.isNotBlank()) {
                if (epsMap.containsKey(fullUrl)) {
                    epsMap.remove(fullUrl)
                }
                epsMap[fullUrl] = newEpisode(fullUrl) { this.name = name }
            }
        }

        // 3. Next.js Inline Script Hydration Scan (catches chapters hidden behind "Show All" buttons)
        if (baseSlug.length > 2) {
            val rawScripts = doc.select("script").joinToString("\n") { it.data() }.replace("\\/", "/")
            val scriptUrlRegex = Regex(
                """(/(?:series|comics|manga)/[^"'\s\\]*${Regex.escape(baseSlug)}[^"'\s\\]*/(?:chapter|ch)[/\-_]?(\d+(?:[.\-_]\d+)?))""",
                RegexOption.IGNORE_CASE
            )
            scriptUrlRegex.findAll(rawScripts).forEach { m ->
                val fullUrl = abs(m.groupValues[1])
                val chNumStr = m.groupValues[2].replace('-', '.').replace('_', '.')
                if (!epsMap.containsKey(fullUrl)) {
                    epsMap[fullUrl] = newEpisode(fullUrl) {
                        this.name = "Chapter $chNumStr"
                    }
                }
            }
        }

        return epsMap.values.toList()
    }

    /**
     * Dynamically backfills missing older chapters (e.g., Chapters 50 down to 1) when a site
     * truncates long descending chapter lists (300+ chapters) behind a client-side "Show All" state.
     */
    private fun backfillMissingTailChapters(episodes: List<Episode>): List<Episode> {
        if (episodes.size < 15) return episodes

        val numRegex = Regex("""(?:chapter|ch)[/\-_\s#]*(\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
        val firstNum = numRegex.find(episodes.first().data.trimEnd('/'))?.groupValues?.get(1)?.toDoubleOrNull() ?: return episodes
        val lastEp = episodes.last()
        val lastUrl = lastEp.data.trimEnd('/')
        val lastMatch = numRegex.find(lastUrl) ?: return episodes
        val lastNum = lastMatch.groupValues[1].toDoubleOrNull() ?: return episodes

        // If the list is descending (e.g., 320 down to 51) and stops above Chapter 1 with an integer URL pattern
        if (firstNum > lastNum && lastNum > 1.0 && lastNum <= 250.0 && lastNum % 1.0 == 0.0) {
            val lastInt = lastNum.toInt()
            val suffix = lastMatch.groupValues[1]
            val prefixUrl = lastUrl.dropLast(suffix.length)
            val existingUrls = episodes.map { it.data }.toHashSet()
            val backfilled = episodes.toMutableList()

            for (ch in (lastInt - 1) downTo 1) {
                val synthesizedUrl = "$prefixUrl$ch"
                if (existingUrls.add(synthesizedUrl)) {
                    backfilled.add(newEpisode(synthesizedUrl) {
                        this.name = "Chapter $ch"
                    })
                }
            }
            return backfilled
        }

        return episodes
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
    name = "Asura Scans",
    mainUrl = "https://asurascans.com",
    popularPath = "/browse/comics?page={page}&order=bookmarks",
    latestPath = "/browse/comics?page={page}&order=update",
    trendingPath = "/browse/comics?page={page}&order=rating",
    completedPath = "/browse/comics?page={page}&status=completed",
    newPath = "/browse/comics?page={page}&order=desc",
    searchPath = "/browse/comics?page={page}&search={query}"
)

class HiveToon : NextGenWebProvider(
    name = "Hive Toon",
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
