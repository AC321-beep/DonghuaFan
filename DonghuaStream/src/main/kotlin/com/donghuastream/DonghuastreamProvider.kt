package com.donghuastream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DonghuastreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "DonghuaStream"
    override var lang = "en" // Site primarily provides English hardsubs/softsubs
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    // Using standard WP pagination endpoints
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Updates"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data.substringBefore("page/")
        } else {
            "${request.data}$page/"
        }
        
        val doc = app.get(url).document
        
        // Standard WordPress/Dooplay/AnimeStream selectors
        val items = doc.select("article, .post-item, .bsx, .update-info, .l-post").mapNotNull { element ->
            element.toSearchResponse()
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleElement = this.selectFirst("h2, h3, .tt, .entry-title, .post-title") ?: return null
        val title = titleElement.text().trim()
        
        val a = this.selectFirst("a") ?: return null
        val url = fixUrl(a.attr("href"))
        
        val img = this.selectFirst("img")
        var poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")
            
        // Filter out blank or placeholder Base64 images
        if (poster?.contains("data:image") == true) poster = null

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Standard WP search query parameter
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        
        return doc.select("article, .post-item, .bsx, .result-item, .l-post").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.entry-title, .infox h1, h1[itemprop=name]")?.text()?.trim() 
            ?: doc.title().substringBefore("-").trim()
            
        val poster = doc.selectFirst(".thumb img, .post-thumbnail img, .poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        
        val description = doc.selectFirst(".entry-content, .description, [itemprop=description]")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        
        // Looks for traditional episode lists in WP series pages
        val epElements = doc.select(".eplister ul li a, .episodes li a, .eplist li a")
        
        if (epElements.isNotEmpty()) {
            epElements.forEach { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epTitle = ep.selectFirst(".epl-title, .ep-title")?.text() ?: ep.text()
                val epNum = ep.selectFirst(".epl-num, .ep-num")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                    ?: Regex("""(?i)episode\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle.trim()
                        this.episode = epNum
                    }
                )
            }
        } else {
            // Fallback: If donghuastream.org links directly to the episode post rather than a series page
            val epNum = Regex("""(?i)episode\s*[-]?\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
            
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = epNum
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = description
            // Reversing is standard since WP lists newest episodes first
            addEpisodes(DubStatus.Subbed, episodes.reversed()) 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false
        
        // 1. Scrape standard iframes for video players
        val iframes = doc.select("iframe, .player-video iframe")
        
        for (iframe in iframes) {
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
            if (src.isNotBlank() && !src.contains("youtube.com", ignoreCase = true)) {
                found = loadExtractor(fixUrl(src), data, subtitleCallback, callback) || found
            }
        }
        
        // 2. If no iframe is immediately visible, look for script-based player embeds
        if (!found) {
            val scripts = doc.select("script").map { it.data() }.joinToString("")
            val regex = Regex("""['"](https?://[^'"]*(?:dailymotion\.com|filemoon|streamwish|vidhide)[^'"]*)['"]""")
            val match = regex.find(scripts)
            
            if (match != null) {
                val extractedUrl = match.groupValues[1]
                found = loadExtractor(fixUrl(extractedUrl), data, subtitleCallback, callback) || found
            }
        }
        
        if (!found) {
            Log.d("DonghuaStream", "No playable source found for $data")
        }
        
        return found
    }
}
