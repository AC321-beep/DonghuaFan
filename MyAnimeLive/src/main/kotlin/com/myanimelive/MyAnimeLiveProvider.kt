package com.myanimelive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class MyAnimeLiveProvider : MainAPI() {
    override var mainUrl = "https://myanime.live"
    override var name = "MyAnimeLive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "MyAnimeLive"
    }

    override val mainPage = mainPageOf(mainUrl to "Latest Series")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(pageUrl).document
        val articles = doc.select("article")
        if (articles.isEmpty()) {
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val seriesMap = mutableMapOf<String, SearchResponse>()
        articles.forEach { article ->
            val titleLink = article.selectFirst("h2.entry-header-title a")
            val title = titleLink?.text()?.trim() ?: return@forEach
            val seriesName = extractSeriesName(title)
            if (seriesName.isBlank()) return@forEach

            val poster = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            val encodedName = URLEncoder.encode(seriesName, "UTF-8")
            val seriesUrl = "$mainUrl/?s=$encodedName"

            if (!seriesMap.containsKey(seriesUrl)) {
                val response = newAnimeSearchResponse(seriesName, seriesUrl, TvType.Anime)
                response.posterUrl = poster
                seriesMap[seriesUrl] = response
            }
        }

        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(request.name, seriesMap.values.toList(), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val doc = app.get(searchUrl).document

        val noResults = doc.selectFirst(".no-results, .nothing-found, .search-no-results")
        if (noResults != null) return emptyList()

        var articles = doc.select("article")
        if (articles.isEmpty()) {
            articles = doc.select(".post, .entry, li.type-post")
        }
        if (articles.isEmpty()) {
            val fallbackLinks = doc.select("a[href*='/20']")
            return fallbackLinks.mapNotNull { link ->
                val url = fixUrl(link.attr("href"))
                val title = link.text().trim()
                if (title.isNotBlank() && url.contains("/20")) {
                    val seriesName = extractSeriesName(title)
                    newAnimeSearchResponse(seriesName, url, TvType.Anime)
                } else null
            }.distinctBy { it.url }
        }

        val seriesMap = mutableMapOf<String, SearchResponse>()
        articles.forEach { article ->
            val titleLink = article.selectFirst("h2.entry-header-title a, h2 a, .entry-title a")
            val title = titleLink?.text()?.trim() ?: return@forEach
            val seriesName = extractSeriesName(title)
            if (seriesName.isBlank()) return@forEach

            val poster = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            val encodedName = URLEncoder.encode(seriesName, "UTF-8")
            val seriesUrl = "$mainUrl/?s=$encodedName"

            if (!seriesMap.containsKey(seriesUrl)) {
                val response = newAnimeSearchResponse(seriesName, seriesUrl, TvType.Anime)
                response.posterUrl = poster
                seriesMap[seriesUrl] = response
            }
        }
        return seriesMap.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        if (url.contains("?s=")) {
            val seriesName = doc.selectFirst("h1.page-header-title span")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.substringBefore(" - ") ?: "Unknown Series"
            val poster = doc.selectFirst("article img")?.attr("src")?.let { fixUrl(it) }

            val allEpisodes = mutableListOf<Episode>()
            var currentUrl = url
            while (currentUrl.isNotBlank()) {
                val pageDoc = app.get(currentUrl).document
                val episodes = pageDoc.select("article").mapNotNull { article ->
                    val link = article.selectFirst("h2.entry-header-title a")
                    val epUrl = link?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
                    val epTitle = link.text().trim()
                    val epNum = extractEpisodeNumber(epTitle)
                    newEpisode(epUrl) {
                        name = if (epNum != null) "Episode $epNum" else epTitle
                        episode = epNum
                        posterUrl = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    }
                }
                allEpisodes.addAll(episodes)

                val nextLink = pageDoc.selectFirst("a.next.page-numbers")?.attr("href")
                currentUrl = if (nextLink != null && nextLink != currentUrl) fixUrl(nextLink) else ""
            }
            val sortedEpisodes = allEpisodes.sortedBy { it.episode ?: Int.MAX_VALUE }
            return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
                addEpisodes(DubStatus.None, sortedEpisodes)
                posterUrl = poster
            }
        } else {
            val title = doc.selectFirst("h1.entry-header-title")?.text()?.trim() ?: "Episode"
            val seriesName = extractSeriesName(title).ifBlank { "Unknown Series" }
            val epNum = extractEpisodeNumber(title)
            val poster = doc.selectFirst("article img")?.attr("src")?.let { fixUrl(it) }
            val episode = newEpisode(url) {
                name = if (epNum != null) "Episode $epNum" else title
                episode = epNum
                posterUrl = poster
            }
            return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
                addEpisodes(DubStatus.None, listOf(episode))
                posterUrl = poster
            }
        }
    }

    private fun extractSeriesName(title: String): String {
        var name = title
            .substringBefore(" episode", missingDelimiterValue = title)
            .substringBefore(" Episode", missingDelimiterValue = title)
            .replace(Regex("""\s+[Ee]p(?:isode)?\.?\s*\d+.*$"""), "")
            .trim()
        name = Regex("""\s+english\s+sub$""", RegexOption.IGNORE_CASE).replace(name, "")
        if (name.isBlank() || name.length < 3) {
            name = title.split(Regex("[-–:]"))[0].trim()
        }
        return name
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""(?:episode|ep|ep\.)\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""#(\d+)""")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
    }

    // --------------------------------------------------------------
    // loadLinks: direct call to custom YouTube extractor (Option A)
    // --------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1) Direct handling of YouTube URLs using our custom extractor
        if (data.contains("youtube.com/watch") || data.contains("youtu.be/") || data.contains("/shorts/")) {
            val ytExtractor = YoutubeExtractor()
            return ytExtractor.getUrl(data, null, subtitleCallback, callback)
        }

        // 2) For other sources (Dailymotion, ok.ru, etc.) use the normal extractor chain
        val doc = app.get(data).document
        val html = doc.html()

        suspend fun tryLoad(url: String): Boolean {
            return loadExtractor(url, data, subtitleCallback, callback)
        }

        // Check iframes
        for (iframe in doc.select("iframe")) {
            val src = iframe.attr("src")
            when {
                src.contains("dailymotion.com") -> {
                    val videoId = Regex("""[?&]video=([a-zA-Z0-9]+)""").find(src)?.groupValues?.get(1)
                    if (videoId != null) return tryLoad("https://www.dailymotion.com/video/$videoId")
                    return tryLoad(src)
                }
                src.contains("ok.ru") -> return tryLoad(src)
                src.contains("streamtape") || src.contains("mp4upload") -> return tryLoad(src)
                // YouTube iframes are already handled above – skip them here
            }
        }

        // Direct Dailymotion links
        val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")
        if (dmLink != null) return tryLoad(dmLink)

        // Dailymotion ID via regex
        val dmId = Regex("""dailymotion\.com/video/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
        if (dmId != null) return tryLoad("https://www.dailymotion.com/video/$dmId")

        // ok.ru direct links
        val okLink = doc.selectFirst("a[href*='ok.ru/video/']")?.attr("href")
        if (okLink != null) return tryLoad(okLink)

        Log.d(TAG, "No supported video source found for $data")
        return false
    }
}
