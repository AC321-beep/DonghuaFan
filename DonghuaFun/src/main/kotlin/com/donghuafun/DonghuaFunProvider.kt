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
        private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "coming_soon_filter" to "Coming Soon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "coming_soon_filter") {
            val doc = app.get("$mainUrl/index.php/vod/show/id/20/by/time.html").document
            val cards = parseShowCardsWithFilter(doc, onlyComingSoon = true)
            return newHomePageResponse(request.name, cards)
        }

        val pageUrl = if (page == 1) request.data
                      else request.data.replace(".html", "/page/$page.html")
        val doc = app.get(pageUrl).document
        val cards = parseShowCardsWithFilter(doc, onlyComingSoon = false)
        return newHomePageResponse(request.name, cards)
    }

    private fun parseShowCardsWithFilter(doc: Document, onlyComingSoon: Boolean): List<SearchResponse> {
        return doc.select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                if (onlyComingSoon && !a.text().contains("Coming Soon", ignoreCase = true)) {
                    val badge = a.selectFirst(".public-list-prb, .status, .badge")
                    if (badge == null || !badge.text().contains("Coming Soon", ignoreCase = true)) {
                        return@mapNotNull null
                    }
                }
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
        return parseShowCardsWithFilter(doc, onlyComingSoon = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
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

        // Episode extraction from tabs
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

        // Fallback: any play link on the page
        if (episodes.isEmpty()) {
            val allPlayLinks = doc.select("a[href*='/vod/play/id/$showId/']")
            for (a in allPlayLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                episodes.add(newEpisode(epUrl) { name = if (epName.isNotEmpty()) epName else "Episode" })
            }
            Log.d(TAG, "Found ${episodes.size} episodes from global play links")
        }

        val isComingSoon = doc.select(".right p:contains(Coming Soon), .card-top .right p:contains(Coming Soon)").any()
                || doc.text().contains("Coming Soon", ignoreCase = true)

        // For Coming Soon shows with no episodes, add a trailer
        if (episodes.isEmpty() && isComingSoon) {
            Log.d(TAG, "Series is Coming Soon, looking for trailer")
            val trailerUrl = extractTrailerPlayUrl(doc, showId)
            if (trailerUrl != null) {
                episodes.add(newEpisode(trailerUrl) { name = "Trailer" })
                Log.d(TAG, "Added trailer episode")
            } else {
                Log.d(TAG, "No trailer found – leaving episode list empty")
            }
        }

        // Only generate fake episodes if not Coming Soon and still no episodes
        if (episodes.isEmpty() && !isComingSoon) {
            Log.d(TAG, "No episodes found and not Coming Soon – generating numeric range 1..300")
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

    private fun extractTrailerPlayUrl(doc: Document, showId: String): String? {
        val html = doc.html()
        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (playerJson != null) {
            val epUrl = Regex(""""link"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)
            if (!epUrl.isNullOrBlank()) {
                return fixUrl(epUrl)
            }
        }
        return "$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/1.html"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)

        val html = try { app.get(data, headers = headers).text } catch (e: Exception) { "" }

        // Extract direct .m3u8 link from player_aaaa JSON
        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (playerJson != null) {
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)
            if (!rawUrl.isNullOrBlank() && rawUrl.startsWith("http")) {
                val extLink = ExtractorLink(
                    source = name,
                    name = "CloudOKyo",
                    url = rawUrl,
                    referer = mainUrl,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8
                )
                callback(extLink)
                return true
            }
        }

        // Fallback: look for any iframe that might embed the video (Dailymotion, etc.)
        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src]")?.forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank()) {
                if (loadExtractor(src, data, subtitleCallback, callback)) return true
            }
        }

        Log.d(TAG, "No playable source found for $data")
        return false
    }
}
