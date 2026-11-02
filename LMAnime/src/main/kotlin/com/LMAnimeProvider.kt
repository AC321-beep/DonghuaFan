package com.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class LMANIME : MainAPI() {

    override var mainUrl  = "https://lmanime.com"
    override var name     = "LMANIME"
    override var lang     = "zh"
    override val hasMainPage        = true
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    /* ─── Date regexes ─── */
    private val dateRegex = Regex(
        """([a-zA-Z]{3,}\s+\d{1,2},\s+\d{4}|\d{1,2}\s+[a-zA-Z]{3,}\s+\d{4}|\d{4}-\d{2}-\d{2})"""
    )
    private val relativeDateRegex = Regex(
        """(?i)\d+\s+(mins?|minutes?|hours?|days?|weeks?|months?|years?)\s+ago"""
    )
    private val fmtMonthDayYear = Regex("""^[A-Za-z]+\s+\d{1,2},\s+\d{4}$""")
    private val fmtDayMonthYear = Regex("""^\d{1,2}\s+[A-Za-z]+\s+\d{4}$""")
    private val fmtIsoDate      = Regex("""^\d{4}-\d{2}-\d{2}$""")

    override val mainPage = mainPageOf(
        "latest"                             to "Latest Release",
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

        val typeText = selectFirst("div.typez")?.text()?.trim()
        val tvType   = if (typeText.equals("Movie", true)) TvType.AnimeMovie else TvType.Anime

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
    /* LOAD                                                               */
    /* ------------------------------------------------------------------ */
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val pageType = document.select(".single-info .spe span")
            .firstOrNull { it.text().contains("Type:", true) }
            ?.text()?.substringAfter(":")?.trim()

        if (pageType.equals("Movie", true)) {
            return loadAsMovie(url, document, TvType.AnimeMovie)
        }

        val isEpisodePage = document.selectFirst("#singlepisode") != null ||
                            url.contains("-episode-", true)

        if (isEpisodePage) {
            val seriesUrl = document
                .selectFirst("#singlepisode .headlist .det h2 a")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?.takeIf { it.isNotBlank() && !it.contains("-episode-", true) }

            if (seriesUrl != null) return loadSeries(seriesUrl, url, document)
        }

        return loadAsMovie(url, document, TvType.Anime)
    }

    /* ------------------------------------------------------------------ */
    /* SERIES LOADER                                                      */
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

            val cssDate = a.selectFirst(
                ".playinfo span, .epl-date, .epdate, .date, .released, .published, .time"
            )?.text()?.trim()

            val fullText = a.text()

            val extractedDate: String? =
                cssDate?.takeIf { it.isNotBlank() }?.let {
                    dateRegex.find(it)?.value?.trim()
                        ?: relativeDateRegex.find(it)?.value?.trim()
                }
                ?: dateRegex.find(fullText)?.value?.trim()
                ?: relativeDateRegex.find(fullText)?.value?.trim()

            newEpisode(href) {
                this.name    = epTitle.ifBlank { "Episode ${epNum ?: "?"}" }
                this.episode = epNum

                if (!extractedDate.isNullOrBlank()) {
                    val addFormat = when {
                        fmtMonthDayYear.matches(extractedDate) -> "MMMM d, yyyy"
                        fmtDayMonthYear.matches(extractedDate) -> "d MMMM yyyy"
                        fmtIsoDate.matches(extractedDate)      -> "yyyy-MM-dd"
                        else                                    -> null
                    }
                    if (addFormat != null) {
                        this.addDate(extractedDate, format = addFormat)
                    }
                    this.description = extractedDate
                }
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
    /* MOVIE FALLBACK                                                     */
    /* ------------------------------------------------------------------ */
    private suspend fun loadAsMovie(
        url: String,
        document: Document,
        tvType: TvType = TvType.Anime,
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
    /* LOAD LINKS                                                         */
    /*                                                                    */
    /*  LMANIME exposes per-language download blocks (div.soraddlx) and   */
    /*  per-language mirrors (select.mirror option). Both carry the       */
    /*  language label in the HTML, so we propagate it as a [Tag] on      */
    /*  every emitted ExtractorLink.                                       */
    /* ------------------------------------------------------------------ */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        var found = false

        /* ── 1. Language detection ─────────────────────────────────── */
        fun detectLang(vararg sources: String?): String? {
            val text = sources.filterNotNull().joinToString(" ").lowercase()
            return when {
                Regex("""\b(multi(sub)?|multilingual)\b""").containsMatchIn(text) -> "multi"
                Regex("""\b(indonesian|indo|bahasa|ind)\b""").containsMatchIn(text) -> "ind"
                Regex("""\b(portugu[eê]s|portuguese|por)\b""").containsMatchIn(text) -> "por"
                Regex("""\b(espa[nñ]ol|spanish|spa)\b""").containsMatchIn(text) -> "spa"
                Regex("""\b(ไทย|thai|tha)\b""").containsMatchIn(text) -> "tha"
                Regex("""\b(t[uü]rk[cç]e|turkish|tur)\b""").containsMatchIn(text) -> "tur"
                Regex("""(العَرَبِيَّة|عربي|arabic|ara)""").containsMatchIn(text) -> "ara"
                Regex("""\b(english|eng)\b""").containsMatchIn(text) -> "eng"
                else -> null
            }
        }

        fun langTag(lang: String?): String? = when (lang) {
            "multi" -> "[Multi]"
            "ind"   -> "[Ind]"
            "por"   -> "[Por]"
            "spa"   -> "[Spa]"
            "tha"   -> "[Thai]"
            "tur"   -> "[Tur]"
            "ara"   -> "[Ara]"
            "eng"   -> "[Eng]"
            else    -> null
        }

        /* ── 2. URL normalisation ──────────────────────────────────── */
        fun normalize(raw: String): String {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("//")   -> "https:$trimmed"
                trimmed.startsWith("/")    -> "$mainUrl$trimmed"
                trimmed.startsWith("http") -> trimmed
                trimmed.isBlank()          -> ""
                else                       -> "https://$trimmed"
            }
        }

        val skipPatterns = listOf(
            "chatango", "google", "doubleclick", "adsystem",
            "facebook.com/plugins", "googletag", "disqus", "onesignal",
            "cloudfront.net", "21wiz.com",
        )

        /* ── 3. Collectors ─────────────────────────────────────────── */
        val iframeEntries = mutableListOf<Pair<String, String?>>()
        val videoUrls     = linkedSetOf<String>()
        val seenKeys      = mutableSetOf<String>()

        fun pushIframe(url: String, lang: String?) {
            if (url.isBlank()) return
            if (skipPatterns.any { url.contains(it, true) }) return
            if (seenKeys.add(url.substringBefore("?"))) iframeEntries.add(url to lang)
        }

        fun collectFrom(doc: Document, parentLang: String? = null) {
            /* Bare iframes */
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                val lang = detectLang(iframe.attr("title"), iframe.attr("data-lang")) ?: parentLang
                pushIframe(normalize(src), lang)
            }

            /* Direct <video> URLs (rare on LMANIME, but appears on mirrors) */
            doc.select("video, video source").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isBlank()) return@forEach
                val fixed = fixUrl(src)
                if (fixed.contains(".m3u8", true) || fixed.contains(".mp4", true)) {
                    videoUrls.add(fixed)
                }
            }

            /* Download blocks — one .soraddlx per language.
             *
             *   <div class="soraddlx soradlg">
             *     <div class="sorattlx"><h3>English</h3></div>
             *     <div class="soraurlx">
             *       <strong>DL</strong>
             *       <a href="https://morencius.com/file/…">vidhide</a>
             *       <a href="https://www.mp4upload.com/…">mp4upload</a>
             *     </div>
             *   </div>
             */
            doc.select("div.soraddlx").forEach { block ->
                val heading = block.selectFirst(".sorattlx h3, .sorattlx")?.text()?.trim()
                val lang = detectLang(heading) ?: parentLang

                block.select(".soraurlx a[href]").forEach { a ->
                    val href = a.attr("href").trim()
                    when {
                        href.contains("morencius.com/file/") -> {
                            val id = href.substringAfter("/file/")
                                .substringBefore("/").substringBefore("?")
                            if (id.isNotBlank()) pushIframe("https://morencius.com/embed/$id", lang)
                        }
                        href.contains("mp4upload.com/") && !href.contains("/embed-") -> {
                            val id = href.substringAfter("mp4upload.com/")
                                .substringBefore("/").substringBefore(".")
                            if (id.isNotBlank())
                                pushIframe("https://www.mp4upload.com/embed-$id.html", lang)
                        }
                        href.contains("ok.ru/") || href.contains("odnoklassniki.ru/") -> {
                            pushIframe(href, lang)
                        }
                        href.contains("wishfast") || href.contains("filemoon") ||
                        href.contains("waaw.") || href.contains("d.tube") ||
                        href.contains("rumble.com") -> {
                            pushIframe(href, lang)
                        }
                    }
                }
            }
        }

        collectFrom(document)

        /* ── 4. Mirror pages (language from option label) ──────────── */
        document.select("select.mirror option").forEach { option ->
            val serverUrl = option.attr("value").trim()
            if (!serverUrl.startsWith("http")) return@forEach
            val optionLang = detectLang(option.text(), option.attr("data-lang"), option.attr("title"))
            runCatching {
                collectFrom(app.get(serverUrl, referer = data).document, optionLang)
            }
        }

        /* ── 5. Locale-aware priority ──────────────────────────────── */
        val userLang = Locale.getDefault().language
        fun priority(lang: String?): Int = when {
            lang == null     -> 0
            lang == userLang -> 3
            lang == "eng"    -> 2
            lang == "multi"  -> 2
            else             -> 1
        }

        val sortedIframes = iframeEntries.sortedByDescending { priority(it.second) }

        /* ── 6. Emit via loadExtractor with [Lang] tag ─────────────── */
        for ((url, lang) in sortedIframes) {
            val normalized = normalizeDailymotion(url)
            val tag = langTag(lang)
            val wrapper: (ExtractorLink) -> Unit = { link ->
                val newName   = if (tag != null && !link.name.contains(tag))   "${link.name} $tag"   else link.name
                val newSource = if (tag != null && !link.source.contains(tag)) "${link.source} $tag" else link.source
                callback(
                    ExtractorLink(
                        source        = newSource,
                        name          = newName,
                        url           = link.url,
                        referer       = link.referer,
                        quality       = link.quality,
                        type          = link.type,
                        headers       = link.headers,
                        extractorData = link.extractorData,
                    )
                )
                found = true
            }
            runCatching {
                loadExtractor(normalized, data, subtitleCallback, wrapper)
            }
        }

        /* ── 7. Direct .m3u8 / .mp4 ────────────────────────────────── */
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

        /* ── 8. Regex fallback ─────────────────────────────────────── */
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

    /* ------------------------------------------------------------------ */
    /* Dailymotion URL normalisation                                      */
    /*  Rewriting to the canonical form lets the built-in extractor handle */
    /*  it without any custom extraction code.                             */
    /* ------------------------------------------------------------------ */
    private fun normalizeDailymotion(url: String): String {
        if (url.contains("dai.ly/")) {
            val id = url.substringAfter("dai.ly/").substringBefore("/").substringBefore("?")
            if (id.isNotBlank()) return "https://www.dailymotion.com/video/$id"
        }
        if (url.contains("geo.dailymotion.com")) {
            val id = Regex("""[?&]video=([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            if (!id.isNullOrBlank()) return "https://www.dailymotion.com/video/$id"
        }
        if (url.contains("dailymotion.com/embed/video/")) {
            val id = url.substringAfter("/embed/video/").substringBefore("/").substringBefore("?")
            if (id.isNotBlank()) return "https://www.dailymotion.com/video/$id"
        }
        return url
    }
}
