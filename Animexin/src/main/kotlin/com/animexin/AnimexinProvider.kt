package com.Animexin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.random.Random

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val episodeRegex = Regex("""(\d+)""")

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Latest Release",
        "anime/?status=ongoing&order=popular" to "Popular Today",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "New Movies"
    )

    // ----- Session warm‑up: fetch homepage first to get cookies -----
    private var sessionInitialized = false

    private suspend fun warmUpSession() {
        if (sessionInitialized) return
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            )
            app.get(mainUrl, headers = headers)
            sessionInitialized = true
        } catch (_: Exception) {
            // ignore – will retry later
        }
    }

    // ----- Cloudflare‑aware fetch with advanced retry and session warm‑up -----
    private suspend fun fetchDocumentWithRetry(url: String): Document {
        // Ensure we have a session cookie
        warmUpSession()

        var attempt = 0
        val maxAttempts = 4

        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36"
        )

        val referer = if (url.contains("/anime/")) "$mainUrl/anime/" else mainUrl

        while (attempt < maxAttempts) {
            try {
                val headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "User-Agent" to userAgents[attempt % userAgents.size],
                    "Referer" to referer,
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-User" to "?1"
                )

                val response = app.get(url, headers = headers)
                val doc = response.document
                val html = doc.html()

                // Detect Cloudflare challenge
                val isChallenge = html.contains("cf-browser-verification") ||
                        html.contains("jschl") ||
                        html.contains("__cf_chl") ||
                        html.contains("Ray ID") ||
                        html.contains("Just a moment") ||
                        html.contains("Checking your browser") ||
                        html.contains("Please turn JavaScript on")

                if (isChallenge) {
                    attempt++
                    val waitMs = 3000L * attempt + Random.nextLong(1000L, 3000L)  // Long
                    println("Cloudflare challenge detected. Retrying in ${waitMs}ms (attempt $attempt)")
                    delay(waitMs)  // now accepts Long
                    warmUpSession()
                    continue
                }

                return doc

            } catch (e: Exception) {
                attempt++
                if (attempt < maxAttempts) {
                    val waitMs = 2000L * attempt + Random.nextLong(500L, 1500L)  // Long
                    println("Request error: ${e.message}. Retrying in ${waitMs}ms (attempt $attempt)")
                    delay(waitMs)  // Long
                } else {
                    throw e
                }
            }
        }

        throw Exception("Failed to fetch page after $maxAttempts attempts")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val path = request.data.substringBefore("?")
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) {
                "$mainUrl/${path}page/$page/?$query"
            } else {
                "$mainUrl/${path}page/$page/"
            }
        }

        val document = fetchDocumentWithRetry(url)

        val items = document.select(".listupd .bs")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        if (href.isBlank() || href == mainUrl) return null

        var title = this.selectFirst(".tt")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = listOf(
                this.selectFirst("h2")?.text(),
                this.selectFirst("h3")?.text(),
                this.selectFirst("h4")?.text(),
                this.selectFirst(".title")?.text(),
                aTag.attr("title"),
                aTag.text()
            ).firstOrNull { !it.isNullOrBlank() }?.trim()
        }
        if (title.isNullOrBlank()) return null

        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            listOf(
                img?.attr("src"),
                img?.attr("data-lazy-src"),
                img?.attr("data-src")
            ).firstOrNull { !it.isNullOrBlank() }
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = fetchDocumentWithRetry(url)
        return document.select(".listupd .bs")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocumentWithRetry(url)

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst(".infox h1")?.text()?.trim()
            ?: "Unknown Title"

        val img = document.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = fixUrlNull(
            listOf(
                img?.attr("data-lazy-src"),
                img?.attr("data-src"),
                img?.attr("src"),
                document.selectFirst("meta[property=og:image]")?.attr("content")
            ).firstOrNull { !it.isNullOrBlank() }
        )

        val description = document.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()

        val typeStr = document.selectFirst(".spe, .type")?.text() ?: ""
        val isMovie = typeStr.contains("Movie", ignoreCase = true)

        if (isMovie) {
            val href = document.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodeElements = document.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .asSequence()
            .filter { it.selectFirst("a") != null }

        val episodes = episodeElements.mapNotNull { info ->
            val epHref = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            val epImg = info.selectFirst("a img")
            val epPoster = listOf(
                epImg?.attr("data-lazy-src"),
                epImg?.attr("data-src"),
                epImg?.attr("src")
            ).firstOrNull { !it.isNullOrBlank() }

            val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text() ?: ""
            val epNum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

            newEpisode(epHref) {
                this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { "Episode" }
                this.episode = epNum
                this.posterUrl = epPoster
                if (!dateText.isNullOrBlank()) {
                    this.addDate(dateText, format = "MMMM d, yyyy")
                    this.description = dateText
                }
            }
        }.toList().reversed()

        val finalEpisodes = if (episodes.isEmpty()) {
            document.select(".eplister a[href]")
                .asSequence()
                .mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank()) null else newEpisode(href) {
                        this.name = a.text().ifBlank { "Episode" }
                    }
                }.toList().reversed()
        } else {
            episodes
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, finalEpisodes) {
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
        val document = fetchDocumentWithRetry(data)

        val servers = document.select(".mobius option, select.mirror option, .server option, .player option")

        coroutineScope {
            servers.map { server ->
                async {
                    val value = server.attr("value")
                    if (value.isNotEmpty()) {
                        val decoded = try { base64Decode(value) } catch (e: Exception) { value }

                        val iframeSrc = if (decoded.contains("<iframe")) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http")) {
                            decoded
                        } else {
                            null
                        }

                        if (iframeSrc != null) {
                            val url = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                            loadExtractor(url, subtitleCallback, callback)
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
