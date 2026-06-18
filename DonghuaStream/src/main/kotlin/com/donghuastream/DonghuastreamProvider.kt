package com.Donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

open class Donghuastream : MainAPI() {
    override var mainUrl          = "https://donghuastream.org"
    override var name             = "Donghuastream"
    override val hasMainPage      = true
    override var lang             = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes   = setOf(TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    // IMPROVEMENT: Utilizing Phisher98's native URL structure (type=special)
    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "anime/?status=completed&type=&order=update&page=" to "Completed",
        "anime/?status=&type=special&order=update&page=" to "Special Edition" 
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = try { app.get(url).document } catch (e: Exception) { null }
        val elements = document?.select("div.listupd > article") ?: emptyList()

        var items = elements.mapNotNull { it.toSearchResult() }

        // Apply strict keyword and episode constraints ONLY to the Special Edition row
        if (request.name == "Special Edition") {
            val keywords = listOf("special", "edition", "part 1", "part 01", "ova") 
            
            items = elements.filter { element ->
                val title = element.selectFirst("div.bsx > a")?.attr("title").toString()
                    .ifEmpty { element.text() }
                    .lowercase() // Case insensitive check

                val hasKeyword = keywords.any { title.contains(it) }

                // Default to 1 if no number is found (e.g., standard movies)
                val epText = element.selectFirst(".epx, .ep")?.text() ?: ""
                val epCount = Regex("""\d+""").find(epText)?.value?.toIntOrNull() ?: 1

                hasKeyword && epCount <= 6
            }.mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            // Let the native website pagination handle "hasNext"
            hasNext = elements.isNotEmpty() 
        )
    }

    fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("div.bsx > a").attr("title").ifEmpty { this.text() }
        val href      = fixUrl(this.select("div.bsx > a").attr("href"))
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
        val title       = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href = document.selectFirst(".eplister li > a")?.attr("href") ?:""
        var poster = document.select("div.ime > img").attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().toString()
        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries
        
        return if (tvtag == TvType.TvSeries) {
            val Eppage= document.selectFirst(".eplister li > a")?.attr("href") ?:""
            val doc= app.get(Eppage).document
            val episodes=doc.select("div.episodelist > ul > li").map { info->
                val href1 = info.select("a").attr("href")
                val episode = info.select("a span").text().substringAfter("-").substringBeforeLast("-")
                val posterr=info.selectFirst("a img")?.attr("data-src") ?:""
                newEpisode(href1) {
                    this.name=episode.replace(title,"",ignoreCase = true)
                    this.episode=episode.toIntOrNull()
                    this.posterUrl=posterr
                }
            }
            if (poster.isEmpty()) {
                poster=document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            if (poster.isEmpty()) {
                poster=document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
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
        val html = app.get(data, headers = defaultHeaders).document
        val options = html.select("option[data-index]")

        // Helper function to extract Dailymotion tokens natively
        suspend fun extractDailymotionToken(pageUrl: String): String? {
            val pageHtml = try { app.get(pageUrl, headers = defaultHeaders).text } catch (e: Exception) { return null }
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

                if (from.equals("dailymotion", ignoreCase = true)) return rawUrl
            }
            return null
        }

        // Parallel processing block
        options.amap { option ->
            val base64 = option.attr("value")
            if (base64.isBlank()) return@amap
            val label = option.text().trim()
            val decodedHtml = try {
                base64Decode(base64)
            } catch (_: Exception) {
                return@amap
            }

            val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")?.let(::httpsify)
            if (iframeUrl.isNullOrEmpty()) return@amap
            
            // Dailymotion unlocker route
            if (label.contains("dailymotion", ignoreCase = true) || "dailymotion" in iframeUrl) {
                var token = Regex("""[?&]video=([^&]+)""").find(iframeUrl)?.groupValues?.get(1)
                if (token == null) token = extractDailymotionToken(iframeUrl)
                if (token != null) {
                    val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$token"
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    return@amap
                }
            }

            // Standard fallback routes
            when {
                "vidmoly" in iframeUrl -> {
                    val cleanedUrl = "http:" + iframeUrl.substringAfter("=\"").substringBefore("\"")
                    loadExtractor(cleanedUrl, referer = iframeUrl, subtitleCallback, callback)
                }
                iframeUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(
                            label,
                            label,
                            url = iframeUrl,
                            INFER_TYPE
                        ) {
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
