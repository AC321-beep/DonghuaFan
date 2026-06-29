package com.footballreplays

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FootballReplays : MainAPI() {
    override var mainUrl = "https://www.footreplays.com"
    override var name = "FootballReplays"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others)
    
    // REORDERED AND RENAMED MAIN PAGE CATEGORIES
    override val mainPage = mainPageOf(
        "${mainUrl}/international/" to "FIFA/International",
        "${mainUrl}/uefa/" to "UEFA",
        "${mainUrl}/england/" to "England",
        "${mainUrl}/spain/" to "Spain",
        "${mainUrl}/italy/" to "Italy",
        "${mainUrl}/germany/" to "Germany",
        "${mainUrl}/france/" to "France",
        "${mainUrl}/portugal/" to "Portugal",
        "${mainUrl}/other/" to "Other"
    )

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
        val year = document.selectFirst("time.updated-date")?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        
        // Grab the raw description
        val rawDescription = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""
        
        // 1. Try to find the exact "Kick-off" time buried in the description
        val kickOffRegex = Regex("""Kick-off:\s*([^.]+)""", RegexOption.IGNORE_CASE)
        val kickOffMatch = kickOffRegex.find(rawDescription)?.groupValues?.getOrNull(1)

        // 2. Fallback to the web article date, but strip the ugly "Last updated: " text
        val fallbackDate = document.selectFirst("time.updated-date")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()

        // Choose the best available date
        val displayDate = kickOffMatch ?: fallbackDate

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
                    newEpisode(data = episodeData) {
                        this.name = "$sourceName - $part"
                        this.episode = currentEpisodeSize + 1
                    }
                )
            }
        }

        // PERFECTLY FORMATTED SYNOPSIS
        val plotText = buildString {
            if (!displayDate.isNullOrBlank()) {
                append("🕒 Match Date: $displayDate\n\n")
            }
            if (rawDescription.isNotBlank()) {
                append("$rawDescription\n\n")
            }
            append("📡 Available Streams: ${episodes.size}")
        }

        Log.d("FootballReplays", "Title: $title | Url: $url")

        return newTvSeriesLoadResponse(title, url, TvType.Others, episodes) {
            this.posterUrl = poster
            this.plot = plotText
            this.year = year
            this.tags = document.select("div.efoot-bar.tag-bar a").map { it.text() }
            this.recommendations = document.select("div.p-wrap.p-grid").mapNotNull { it.toRecommendationResult() }
        }
    }

    // HOME PAGE CARD FORMATTER
    private fun Element.toRecommendationResult(): SearchResponse? {
        val baseTitle = this.selectFirst("h4.entry-title a, a.p-flink")?.attr("title")
            ?.takeIf { it.isNotBlank() } ?: this.selectFirst("h4.entry-title a")?.text()?.trim()
            ?: return null
        val href = fixUrlNull(this.selectFirst("a.p-flink, h4.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.p-featured img")?.attr("src"))

        // Grab the date from the homepage card and clean it up
        val dateText = this.selectFirst("time")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()

        // Combine title and date for a perfect UI display (e.g. "Team A vs Team B • 28/06/2026")
        val displayTitle = if (!dateText.isNullOrBlank()) {
            "$baseTitle • $dateText"
        } else {
            baseTitle
        }

        return newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
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
