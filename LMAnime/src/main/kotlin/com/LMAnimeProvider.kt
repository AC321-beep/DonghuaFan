package com.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LMANIME : MainAPI() {

    override var mainUrl  = "https://lmanime.com"
    override var name     = "LMANIME"
    override var lang     = "en"
    override val hasMainPage       = true
    override val hasQuickSearch    = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "latest"                             to "Latest Release",
        "anime/?status=&type=&order=update"  to "Recently Updated",
        "anime/?status=&type=&order=popular" to "Popular",
        "ongoing/"                           to "Ongoing",
        "upcoming/"                          to "Upcoming",
    )

    /* ------------------------------------------------------------------ */
    /* MAIN PAGE                                                          */
    /* ------------------------------------------------------------------ */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data == "latest" ->
                if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"

            request.data.contains("?") ->
                "$mainUrl/${request.data}" + if (page > 1) "&page=$page" else ""

            else -> {
                val base = request.data.trimEnd('/')
                if (page > 1) "$mainUrl/$base/page/$page/" else "$mainUrl/$base/"
            }
        }
        val document = app.get(url).document
        val items = document.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    /* ------------------------------------------------------------------ */
    /* CARD PARSER                                                        */
    /* ------------------------------------------------------------------ */
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[itemprop=url]") ?: return null
        val tt = selectFirst("div.tt")

        val seriesName = tt?.let { el ->
            val own = el.ownText().trim()
            if (own.isNotEmpty()) own
            else el.selectFirst("h2")?.text()
                ?.replace(Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
        } ?: anchor.attr("title")
            .replace(Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
            .trim()

        if (seriesName.isEmpty()) return null

        // Only append the badge if it actually looks like an episode marker
        val epBadge = selectFirst("span.epx")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() && it.matches(Regex("""(?i)(eps?\.?\s*\d+|\d+)""")) }
        val fullTitle = if (epBadge != null && !seriesName.contains(epBadge, true))
            "$seriesName $epBadge" else seriesName

        val href = fixUrl(anchor.attr("href"))
        val poster = selectFirst("img")?.let {
            fixUrlNull(it.attr("data-src").ifEmpty { it.attr("src") })
        }

        return newAnimeSearchResponse(fullTitle, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    /* ------------------------------------------------------------------ */
    /* SEARCH                                                             */
    /* ------------------------------------------------------------------ */
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        return doc.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    /* ------------------------------------------------------------------ */
    /* LOAD (episode page → loadable entry)                               */
    /* ------------------------------------------------------------------ */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("div.entry-content p")?.text()
            ?: document.selectFirst("div.desc p")?.text()

        // Try to pull year from the "Released: … YYYY" line
        val year = document.select(".single-info .spe span")
            .firstOrNull { it.text().contains("Released", true) }?.text()
            ?.let { text -> Regex("""\b((?:19|20)\d{2})\b""").find(text)?.value?.toIntOrNull() }

        return newMovieLoadResponse(title, url, TvType.Anime, url) {
            this.posterUrl = poster
            this.plot      = plot
            if (year != null) this.year = year
        }
    }

    /* ------------------------------------------------------------------ */
    /* LOAD LINKS (multi-server + fallbacks)                              */
    /* ------------------------------------------------------------------ */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        val iframeUrls = mutableListOf<String>()
        val videoUrls  = mutableListOf<String>()

        /* -------- collector for any document -------- */
        fun collectFrom(doc: org.jsoup.nodes.Document) {
            // iframes
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.trim()
                if (src.isBlank()) return@forEach
                val fixed = when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/")  -> "$mainUrl$src"
                    else                 -> src
                }
                val skip = listOf(
                    "chatango", "google", "doubleclick", "adsystem",
                    "facebook.com/plugins", "googletag", "disqus",
                ).any { fixed.contains(it, ignoreCase = true) }
                if (!skip) iframeUrls.add(fixed)
            }

            // <video> / <source> fallback
            doc.select("video, video source").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isBlank()) return@forEach
                val fixed = fixUrl(src)
                if (fixed.contains(".m3u8", true) || fixed.contains(".mp4", true)) {
                    videoUrls.add(fixed)
                }
            }

            // Convert known download links → streamable embeds
            doc.select("div.soraddlx a[href]").forEach { a ->
                val href = a.attr("href").trim()
                when {
                    href.contains("morencius.com/file/") -> {
                        val id = href.substringAfter("/file/")
                                     .substringBefore("/").substringBefore("?")
                        if (id.isNotBlank()) iframeUrls.add("https://morencius.com/embed/$id")
                    }
                    href.contains("mp4upload.com/") && !href.contains("/embed-") -> {
                        val id = href.substringAfter("mp4upload.com/")
                                     .substringBefore("/").substringBefore(".")
                        if (id.isNotBlank())
                            iframeUrls.add("https://www.mp4upload.com/embed-$id.html")
                    }
                }
            }
        }

        /* -------- 1. main episode page -------- */
        collectFrom(document)

        /* -------- 2. every mirror server page (/v/1/, /v/2/, …) -------- */
        val serverUrls = document.select("select.mirror option")
            .mapNotNull { it.attr("value").trim().takeIf { u -> u.startsWith("http") } }
            .distinct()

        for (serverUrl in serverUrls) {
            runCatching {
                val serverDoc = app.get(serverUrl, referer = data).document
                collectFrom(serverDoc)
            }
        }

        /* -------- 3. feed every iframe to the extractor registry -------- */
        var found = false

        for (iframeUrl in iframeUrls.distinct()) {
            val normalized = normalizeDailymotion(iframeUrl)
            runCatching {
                if (loadExtractor(normalized, data, subtitleCallback, callback)) found = true
            }
        }

        /* -------- 4. direct video URLs -------- */
        for (videoUrl in videoUrls.distinct()) {
            val isHls = videoUrl.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = videoUrl,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) { this.referer = data }
            )
            found = true
        }

        /* -------- 5. last-resort regex on the main page -------- */
        if (!found) {
            val html = document.html()
            Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""")
                .findAll(html).forEach { m ->
                    val isHls = m.value.contains(".m3u8", ignoreCase = true)
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name   = this.name,
                            url    = m.value.replace("\\/", "/"),
                            type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) { this.referer = data }
                    )
                    found = true
                }
        }

        return found
    }

    /* dai.ly → canonical dailymotion URL so the extractor can pick it up */
    private fun normalizeDailymotion(url: String): String {
        return if (url.contains("dai.ly/")) {
            val id = url.substringAfter("dai.ly/").substringBefore("/").substringBefore("?")
            "https://www.dailymotion.com/video/$id"
        } else url
    }
}
