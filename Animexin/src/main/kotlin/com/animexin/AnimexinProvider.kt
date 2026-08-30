package com.Animexin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    // THE FIX: "by lazy" ensures the WebView is initialized safely on a background thread 
    // only when the first network request is made, preventing the black screen crash.
    private val cfKiller by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Recently Updated",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "Movies",
        "anime/?status=&sub=raw&order=update" to "Anime (RAW)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val query = request.data.substringAfter("?", "")
            "$mainUrl/anime/?page=$page&$query"
        }

        // The cfKiller interceptor will automatically solve Turnstile silently in the app
        val document = app.get(url, interceptor = cfKiller).document

        val items = document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl || href.isBlank()) return null

        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()
            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()
            ?: aTag.attr("title").trim()

        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val poster = img?.let { 
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        }?.let { fixUrlNull(it) }

        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()
        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            if (epNum != null) {
                this.addSub(epNum)
            }
            if (href.contains("movie", ignoreCase = true) || type.equals("Movie", ignoreCase = true)) {
                this.type = TvType.Movie
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", interceptor = cfKiller).document
        return document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url, interceptor = cfKiller).document

        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")
        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {
            doc = app.get(seriesBreadcrumb, interceptor = cfKiller).document
        }

        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"
        
        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = img?.let { 
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        }?.let { fixUrlNull(it) } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()

        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true

        if (isMovie) {
            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .mapNotNull { info ->
                val a = info.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val epImg = info.selectFirst("a img")
                val epPoster = epImg?.let { 
                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                }?.let { fixUrlNull(it) }

                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text()?.trim() ?: ""
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
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
        val document = app.get(data, interceptor = cfKiller).document
        
        val servers = document.select(".mobius option, select.mirror option, .server option, .player option")

        coroutineScope {
            servers.map { server ->
                async {
                    val value = server.attr("value")
                    if (value.isNotBlank()) {
                        val decoded = try { base64Decode(value) } catch (e: Exception) { value }
                        val iframeSrc = if (decoded.contains("<iframe")) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http")) {
                            decoded
                        } else null

                        if (!iframeSrc.isNullOrBlank()) {
                            val targetUrl = fixUrl(iframeSrc)
                            loadExtractor(targetUrl, subtitleCallback, callback)
                        }
                    }
                }
            }.awaitAll()
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
