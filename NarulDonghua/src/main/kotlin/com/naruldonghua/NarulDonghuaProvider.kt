package com.naruldonghua

import android.util.Base64
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
    override var name = "Narul Donghua"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    // Reusable pattern (AnimeKhor): single timeout-guarded fetch helper.
    private suspend fun fetch(url: String, timeoutMs: Long = 15_000L): Document? =
        try {
            withTimeoutOrNull(timeoutMs) {
                app.get(url, headers = defaultHeaders).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // Confirmed from HTML: cards sit inside .listupd → .excstf → article.bs → .bsx.
    private val cardSelector = "div.listupd article.bs, div.listupd .bsx, div.bsx"

    // Confirmed from HTML: /anime uses status/type/order params; comic at /comic-series/.
    override val mainPage = mainPageOf(
        "anime/?status=ongoing&type=&order=update" to "Recently Updated",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?status=&type=ona" to "Donghua Series",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed",
        "comic-series/" to "Comic Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(buildPagedUrl(request.data, page))
            ?: return newHomePageResponse(request.name, emptyList())
        val items = doc.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // Site uses /page/N/ BEFORE the query string (verified from <link rel="next">).
    private fun buildPagedUrl(data: String, page: Int): String {
        val q = data.indexOf('?')
        val path = if (q == -1) data else data.substring(0, q)
        val query = if (q == -1) "" else data.substring(q + 1)
        return buildString {
            append(mainUrl).append('/').append(path)
            if (!path.endsWith('/')) append('/')
            if (page > 1) append("page/$page/")
            if (query.isNotEmpty()) append('?').append(query)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        // On episode cards a[title] is the *episode* title; .tt ownText = series name.
        val title = selectFirst(".tt")?.ownText()?.trim().takeUnless { it.isNullOrBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        // LiteSpeed lazy loading: real poster in data-src, src is a base64 placeholder.
        val poster = selectFirst("img")?.let { img ->
            sequenceOf("data-src", "data-lazy-src", "src")
                .map { img.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { fixUrlNull(it) }
        }

        return newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return coroutineScope {
            (1..2).map { page ->
                async {
                    val url = if (page == 1) "$mainUrl/?s=$encoded"
                              else "$mainUrl/page/$page/?s=$encoded"
                    fetch(url)?.select(cardSelector)
                        ?.mapNotNull { it.toSearchResult() }
                        .orEmpty()
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetch(url)
            ?: return newTvSeriesLoadResponse("", url, TvType.Anime, emptyList()) {}

        val entryTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1")
            ?.text()?.trim().orEmpty()
        val seriesName = doc.selectFirst(".single-info h2[itemprop=partOfSeries]")?.text()?.trim()
            ?: doc.selectFirst(".headlist h2 a, .headlist h2")?.text()?.trim()
            ?: entryTitle
        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".desc, .entry-content")?.text()?.trim()

        // Canonical series URL is emitted on episode pages via multiple landmarks.
        val seriesUrl = sequenceOf(
            doc.selectFirst("a[aria-label='All Episodes']")?.attr("href"),
            doc.selectFirst(".naveps .nvsc a[href]")?.attr("href"),
            doc.select(".ts-breadcrumb a[href]").getOrNull(1)?.attr("href"),
            doc.selectFirst(".headlist h2 a[href]")?.attr("href"),
        ).mapNotNull { it?.takeIf { s -> s.isNotBlank() }?.let { fixUrlNull(it) } }
            .firstOrNull() ?: url

        // .episodelist exists on both series pages and episode pages.
       val epNodes: List<Element> = run {
    val first = doc.select(".episodelist li")
    if (first.isNotEmpty() || seriesUrl == url) first
    else fetch(seriesUrl)?.select(".episodelist li")?.toList().orEmpty()
}

        val episodes = epNodes.mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val name = li.selectFirst(".playinfo h3")?.text()?.trim()
                ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val spanText = li.selectFirst(".playinfo span")?.text().orEmpty()
            val epNum = Regex("""(?:Eps?|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(spanText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = name
                this.posterUrl = poster
                if (epNum != null) this.episode = epNum
            }
        }.distinctBy { it.data }.reversed() // site lists newest-first

        if (episodes.isEmpty() && doc.selectFirst("#embed_holder, .video-content") != null) {
            return newMovieLoadResponse(
                seriesName.ifBlank { entryTitle }, url, TvType.Movie, url
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newTvSeriesLoadResponse(
            seriesName.ifBlank { entryTitle }, seriesUrl, TvType.Anime, episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = fetch(data) ?: return false

        // Reusable pattern (AnimeKhor): dedupe emissions across strategies.
        val emittedLinks = ConcurrentHashMap.newKeySet<String>()
        val emittedSubs = ConcurrentHashMap.newKeySet<String>()
        val trackLink: (ExtractorLink) -> Unit = { if (emittedLinks.add(it.url)) callback(it) }
        val trackSub: (SubtitleFile) -> Unit = { if (emittedSubs.add(it.url)) subtitleCallback(it) }

        // Reusable pattern: visited-set + recursive resolver for shortener chains.
        val visited = ConcurrentHashMap.newKeySet<String>()

        suspend fun resolve(raw: String, label: String = "") {
            val url = normalizeUrl(raw) ?: return
            if (isAdUrl(url)) return
            if (!visited.add(url.substringBefore('#').substringBefore('?'))) return

            var extracted = false
            val inner: (ExtractorLink) -> Unit = { extracted = true; trackLink(it) }

            // ── Priority 1: Dailymotion (custom, verified working via metadata+geo) ──
            if (isDailymotionUrl(url)) {
                try {
                    if (extractDailymotion(url, trackSub, inner)) return
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* fall through */ }
            }

            // ── Priority 2: p2pstream.vip (bundled extractor) ───────────────────
            if (url.contains("p2pstream.vip", true)) {
                try {
                    P2pstream().getUrl(url, mainUrl, trackSub, inner)
                    if (extracted) return
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* fall through */ }
            }

            // ── Priority 3: abysplayserver / abyssplayer / playhydrax (bundled) ─
            if (url.contains("abysplayserver.com", true) ||
                url.contains("abyssplayer.com", true) ||
                url.contains("playhydrax.com", true)
            ) {
                try {
                    AbyssPlayer().getUrl(url, mainUrl, trackSub, inner)
                    if (extracted) return
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* fall through */ }
            }

            // ── Priority 4: bysekoze.com OR bysejikuar.com (bundled) ────────────
            if (url.contains("bysekoze.com", true) || url.contains("bysejikuar.com", true)) {
                try {
                    Bysekoze().getUrl(url, mainUrl, trackSub, inner)
                    if (extracted) return
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* fall through */ }
            }

            // ── Priority 5: CloudStream's built-in extractors (YouTube, ok.ru, …) ─
            try {
                loadExtractor(url, referer = mainUrl, trackSub, inner)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* silent */ }

            if (extracted) return

            // ── Priority 6: one-hop recursion via nested iframe / meta-refresh ─
            val nested = fetch(url)?.let { page ->
                page.selectFirst("iframe[data-litespeed-src]")?.attr("data-litespeed-src")
                    ?: page.selectFirst("iframe[data-src]")?.attr("data-src")
                    ?: page.selectFirst("iframe[src]")?.attr("src")?.takeIf {
                        !it.startsWith("about:") && !it.startsWith("data:") && !isAdUrl(it)
                    }
                    ?: page.selectFirst("meta[http-equiv=refresh]")
                        ?.attr("content")?.substringAfter("url=", "")?.trim()
            }
            if (!nested.isNullOrBlank() && !nested.contains("youtube", true) && !isAdUrl(nested)) {
                resolve(nested, label)
            }
        }

        coroutineScope {
            // A) Direct iframes in #embed_holder — LiteSpeed lazy-loads the
            //    real URL into data-litespeed-src; src is "about:blank".
            val directIframes = doc
                .select("#embed_holder iframe, .player-embed iframe, .video-content iframe")
                .mapNotNull { iframe ->
                    sequenceOf(
                        iframe.attr("data-litespeed-src"),
                        iframe.attr("data-src"),
                        iframe.attr("src"),
                    ).firstOrNull {
                        it.isNotBlank() && !it.startsWith("about:") &&
                        !it.startsWith("data:") && !isAdUrl(it)
                    }
                }

            // B) Server mirror options — value is base64 of <iframe> OR <div><iframe>
            val serverValues = doc
                .select("select.mirror option[value], .mobius option[value]")
                .mapNotNull { option ->
                    val raw = option.attr("value").trim()
                    if (raw.isBlank()) null else Pair(raw, option.text().trim())
                }

            // C) Raw HTML scan — heuristic fallback for anything the selectors miss.
            val rawEmbeds = Regex("""(?:https?:)?//[^"'\s<>]+""")
                .findAll(doc.html())
                .map { it.value.replace("\\/", "/") }
                .filter { u ->
                    !isAdUrl(u) && listOf(
                        "embed", "player", "stream", "mirror",
                        "dailymotion.com", "p2pstream.vip", "abysplayserver.com",
                        "abyssplayer.com", "playhydrax.com", "ok.ru",
                        "youtube.com/embed", "youtu.be",
                    ).any { u.contains(it, true) }
                }
                .toList()

            directIframes.map { url -> async { resolve(url, "Direct") } }.awaitAll()
            serverValues.map { (raw, label) ->
                async {
                    val decoded = decodeServerValue(raw)
                    if (!decoded.isNullOrBlank()) resolve(decoded, label)
                }
            }.awaitAll()
            rawEmbeds.map { url -> async { resolve(url, "Raw") } }.awaitAll()
        }

        // Download links — HAR: .soradl .soraurlx a[href]
        //  • FM → bysejikuar.com/d/{hash}/{slug}
        //  • NP → narulplex.p2pstream.vip/#{id}&dl=1
        doc.select(".soradl .soraurlx a[href], .mctnx .soraurlx a[href]")
            .map { it.attr("href").trim() to it.text().trim() }
            .filter { (href, _) -> href.startsWith("http") && !isAdUrl(href) }
            .forEach { (href, label) ->
                if (emittedLinks.add(href)) {
                    callback(
                        newExtractorLink(
                            "Download", "Download ($label)", href, ExtractorLinkType.VIDEO
                        ) { this.referer = mainUrl }
                    )
                }
            }

        return emittedLinks.isNotEmpty()
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Dailymotion resolver — metadata API + __PLAYER_CONFIG__ fallback.
    //  Both formats work because the DM player page embeds a freshly-signed
    //  HLS manifest inside its inline JSON.
    // ────────────────────────────────────────────────────────────────────────
    private fun isDailymotionUrl(url: String): Boolean =
        url.contains("dailymotion.com", true) || url.contains("dai.ly", true)

    private fun extractDailymotionVideoId(url: String): String? = when {
        url.contains("geo.dailymotion.com", true) ->
            Regex("""[?&]video=([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
        url.contains("/embed/video/", true) ->
            url.substringAfter("/embed/video/").substringBefore("?").substringBefore("/")
                .substringBefore("_").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
        url.contains("/video/", true) ->
            url.substringAfter("/video/").substringBefore("_").substringBefore("?")
                .substringBefore("/").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
        url.contains("dai.ly/", true) ->
            url.substringAfter("dai.ly/").substringBefore("?").substringBefore("/")
                .takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
        else -> null
    }

    private suspend fun extractDailymotion(
        url: String,
        subCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val videoId = extractDailymotionVideoId(url) ?: return false
        val dmHeaders = mapOf(
            "User-Agent" to defaultHeaders["User-Agent"]!!,
            "Referer"    to "https://www.dailymotion.com/",
            "Origin"     to "https://www.dailymotion.com",
        )
        var emitted = false

        // Strategy 1: metadata API
        try {
            val body = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                headers = dmHeaders,
            ).text
            val json = JSONObject(body)
            val hls = json.optJSONObject("qualities")?.optJSONArray("auto")
                ?.optJSONObject(0)?.optString("url")
            if (!hls.isNullOrBlank()) {
                callback(newExtractorLink("Dailymotion", "Dailymotion", hls, ExtractorLinkType.M3U8) {
                    this.referer = "https://www.dailymotion.com/"
                })
                emitted = true
            }
            json.optJSONObject("subtitles")?.let { subs ->
                subs.keys().forEach { lang ->
                    val subUrl = subs.optJSONObject(lang)?.optString("urls") ?: return@forEach
                    if (subUrl.isNotBlank()) {
                        subCallback(newSubtitleFile(lang, subUrl) { headers = dmHeaders })
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* fall through */ }

        if (emitted) return true

        // Strategy 2: geo player page + __PLAYER_CONFIG__ (guaranteed fallback).
        try {
            val geoUrl = "https://geo.dailymotion.com/player/x9mpt.html?video=$videoId"
            val geoDoc = app.get(geoUrl, headers = dmHeaders).document
            val configText = geoDoc.select("script")
                .firstOrNull { it.data().contains("__PLAYER_CONFIG__") }
                ?.data() ?: return false
            val hls = Regex(""""url"\s*:\s*"(https://[^"]+\.m3u8[^"]*)"""")
                .find(configText)?.groupValues?.get(1) ?: return false
            callback(newExtractorLink("Dailymotion", "Dailymotion (geo)", hls, ExtractorLinkType.M3U8) {
                this.referer = "https://www.dailymotion.com/"
            })
            return true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return false }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Server-value decoder — HAR-confirmed critical fix:
    //  Some base64 payloads wrap the iframe in a <div style="…">, e.g.
    //  <div style="position:relative"><iframe src="…"></iframe></div>.
    //  A naive `startsWith("<iframe")` check silently drops those (breaks DM).
    // ────────────────────────────────────────────────────────────────────────
    private fun decodeServerValue(raw: String): String? {
        val s = raw.trim()
        return when {
            s.startsWith("http") || s.startsWith("//") -> s
            s.startsWith("<", true) -> extractIframeSrc(s)
            else -> runCatching {
                val decoded = String(Base64.decode(s, Base64.DEFAULT)).trim()
                when {
                    decoded.startsWith("<", true) -> extractIframeSrc(decoded)
                    decoded.startsWith("http") || decoded.startsWith("//") -> decoded
                    else -> null
                }
            }.getOrNull()
        }
    }

    /** Parse any HTML fragment (<iframe>, <div><iframe>, etc.) and extract the src. */
    private fun extractIframeSrc(html: String): String? {
        if (!html.contains("<iframe", true)) return null
        return Jsoup.parse(html)
            .selectFirst("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
            ?.let {
                it.attr("data-litespeed-src")
                    .ifEmpty { it.attr("data-src") }
                    .ifEmpty { it.attr("src") }
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun isAdUrl(url: String): Boolean = listOf(
        "a-ads.com", "doubleclick", "googlesyndication", "googleadservices",
        "googletagservices", "adservice.google", "pubads", "adsystem",
        "dm-event.net", "live.rezync.com", "pippio.com",
        "static.cloudflareinsights.com", "mc.yandex.ru",
        "disqus.com", "accounts.google.com",
    ).any { url.contains(it, true) }

    private fun normalizeUrl(raw: String): String? {
        val s = raw.trim().replace("\\/", "/")
        if (s.isBlank()) return null
        return when {
            s.startsWith("//")   -> "https:$s"
            s.startsWith("/")    -> "$mainUrl$s"
            s.startsWith("http") -> s
            s.contains('.')      -> "https://$s"
            else                 -> null
        }
    }
}
