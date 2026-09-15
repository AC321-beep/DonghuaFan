package com.chikianimation

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ChikiAnimationProvider : MainAPI() {

    override var mainUrl = "https://chikianimation.com"
    override var name = "ChikiAnimation"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    private data class MainPageEntry(val path: String, val name: String)

    private val mainPageEntries = listOf(
        MainPageEntry("anime/?status=&type=&order=update", "Recently Updated"),
        MainPageEntry("anime/?status=&type=&order=popular", "Popular"),
        MainPageEntry("anime/?status=&type=ai+animes&order=update", "AI Anime"),
        MainPageEntry("anime/?status=ongoing&type=&order=update", "Ongoing"),
        MainPageEntry("anime/?status=completed&type=&order=update", "Completed"),
        MainPageEntry("anime/?status=&type=movie&order=update", "Movies")
    )

    override val mainPage = mainPageOf(
        *mainPageEntries.map { it.path to it.name }.toTypedArray()
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    private val blacklistHosts = listOf(
        "youtube", "youtu.be", "disqus", "googlesyndication", "doubleclick",
        "vidverto", "pubfuture", "360yield", "eskimi", "onetag", "openx.net",
        "imasdk.googleapis.com", "mox.tv", "googletagmanager", "google-analytics",
        "googleadservices", "adservice.google", "amazon-adsystem", "criteo",
        "taboola", "outbrain", "mgid", "propellerads", "adsterra",
        "schema.org", "w3.org", "dmcdn.net"
    )

    private fun dbg(tag: String, msg: String) = Log.e("ChikiDbg", "[$tag] $msg")
    private fun dbgSection(tag: String) = Log.e("ChikiDbg", "════════ SECTION: $tag ════════")

    // =========================================================================
    // MAIN PAGE
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        dbgSection("getMainPage")
        val url = buildPageUrl(request.data, page)
        dbg("getMainPage", "name='${request.name}' page=$page URL='$url'")

        val document: Document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (e: Exception) {
            dbg("getMainPage", "❌ HTTP FAILED: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val rawSelect: Elements = document.select(
            "div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx"
        )
        dbg("getMainPage", "selector matched ${rawSelect.size} raw elements")

        val items = rawSelect
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        dbg("getMainPage", "→ parsed ${items.size} unique items")

        val hasNext = detectHasNextPage(document, page)
        dbg("getMainPage", "hasNext=$hasNext")

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun detectHasNextPage(document: Document, currentPage: Int): Boolean {
        val relNext = document.selectFirst("link[rel=next], a[rel=next]")
        val nextClass = document.selectFirst("a.next.page-numbers, a.nextpostslink, .pagination a.next")
        val nextPagePattern = Regex("""/page/${currentPage + 1}/""")
        val explicitNext = document.select("a[href]").any {
            nextPagePattern.containsMatchIn(it.attr("href"))
        }
        val pagBlock = document.selectFirst(".pagination, .wp-pagenavi, .page-numbers")
        return relNext != null || nextClass != null || explicitNext || pagBlock != null
    }

    private fun buildPageUrl(base: String, page: Int): String {
        val cleanBase = base.trimStart('/')
        if (page <= 1) return "$mainUrl/$cleanBase"

        val queryIndex = cleanBase.indexOf('?')
        val rawPath: String
        val query: String
        if (queryIndex >= 0) {
            rawPath = cleanBase.substring(0, queryIndex)
            query = cleanBase.substring(queryIndex)
        } else {
            rawPath = cleanBase
            query = ""
        }
        val path = rawPath.trim('/')
        val paginatedPath = if (path.isEmpty()) "page/$page/" else "$path/page/$page/"
        return "$mainUrl/$paginatedPath$query"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href]")
            ?: selectFirst("a[itemprop=url]")
            ?: selectFirst("h2 a[href]")
            ?: selectFirst("a[href]")
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (href.isBlank()) return null

        val excludePatterns = listOf(
            "/genres/", "/bookmark", "/privacy", "/contact",
            "/dmca", "/page/", "/anime-history", "/az-list",
            "/donor-wall", "/anime-requests", "/shecdule"
        )
        if (excludePatterns.any { href.contains(it) }) return null

        val title = selectFirst("div.tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("div.tt h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("img.ts-post-image")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
                    .ifEmpty { img.attr("data-lazy-src") }
                    .ifEmpty { img.attr("data-original") }
            }
                ?: selectFirst("div.limit img")?.attr("src")
                ?: selectFirst("img")?.attr("src")
        )

        return newAnimeSearchResponse(title, href) { this.posterUrl = posterUrl }
    }

    // =========================================================================
    // SEARCH
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.trim().replace(" ", "+")

        val results = coroutineScope {
            (1..3).map { page ->
                async {
                    try {
                        val url = if (page == 1) "$mainUrl/?s=$encoded"
                                  else "$mainUrl/page/$page/?s=$encoded"
                        app.get(url, headers = defaultHeaders).document
                            .select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                            .mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    // =========================================================================
    // LOAD — WITH SEASON-AWARE EPISODE PARSING
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        dbgSection("load")
        dbg("load", "URL='$url'")

        val document: Document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (e: Exception) {
            dbg("load", "❌ HTTP FAILED: ${e.message}")
            return null
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("div.thumb img.wp-post-image")?.attr("src")?.trim()
            ?: document.selectFirst("div.thumb img")?.attr("src")?.trim()
            ?: document.selectFirst("img.wp-post-image")?.attr("src")?.trim()
            ?: ""

        val description = document
            .selectFirst("div.entry-content[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()
            ?: document.selectFirst("div[itemprop=description]")?.text()?.trim()

        val genres = document.select("div.genxed a, span.genxed a, .spe .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val typeText = (
                document.selectFirst("div.typez, .spe, .infox .spe, div.anime-info .type")
                    ?.text()?.lowercase() ?: ""
                ) + " " + title.lowercase()

        val isMovie = typeText.contains("movie", ignoreCase = true)

        dbg("load", "title='$title' isMovie=$isMovie")

        if (isMovie) {
            val watchHref = document
                .selectFirst(".eplister li > a[href], .episodelist li > a[href], .eplister tr > td > a[href]")
                ?.attr("href")?.trim()
                ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, watchHref) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        var epListElements: Elements = document.select(
            ".episodelist li, .eplister li, .episodelist tr, .eplister tr"
        )
        dbg("load", "primary episode selector matched ${epListElements.size}")

        if (epListElements.isEmpty()) {
            val epPage = document
                .selectFirst(".episodelist li > a[href], .eplister li > a[href], .episodelist tr > td > a[href]")
                ?.attr("href")?.trim()
            if (!epPage.isNullOrBlank()) {
                epListElements = try {
                    app.get(fixUrl(epPage), headers = defaultHeaders).document
                        .select(".episodelist li, .eplister li, .episodelist tr, .eplister tr")
                } catch (e: Exception) { Elements() }
            }
        }

        dbg("load", "processing ${epListElements.size} episode elements")

        val episodes = epListElements.mapNotNull { info ->
            val href = info.selectFirst("a[href]")?.attr("href")?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // Full title from <h3>
            val fullTitle = info.selectFirst(".playinfo h3")?.text()?.trim()
                ?: info.selectFirst(".epl-title")?.text()?.trim()
                ?: info.selectFirst("td.ep-title")?.text()?.trim()
                ?: info.select("td").getOrNull(1)?.text()?.trim()
                ?: info.selectFirst("a span")?.text()?.trim()
                ?: info.selectFirst("a")?.text()?.trim()
                ?: ""

            // Structured metadata from <span>: "Eps S4-419 to 422 - ... - date"
            val metaSpan = info.selectFirst(".playinfo span")?.text()?.trim()
                ?: info.selectFirst(".epl-date, .date, .time, td.date")?.text()?.trim()
                ?: ""

            val (season, epStart, epEnd) = parseSeasonEpisode(metaSpan, fullTitle)
            val dateText = extractDate(metaSpan)

            val displayName = buildShortEpisodeName(season, epStart, epEnd, fullTitle)

            dbg(
                "load.ep",
                "✓ S=$season E=$epStart..${epEnd ?: "-"} name='$displayName'"
            )

            newEpisode(fixUrl(href)) {
                this.name = displayName
                this.posterUrl = poster
                if (season != null) this.season = season
                if (epStart != null) this.episode = epStart
                if (dateText != null) {
                    this.addDate(dateText, format = "MMMM d, yyyy")
                    this.description = dateText
                }
            }
        }.distinctBy { it.data }

        // Sort by season then episode number
        val sortedEpisodes = episodes.sortedWith(
            compareBy(
                { it.season ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE }
            )
        )

        dbg(
            "load",
            "→ returning ${sortedEpisodes.size} episodes " +
                    "(seasons: ${sortedEpisodes.mapNotNull { it.season }.distinct().sorted()})"
        )

        return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    /**
     * Extracts (season, episodeStart, episodeEnd) from the metadata span and title.
     *
     * Span patterns handled:
     *   "Eps S4-419 to 422 - ..."        → (4, 419, 422)
     *   "Eps S4-419 - ..."                → (4, 419, null)
     *   "Eps S1-(01-30) - ..."            → (1, 1, 30)
     *   "Eps S3-80 - ..."                 → (3, 80, null)
     *   "Eps 79 - ..."                    → (from title, 79, null)
     *   "Eps 51-60 - ..."                 → (from title, 51, 60)
     *
     * Title fallback:
     *   "... Season 4 Episode 419 to 422 ..."  → season = 4, ep = 419
     */
    private data class EpisodeMeta(val season: Int?, val start: Int?, val end: Int?)

    private fun parseSeasonEpisode(metaSpan: String, fullTitle: String): EpisodeMeta {
        var season: Int? = null
        var start: Int? = null
        var end: Int? = null

        // Pattern 1: "Eps S4-419 to 422" or "Eps S1-(01-30)" or "Eps S3-80"
        val withSeason = Regex("""Eps\s+S(\d+)\s*[-–]?\s*\(?\s*(\d+)(?:\s*(?:to|-)\s*(\d+))?""")
            .find(metaSpan)
        if (withSeason != null) {
            season = withSeason.groupValues[1].toIntOrNull()
            start = withSeason.groupValues[2].toIntOrNull()
            end = withSeason.groupValues[3].toIntOrNull()
        } else {
            // Pattern 2: "Eps 79" or "Eps 51-60" or "Eps 01-30"
            val noSeason = Regex("""Eps\s+(\d+)(?:\s*(?:to|-)\s*(\d+))?""")
                .find(metaSpan)
            if (noSeason != null) {
                start = noSeason.groupValues[1].toIntOrNull()
                end = noSeason.groupValues[2].toIntOrNull()
            }
        }

        // Fallback: season from title
        if (season == null) {
            val sFromTitle = Regex("""(?i)Season\s*(\d+)""").find(fullTitle)
            season = sFromTitle?.groupValues?.get(1)?.toIntOrNull()
        }

        // Fallback: episode from title if still missing
        if (start == null) {
            val eFromTitle = Regex("""(?i)Episode\s+(\d+)""").find(fullTitle)
            start = eFromTitle?.groupValues?.get(1)?.toIntOrNull()
        }

        return EpisodeMeta(season, start, end)
    }

    /**
     * Pulls the release date out of the trailing " - Month d, yyyy" portion of the metadata span.
     */
    private fun extractDate(metaSpan: String): String? {
        val dateRegex = Regex(
            """(January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},\s+\d{4}"""
        )
        return dateRegex.find(metaSpan)?.value
    }

    /**
     * Builds a short, readable episode name for the CloudStream list.
     *   S4 · E419-422  → "S4 · E419-422"
     *   S1 · E1-30     → "S1 · E1-30"
     *   S3 · E80       → "S3 · E80"
     */
    private fun buildShortEpisodeName(
        season: Int?,
        start: Int?,
        end: Int?,
        fullTitle: String
    ): String {
        val seasonPrefix = if (season != null) "S$season · " else ""

        return when {
            start != null && end != null && end != start ->
                "${seasonPrefix}E$start-$end"
            start != null ->
                "${seasonPrefix}E$start"
            else ->
                fullTitle.take(60).ifBlank { "Episode" }
        }
    }

    // =========================================================================
    // LOAD LINKS (unchanged from prior working version)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        dbgSection("loadLinks")
        dbg("loadLinks", "data='$data'")

        val document: Document = try {
            app.get(data, headers = defaultHeaders).document
        } catch (e: Exception) {
            dbg("loadLinks", "❌ HTTP FAILED: ${e.message}")
            return false
        }

        var found = false

        suspend fun handleUrl(rawUrl: String, ref: String) {
            val cleanUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else fixUrl(rawUrl)
            if (!cleanUrl.startsWith("http")) return
            if (cleanUrl.contains(" ")) return
            if (blacklistHosts.any { cleanUrl.contains(it, true) }) return

            try {
                if (cleanUrl.contains("ghbrisk.com", true)) {
                    Ghbrisk().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true; return
                }
                if (cleanUrl.contains("dailymotion.com", true) ||
                    cleanUrl.contains("dai.ly", true)) {
                    DailymotionExtractor().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true; return
                }
                if (cleanUrl.contains("galaxydonghua.xyz", true)) {
                    GalaxyDonghua().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true; return
                }
                if (cleanUrl.contains("drive.google.com", true) ||
                    cleanUrl.contains("docs.google.com", true)) {
                    GoogleDriveExtractor().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true; return
                }
                if (loadExtractor(cleanUrl, referer = ref, subtitleCallback, callback)) {
                    found = true; return
                }

                val html = try {
                    app.get(cleanUrl, headers = mapOf("Referer" to ref)).text
                } catch (e: Exception) { "" }
                if (html.isBlank()) return

                val streamRegex = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*)""")
                var foundGeneric = false
                streamRegex.findAll(html).forEach { m ->
                    val fileUrl = m.groupValues[1].replace("\\/", "/")
                    if (blacklistHosts.any { fileUrl.contains(it, true) }) return@forEach
                    if (fileUrl.contains(".m3u8", true)) {
                        try {
                            M3u8Helper.generateM3u8("Generic HLS", fileUrl, cleanUrl)
                                .forEach { callback.invoke(it) }
                            foundGeneric = true
                        } catch (e: Exception) { }
                    } else if (fileUrl.contains(".mp4", true)) {
                        callback.invoke(newExtractorLink(
                            source = "Generic MP4", name = "Generic MP4",
                            url = fileUrl, type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = cleanUrl
                            this.quality = Qualities.Unknown.value
                        })
                        foundGeneric = true
                    }
                }

                if (!foundGeneric) {
                    val nestedIframe = Jsoup.parse(html).selectFirst("iframe")?.let {
                        it.attr("src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-litespeed-src") } }
                    }
                    if (!nestedIframe.isNullOrBlank() && nestedIframe.startsWith("http")) {
                        if (loadExtractor(nestedIframe, cleanUrl, subtitleCallback, callback)) {
                            foundGeneric = true
                        }
                    }
                }
                if (foundGeneric) found = true
            } catch (e: Exception) { }
        }

        fun getIframeSrc(iframe: Element): String =
            iframe.attr("src").ifBlank {
                iframe.attr("data-src").ifBlank { iframe.attr("data-litespeed-src") }
            }

        val mirrorOptions: Elements = document.select(
            "select.mirror option, .mobius option, select#mirror option, select[name=mirror] option"
        )

        coroutineScope {
            mirrorOptions.map { option ->
                async {
                    val value = option.attr("value").trim()
                    if (value.isBlank()) return@async
                    if (value.startsWith("http") || value.startsWith("//")) {
                        handleUrl(value, data); return@async
                    }
                    var decoded: String? = null
                    try {
                        decoded = String(Base64.decode(value, Base64.DEFAULT))
                    } catch (e: Exception) {
                        try {
                            decoded = String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP))
                        } catch (e2: Exception) {
                            try {
                                decoded = String(Base64.decode(value, Base64.NO_PADDING or Base64.NO_WRAP))
                            } catch (e3: Exception) { decoded = null }
                        }
                    }
                    if (decoded.isNullOrBlank()) return@async

                    try {
                        Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                            val src = getIframeSrc(iframe)
                            if (src.isNotBlank()) handleUrl(src, data)
                        }
                    } catch (e: Exception) { }

                    Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).forEach { m ->
                        handleUrl(m.value, data)
                    }
                }
            }.awaitAll()
        }

        if (!found) {
            document.select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) handleUrl(src, data)
            }
        }

        if (!found) {
            document.select("script").forEach { script ->
                val body = script.data()
                Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
                    .findAll(body).forEach { m -> handleUrl(m.value, data) }
                Regex("""['"]([A-Za-z0-9+/=_-]{60,})['"]""").findAll(body).forEach { m ->
                    val blob = m.groupValues[1]
                    var decoded: String? = null
                    try { decoded = String(Base64.decode(blob, Base64.DEFAULT)) }
                    catch (e: Exception) {
                        try { decoded = String(Base64.decode(blob, Base64.URL_SAFE or Base64.NO_WRAP)) }
                        catch (e2: Exception) { decoded = null }
                    }
                    if (decoded.isNullOrBlank()) return@forEach
                    try {
                        Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                            val src = getIframeSrc(iframe)
                            if (src.isNotBlank()) handleUrl(src, data)
                        }
                    } catch (e: Exception) { }
                    Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).forEach { mm ->
                        handleUrl(mm.value, data)
                    }
                }
            }
        }

        dbg("loadLinks", "════ FINAL RETURN found=$found ════")
        return found
    }
}
