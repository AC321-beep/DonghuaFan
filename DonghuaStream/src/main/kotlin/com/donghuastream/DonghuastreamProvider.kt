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

    // Custom headers to mimic a real browser (improves Dailymotion extraction)
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
        
        // 1. Detect if we are on a direct Episode page (missing the .eplister container)
        val isEpisodePage = document.selectFirst(".eplister") == null
        
        if (isEpisodePage) {
            val seriesUrl = document.select("div.ts-breadcrumb a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select(".naveps a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select("div.ts-breadcrumb a").lastOrNull()?.attr("href") 
            
            if (!seriesUrl.isNullOrEmpty() && seriesUrl != url) {
                return load(seriesUrl)
            }
            
            // SECONDARY FALLBACK: Scrape from episode page
            val titleRaw = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
            val title = titleRaw.substringBefore(" Episode").trim() 
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            
            val epElements = document.select("div.episodelist > ul > li")
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

        // 2. STANDARD SERIES PAGE LOGIC
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        var poster = document.selectFirst("div.ime > img")?.attr("data-src") ?: ""
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text() ?: ""
        val tvtag = if (type.contains("Movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            var epElements = document.select(".eplister > ul > li")
            
            if (epElements.isEmpty()) {
                val epPage = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
                if (epPage.isNotBlank()) {
                    epElements = app.get(epPage).document.select("div.episodelist > ul > li")
                }
            }
            
            val episodes = parseEpisodes(epElements, title)
            
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val href = document.selectFirst(".eplister li > a")?.attr("href") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // --- NEW HELPER FUNCTION TO PARSE EPISODES FLAWLESSLY ---
    private fun parseEpisodes(epElements: org.jsoup.select.Elements, title: String): List<Episode> {
        return epElements.mapNotNull { info ->
            val aTag = info.selectFirst("a")
            val href1 = aTag?.attr("href") ?: return@mapNotNull null
            
            // 1. ISOLATE CLEAN TEXT: Prefer .epl-title to avoid capturing hidden badges, metadata, or typos
            var cleanTitle = info.selectFirst(".epl-title")?.text()?.trim() ?: ""
            if (cleanTitle.isEmpty()) cleanTitle = aTag.ownText().trim() // Skips child spans
            if (cleanTitle.isEmpty()) cleanTitle = info.selectFirst(".title, .ep-title, h2, h3")?.text()?.trim() ?: ""
            if (cleanTitle.isEmpty()) cleanTitle = aTag.text().trim()
            
            // 2. EPISODE NUMBER EXTRACTION
            // Remove the show title to prevent number interference (e.g., "100.000 years")
            var textWithoutTitle = cleanTitle.replace(title, "", ignoreCase = true).trim()
            if (textWithoutTitle.isEmpty()) textWithoutTitle = cleanTitle

            // Try strict format first (e.g., "Episode 14")
            var episodeNumber = Regex("""(?i)(?:Ep|Eps|Episode)\s*(\d+)""").find(textWithoutTitle)?.groupValues?.get(1)?.toIntOrNull()
            
            // Dynamic fallback if "Episode X" is completely missing
            if (episodeNumber == null) {
                val allNumbers = Regex("""\d+""").findAll(textWithoutTitle).map { it.value }.toList()
                episodeNumber = allNumbers.firstOrNull { num ->
                    val isResolution = (num == "4" && textWithoutTitle.contains("4K", true)) || 
                                       (num == "1080" && textWithoutTitle.contains("1080", true))
                    !isResolution
                }?.toIntOrNull()
            }
            
            // 3. CLEAN DISPLAY NAME
            // Removes any typos or visual clutter admins left behind
            var epName = textWithoutTitle
                .replace(Regex("""(?i)(Multiple Subtitles|Subtitles|Good Sub|Download Link|Download Linl|\(4K\)|\[4K\]|\(1080p\)|\[1080p\])"""), "")
                .trim(' ', '+', '-', '|', ':', '(', ')')
                
            // If the name is blank or just a raw number after cleaning, format it nicely
            if (epName.isBlank() || epName.matches(Regex("""^\d+$"""))) {
                epName = if (episodeNumber != null) "Episode $episodeNumber" else cleanTitle
            }
            
            val posterr = info.selectFirst("a img")?.attr("data-src") ?: ""
            
            newEpisode(href1) {
                this.name = epName
                this.episode = episodeNumber
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

        suspend fun extractDailymotionToken(pageUrl: String): String? {
            val pageHtml = try {
                app.get(pageUrl, headers = defaultHeaders).text
            } catch (e: Exception) { return null }
            val pageDoc = try { Jsoup.parse(pageHtml) } catch (e: Exception) { null }

            pageDoc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
                val src = iframe.attr("src")
                val match = Regex("""[?&]video=([^&]+)""").find(src)
                if (match != null) return match.groupValues[1]
            }

            val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(pageHtml)?.groupValues?.get(1)
            if (playerJson != null) {
                var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
                val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                else if (encrypt == 2) {
                    rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                }

                if (from.equals("dailymotion", ignoreCase = true)) {
                    return rawUrl
                }
            }
            return null
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
            if (iframeUrl.isNullOrEmpty()) continue

            if (label.contains("dailymotion", ignoreCase = true) || "dailymotion" in iframeUrl) {
                var token: String? = null
                token = Regex("""[?&]video=([^&]+)""").find(iframeUrl)?.groupValues?.get(1)
                if (token == null) {
                    token = extractDailymotionToken(iframeUrl)
                }
                if (token != null) {
                    val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$token"
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        continue
                    }
                }
            }

            when {
                "rumble.com" in iframeUrl -> {
                    Rumble().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
                }
                "play.streamplay.co.in" in iframeUrl -> {
                    PlayStreamplay().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
                }
                iframeUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(label, label, iframeUrl, INFER_TYPE) {
                            this.referer = ""
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
                else -> {
                    loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
