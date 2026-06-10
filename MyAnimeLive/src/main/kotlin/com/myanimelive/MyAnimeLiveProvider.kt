package com.myanimelive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class MyAnimeLiveProvider : MainAPI() {
    override var mainUrl = "https://myanimelive.com"   // ⬅️ change to actual domain
    override var name = "MyAnimeLive"
    override val lang = "en"
    override val hasMainPage = true
    override val hasSearch = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/page/$page/"
        val doc = Jsoup.connect(url).get()
        val list = doc.select(".anime-item").mapNotNull { element ->
            val title = element.select(".title").text()
            val href = element.select("a").attr("href")
            val link = if (href.startsWith("http")) href else mainUrl + href
            val poster = element.select("img").attr("src")
            // Use the new non‑deprecated builder
            newTvSeriesSearchResponse(title, link, this.name) {
                this.posterUrl = poster
                this.tvType = TvType.Anime
            }
        }
        return newHomePageResponse(name, list)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.connect(url).get()
        val title = doc.select(".anime-title").text()
        val poster = doc.select(".poster img").attr("src")
        val plot = doc.select(".description").text()
        val episodes = doc.select(".episode-item").map { ep ->
            val epUrl = ep.select("a").attr("href")
            val epName = ep.select(".ep-number").text()
            Episode(epUrl, epName)
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        url: String,
        data: String?,
        extractor: com.lagradost.cloudstream3.utils.ExtractorApi?,
        isM3u8: Boolean
    ): List<ExtractorLink> {
        val doc = Jsoup.connect(url).get()
        val iframe = doc.select("iframe").attr("src")
        if (iframe.isBlank()) return emptyList()

        // Collect links from iframe
        val result = mutableListOf<ExtractorLink>()
        loadExtractor(
            iframe,
            referer = mainUrl,
            subtitleCallback = { result.addAll(it.map { sub -> /* handle subtitles if needed */ }) },
            callback = { link -> result.add(link) }
        )
        return result
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val doc = Jsoup.connect(url).get()
        return doc.select(".search-item").mapNotNull { el ->
            val title = el.select(".title").text()
            val href = el.select("a").attr("href")
            val poster = el.select("img").attr("src")
            newTvSeriesSearchResponse(title, href, this.name) {
                this.posterUrl = poster
                this.tvType = TvType.Anime
            }
        }
    }
}
