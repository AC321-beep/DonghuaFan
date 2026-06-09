package com.myanimelive

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

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

    // Use the /tags/ page which lists all series (tags)
    override val mainPage = mainPageOf(
        "$mainUrl/tags/" to "All Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val seriesList = doc.select("a[rel='tag']").mapNotNull { a ->
            val name = a.text().trim()
            val url = fixUrl(a.attr("href"))
            if (name.isNotBlank() && url.isNotBlank()) {
                newAnimeSearchResponse(name, url, TvType.Anime)
            } else null
        }.distinctBy { it.url }

        // If tags page fails (empty), fallback to scanning recent posts
        val finalList = if (seriesList.isEmpty()) {
            Log.d(TAG, "Tags page empty, falling back to archive scan")
            scanArchiveForSeries()
        } else {
            seriesList
        }

        return newHomePageResponse(request.name, finalList)
    }

    private suspend fun scanArchiveForSeries(): List<SearchResponse> {
        val seriesMap = mutableMapOf<String, SearchResponse>()
        // Check first 3 pages of the blog archive
        for (i in 1..3) {
            val url = if (i == 1) mainUrl else "$mainUrl/page/$i/"
            val doc = app.get(url).document
            doc.select("article h2.entry-title a").forEach { link ->
                val postUrl = link.attr("href")
                // Extract series slug from post URL: e.g., /zhe-tian/episode-123/ -> zhe-tian
                val slug = postUrl.substringAfter(mainUrl).substringAfter("/").substringBefore("/")
                if (slug.isNotBlank() && !slug.contains("episode")) {
                    val seriesName = slug.replace("-", " ")
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                    val seriesUrl = "$mainUrl/tag/$slug/"
                    if (!seriesMap.containsKey(slug)) {
                        seriesMap[slug] = newAnimeSearchResponse(seriesName, seriesUrl, TvType.Anime)
                    }
                }
            }
            if (seriesMap.size >= 30) break
        }
        return seriesMap.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        if (url.contains("/tag/")) {
            // Tag page: list episodes
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
            // Single episode fallback
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

        // Direct Dailymotion link
        val dmLink = doc.selectFirst("a[href*='dailymotion.com/video/']")?.attr("href")
        if (dmLink != null) {
            return loadExtractor(dmLink, data, subtitleCallback, callback)
        }

        // Iframe
        val iframeSrc = doc.selectFirst("iframe[src*='dailymotion.com']")?.attr("src")
        if (iframeSrc != null) {
            return loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        // Regex fallback
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
