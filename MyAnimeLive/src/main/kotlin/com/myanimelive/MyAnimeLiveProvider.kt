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

    // Main page: list all tags as series
    override val mainPage = mainPageOf(
        "$mainUrl/tags/" to "All Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val series = doc.select("a[rel='tag']").mapNotNull { element ->
            val name = element.text().trim()
            val url = fixUrl(element.attr("href"))
            if (name.isNotBlank() && url.isNotBlank()) {
                newAnimeSearchResponse(name, url, TvType.Anime)
            } else null
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, series)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
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
        }.reversed()  // newest first

        return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
            addEpisodes(DubStatus.None, episodes)
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

        // 1. Direct Dailymotion link inside the post
        val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")
        if (dmLink != null) {
            return loadExtractor(dmLink, data, subtitleCallback, callback)
        }

        // 2. Dailymotion iframe
        val iframeSrc = doc.selectFirst("iframe[src*='dailymotion.com']")?.attr("src")
        if (iframeSrc != null) {
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        // 3. Fallback: regex on whole HTML
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
