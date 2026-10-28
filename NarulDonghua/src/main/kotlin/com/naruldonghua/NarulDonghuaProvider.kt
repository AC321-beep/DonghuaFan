package com.naruldonghua

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class NarulDonghuaProvider : MainAPI() {
    override var mainUrl = "https://naruldonghua.com"
    override var name = "NarulDonghua"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
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
        try { withTimeoutOrNull(timeoutMs) { app.get(url, headers = defaultHeaders).document } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private fun withCacheBust(url: String): String = if (url.contains("?")) "$url&_=${System.currentTimeMillis()}" else "$url?_=${System.currentTimeMillis()}"
    private val cardSelector = "article.bs, div.swiper-slide.item:not(.swiper-slide-duplicate)"

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update||anime/?status=&type=&order=latest" to "Latest Release",
        "anime/?status=&type=movie&order=update" to "Movie",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=ona&order=update" to "Donghua (ONA)",
        "anime/?status=&type=ova&order=update" to "Comic",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sources = request.data.split("||")
        val effectiveSources = if (page == 1) sources else sources.take(1)
        val bustCache = effectiveSources.size > 1
        val urls = effectiveSources.map { buildPagedUrl(it, page, bustCache) }
        val docs = coroutineScope { urls.map { url -> async { url to fetch(url) } }.awaitAll() }

        val items = docs.flatMap { (url, doc) ->
            if (doc == null) emptyList() else doc.select(cardSelector).mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        val hasNext = docs.any { (_, doc) -> doc?.selectFirst(".hpage a.r") != null } || items.isNotEmpty()
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
                if (useQueryPagination && page > 1) { add("paged=$page"); add("page=$page") }
                if (bustCache) add("_=${System.currentTimeMillis()}")
            }.joinToString("&")
            if (qs.isNotEmpty()) append('?').append(qs)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href == mainUrl || href.contains("/genres/") || href.contains("/anime/?") || href.contains("/network/") || href.contains("/studio/") || href.contains("/season/") || href.contains("/country/") || href.contains("/author/") || href.contains("/page/") || href.contains("/a-z/") || href.contains("/feed/") || href.contains("/wp-") || href.contains("?p=")) return null

        val title = selectFirst(".tt")?.ownText()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectFirst(".info h2 a")?.text()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectFirst("a[data-jtitle]")?.attr("data-jtitle")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst(".tt h2, h2")?.text()?.trim().takeUnless { it.isNullOrBlank() }
            ?: return null

        val poster = selectFirst("img")?.let { img ->
            sequenceOf("data-src", "data-lazy-src", "src").map { img.attr(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrlNull(it) }
        } ?: selectFirst(".backdrop[style]")?.let { div ->
            Regex("""url\(['"]?(.*?)['"]?\)""").find(div.attr("style"))?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
        }

        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return coroutineScope {
            (1..2).map { page ->
                async {
                    val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/?s=$encoded&paged=$page&page=$page"
                    fetch(url)?.select(cardSelector)?.mapNotNull { it.toSearchResult() }.orEmpty()
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetch(withCacheBust(url)) ?: fetch(url) ?: return newTvSeriesLoadResponse("", url, TvType.Anime, emptyList()) {}

        val entryTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim().orEmpty()
        val seriesName = doc.selectFirst(".single-info h2[itemprop=partOfSeries]")?.text()?.trim() ?: doc.selectFirst(".headlist h2 a, .headlist h2")?.text()?.trim() ?: entryTitle
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() } ?: doc.selectFirst(".desc, .entry-content")?.text()?.trim()

        val seriesUrl = sequenceOf(
            doc.selectFirst("a[aria-label='All Episodes']")?.attr("href"),
            doc.selectFirst(".naveps .nvsc a[href]")?.attr("href"),
            doc.select(".ts-breadcrumb a[href]").getOrNull(1)?.attr("href"),
            doc.selectFirst(".headlist h2 a[href]")?.attr("href"),
        ).mapNotNull { it?.takeIf { s -> s.isNotBlank() }?.let { fixUrlNull(it) } }.firstOrNull() ?: url

        val selectors = listOf(".episodelist li", ".eplister li", ".bixbox .listupd article.bs", ".listupd article.bs")
        var epNodes: List<Element> = emptyList()
        for (sel in selectors) {
            val nodes = doc.select(sel).toList()
            if (nodes.isNotEmpty()) { epNodes = nodes; break }
        }
        if (epNodes.isEmpty() && seriesUrl != url) {
            val seriesDoc = fetch(withCacheBust(seriesUrl)) ?: fetch(seriesUrl)
            if (seriesDoc != null) {
                for (sel in selectors) {
                    val nodes = seriesDoc.select(sel).toList()
                    if (nodes.isNotEmpty()) { epNodes = nodes; break }
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

            val epNum = Regex("""episode-(\d+)-""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?:Eps?|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(node.selectFirst(".playinfo span")?.text().orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) { this.name = name; this.posterUrl = poster; if (epNum != null) this.episode = epNum }
        }.distinctBy { it.data }.sortedByDescending { it.episode ?: 0 }

        if (episodes.isEmpty()) {
            val hasPlayer = doc.selectFirst("#embed_holder iframe, .video-content iframe, .player-embed iframe") != null
            if (hasPlayer) return newMovieLoadResponse(seriesName.ifBlank { entryTitle }, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = plot }
            return newTvSeriesLoadResponse(seriesName.ifBlank { entryTitle }, url, TvType.Movie, listOf(newEpisode(url) { this.name = entryTitle.ifBlank { url }; this.posterUrl = poster })) { this.posterUrl = poster; this.plot = plot }
        }

        return newTvSeriesLoadResponse(seriesName.ifBlank { entryTitle }, seriesUrl, TvType.Anime, episodes) { this.posterUrl = poster; this.plot = plot }
    }

    private fun isYouTubeUrl(url: String) = url.contains("youtube.com", true) || url.contains("youtu.be", true) || url.contains("youtube-nocookie.com", true)

    private fun normalizeYouTubeUrl(url: String): String {
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (u.contains("youtu.be/", true)) { val id = u.substringAfter("youtu.be/", "").substringBefore("?").substringBefore("&").substringBefore("/"); if (id.length in 6..20) return "https://www.youtube.com/watch?v=$id" }
        if (u.contains("youtube-nocookie.com", true)) { val id = Regex("""/(?:embed|v)/([A-Za-z0-9_-]{6,20})""").find(u)?.groupValues?.getOrNull(1); if (id != null) return "https://www.youtube.com/watch?v=$id" }
        if (u.contains("/embed/", true) || u.contains("/v/", true)) { val id = Regex("""/(?:embed|v)/([A-Za-z0-9_-]{6,20})""").find(u)?.groupValues?.getOrNull(1); if (id != null) return "https://www.youtube.com/watch?v=$id" }
        if (u.contains("youtube.com/watch", true)) { val id = Regex("""[?&]v=([A-Za-z0-9_-]{6,20})""").find(u)?.groupValues?.getOrNull(1); if (id != null) return "https://www.youtube.com/watch?v=$id" }
        return u
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.e("Narul/Prov", "=== loadLinks START data=$data")
        val doc = fetch(data) ?: return false

        val emittedLinks = ConcurrentHashMap.newKeySet<String>()
        val emittedSubs = ConcurrentHashMap.newKeySet<String>()
        val trackLink: (ExtractorLink) -> Unit = { if (emittedLinks.add(it.url)) callback(it) }
        val trackSub: (SubtitleFile) -> Unit = { if (emittedSubs.add(it.url)) subtitleCallback(it) }
        val visited = ConcurrentHashMap.newKeySet<String>()

        suspend fun resolve(raw: String, label: String = "") {
            val url = normalizeUrl(raw) ?: return
            if (isAdUrl(url)) return
            
            // DYNAMIC: The exact URL string is used. 
            // This allows #hash and #hash&dl=1 to run BOTH independently.
            if (!visited.add(url)) {
                Log.e("Narul/Prov", "skip dup: $url")
                return
            }
            Log.e("Narul/Prov", "── resolve[$label] $url")

            var extracted = false
            val inner: (ExtractorLink) -> Unit = { extracted = true; trackLink(it) }

            if (isDailymotionUrl(url)) {
                try { if (extractDailymotion(url, trackSub, inner)) return } catch (_: Exception) {}
            }
            if (url.contains("p2pstream.vip", true)) {
                try { P2pstream().getUrl(url, mainUrl, trackSub, inner) } catch (_: Exception) {}
                if (extracted) return else Log.e("Narul/Prov", "   ✗ Plex no links")
            }
            if (url.contains("abysplayserver.com", true) || url.contains("abyssplayer.com", true) || url.contains("abyysplayer.com", true) || url.contains("playhydrax.com", true)) {
                try { AbyssPlayer().getUrl(url, mainUrl, trackSub, inner) } catch (_: Exception) {}
                if (extracted) return else Log.e("Narul/Prov", "   ✗ Abyss no links")
            }
            if (url.contains("bysekoze.com", true) || url.contains("bysejikuar.com", true)) {
                try { Bysekoze().getUrl(url, mainUrl, trackSub, inner) } catch (_: Exception) {}
                if (extracted) return else Log.e("Narul/Prov", "   ✗ Byse no links")
            }

            if (isYouTubeUrl(url)) {
                val ytUrl = normalizeYouTubeUrl(url)
                try { loadExtractor(ytUrl, referer = "https://www.youtube.com/", trackSub, inner) } catch (_: Exception) {}
                if (!extracted) {
                    val retryUrl = if (ytUrl.contains("?")) "$ytUrl&_cb=${System.currentTimeMillis()}" else "$ytUrl?_cb=${System.currentTimeMillis()}"
                    try { loadExtractor(retryUrl, referer = "https://www.youtube.com/", trackSub, inner) } catch (_: Exception) {}
                }
                if (extracted) return
            }

            try { loadExtractor(url, referer = mainUrl, trackSub, inner) } catch (_: Exception) {}
            if (extracted) return

            val nested = fetch(url)?.let { page ->
                page.selectFirst("iframe[data-litespeed-src]")?.attr("data-litespeed-src")
                    ?: page.selectFirst("iframe[data-src]")?.attr("data-src")
                    ?: page.selectFirst("iframe[src]")?.attr("src")?.takeIf { !it.startsWith("about:") && !it.startsWith("data:") && !isAdUrl(it) }
                    ?: page.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.substringAfter("url=", "")?.trim()
            }
            if (!nested.isNullOrBlank() && !isAdUrl(nested)) resolve(nested, label)
        }

        coroutineScope {
            val tasks = mutableListOf<Pair<String, String>>()

            // 1. Direct Iframes
            doc.select("#embed_holder iframe, .player-embed iframe, .video-content iframe").forEach { iframe ->
                val src = sequenceOf(iframe.attr("data-litespeed-src"), iframe.attr("data-src"), iframe.attr("src")).firstOrNull { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("data:") && !isAdUrl(it) }
                if (src != null) tasks.add(src to "Direct")
            }

            // 2. Dropdowns
            doc.select("select.mirror option[value], .mobius option[value]").forEach { option ->
                val raw = option.attr("value").trim()
                val decoded = decodeServerValue(raw)
                if (!decoded.isNullOrBlank()) tasks.add(decoded to option.text().trim())
            }

            // 3. Raw Embeds
            Regex("""(?:https?:)?//[^"'\s<>]+""").findAll(doc.html().replace("\\/", "/")).map { it.value }
                .filter { u -> !isAdUrl(u) && listOf("embed", "player", "stream", "mirror", "dailymotion.com", "p2pstream.vip", "abysplayserver.com", "abyssplayer.com", "abyysplayer.com", "playhydrax.com", "ok.ru", "youtube.com/embed", "youtube-nocookie.com", "youtu.be", "bysekoze.com", "bysejikuar.com", "n1mwq.org").any { u.contains(it, true) } }
                .forEach { tasks.add(it to "Raw") }

            // 4. Downloads (ALWAYS ADD to the queue, no "if empty" hardcoding!)
            doc.select(".soradl .soraurlx a[href], .mctnx .soraurlx a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http") && !isAdUrl(href)) tasks.add(href to "DL-${a.text().trim()}")
            }

            Log.e("Narul/Prov", "Total tasks to resolve: ${tasks.size}")

            // Run all unique tasks independently!
            tasks.distinctBy { it.first }.map { (url, label) -> async { resolve(url, label) } }.awaitAll()
        }

        Log.e("Narul/Prov", "=== loadLinks END total links=${emittedLinks.size}")
        return emittedLinks.isNotEmpty()
    }

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
            val geoDoc = app.get(geoUrl, headers = dmHeaders).document
            val configScript = geoDoc.select("script").firstOrNull { it.data().contains("__PLAYER_CONFIG__") }
            if (configScript != null) {
                val raw = configScript.data()
                val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
                if (start in 0 until end) m3u8Url = JSONObject(raw.substring(start, end + 1)).optJSONObject("criticalMetadata")?.optJSONObject("stream")?.optString("url")?.takeIf { it.isNotBlank() }
            }
            if (m3u8Url == null) m3u8Url = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(geoDoc.outerHtml())?.value
        } catch (_: Exception) {}

        if (m3u8Url.isNullOrBlank()) {
            try {
                val body = app.get("https://www.dailymotion.com/player/metadata/video/$videoId", headers = dmHeaders).text
                val json = JSONObject(body)
                m3u8Url = json.optJSONObject("qualities")?.optJSONArray("auto")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                json.optJSONObject("subtitles")?.let { subs -> subs.keys().forEach { lang -> val subUrl = subs.optJSONObject(lang)?.optString("urls"); if (!subUrl.isNullOrBlank()) subCallback(newSubtitleFile(lang, subUrl) { headers = dmHeaders }) } }
            } catch (_: Exception) {}
        }

        if (m3u8Url.isNullOrBlank()) return false
        callback(newExtractorLink("Dailymotion", "Dailymotion", m3u8Url, ExtractorLinkType.M3U8) { this.referer = "https://www.dailymotion.com/" })
        return true
    }

    private fun decodeServerValue(raw: String): String? {
        val s = raw.trim()
        return when {
            s.startsWith("http") || s.startsWith("//") -> s
            s.startsWith("<", true) -> extractIframeSrc(s)
            else -> runCatching { val decoded = String(Base64.decode(s, Base64.DEFAULT)).trim(); when { decoded.startsWith("<", true) -> extractIframeSrc(decoded); decoded.startsWith("http") || decoded.startsWith("//") -> decoded; else -> null } }.getOrNull()
        }
    }

    private fun extractIframeSrc(html: String): String? {
        if (!html.contains("<iframe", true)) return null
        return Jsoup.parse(html).selectFirst("iframe[src], iframe[data-src], iframe[data-litespeed-src]")?.let { it.attr("data-litespeed-src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") } }?.takeIf { it.isNotBlank() }
    }

    private fun isAdUrl(url: String) = listOf("a-ads.com", "doubleclick", "googlesyndication", "googleadservices", "googletagservices", "adservice.google", "pubads", "adsystem", "dm-event.net", "live.rezync.com", "pippio.com", "static.cloudflareinsights.com", "mc.yandex.ru", "disqus.com", "accounts.google.com").any { url.contains(it, true) }

    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim().replace("\\/", "/")
        if (s.startsWith("\"") && s.endsWith("\"") && s.length > 1) s = s.substring(1, s.length - 1)
        if (s.startsWith("'") && s.endsWith("'") && s.length > 1) s = s.substring(1, s.length - 1)
        if (s.isBlank()) return null
        return when { s.startsWith("//") -> "https:$s"; s.startsWith("/") -> "$mainUrl$s"; s.startsWith("http") -> s; s.contains('.') -> "https://$s"; else -> null }
    }
}
