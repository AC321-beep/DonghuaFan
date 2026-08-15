package com.donghuaworld

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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
    override var lang = "zh"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    // ---------- MAIN PAGE CATEGORIES ----------
    // Based on actual filter URLs from the site's HTML
    override val mainPage = mainPageOf(
        "" to "Recently Updated",                       // homepage
        "anime/?status=ongoing&order=latest" to "Ongoing",
        "anime/?status=completed&order=update" to "Completed",
        "anime/?type=movie&order=update" to "Movies",
        "anime/?type=ona&order=update" to "Donghua (ONA)",
        "anime/?type=comic&order=update" to "Comic",
        "anime/?order=popular" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isBlank()) {
            mainUrl
        } else {
            // If the URL contains '?', we need to add &page=; if not, we add ?page=
            val base = "$mainUrl/${request.data}"
            if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
        }
        Log.i("DonghuaWorld", "Fetching main page: $url")
        val document = app.get(url).document

        // Exact selector from HTML: all article.bs (both slider and normal list)
        val items = document.select("article.bs")
        Log.i("DonghuaWorld", "Found ${items.size} items")

        val home = items.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    // ---------- SEARCH ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        val url = "$mainUrl/page/$page/?s=${query.replace(" ", "+")}"
                        Log.i("DonghuaWorld", "Searching: $url")
                        val document = app.get(url).document
                        document.select("article.bs").mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) {
                        Log.e("DonghuaWorld", "Search error: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Find the anchor inside the card
        val linkElement = this.selectFirst("a[href*=/anime/], a[href*=/donghua/], a[href*=/movie/], a[href*=/comic/]")
            ?: this.selectFirst("a") // fallback
            ?: return null

        val title = linkElement.attr("title").ifEmpty { linkElement.text() }
            .ifEmpty { this.selectFirst(".tt")?.text() } ?: return null
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

        val isMovie = document.selectFirst(".eplister, .episodelist, .episodes-list") == null &&
                document.selectFirst("a[href*=/episode-]") == null

        if (isMovie) {
            val href = document.selectFirst("a[href*=/watch], a[href*=/episode-]")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
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

                newEpisode(href) {
                    this.name = episodeText.takeIf { it.isNotEmpty() } ?: "Episode $epNum"
                    this.posterUrl = poster
                    this.episode = epNum.toInt()
                }
            }.distinctBy { it.data }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
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

        // 1. Primary: div.server-item a with data-hash (from decompiled code)
        val serverItems = document.select("div.server-item a")
        Log.i("DonghuaWorld", "Found ${serverItems.size} server items")

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            val finalUrl = fixUrl(iframeUrl)
            Log.i("DonghuaWorld", "Routing -> $finalUrl | Label -> $label")

            when {
                "rumble.com" in finalUrl -> Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "player.donghuaplanet.com" in finalUrl -> Donghuaplanet().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "player.donghuaworld.in" in finalUrl -> PlayerDonghuaworld().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                else -> loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, callback)
            }
        }

        serverItems.forEach { item ->
            val base64 = item.attr("data-hash")
            if (base64.isNotBlank()) {
                val decodedHtml = try { String(Base64.decode(base64, Base64.DEFAULT)) } catch (e: Exception) { "" }
                val regex = Regex("src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val match = regex.find(decodedHtml)
                val iframeUrl = match?.groupValues?.get(1)
                if (!iframeUrl.isNullOrBlank()) {
                    invokeExtractor(iframeUrl, item.text().trim())
                }
            }
        }

        // 2. Fallback: dropdown options (like Animekhor)
        if (serverItems.isEmpty()) {
            val serverOptions = document.select(".mobius option, select.mirror option")
            Log.i("DonghuaWorld", "Found ${serverOptions.size} server options")

            serverOptions.forEach { option ->
                val base64 = option.attr("value")
                if (base64.isNotBlank()) {
                    val decodedHtml = try { String(Base64.decode(base64, Base64.DEFAULT)) } catch (e: Exception) { "" }
                    val iframeSrc = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
                    if (!iframeSrc.isNullOrBlank()) {
                        invokeExtractor(iframeSrc, option.text().trim())
                    }
                }
            }
        }

        // 3. Last resort: direct iframes
        if (serverItems.isEmpty()) {
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                    invokeExtractor(src, "Direct")
                }
            }
        }

        // 4. Regex fallback
        val pageHtml = document.toString()
        val regex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
        regex.find(pageHtml)?.groupValues?.get(1)?.let { directUrl ->
            loadExtractor(directUrl, referer = mainUrl, subtitleCallback, callback)
        }

        return true
    }
}
