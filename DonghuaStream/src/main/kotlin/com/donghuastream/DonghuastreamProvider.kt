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
        // --- Custom Dual-Fetch Logic for Special Edition ---
        if (request.name == "Special Edition") {
            // 1. Fetch "movie" results
            val movieUrl = if (page == 1) "$mainUrl/?s=movie" else "$mainUrl/pagg/$page/?s=movie"
            val movieDoc = try { app.get(movieUrl, cacheTime = 0).document } catch(e: Exception) { null }
            val movieResults = movieDoc?.select("div.listupd > article")?.mapNotNull { it.toSearchResult() } ?: emptyList()

            // 2. Fetch "special" results
            val specialUrl = if (page == 1) "$mainUrl/?s=special" else "$mainUrl/pagg/$page/?s=special"
            val specialDoc = try { app.get(specialUrl, cacheTime = 0).document } catch(e: Exception) { null }
            val specialResults = specialDoc?.select("div.listupd > article")?.mapNotNull { it.toSearchResult() } ?: emptyList()

            // 3. Combine both lists and remove any duplicates
            val combinedResults = (movieResults + specialResults).distinctBy { it.url }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = combinedResults,
                    isHorizontalImages = false
                ),
                hasNext = movieResults.isNotEmpty() || specialResults.isNotEmpty()
            )
        } 
        // --- Standard Logic for Recently Updated ---
        else {
            // Page 1 uses the root homepage to bypass filter delays. Page 2+ uses infinite scroll URLs.
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
                // EXACT FIX: We target the specific "latesthome" class found in the site's source HTML.
                // We find the latesthome header, go to its parent container, and select all articles inside it.
                document.selectFirst("div.releases.latesthome")
                    ?.parent()
                    ?.select("article")
                    ?.mapNotNull { it.toSearchResult() } ?: emptyList()
            } else {
                // Page 2 and beyond use the standard filter page structure
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
            // Dynamically find Series URL from breadcrumbs
            val seriesUrl = document.select("div.ts-breadcrumb a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select(".naveps a").find { it.attr("href").contains("/anime/") }?.attr("href")
                ?: document.select("div.ts-breadcrumb a").lastOrNull()?.attr("href") 
            
            // Redirect to the Series page so the user can see all episodes
            if (!seriesUrl.isNullOrEmpty() && seriesUrl != url) {
                return load(seriesUrl)
            }
            
            // SECONDARY FALLBACK: Scrape from episode page
            val titleRaw = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
            val title = titleRaw.substringBefore(" Episode").trim() 
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            
            val epElements = document.select("div.episodelist > ul > li")
            if (epElements.isNotEmpty()) {
                val episodes = epElements.mapNotNull { info ->
                    val href1 = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val episodeText = info.text().trim()
                    val posterr = info.selectFirst("a img")?.attr("data-src") ?: ""
                    
                    // --- DYNAMIC PARSING LOGIC ---
                    val textWithoutTitle = episodeText.replace(title, "", ignoreCase = true)
                    val allNumbers = Regex("""\d+""").findAll(textWithoutTitle).map { it.value }.toList()
                    
                    val episodeNumber = allNumbers.firstOrNull { num ->
                        // Dynamically ignore resolutions so "4K" doesn't become Episode 4
                        val isResolution = (num == "4" && textWithoutTitle.contains("4K", true)) || 
                                           (num == "1080" && textWithoutTitle.contains("1080", true))
                        !isResolution
                    }?.toIntOrNull() ?: allNumbers.firstOrNull()?.toIntOrNull()
                    
                    val epName = textWithoutTitle.trim(' ', '-', '|', ':').ifBlank { episodeText }

                    newEpisode(href1) {
                        this.name = epName
                        this.episode = episodeNumber
                        this.posterUrl = posterr
                    }
                }.reversed()
                
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
            // Optimization: Try to get episodes directly from the Series page first
            var epElements = document.select(".eplister > ul > li")
            
            // Fallback: If not on the Series page, fetch the first episode page to get the list
            if (epElements.isEmpty()) {
                val epPage = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
                if (epPage.isNotBlank()) {
                    epElements = app.get(epPage).document.select("div.episodelist > ul > li")
                }
            }
            
            val episodes = epElements.mapNotNull { info ->
                val href1 = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val episodeText = info.text().trim()
                val posterr = info.selectFirst("a img")?.attr("data-src") ?: ""
                
                // --- DYNAMIC PARSING LOGIC ---
                // 1. Remove the series title to eliminate interfering numbers (like 100.000)
                val textWithoutTitle = episodeText.replace(title, "", ignoreCase = true)
                
                // 2. Extract all remaining numbers in the text
                val allNumbers = Regex("""\d+""").findAll(textWithoutTitle).map { it.value }.toList()
                
                // 3. Pick the first number (dynamically bypassing video resolutions)
                val episodeNumber = allNumbers.firstOrNull { num ->
                    val isResolution = (num == "4" && textWithoutTitle.contains("4K", true)) || 
                                       (num == "1080" && textWithoutTitle.contains("1080", true))
                    !isResolution
                }?.toIntOrNull() ?: allNumbers.firstOrNull()?.toIntOrNull()
                
                // 4. Format a clean display name
                val epName = textWithoutTitle.trim(' ', '-', '|', ':').ifBlank { episodeText }
                
                newEpisode(href1) {
                    this.name = epName
                    this.episode = episodeNumber
                    this.posterUrl = posterr
                }
            }.reversed()
            
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document
        val options = doc.select("option[data-index]")

        // ----- Helper function to extract Dailymotion token from a page -----
        suspend fun extractDailymotionToken(pageUrl: String): String? {
            val pageHtml = try {
                app.get(pageUrl, headers = defaultHeaders).text
            } catch (e: Exception) { return null }
            val pageDoc = try { Jsoup.parse(pageHtml) } catch (e: Exception) { null }

            // 1) Look for iframe with dailymotion in src
            pageDoc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
                val src = iframe.attr("src")
                val match = Regex("""[?&]video=([^&]+)""").find(src)
                if (match != null) return match.groupValues[1]
            }

            // 2) Fallback: parse player_aaaa JSON
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

            // ----- Dailymotion branch -----
            if (label.contains("dailymotion", ignoreCase = true) || "dailymotion" in iframeUrl) {
                var token: String? = null
                // First try to get token from the iframe URL itself
                token = Regex("""[?&]video=([^&]+)""").find(iframeUrl)?.groupValues?.get(1)
                // If not found, fetch the iframe page and extract using the helper
                if (token == null) {
                    token = extractDailymotionToken(iframeUrl)
                }
                if (token != null) {
                    val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$token"
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        continue // success, move to next mirror
                    }
                }
                // If token extraction failed, fall through to generic extractor
            }

            // ----- Known extractors for other domains -----
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
