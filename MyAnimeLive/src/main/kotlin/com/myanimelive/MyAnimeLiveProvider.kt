package com.myanimelive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

    override val mainPage = mainPageOf(
        "$mainUrl/post-sitemap.xml" to "All Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val seriesMap = mutableMapOf<String, SearchResponse>()

        doc.select("loc").forEach { loc ->
            val url = loc.text()
            // Match series slug from URL like https://myanime.live/series-name/episode-123/
            val match = Regex("""$mainUrl/([^/]+)/episode-\d+/?""").find(url)
            if (match != null) {
                val slug = match.groupValues[1]
                val seriesName = slug.replace("-", " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                if (!seriesMap.containsKey(slug)) {
                    // Use the tag page URL if it exists, otherwise first episode
                    val seriesUrl = "$mainUrl/tag/$slug/"
                    seriesMap[slug] = newAnimeSearchResponse(seriesName, seriesUrl, TvType.Anime)
                }
            }
        }

        return newHomePageResponse(request.name, seriesMap.values.toList())
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        if (url.contains("/tag/")) {
            // Tag page: list all episodes from this tag
            val seriesName = doc.selectFirst("h1.page-title")?.text()
                ?.replace("Tag: ", "")?.trim() ?: "Unknown Series"

            val episodes = doc.select("article").mapNotNull { article ->
                val link = article.selectFirst("h2.entry-title a")
                val epUrl = link?.attr("href")?.let { fixUrl(it) } ?: return@mapNotNull null
                val epTitle = link.text().trim()
                val epNum = extractEpisodeNumber(epTitle)
                newEpisode(epUrl) {
                    name = if (epNum != null) "Episode $epNum" else epTitle
                    episode = epNum
                }
            }.sortedBy { it.episode }

            return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
                addEpisodes(DubStatus.None, episodes)
            }
        } else {
            // Single episode page – treat as series with one episode
            val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "Episode"
            val seriesName = title.substringBefore(" Episode").trim()
            val epNum = extractEpisodeNumber(title)
            val episode = newEpisode(url) {
                name = if (epNum != null) "Episode $epNum" else title
                episode = epNum
            }
            return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
                addEpisodes(DubStatus.None, listOf(episode))
            }
        }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            Regex("""(?:episode|ep|ep\.)\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // 1. Direct Dailymotion link
        val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")
        if (dmLink != null) {
            return loadExtractor(dmLink, data, subtitleCallback, callback)
        }

        // 2. Dailymotion iframe
        val iframeSrc = doc.selectFirst("iframe[src*='dailymotion.com']")?.attr("src")
        if (iframeSrc != null) {
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        // 3. Regex fallback
        val html = doc.html()
        val dmId = Regex("""dailymotion\.com/video/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
        if (dmId != null) {
            val videoUrl = "https://www.dailymotion.com/video/$dmId"
            return loadExtractor(videoUrl, data, subtitleCallback, callback)
        }

        Log.d(TAG, "No Dailymotion source found for $data")
        return false
    }
}
