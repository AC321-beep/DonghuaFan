package com.footballreplays

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder

class FootballReplays : MainAPI() {
    override var mainUrl = "https://www.footreplays.com"
    override var name = "FootballReplays"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = mainPageOf(
        "${mainUrl}/international/" to "International",
        "${mainUrl}/uefa/" to "UEFA",
        "${mainUrl}/england/" to "England",
        "${mainUrl}/spain/" to "Spain",
        "${mainUrl}/italy/" to "Italy",
        "${mainUrl}/germany/" to "Germany",
        "${mainUrl}/france/" to "France",
        "${mainUrl}/portugal/" to "Portugal",
        "${mainUrl}/other/" to "Other"
    )

    private fun getPosterHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to DEFAULT_UA
        )
        val cookies = android.webkit.CookieManager.getInstance().getCookie(mainUrl)
        if (!cookies.isNullOrEmpty()) {
            headers["Cookie"] = cookies
        }
        return headers
    }

    // 🎯 NEW: Uses CFSolver for the main site!
    private suspend fun fetchHtml(url: String): String {
        // 1. Check if CFSolver already has the cached HTML
        CFState.getSolvedHtml(url)?.let { return it }

        // 2. Try standard HTTP
        try {
            val response = app.get(url, headers = getPosterHeaders()).text
            if (!response.contains("cf-browser-verification") && !response.contains("just a moment")) {
                return response
            }
        } catch (_: Exception) {}

        // 3. Fallback to CFSolver
        Log.e("FootballReplays", "→ CF Challenge detected on $url. Using CFSolver...")
        CFSolver(url).solve()
        
        // 4. Return solved HTML or retry HTTP
        return CFState.getSolvedHtml(url) ?: try {
            app.get(url, headers = getPosterHeaders()).text
        } catch (_: Exception) { "" }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val siteurl = if (page > 1) "${request.data.removeSuffix("/")}/page/$page/" else request.data

        val html = fetchHtml(siteurl)
        val document = org.jsoup.Jsoup.parse(html)
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
        if (isnot || categoryId?.contains("category-id-283") == true) {
            return null
        }
        return toRecommendationResult()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "$mainUrl/?s=$query"
        } else {
            "$mainUrl/page/$page/?s=$query"
        }

        val html = fetchHtml(url)
        val document = org.jsoup.Jsoup.parse(html)
        val aramaCevap = document.select("div.p-wrap").mapNotNull { it.toMainPageResult() }

        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        val document = org.jsoup.Jsoup.parse(html)

        val title = document.selectFirst("h1.s-title")?.text()?.trim() ?: return null

        val poster = fixUrlNull(document.selectFirst("div.s-feat img")?.attr("src"))
        val year = document.selectFirst("time.updated-date")?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        val rawDescription = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""

        val kickOffRegex = Regex("""Kick-off:\s*([^.]+)""", RegexOption.IGNORE_CASE)
        val kickOffMatch = kickOffRegex.find(rawDescription)?.groupValues?.getOrNull(1)
        
        val fallbackDate = document.selectFirst("time.updated-date")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()
        val displayDate = kickOffMatch ?: fallbackDate

        val episodes = mutableListOf<Episode>()
        document.select("table.video-table").forEachIndexed { _, table ->
            val sourceName = table.selectFirst("thead tr th[colspan]")?.text()?.trim() ?: "Source"
            
            table.select("tbody tr").forEachIndexed { _, tr ->
                val part = tr.select("td").firstOrNull()?.text()?.trim() ?: "Video"
                val onclickAttr = tr.selectFirst("a.play-button")?.attr("onclick") ?: return@forEachIndexed
                
                val regex = Regex("""loadVideo\('([^']+)'\)""")
                val videoUrl = regex.find(onclickAttr)?.groupValues?.get(1) ?: return@forEachIndexed
                
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

        val plotText = buildString {
            if (!displayDate.isNullOrBlank()) {
                append("🕒 Match Date: $displayDate\n\n")
            }
            if (rawDescription.isNotBlank()) {
                append("$rawDescription\n\n")
            }
            append("📡 Available Streams: ${episodes.size}")
        }

        return newTvSeriesLoadResponse(title, url, TvType.Others, episodes) {
            this.posterUrl = poster
            this.posterHeaders = getPosterHeaders() 
            this.plot = plotText
            this.year = year
            this.tags = document.select("div.efoot-bar.tag-bar a").map { it.text() }
            this.recommendations = document.select("div.p-wrap.p-grid").mapNotNull { it.toRecommendationResult() }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val baseTitle = this.selectFirst("h4.entry-title a, a.p-flink")?.attr("title")
            ?.takeIf { it.isNotBlank() } ?: this.selectFirst("h4.entry-title a")?.text()?.trim()
            ?: return null
        val href = fixUrlNull(this.selectFirst("a.p-flink, h4.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.p-featured img")?.attr("src"))

        val dateText = this.selectFirst("time")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()

        val displayTitle = if (!dateText.isNullOrBlank()) {
            "$baseTitle • $dateText"
        } else {
            baseTitle
        }

        return newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = getPosterHeaders()
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

        var emitted = 0

        // 🎯 Check if it's Byse first to avoid loadExtractor timeout
        val uri = try { URI(iframeUrl) } catch (_: Exception) { null }
        val path = uri?.path ?: ""
        val looksLikeByse = uri != null && (
            path.contains("/d/") || 
            path.contains("/e/") || 
            path.contains("/v/") || 
            uri.host?.contains("byse") == true
        )

        if (looksLikeByse) {
            try {
                ByseSX().getUrl(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
                    emitted++
                    callback(newExtractorLink(
                        source = customName,
                        name = customName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                    })
                }
            } catch (e: Exception) {
                Log.e("FootballReplays", "ByseSX failed: ${e.message}")
            }
        }

        // Fallback to generic extractor if Byse failed or it's not Byse
        if (emitted == 0) {
            val extractedLinks = mutableListOf<ExtractorLink>()

            loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
                extractedLinks.add(link)
            }

            for (link in extractedLinks) {
                emitted++
                callback(newExtractorLink(
                    source = customName,
                    name = customName,
                    url = link.url,
                    type = link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                })
            }
        }

        return emitted > 0
    }
}
