package com.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LMANIME : MainAPI() {

    override var mainUrl  = "https://lmanime.com"
    override var name     = "LMANIME"
    override var lang     = "zh"
    override val hasMainPage        = true
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    /* ─── Date regexes (precompiled) ─── */
    private val dateRegex = Regex(
        """([a-zA-Z]{3,}\s+\d{1,2},\s+\d{4}|\d{1,2}\s+[a-zA-Z]{3,}\s+\d{4}|\d{4}-\d{2}-\d{2})"""
    )
    private val relativeDateRegex = Regex(
        """(?i)\d+\s+(mins?|minutes?|hours?|days?|weeks?|months?|years?)\s+ago"""
    )
    private val fmtMonthDayYear = Regex("""^[A-Za-z]+\s+\d{1,2},\s+\d{4}$""")
    private val fmtDayMonthYear = Regex("""^\d{1,2}\s+[A-Za-z]+\s+\d{4}$""")
    private val fmtIsoDate      = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private val epNumRegex   = Regex("""(?:\bepisode|\beps?\.?)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val epBracketNum = Regex("""[\[\(](\d{1,4})[\]\)]""")
    private val yearRegex    = Regex("""\b((?:19|20)\d{2})\b""")
    private val epStripRegex = Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE)
    private val epBadgeRegex = Regex("""(?i)(eps?\.?\s*\d+|\d+)""")

    override val mainPage = mainPageOf(
        "latest"                             to "Latest Release",
        "anime/?status=&type=&order=popular" to "Popular",
        "ongoing/"                           to "Ongoing",
        "upcoming/"                          to "Upcoming",
    )

    /* ─── Shared parsing helpers (used by loadSeries + loadAsMovie) ─── */
    private fun extractTitle(vararg docs: Document): String =
        docs.asSequence().mapNotNull {
            it.selectFirst("h1.entry-title")?.text()?.trim()
                ?: it.selectFirst("h1")?.text()?.trim()
                ?: it.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        }.firstOrNull { it.isNotBlank() } ?: "Unknown"

    private fun extractPoster(vararg docs: Document): String? =
        docs.asSequence()
            .mapNotNull { it.selectFirst("meta[property=og:image]")?.attr("content") }
            .firstOrNull()
            ?.let { fixUrlNull(it) }

    private fun extractPlot(vararg docs: Document): String? =
        docs.asSequence().mapNotNull { d ->
            d.selectFirst("div.desc.mindes")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
                ?: d.selectFirst("div.desc p")?.text()?.trim()
                ?: d.selectFirst("meta[property=og:description]")?.attr("content")
        }.firstOrNull()

    private fun extractYear(vararg docs: Document): Int? =
        docs.asSequence()
            .flatMap { it.select(".single-info .spe span").asSequence() }
            .firstOrNull { it.text().contains("Released", true) }?.text()
            ?.let { yearRegex.find(it)?.value?.toIntOrNull() }

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
                ?.replace(epStripRegex, "")
                ?.trim()
        } ?: anchor.attr("title")
            .replace(epStripRegex, "")
            .trim()

        if (seriesName.isEmpty()) return null

        val epBadge = selectFirst("span.epx")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() && it.matches(epBadgeRegex) }
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
        val seriesDoc = runCatching { app.get(seriesUrl).document }
            .onFailure { println("LMANIME: series fetch $seriesUrl failed: ${it.message}") }
            .getOrNull() ?: episodeDoc

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

            val epNum = epNumRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: epBracketNum.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

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

        val title  = extractTitle(seriesDoc, episodeDoc)
        val poster = extractPoster(episodeDoc, seriesDoc)
        val plot   = extractPlot(seriesDoc, episodeDoc)
        val year   = extractYear(seriesDoc, episodeDoc)

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
        val title  = extractTitle(document)
        val poster = extractPoster(document)
        val plot   = extractPlot(document)
        val year   = extractYear(document)

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = poster
            this.plot      = plot
            if (year != null) this.year = year
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

        /* Global dedup — only path that touches the caller's callback. */
        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        fun emit(link: ExtractorLink) {
            if (emittedUrls.add(link.url)) callback(link)
        }

        /* ── Language detection (precompiled) ──────────────────────── */
        val langMatchers: List<Pair<String, Regex>> = listOf(
            "multi" to Regex("""\b(multi(sub)?|multilingual)\b"""),
            "ind"   to Regex("""\b(indonesian|indo|bahasa|ind)\b"""),
            "por"   to Regex("""\b(portugu[eê]s|portuguese|por)\b"""),
            "spa"   to Regex("""\b(espa[nñ]ol|spanish|spa)\b"""),
            "tha"   to Regex("""\b(ไทย|thai|tha)\b"""),
            "tur"   to Regex("""\b(t[uü]rk[cç]e|turkish|tur)\b"""),
            "ara"   to Regex("""(العَرَبِيَّة|عربي|arabic|ara)"""),
            "eng"   to Regex("""\b(english|eng)\b"""),
        )

        fun detectLang(vararg sources: String?): String? {
            val text = sources.filterNotNull().joinToString(" ").lowercase()
            if (text.isEmpty()) return null
            return langMatchers.firstOrNull { it.second.containsMatchIn(text) }?.first
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

        /* ── URL normalisation ──────────────────────────────────────── */
        fun normalize(raw: String): String {
            val t = raw.trim()
            return when {
                t.isBlank()          -> ""
                t.startsWith("//")   -> "https:$t"
                t.startsWith("/")    -> "$mainUrl$t"
                t.startsWith("http") -> t
                else                 -> "https://$t"
            }
        }

        val skipPatterns = listOf(
            "chatango", "google", "doubleclick", "adsystem",
            "facebook.com/plugins", "googletag", "disqus", "onesignal",
            "cloudfront.net", "21wiz.com",
        )

        /* ── Collectors ─────────────────────────────────────────────── */
        val iframeEntries = mutableListOf<Pair<String, String?>>()
        val videoUrls     = linkedSetOf<String>()
        val seenKeys      = mutableSetOf<String>()

        fun pushIframe(url: String, lang: String?) {
            if (url.isBlank()) return
            if (skipPatterns.any { url.contains(it, true) }) return
            if (seenKeys.add(url.substringBefore("?"))) iframeEntries.add(url to lang)
        }

        fun collectFrom(doc: Document, parentLang: String? = null) {
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach
                val lang = detectLang(iframe.attr("title"), iframe.attr("data-lang")) ?: parentLang
                pushIframe(normalize(src), lang)
            }

            doc.select("video, video source").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isBlank()) return@forEach
                val fixed = fixUrl(src)
                if (fixed.contains(".m3u8", true) || fixed.contains(".mp4", true)) {
                    videoUrls.add(fixed)
                }
            }

            /* Download blocks — one .soraddlx per language. */
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

        /* ── Mirror pages — PARALLEL ───────────────────────────────── */
        coroutineScope {
            document.select("select.mirror option").map { option ->
                async(Dispatchers.IO) {
                    val serverUrl = option.attr("value").trim()
                    if (!serverUrl.startsWith("http")) return@async null
                    val optLang = detectLang(
                        option.text(), option.attr("data-lang"), option.attr("title")
                    )
                    runCatching { app.get(serverUrl, referer = data).document }
                        .onFailure { println("LMANIME: mirror $serverUrl failed: ${it.message}") }
                        .getOrNull()?.let { it to optLang }
                }
            }.awaitAll().filterNotNull()
                .forEach { (doc, lang) -> collectFrom(doc, lang) }
        }

        /* ── Priority sort (user locale > English/Multi > rest) ────── */
        val userLang = Locale.getDefault().language
        fun priority(lang: String?): Int = when {
            lang == null     -> 0
            lang == userLang -> 3
            lang == "eng"    -> 2
            lang == "multi"  -> 2
            else             -> 1
        }
        val sortedIframes = iframeEntries.sortedByDescending { priority(it.second) }

        /* ── Dispatch — parallel with semaphore ────────────────────── */
        suspend fun dispatch(url: String, lang: String?) {
            val tag = langTag(lang)
            val wrapped: (ExtractorLink) -> Unit = { link ->
                val newName   = if (tag != null && !link.name.contains(tag))   "${link.name} $tag"   else link.name
                val newSource = if (tag != null && !link.source.contains(tag)) "${link.source} $tag" else link.source
                emit(ExtractorLink(
                    source        = newSource,
                    name          = newName,
                    url           = link.url,
                    referer       = link.referer,
                    quality       = link.quality,
                    type          = link.type,
                    headers       = link.headers,
                    extractorData = link.extractorData,
                ))
            }

            when {
                url.contains("morencius.com") ->
                    VidHideExtractor().getUrl(url, data, subtitleCallback, wrapped)
                url.contains("rumble.com") ->
                    RumbleExtractor().getUrl(url, data, subtitleCallback, wrapped)
                url.contains("ok.ru") || url.contains("odnoklassniki.ru") ->
                    OkRuExtractor().getUrl(url, data, subtitleCallback, wrapped)
                url.contains("mp4upload.com") ->
                    Mp4UploadExtractor().getUrl(url, data, subtitleCallback, wrapped)
                else ->
                    loadExtractor(url, data, subtitleCallback, wrapped)
            }
        }

        val gate = Semaphore(6)
        coroutineScope {
            sortedIframes.map { (url, lang) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        runCatching { dispatch(normalizeDailymotion(url), lang) }
                            .onFailure { println("LMANIME: dispatch $url failed: ${it.message}") }
                    }
                }
            }.awaitAll()
        }

        /* ── Direct .m3u8 / .mp4 ───────────────────────────────────── */
        for (videoUrl in videoUrls) {
            val isHls = videoUrl.contains(".m3u8", true)
            emit(newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = videoUrl,
                type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) { this.referer = data })
        }

        /* ── Regex fallback (scripts/videos first, whole page last) ── */
        if (emittedUrls.isEmpty()) {
            val videoRegex = Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""")
            val scopedHtml = buildString {
                document.select("script, video, video source, iframe").forEach {
                    appendLine(it.data().ifBlank { it.outerHtml() })
                }
            }
            val haystack = if (scopedHtml.contains(".mp4", true) || scopedHtml.contains(".m3u8", true))
                scopedHtml else document.html()

            videoRegex.findAll(haystack).forEach { m ->
                val clean = m.value.replace("\\/", "/")
                val isHls = clean.contains(".m3u8", true)
                emit(newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = clean,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) { this.referer = data })
            }
        }

        return emittedUrls.isNotEmpty()
    }

    /* ------------------------------------------------------------------ */
    /* Dailymotion URL normalisation (pure string ops — no network)       */
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
