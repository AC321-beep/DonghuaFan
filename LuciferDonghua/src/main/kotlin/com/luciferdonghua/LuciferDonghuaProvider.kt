package com.luciferdonghua

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "Lucifer Donghua"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // ✅ Changed: Removed "Home", added "Latest Release" (using order=update)
    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=movie&sub=" to "Movies",
        "$mainUrl/network/tencent/" to "Tencent Anime",
        "$mainUrl/network/youku/" to "YouKu Anime",
        "$mainUrl/anime/?status=completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        
        val home = document.select("article.bs").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".tt h2")
        val title = titleElement?.text()?.trim() ?: return null
        
        val href = fixUrlNull(this.selectFirst("a[itemprop=url]")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.ts-post-image")?.attr("src"))
        
        val epText = this.selectFirst(".bt .epx")?.text()
        val epCount = epText?.let { 
            Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() 
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true, epCount)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
        
        val results = document.select("article.bs").mapNotNull { it.toSearchResult() }
        
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".thumb img, .poster img")?.attr("src"))
        val description = document.selectFirst(".entry-content p, .synopsis p, .desc")?.text()?.trim()
        val tags = document.select(".genx a, .genres a").map { it.text() }
        
        val yearText = document.selectFirst(".split span:contains(Released), .info-content span:contains(Year)")?.text()
        val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        
        document.select(".eplister ul li, #episode_list li, .listeps ul li").forEach { ep ->
            val linkElement = ep.selectFirst("a") ?: return@forEach
            val epHref = fixUrlNull(linkElement.attr("href")) ?: return@forEach
            
            val epName = linkElement.selectFirst(".epl-num, .epnum")?.text()?.trim() 
                ?: ep.text().trim()
                
            val epNum = epName.filter { it.isDigit() }.toIntOrNull()

            episodes.add(
                newEpisode(data = epHref) {
                    this.name = "Episode $epName"
                    this.episode = epNum
                }
            )
        }

        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }

        // ✅ Correctly uses mutableMapOf to match the expected type
        val episodeMap = mutableMapOf(
            DubStatus.Subbed to sortedEpisodes
        )

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.episodes = episodeMap 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // 1️⃣ Try iframes first (uses built-in Dailymotion extractor via loadExtractor)
        val iframeCandidates = document.select("iframe[src]")
            .filter { it.attr("src").isNotBlank() }
            .map { it.attr("src") }
            .filter { 
                !it.contains("about:blank") && 
                !it.contains("googleads") && 
                !it.contains("doubleclick") 
            }

        if (iframeCandidates.isNotEmpty()) {
            for (src in iframeCandidates) {
                val cleanUrl = fixUrlNull(src)
                if (cleanUrl != null) {
                    val success = loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
                    if (success) return true
                }
            }
        }

        // 2️⃣ Regex fallback: find video URLs inside JavaScript
        val scriptRegex = Regex("""(?:file|video_url|source|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4|mkv))["']""")
        val scripts = document.select("script")
        for (script in scripts) {
            val matches = scriptRegex.findAll(script.html())
            for (match in matches) {
                val videoUrl = match.groupValues[1]
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        videoUrl,
                        data,
                        Qualities.Unknown.value,
                        ExtractorLinkType.M3U8
                    )
                )
                return true
            }
        }

        // 3️⃣ Ultimate fallback: use WebView to render JavaScript and extract
        val webViewLinks = app.extractFromWebView(data)
        webViewLinks.forEach { callback.invoke(it) }
        return webViewLinks.isNotEmpty()
    }
}
