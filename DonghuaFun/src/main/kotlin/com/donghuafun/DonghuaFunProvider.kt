package com.donghuafun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun (4K)"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val headers get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html?filter=comingsoon" to "Coming Soon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // For normal pages, pagination is handled by CloudStream (page parameter)
        if (request.data.contains("filter=comingsoon")) {
            // Extract base URL without filter param
            val baseUrl = "$mainUrl/index.php/vod/show/id/20/by/time.html"
            val pageUrl = if (page == 1) baseUrl else "$baseUrl/page/$page.html"
            val doc = app.get(pageUrl).document
            // Filter cards that are "Coming Soon" (only trailer, no episode numbers)
            val cards = parseComingSoonCards(doc)
            return newHomePageResponse(request.name, cards)
        }

        val pageUrl = if (page == 1) request.data
                      else request.data.replace(".html", "/page/$page.html")
        val doc = app.get(pageUrl).document
        val cards = parseShowCards(doc)
        return newHomePageResponse(request.name, cards)
    }

    private fun parseShowCards(doc: Document): List<SearchResponse> {
        return doc.select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val title = a.attr("title").ifEmpty {
                    a.selectFirst("img")?.attr("alt") ?: a.text()
                }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }

    private fun parseComingSoonCards(doc: Document): List<SearchResponse> {
        return doc.select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                // Check the badge that shows episode info
                val badge = a.selectFirst(".public-list-prb, .status, .badge, .episode-badge")
                val badgeText = badge?.text()?.trim() ?: ""
                
                // A show is "Coming Soon" if:
                // 1. Badge contains "Trailer" (case insensitive)
                // 2. Badge contains "Coming Soon"
                // 3. Badge is not empty and does NOT contain "EP" (episode number) AND does NOT contain "Part" (like "Part 01")
                //    and also not just numeric (like "01")
                val isComingSoon = badgeText.contains("Trailer", ignoreCase = true) ||
                                   badgeText.contains("Coming Soon", ignoreCase = true) ||
                                   (badgeText.isNotBlank() && 
                                    !badgeText.contains("EP", ignoreCase = true) &&
                                    !badgeText.contains("Part", ignoreCase = true) &&
                                    !badgeText.matches(Regex("\\d+")))
                
                if (!isComingSoon) return@mapNotNull null
                
                val href = fixUrl(a.attr("href"))
                val title = a.attr("title").ifEmpty {
                    a.selectFirst("img")?.attr("alt") ?: a.text()
                }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return parseShowCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val showId = detailUrlToId(url)

        val title = doc.selectFirst("h1, .video-title, .detail-title")?.text()?.trim()
            ?: doc.title().substringBefore(" Donghua").trim()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".detail-pic img, .video-cover img, .card-top img")?.attr("data-src")
            ?: doc.selectFirst("img.lazy")?.attr("data-src")

        val description = doc.selectFirst(".video-desc, .detail-desc, .card-text")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val tags = doc.select("a[href*='/class/']").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        // Extract episodes from the 4K tab or any play link
        val tabs = doc.select(".anthology-tab a.vod-playerUrl")
        val fourKTabIndex = tabs.indexOfFirst { it.text().contains("4K", ignoreCase = true) }
        val targetIndex = if (fourKTabIndex != -1) fourKTabIndex else 0
        val listContainers = doc.select(".anthology-list-box")
        if (targetIndex < listContainers.size) {
            val container = listContainers[targetIndex]
            val episodeLinks = container.select("a[href*='/vod/play/id/$showId/']")
            for (a in episodeLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                episodes.add(newEpisode(epUrl) { name = if (epName.isNotEmpty()) epName else "Episode" })
            }
            Log.d(TAG, "Found ${episodes.size} episodes from 4K tab")
        }

        if (episodes.isEmpty()) {
            val allPlayLinks = doc.select("a[href*='/vod/play/id/$showId/']")
            for (a in allPlayLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                episodes.add(newEpisode(epUrl) { name = if (epName.isNotEmpty()) epName else "Episode" })
            }
            Log.d(TAG, "Found ${episodes.size} episodes from global links")
        }

        // If no episodes and the page has "Coming Soon" text, add a trailer link
        val isComingSoonDetail = doc.select(".right p:contains(Coming Soon), .card-top .right p:contains(Coming Soon)").any()
                || doc.text().contains("Coming Soon", ignoreCase = true)

        if (episodes.isEmpty() && isComingSoonDetail) {
            Log.d(TAG, "Series is Coming Soon, adding trailer episode")
            val trailerUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/1.html"
            episodes.add(newEpisode(trailerUrl) { name = "Trailer" })
        }

        // Only generate fake episodes for regular series (not Coming Soon) with no episodes
        if (episodes.isEmpty() && !isComingSoonDetail) {
            Log.d(TAG, "No episodes found and not Coming Soon – generating range 1..300")
            for (n in 1..300) {
                val epUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html"
                episodes.add(newEpisode(epUrl) { name = "EP$n" })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster?.let { fixUrl(it) }
            plot = description
            tags?.let { this.tags = it }
            year?.let { this.year = it }
            addEpisodes(DubStatus.None, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Fetch the play page
        val doc = try {
            app.get(data, headers = headers).document
        } catch (e: Exception) {
            null
        }

        if (doc == null) return false

        // 1. Look for player_aaaa JSON (contains direct .m3u8 or Dailymotion info)
        val scripts = doc.select("script").map { it.html() }.joinToString("\n")
        val playerJsonMatch = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(scripts)
        
        if (playerJsonMatch != null) {
            val playerJson = playerJsonMatch.groupValues[1]
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)
            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
            
            // If it's Dailymotion
            if (from.equals("dailymotion", ignoreCase = true) || rawUrl?.contains("dailymotion") == true) {
                val dmId = extractDailymotionId(rawUrl ?: "")
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    return loadExtractor(videoUrl, data, subtitleCallback, callback)
                }
            }
            // If it's a direct .m3u8 URL
            else if (!rawUrl.isNullOrBlank() && rawUrl.startsWith("http")) {
                val extLink = ExtractorLink(
                    source = name,
                    name = "CloudOKyo",
                    url = rawUrl,
                    referer = mainUrl,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                )
                callback(extLink)
                return true
            }
        }

        // 2. Look for Dailymotion iframes
        val iframes = doc.select("iframe[src]")
        for (iframe in iframes) {
            val src = fixUrl(iframe.attr("src"))
            if (src.contains("dailymotion")) {
                val dmId = extractDailymotionId(src)
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
                }
            }
        }

        // 3. Try any iframe that might be a player (fallback)
        for (iframe in iframes) {
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && loadExtractor(src, data, subtitleCallback, callback)) {
                return true
            }
        }

        Log.d(TAG, "No playable source found for $data")
        return false
    }

    private fun extractDailymotionId(urlOrId: String): String? {
        // If it's already just an ID (alphanumeric, length > 10)
        if (urlOrId.matches(Regex("^[a-zA-Z0-9]{10,}$"))) return urlOrId
        // Extract from URL
        val patterns = listOf(
            Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)"""),
            Regex("""dailymotion\.com/video/([a-zA-Z0-9]+)""")
        )
        for (pattern in patterns) {
            pattern.find(urlOrId)?.let {
                return it.groupValues[1]
            }
        }
        return null
    }
}
