package com.lmanime

import android.util.Log
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LMANIME : MainAPI() {

    companion object {
        private const val TAG = "LMANIME"
    }

    override var mainUrl  = "https://lmanime.com"
    override var name     = "LMANIME"
    override var lang     = "zh"
    override val hasMainPage        = true
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    /* ─── Precompiled date regexes ─── */
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

    /* ─── Dailymotion helpers ─── */
    private val dmVideoIdRegex    = Regex("""[?&]video=([a-zA-Z0-9_-]+)""")
    /* Matches both /video/<id> and /embed/video/<id> — used for canonical rewrite + dedup */
    private val dmEmbedPathRegex  = Regex("""/(?:embed/)?video/([a-zA-Z0-9_-]+)""")
    private val dmManifestIdRegex = Regex("""manifest/video/([a-zA-Z0-9]+)\.m3u8""")
    private val dmConfigIdRegex   = Regex(""""criticalMetadata"\s*:\s*\{[^}]*?"id"\s*:\s*"([a-zA-Z0-9]+)"""")

    override val mainPage = mainPageOf(
        "latest"                             to "Latest Release",
        "anime/?status=&type=&order=popular" to "Popular",
        "ongoing/"                           to "Ongoing",
        "upcoming/"                          to "Upcoming",
    )

    /* ─── Shared parsing helpers ─── */
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
            .onFailure { Log.e(TAG, "series fetch $seriesUrl failed: ${it.message}") }
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
        Log.e(TAG, "═══ loadLinks START  data=$data")

        val document = app.get(data).document
        Log.e(TAG, "episode page fetched, html size=${document.html().length}")

        /* Thread-safe sinks */
        val found        = AtomicBoolean(false)
        val callbackLock = Any()
        val subtitleLock = Any()
        val emittedUrls  = ConcurrentHashMap.newKeySet<String>()

        fun emit(link: ExtractorLink) {
            if (!emittedUrls.add(link.url)) {
                Log.e(TAG, "  ↺ duplicate skipped: ${link.url.take(100)}")
                return
            }
            Log.e(TAG, "  ✓ EMIT  name='${link.name}'  source='${link.source}'  type=${link.type}  url=${link.url.take(110)}")
            found.set(true)
            synchronized(callbackLock) { callback(link) }
        }
        fun emitSub(sub: SubtitleFile) {
            Log.e(TAG, "  ▸ SUBTITLE  lang=${sub.lang}  url=${sub.url.take(100)}")
            synchronized(subtitleLock) { subtitleCallback(sub) }
        }

        val subtitleSink: (SubtitleFile) -> Unit = { sub -> emitSub(sub) }

        /* ── Language detection (Portugués with é now matched) ─── */
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

        /* ── Collectors ─── */
        val iframeEntries = mutableListOf<Pair<String, String?>>()
        val videoUrls     = linkedSetOf<String>()
        val seenKeys      = mutableSetOf<String>()

        fun pushIframe(url: String, lang: String?) {
            if (url.isBlank()) return
            if (skipPatterns.any { url.contains(it, true) }) {
                Log.e(TAG, "  ✗ skipped (ad pattern): $url")
                return
            }
            /* Dedup on video ID when present — catches same video served via
             * different Dailymotion URL forms (embed/video vs /video vs geo player). */
            val key = dmVideoIdRegex.find(url)?.groupValues?.get(1)
                ?: dmEmbedPathRegex.find(url)?.groupValues?.get(1)
                ?: url.substringBefore("?").substringBefore("#")
            if (seenKeys.add(key)) {
                Log.e(TAG, "  + iframe  lang=$lang  url=$url")
                iframeEntries.add(url to lang)
            } else {
                Log.e(TAG, "  ↺ iframe dedupe  key=$key")
            }
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
                    Log.e(TAG, "  + direct video  url=$fixed")
                    videoUrls.add(fixed)
                }
            }

            doc.select("div.soraddlx").forEach { block ->
                val heading = block.selectFirst(".sorattlx h3, .sorattlx")?.text()?.trim()
                val lang = detectLang(heading) ?: parentLang
                Log.e(TAG, "  dl block  heading='$heading'  lang=$lang")

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
        Log.e(TAG, "after main doc → iframes=${iframeEntries.size}  videoUrls=${videoUrls.size}")

        /* ── Mirror pages — bounded parallel (Semaphore 4) ─── */
        coroutineScope {
            val mirrorGate = Semaphore(4)
            document.select("select.mirror option").map { option ->
                async {
                    mirrorGate.withPermit {
                        val serverUrl = option.attr("value").trim()
                        if (!serverUrl.startsWith("http")) return@withPermit null
                        Log.e(TAG, "mirror fetch: $serverUrl")
                        val optLang = detectLang(
                            option.text(), option.attr("data-lang"), option.attr("title")
                        )
                        runCatching { app.get(serverUrl, referer = data).document }
                            .onSuccess {
                                Log.e(TAG, "  mirror OK: $serverUrl  (iframes=${it.select("iframe").size})")
                            }
                            .onFailure { Log.e(TAG, "  mirror FAIL: $serverUrl  ${it.message}") }
                            .getOrNull()?.let { it to optLang }
                    }
                }
            }.awaitAll().filterNotNull()
                .forEach { (doc, lang) -> collectFrom(doc, lang) }
        }
        Log.e(TAG, "after mirrors → iframes=${iframeEntries.size}")

        /* ── Priority sort ─── */
        val userLang = Locale.getDefault().language
        fun priority(lang: String?): Int = when {
            lang == null     -> 0
            lang == userLang -> 3
            lang == "eng"    -> 2
            lang == "multi"  -> 2
            else             -> 1
        }
        val sortedIframes = iframeEntries.sortedByDescending { priority(it.second) }
        Log.e(TAG, "sorted iframes (${sortedIframes.size}):")
        sortedIframes.forEachIndexed { i, (u, l) ->
            Log.e(TAG, "  [$i] lang=$l  url=$u")
        }

        /* ── Dispatch (single URL) ─── */
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
                    VidHideExtractor().getUrl(url, data, subtitleSink, wrapped)

                url.contains("rumble.com") ->
                    RumbleExtractor().getUrl(url, data, subtitleSink, wrapped)

                url.contains("ok.ru") || url.contains("odnoklassniki.ru") ->
                    OkRuExtractor().getUrl(url, data, subtitleSink, wrapped)

                /* mp4upload.com, dailymotion.com, and anything else → built-in via loadExtractor */

                else -> {
                    Log.e(TAG, "  routing to loadExtractor (built-in): $url")
                    loadExtractor(url, data, subtitleSink, wrapped)
                }
            }
        }

        /* ── PHASE 0 — parallel Dailymotion URL normalisation ─── */
        val prepared = coroutineScope {
            val gate = Semaphore(4)
            sortedIframes.map { (url, lang) ->
                async {
                    gate.withPermit {
                        try {
                            val n = normalizeDailymotion(url)
                            if (n != url) Log.e(TAG, "  dm normalise: $url  →  $n")
                            n to lang
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "  dm normalise FAIL: $url  ${e.message}")
                            url to lang
                        }
                    }
                }
            }.awaitAll()
        }

        /* ── Partition: custom (parallel-safe) vs built-in (sequential) ───
         * Dailymotion + Mp4Upload are INTENTIONALLY excluded from `custom`
         * because the built-in extractors share registry state and must not
         * be invoked in parallel.
         */
        fun isCustom(url: String): Boolean =
            url.contains("morencius.com") ||
            url.contains("rumble.com") ||
            url.contains("ok.ru") || url.contains("odnoklassniki.ru")

        val (customSources, builtinSources) = prepared.partition { (url, _) -> isCustom(url) }
        Log.e(TAG, "partition → custom=${customSources.size}  builtin=${builtinSources.size}")
        Log.e(TAG, "  custom:  ${customSources.map { it.first }}")
        Log.e(TAG, "  builtin: ${builtinSources.map { it.first }}")

        /* ── PHASES 1 + 2 — run concurrently ─── */
        coroutineScope {
            /* Phase 1 — custom extractors, parallel (Semaphore 4) */
            launch {
                Log.e(TAG, "▶ PHASE 1 (custom parallel) start")
                val gate = Semaphore(4)
                customSources.map { (url, lang) ->
                    async {
                        gate.withPermit {
                            Log.e(TAG, "  custom→ $url  (lang=$lang)")
                            try {
                                withTimeoutOrNull(20_000L) { dispatch(url, lang) }
                                    ?: Log.e(TAG, "  custom TIMEOUT: $url")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "  custom FAIL: $url  ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
                Log.e(TAG, "▶ PHASE 1 done. found=${found.get()}")
            }

            /* Phase 2 — built-in via loadExtractor, sequential */
            launch {
                Log.e(TAG, "▶ PHASE 2 (built-in sequential) start")
                for ((url, lang) in builtinSources) {
                    Log.e(TAG, "  builtin→ $url  (lang=$lang)")
                    try {
                        withTimeoutOrNull(20_000L) { dispatch(url, lang) }
                            ?: Log.e(TAG, "  builtin TIMEOUT: $url")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "  builtin FAIL: $url  ${e.message}")
                    }
                }
                Log.e(TAG, "▶ PHASE 2 done. found=${found.get()}")
            }
        }

        /* ── Direct .m3u8 / .mp4 ─── */
        for (videoUrl in videoUrls) {
            val isHls = videoUrl.contains(".m3u8", true)
            emit(newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = videoUrl,
                type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) { this.referer = data })
        }

        /* ── Regex fallback ─── */
        if (!found.get()) {
            Log.e(TAG, "no links yet → running regex fallback")
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

        Log.e(TAG, "═══ loadLinks END  found=${found.get()}  emitted=${emittedUrls.size}")
        return found.get()
    }

    /* ------------------------------------------------------------------ */
    /* Dailymotion URL normalisation (suspend — may fetch player shell)   */
    /*                                                                    */
    /* 1.   ?video=<id>                → /video/<id>                      */
    /* 1.5  /embed/video/<id>  or  /video/<id>  → /video/<id>             */
    /*      (strips query params like ?queue-enable=false)                */
    /* 2.   geo…/player/…html          → fetch, extract id, /video/<id>   */
    /* 3.   Anything else              → unchanged                        */
    /* ------------------------------------------------------------------ */
    private suspend fun normalizeDailymotion(url: String): String {
        if (!url.contains("dailymotion", true)) return url

        Log.e(TAG, "  dm  input=$url")

        // Case 1 — id in query string
        dmVideoIdRegex.find(url)?.groupValues?.get(1)?.let { id ->
            Log.e(TAG, "  dm  id from query=$id")
            return "https://www.dailymotion.com/video/$id"
        }

        // Case 1.5 — /embed/video/<id> or /video/<id> (strips query params)
        dmEmbedPathRegex.find(url)?.groupValues?.get(1)?.let { id ->
            Log.e(TAG, "  dm  id from path=$id  (stripping query/embed)")
            return "https://www.dailymotion.com/video/$id"
        }

        // Case 2 — player shell (fetch HTML to extract id from __PLAYER_CONFIG__)
        if (url.contains("geo.dailymotion.com", true) && url.contains("/player/")) {
            Log.e(TAG, "  dm  player shell — fetching HTML")
            val html = runCatching { app.get(url).text }
                .onFailure { Log.e(TAG, "  dm  shell fetch FAIL: ${it.message}") }
                .getOrNull()

            if (html != null) {
                Log.e(TAG, "  dm  shell html len=${html.length}")
                val id = dmManifestIdRegex.find(html)?.groupValues?.get(1)
                    ?: dmConfigIdRegex.find(html)?.groupValues?.get(1)

                if (!id.isNullOrBlank()) {
                    Log.e(TAG, "  dm  id from shell=$id")
                    return "https://www.dailymotion.com/video/$id"
                } else {
                    Log.e(TAG, "  dm  shell — no id found in HTML (regex missed)")
                }
            }
        }

        Log.e(TAG, "  dm  unchanged (no rewrite possible)")
        return url
    }
}
