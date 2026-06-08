package com.donghuafun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // ── URL Helpers ────────────────────────────────────────────────────────────

    private fun getShowId(url: String): String {
        Regex("""/id/(\d+)\.html""").find(url)?.let { return it.groupValues[1] }
        return ""
    }

    private fun getSidNid(url: String): Triple<Int, Int, String> {
        val showId = getShowId(url)
        val sid = Regex("""/sid/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val nid = Regex("""/nid/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return Triple(sid, nid, showId)
    }

    // ── Home Page ──────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/type/id/20.html"         to "Latest Donghua",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data
                      else request.data.replace(".html", "/page/$page.html")
        
        val doc = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return newHomePageResponse(request.name, doc.parseShowCards())
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query),
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document
        return doc.parseShowCards()
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val showId = getShowId(url)

        val title = doc.selectFirst("h1, .video-title")?.text()?.trim()
            ?: doc.title().substringBefore(" - Donghua").substringBefore(" Donghua").trim()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".detail-pic img, .video-cover img")?.attr("data-src")

        val description = doc.selectFirst(".video-desc, .detail-desc, p.desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val tags = doc.select("a[href*='/class/']").map { it.text().trim() }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        // Parse episodes from the anthology tabs (Source 1, 2, 3)
        val sourceTabs = doc.select(".anthology-tab .vod-playerUrl")
        val episodeContainers = doc.select(".anthology-list-box")
        
        if (sourceTabs.isNotEmpty() && episodeContainers.isNotEmpty()) {
            sourceTabs.forEachIndexed { idx, tab ->
                val sourceName = tab.ownText().trim().ifEmpty { 
                    tab.text().trim()
                }.replace(Regex("""\d+"""), "").trim().ifEmpty { "Source ${idx + 1}" }
                
                val container = episodeContainers.getOrNull(idx)
                container?.select("li a")?.forEach { a ->
                    val epHref = fixUrl(a.attr("href"))
                    val epNum = a.select("span").first()?.text()?.trim() ?: a.text().trim()
                    if (epHref.isNotEmpty() && epNum.isNotEmpty()) {
                        episodes.add(newEpisode(epHref) {
                            name = "$epNum [$sourceName]"
                        })
                    }
                }
            }
        } else {
            doc.select("a[href*='/vod/play/id/$showId/']").forEach { a ->
                val epHref = fixUrl(a.attr("href"))
                val epNum = a.text().trim().ifEmpty { 
                    Regex("""nid/(\d+)""").find(epHref)?.groupValues?.get(1)?.let { "EP$it" } ?: "Episode"
                }
                episodes.add(newEpisode(epHref) {
                    name = epNum
                })
            }
        }

        episodes.sortBy { episode ->
            val name = episode.name ?: ""
            Regex("""EP(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }

        return newAnimeLoadResponse(title ?: name, url, TvType.Anime) {
            posterUrl = poster?.let { fixUrl(it) }
            plot = description
            this.tags = tags
            this.year = year
            addEpisodes(DubStatus.None, episodes)
        }
    }

    // ── Link Extraction ────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (sid, nid, showId) = getSidNid(data)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to data,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        Log.d(TAG, "Loading: showId=$showId, sid=$sid, nid=$nid")

        val html = try {
            app.get(data, headers = headers).document
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch page: ${e.message}")
            return false
        }

        // Strategy 1: Extract player_aaaa JSON
        val scriptWithPlayer = html.select("script").firstOrNull { it.data().contains("player_aaaa") }
        if (scriptWithPlayer != null) {
            val scriptContent = scriptWithPlayer.data()
            val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptContent)?.groupValues?.get(1)
            if (playerJson != null) {
                if (extractFromPlayerJson(playerJson, data, headers, subtitleCallback, callback)) {
                    return true
                }
            }
        }

        // Strategy 2: iframe
        val iframe = html.select("iframe[src*='dailymotion']").firstOrNull()
        if (iframe != null) {
            return extractDailymotion(iframe.attr("src"), callback)
        }

        // Strategy 3: video source
        val videoSource = html.select("video source, video").firstOrNull()
        if (videoSource != null) {
            val videoUrl = videoSource.attr("src").ifEmpty { videoSource.attr("data-src") }
            if (videoUrl.isNotEmpty()) {
                callback(createExtractorLink(videoUrl, data, headers))
                return true
            }
        }

        return false
    }

    private suspend fun extractFromPlayerJson(
        json: String,
        referer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: return false
        val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""

        val decodedUrl = decodeUrl(rawUrl, encryptType)
        Log.d(TAG, "Decoded URL: from=$from, url=$decodedUrl")

        return when {
            decodedUrl.contains("dailymotion") || from.contains("dailymotion", ignoreCase = true) -> {
                extractDailymotion(decodedUrl, callback)
            }
            decodedUrl.contains(".m3u8") -> {
                callback(createExtractorLink(decodedUrl, referer, headers))
                true
            }
            decodedUrl.contains(".mp4") -> {
                callback(createExtractorLink(decodedUrl, referer, headers))
                true
            }
            else -> {
                loadExtractor(decodedUrl, referer, subtitleCallback, callback)
            }
        }
    }

    private suspend fun extractDailymotion(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val videoId = Regex("""dailymotion\.com/(?:video/|embed/video/|embed/)([a-zA-Z0-9]+)""")
            .find(url)?.groupValues?.get(1)
            ?: Regex("""^([a-zA-Z0-9]+)$""").find(url)?.groupValues?.get(1)
            ?: return false

        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://www.dailymotion.com/"
        )

        val html = try {
            app.get(embedUrl, headers = headers).text
        } catch (e: Exception) {
            return false
        }

        val qualities = listOf("1080", "720", "480", "380", "240")
        var found = false
        for (quality in qualities) {
            val pattern = Regex(""""$quality":\s*\{\s*"(?:auto|en|fr)":\s*"([^"]+\.m3u8[^"]*)"""")
            pattern.find(html)?.let { match ->
                val m3u8 = match.groupValues[1].replace("\\/", "/")
                val link = newExtractorLink(
                    name,
                    "$name $quality",
                    m3u8,
                    ExtractorLinkType.M3U8,
                    when (quality) {
                        "1080" -> 1080
                        "720" -> 720
                        "480" -> 480
                        "380" -> 380
                        else -> 240
                    }
                ).apply {
                    this.referer = "https://www.dailymotion.com/"
                    this.headers = headers
                }
                callback(link)
                found = true
            }
        }

        if (!found) {
            val anyM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.value?.replace("\\/", "/")
            if (anyM3u8 != null) {
                val link = newExtractorLink(name, name, anyM3u8, ExtractorLinkType.M3U8, 720).apply {
                    this.referer = "https://www.dailymotion.com/"
                    this.headers = headers
                }
                callback(link)
                found = true
            }
        }
        return found
    }

    private fun createExtractorLink(url: String, referer: String, headers: Map<String, String>): ExtractorLink {
        return newExtractorLink(
            name,
            name,
            url,
            if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            1080
        ).apply {
            this.referer = referer
            this.headers = headers
        }
    }

    private fun decodeUrl(raw: String, encryptType: Int): String {
        return try {
            val decoded = when (encryptType) {
                1 -> java.net.URLDecoder.decode(raw, "UTF-8")
                2 -> raw.reversed()
                3 -> String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
                else -> raw
            }
            decoded.replace("\\/", "/")
        } catch (e: Exception) {
            raw.replace("\\/", "/")
        }
    }

    private fun Document.parseShowCards(): List<SearchResponse> {
        return select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                if (href.isEmpty()) return@mapNotNull null
                val title = a.attr("title").ifEmpty {
                    a.selectFirst("img")?.attr("alt") ?: a.text()
                }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.let { if (it.startsWith("data:")) null else fixUrl(it) }
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }
}
