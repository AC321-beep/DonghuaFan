package com.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LMANIME : MainAPI() {

    override var mainUrl  = "https://lmanime.com"
    override var name     = "LMANIME"
    override var lang     = "zh"
    override val hasMainPage        = true
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // 👇 NEW: "Movies" row (filter by type=movie) added between Popular and Ongoing
    override val mainPage = mainPageOf(
        "latest"                                 to "Latest Release",
        "anime/?status=&type=&order=popular"     to "Popular",
        "anime/?status=&type=movie&order=update" to "Movies",   // ← NEW
        "ongoing/"                               to "Ongoing",
        "upcoming/"                              to "Upcoming",
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
    /* CARD PARSER — now reads the typez badge                            */
    /* ------------------------------------------------------------------ */
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[itemprop=url]") ?: return null
        val tt     = selectFirst("div.tt")

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
            ?.takeIf { it.isNotEmpty() && it.matches(Regex("""(?i)(eps?\.?\s*\d+|\d+)""")) }
        val fullTitle = if (epBadge != null && !seriesName.contains(epBadge, true))
            "$seriesName $epBadge" else seriesName

        val href   = fixUrl(anchor.attr("href"))
        val poster = selectFirst("img")?.let {
            fixUrlNull(it.attr("data-src").ifEmpty { it.attr("src") })
        }

        // 👇 NEW: read type badge (TV / ONA / Movie / OVA / Special / …)
        val typeText = selectFirst("div.typez")?.text()?.trim()
        val tvType   = when {
            typeText.equals("Movie", true) -> TvType.AnimeMovie
            else                           -> TvType.Anime
        }

        return newAnimeSearchResponse(fullTitle, href, tvType) {
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
    /* LOAD — decides Movie vs Series from the "Type:" field              */
    /* ------------------------------------------------------------------ */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // 👇 NEW: read Type from the single-info block (series *or* movie page)
        val pageType = document.select(".single-info .spe span")
            .firstOrNull { it.text().contains("Type:", true) }
            ?.text()?.substringAfter(":")?.trim()

        if (pageType.equals("Movie", true)) {
            return loadAsMovie(url, document, TvType.AnimeMovie)
        }

        // Episode of a series?
        val isEpisodePage = document.selectFirst("#singlepisode") != null ||
                            url.contains("-episode-", true)

        if (isEpisodePage) {
            val seriesUrl = document
                .selectFirst("#singlepisode .headlist .det h2 a")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?.takeIf { it.isNotBlank() && !it.contains("-episode-", true) }

            if (seriesUrl != null) {
                return loadSeries(seriesUrl, url, document)
            }
        }

        // Otherwise treat as a movie/anime movie fallback
        return loadAsMovie(url, document, TvType.Anime)
    }

    /* ------------------------------------------------------------------ */
    /* SERIES LOADER (ONA/TV)                                             */
    /* ------------------------------------------------------------------ */
    private suspend fun loadSeries(
        seriesUrl: String,
        episodeUrl: String,
        episodeDoc: Document,
    ): LoadResponse {
        val seriesDoc = runCatching { app.get(seriesUrl).document }.getOrNull() ?: episodeDoc

        var links = episodeDoc.select("#singlepisode .episodelist ul li a")
        if (links.size < 5) {
            links = seriesDoc.select("div.bxcl ul li a, #episodelist ul li a, a[href*='-episode-']")
        }

        val episodes = links.mapNotNull { a ->
            val href = fixUrl(a.attr("href")).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!href.contains("-episode-", true)) return@mapNotNull null

            val epTitle = a.selectFirst("h3")?.text()?.trim()
                ?: a.selectFirst(".playinfo h3")?.text()?.trim()
                ?: a.attr("title").trim()

            val epNum = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(href) {
                this.name    = epTitle.ifBlank { "Episode ${epNum ?: "?"}" }
                this.episode = epNum
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) return loadAsMovie(episodeUrl, episodeDoc, TvType.Anime)

        val title = seriesDoc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: seriesDoc.selectFirst("h1")?.text()?.trim()
            ?: episodeDoc.selectFirst("#singlepisode .headlist .det h2")?.text()?.trim()
            ?: "Unknown"

        val poster = fixUrlNull(
            episodeDoc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: seriesDoc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = seriesDoc.selectFirst("div.desc.mindes")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: seriesDoc.selectFirst("div.desc p")?.text()?.trim()
            ?: episodeDoc.selectFirst("meta[property=og:description]")?.attr("content")

        val year = (seriesDoc.select(".single-info .spe span") + episodeDoc.select(".single-info .spe span"))
            .firstOrNull { it.text().contains("Released", true) }?.text()
            ?.let { t -> Regex("""\b((?:19|20)\d{2})\b""").find(t)?.value?.toIntOrNull() }

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            if (year != null) this.year = year
        }
    }

    /* ------------------------------------------------------------------ */
    /* MOVIE FALLBACK — takes the TvType so movies get AnimeMovie         */
    /* ------------------------------------------------------------------ */
    private fun loadAsMovie(
        url: String,
        document: Document,
        tvType: TvType = TvType.Anime,      // 👈 parameter so movie pages use AnimeMovie
    ): LoadResponse {
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

        val year = document.select(".single-info .spe span")
            .firstOrNull { it.text().contains("Released", true) }?.text()
            ?.let { t -> Regex("""\b((?:19|20)\d{2})\b""").find(t)?.value?.toIntOrNull() }

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = poster
            this.plot      = plot
            if (year != null) this.year = year
        }
    }

    /* ------------------------------------------------------------------ */
    /* LOAD LINKS — unchanged                                            */
    /* ------------------------------------------------------------------ */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document   = app.get(data).document
        val iframeUrls = linkedSetOf<String>()
        val videoUrls  = linkedSetOf<String>()
        var found      = false

        fun collectFrom(doc: Document) {
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
                    "facebook.com/plugins", "googletag", "disqus", "onesignal",
                ).any { fixed.contains(it, true) }
                if (!skip) iframeUrls.add(fixed)
            }

            doc.select("video, video source").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isBlank()) return@forEach
                val fixed = fixUrl(src)
                if (fixed.contains(".m3u8", true) || fixed.contains(".mp4", true)) {
                    videoUrls.add(fixed)
                }
            }

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

        collectFrom(document)

        document.select("select.mirror option")
            .mapNotNull { it.attr("value").trim().takeIf { u -> u.startsWith("http") } }
            .distinct()
            .forEach { serverUrl ->
                runCatching {
                    collectFrom(app.get(serverUrl, referer = data).document)
                }
            }

        for (iframeUrl in iframeUrls) {
            val normalized = normalizeDailymotion(iframeUrl)
            runCatching {
                if (loadExtractor(normalized, data, subtitleCallback, callback)) found = true
            }
        }

        for (videoUrl in videoUrls) {
            val isHls = videoUrl.contains(".m3u8", true)
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

        if (!found) {
            Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""")
                .findAll(document.html())
                .forEach { m ->
                    val isHls = m.value.contains(".m3u8", true)
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

    private fun normalizeDailymotion(url: String): String =
        if (url.contains("dai.ly/")) {
            val id = url.substringAfter("dai.ly/").substringBefore("/").substringBefore("?")
            "https://www.dailymotion.com/video/$id"
        } else url
}
