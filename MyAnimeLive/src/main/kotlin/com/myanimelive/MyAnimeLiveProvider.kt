package com.myanimelive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.Jsoup
import java.net.URLEncoder

class MyAnimeLiveProvider : MainAPI() {
    override var mainUrl = "https://myanimelive.com"   // ⬅️ change to actual domain
    override var name = "MyAnimeLive"
    override var lang = "en"                           // ⬅️ var, not val
    override val hasMainPage = true                    // OK (not overridden? but keep if no error)
    // hasSearch is NOT used in this API version – remove it
    // override val hasSearch = true                   // ❌ remove (does not override anything)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/page/$page/"
        val doc = Jsoup.connect(url).get()
        val list = doc.select(".anime-item").mapNotNull { element ->
            val title = element.select(".title").text()
            val href = element.select("a").attr("href")
            val link = if (href.startsWith("http")) href else mainUrl + href
            val poster = element.select("img").attr("src")
            // ✅ Correct: newTvSeriesSearchResponse(name, url, type, ...)
            newTvSeriesSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = poster
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
            // ✅ Use newEpisode (deprecated constructor replaced)
            newEpisode(epUrl, epName)
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ✅ Override the correct loadLinks signature expected by this build
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is the episode URL
        val doc = Jsoup.connect(data).get()
        val iframe = doc.select("iframe").attr("src")
        if (iframe.isBlank()) return false

        // Use loadExtractor with callbacks
        loadExtractor(
            iframe,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val doc = Jsoup.connect(url).get()
        return doc.select(".search-item").mapNotNull { el ->
            val title = el.select(".title").text()
            val href = el.select("a").attr("href")
            val poster = el.select("img").attr("src")
            newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }
}
