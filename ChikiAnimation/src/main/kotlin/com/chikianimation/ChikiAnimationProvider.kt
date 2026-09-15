package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
        "taboola", "outbrain", "mgid", "propellerads", "adsterra"
    )

    // =========================================================================
    // DEBUG HELPER
    // =========================================================================
    private fun dbg(tag: String, msg: String) {
        println("╔══ [ChikiDbg][$tag] ══╗ $msg")
    }

    private fun dbgSection(tag: String) {
        println("╔══════════════════════════════════════════════════════════════")
        println("║ [ChikiDbg] SECTION: $tag")
        println("╚══════════════════════════════════════════════════════════════")
    }

    // =========================================================================
    // MAIN PAGE
    // =========================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        dbgSection("getMainPage")
        val url = buildPageUrl(request.data, page)
        dbg("getMainPage", "request.name='${request.name}' page=$page")
        dbg("getMainPage", "request.data='${request.data}'")
        dbg("getMainPage", "final URL='$url'")

        val document = try {
            val doc = app.get(url, headers = defaultHeaders).document
            dbg("getMainPage", "HTTP OK. title='${doc.title()}'")
            doc
        } catch (e: Exception) {
            dbg("getMainPage", "❌ HTTP FAILED: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val rawSelect = document.select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
        dbg("getMainPage", "selector matched ${rawSelect.size} raw elements")

        val items = rawSelect
            .mapNotNull { el ->
                val r = el.toSearchResult()
                if (r == null) dbg("getMainPage", "  → element dropped (toSearchResult null)")
                r
            }
            .distinctBy { it.url }

        dbg("getMainPage", "→ parsed ${items.size} unique items after filter")
        items.take(3).forEach { dbg("getMainPage", "  • ${it.name} → ${it.url}") }

        val hasNext = detectHasNextPage(document, page)
        dbg("getMainPage", "hasNext=$hasNext")

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun detectHasNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        val relNext = document.selectFirst("link[rel=next], a[rel=next]")
        dbg("hasNext", "rel=next → ${relNext != null}")

        val nextClass = document.selectFirst("a.next.page-numbers, a.nextpostslink, .pagination a.next")
        dbg("hasNext", ".next.page-numbers → ${nextClass != null}")

        val nextPagePattern = Regex("""/page/${currentPage + 1}/""")
        val explicitNext = document.select("a[href]").any { nextPagePattern.containsMatchIn(it.attr("href")) }
        dbg("hasNext", "explicit /page/${currentPage + 1}/ link → $explicitNext")

        val pagBlock = document.selectFirst(".pagination, .wp-pagenavi, .page-numbers")
        dbg("hasNext", "pagination block → ${pagBlock != null}")

        return relNext != null || nextClass != null || explicitNext || pagBlock != null
    }

    private fun buildPageUrl(base: String, page: Int): String {
        val cleanBase = base.trimStart('/')
        if (page <= 1) return "$mainUrl/$cleanBase"

        val queryIndex = cleanBase.indexOf('?')
        val (rawPath, query) = if (queryIndex >= 0) {
            cleanBase.substring(0, queryIndex) to cleanBase.substring(queryIndex)
        } else {
            cleanBase to ""
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
            ?: run {
                dbg("toSearchResult", "❌ no anchor found in element")
                return null
            }

        val rawHref = anchor.attr("href")
        val href = fixUrlNull(rawHref)
        if (href.isNullOrBlank()) {
            dbg("toSearchResult", "❌ bad href raw='$rawHref'")
            return null
        }

        val excludePatterns = listOf(
            "/genres/", "/bookmark", "/privacy", "/contact",
            "/dmca", "/page/", "/anime-history", "/az-list",
            "/donor-wall", "/anime-requests", "/shecdule"
        )
        val excl = excludePatterns.firstOrNull { href.contains(it) }
        if (excl != null) {
            dbg("toSearchResult", "⛔ excluded by pattern '$excl': $href")
            return null
        }

        val title = selectFirst("div.tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("div.tt h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: run {
                dbg("toSearchResult", "❌ no title for $href")
                return null
            }

        val posterUrl = fixUrlNull(
            selectFirst("img.ts-post-image")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
                    .ifEmpty { img.attr("data-lazy-src") }
                    .ifEmpty { img.attr("data-original") }
            }
                ?: selectFirst("div.limit img")?.attr("src")
                ?: selectFirst("img")?.attr("src")
        )

        dbg("toSearchResult", "✓ parsed title='$title' href='$href' poster='$posterUrl'")

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    // =========================================================================
    // SEARCH
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        dbgSection("search")
        if (query.isBlank()) return emptyList()
        val encoded = query.trim().replace(" ", "+")
        dbg("search", "query='$query' encoded='$encoded'")

        val results = coroutineScope {
            (1..3).map { page ->
                async {
                    try {
                        val url = if (page == 1)
                            "$mainUrl/?s=$encoded"
                        else
                            "$mainUrl/page/$page/?s=$encoded"

                        dbg("search", "page $page → $url")

                        val doc = app.get(url, headers = defaultHeaders).document
                        val els = doc.select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                        dbg("search", "  page $page → matched ${els.size} elements")

                        els.mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) {
                        dbg("search", "  page $page ❌ ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        dbg("search", "→ total distinct results: ${results.distinctBy { it.url }.size}")
        return results.distinctBy { it.url }
    }

    // =========================================================================
    // LOAD
    // =========================================================================

    override suspend fun load(url: String): LoadResponse? {
        dbgSection("load")
        dbg("load", "URL='$url'")

        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (e: Exception) {
            dbg("load", "❌ HTTP FAILED: ${e.message}")
            return null
        }

        dbg("load", "HTML length=${document.html().length}")

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: run {
                dbg("load", "❌ no title found")
                return null
            }

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

        dbg("load", "title='$title'")
        dbg("load", "poster='$poster'")
        dbg("load", "genres=${genres.size}")
        dbg("load", "typeText='$typeText'")
        dbg("load", "isMovie=$isMovie")

        if (isMovie) {
            val watchHref = document
                .selectFirst(".eplister li > a[href], .episodelist li > a[href], .eplister tr > td > a[href]")
                ?.attr("href")?.trim()
                ?: url
            dbg("load", "→ MOVIE, watchHref=$watchHref")
            return newMovieLoadResponse(title, url, TvType.Movie, watchHref) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        // Episode scanning
        var epListElements = document.select(
            ".episodelist li, .eplister li, .episodelist tr, .eplister tr"
        )
        dbg("load", "primary episode selector matched ${epListElements.size} elements")

        if (epListElements.isEmpty()) {
            dbg("load", "primary empty, trying episode page link...")
            val epPage = document
                .selectFirst(".episodelist li > a[href], .eplister li > a[href], .episodelist tr > td > a[href]")
                ?.attr("href")?.trim()
            dbg("load", "epPage='$epPage'")
            if (!epPage.isNullOrBlank()) {
                epListElements = try {
                    val sub = app.get(fixUrl(epPage), headers = defaultHeaders).document
                    val subEls = sub.select(".episodelist li, .eplister li, .episodelist tr, .eplister tr")
                    dbg("load", "sub-page matched ${subEls.size} elements")
                    subEls
                } catch (e: Exception) {
                    dbg("load", "sub-page ❌ ${e.message}")
                    org.jsoup.select.Elements()
                }
            }
        }

        dbg("load", "processing ${epListElements.size} episode elements...")

        val episodes = epListElements.mapNotNull { info ->
            val href = info.selectFirst("a[href]")?.attr("href")?.trim()
                ?.takeIf { it.isNotBlank() } ?: run {
                dbg("load.ep", "  → dropped (no href)")
                return@mapNotNull null
            }

            val rawTitle = info.selectFirst(".epl-title")?.text()?.trim()
                ?: info.selectFirst("td.ep-title")?.text()?.trim()
                ?: info.selectFirst("td.title")?.text()?.trim()
                ?: info.select("td").getOrNull(1)?.text()?.trim()
                ?: info.selectFirst("a span")?.text()?.trim()
                ?: info.selectFirst("a")?.text()?.trim()
                ?: ""

            val dateText = info.selectFirst(".epl-date, .date, .time, td.date")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }

            val epNum = Regex("""(?i)(\d+(?:\.\d+)?)""")
                .find(rawTitle)?.groupValues?.get(1)?.toFloatOrNull()

            val cleanName = rawTitle
                .replace(Regex("""(?i)^\s*Episode\s*"""), "")
                .trim()
                .ifBlank { rawTitle.ifBlank { "Episode" } }

            dbg("load.ep", "  ✓ title='$rawTitle' epNum=$epNum name='$cleanName' href=$href")

            newEpisode(fixUrl(href)) {
                this.name = cleanName
                this.posterUrl = poster
                if (epNum != null) this.episode = epNum.toInt()
                if (dateText != null) {
                    this.addDate(dateText, format = "MMMM d, yyyy")
                    this.description = dateText
                }
            }
        }.distinctBy { it.data }

        val sortedEpisodes = if (episodes.all { it.episode != null }) {
            dbg("load", "all episodes have numeric episode → sorting ascending")
            episodes.sortedBy { it.episode }
        } else {
            dbg("load", "some episodes missing number → reversing site order")
            episodes.reversed()
        }

        dbg("load", "→ returning ${sortedEpisodes.size} episodes")

        return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    // =========================================================================
    // LOAD LINKS
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        dbgSection("loadLinks")
        dbg("loadLinks", "data='$data'")

        val document = try {
            app.get(data, headers = defaultHeaders).document
        } catch (e: Exception) {
            dbg("loadLinks", "❌ HTTP FAILED: ${e.message}")
            return false
        }

        dbg("loadLinks", "page HTML length=${document.html().length}")

        var found = false

        suspend fun handleUrl(rawUrl: String, ref: String) {
            dbg("handleUrl", "── ENTER raw='$rawUrl'")
            val cleanUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else fixUrl(rawUrl)
            dbg("handleUrl", "  cleanUrl='$cleanUrl'")

            if (!cleanUrl.startsWith("http")) {
                dbg("handleUrl", "  ⛔ not http, skipping")
                return
            }

            val hit = blacklistHosts.firstOrNull { cleanUrl.contains(it, true) }
            if (hit != null) {
                dbg("handleUrl", "  ⛔ blacklisted by '$hit'")
                return
            }

            try {
                dbg("handleUrl", "  ▸ checking extractor routes...")

                if (cleanUrl.contains("ghbrisk.com", true)) {
                    dbg("handleUrl", "  ✔ ROUTE: Ghbrisk")
                    Ghbrisk().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                if (cleanUrl.contains("dailymotion.com", true) ||
                    cleanUrl.contains("dai.ly", true)
                ) {
                    dbg("handleUrl", "  ✔ ROUTE: DailymotionExtractor")
                    DailymotionExtractor().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                if (cleanUrl.contains("galaxydonghua.xyz", true)) {
                    dbg("handleUrl", "  ✔ ROUTE: GalaxyDonghua")
                    GalaxyDonghua().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                if (cleanUrl.contains("drive.google.com", true) ||
                    cleanUrl.contains("docs.google.com", true)
                ) {
                    dbg("handleUrl", "  ✔ ROUTE: GoogleDriveExtractor")
                    GoogleDriveExtractor().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                dbg("handleUrl", "  ▸ trying loadExtractor(cleanUrl)...")
                if (loadExtractor(cleanUrl, referer = ref, subtitleCallback, callback)) {
                    dbg("handleUrl", "  ✔ loadExtractor returned TRUE")
                    found = true
                    return
                }
                dbg("handleUrl", "  ✗ loadExtractor returned FALSE")

                dbg("handleUrl", "  ▸ attempting generic fetch...")
                val html = try {
                    app.get(cleanUrl, headers = mapOf("Referer" to ref)).text
                } catch (e: Exception) {
                    dbg("handleUrl", "  ❌ generic fetch failed: ${e.message}")
                    ""
                }

                if (html.isBlank()) {
                    dbg("handleUrl", "  ✗ html blank, giving up on this URL")
                    return
                }

                dbg("handleUrl", "  html length=${html.length}")

                val streamRegex = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*)""")
                var foundGeneric = false

                streamRegex.findAll(html).forEach { m ->
                    val fileUrl = m.groupValues[1].replace("\\/", "/")
                    if (blacklistHosts.any { fileUrl.contains(it, true) }) {
                        dbg("handleUrl", "    ⛔ stream blacklisted: $fileUrl")
                        return@forEach
                    }

                    dbg("handleUrl", "    ✔ stream found: $fileUrl")

                    if (fileUrl.contains(".m3u8", ignoreCase = true)) {
                        try {
                            M3u8Helper.generateM3u8("Generic HLS", fileUrl, cleanUrl)
                                .forEach { callback.invoke(it) }
                            foundGeneric = true
                        } catch (e: Exception) {
                            dbg("handleUrl", "    ❌ m3u8 parse failed: ${e.message}")
                        }
                    } else if (fileUrl.contains(".mp4", ignoreCase = true)) {
                        callback.invoke(
                            newExtractorLink(
                                source = "Generic MP4",
                                name = "Generic MP4",
                                url = fileUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = cleanUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundGeneric = true
                    }
                }

                if (!foundGeneric) {
                    dbg("handleUrl", "  ▸ no streams, checking nested iframe...")
                    val iframeNode = Jsoup.parse(html).selectFirst("iframe")
                    val nestedIframe = iframeNode?.let {
                        it.attr("src").ifBlank {
                            it.attr("data-src").ifBlank { it.attr("data-litespeed-src") }
                        }
                    }
                    dbg("handleUrl", "  nested iframe='$nestedIframe'")
                    if (!nestedIframe.isNullOrBlank() && nestedIframe.startsWith("http")) {
                        if (loadExtractor(nestedIframe, cleanUrl, subtitleCallback, callback)) {
                            foundGeneric = true
                            dbg("handleUrl", "  ✔ nested iframe extractor TRUE")
                        }
                    }
                }

                if (foundGeneric) {
                    dbg("handleUrl", "  ✔ foundGeneric=TRUE for this URL")
                    found = true
                } else {
                    dbg("handleUrl", "  ✗ nothing found for this URL")
                }
            } catch (e: Exception) {
                dbg("handleUrl", "  ❌ EXCEPTION: ${e.message}")
                e.printStackTrace()
            }
        }

        fun getIframeSrc(iframe: Element): String {
            return iframe.attr("src").ifBlank {
                iframe.attr("data-src").ifBlank {
                    iframe.attr("data-litespeed-src")
                }
            }
        }

        // ---- Mirror options ----
        val mirrorOptions = document.select(
            "select.mirror option, .mobius option, select#mirror option, select[name=mirror] option"
        )
        dbg("loadLinks", "mirror options found: ${mirrorOptions.size}")
        mirrorOptions.forEachIndexed { i, opt ->
            val valTrunc = opt.attr("value").take(60)
            dbg("loadLinks", "  mirror[$i] label='${opt.text().trim()}' value='$valTrunc...'")
        }

        coroutineScope {
            mirrorOptions.map { option ->
                async {
                    val value = option.attr("value").trim()
                    if (value.isBlank()) {
                        dbg("mirror", "skip: blank value")
                        return@async
                    }

                    if (value.startsWith("http") || value.startsWith("//")) {
                        dbg("mirror", "direct URL, routing: $value")
                        handleUrl(value, data)
                        return@async
                    }

                    val decoded: String? = try {
                        String(Base64.decode(value, Base64.DEFAULT))
                    } catch (e: Exception) {
                        try {
                            String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP))
                        } catch (e2: Exception) {
                            try {
                                String(Base64.decode(value, Base64.NO_PADDING or Base64.NO_WRAP))
                            } catch (e3: Exception) { null }
                        }
                    }

                    if (decoded.isNullOrBlank()) {
                        dbg("mirror", "❌ base64 decode failed for value len=${value.length}")
                        return@async
                    }

                    dbg("mirror", "✓ decoded (${decoded.length} chars): ${decoded.take(200)}")

                    try {
                        val iframes = Jsoup.parse(decoded).select("iframe")
                        dbg("mirror", "  iframes in decoded HTML: ${iframes.size}")
                        iframes.forEach { iframe ->
                            val src = getIframeSrc(iframe)
                            dbg("mirror", "  iframe src='$src'")
                            if (src.isNotBlank()) handleUrl(src, data)
                        }
                    } catch (e: Exception) {
                        dbg("mirror", "  ❌ parse HTML failed: ${e.message}")
                    }

                    val urls = Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).toList()
                    dbg("mirror", "  raw URLs in decoded: ${urls.size}")
                    urls.forEach { m ->
                        dbg("mirror", "    raw url='${m.value}'")
                        handleUrl(m.value, data)
                    }
                }
            }.awaitAll()
        }

        // ---- Top-level iframes ----
        if (!found) {
            val iframes = document.select("iframe")
            dbg("loadLinks", "fallback: top-level iframes = ${iframes.size}")
            iframes.forEach { iframe ->
                val src = getIframeSrc(iframe)
                dbg("loadLinks", "  top iframe src='$src'")
                if (src.isNotBlank()) handleUrl(src, data)
            }
        }

        // ---- Inline scripts ----
        if (!found) {
            val scripts = document.select("script")
            dbg("loadLinks", "fallback: scanning ${scripts.size} scripts")
            scripts.forEach { script ->
                val body = script.data()

                Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
                    .findAll(body).forEach { m ->
                        dbg("script", "  ✔ stream in script: ${m.value.take(100)}")
                        handleUrl(m.value, data)
                    }

                Regex("""['"]([A-Za-z0-9+/=_-]{60,})['"]""").findAll(body).forEach { m ->
                    val blob = m.groupValues[1]
                    val decoded: String? = try {
                        String(Base64.decode(blob, Base64.DEFAULT))
                    } catch (e: Exception) {
                        try {
                            String(Base64.decode(blob, Base64.URL_SAFE or Base64.NO_WRAP))
                        } catch (e2: Exception) { null }
                    }
                    if (decoded.isNullOrBlank()) return@forEach

                    dbg("script", "  blob decoded: ${decoded.take(120)}")

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
