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
                // Get source name (e.g., "4K", "1080P ENG", "1080P Indo")
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
            // Fallback: parse all episode links
            doc.select("a[href*='/vod/play/id/$showId/']").forEach { a ->
                val epHref = fixUrl(a.attr("href"))
                val epNum = a.text().trim().ifEmpty { 
                    Regex("""nid/(\d+)""").find(epHref)?.groupValues?.get(1)?.let { "EP$it" } ?: "Episode"
                }
                episodes.add(newEpisode(epHref) { name = epNum })
            }
        }

        // Sort episodes by episode number (ascending)
        episodes.sortBy { ep ->
            Regex("""EP(\d+)""").find(ep.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }

        return newAnimeLoadResponse(title ?: name, url, TvType.Anime) {
            posterUrl = poster?.let { fixUrl(it) }
            plot = description
            tags?.let { this.tags = it }
            year?.let { this.year = it }
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

        // Fetch the play page HTML
        val html = try {
            app.get(data, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch page: ${e.message}")
            return false
        }

        // ─── Strategy 1: Extract player_aaaa JSON (Primary method) ───────────────
        // This works for ALL sources (1, 2, and 3)
        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        
        if (playerJson != null) {
            Log.d(TAG, "Found player_aaaa JSON for source $sid")
            if (extractFromPlayerJson(playerJson, data, headers, callback)) {
                return true
            }
        }

        // ─── Strategy 2: Look for iframes (Fallback) ────────────────────────────
        val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+dailymotion\.com[^"']+)["']""", RegexOption.IGNORE_CASE)
        iframePattern.find(html)?.let { match ->
            val iframeUrl = match.groupValues[1].replace("&amp;", "&")
            Log.d(TAG, "Found iframe: $iframeUrl")
            return extractDailymotion(iframeUrl, callback)
        }

        // ─── Strategy 3: Look for direct video sources (Fallback) ────────────────
        val videoPattern = Regex("""<video[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        videoPattern.find(html)?.let { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            Log.d(TAG, "Found direct video: $videoUrl")
            callback(createDirectLink(videoUrl, data, headers))
            return true
        }

        Log.w(TAG, "No video source found for sid=$sid, nid=$nid")
        return false
    }

    // ─── Extract from player_aaaa JSON ─────────────────────────────────────────

    private suspend fun extractFromPlayerJson(
        json: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Extract the URL field
        val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: return false
        
        // Extract encryption type (if any)
        val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        // Extract the source type (dailymotion, etc.)
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""

        // Decode the URL if encrypted
        val decodedUrl = decodeUrl(rawUrl, encryptType)
        
        Log.d(TAG, "Decoded URL: from=$from, url=$decodedUrl")

        // Route to appropriate extractor
        return when {
            decodedUrl.contains("dailymotion") || from.contains("dailymotion", ignoreCase = true) -> {
                extractDailymotion(decodedUrl, callback)
            }
            decodedUrl.contains(".m3u8") -> {
                callback(createDirectLink(decodedUrl, referer, headers))
                true
            }
            decodedUrl.contains(".mp4") -> {
                callback(createDirectLink(decodedUrl, referer, headers, isM3u8 = false))
                true
            }
            else -> {
                // Try generic extractor as last resort
                loadExtractor(decodedUrl, referer) { link ->
                    callback(link)
                }
                true
            }
        }
    }

    // ─── Dailymotion Extractor ─────────────────────────────────────────────────

    private suspend fun extractDailymotion(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Extract video ID from various Dailymotion URL formats
        val videoId = Regex("""dailymotion\.com/(?:video/|embed/video/|embed/)([a-zA-Z0-9]+)""")
            .find(url)?.groupValues?.get(1)
            ?: Regex("""^([a-zA-Z0-9]+)$""").find(url)?.groupValues?.get(1)
            ?: run {
                Log.e(TAG, "Could not extract Dailymotion video ID from: $url")
                return false
            }

        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://www.dailymotion.com/",
            "Origin" to "https://www.dailymotion.com"
        )

        Log.d(TAG, "Fetching Dailymotion embed: $embedUrl")

        val html = try {
            app.get(embedUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "Dailymotion fetch failed: ${e.message}")
            return false
        }

        // Try to extract all available quality streams
        val qualityPatterns = mapOf(
            "1080" to Regex(""""1080":\s*\{\s*"(?:auto|en|fr)":\s*"([^"]+\.m3u8[^"]*)"""", RegexOption.DOT_MATCHES_ALL),
            "720" to Regex(""""720":\s*\{\s*"(?:auto|en|fr)":\s*"([^"]+\.m3u8[^"]*)"""", RegexOption.DOT_MATCHES_ALL),
            "480" to Regex(""""480":\s*\{\s*"(?:auto|en|fr)":\s*"([^"]+\.m3u8[^"]*)"""", RegexOption.DOT_MATCHES_ALL),
            "380" to Regex(""""380":\s*\{\s*"(?:auto|en|fr)":\s*"([^"]+\.m3u8[^"]*)"""", RegexOption.DOT_MATCHES_ALL),
            "240" to Regex(""""240":\s*\{\s*"(?:auto|en|fr)":\s*"([^"]+\.m3u8[^"]*)"""", RegexOption.DOT_MATCHES_ALL)
        )

        var found = false
        for ((quality, pattern) in qualityPatterns) {
            pattern.find(html)?.let { match ->
                val m3u8 = match.groupValues[1].replace("\\/", "/")
                val qualityValue = when (quality) {
                    "1080" -> Qualities.P1080.value
                    "720" -> Qualities.P720.value
                    "480" -> Qualities.P480.value
                    "380" -> Qualities.P360.value
                    else -> Qualities.P240.value
                }
                
                callback(newExtractorLink(
                    source = name,
                    name = "$name ${quality}p",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8,
                    quality = qualityValue,
                    headers = headers
                ) {
                    this.referer = "https://www.dailymotion.com/"
                })
                found = true
            }
        }

        // Fallback: try to find any m3u8 URL in the page
        if (!found) {
            val anyM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.value?.replace("\\/", "/")
            if (anyM3u8 != null) {
                callback(newExtractorLink(name, name, anyM3u8, ExtractorLinkType.M3U8) {
                    this.referer = "https://www.dailymotion.com/"
                    this.quality = Qualities.P720.value
                    this.headers = headers
                })
                found = true
            }
        }

        return found
    }

    // ─── Helper Functions ──────────────────────────────────────────────────────

    private fun createDirectLink(
        url: String,
        referer: String,
        headers: Map<String, String>,
        isM3u8: Boolean = true
    ): ExtractorLink {
        return newExtractorLink(
            source = name,
            name = if (isM3u8) "$name HLS" else name,
            url = url,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            quality = Qualities.P1080.value,
            headers = headers
        ) {
            this.referer = referer
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
            Log.e(TAG, "URL decode failed: ${e.message}")
            raw.replace("\\/", "/")
        }
    }

    // ─── Document Helper ───────────────────────────────────────────────────────

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
                
                newAnimeSearchResponse(title, href, TvType.Anime) { 
                    this.posterUrl = poster 
                }
            }
    }
}
