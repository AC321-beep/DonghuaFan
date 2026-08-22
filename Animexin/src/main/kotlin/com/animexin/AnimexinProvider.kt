package com.Animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"
        val document = app.get(url).document
        
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("div.bsx > a") ?: return null
        val title = aTag.attr("title")
        val href = fixUrl(aTag.attr("href"))
        val posterUrl = fixUrlNull(aTag.selectFirst("img")?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
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
            
            // Map episodes and reverse them so Episode 1 is at the top
            val episodes = document.select("div.eplister > ul > li").mapNotNull { info ->
                val epHref = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster = info.selectFirst("a img")?.attr("src") ?: ""
                val epText = info.selectFirst("div.epl-num")?.text() ?: ""
                val epNum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = if (epNum != null) "Episode $epNum" else epText
                    this.episode = epNum
                    this.posterUrl = epPoster
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

        // Parse servers concurrently
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
