package com.footballreplays

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FootballReplays : MainAPI() {
    override var mainUrl = "https://www.footreplays.com"
    override var name = "FootballReplays"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others)
    
    // REORDERED AND RENAMED MAIN PAGE CATEGORIES
    override val mainPage = mainPageOf(
        "${mainUrl}/international/" to "FIFA",
        "${mainUrl}/uefa/" to "UEFA",
        "${mainUrl}/england/" to "England",
        "${mainUrl}/spain/" to "Spain",
        "${mainUrl}/italy/" to "Italy",
        "${mainUrl}/germany/" to "Germany",
        "${mainUrl}/france/" to "France",
        "${mainUrl}/portugal/" to "Portugal",
        "${mainUrl}/other/" to "Other"
    )

    // SAFE DATE PARSER: Mimics the working logic from LiveSportsEvents
    private fun parseEventDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            // Converts web format "2023-11-04T12:00:00+00:00" -> safely readable "2023-11-04 12:00:00+0000"
            val cleanStr = dateStr.replace("T", " ").let {
                if (it.length >= 25 && it[22] == ':') {
                    it.substring(0, 22) + it.substring(23)
                } else it
            }
            SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).parse(cleanStr)?.time
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val siteurl = if (page > 1) "${request.data.removeSuffix("/")}/page/$page/" else request.data
        val document = app.get(siteurl).document
        val home = document.select("div.p-wrap").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            )
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val isnot = this.selectFirst("a.p-category")?.attr("href")?.contains("/news/") == true
        val categoryId = this.selectFirst("a.p-category")?.className()
        if (isnot || categoryId?.contains("category-id-283") == true) return null

        return toRecommendationResult()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "$mainUrl/?s=$query"
        } else {
            "$mainUrl/page/$page/?s=$query"
        }

        val document = app.get(url).document
        val aramaCevap = document.select("div.p-wrap").mapNotNull { it.toMainPageResult() }

        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.s-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.s-feat img")?.attr("src"))
        
        val timeElement = document.selectFirst("time.updated-date")
        val rawDateText = timeElement?.text()?.trim()
        val datetimeAttr = timeElement?.attr("datetime")
        val year = datetimeAttr?.substringBefore("-")?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        document.select("table.video-table").forEach { table ->
            val sourceName = table.selectFirst("thead tr th[colspan]")?.text()?.trim() ?: "Source"
            table.select("tbody tr").forEach { tr ->
                val part = tr.select("td").firstOrNull()?.text()?.trim() ?: "Video"
                val onclickAttr = tr.selectFirst("a.play-button")?.attr("onclick") ?: return@forEach
                val regex = Regex("""loadVideo\('([^']+)'\)""")
                val videoUrl = regex.find(onclickAttr)?.groupValues?.get(1) ?: return@forEach

                val episodeData = "$videoUrl|$sourceName - $part"
                val currentEpisodeSize = episodes.size

                episodes.add(
                    // STRICTLY safe Episode creation. No custom date properties here to avoid crashes.
                    newEpisode(data = episodeData) {
                        this.name = "$sourceName - $part"
                        this.episode = currentEpisodeSize + 1
                    }
                )
            }
        }

        // DATE & TIME BUILDER: Exact match of the LiveSportsEvents plot generation logic
        val plotText = buildString {
            if (!datetimeAttr.isNullOrBlank()) {
                val parsedMs = parseEventDate(datetimeAttr)
                if (parsedMs != null) {
                    append("🕐 ${SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US).format(Date(parsedMs))}\n\n")
                } else if (!rawDateText.isNullOrBlank()) {
                    append("🕐 $rawDateText\n\n")
                }
            } else if (!rawDateText.isNullOrBlank()) {
                append("🕐 $rawDateText\n\n")
            }

            val rawDescription = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            if (!rawDescription.isNullOrBlank()) {
                append("$rawDescription\n\n")
            }
            
            append("📡 Available Streams: ${episodes.size}")
        }

        Log.d("FootballReplays", "Title: $title | Url: $url")

        return newTvSeriesLoadResponse(title, url, TvType.Others, episodes) {
            this.posterUrl = poster
            this.plot = plotText // The UI handles all standard text perfectly without crashing
            this.year = year
            this.tags = document.select("div.efoot-bar.tag-bar a").map { it.text() }
            this.recommendations = document.select("div.p-wrap.p-grid").mapNotNull { it.toRecommendationResult() }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("h4.entry-title a, a.p-flink")?.attr("title")
            ?.takeIf { it.isNotBlank() } ?: this.selectFirst("h4.entry-title a")?.text()?.trim()
            ?: return null
        val href = fixUrlNull(this.selectFirst("a.p-flink, h4.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.p-featured img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val videoUrl = parts.getOrNull(0) ?: return false
        val customName = parts.getOrNull(1) ?: "Video"
        val iframeUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
        
        Log.d("FootballReplays", "Iframe Url: $iframeUrl")

        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
            val extractedLink = ExtractorLink(
                source = customName,
                name = customName,
                url = link.url,
                referer = link.referer,
                quality = link.quality,
                type = link.type,
                headers = link.headers
            )
            callback(extractedLink)
        }

        return true
    }
}
