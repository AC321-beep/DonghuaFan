package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    override val supportedTypes = setOf(TvType.Anime)

    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Release",
        "$mainUrl/category/3d-donghua/" to "3D Donghua",
        "$mainUrl/category/completed/" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.select("article, .post-item, .list-loop li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, .post-title, a")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article, .post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title, .post-title")?.text() ?: "Unknown Title"
        val poster = doc.selectFirst(".entry-content img, .post-thumbnail img")?.attr("src")
        val description = doc.selectFirst(".entry-content p")?.text()

        val episodes = doc.select(".episode-list a, .entry-content p a[href*='episode']").map {
            Episode(
                data = it.attr("href"),
                name = it.text().ifEmpty { "Episode ${it.index() + 1}" }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            if (episodes.isEmpty()) {
                this.episodes = listOf(Episode(url, "Play"))
            } else {
                this.episodes = episodes
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeUrl = doc.selectFirst("iframe, .video-iframe")?.attr("src")
        
        if (!iframeUrl.isNullOrEmpty()) {
            if (loadExtractor(iframeUrl, data, subtitleCallback, callback)) return true
        }

        doc.select("video source, video").forEach { 
            val videoUrl = it.attr("src").ifEmpty { it.attr("data-src") }
            if (videoUrl.isNotEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.P1080.value,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
        return true
    }
}
