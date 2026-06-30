package com.myanimelive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

            val encodedName = URLEncoder.encode(seriesName, "UTF-8")
            val seriesUrl = "$mainUrl/?s=$encodedName"

            if (!seriesMap.containsKey(seriesUrl)) {
                val poster = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
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

        val seriesMap = mutableMapOf<String, SearchResponse>()
        articles.forEach { article ->
            val titleLink = article.selectFirst("h2.entry-header-title a, h2 a, .entry-title a")
            val title = titleLink?.text()?.trim() ?: return@forEach
            val seriesName = extractSeriesName(title)
            if (seriesName.isBlank()) return@forEach

            val encodedName = URLEncoder.encode(seriesName, "UTF-8")
            val seriesUrl = "$mainUrl/?s=$encodedName"

            if (!seriesMap.containsKey(seriesUrl)) {
                val poster = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
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
            val pagesToFetch = mutableListOf(url)
            
            doc.select("a.page-numbers").forEach { 
                val href = it.attr("href")
                if (href.isNotBlank() && !pagesToFetch.contains(href)) {
                    pagesToFetch.add(fixUrl(href))
                }
            }

            // Safe, non-blocking parallel execution using modern coroutines
            coroutineScope {
                pagesToFetch.map { pageUrl ->
                    async {
                        val pageDoc = app.get(pageUrl).document
                        val episodes = pageDoc.select("article").mapNotNull { article ->
                            val link = article.selectFirst("h2.entry-header-title a")
                            val epUrl = link?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
                            val epTitle = link.text().trim()
                            
                            val epNum = extractEpisodeNumber(epTitle)
                            val seasonNum = extractSeasonNumber(epTitle)

                            newEpisode(epUrl) {
                                name = if (epNum != null) "Episode $epNum" else epTitle
                                episode = epNum
                                season = seasonNum
                                posterUrl = article.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                            }
                        }
                        synchronized(allEpisodes) {
                            allEpisodes.addAll(episodes)
                        }
                    }
                }.awaitAll()
            }

            // Fixed: distinctBy checks the proper Episode field 'data', then passes cleanly to sorting loops
            val uniqueEpisodes = allEpisodes.distinctBy { it.data }
            val sortedEpisodes = uniqueEpisodes.sortedWith(
                compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE }
            )

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

    private fun extractSeasonNumber(text: String): Int {
        val sMatch = Regex("""(?:season|s)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        if (sMatch != null) return sMatch.groupValues[1].toIntOrNull() ?: 1
        
        val ordinalMatch = Regex("""(\d+)(?:st|nd|rd|th)\s*season""", RegexOption.IGNORE_CASE).find(text)
        if (ordinalMatch != null) return ordinalMatch.groupValues[1].toIntOrNull() ?: 1
        
        return 1
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var linksLoaded = false

        fun fixUrlIfRelative(url: String): String {
            return if (url.startsWith("//")) "https:$url" else url
        }

        suspend fun handleGeneric(rawUrl: String) {
            val fullUrl = fixUrlIfRelative(rawUrl)
            val success = loadExtractor(fullUrl, data, subtitleCallback, callback)
            if (success) {
                linksLoaded = true
            }
        }

        val iframes = doc.select("iframe")
        coroutineScope {
            iframes.map { iframe ->
                async {
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) {
                        val fixedSrc = fixUrlIfRelative(src)
                        when {
                            fixedSrc.contains("dailymotion.com") -> {
                                val videoId = Regex("""[?&]video=([a-zA-Z0-9]+)""").find(fixedSrc)?.groupValues?.get(1)
                                handleGeneric(videoId?.let { "https://www.dailymotion.com/video/$it" } ?: fixedSrc)
                            }
                            fixedSrc.contains("youtube.com/embed/") || 
                            fixedSrc.contains("youtu.be") || 
                            fixedSrc.contains("ok.ru") || 
                            fixedSrc.contains("streamtape") || 
                            fixedSrc.contains("mp4upload") -> {
                                handleGeneric(fixedSrc)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return linksLoaded
    }
}
