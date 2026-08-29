package com.Animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    // Aligned strictly with the 'View All' href endpoints found in the HTML source
    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Latest Release",
        "anime/?status=ongoing&order=popular" to "Popular Today",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "New Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val path = request.data.substringBefore("?")
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) {
                "$mainUrl/${path}page/$page/?$query"
            } else {
                "$mainUrl/${path}page/$page/"
            }
        }

        // Added standard browser headers to help bypass basic Cloudflare/Anti-bot checks
        val document = app.get(url, headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
        )).document
        
        // Aggressive selectors capturing both main grid (.styleegg, .bsx) and sidebar lists (.serieslist li)
        val elements = document.select("article.bs, article.styleegg, div.listupd .bs, div.listupd .bsx, div.bixbox .bs, div.postbox, .item, .serieslist ul li")
        val home = elements.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        
        if (href.isBlank() || href == mainUrl) return null
        
        // Cascading title extraction favoring clean names from the latest HTML structure
        val title = this.selectFirst(".eggtitle, .leftseries h4 a")?.text()?.trim() 
            ?: this.selectFirst(".tt, .nt, .ts5, h2, h3, h4, .title, .entry-title")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt").takeIf { it.isNotBlank() }
            ?: aTag.attr("title").takeIf { it.isNotBlank() }
            ?: aTag.text().trim()
            
        val finalTitle = title.ifBlank { "Unknown Title" }
        
        val img = this.selectFirst("img")
        // Bypassing base64 lazy-load placeholders
        val posterUrl = fixUrlNull(
            img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )
        
        return newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("article.bs, article.styleegg, div.listupd .bs, div.listupd .bsx, div.bixbox .bs, div.postbox, .item, .serieslist ul li")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown Title"
        
        val img = document.selectFirst("div.thumb img, div.infox img")
        val poster = fixUrlNull(
            img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        )
        
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        
        val typeStr = document.selectFirst(".spe")?.text() ?: ""
        val isMovie = typeStr.contains("Movie", ignoreCase = true)

        if (isMovie) {
            val href = document.selectFirst("div.eplister > ul > li a, .eplister li a")?.attr("href") ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodeRegex = Regex("""(\d+)""")
            
            val episodes = document.select("div.eplister li, ul.eplister li, .eplister li").mapNotNull { info ->
                val epHref = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                
                val epImg = info.selectFirst("a img")
                val epPoster = epImg?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: epImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: epImg?.attr("src")?.takeIf { it.isNotBlank() } ?: ""
                    
                val epText = info.selectFirst("div.epl-num, .epl-num, .epl-title")?.text() ?: ""
                val epNum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim() 

                newEpisode(epHref) {
                    this.name = if (epNum != null) "Episode $epNum" else epText
                    this.episode = epNum
                    this.posterUrl = epPoster
                    
                    if (!dateText.isNullOrBlank()) { 
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = document.select(".mobius option, select.mirror option, .server option")

        servers.amap { server ->
            val value = server.attr("value")
            if (value.isNotEmpty()) {
                val decoded = try { base64Decode(value) } catch (e: Exception) { value }
                
                val iframeSrc = if (decoded.contains("<iframe")) {
                    Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                } else if (decoded.startsWith("http")) {
                    decoded
                } else {
                    null
                }
                
                if (iframeSrc != null) {
                    val url = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                    loadExtractor(url, subtitleCallback, callback)
                }
            }
        }
        
        return true
    }
}
