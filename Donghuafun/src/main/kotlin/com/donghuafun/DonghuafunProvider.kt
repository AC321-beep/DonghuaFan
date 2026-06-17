package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "Donghuafun (4K)"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "Donghuafun"
        // Desktop User-Agent to bypass mobile scraper blocks
        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    // Reverted EXACTLY to your original working order
    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Coming Soon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isComingSoon = request.name == "Coming Soon"
        var currentPage = page
        val items = mutableListOf<SearchResponse>()
        var hasNextPage = true
        
        val maxPagesToSearch = if (isComingSoon) 5 else 1 
        var pagesSearched = 0

        while (items.isEmpty() && hasNextPage && pagesSearched < maxPagesToSearch) {
            val pageUrl = if (currentPage == 1) request.data else request.data.replace(".html", "/page/$currentPage.html")
            val doc = app.get(pageUrl).document
            val elements = doc.select("a[href*='/vod/detail/id/']")
            if (elements.isEmpty()) { hasNextPage = false; break }
            items.addAll(parseShowCards(doc, isComingSoon))
            pagesSearched++
            if (items.isEmpty()) currentPage++ 
        }
        return newHomePageResponse(request.name, items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val doc = try { app.get("$mainUrl/index.php/vod/search.html", params = mapOf("wd" to query, "page" to page.toString())).document } catch (e: Exception) { null } ?: break
            val pageResults = parseShowCards(doc)
            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
            val hasNext = doc.select("a.page-next:not(.disabled), a:contains(Next), a:contains(下一页)").isNotEmpty()
            if (!hasNext) break
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showId = detailUrlToId(url)

        val title = doc.selectFirst("h1, .video-title, .detail-title")?.text()?.trim() ?: doc.title().substringBefore(" Donghua").trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: doc.selectFirst(".detail-pic img, .video-cover img, .card-top img")?.attr("data-src") ?: doc.selectFirst("img.lazy")?.attr("data-src")
        val description = doc.selectFirst(".video-desc, .detail-desc, .card-text")?.text()?.trim() ?: doc.selectFirst("meta[name='description']")?.attr("content")
        val tags = doc.select("a[href*='/class/']").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val tabs = doc.select(".anthology-tab a.vod-playerUrl")
        val fourKTabIndex = tabs.indexOfFirst { it.text().contains("4K", ignoreCase = true) }
        val targetIndex = if (fourKTabIndex != -1) fourKTabIndex else 0

        val listContainers = doc.select(".anthology-list-box")
        if (targetIndex < listContainers.size) {
            val container = listContainers[targetIndex]
            val episodeLinks = container.select("a[href*='/vod/play/id/$showId/']")
            val episodeMap = mutableMapOf<Int, Episode>()

            for (a in episodeLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNumber = parseEpisodeNumber(epName)
                val finalNumber = if (epNumber > 0) epNumber else episodeMap.size + 1
                
                if (!episodeMap.containsKey(finalNumber)) {
                    episodeMap[finalNumber] = newEpisode(epUrl) { 
                        name = epName.ifEmpty { "Episode $finalNumber" }; episode = finalNumber
                    }
                }
            }
            episodes.addAll(episodeMap.toSortedMap().values)
        }

        if (episodes.isEmpty() && showId.isNotEmpty()) {
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

    private fun parseEpisodeNumber(name: String): Int {
        return Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailPageUrl = data
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to detailPageUrl, "Origin" to mainUrl)
        val html = try { app.get(detailPageUrl, headers = headers).text } catch (e: Exception) { "" }
        val doc = try { app.get(detailPageUrl, headers = headers).document } catch (e: Exception) { null }

        // --- Dailymotion Logic Restored ---
        var dailymotionToken: String? = null
        doc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
            val src = iframe.attr("src")
            val match = Regex("""[?&]video=([^&]+)""").find(src)
            if (match != null) {
                dailymotionToken = match.groupValues[1]
                return@forEach
            }
        }
        if (dailymotionToken != null) {
            val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$dailymotionToken"
            if (loadExtractor(embedUrl, detailPageUrl, subtitleCallback, callback)) return true
        }

        // --- Main Player Logic ---
        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return false

        var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
        val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
        else if (encrypt == 2) {
            rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
            rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
        }

        if (from.equals("dailymotion", ignoreCase = true)) {
            val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$rawUrl"
            if (loadExtractor(embedUrl, detailPageUrl, subtitleCallback, callback)) return true
        } 
        else if (rawUrl.isNotEmpty()) {
            if (rawUrl.contains("url=")) {
                rawUrl = rawUrl.substringAfter("url=")
                rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
            }

            val isM3u8 = rawUrl.contains(".m3u8", ignoreCase = true)
            val streamHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://donghuafun.com/",
                "Origin" to "https://donghuafun.com"
            )

            if (isM3u8) {
                val extractorUrl = if (rawUrl.startsWith("http")) {
                    "https://play.donghuafun.com/m3u8/?url=$rawUrl"
                } else rawUrl

                if (loadExtractor(extractorUrl, "https://donghuafun.com/", subtitleCallback, callback)) {
                    return true
                }
            } else {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = from.ifEmpty { "Server 1" },
                        url = rawUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = streamHeaders
                        this.referer = "https://donghuafun.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }
        return false
    }

    private fun parseShowCards(doc: Document, isComingSoon: Boolean = false): List<SearchResponse> {
        return doc.select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .filter { a -> 
                if (!isComingSoon) { true } else {
                    val parent1 = a.parent()
                    val parent2 = a.parent()?.parent()
                    val parent3 = a.parent()?.parent()?.parent()

                    val container = when {
                        parent3 != null && parent3.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent3
                        parent2 != null && parent2.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent2
                        parent1 != null && parent1.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent1
                        else -> a
                    }
                    val cardText = container.text()
                    val keywords = listOf("trailer", "coming soon", "not yet aired", "upcoming", "releasing soon", "0 episode")
                    keywords.any { keyword -> cardText.contains(keyword, ignoreCase = true) }
                }
            }
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val title = a.attr("title").ifEmpty { a.selectFirst("img")?.attr("alt") ?: a.text() }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val poster = a.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }
}
