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
    
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.AsianDrama)

    // TODO: Step 1 - Update these URLs to match the actual category menus on the site
    override val mainPage = mainPageOf(
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/series/" to "Series",
        "$mainUrl/ongoing/" to "Ongoing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page/"
        val document = app.get(url).document
        
        // TODO: Step 2 - Replace "div.post-item" with the actual HTML class of the anime cards
        val home = document.select("div.post-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true 
            ),
            hasNext = true 
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // TODO: Step 3 - Update the CSS selectors below to match the elements inside the Anime card
        val title = this.selectFirst("h3, .title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epCount = this.selectFirst(".ep-status, .episode")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true, epCount)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
        
        // TODO: Step 4 - Replace with the actual search result item selector
        val results = document.select("div.search-item, div.post-item").mapNotNull { it.toSearchResult() }
        
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // TODO: Step 5 - Update page detail selectors
        val title = document.selectFirst("h1.title, .entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".poster img, .thumb img")?.attr("src"))
        val description = document.selectFirst(".description, .synopsis")?.text()?.trim()
        val tags = document.select(".genres a").map { it.text() }
        val year = document.selectFirst(".year, .released")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        
        // TODO: Step 6 - Update episode list selector
        document.select("ul.episodes li, .ep-list a").forEach { ep ->
            val epHref = fixUrlNull(ep.selectFirst("a")?.attr("href") ?: ep.attr("href")) ?: return@forEach
            val epName = ep.text().trim()
            val epNum = epName.filter { it.isDigit() }.toIntOrNull()

            episodes.add(
                newEpisode(data = epHref) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.episodes = episodes.associateBy { it.episode ?: 0 }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // TODO: Step 7 - Adjust the iframe selector based on the site's player box
        document.select("iframe").forEach { iframe ->
            val iframeUrl = fixUrlNull(iframe.attr("src"))
            if (iframeUrl != null) {
                loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
