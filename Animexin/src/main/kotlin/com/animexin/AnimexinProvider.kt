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

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Corrects pagination routing for standard WordPress themes
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

        val document = app.get(url).document
        
        // Broadened selectors to catch any layout variations (article, div.bs, div.bixbox)
        val home = document.select("div.listupd article, div.listupd div.bs, div.bixbox div.bs").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Target the anchor directly, sidestepping wrapper div changes
        val aTag = this.selectFirst("a") ?: return null
        
        // Grab title from text elements first, fallback to title attribute, fallback to raw text
        val title = this.selectFirst(".tt, .nt, h2, h3")?.text() 
            ?: aTag.attr("title").ifEmpty { aTag.text() }
            
        if (title.isBlank()) return null
        
        val href = fixUrl(aTag.attr("href"))
        
        // Support for lazy-loaded images (data-src)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.listupd article, div.listupd div.bs, div.bixbox div.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.thumb img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        
        val typeStr = document.selectFirst(".spe")?.text() ?: ""
        val isMovie = typeStr.contains("Movie", ignoreCase = true)

        if (isMovie) {
            val href = document.selectFirst("div.eplister > ul > li a")?.attr("href") ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodeRegex = Regex("""(\d+)""")
            
            val episodes = document.select("div.eplister > ul > li").mapNotNull { info ->
                val epHref = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster = info.selectFirst("a img")?.attr("src") ?: ""
                val epText = info.selectFirst("div.epl-num")?.text() ?: ""
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
        val servers = document.select(".mobius option")

        servers.amap { server ->
            val base64 = server.attr("value")
            if (base64.isNotEmpty()) {
                val decoded = base64Decode(base64)
                val doc = Jsoup.parse(decoded)
                
                val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return@amap
                val url = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                
                loadExtractor(url, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
