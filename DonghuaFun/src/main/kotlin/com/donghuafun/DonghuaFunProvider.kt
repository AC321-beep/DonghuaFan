package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder

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

    private fun detailUrlToId(url: String): String {
        val match = Regex("/id/(\\d+)\\.html").find(url)
        return match?.groupValues?.get(1) ?: ""
    }

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
            
            if (doc.select("a[href*='/vod/detail/id/']").isEmpty()) {
                hasNextPage = false
                break
            }

            items.addAll(parseShowCards(doc, isComingSoon))
            pagesSearched++
            if (items.isEmpty()) {
                currentPage++ 
            }
        }

        return newHomePageResponse(request.name, items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            var doc: Document? = null
            try {
                doc = app.get("$mainUrl/index.php/vod/search.html", params = mapOf("wd" to query, "page" to page.toString())).document
            } catch (e: Exception) { 
                break 
            }

            if (doc == null) break

            val pageResults = parseShowCards(doc, false)
            if (pageResults.isEmpty()) break
            
            results.addAll(pageResults)
            if (doc.select("a.page-next:not(.disabled), a:contains(Next), a:contains(下一页)").isEmpty()) break
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showId = detailUrlToId(url)

        var title = doc.selectFirst("h1, .video-title, .detail-title")?.text()?.trim() ?: ""
        if (title.isEmpty()) {
            title = doc.title().replace(" Donghua", "").trim()
        }

        var poster = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        if (poster.isEmpty()) {
            poster = doc.selectFirst(".detail-pic img, .video-cover img, .card-top img")?.attr("data-src") ?: ""
        }
        if (poster.isEmpty()) {
            poster = doc.selectFirst("img.lazy")?.attr("data-src") ?: ""
        }

        var description = doc.selectFirst(".video-desc, .detail-desc, .card-text")?.text()?.trim() ?: ""
        if (description.isEmpty()) {
            description = doc.selectFirst("meta[name='description']")?.attr("content") ?: ""
        }

        val tags = doc.select("a[href*='/class/']").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
        val yearText = doc.selectFirst("a[href*='/year/']")?.text()
        val year = yearText?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val tabs = doc.select(".anthology-tab a.vod-playerUrl")
        
        val fourKTabIndex = tabs.indexOfFirst { it.text().contains("4K", ignoreCase = true) }
        val targetIndex = if (fourKTabIndex != -1) fourKTabIndex else 0

        val listContainers = doc.select(".anthology-list-box")
        if (targetIndex < listContainers.size) {
            val container = listContainers[targetIndex]
            val episodeMap = mutableMapOf<Int, Episode>()

            for (a in container.select("a[href*='/vod/play/id/$showId/']")) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNumber = parseEpisodeNumber(epName)
                
                val finalNumber = if (epNumber > 0) epNumber else (episodeMap.size + 1)
                
                if (!episodeMap.containsKey(finalNumber)) {
                    episodeMap[finalNumber] = newEpisode(epUrl) { 
                        name = if (epName.isNotEmpty()) epName else "Episode $finalNumber"
                        episode = finalNumber
                    }
                }
            }
            episodes.addAll(episodeMap.toSortedMap().values)
        }

        if (episodes.isEmpty() && showId.isNotEmpty()) {
            for (n in 1..300) {
                episodes.add(newEpisode("$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html") { name = "EP$n" })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            if (poster.isNotEmpty()) {
                this.posterUrl = fixUrl(poster)
            }
            this.plot = description
            this.tags = tags
            this.year = year
            addEpisodes(DubStatus.None, episodes)
        }
    }

    private fun parseEpisodeNumber(name: String): Int {
        val match = Regex("(\\d+)").find(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
