package com.dongsub

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DongsubProvider : MainAPI() {
    override var mainUrl = "https://www.dongsub.net"
    override var name = "Dongsub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.AsianDrama)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // Using the real categories available on Dongsub
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Release",
        "$mainUrl/search/label/Donghua" to "Donghua",
        "$mainUrl/search/label/Movie" to "Movie",
        "$mainUrl/search/label/Live%20Action" to "Live Action"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Blogger uses string tokens for deep pagination, so we add a high max-results to Page 1 to get plenty of shows
        val url = if (page == 1) "${request.data}?max-results=20" else return newHomePageResponse(request.name, emptyList())

        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = document.select("article.post-outer-container, .list-post li").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElem = this.selectFirst("h3.post-title a, a.grid2-tt, .info-az a") ?: return null
        val rawTitle = titleElem.text().trim()
        
        // 1. EXTRACT SERIES NAME (MyAnimeLive Logic)
        val seriesName = extractSeriesName(rawTitle)
        if (seriesName.isBlank()) return null

        // 2. FAKE A SERIES PAGE VIA SEARCH (MyAnimeLive Logic)
        // We append max-results=500 so Blogger returns every episode at once
        val encodedName = URLEncoder.encode(seriesName, "UTF-8").replace("+", "%20")
        val seriesUrl = "$mainUrl/search?q=$encodedName&max-results=500"

        // Target lazy loaded images in Blogger (checking data-src first)
        val imgElem = this.selectFirst("img.gambar, img")
        val posterUrl = fixUrlNull(imgElem?.attr("data-src")?.ifEmpty { imgElem.attr("src") })

        val epNum = extractEpisodeNumber(rawTitle)

        return newAnimeSearchResponse(seriesName, seriesUrl, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"

        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }

        return document.select("article.post-outer-container, .post-filter").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        // If the URL is our fake search-series page (MyAnimeLive Logic)
        if (url.contains("/search?q=")) {
            val firstArticle = doc.selectFirst("article.post-outer-container, .list-post li")
            val rawTitle = firstArticle?.selectFirst("h3.post-title a, a.grid2-tt, .info-az a")?.text()?.trim() ?: "Unknown Series"
            val seriesName = extractSeriesName(rawTitle)
            
            var poster = firstArticle?.selectFirst("img.gambar, img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            var description: String? = null

            val allEpisodes = mutableListOf<Episode>()
            
            // Loop through the search results to build the episode list
            doc.select("article.post-outer-container, .list-post li").forEach { article ->
                val link = article.selectFirst("h3.post-title a, a.grid2-tt, .info-az a") ?: return@forEach
                val epUrl = fixUrlNull(link.attr("href")) ?: return@forEach
                val epTitle = link.text().trim()
                
                val epNum = extractEpisodeNumber(epTitle)
                val epPoster = article.selectFirst("img.gambar, img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                
                allEpisodes.add(newEpisode(epUrl) {
                    this.name = if (epNum != null) "Episode $epNum" else epTitle
                    this.episode = epNum
                    this.posterUrl = epPoster
                })
            }

            // Clean, deduplicate, and sort episodes chronologically
            val uniqueEpisodes = allEpisodes.distinctBy { it.data }
            val sortedEpisodes = uniqueEpisodes.sortedBy { it.episode ?: Int.MAX_VALUE }

            // Quickly fetch the Plot Description from the first episode's page
            val firstEpUrl = sortedEpisodes.firstOrNull()?.data
            if (firstEpUrl != null) {
                try {
                    val epDoc = app.get(firstEpUrl, headers = defaultHeaders).document
                    description = epDoc.selectFirst(".sinoposis, .descNime, .entry-content, .keyword")?.text()?.trim()
                    poster = epDoc.selectFirst(".bigcover img, .ime img")?.attr("src") ?: poster
                } catch (_: Exception) {}
            }

            return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, sortedEpisodes)
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Fallback: If it's a standalone movie or single page
            val title = doc.selectFirst("h1.post-title, h2.heading, h3.post-title")?.text()?.trim() ?: "Episode"
            val seriesName = extractSeriesName(title).ifBlank { "Unknown Series" }
            val epNum = extractEpisodeNumber(title)
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst(".bigcover img, .ime img, img.gambar")?.attr("src")
            
            val episode = newEpisode(url) {
                this.name = if (epNum != null) "Episode $epNum" else title
                this.episode = epNum
                this.posterUrl = poster
            }
            return newAnimeLoadResponse(seriesName, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, listOf(episode))
                this.posterUrl = poster
            }
        }
    }

    // Exact logic from MyAnimeLive, customized for Dongsub titles
    private fun extractSeriesName(title: String): String {
        var name = title
            .replace(Regex("(?i)\\s*(?:Episode|Eps)\\.?\\s*\\d+.*$"), "")
            .trim()
        name = Regex("(?i)\\s+english\\s+sub$").replace(name, "")
        name = Regex("(?i)\\s+subtitles?$").replace(name, "")
        
        if (name.isBlank() || name.length < 3) {
            name = title.split(Regex("[-–:]"))[0].trim()
        }
        return name
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""(?:episode|eps|ep|eps\.|ep\.)\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""#(\d+)""")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }
        
        val rawHtml = doc.html()
        var linksLoaded = false
        val extractedUrls = mutableSetOf<String>()

        // 1. Direct iframes (Check data-src for Blogger lazy-loading first!)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotBlank()) extractedUrls.add(src)
        }

        // 2. Decode hidden server buttons (Blogger data-embed base64 strings)
        doc.select("[data-embed], .DagPlayOpt, #server ul li a").forEach { elem ->
            val raw = elem.attr("data-embed").ifEmpty { elem.attr("data-src") }.ifEmpty { elem.attr("value") }
            if (raw.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(raw, Base64.DEFAULT))
                    val iframeSrc = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                    extractedUrls.add(iframeSrc)
                } catch (_: Exception) {}
            }
        }

        // 3. Raw HTML Regex scan (Defeats JavaScript Obfuscation completely)
        val dmRegex = Regex("""https?://(?:www\.)?(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/)[a-zA-Z0-9]+""")
        dmRegex.findAll(rawHtml).forEach { match ->
            extractedUrls.add(match.value)
        }

        // MyAnimeLive Concurrent Execution Logic
        coroutineScope {
            extractedUrls.map { rawUrl ->
                async {
                    val fullUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                    
                    if (fullUrl.contains("dailymotion") || fullUrl.contains("dai.ly")) {
                        // Some sites hide dailymotion in weird parameters, this extracts the pure video ID
                        val videoId = Regex("""[?&]video=([a-zA-Z0-9]+)""").find(fullUrl)?.groupValues?.get(1)
                        val cleanUrl = videoId?.let { "https://www.dailymotion.com/video/$it" } ?: fullUrl
                        
                        val success = loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
                        if (success) {
                            linksLoaded = true
                        }
                    }
                }
            }.awaitAll()
        }

        return linksLoaded
    }
}
