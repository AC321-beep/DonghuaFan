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

        private val COMING_SOON_TITLES = setOf(
            "fierce in the snow", "firece in the snow",
            "blazing through the snow"
        )
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
        if (request.data.contains("filter=comingsoon")) {
            val baseUrl = "$mainUrl/index.php/vod/show/id/20/by/time.html"
            val pageUrl = if (page == 1) baseUrl else "$baseUrl/page/$page.html"
            val doc = app.get(pageUrl).document
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
                val href = fixUrl(a.attr("href"))
                val title = a.attr("title").ifEmpty {
                    a.selectFirst("img")?.attr("alt") ?: a.text()
                }.trim()
                if (title.isEmpty()) return@mapNotNull null

                val cardText = a.text()
                val hasEpisodeNumber = Regex(
                    """EP\s*\d+|第\d+集|更新至\s*\d+|全集|已完结|\b\d{1,3}\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(cardText)

                val badgeElements = a.select(".public-list-prb, .status, .badge, .episode-badge, span")
                val hasNumericBadge = badgeElements.any { it.text().trim().matches(Regex("^\\d{1,3}$")) }

                if (hasEpisodeNumber || hasNumericBadge) return@mapNotNull null

                val badgeText = badgeElements.joinToString(" ") { it.text() }
                val isComingSoon = badgeText.contains("Trailer", ignoreCase = true) ||
                                   badgeText.contains("Coming Soon", ignoreCase = true) ||
                                   badgeText.contains("预告", ignoreCase = true) ||
                                   badgeText.contains("即将上线", ignoreCase = true) ||
                                   badgeText.contains("敬请期待", ignoreCase = true)

                if (!isComingSoon) return@mapNotNull null

                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }

                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        var page = 1
        while (true) {
            val url = "$mainUrl/index.php/vod/search.html"
            val doc = app.get(url, params = mapOf("wd" to query, "page" to page.toString())).document
            val cards = parseShowCards(doc)
            if (cards.isEmpty()) break
            results.addAll(cards)
            val nextPage = doc.selectFirst("a:contains(下一页), .page-next, .next")
            if (nextPage == null || nextPage.attr("href").isBlank()) break
            page++
            delay(500)
        }
        return results
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

        // 4K tab first
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
        }

        // Fallback to all play links (if 4K tab gave nothing)
        if (episodes.isEmpty()) {
            val allPlayLinks = doc.select("a[href*='/vod/play/id/$showId/']")
            for (a in allPlayLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                episodes.add(newEpisode(epUrl) { name = if (epName.isNotEmpty()) epName else "Episode" })
            }
        }

        // Deduplicate episodes by URL
        val uniqueEpisodes = episodes.distinctBy { it.url }

        val isComingSoonDetail = doc.select(".right p:contains(Coming Soon), .card-top .right p:contains(Coming Soon)").any()
                || doc.text().contains("Coming Soon", ignoreCase = true)
                || doc.text().contains("预告", ignoreCase = true)
                || doc.text().contains("即将上线", ignoreCase = true)

        val titleLower = title.lowercase()
        val isSpecialComingSoon = COMING_SOON_TITLES.any { titleLower.contains(it) }

        // If no episodes and it's a coming soon show, add a single trailer episode
        val finalEpisodes = if (uniqueEpisodes.isEmpty() && (isComingSoonDetail || isSpecialComingSoon)) {
            listOf(newEpisode("$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/1.html") { name = "Trailer" })
        } else {
            uniqueEpisodes
        }

        // No fake episode generation – if still empty, user sees nothing

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster?.let { fixUrl(it) }
            plot = description
            tags?.let { this.tags = it }
            year?.let { this.year = it }
            addEpisodes(DubStatus.None, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, headers = headers).document
        } catch (e: Exception) {
            null
        } ?: return false

        val scripts = doc.select("script").map { it.html() }.joinToString("\n")
        val playerJsonMatch = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(scripts)

        if (playerJsonMatch != null) {
            val playerJson = playerJsonMatch.groupValues[1]
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)
            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""

            if (from.equals("dailymotion", ignoreCase = true) || rawUrl?.contains("dailymotion") == true) {
                val dmId = extractDailymotionId(rawUrl ?: "")
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    return loadExtractor(videoUrl, data, subtitleCallback, callback)
                }
            } else if (!rawUrl.isNullOrBlank() && rawUrl.startsWith("http")) {
                callback(ExtractorLink(
                    source = name,
                    name = "CloudOKyo",
                    url = rawUrl,
                    referer = mainUrl,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                ))
                return true
            }
        }

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

        for (iframe in iframes) {
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && loadExtractor(src, data, subtitleCallback, callback)) return true
        }

        Log.d(TAG, "No playable source found for $data")
        return false
    }

    private fun extractDailymotionId(urlOrId: String): String? {
        if (urlOrId.matches(Regex("^[a-zA-Z0-9]{10,}$"))) return urlOrId
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
