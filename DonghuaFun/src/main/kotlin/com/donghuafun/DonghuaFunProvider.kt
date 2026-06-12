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
        return Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""
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
            if (items.isEmpty()) currentPage++ 
        }

        return newHomePageResponse(request.name, items, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val doc = try {
                app.get("$mainUrl/index.php/vod/search.html", params = mapOf("wd" to query, "page" to page.toString())).document
            } catch (e: Exception) { null } ?: break

            val pageResults = parseShowCards(doc)
            if (pageResults.isEmpty()) break
            
            results.addAll(pageResults)
            if (doc.select("a.page-next:not(.disabled), a:contains(Next), a:contains(下一页)").isEmpty()) break
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showId = detailUrlToId(url)

        val title = doc.selectFirst("h1, .video-title, .detail-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.title().substringBefore(" Donghua").trim()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst(".detail-pic img, .video-cover img, .card-top img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("img.lazy")?.attr("data-
