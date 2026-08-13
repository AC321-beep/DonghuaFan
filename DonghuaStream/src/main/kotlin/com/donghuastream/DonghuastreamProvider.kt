package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

open class DonghuastreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "DonghuaStream"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    // Custom headers to mimic a real browser and bypass basic bot protection
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "special_edition" to "Special Edition" 
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Special Edition") {
            val movieUrl = if (page == 1) "$mainUrl/?s=movie" else "$mainUrl/pagg/$page/?s=movie"
            val movieDoc = try { app.get(movieUrl, cacheTime = 0).document } catch(e: Exception) { null }
            val movieResults = movieDoc?.select("div.listupd > article")?.mapNotNull { it.toSearchResult() } ?: emptyList()

            val specialUrl = if (page == 1) "$mainUrl/?s=special" else "$mainUrl/pagg/$page/?s=special"
            val specialDoc = try { app.get(specialUrl, cacheTime = 0).document } catch(e: Exception) { null }
            val specialResults = specialDoc?.select("div.listupd > article")?.mapNotNull { it.toSearchResult() } ?: emptyList()

            val combinedResults = (movieResults + specialResults).distinctBy { it.url }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = combinedResults,
                    isHorizontalImages = false
                ),
                hasNext = movieResults.isNotEmpty() || specialResults.isNotEmpty()
            )
        } else {
            // Page 1 grabs instant updates from the main homepage (bypassing cache delays). Page 2+ uses the directory.
            val url = if (page == 1) "$mainUrl/" else "$mainUrl/${request.data}$page"

            val document = app.get(
                url,
                headers = defaultHeaders + mapOf(
                    "Cache-Control" to "no-cache", 
                    "Pragma" to "no-cache"
                ),
                cacheTime = 0
            ).document
            
            // AGGRESSIVE HOMEPAGE SCRAPER: Catches all update blocks regardless of site layout changes
            val home = if (page == 1) {
                document.select(".bixbox article, div.listupd > article, .releases article, .postbody article, div.releases.latesthome article")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            } else {
                document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            }
            
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = false
                ),
                hasNext = true
            )
        }
    }

    fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title")
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("div.bsx a img")?.getImageAttr())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("data-src")
            this.hasAttr("src") -> this.attr("src")
            else -> this.attr("src")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("${mainUrl}/pagg/$i/?s=$query").document
            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    // HELPER: Scans robustly for Table rows, List items, and Grid articles
    private fun getEpisodesElements(document: org.jsoup.nodes.Document): org.jsoup.select.Elements {
        var eps = document.select("table tbody tr:not(:has(th)), div.episodelist ul li, div.eplister ul li, ul.eplister li, .ep_list li, .episodelist li, .eplister li, #episodelist li, .lsteps li, .list1 li")
        if (eps.isEmpty()) {
            eps = document.select("div.episodelist a[href], div.eplister a[href], .ep_list a[href], .episodelist a[href], .eplister a[href], #episodelist a[href], .lsteps a[href], .list1 a[href]")
        }
        if (eps.isEmpty()) {
            eps = document.select("div.listupd article, div.bixbox article, div.related article")
        }
        return eps
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Accurate Series vs Episode validation. These classes ONLY exist on the main series page.
        val isEpisodePage = document.selectFirst(".infox, .tsinfo, .anime-info") == null
        
        if (isEpisodePage) {
            val seriesUrl = document.select("div.ts-breadcrumb a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select(".naveps a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select("div.ts-breadcrumb a").lastOrNull()?.attr("href") 
            
            // Seamlessly resolves single episode lookups back into complete series objects
            if (!seriesUrl.isNullOrEmpty() && seriesUrl != url) {
                return load(seriesUrl)
            }
            
            val titleRaw = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
            val title = titleRaw.substringBefore(" Episode").substringBefore(" Movie").trim() 
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            
            val epElements = getEpisodesElements(document)

            if (epElements.isNotEmpty()) {
                val episodes = parseEpisodes(epElements)
                return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                }
            }
            
            return newMovieLoadResponse(titleRaw, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        var poster = document.selectFirst("div.ime > img")?.attr("data-src") ?: ""
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text() ?: ""
        val tvtag = if (type.contains("Movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries

        val epElements = getEpisodesElements(document)

        // DYNAMIC OVERRIDE: If multiple parts/episodes are found, force TvSeries view
        return if (tvtag == TvType.TvSeries || epElements.size > 1) {
            val episodes = parseEpisodes(epElements)
            
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val href = epElements.firstOrNull()?.selectFirst("a[href]")?.attr("href") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private fun parseEpisodes(epElements: org.jsoup.select.Elements): List<Episode> {
        return epElements.mapNotNull { info ->
            val aTag = info.selectFirst("a[href]") ?: info.takeIf { it.tagName() == "a" && it.hasAttr("href") }
            val href1 = aTag?.attr("href") ?: return@mapNotNull null
            
            var rawTitle = info.selectFirst(".epl-title, .ep-title, .title, h2, h3")?.text()?.trim() ?: ""
            if (rawTitle.isEmpty()) rawTitle = aTag.text().trim() 
            if (rawTitle.isEmpty()) rawTitle = info.text().trim()
            
            var episodeNum: Int? = null
            var epName: String
            
            // FULL TEXT SCAN: Extracts the date from the entire HTML element to ensure table layouts don't miss dates
            val fullText = info.text()
            val dateMatch = Regex("""([a-zA-Z]+\s+\d{1,2},\s+\d{4})""").find(fullText)?.value?.trim()
            
            // Identify if it's a Full Movie file
            val isFullMovie = rawTitle.contains("Full Movie", ignoreCase = true) || 
                              rawTitle.contains("Eps Full", ignoreCase = true) ||
                              Regex("""(?i)\bfull\b""").containsMatchIn(rawTitle)

            // STRICT EPISODE MATCHER: Grabs Episode, Part, SP, and Ranges (e.g. "3-4")
            val trueEpMatch = Regex("""(?i)(?:Ep|Eps|Episode|Ep\.|Part|SP|Special)\s*(\d+(?:\s*[-~]\s*\d+)?)""").findAll(rawTitle).firstOrNull()
            val matchStr = trueEpMatch?.groupValues?.get(1)?.trim()
            
            // LOGIC BLOCK: Fixes the Episode 1 override bug by forcing Full Movie to be Episode 0
            if (isFullMovie && matchStr == null) {
                episodeNum = 0 
                epName = if (dateMatch != null) "Full Movie: $dateMatch" else "Full Movie"
            } else if (matchStr != null) {
                episodeNum = Regex("""\d+""").find(matchStr)?.value?.toIntOrNull()
                epName = if (dateMatch != null) "Episode $matchStr: $dateMatch" else "Episode $matchStr"
            } else {
                // Fallback A: Standalone ranges (like "3-4" without prefixes)
                val rangeMatch = Regex("""\b(\d+[-~]\d+)\b""").find(rawTitle)
                if (rangeMatch != null) {
                    val rawRange = rangeMatch.groupValues[1]
                    episodeNum = Regex("""\d+""").find(rawRange)?.value?.toIntOrNull()
                    epName = if (dateMatch != null) "Episode $rawRange: $dateMatch" else "Episode $rawRange"
                } else {
                    // Fallback B: Standalone digit (ignores resolutions and 20XX years)
                    val numbers = Regex("""\d+""").findAll(rawTitle).map { it.value }.toList()
                    episodeNum = numbers.lastOrNull { num ->
                        num != "4" && num != "1080" && num != "720" && num != "2160" && !(num.length == 4 && num.startsWith("20"))
                    }?.toIntOrNull()

                    epName = if (episodeNum != null) {
                        if (dateMatch != null) "Episode $episodeNum: $dateMatch" else "Episode $episodeNum"
                    } else {
                        if (dateMatch != null) "Episode: $dateMatch" else "Episode"
                    }
                }
            }
            
            val posterr = info.selectFirst("img")?.let { 
                it.attr("data-src").takeIf { src -> src.isNotBlank() } ?: it.attr("src")
            } ?: ""
            
            newEpisode(href1) {
                this.name = epName
                this.episode = episodeNum
                this.posterUrl = posterr
            }
        }.reversed()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document
        val options = doc.select("option[data-index]")

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = iframeUrl
            var extReferer = iframeUrl // By default, use the iframe URL as referer

            // CRITICAL FIX FOR 2004 ERROR:
            if (finalUrl.contains("dailymotion", ignoreCase = true)) {
                // Safely grab the video ID, accommodating hyphens/underscores if they exist
                val videoIdMatch = Regex("""[?&]video=([a-zA-Z0-9_-]+)""").find(finalUrl)
                if (videoIdMatch != null) {
                    finalUrl = "https://www.dailymotion.com/video/${videoIdMatch.groupValues[1]}"
                    // Force the extractor to use DonghuaStream as the referer, bypassing Dailymotion's domain restriction
                    extReferer = mainUrl 
                }
            }

            when {
                "rumble.com" in finalUrl -> {
                    Rumble().getUrl(finalUrl, finalUrl, subtitleCallback, callback)
                }
                "play.streamplay.co.in" in finalUrl -> {
                    PlayStreamplay().getUrl(finalUrl, finalUrl, subtitleCallback, callback)
                }
                finalUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(label, label, finalUrl, INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
                else -> {
                    // This dynamically passes either the iframe URL or the corrected Dailymotion referer
                    loadExtractor(finalUrl, referer = extReferer, subtitleCallback, callback)
                }
            }
        }

        for (option in options) {
            val base64 = option.attr("value")
            if (base64.isBlank()) continue
            val label = option.text().trim()
            val decodedHtml = try {
                base64Decode(base64)
            } catch (_: Exception) {
                Log.w("Error", "Base64 decode failed: $base64")
                continue
            }

            val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")?.let(::httpsify)
            if (!iframeUrl.isNullOrEmpty()) {
                invokeExtractor(iframeUrl, label)
            }
        }
        
        if (options.isEmpty()) {
            val directIframe = doc.selectFirst(".player-area iframe, .playcon iframe")?.attr("src")?.let(::httpsify)
            if (!directIframe.isNullOrEmpty()) {
                invokeExtractor(directIframe, "Server")
            }
        }
        
        return true
    }
}
