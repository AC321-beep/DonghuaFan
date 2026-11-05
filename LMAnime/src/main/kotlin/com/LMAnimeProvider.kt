package com.lmanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LMANIME : MainAPI() {

    companion object {
        // LMANIME's site-wide Dailymotion player id
        private const val DM_PLAYER_ID = "xofxc"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    override var mainUrl  = "https://lmanime.com"
    override var name     = "LMANIME"
    override var lang     = "zh"
    override val hasMainPage        = true
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val posterHeaders by lazy {
        mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )
    }

    private val dateRegex = Regex(
        """([a-zA-Z]{3,}\s+\d{1,2},\s+\d{4}|\d{1,2}\s+[a-zA-Z]{3,}\s+\d{4}|\d{4}-\d{2}-\d{2})"""
    )
    private val relativeDateRegex = Regex(
        """(?i)\d+\s+(mins?|minutes?|hours?|days?|weeks?|months?|years?)\s+ago"""
    )
    private val fmtMonthDayYear = Regex("""^[A-Za-z]+\s+\d{1,2},\s+\d{4}$""")
    private val fmtDayMonthYear = Regex("""^\d{1,2}\s+[A-Za-z]+\s+\d{4}$""")
    private val fmtIsoDate      = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private val epNumRegex      = Regex("""(?:\bepisode|\beps?\.?)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val leadingNumRegex = Regex("""^(\d{1,4})\b""")
    private val epBracketNum    = Regex("""[\[\(](\d{1,4})[\]\)]""")
    private val epUrlNum        = Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
    private val yearRegex       = Regex("""\b((?:19|20)\d{2})\b""")
    private val epStripRegex    = Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE)
    private val epBadgeRegex    = Regex("""(?i)(eps?\.?\s*\d+|\d+)""")

    private val dmVideoIdRegex   = Regex("""[?&]video=([a-zA-Z0-9_-]+)""")
    private val dmEmbedPathRegex = Regex("""/(?:embed/)?video/([a-zA-Z0-9_-]+)""")

    override val mainPage = mainPageOf(
        "latest"                             to "Latest Release",
        "anime/?status=&type=&order=popular" to "Popular",
        "ongoing/"                           to "Ongoing",
        "upcoming/"                          to "Upcoming",
    )

    private fun extractTitle(vararg docs: Document): String =
        docs.asSequence().mapNotNull {
            it.selectFirst("h1.entry-title")?.text()?.trim()
                ?: it.selectFirst("h1")?.text()?.trim()
                ?: it.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        }.firstOrNull { it.isNotBlank() } ?: "Unknown"

    private fun extractPoster(vararg docs: Document): String? =
        docs.asSequence()
            .mapNotNull {
                it.selectFirst(".bigcontent .thumb img, .single-info .thumb img")?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }?.takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                    ?: it.selectFirst("meta[property=og:image]")?.attr("content")
            }
            .firstOrNull()
            ?.let { fixUrlNull(it) }

    private fun extractPlot(vararg docs: Document): String? =
        docs.asSequence().mapNotNull { d ->
            d.selectFirst("div.desc.mindes")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
                ?: d.selectFirst(".bixbox.synp .entry-content")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: d.selectFirst("div.desc p")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: d.selectFirst("meta[property=og:description]")?.attr("content")
        }.firstOrNull()

    private fun extractYear(vararg docs: Document): Int? =
        docs.asSequence()
            .flatMap { it.select(".single-info .spe span, .bigcontent .infox .spe span").asSequence() }
            .firstOrNull { it.text().contains("Released", true) }?.text()
            ?.let { yearRegex.find(it)?.value?.toIntOrNull() }

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

        // Scope "latest" on page 1 to the Latest Release box so Completed/Recommendation boxes aren't mixed in
        val container = if (request.data == "latest" && page == 1) {
            document.selectFirst("div.bixbox:has(.latesthome)") ?: document
        } else {
            document
        }

        val items = container.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

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
            .ifEmpty { anchor.attr("oldtitle") }
            .replace(epStripRegex, "")
            .trim()

        if (seriesName.isEmpty()) return null

        val epBadge = selectFirst("span.epx")?.text()?.trim()
            ?.takeIf {
                it.isNotEmpty() &&
                !it.equals("Ongoing", true) &&
                !it.equals("Completed", true) &&
                epBadgeRegex.containsMatchIn(it)
            }
        val fullTitle = if (epBadge != null && !seriesName.contains(epBadge, true))
            "$seriesName $epBadge" else seriesName

        val href = fixUrl(anchor.attr("href"))
        val poster = selectFirst("img[data-src], img[src]")?.let { img ->
            val raw = img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: selectFirst("noscript img")?.attr("src")
            fixUrlNull(raw)
        }

        val typeText = selectFirst("div.typez")?.text()?.trim()
        val tvType   = if (typeText.equals("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(fullTitle, href, tvType) {
            this.posterUrl = poster
            this.posterHeaders = this@LMANIME.posterHeaders
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        return doc.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val pageType = document.select(".single-info .spe span, .bigcontent .infox .spe span")
            .firstOrNull { it.text().contains("Type:", true) }
            ?.text()?.substringAfter(":")?.trim()

        if (pageType.equals("Movie", true)) {
            return loadAsMovie(url, document, TvType.AnimeMovie)
        }

        val isEpisodePage = document.selectFirst("#singlepisode") != null ||
                            url.contains("-episode-", true)

        if (isEpisodePage) {
            val seriesUrl = document
                .selectFirst(
                    "#singlepisode .headlist .det h2 a, " +
                    "#singlepisode .headlist .det h3 a, " +
                    ".naveps .nvsc a, " +
                    ".navepsx .nvsc a"
                )
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?.takeIf { it.isNotBlank() && !it.contains("-episode-", true) }

            if (seriesUrl != null) {
                return loadSeries(seriesUrl, url, document)
            }
        }

        // Series page (from Popular / Ongoing / Upcoming) or episode page without a separate series link
        return loadSeries(url, url, document)
    }

    private suspend fun loadSeries(
        seriesUrl: String,
        episodeUrl: String,
        episodeDoc: Document,
    ): LoadResponse {
        val seriesDoc = if (seriesUrl != episodeUrl) {
            runCatching { app.get(seriesUrl).document }.getOrNull() ?: episodeDoc
        } else {
            episodeDoc
        }

        var links = episodeDoc.select("#singlepisode .episodelist ul li a")
        if (links.size < 2) {
            links = seriesDoc.select(
                "div.bxcl ul li a, " +
                ".eplister ul li a, " +
                ".epcheck .eplister ul li a, " +
                "#episodelist ul li a, " +
                "#singlepisode .episodelist ul li a"
            )
        }
        // Scoped fallback to .postbody so #sidebar "Latest Post" links from other shows are never included
        if (links.isEmpty()) {
            links = seriesDoc.select(".postbody a[href*='-episode-']")
        }

        val episodes = links.mapNotNull { a ->
            val href = fixUrl(a.attr("href")).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!href.contains("-episode-", true)) return@mapNotNull null

            val epTitle = a.selectFirst("h3, h4")?.text()?.trim()
                ?: a.selectFirst(".playinfo h3, .playinfo h4")?.text()?.trim()
                ?: a.selectFirst(".epl-title")?.text()?.trim()
                ?: a.selectFirst(".epl-num")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }.trim()

            val epNum = a.selectFirst(".epl-num")?.text()?.trim()
                ?.let {
                    epNumRegex.find(it)?.groupValues?.get(1)?.toIntOrNull()
                        ?: leadingNumRegex.find(it)?.groupValues?.get(1)?.toIntOrNull()
                        ?: epBracketNum.find(it)?.groupValues?.get(1)?.toIntOrNull()
                        ?: it.toIntOrNull()
                }
                ?: epNumRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: epUrlNum.find(href)?.groupValues?.get(1)?.toIntOrNull()
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
                        else                                   -> null
                    }
                    if (addFormat != null) {
                        this.addDate(extractedDate, format = addFormat)
                    }
                    this.description = extractedDate
                }
            }
        }.distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        if (episodes.isEmpty()) return loadAsMovie(episodeUrl, episodeDoc, TvType.Anime)

        val title  = extractTitle(seriesDoc, episodeDoc).replace(epStripRegex, "").trim()
        val poster = extractPoster(seriesDoc, episodeDoc)
        val plot   = extractPlot(seriesDoc, episodeDoc)
        val year   = extractYear(seriesDoc, episodeDoc)

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
            this.posterUrl     = poster
            this.posterHeaders = this@LMANIME.posterHeaders
            this.plot          = plot
            if (year != null) this.year = year
        }
    }

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
            this.posterUrl     = poster
            this.posterHeaders = this@LMANIME.posterHeaders
            this.plot          = plot
            if (year != null) this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        val found        = AtomicBoolean(false)
        val callbackLock = Any()
        val subtitleLock = Any()
        val emittedUrls  = ConcurrentHashMap.newKeySet<String>()

        fun emit(link: ExtractorLink) {
            if (!emittedUrls.add(link.url)) return
            found.set(true)
            synchronized(callbackLock) { callback(link) }
        }
        fun emitSub(sub: SubtitleFile) {
            synchronized(subtitleLock) { subtitleCallback(sub) }
        }

        val subtitleSink: (SubtitleFile) -> Unit = { sub -> emitSub(sub) }

        val langMatchers: List<Pair<String, Regex>> = listOf(
            "multi" to Regex("""\b(multi(sub)?|multilingual)\b""", RegexOption.IGNORE_CASE),
            "ind"   to Regex("""\b(indonesian|indo|bahasa|ind)\b""", RegexOption.IGNORE_CASE),
            "por"   to Regex("""\b(portugu[eêéë]s|portuguese|por)\b""", RegexOption.IGNORE_CASE),
            "spa"   to Regex("""\b(espa[nñ]ol|spanish|spa)\b""", RegexOption.IGNORE_CASE),
            "tha"   to Regex("""\b(ไทย|thai|tha)\b""", RegexOption.IGNORE_CASE),
            "tur"   to Regex("""\b(t[uü]rk[cç]e|turkish|tur)\b""", RegexOption.IGNORE_CASE),
            "ara"   to Regex("""(العَرَبِيَّة|عربي|arabic|ara)""", RegexOption.IGNORE_CASE),
            "eng"   to Regex("""\b(english|eng)\b""", RegexOption.IGNORE_CASE),
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

        val iframeEntries = mutableListOf<Pair<String, String?>>()
        val videoUrls     = linkedSetOf<String>()
        val keyToIndex    = mutableMapOf<String, Int>()

        fun pushIframe(url: String, lang: String?) {
            if (url.isBlank()) return
            if (skipPatterns.any { url.contains(it, true) }) return

            val key = dmVideoIdRegex.find(url)?.groupValues?.get(1)
                ?: dmEmbedPathRegex.find(url)?.groupValues?.get(1)
                ?: url.substringBefore("?").substringBefore("#")

            val existingIdx = keyToIndex[key]
            if (existingIdx == null) {
                keyToIndex[key] = iframeEntries.size
                iframeEntries.add(url to lang)
            } else {
                val (existingUrl, existingLang) = iframeEntries[existingIdx]
                if (existingLang == null && lang != null) {
                    iframeEntries[existingIdx] = existingUrl to lang
                }
            }
        }

        fun collectFrom(
            doc: Document,
            parentLang: String? = null,
            includeDownloads: Boolean = true,
        ) {
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

            if (!includeDownloads) return

            doc.select("div.soraddlx").forEach { block ->
                val heading = block.selectFirst(".sorattlx h3, .sorattlx")?.text()?.trim()
                val blockLang = detectLang(heading)

                block.select(".soraurlx a[href]").forEach { a ->
                    val href = a.attr("href").trim()
                    val linkLang = detectLang(a.text(), a.attr("title")) ?: blockLang ?: parentLang
                    when {
                        href.contains("morencius.com/file/") -> {
                            val id = href.substringAfter("/file/")
                                .substringBefore("/").substringBefore("?")
                            if (id.isNotBlank()) pushIframe("https://morencius.com/embed/$id", linkLang)
                        }
                        href.contains("mp4upload.com/") && !href.contains("/embed-") -> {
                            val id = href.substringAfter("mp4upload.com/")
                                .substringBefore("/").substringBefore(".")
                            if (id.isNotBlank())
                                pushIframe("https://www.mp4upload.com/embed-$id.html", linkLang)
                        }
                        href.contains("ok.ru/") || href.contains("odnoklassniki.ru/") -> {
                            pushIframe(href, linkLang)
                        }
                        href.contains("wishfast") || href.contains("filemoon") ||
                        href.contains("waaw.") || href.contains("d.tube") ||
                        href.contains("rumble.com") || href.contains("dailymotion.com") -> {
                            pushIframe(href, linkLang)
                        }
                    }
                }
            }
        }

        val defaultMirrorOpt = document.selectFirst(
            "select.mirror option[selected], select.mirror option[value^=http]"
        )
        val mainDocLang = defaultMirrorOpt?.let {
            detectLang(it.text(), it.attr("data-lang"), it.attr("title"))
        }

        collectFrom(document, parentLang = mainDocLang, includeDownloads = true)

        coroutineScope {
            val mirrorGate = Semaphore(4)
            document.select("select.mirror option").map { option ->
                async {
                    mirrorGate.withPermit {
                        val serverUrl = option.attr("value").trim()
                        if (!serverUrl.startsWith("http")) return@withPermit null
                        val optLang = detectLang(
                            option.text(), option.attr("data-lang"), option.attr("title")
                        )
                        runCatching { app.get(serverUrl, referer = data).document }
                            .getOrNull()?.let { it to optLang }
                    }
                }
            }.awaitAll().filterNotNull()
                .forEach { (doc, lang) -> collectFrom(doc, lang, includeDownloads = false) }
        }

        val userLang = Locale.getDefault().language
        fun priority(lang: String?): Int = when {
            lang == null     -> 0
            lang == userLang -> 3
            lang == "eng"    -> 2
            lang == "multi"  -> 2
            else             -> 1
        }
        val sortedIframes = iframeEntries.sortedByDescending { priority(it.second) }

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
                url.contains("dailymotion.com", true) ->
                    extractDailymotion(url, data, subtitleSink, wrapped)

                url.contains("morencius.com", true) ->
                    VidHideExtractor().getUrl(url, data, subtitleSink, wrapped)

                url.contains("rumble.com", true) ->
                    RumbleExtractor().getUrl(url, data, subtitleSink, wrapped)

                url.contains("ok.ru", true) || url.contains("odnoklassniki.ru", true) ->
                    OkRuExtractor().getUrl(url, data, subtitleSink, wrapped)

                else ->
                    loadExtractor(url, data, subtitleSink, wrapped)
            }
        }

        fun isCustom(url: String): Boolean =
            url.contains("dailymotion.com", true) ||
            url.contains("morencius.com", true) ||
            url.contains("rumble.com", true) ||
            url.contains("ok.ru", true) || url.contains("odnoklassniki.ru", true)

        val (customSources, builtinSources) = sortedIframes.partition { (url, _) -> isCustom(url) }

        coroutineScope {
            launch {
                val gate = Semaphore(4)
                customSources.map { (url, lang) ->
                    async {
                        gate.withPermit {
                            try {
                                withTimeoutOrNull(20_000L) { dispatch(url, lang) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                }.awaitAll()
            }

            launch {
                for ((url, lang) in builtinSources) {
                    try {
                        withTimeoutOrNull(20_000L) { dispatch(url, lang) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }

        for (videoUrl in videoUrls) {
            val isHls = videoUrl.contains(".m3u8", true)
            emit(newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = videoUrl,
                type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) { this.referer = data })
        }

        if (!found.get()) {
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

        return found.get()
    }

    private suspend fun extractDailymotion(
        url: String,
        episodeReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = dmVideoIdRegex.find(url)?.groupValues?.get(1)
            ?: dmEmbedPathRegex.find(url)?.groupValues?.get(1)

        if (videoId.isNullOrBlank()) {
            loadExtractor(url, episodeReferer, subtitleCallback, callback)
            return
        }

        val playerReferer = "https://geo.dailymotion.com/player/$DM_PLAYER_ID.html?video=$videoId"
        val encodedEmbedder = URLEncoder.encode(episodeReferer, "UTF-8")
        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId" +
            "?embedder=$encodedEmbedder&app=com.dailymotion.neon&locale=en_US&is_native_app=0"

        val headers = mapOf(
            "Referer" to playerReferer,
            "Origin"  to "https://geo.dailymotion.com",
        )
        val cookies = mapOf(
            "ff"            to "off",
            "family_filter" to "off",
        )

        val responseText = runCatching {
            app.get(metadataUrl, headers = headers, cookies = cookies).text
        }.getOrNull() ?: return

        val json = runCatching { JSONObject(responseText) }.getOrNull() ?: return

        if (json.optJSONObject("error") != null) return

        // Extract subtitles
        json.optJSONObject("subtitles")?.optJSONObject("data")?.let { subsData ->
            val subKeys = subsData.keys()
            while (subKeys.hasNext()) {
                val key = subKeys.next()
                val subObj = subsData.optJSONObject(key) ?: continue
                val label = subObj.optString("label").ifBlank { key }
                val urls = subObj.optJSONArray("urls") ?: continue
                for (i in 0 until urls.length()) {
                    val subUrl = urls.optString(i)
                    if (subUrl.isNotBlank()) {
                        subtitleCallback(newSubtitleFile(label, subUrl))
                    }
                }
            }
        }

        val qualities = json.optJSONObject("qualities") ?: return

        // Get master URL from 'auto' array
        val masterUrl = qualities.optJSONArray("auto")
            ?.optJSONObject(0)?.optString("url")
            ?.takeIf { it.isNotBlank() && it.contains(".m3u8", true) }
            ?: return

        // Fetch master once to check whether audio is a separate rendition.
        // Old DM: muxed variants -> M3u8Helper split preserves A/V.
        // New DM: EXT-X-MEDIA:TYPE=AUDIO -> variants are video-only;
        //         splitting would strip audio. Must emit raw master only.
        val masterText = runCatching { app.get(masterUrl).text }.getOrNull()
        val hasSeparateAudio = masterText?.contains(
            "EXT-X-MEDIA:TYPE=AUDIO", ignoreCase = true
        ) == true

        if (hasSeparateAudio) {
            callback(
                newExtractorLink(
                    source = "Dailymotion",
                    name   = "Dailymotion",
                    url    = masterUrl,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "https://geo.dailymotion.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        val generated = runCatching {
            M3u8Helper.generateM3u8(
                "Dailymotion",
                masterUrl,
                "https://geo.dailymotion.com/",
                headers = mapOf("Origin" to "https://geo.dailymotion.com"),
            )
        }.getOrNull().orEmpty()

        if (generated.isNotEmpty()) {
            generated.forEach { callback(it) }
        } else {
            callback(
                newExtractorLink(
                    source = "Dailymotion",
                    name   = "Dailymotion",
                    url    = masterUrl,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "https://geo.dailymotion.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
