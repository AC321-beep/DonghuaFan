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
            val url = if (page == 1) "$mainUrl/" else "$mainUrl/${request.data}$page"

            val document = app.get(
                url,
                headers = defaultHeaders + mapOf(
                    "Cache-Control" to "no-cache", 
                    "Pragma" to "no-cache"
                ),
                cacheTime = 0
            ).document
            
            val home = if (page == 1) {
                document.selectFirst("div.releases.latesthome")
                    ?.parent()
                    ?.select("article")
                    ?.mapNotNull { it.toSearchResult() } ?: emptyList()
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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val isEpisodePage = document.selectFirst(".infox, .tsinfo, .anime-info") == null
        
        if (isEpisodePage) {
            val seriesUrl = document.select("div.ts-breadcrumb a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select(".naveps a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select("div.ts-breadcrumb a").lastOrNull()?.attr("href") 
            
            if (!seriesUrl.isNullOrEmpty() && seriesUrl != url) {
                return load(seriesUrl)
            }
            
            val titleRaw = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
            val title = titleRaw.substringBefore(" Episode").substringBefore(" Movie").trim() 
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            
            var epElements = document.select("div.episodelist li, .eplister li")
            if (epElements.isEmpty()) epElements = document.select("div.episodelist a[href], .eplister a[href]")
            if (epElements.isEmpty()) epElements = document.select("div.listupd article, div.bixbox article, div.related article")

            if (epElements.isNotEmpty()) {
                val episodes = parseEpisodes(epElements, title)
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

        var epElements = document.select(".eplister li, .episodelist li")
        if (epElements.isEmpty()) epElements = document.select(".eplister a[href], .episodelist a[href]")
        if (epElements.isEmpty()) epElements = document.select("div.listupd article, div.bixbox article, div.related article")

        return if (epElements.size > 1) {
            val episodes = parseEpisodes(epElements, title)
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

    private fun parseEpisodes(epElements: org.jsoup.select.Elements, seriesTitle: String = ""): List<Episode> {
        return epElements.mapNotNull { info ->
            val aTag = info.selectFirst("a[href]") ?: info.takeIf { it.tagName() == "a" && it.hasAttr("href") }
            val href1 = aTag?.attr("href") ?: return@mapNotNull null
            
            var rawTitle = info.selectFirst(".epl-title, .ep-title, .title, h2, h3")?.text()?.trim() ?: ""
            if (rawTitle.isEmpty()) rawTitle = aTag.text().trim() 
            if (rawTitle.isEmpty()) rawTitle = info.text().trim()
            
            var episodeNum: Int? = null
            var episodeDisplayString: String? = null
            
            // Extract the date format (e.g., "August 8, 2026")
            val dateMatch = Regex("""([a-zA-Z]+\s+\d{1,2},\s+\d{4})""").find(rawTitle)?.value?.trim()
            
            // Check for Part, Ep, or Special numbers INCLUDING ranges (e.g., "Episode 3-4")
            val trueEpMatch = Regex("""(?i)(?:Eps?\s*Part|Ep|Eps|Episode|Ep\.|Part|Special|SP)\s*(\d+(?:\s*[-~]\s*\d+)?)""").findAll(rawTitle).firstOrNull()
            val isFullMovie = rawTitle.contains("Full Movie", ignoreCase = true) || rawTitle.contains("Eps Full", ignoreCase = true)

            if (trueEpMatch != null) {
                val rawEpMatch = trueEpMatch.groupValues[1].trim() // Can be "3" or "3-4"
                episodeDisplayString = rawEpMatch
                
                // For internal tracking, grab just the first number (so Cloudstream tracks "3-4" under Episode 3)
                episodeNum = Regex("""\d+""").find(rawEpMatch)?.value?.toIntOrNull()
            } else if (isFullMovie) {
                episodeNum = 1
                episodeDisplayString = "1"
            } else {
                // Fallback: see if there's a standalone range (e.g., "1-4" without the "Episode" prefix)
                val rangeMatch = Regex("""\b(\d+[-~]\d+)\b""").find(rawTitle)
                if (rangeMatch != null) {
                    val rawEpMatch = rangeMatch.groupValues[1]
                    episodeDisplayString = rawEpMatch
                    episodeNum = Regex("""\d+""").find(rawEpMatch)?.value?.toIntOrNull()
                } else {
                    // Standard fallback for a single standalone number
                    val numbers = Regex("""\d+""").findAll(rawTitle).map { it.value }.toList()
                    episodeNum = numbers.lastOrNull { num ->
                        num != "4" && num != "1080" && num != "720" && num != "2160" && !(num.length == 4 && num.startsWith("20")) && (num.toIntOrNull() ?: 0) < 32
                    }?.toIntOrNull()
                    
                    if (episodeNum != null) {
                        episodeDisplayString = episodeNum.toString()
                    }
                }
            }
            
            val baseLabel = if (episodeDisplayString != null) "Episode $episodeDisplayString" else "Episode"
            
            // Strictly enforce the "Episode [X-Y]: Date" naming convention
            val epName = if (dateMatch != null) {
                "$baseLabel: $dateMatch"
            } else if (isFullMovie) {
                "$baseLabel: Full Movie"
            } else {
                baseLabel
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
            var extReferer = iframeUrl 

            if (finalUrl.contains("dailymotion", ignoreCase = true)) {
                val videoIdMatch = Regex("""[?&]video=([a-zA-Z0-9_-]+)""").find(finalUrl)
                if (videoIdMatch != null) {
                    finalUrl = "https://www.dailymotion.com/video/${videoIdMatch.groupValues[1]}"
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
