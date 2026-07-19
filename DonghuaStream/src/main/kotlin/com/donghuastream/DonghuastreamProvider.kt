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
            val title = titleRaw.substringBefore(" Episode").trim() 
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            
            var epElements = document.select("div.episodelist li")
            if (epElements.isEmpty()) epElements = document.select("div.episodelist a[href]")

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

        return if (tvtag == TvType.TvSeries) {
            var epElements = document.select(".eplister li, .episodelist li")
            if (epElements.isEmpty()) {
                epElements = document.select(".eplister a[href], .episodelist a[href]")
            }
            
            val episodes = parseEpisodes(epElements)
            
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val href = document.selectFirst(".eplister a[href], .episodelist a[href]")?.attr("href") ?: url
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
            
            // Comprehensive regex to catch and destroy all SEO tags Admins append to titles
            val junkRegex = Regex("""(?i)(English Sub|Multiple Subtitles|Subtitles|Good Sub|Download Link|Download Linl|\(4K\)|\[4K\]|\(1080p\)|\[1080p\]|4K|1080p|720p)""")

            // Locate the exact anchor of the episode number
            val epMatch = Regex("""(?i)(?:Ep|Eps|Episode|Ep\.)\s*(\d+)""").findAll(rawTitle).lastOrNull()
            
            if (epMatch != null) {
                episodeNum = epMatch.groupValues[1].toIntOrNull()
                
                // Delete everything BEFORE the number (the show title). 
                // Keep only what comes AFTER the number (chapter titles).
                val afterText = rawTitle.substring(epMatch.range.last + 1)
                val cleanAfter = afterText.replace(junkRegex, "").trim(' ', '-', ':', ',', '|', '(', ')')
                
                epName = if (cleanAfter.isNotBlank()) {
                    "Episode $episodeNum: $cleanAfter"
                } else {
                    "Episode $episodeNum"
                }
            } else {
                // FALLBACK: If the word "Ep" is missing entirely
                val numbers = Regex("""\d+""").findAll(rawTitle).map { it.value }.toList()
                episodeNum = numbers.lastOrNull { num ->
                    num != "4" && num != "1080" && num != "720" && num != "2160"
                }?.toIntOrNull()

                val cleanTitle = rawTitle.replace(junkRegex, "").trim(' ', '-', ':', ',', '|', '(', ')')
                
                epName = if (episodeNum != null) {
                    if (cleanTitle.contains(episodeNum.toString())) "Episode $episodeNum" else cleanTitle
                } else {
                    cleanTitle.ifEmpty { "Episode" }
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
                    loadExtractor(finalUrl, referer = extReferer, subtitleCallback) { link ->
                        val isDailymotion = finalUrl.contains("dailymotion", ignoreCase = true)
                        
                        if (isDailymotion) {
                            val isHls = link.isM3u8 || link.url.contains(".m3u8", ignoreCase = true)
                            
                            @Suppress("DEPRECATION")
                            if (isHls) {
                                // Keep HLS, but label it and tank its quality score so MP4 auto-plays first
                                callback(
                                    ExtractorLink(
                                        source = link.source,
                                        name = "${link.name} (HLS)",
                                        url = link.url,
                                        referer = link.referer,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = link.isM3u8,
                                        headers = link.headers,
                                        extractorData = link.extractorData
                                    )
                                )
                            } else {
                                // Explicitly mark MP4s and let them keep their natural, higher quality score
                                callback(
                                    ExtractorLink(
                                        source = link.source,
                                        name = "${link.name} (MP4)",
                                        url = link.url,
                                        referer = link.referer,
                                        quality = link.quality,
                                        isM3u8 = link.isM3u8,
                                        headers = link.headers,
                                        extractorData = link.extractorData
                                    )
                                )
                            }
                        } else {
                            // Standard callback for non-Dailymotion links
                            callback(link)
                        }
                    }
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
