package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class DonghuastreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "DonghuaStream"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "anime/?status=&type=&order=update&page=" to "Special Edition"
    )

    // ---------- getMainPage (unchanged for Recently Updated) ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSpecialEdition = request.name == "Special Edition"

        // --- Recently Updated: keep original logic exactly ---
        if (!isSpecialEdition) {
            var currentPage = page
            val items = mutableListOf<SearchResponse>()
            var hasNextPage = true
            val maxPagesToSearch = 1 // as per original code
            var pagesSearched = 0

            while (items.isEmpty() && hasNextPage && pagesSearched < maxPagesToSearch) {
                val url = "$mainUrl/${request.data}$currentPage"
                val document = try { app.get(url).document } catch (e: Exception) { null }
                val elements = document?.select("div.listupd > article")
                if (elements.isNullOrEmpty()) {
                    hasNextPage = false
                    break
                }
                val mappedItems = elements.mapNotNull { it.toSearchResult() }
                items.addAll(mappedItems)
                pagesSearched++
                if (items.isEmpty()) currentPage++
            }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = hasNextPage
            )
        }

        // --- Special Edition: new logic using search and type filtering ---
        val items = mutableListOf<SearchResponse>()
        var currentPage = page
        var hasNextPage = true
        val maxPages = 3 // search up to 3 pages

        while (items.isEmpty() && hasNextPage && currentPage <= maxPages) {
            val searchUrl = "$mainUrl/?s=movie&page=$currentPage"
            val document = try {
                app.get(searchUrl).document
            } catch (_: Exception) { null }

            val elements = document?.select("div.listupd > article")
            if (elements.isNullOrEmpty()) {
                hasNextPage = false
                break
            }

            val mapped = elements.mapNotNull { element ->
                val typez = element.selectFirst("div.typez")?.text()?.trim() ?: ""
                if (typez.equals("Movie", ignoreCase = true) ||
                    typez.equals("Special", ignoreCase = true) ||
                    typez.equals("ONA", ignoreCase = true)) {
                    element.toSearchResult()
                } else null
            }

            items.addAll(mapped)
            currentPage++
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = false // we limit pages, so no further pages
        )
    }

    // ---------- Helpers (unchanged) ----------
    fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").ifEmpty { this.text() }
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("div.bsx a img")?.getImageAttr())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("data-src")
            this.hasAttr("src") -> this.attr("src")
            else -> this.attr("src")
        }
    }

    // ---------- Search (unchanged) ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("${mainUrl}/pagg/$i/?s=$query").document
            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    // ---------- Load Episode / Movie (unchanged) ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
        var poster = document.select("div.ime > img").attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().toString()
        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val epPage = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
            val doc = app.get(epPage).document
            val episodes = doc.select("div.episodelist > ul > li").map { info ->
                val href1 = info.select("a").attr("href")
                val episode = info.select("a span").text().substringAfter("-").substringBeforeLast("-")
                val posterr = info.selectFirst("a img")?.attr("data-src") ?: ""
                newEpisode(href1) {
                    this.name = episode.replace(title, "", ignoreCase = true)
                    this.episode = episode.toIntOrNull()
                    this.posterUrl = posterr
                }
            }
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ---------- Load Links (added fallback for direct iframes) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document
        var linksFound = false

        // 1) Try mirror select (original logic)
        val options = doc.select("option[data-index]")
        if (options.isNotEmpty()) {
            options.amap { option ->
                val base64 = option.attr("value")
                if (base64.isBlank()) return@amap
                val label = option.text().trim()
                val decodedHtml = try {
                    base64Decode(base64)
                } catch (_: Exception) {
                    Log.w("Error", "Base64 decode failed: $base64")
                    return@amap
                }

                val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")?.let(::httpsify)
                if (iframeUrl.isNullOrEmpty()) return@amap

                processIframeUrl(iframeUrl, label, data, subtitleCallback, callback)
                linksFound = true
            }
        }

        // 2) If no links found yet, look for a direct iframe (NEW fallback)
        if (!linksFound) {
            val directIframe = doc.selectFirst(
                "#embed_holder iframe, .player-embed iframe, .video-content iframe, iframe[src*='play.streamplay.co.in']"
            )
            if (directIframe != null) {
                val iframeUrl = httpsify(directIframe.attr("src"))
                if (!iframeUrl.isNullOrEmpty()) {
                    processIframeUrl(iframeUrl, "Direct Source", data, subtitleCallback, callback)
                    linksFound = true
                }
            }
        }

        return linksFound
    }

    // ---------- Helper: process iframe URL ----------
    private suspend fun processIframeUrl(
        iframeUrl: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            label.contains("dailymotion", ignoreCase = true) || "dailymotion" in iframeUrl -> {
                Extractor().getUrl(iframeUrl, referer, subtitleCallback, callback)
            }
            "rumble.com" in iframeUrl -> {
                Rumble().getUrl(iframeUrl, referer, subtitleCallback, callback)
            }
            "play.streamplay.co.in" in iframeUrl -> {
                PlayStreamplay().getUrl(iframeUrl, referer, subtitleCallback, callback)
            }
            "vidmoly" in iframeUrl -> {
                val cleanedUrl = "http:" + iframeUrl.substringAfter("=\"").substringBefore("\"")
                loadExtractor(cleanedUrl, referer = referer, subtitleCallback, callback)
            }
            iframeUrl.endsWith(".mp4") -> {
                callback(
                    newExtractorLink(label, label, iframeUrl, INFER_TYPE) {
                        this.referer = referer
                        this.quality = getQualityFromName(label)
                    }
                )
            }
            else -> {
                loadExtractor(iframeUrl, referer = referer, subtitleCallback, callback)
            }
        }
    }

    // ---------- Utility ----------
    private fun base64Decode(str: String): String {
        return String(Base64.decode(str, Base64.DEFAULT))
    }
}
