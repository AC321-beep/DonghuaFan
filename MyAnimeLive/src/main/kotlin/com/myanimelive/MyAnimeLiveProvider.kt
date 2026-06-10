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

    // Use the homepage (recent posts) to extract unique series
    override val mainPage = mainPageOf(
        mainUrl to "Latest Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val seriesMap = mutableMapOf<String, SearchResponse>()

        doc.select("article").forEach { article ->
            val titleElem = article.selectFirst("h2.entry-title a")
            val title = titleElem?.text()?.trim() ?: return@forEach

            // Extract series name: remove episode info
            var seriesName = title
                .substringBefore(" episode", missingDelimiterValue = title)
                .substringBefore(" Episode", missingDelimiterValue = title)
                .substringBefore("- episode", missingDelimiterValue = title)
                .substringBefore("- Episode", missingDelimiterValue = title)

            // Remove any remaining "episode 123" or "EP 123" using regex
            val episodePattern = Regex("""\s+[Ee]p(?:isode)?\s+\d+.*$""")
            seriesName = episodePattern.replace(seriesName, "").trim()

            // If title contains " - ", take the part before it
            if (seriesName.contains(" - ")) {
                seriesName = seriesName.substringBefore(" - ").trim()
            }

            if (seriesName.isBlank()) return@forEach

            val slug = seriesName.lowercase().replace(" ", "-")
            val seriesUrl = "$mainUrl/tag/$slug/"

            if (!seriesMap.containsKey(seriesUrl)) {
                seriesMap[seriesUrl] = newAnimeSearchResponse(seriesName, seriesUrl, TvType.Anime)
            }
        }

        if (seriesMap.isEmpty()) {
            Log.d(TAG, "No series extracted from homepage, falling back to archive scan")
            return fallbackToArchiveScan()
        }

        return newHomePageResponse(request.name, seriesMap.values.toList())
    }

    private suspend fun fallbackToArchiveScan(): HomePageResponse {
        val doc = app.get(mainUrl).document
        val fallbackList = doc.select("article h2.entry-title a").mapNotNull { link ->
            val url = fixUrl(link.attr("href"))
            val title = link.text().trim()
            val seriesName = title.substringBefore(" episode").substringBefore(" Episode").trim()
            if (seriesName.isNotBlank()) {
                newAnimeSearchResponse(seriesName, url, TvType.Anime)
            } else null
        }.distinctBy { it.url }
        return newHomePageResponse("Latest Series (fallback)", fallbackList)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        if (url.contains("/tag/")) {
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
            val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: "Episode"
            val seriesName = title.substringBefore(" episode").substringBefore(" Episode").trim()
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

        val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")
        if (dmLink != null) {
            return loadExtractor(dmLink, data, subtitleCallback, callback)
        }

        val iframeSrc = doc.selectFirst("iframe[src*='dailymotion.com']")?.attr("src")
        if (iframeSrc != null) {
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

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
