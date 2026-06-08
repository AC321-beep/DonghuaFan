package com.donghuafun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun (4K only)"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
        private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // -------------------------------------------------------------------
    //  URL helpers
    // -------------------------------------------------------------------
    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    // -------------------------------------------------------------------
    //  Main page
    // -------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/type/id/20.html"         to "Trending Donghua",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data
                     else request.data.replace(".html", "/page/$page.html")
        val doc = app.get(pageUrl).document
        return newHomePageResponse(request.name, parseShowCards(doc))
    }

    // -------------------------------------------------------------------
    //  Search
    // -------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return parseShowCards(doc)
    }

    // -------------------------------------------------------------------
    //  Detail – only 4K (Dailymotion) source, deduplicated
    // -------------------------------------------------------------------
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

        // --- Extract only the 4K (Dailymotion) source episodes ---
        val episodes = mutableListOf<Episode>()

        // Find the 4K tab (usually "4K" or data-form="dailymotion")
        val fourKTab = doc.select(".anthology-tab a.vod-playerUrl").firstOrNull { tab ->
            tab.text().contains("4K", ignoreCase = true) ||
            tab.attr("data-form").equals("dailymotion", ignoreCase = true)
        } ?: doc.select(".anthology-tab a.vod-playerUrl").firstOrNull() // fallback to first tab

        if (fourKTab != null) {
            val serverId = fourKTab.attr("data-form")
            val listDiv = doc.selectFirst(".anthology-list-box[data-form='$serverId']")
                ?: doc.select(".anthology-list-box").firstOrNull()

            val episodeLinks = listDiv?.select("a[href*='/vod/play/id/$showId/']") ?: emptyList()
            val episodeMap = mutableMapOf<Int, Episode>()

            for (a in episodeLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNumber = parseEpisodeNumber(epName)
                if (epNumber > 0 && !episodeMap.containsKey(epNumber)) {
                    val episode = newEpisode(epUrl) { name = "EP$epNumber" }
                    episodeMap[epNumber] = episode
                }
            }

            episodes.addAll(episodeMap.toSortedMap().values)
        }

        // Fallback: if no 4K source found, generate numeric episodes using sid=1 (assumed 4K)
        if (episodes.isEmpty() && showId.isNotEmpty()) {
            val epCountText = doc.selectFirst(".video-info-main em, .detail-status")?.text() ?: ""
            val epCount = Regex("""EP(\d+)""").find(epCountText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            for (n in 1..epCount) {
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

    private fun parseEpisodeNumber(name: String): Int {
        val match = Regex("""EP(\d+)""", RegexOption.IGNORE_CASE).find(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    // -------------------------------------------------------------------
    //  Link extraction – Dailymotion only
    // -------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)

        // 1) Try to get Dailymotion ID from player_aaaa JSON
        val html = try { app.get(data, headers = headers).text } catch (e: Exception) { "" }
        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)
            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
            
            if (from.equals("dailymotion", ignoreCase = true) || rawUrl?.contains("dailymotion") == true) {
                val dmId = extractDailymotionId(rawUrl ?: "")
                if (dmId != null) {
                    val embedUrl = "https://www.dailymotion.com/embed/video/$dmId"
                    return loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
        }

        // 2) Search for any Dailymotion iframe
        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
            val src = iframe.attr("src")
            val dmId = extractDailymotionId(src)
            if (dmId != null) {
                val embedUrl = "https://www.dailymotion.com/embed/video/$dmId"
                return loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }

        // 3) Fallback: regex on entire HTML
        val dmIdMatch = Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)""").find(html)
        if (dmIdMatch != null) {
            val dmId = dmIdMatch.groupValues[1]
            val embedUrl = "https://www.dailymotion.com/embed/video/$dmId"
            return loadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        Log.d(TAG, "No Dailymotion source found for $data")
        return false
    }

    private fun extractDailymotionId(urlOrId: String): String? {
        if (urlOrId.matches(Regex("^[a-zA-Z0-9]{15,}$"))) return urlOrId
        val pattern = Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)""")
        return pattern.find(urlOrId)?.groupValues?.get(1)
    }

    // -------------------------------------------------------------------
    //  Card parser
    // -------------------------------------------------------------------
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
}
