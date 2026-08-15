package com.donghuaworld

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DonghuaWorldProvider : MainAPI() {
    override var mainUrl = "https://donghuaworld.com"
    override var name = "Donghua World"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    // ---------- MAIN PAGE CATEGORIES ----------
    override val mainPage = mainPageOf(
        "page/1/" to "Recently Updated",
        "category/comic-recently-updated/page/1/" to "Comic Recently Updated",
        "category/comic-series/page/1/" to "Comic Series",
        "category/donghua-recently-updated/page/1/" to "Donghua Recently Updated",
        "category/donghua-series/page/1/" to "Donghua Series",
        "category/latest-added/page/1/" to "Latest Added",
        "category/popular/page/1/" to "Popular",
        "category/completed/page/1/" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}".replace("/page/1/", "/page/$page/")
        val document = app.get(url).document

        val home = document.select("div.listupd > article, div.bsx, div.post-item, div.entry")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    // ---------- SEARCH ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        val document = app.get("$mainUrl/page/$page/?s=${query.replace(" ", "+")}").document
                        document.select("div.listupd > article, div.bsx, div.post-item, div.entry")
                            .mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    // ---------- SEARCH RESULT PARSER ----------
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href*=/anime/], a[href*=/donghua/], a[href*=/movie/], a[href*=/comic/]") ?: return null
        val title = linkElement.attr("title").ifEmpty { linkElement.text() }.ifEmpty { this.selectFirst(".tt")?.text() } ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }.ifEmpty { img.attr("data-lazy-src") }
            }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    // ---------- LOAD (EPISODES / MOVIE) ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("img.wp-post-image")?.attr("abs:src") ?: ""
        val description = document.selectFirst("div.entry-content, div.description, div.summary")?.text()?.trim()

        // Check if it's a movie (no episode list)
        val isMovie = document.selectFirst(".eplister, .episodelist, .episodes-list") == null &&
                document.selectFirst("a[href*=/episode-]") == null

        if (isMovie) {
            val href = document.selectFirst("a[href*=/watch], a[href*=/episode-]")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Episode list with multiple fallback selectors
            var epListElements = document.select(".episodelist li, .eplister li, .episodes-list li, .eplist li")
            if (epListElements.isEmpty()) {
                val epPage = document.selectFirst(".episodelist li > a, .eplister li > a")?.attr("href") ?: ""
                if (epPage.isNotBlank()) {
                    val doc = app.get(epPage).document
                    epListElements = doc.select(".episodelist li, .eplister li, .episodes-list li")
                }
            }

            val episodes = epListElements.mapNotNull { info ->
                val href = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val episodeText = info.selectFirst(".epl-title, .ep-title, a span")?.text()
                    ?: info.selectFirst("a")?.text() ?: ""
                val epNum = Regex("""(?:Episode|EP|E)?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(episodeText)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

                // ***** FIXED: Correct newEpisode usage *****
                newEpisode(
                    name = episodeText.takeIf { it.isNotEmpty() } ?: "Episode $epNum",
                    url = href
                ) {
                    this.posterUrl = poster
                    this.episode = epNum.toInt()
                }
            }.distinctBy { it.data }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.status = when {
                    document.selectFirst("span:contains(Completed), .status:contains(Complete)") != null -> ShowStatus.Completed
                    document.selectFirst("span:contains(Ongoing), .status:contains(Releasing)") != null -> ShowStatus.Ongoing
                    else -> ShowStatus.Unknown
                }
            }
        }
    }

    // ---------- LOAD LINKS (VIDEO EXTRACTION) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Primary method: server items with Base64-encoded iframe data
        val serverItems = document.select("div.server-item a")
        Log.i("DonghuaWorld", "Found ${serverItems.size} server items")

        suspend fun processServerItem(element: Element) {
            val base64 = element.attr("data-hash")
            if (base64.isNotBlank()) {
                val decodedHtml = try { String(Base64.decode(base64, Base64.DEFAULT)) } catch (e: Exception) { "" }
                val regex = Regex("src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val matchResult = regex.find(decodedHtml)
                val iframeUrl = matchResult?.groupValues?.get(1)
                if (!iframeUrl.isNullOrBlank()) {
                    val finalUrl = fixUrl(iframeUrl)
                    Log.i("DonghuaWorld", "Extracted iframe URL: $finalUrl")
                    // Route Rumble to its custom extractor, everything else to the universal one
                    if ("rumble.com" in finalUrl) {
                        Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    } else {
                        loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        serverItems.forEach { processServerItem(it) }

        // 2. Fallback: direct iframes on the page
        if (serverItems.isEmpty()) {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                    if ("rumble.com" in src) {
                        Rumble().getUrl(src, mainUrl, subtitleCallback, callback)
                    } else {
                        loadExtractor(src, referer = mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        // 3. Last resort: regex for direct video URLs
        if (serverItems.isEmpty()) {
            val pageHtml = document.toString()
            val regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
            val directUrl = regex.find(pageHtml)?.groupValues?.get(1)
            if (directUrl != null) {
                // ***** FIXED: Correct ExtractorLink constructor *****
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = directUrl,
                        referer = mainUrl,
                        quality = 720,
                        type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4
                    )
                )
            }
        }

        return true
    }
}
