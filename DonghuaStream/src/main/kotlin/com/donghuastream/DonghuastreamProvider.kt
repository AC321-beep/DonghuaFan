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
            val movieDoc = try { app.get(movieUrl).document } catch(e: Exception) { null }
            val movieResults = movieDoc?.select("div.listupd > article")?.mapNotNull { it.toSearchResult() } ?: emptyList()

            // 2. Fetch "special" results
            val specialUrl = if (page == 1) "$mainUrl/?s=special" else "$mainUrl/pagg/$page/?s=special"
            val specialDoc = try { app.get(specialUrl).document } catch(e: Exception) { null }
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
            val url = "$mainUrl/${request.data}$page"
            val document = app.get(url).document
            val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            
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
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
        var poster = document.select("div.ime > img").attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().toString()
        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val epPage = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
            val doc = app.get(epPage).document
            val episodes = doc.select("div.episodelist > ul > li").map { info ->
                val href1 = info.select("a").attr("href")
                val episode = info.select("a span").text().substringAfter("-").substringBeforeLast("-")
                val posterr = info.selectFirst("a img")?.attr("data-src") ?: ""
                newEpisode(href1) {
                    this.name = episode.replace(title, "", ignoreCase = true)
                    this.episode = episode.toIntOrNull()
                    this.posterUrl = posterr
                }
            }
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
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

        // ----- Helper function to extract Dailymotion token from a page (mirrors DonghuaFun logic) -----
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

            // ----- Dailymotion branch (improved with DonghuaFun logic) -----
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
