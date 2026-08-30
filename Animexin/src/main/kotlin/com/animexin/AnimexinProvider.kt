package com.Animexin

import android.util.Base64
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
        "anime/?status=&type=&order=update" to "Latest Release",
        "anime/?status=ongoing&order=popular" to "Popular Today",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "New Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore("?")
        val query = request.data.substringAfter("?", "")
        
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            if (query.isNotEmpty()) {
                "$mainUrl/${path}page/$page/?$query"
            } else {
                "$mainUrl/${path}page/$page/"
            }
        }

        // Rely purely on app.get() so Cloudstream's native Cloudflare WebView solver functions correctly.
        val document = app.get(url).document

        val items = document.select(".listupd article.bs, .listupd .bs, .listupd .bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl) return null

        // Target the inner heading directly to prevent grabbing duplicated text from the parent .tt element
        val title = this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()
            ?: this.selectFirst(".tt")?.text()?.trim()
            ?: aTag.attr("title").takeIf { it.isNotBlank() }
            ?: aTag.text().trim()

        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("src").takeIf { !it.isNullOrBlank() }
                ?: img?.attr("data-lazy-src")
                ?: img?.attr("data-src")
        )

        val epText = this.selectFirst(".epx")?.text()?.trim()
        val epNum = epText?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val type = this.selectFirst(".typez")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            if (epNum != null) {
                this.addSub(epNum, "Episode")
            }
            if (href.contains("movie", true) || type.equals("Movie", true)) {
                this.type = TvType.Movie
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".listupd article.bs, .listupd .bs, .listupd .bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"
        
        val img = document.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = fixUrlNull(
            img?.attr("src").takeIf { !it.isNullOrBlank() }
                ?: img?.attr("data-lazy-src")
                ?: img?.attr("data-src")
        ) ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()

        val isMovie = document.selectFirst(".spe, .type")?.text()?.contains("Movie", true) == true

        if (isMovie) {
            val href = document.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = document.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .mapNotNull { info ->
                val a = info.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val epImg = info.selectFirst("a img")
                val epPoster = fixUrlNull(
                    epImg?.attr("src").takeIf { !it.isNullOrBlank() }
                        ?: epImg?.attr("data-lazy-src")
                        ?: epImg?.attr("data-src")
                )

                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text() ?: ""
                val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

                newEpisode(epHref) {
                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { a.text().trim() }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = document.select(".mobius option, select.mirror option, .server option, .player option")

        // Uses Cloudstream's native parallel mapping (apmap) for efficient fetching
        servers.apmap { server ->
            val value = server.attr("value")
            if (value.isNotBlank()) {
                val decoded = try { base64Decode(value) } catch (e: Exception) { value }
                val iframeSrc = if (decoded.contains("<iframe")) {
                    Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                } else if (decoded.startsWith("http")) {
                    decoded
                } else null

                if (iframeSrc != null) {
                    val url = fixUrl(iframeSrc)
                    loadExtractor(url, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private fun base64Decode(str: String): String {
        return try {
            String(Base64.decode(str, Base64.DEFAULT))
        } catch (e: Exception) {
            str
        }
    }
}
