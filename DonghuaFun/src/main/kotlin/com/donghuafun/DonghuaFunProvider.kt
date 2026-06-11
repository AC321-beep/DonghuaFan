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

    // Regular show cards (used for main page & search)
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

    // Coming Soon parser: only "coming soon" badge, no episode numbers, no movie/date
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

                // 1. Episode numbers (EP40, EP40[END], 第1集, 更新至, etc.)
                val hasEpisodeNumber = Regex(
                    """EP\s*\d+(\s*\[END\])?|第\d+集|更新至\s*\d+|全集|已完结""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(cardText)
                if (hasEpisodeNumber) return@mapNotNull null

                // 2. Badge elements
                val badgeElements = a.select(".public-list-prb, .status, .badge, .episode-badge, span, .tag, .remark, .label")
                val badgeText = badgeElements.joinToString(" ") { it.text() }
                val combinedText = "$badgeText $cardText"

                // Exclude "movie" / "电影"
                val hasMovieIndicator = Regex("""movie|电影""", RegexOption.IGNORE_CASE).containsMatchIn(combinedText)
                if (hasMovieIndicator) return@mapNotNull null

                // Exclude dates (numeric and month‑name formats)
                val hasDate = Regex(
                    """\d{4}[./-]\d{1,2}[./-]\d{1,2}|""" +  // 2026-06-13
                    """(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{1,2},?\s*\d{4}|""" + // Jun 13,2026
                    """\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4}""",     // 13 Jun 2026
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(combinedText)
                if (hasDate) return@mapNotNull null

                // 3. Only accept explicit "coming soon" badge (case‑insensitive, allows spaces)
                val isComingSoon = Regex("""coming\s*soon""", RegexOption.IGNORE_CASE).containsMatchIn(badgeText)
                if (!isComingSoon) return@mapNotNull null

                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }

                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }

    // Search with pagination
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

        // Fallback to all play links
        if (episodes.isEmpty()) {
            val allPlayLinks = doc.select("a[href*='/vod/play/id/$showId/']")
            for (a in allPlayLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                episodes.add(newEpisode(epUrl) { name = if (epName.isNotEmpty()) epName else "Episode" })
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }

        // Coming Soon detection on detail page
        val isComingSoonDetail = doc.select(".right p:contains(Coming Soon), .card-top .right p:contains(Coming Soon)").any()
                || doc.text().contains("Coming Soon", ignoreCase = true)
                || doc.text().contains("Not yet Aired", ignoreCase = true)

        // If no episodes and it's a coming soon show, add a trailer episode
        val finalEpisodes = if (uniqueEpisodes.isEmpty() && isComingSoonDetail) {
            listOf(newEpisode("$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/1.html") { name = "Trailer" })
        } else {
            uniqueEpisodes
        }

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

        // Look for player_aaaa JSON
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
