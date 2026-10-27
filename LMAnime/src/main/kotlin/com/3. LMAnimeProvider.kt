package com.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LMANIME : MainAPI() {

    override var mainUrl  = "https://lmanime.com"
    override var name     = "LMANIME"
    override var lang     = "en"
    override val hasMainPage      = true
    override val hasQuickSearch   = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "latest"                              to "Latest Release",
        "anime/?status=&type=&order=update"   to "Recently Updated",
        "anime/?status=&type=&order=popular"  to "Popular",
        "ongoing/"                            to "Ongoing",
        "upcoming/"                           to "Upcoming",
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
    /* CARD PARSER  (homepage / search results)                           */
    /* ------------------------------------------------------------------ */
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[itemprop=url]") ?: return null
        val tt = selectFirst("div.tt")

        // .tt ownText = clean series name; nested h2 = full title with "Episode N"
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

        val epBadge = selectFirst("span.epx")?.text()?.trim()
        val fullTitle = if (epBadge != null && !seriesName.contains(epBadge, ignoreCase = true))
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
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    /* ------------------------------------------------------------------ */
    /* LOAD (episode page → loadable entry)                              */
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

        // Treat each episode page as a standalone "movie" entry.
        return newMovieLoadResponse(title, url, TvType.Anime, url) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    /* ------------------------------------------------------------------ */
    /* LOAD LINKS                                                         */
    /* ------------------------------------------------------------------ */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        var found = false

        /* ---------- 1. iframes (Dailymotion, Streamtape, etc.) ---------- */
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isBlank()) return@forEach

            val fixed = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/")  -> "$mainUrl$src"
                else                  -> src
            }

            val skip = listOf(
                "chatango", "google", "doubleclick", "adsystem",
                "facebook.com/plugins", "googletag",
            ).any { fixed.contains(it, ignoreCase = true) }
            if (skip) return@forEach

            runCatching {
                if (loadExtractor(fixed, data, subtitleCallback, callback)) found = true
            }
        }

        /* ---------- 2. <video> / <source> fallback ---------- */
        document.select("video, video source").forEach { el ->
            val src = el.attr("src")
            if (src.isBlank()) return@forEach

            val isHls = src.contains(".m3u8", ignoreCase = true)
            if (!isHls && !src.contains(".mp4", ignoreCase = true)) return@forEach

            callback(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = fixUrl(src),
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) { this.referer = data }
            )
            found = true
        }

        /* ---------- 3. last resort: regex over full HTML ---------- */
        if (!found) {
            val html = document.html()
            Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""")
                .findAll(html)
                .forEach { m ->
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
}
