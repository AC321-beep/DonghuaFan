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
        // Construct paginated URL: page 1 -> mainUrl, page 2 -> mainUrl/page/2/
        val pageUrl = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(pageUrl).document
        
        // If no articles found, we've reached the end
        val articles = doc.select("article")
        if (articles.isEmpty()) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        
        val seriesMap = mutableMapOf<String, SearchResponse>()
        
        articles.forEach { article ->
            val titleLink = article.selectFirst("h2.entry-header-title a")
            val title = titleLink?.text()?.trim() ?: return@forEach
            
            // Extract series name by removing episode info
            var seriesName = title
                .substringBefore(" episode", missingDelimiterValue = title)
                .substringBefore(" Episode", missingDelimiterValue = title)
                .replace(Regex("""\s+[Ee]p(?:isode)?\.?\s*\d+.*$"""), "")
                .trim()
            seriesName = Regex("""\s+english\s+sub$""", RegexOption.IGNORE_CASE).replace(seriesName, "")
            
            if (seriesName.isBlank() || seriesName.length < 3) {
                seriesName = title.split(Regex("[-–:]"))[0].trim()
            }
            if (seriesName.isBlank()) return@forEach
            
            // Extract poster from thumbnail
            val poster = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            
            // Use search URL to list all episodes of this series
            val encodedName = URLEncoder.encode(seriesName, "UTF-8")
            val seriesUrl = "$mainUrl/?s=$encodedName"
            
            if (!seriesMap.containsKey(seriesUrl)) {
                val response = newAnimeSearchResponse(seriesName, seriesUrl, TvType.Anime)
                response.posterUrl = poster
                seriesMap[seriesUrl] = response
            }
        }
        
        // Check if there is a next page (pagination)
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        
        return newHomePageResponse(request.name, seriesMap.values.toList(), hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        Log.d(TAG, "Loading URL: $url")

        if (url.contains("?s=")) {
            // Search results page – fetch all pages for episodes
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
                
                // Find next page link
                val nextLink = pageDoc.selectFirst("a.next.page-numbers")?.attr("href")
                currentUrl = if (nextLink != null && nextLink != currentUrl) fixUrl(nextLink) else ""
            }
            
            val sortedEpisodes = allEpisodes.sortedBy { it.episode ?: Int.MAX_VALUE }
            return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
                addEpisodes(DubStatus.None, sortedEpisodes)
                posterUrl = poster
            }
        } else {
            // Single episode page
            val title = doc.selectFirst("h1.entry-header-title")?.text()?.trim() ?: "Episode"
            var seriesName = title
                .substringBefore(" episode", missingDelimiterValue = title)
                .substringBefore(" Episode", missingDelimiterValue = title)
                .replace(Regex("""\s+[Ee]p(?:isode)?\.?\s*\d+.*$"""), "")
                .trim()
            seriesName = Regex("""\s+english\s+sub$""", RegexOption.IGNORE_CASE).replace(seriesName, "")
            if (seriesName.isBlank()) seriesName = "Unknown Series"
            
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

    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""(?:episode|ep|ep\.)\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""#(\d+)""")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val html = doc.html()

        // Try to find Dailymotion iframe
        val iframe = doc.selectFirst("iframe[src*='dailymotion.com']")?.attr("src")
        if (iframe != null) {
            val videoId = Regex("""[?&]video=([a-zA-Z0-9]+)""").find(iframe)?.groupValues?.get(1)
            if (videoId != null) {
                return loadExtractor("https://www.dailymotion.com/video/$videoId", data, subtitleCallback, callback)
            }
        }

        // Fallback: direct Dailymotion link
        val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")
        if (dmLink != null) {
            return loadExtractor(dmLink, data, subtitleCallback, callback)
        }

        // Regex fallback
        val dmId = Regex("""dailymotion\.com/video/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
        if (dmId != null) {
            return loadExtractor("https://www.dailymotion.com/video/$dmId", data, subtitleCallback, callback)
        }

        Log.d(TAG, "No Dailymotion source found for $data")
        return false
    }
}
