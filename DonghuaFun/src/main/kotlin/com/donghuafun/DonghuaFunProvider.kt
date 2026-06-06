package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "zh"
    override val hasMainPage = true

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst(".title, h2, .entry-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("data-src") 
            ?: this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse? {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val elements = document.select(".listupd .bs, .listupd article, .post-item")
        val items = elements.mapNotNull { it.toSearchResult() }

        return HomePageResponse(
            listOf(HomePageList("Latest Updates", items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val html = app.get(searchUrl).text
        val document = Jsoup.parse(html)

        return document.select(".listupd .bs, .result-item, article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1.entry-title, .title-name")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".thumb img, .poster img")?.attr("src")
        val description = document.selectFirst(".entry-content, .synopsis")?.text()?.trim()

        val episodes = document.select(".eplister li, .listeps li").mapIndexed { index, element ->
            val linkElement = element.selectFirst("a")
            val epHref = linkElement?.attr("href") ?: url
            val epName = element.select(".epl-num, .epnum").text().trim().ifEmpty { "Episode ${index + 1}" }

            newEpisode(epHref) {
                this.name = epName
                this.episode = index + 1
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.episodes = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val document = Jsoup.parse(html)

        document.select("iframe, .embed-container iframe").forEach { iframe ->
            val sourceUrl = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (sourceUrl.isNotEmpty() && !sourceUrl.contains("about:blank")) {
                val cleanUrl = fixUrl(sourceUrl)

                if (cleanUrl.contains(".m3u8") || cleanUrl.contains(".mp4")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = cleanUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = cleanUrl.contains(".m3u8")
                        )
                    )
                } else {
                    loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
