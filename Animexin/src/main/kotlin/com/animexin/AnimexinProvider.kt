package com.Animexin

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    companion object {
        private const val TAG = "AnimeXinDebug"
    }

    // Exact query mappings extracted from the Animexin archive filter form
    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Recently Updated",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "Movies",
        "anime/?status=&sub=raw&order=update" to "Anime (RAW)"
    )

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    private fun checkCloudflare(doc: Document, url: String) {
        val title = doc.title()
        val html = doc.html()
        if (html.contains("cf-browser-verification") ||
            html.contains("jschl") ||
            html.contains("__cf_chl") ||
            html.contains("Ray ID") ||
            title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true)
        ) {
            Log.w(TAG, "Cloudflare challenge detected on: $url | Page title: $title")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore("?").trimEnd('/')
        val query = request.data.substringAfter("?", "")

        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            if (query.isNotEmpty()) {
                "$mainUrl/$path/page/$page/?$query"
            } else {
                "$mainUrl/$path/page/$page/"
            }
        }

        Log.d(TAG, "Fetching MainPage Category: ${request.name} | URL: $url")
        val response = app.get(url, headers = defaultHeaders)
        val document = response.document
        checkCloudflare(document, url)

        // Selects both homepage (.popconslide, .excstf) and archive (.listupd) cards
        val rawElements = document.select("article.bs, div.bsx, div.listupd article.bs")
        Log.d(TAG, "Category [${request.name}] found ${rawElements.size} raw elements on page $page")

        val items = rawElements
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d(TAG, "Category [${request.name}] successfully parsed ${items.size} unique items")

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl || href.isBlank()) return null

        // 1. Extract clean title from either standard card or .styleegg card
        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()
            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()
            ?: aTag.attr("title").trim().takeIf { it.isNotBlank() }
            ?: aTag.text().trim()

        if (title.isBlank()) return null

        // 2. Extract valid image URL, bypassing placeholder base64 data URIs
        val img = this.selectFirst("img")
        val poster = img?.let {
            val src = it.attr("src")
            val lazy = it.attr("data-lazy-src")
            val dataSrc = it.attr("data-src")
            when {
                !lazy.isNullOrBlank() && !lazy.startsWith("data:image") -> lazy
                !dataSrc.isNullOrBlank() && !dataSrc.startsWith("data:image") -> dataSrc
                !src.isNullOrBlank() && !src.startsWith("data:image") -> src
                else -> null
            }
        }?.let { fixUrlNull(it) }

        // 3. Extract episode or badge number
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
        val url = "$mainUrl/?s=$query"
        Log.d(TAG, "Performing Search query: $query | URL: $url")
        val document = app.get(url, headers = defaultHeaders).document
        checkCloudflare(document, url)

        val items = document.select("article.bs, div.bsx, div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d(TAG, "Search for '$query' yielded ${items.size} results")
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Loading Media URL: $url")
        var doc = app.get(url, headers = defaultHeaders).document
        checkCloudflare(doc, url)

        // If the URL is an episode permalink, resolve it back to the main series page
        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")
        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {
            Log.d(TAG, "Redirecting from episode URL to Series URL: $seriesBreadcrumb")
            doc = app.get(seriesBreadcrumb, headers = defaultHeaders).document
            checkCloudflare(doc, seriesBreadcrumb)
        }

        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"

        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = img?.let {
            it.attr("data-lazy-src").takeIf { s -> !s.isNullOrBlank() && !s.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { s -> !s.isNullOrBlank() && !s.startsWith("data:image") }
                ?: it.attr("src").takeIf { s -> !s.isNullOrBlank() && !s.startsWith("data:image") }
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
                    it.attr("data-lazy-src").takeIf { s -> !s.isNullOrBlank() && !s.startsWith("data:image") }
                        ?: it.attr("data-src").takeIf { s -> !s.isNullOrBlank() && !s.startsWith("data:image") }
                        ?: it.attr("src").takeIf { s -> !s.isNullOrBlank() && !s.startsWith("data:image") }
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

        Log.d(TAG, "Loaded Series: $title with ${episodes.size} episodes")

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
        Log.d(TAG, "Fetching Links for: $data")
        val document = app.get(data, headers = defaultHeaders).document
        checkCloudflare(document, data)

        val servers = document.select(".mobius option, select.mirror option, .server option, .player option")
        Log.d(TAG, "Discovered ${servers.size} server options")

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
                            Log.d(TAG, "Resolving stream extractor for: $targetUrl")
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
