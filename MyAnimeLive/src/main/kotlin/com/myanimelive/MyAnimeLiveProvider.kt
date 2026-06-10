package com.myanimelive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class MyAnimeLiveProvider : MainAPI() {
    override var mainUrl = "https://myanimelive.com" // ⬅️ replace with actual domain
    override var name = "MyAnimeLive"
    override val lang = "en"
    override val hasMainPage = true
    override val hasSearch = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/page/$page/"
        val document = Jsoup.connect(url).get()
        val home = document.select("your-selector-for-anime-items").mapNotNull { element ->
            // Build TvSeriesSearchResponse items
            val title = element.select("title-selector").text()
            val href = element.select("a").attr("href")
            val url = if (href.startsWith("http")) href else mainUrl + href
            val poster = element.select("img").attr("src")
            TvSeriesSearchResponse(title, url, this.name, poster = poster)
        }
        return newHomePageResponse(name, home)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = Jsoup.connect(url).get()
        val title = doc.select("h1").text()
        val poster = doc.select(".poster img").attr("src")
        val description = doc.select(".description").text()
        val episodes = doc.select(".episode-item").map { ep ->
            val epUrl = ep.select("a").attr("href")
            val epName = ep.select(".ep-number").text()
            Episode(epUrl, epName)
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes, poster = poster, plot = description)
    }

    override suspend fun loadLinks(
        url: String,
        data: String?,
        extractor: ExtractorApi?,
        isM3u8: Boolean
    ): List<Link> {
        val doc = Jsoup.connect(url).get()
        // find iframe source or video element
        val iframe = doc.select("iframe").attr("src")
        return if (iframe.isNotBlank()) {
            loadExtractor(iframe, mainUrl)
        } else emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val doc = Jsoup.connect(url).get()
        return doc.select(".search-result-item").mapNotNull { el ->
            val title = el.select("h3").text()
            val href = el.select("a").attr("href")
            val poster = el.select("img").attr("src")
            TvSeriesSearchResponse(title, href, this.name, poster = poster)
        }
    }
}
