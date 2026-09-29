package com.dongsub

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DongsubProvider : MainAPI() {
    override var mainUrl = "https://www.dongsub.net"
    override var name = "Dongsub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.AsianDrama)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // Simplified logic using the exact categories available from the site itself
    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "search/label/Donghua" to "Donghua",
        "search/label/Movie" to "Movie",
        "search/label/Live%20Action" to "Live Action"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.isEmpty()) "$mainUrl/" else "$mainUrl/${request.data}?max-results=20"
        } else {
            // Blogger tokens make number pagination hard, skipped for safety
            return newHomePageResponse(request.name, emptyList())
        }

        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = document.select("article.post-outer-container, .list-post li").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElem = this.selectFirst("h3.post-title a, a.grid2-tt, .info-az a") ?: return null
        val title = titleElem.text().trim()
        val href = fixUrlNull(titleElem.attr("href")) ?: return null

        // Target lazy loaded images in Blogger (checking data-src first)
        val imgElem = this.selectFirst("img.gambar, img")
        val posterUrl = fixUrlNull(imgElem?.attr("data-src")?.ifEmpty { imgElem.attr("src") })

        val epString = this.selectFirst(".epsid, .tipeps, .subind")?.text()?.replace(Regex("[^0-9]"), "")
        val epNum = epString?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"

        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }

        return document.select("article.post-outer-container, .post-filter").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url, headers = defaultHeaders).document

        // FIX FOR 1 EPISODE: The homepage links to single Episode Pages, not Series Pages.
        // We find the "Home / All Episodes" button in the Next/Prev bar (#ecHome a) and jump to the Series page.
        val seriesUrl = document.selectFirst("#ecHome a, .breadcrumbs span a:nth-child(2)")?.attr("href")
        
        if (!seriesUrl.isNullOrBlank() && seriesUrl != url && !seriesUrl.equals("$mainUrl/", true)) {
            try {
                document = app.get(seriesUrl, headers = defaultHeaders).document
            } catch (_: Exception) {}
        }

        val rawTitle = document.selectFirst("h1, h2.heading, h3.post-title")?.text()?.trim() ?: ""
        val title = rawTitle.replace(Regex("(?i)\\s*(?:Episode|Eps)\\s*\\d+.*$"), "").trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".bigcover img, .ime img, .thumbox img")?.attr("src")
        )
        val description = document.selectFirst(".sinoposis, .descNime, .entry-content, .keyword")?.text()?.trim()

        // Extract episode list from the Series page (.bxcl)
        var episodes = document.select(".bxcl ul li, .episodelist li").mapNotNull { ep ->
            val a = ep.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            
            val epName = ep.selectFirst(".chapternum")?.text() 
                ?: ep.selectFirst(".eps-num")?.text() 
                ?: a.text()
                
            newEpisode(epHref) {
                this.name = epName.trim()
            }
        }.reversed()

        // Fallback for standalone single-episode movies
        if (episodes.isEmpty()) {
            episodes = listOf(
                newEpisode(url) {
                    this.name = title
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }

        val extractedUrls = mutableSetOf<String>()

        // 1. Direct iframes - FIX: Target 'data-src' to bypass Blogger's image lazy-loader
        document.select(".tvideo iframe, .bixbox.streaming iframe, iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.contains("dailymotion.com") || src.contains("dai.ly")) {
                extractedUrls.add(src)
            }
        }

        // 2. Decode hidden server buttons (Blogger data-embed attributes)
        document.select("#server ul li a, .DagPlayOpt, [data-embed]").forEach { elem ->
            val raw = elem.attr("data-embed").ifEmpty { elem.attr("data-src") }.ifEmpty { elem.attr("value") }
            if (raw.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(raw, Base64.DEFAULT))
                    val iframeSrc = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                    if (iframeSrc.contains("dailymotion") || iframeSrc.contains("dai.ly")) {
                        extractedUrls.add(iframeSrc)
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Fallback: Deep regex scan in the raw HTML for Dailymotion
        val rawHtml = document.html()
        val dmRegex = Regex("""https?://(?:www\.)?(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/)[a-zA-Z0-9]+""")
        dmRegex.findAll(rawHtml).forEach { match ->
            extractedUrls.add(match.value)
        }

        var handled = false
        for (dmUrl in extractedUrls) {
            val cleanUrl = if (dmUrl.startsWith("//")) "https:$dmUrl" else dmUrl
            try {
                // CloudStream's native Dailymotion extractor will execute automatically
                loadExtractor(cleanUrl, referer = mainUrl, subtitleCallback, callback)
                handled = true
            } catch (_: Exception) {}
        }

        return handled
    }
}
