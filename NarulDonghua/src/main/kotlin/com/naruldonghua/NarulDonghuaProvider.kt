package com.naruldonghua

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class NarulDonghuaProvider : MainAPI() {
    override var mainUrl = "https://naruldonghua.com"
    override var name = "NarulDonghua"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    companion object {
        private const val TAG = "Narul/Prov"
        
        // ── Precompiled Regexes (Memory Optimization) ──
        private val RE_BG_URL = Regex("""url\(['"]?(.*?)['"]?\)""")
        private val RE_EP_HREF = Regex("""episode-(\d+)-""", RegexOption.IGNORE_CASE)
        private val RE_EP_TEXT = Regex("""(?:Eps?|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val RE_RAW_URL = Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/[^"'\s<>\\]+""")
        private val STATIC_ASSET_EXT = Regex("""\.(?:png|jpe?g|gif|webp|svg|ico|css|js|woff2?|ttf|eot|json|xml)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        private val RE_JS_REDIRECT = Regex("""(?:window\.location(?:\.href)?|location\.replace)\s*\(?=\s*["']([^"']+)["']""")
        private val RE_RUMBLE_ID = Regex("""rumble\.com/embed/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
        private val RE_HTTP_URL = Regex("""https?://[^\s"'<>]+""")

        // ── Static Lookups (Avoids re-allocating lists on every loop) ──
        private val BYSE_DOMAINS = listOf("bysekoze.com", "bysejikuar.com", "byse.sx", "bysevepoin.com", "g9r6.com", "n1mwq.org", "f16px.com")
        private val AD_DOMAINS = listOf(
            "a-ads.com", "doubleclick", "googlesyndication", "googleadservices",
            "googletagservices", "adservice.google", "pubads", "adsystem",
            "dm-event.net", "live.rezync.com", "pippio.com",
            "static.cloudflareinsights.com", "mc.yandex.ru",
            "disqus.com", "accounts.google.com"
        )
        private val RUMBLE_FORMATS = listOf("mp4", "hls", "webm")
    }

    // ── Headers ──────────────────────────────────────────────────────────
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$mainUrl/",
    )

    private suspend fun fetch(url: String, timeoutMs: Long = 15_000L): Document? =
        try {
            withTimeoutOrNull(timeoutMs) { app.get(url, headers = defaultHeaders).document }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun withCacheBust(url: String): String =
        if (url.contains("?")) "$url&_=${System.currentTimeMillis()}"
        else                   "$url?_=${System.currentTimeMillis()}"

    private val cardSelector = "article.bs, div.swiper-slide.item:not(.swiper-slide-duplicate)"

    // ── Categories ───────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update||anime/?status=&type=&order=latest" to "Latest Release",
        "anime/?status=&type=movie&order=update"     to "Movie",
        "anime/?status=&type=&order=popular"         to "Popular",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=ona&order=update"       to "Donghua (ONA)",
        "anime/?status=&type=ova&order=update"       to "Comic",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sources = request.data.split("||")
        val effectiveSources = if (page == 1) sources else sources.take(1)
        val bustCache = effectiveSources.size > 1
        val urls = effectiveSources.map { buildPagedUrl(it, page, bustCache) }

        val docs = coroutineScope {
            urls.map { url -> async { url to fetch(url) } }.awaitAll()
        }

        val items = docs
            .flatMap { (_, doc) -> doc?.select(cardSelector)?.mapNotNull { it.toSearchResult() }.orEmpty() }
            .distinctBy { it.url }

        val hasNext = docs.any { (_, doc) ->
            doc?.selectFirst(".hpage a.r, .pagination a.next, a.next.page-numbers") != null
        } || items.isNotEmpty()

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun buildPagedUrl(data: String, page: Int, bustCache: Boolean = false): String {
        val q = data.indexOf('?')
        val rawPath = if (q == -1) data else data.substring(0, q)
        val path = rawPath.trim('/')
        val query = if (q == -1) "" else data.substring(q + 1)
        val useQueryPagination = query.isNotEmpty()

        return buildString {
            append(mainUrl).append('/')
            if (path.isNotEmpty()) append(path).append('/')
            if (!useQueryPagination && page > 1) append("page/$page/")
            val qs = buildList {
                if (query.isNotEmpty()) add(query)
                if (useQueryPagination && page > 1) {
                    add("paged=$page")
                    add("page=$page")
                }
                if (bustCache) add("_=${System.currentTimeMillis()}")
            }.joinToString("&")
            if (qs.isNotEmpty()) append('?').append(qs)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        if (href == mainUrl ||
            href.contains("/genres/") ||
            href.contains("/anime/?") ||
            href.contains("/network/") ||
            href.contains("/studio/") ||
            href.contains("/season/") ||
            href.contains("/country/") ||
            href.contains("/author/") ||
            href.contains("/page/") ||
            href.contains("/a-z/") ||
            href.contains("/feed/") ||
            href.contains("/wp-") ||
            href.contains("?p=")
        ) return null

        val title = selectFirst(".tt")?.ownText()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectFirst(".info h2 a")?.text()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectFirst("a[data-jtitle]")?.attr("data-jtitle")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst(".tt h2, h2")?.text()?.trim().takeUnless { it.isNullOrBlank() }
            ?: return null

        val poster = selectFirst("img")?.let { img ->
            sequenceOf("data-src", "data-lazy-src", "src")
                .map { img.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { fixUrlNull(it) }
        } ?: selectFirst(".backdrop[style]")?.let { div ->
            RE_BG_URL.find(div.attr("style"))?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
        }

        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return coroutineScope {
            (1..2).map { page ->
                async {
                    val url = if (page == 1) "$mainUrl/?s=$encoded"
                              else "$mainUrl/?s=$encoded&paged=$page&page=$page"
                    fetch(url)?.select(cardSelector)?.mapNotNull { it.toSearchResult() }.orEmpty()
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetch(withCacheBust(url)) ?: fetch(url)
            ?: return newTvSeriesLoadResponse("", url, TvType.Anime, emptyList()) {}

        val entryTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim().orEmpty()
        val seriesName = doc.selectFirst(".single-info h2[itemprop=partOfSeries]")?.text()?.trim()
            ?: doc.selectFirst(".headlist h2 a, .headlist h2")?.text()?.trim()
            ?: entryTitle
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".desc, .entry-content")?.text()?.trim()

        val seriesUrl = sequenceOf(
            doc.selectFirst("a[aria-label='All Episodes']")?.attr("href"),
            doc.selectFirst(".naveps .nvsc a[href]")?.attr("href"),
            doc.select(".ts-breadcrumb a[href]").getOrNull(1)?.attr("href"),
            doc.selectFirst(".headlist h2 a[href]")?.attr("href"),
        ).mapNotNull { it?.takeIf { s -> s.isNotBlank() }?.let { u -> fixUrlNull(u) } }.firstOrNull() ?: url

        val selectors = listOf(".episodelist li", ".eplister li", ".bixbox .listupd article.bs", ".listupd article.bs")
        var epNodes: List<Element> = emptyList()

        for (sel in selectors) {
            val nodes = doc.select(sel).toList()
            if (nodes.isNotEmpty()) {
                epNodes = nodes
                break
            }
        }

        if (epNodes.isEmpty() && seriesUrl != url) {
            val seriesDoc = fetch(withCacheBust(seriesUrl)) ?: fetch(seriesUrl)
            if (seriesDoc != null) {
                for (sel in selectors) {
                    val nodes = seriesDoc.select(sel).toList()
                    if (nodes.isNotEmpty()) {
                        epNodes = nodes
                        break
                    }
                }
            }
        }

        val episodes = epNodes.mapNotNull { node ->
            val a = node.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (href == mainUrl || href.contains("/genres/") || href.contains("/anime/?")) return@mapNotNull null

            val name = node.selectFirst(".playinfo h3")?.text()?.trim().takeUnless { it.isNullOrBlank() }
                ?: node.selectFirst(".epl-title")?.text()?.trim().takeUnless { it.isNullOrBlank() }
                ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                ?: node.selectFirst(".tt > h2, .tt")?.text()?.trim().takeUnless { it.isNullOrBlank() }
                ?: href.substringAfterLast('/', "").replace("-", " ").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

            val epNum = RE_EP_HREF.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: RE_EP_TEXT.find(node.selectFirst(".playinfo span")?.text().orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = name
                this.posterUrl = poster
                if (epNum != null) this.episode = epNum
            }
        }.distinctBy { it.data }.sortedByDescending { it.episode ?: 0 }

        if (episodes.isEmpty()) {
            val hasPlayer = doc.selectFirst("#embed_holder iframe, .video-content iframe, .player-embed iframe, select.mirror option[value], .mobius option[value]") != null
            if (hasPlayer) {
                return newMovieLoadResponse(seriesName.ifBlank { entryTitle }, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            return newTvSeriesLoadResponse(seriesName.ifBlank { entryTitle }, url, TvType.Movie, listOf(
                newEpisode(url) { this.name = entryTitle.ifBlank { url }; this.posterUrl = poster }
            )) { this.posterUrl = poster; this.plot = plot }
        }

        return newTvSeriesLoadResponse(seriesName.ifBlank { entryTitle }, seriesUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ── YouTube Helpers ──────────────────────────────────────────────────
    private fun isYouTubeUrl(url: String): Boolean =
        url.contains("youtube.com", true) || url.contains("youtu.be", true) || url.contains("youtube-nocookie.com", true)

    private fun normalizeYouTubeUrl(url: String): String {
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"

        if (u.contains("youtu.be/", true)) {
            val id = u.substringAfter("youtu.be/", "").substringBefore("?").substringBefore("&").substringBefore("/")
            if (id.isNotBlank() && id.length in 6..20) return "https://www.youtube.com/watch?v=$id"
        }

        if (u.contains("youtube-nocookie.com", true) || u.contains("/embed/", true) || u.contains("/v/", true)) {
            val id = Regex("""/(?:embed|v)/([A-Za-z0-9_-]{6,20})""").find(u)?.groupValues?.getOrNull(1)
            if (id != null) return "https://www.youtube.com/watch?v=$id"
        }

        if (u.contains("youtube.com/watch", true)) {
            val videoId = Regex("""[?&]v=([A-Za-z0-9_-]{6,20})""").find(u)?.groupValues?.getOrNull(1)
            if (videoId != null) {
                val startTime = Regex("""[?&]t=(\d+)""").find(u)?.groupValues?.getOrNull(1)
                return if (startTime != null) "https://www.youtube.com/watch?v=$videoId&t=$startTime" else "https://www.youtube.com/watch?v=$videoId"
            }
        }
        return u
    }

    // ── loadLinks ────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.e(TAG, "=== loadLinks START data=$data")
        val doc = fetch(data) ?: run {
            Log.e(TAG, "loadLinks page fetch FAILED")
            return false
        }

        val emittedLinks = ConcurrentHashMap.newKeySet<String>()
        val emittedSubs = ConcurrentHashMap.newKeySet<String>()
        val trackLink: (ExtractorLink) -> Unit = { if (emittedLinks.add(it.url)) callback(it) }
        val trackSub: (SubtitleFile) -> Unit = { if (emittedSubs.add(it.url)) subtitleCallback(it) }
        val visited = ConcurrentHashMap.newKeySet<String>()

        suspend fun resolve(raw: String, label: String = "", depth: Int = 0) {
            if (depth > 3) return
            val url = normalizeUrl(raw) ?: return
            if (isJunkOrAdUrl(url)) return

            val visitKey = buildVisitKey(url)
            if (!visited.add(visitKey)) return

            Log.e(TAG, "── resolve[$label] $url")
            var extracted = false
            val inner: (ExtractorLink) -> Unit = { extracted = true; trackLink(it) }

            // 1. Handle short.icu (Abyss/Hydrax shortener)
            if (url.contains("short.icu/", true)) {
                resolveRedirectUrl(url)?.let { target ->
                    if (target != url) {
                        Log.e(TAG, "   short.icu → $target")
                        resolve(target, label, depth + 1)
                        return
                    }
                }
            }

            // 2. Extractors matching
            when {
                isDailymotionUrl(url) -> runCatching { if (extractDailymotion(url, trackSub, inner)) { Log.e(TAG, "   ✓ DM OK"); return } }
                url.contains("p2pstream.vip", true) -> runCatching { P2pstream().getUrl(url, mainUrl, trackSub, inner) }.onSuccess { if (extracted) { Log.e(TAG, "   ✓ Plex OK"); return } }
                url.contains("abysplayserver.com", true) || url.contains("abyssplayer.com", true) || url.contains("abyysplayer.com", true) || url.contains("playhydrax.com", true) -> {
                    runCatching { AbyssPlayer().getUrl(url, mainUrl, trackSub, inner) }.onSuccess { if (extracted) { Log.e(TAG, "   ✓ Abyss OK"); return } }
                }
                isByseUrl(url) -> runCatching { Bysekoze().getUrl(url, mainUrl, trackSub, inner) }.onSuccess { if (extracted) { Log.e(TAG, "   ✓ Byse OK"); return } }
                url.contains("rumble.com/", true) -> runCatching { if (extractRumble(url, inner)) { Log.e(TAG, "   ✓ Rumble OK"); return } }
                isYouTubeUrl(url) -> runCatching { loadExtractor(normalizeYouTubeUrl(url), referer = "https://www.youtube.com/", trackSub, inner) }.onSuccess { if (extracted) { Log.e(TAG, "   ✓ YouTube OK"); return } }
            }

            // 3. Generic Extractor
            runCatching { loadExtractor(url, referer = mainUrl, trackSub, inner) }
            if (extracted) {
                Log.e(TAG, "   ✓ generic OK")
                return
            }

            // 4. Nested iframe fallback
            if (!url.startsWith(mainUrl, true)) {
                val nested = fetch(url, timeoutMs = 8_000L)?.let { page ->
                    page.selectFirst("iframe[data-litespeed-src]")?.attr("data-litespeed-src")
                        ?: page.selectFirst("iframe[data-src]")?.attr("data-src")
                        ?: page.selectFirst("iframe[src]")?.attr("src")?.takeIf { !it.startsWith("about:") && !it.startsWith("data:") && !isJunkOrAdUrl(it) }
                        ?: page.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.substringAfter("url=")?.substringAfter("URL=")?.trim()
                }
                if (!nested.isNullOrBlank() && !isJunkOrAdUrl(nested)) {
                    resolve(nested, label, depth + 1)
                }
            }
        }

        val tasks = mutableListOf<Pair<String, String>>()

        doc.select("#embed_holder iframe, .player-embed iframe, .video-content iframe")
            .mapNotNull { iframe ->
                sequenceOf(iframe.attr("data-litespeed-src"), iframe.attr("data-src"), iframe.attr("src"))
                    .firstOrNull { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("data:") && !isJunkOrAdUrl(it) }
            }.forEach { url -> tasks.add(url to "Direct") }

        doc.select("select.mirror option[value], .mobius option[value], .mirror option[data-em]").forEach { option ->
            val raw = option.attr("value").ifBlank { option.attr("data-em") }.trim()
            if (raw.isNotBlank()) {
                decodeServerValue(raw)?.let { tasks.add(it to option.text().trim().ifBlank { "Mirror" }) }
            }
        }

        doc.select(".soradl .soraurlx a[href], .mctnx .soraurlx a[href], .soraurlx a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http", true) && !isJunkOrAdUrl(href)) {
                tasks.add(href to "DL-${a.text().trim().ifBlank { "Link" }}")
            }
        }

        val htmlUnescaped = doc.html().replace("\\/", "/").replace("\\\"", "\"").replace("&amp;", "&")
        RE_RAW_URL.findAll(htmlUnescaped)
            .map { it.value.trimEnd('"', '\'', ')', ';', ',') }
            .filter { u ->
                !u.startsWith(mainUrl, true) && !isJunkOrAdUrl(u) && (
                    isByseUrl(u) || u.contains("p2pstream.vip", true) ||
                    u.contains("abysplayserver.com", true) || u.contains("abyssplayer.com", true) ||
                    u.contains("abyysplayer.com", true) || u.contains("playhydrax.com", true) ||
                    u.contains("short.icu/", true) || u.contains("rumble.com/embed/", true) ||
                    u.contains("dailymotion.com/embed/", true) || u.contains("ok.ru/videoembed/", true) ||
                    u.contains("youtube.com/embed/", true) || u.contains("youtu.be/", true) ||
                    (u.contains("geo.dailymotion.com/player", true) && u.contains("video=", true))
                )
            }.distinct().forEach { u -> tasks.add(u to "Raw") }

        Log.e(TAG, "Total tasks to resolve: ${tasks.size}")

        coroutineScope {
            tasks.map { (url, label) -> async { resolve(url, label) } }.awaitAll()
        }

        Log.e(TAG, "=== loadLinks END total links=${emittedLinks.size}")
        return emittedLinks.isNotEmpty()
    }

    private fun isByseUrl(url: String): Boolean = BYSE_DOMAINS.any { url.contains(it, true) }

    private fun buildVisitKey(url: String): String {
        val clean = url.replace("&amp;", "&").trimEnd('/')
        if (clean.contains("p2pstream.vip", true)) return clean // Preserve #id vs #id&dl=1 for Plex
        val base = clean.substringBefore('#')
        val hash = clean.substringAfter('#', "")
        return if (hash.isNotBlank() && !hash.startsWith("?")) "$base#$hash" else base
    }

    private suspend fun resolveRedirectUrl(shortUrl: String): String? {
        return try {
            val resp = withTimeoutOrNull(8_000L) { app.get(shortUrl, headers = defaultHeaders, allowRedirects = false) }
            val loc = resp?.headers?.get("location") ?: resp?.headers?.get("Location")
            if (!loc.isNullOrBlank()) return normalizeUrl(loc)

            val fullResp = withTimeoutOrNull(8_000L) { app.get(shortUrl, headers = defaultHeaders, allowRedirects = true) } ?: return null
            if (fullResp.url != shortUrl && !fullResp.url.contains("short.icu", true)) return fullResp.url
            
            val html = fullResp.text
            RE_JS_REDIRECT.find(html)?.groupValues?.getOrNull(1) ?: Jsoup.parse(html).selectFirst("iframe[src]")?.attr("src")
        } catch (_: Exception) { null }
    }

    // ── Rumble Extractor ──────────────────────────────────────────────────
    private suspend fun extractRumble(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val embedId = RE_RUMBLE_ID.find(url)?.groupValues?.getOrNull(1) ?: return false
        val body = withTimeoutOrNull(10_000L) { app.get("https://rumble.com/embedJS/u3/?request=video&ver=2&v=$embedId", headers = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!, "Referer" to url)).text } ?: return false
        val ua = runCatching { JSONObject(body).optJSONObject("ua") }.getOrNull() ?: return false
        var found = false

        for (formatKey in RUMBLE_FORMATS) {
            val fmtObj = ua.optJSONObject(formatKey) ?: continue
            val qKeys = fmtObj.keys()
            while (qKeys.hasNext()) {
                val qKey = qKeys.next()
                val streamUrl = fmtObj.optJSONObject(qKey)?.optString("url")?.takeIf { it.startsWith("http") } ?: continue
                val quality = qKey.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                val isM3u8 = streamUrl.contains(".m3u8", true) || formatKey == "hls"
                callback(newExtractorLink("Rumble", "Rumble", streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE) { this.referer = "https://rumble.com/"; this.quality = quality })
                found = true
            }
        }
        return found
    }

    // ── Dailymotion ──────────────────────────────────────────────────────
    private fun isDailymotionUrl(url: String) = url.contains("dailymotion.com", true) || url.contains("dai.ly", true)

    private fun extractDailymotionVideoId(url: String): String? = when {
        url.contains("geo.dailymotion.com", true) -> Regex("""[?&]video=([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
        url.contains("/embed/video/", true) -> url.substringAfter("/embed/video/").substringBefore("?").substringBefore("/").substringBefore("_").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
        url.contains("/video/", true) -> url.substringAfter("/video/").substringBefore("_").substringBefore("?").substringBefore("/").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
        url.contains("dai.ly/", true) -> url.substringAfter("dai.ly/").substringBefore("?").substringBefore("/").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
        else -> null
    }

    private suspend fun extractDailymotion(url: String, subCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val videoId = extractDailymotionVideoId(url) ?: return false
        val dmHeaders = mapOf("User-Agent" to defaultHeaders["User-Agent"]!!, "Referer" to "https://www.dailymotion.com/", "Origin" to "https://www.dailymotion.com")
        val geoUrl = if (url.contains("geo.dailymotion.com", true)) url else "https://geo.dailymotion.com/player/x9mpt.html?video=$videoId"
        var m3u8Url: String? = null

        try {
            val geoDoc = withTimeoutOrNull(10_000L) { app.get(geoUrl, headers = dmHeaders).document }
            val configScript = geoDoc?.select("script")?.firstOrNull { it.data().contains("__PLAYER_CONFIG__") }
            if (configScript != null) {
                val raw = configScript.data()
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                if (start in 0 until end) {
                    runCatching { m3u8Url = JSONObject(raw.substring(start, end + 1)).optJSONObject("criticalMetadata")?.optJSONObject("stream")?.optString("url")?.takeIf { it.isNotBlank() } }
                }
            }
            if (m3u8Url == null && geoDoc != null) {
                m3u8Url = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(geoDoc.outerHtml())?.value
            }
        } catch (_: Exception) {}

        if (m3u8Url.isNullOrBlank()) {
            try {
                val body = withTimeoutOrNull(10_000L) { app.get("https://www.dailymotion.com/player/metadata/video/$videoId", headers = dmHeaders).text }.orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    m3u8Url = json.optJSONObject("qualities")?.optJSONArray("auto")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                    val subsObj = json.optJSONObject("subtitles")?.optJSONObject("data") ?: json.optJSONObject("subtitles")
                    subsObj?.keys()?.forEach { lang ->
                        val item = subsObj.optJSONObject(lang)
                        val subUrl = item?.optJSONArray("urls")?.optString(0)?.ifBlank { item.optString("urls") }
                        if (!subUrl.isNullOrBlank() && subUrl.startsWith("http")) subCallback(newSubtitleFile(lang, subUrl) { headers = dmHeaders })
                    }
                }
            } catch (_: Exception) {}
        }

        val finalM3u8 = m3u8Url?.takeIf { it.isNotBlank() } ?: return false
        val variants = M3u8Helper.generateM3u8("Dailymotion", finalM3u8, "https://www.dailymotion.com/", headers = dmHeaders)
        if (variants.isNotEmpty()) variants.forEach(callback)
        else callback(newExtractorLink("Dailymotion", "Dailymotion", finalM3u8, ExtractorLinkType.M3U8) { this.referer = "https://www.dailymotion.com/"; this.headers = dmHeaders })
        return true
    }

    private fun decodeServerValue(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when {
            s.startsWith("http", true) || s.startsWith("//") -> s
            s.contains("<iframe", true) -> extractIframeSrc(s)
            else -> runCatching {
                val decoded = String(Base64.decode(s.filterNot { it.isWhitespace() }, Base64.DEFAULT), Charsets.UTF_8).trim()
                when {
                    decoded.contains("<iframe", true) -> extractIframeSrc(decoded)
                    decoded.startsWith("http", true) || decoded.startsWith("//") -> decoded
                    else -> RE_HTTP_URL.find(decoded)?.value
                }
            }.getOrNull()
        }
    }

    private fun extractIframeSrc(html: String): String? =
        Jsoup.parse(html).selectFirst("iframe[src], iframe[data-src], iframe[data-litespeed-src]")?.let {
            it.attr("data-litespeed-src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") }
        }?.takeIf { it.isNotBlank() }

    private fun isJunkOrAdUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        if (lower.isEmpty() || lower.contains("\\\"") || lower.contains("\"%3a")) return true
        if (STATIC_ASSET_EXT.containsMatchIn(lower)) return true
        if (lower.contains("/wp-content/") || lower.contains("/wp-json/") || lower.contains("/wp-includes/")) return true
        if (lower.contains("/embed/#?secret=")) return true
        if (lower == "https://player.vimeo.com" || lower == "https://player.vimeo.com/") return true
        if (lower == "https://geo.dailymotion.com/player" || lower == "https://geo.dailymotion.com/player/") return true
        return AD_DOMAINS.any { lower.contains(it) }
    }

    private fun normalizeUrl(raw: String): String? {
        val s = raw.trim().replace("\\/", "/").replace("\\\"", "").replace("&amp;", "&").trim('"', '\'', ' ')
        if (s.isBlank() || s.startsWith("about:") || s.startsWith("data:") || s.startsWith("javascript:")) return null
        return when {
            s.startsWith("//")   -> "https:$s"
            s.startsWith("/")    -> "$mainUrl$s"
            s.startsWith("http") -> s
            s.contains('.') && !s.contains(' ') -> "https://$s"
            else -> null
        }
    }
}
