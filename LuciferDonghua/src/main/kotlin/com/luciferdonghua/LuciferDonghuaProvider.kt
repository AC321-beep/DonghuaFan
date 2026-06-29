package com.luciferdonghua

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "Lucifer Donghua"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=movie&sub=" to "Movies",
        "$mainUrl/network/tencent/" to "Tencent Anime",
        "$mainUrl/network/youku/" to "YouKu Anime",
        "$mainUrl/anime/?status=completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = defaultHeaders).document
        val home = document.select("article.bs").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".tt h2")
        val title = titleElement?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a[itemprop=url]")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.ts-post-image")?.attr("src"))
        val epText = this.selectFirst(".bt .epx")?.text()
        val epCount = epText?.let {
            Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true, epCount)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url, headers = defaultHeaders).document
        val results = document.select("article.bs").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".thumb img, .poster img")?.attr("src"))
        val description = document.selectFirst(".entry-content p, .synopsis p, .desc")?.text()?.trim()
        val tags = document.select(".genx a, .genres a").map { it.text() }

        val yearText = document.selectFirst(".split span:contains(Released), .info-content span:contains(Year)")?.text()
        val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        document.select(".eplister ul li, #episode_list li, .listeps ul li").forEach { ep ->
            val linkElement = ep.selectFirst("a") ?: return@forEach
            val epHref = fixUrlNull(linkElement.attr("href")) ?: return@forEach
            val epName = linkElement.selectFirst(".epl-num, .epnum")?.text()?.trim() ?: ep.text().trim()
            val epNum = epName.filter { it.isDigit() }.toIntOrNull()

            episodes.add(
                newEpisode(data = epHref) {
                    this.name = "Episode $epName"
                    this.episode = epNum
                }
            )
        }

        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }
        val episodeMap = mutableMapOf(
            DubStatus.Subbed to sortedEpisodes
        )

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.episodes = episodeMap
        }
    }

    private suspend fun extractDailymotionToken(pageUrl: String): String? {
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

    private suspend fun tryExtractFromPage(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val html = try { app.get(pageUrl, headers = architectureHeaders(pageUrl)).text } catch (e: Exception) { return false }
        val doc = Jsoup.parse(html)

        doc.select("iframe, .player-embed iframe, div[id*='player'] iframe").forEach { iframe ->
            var iframeUrl = iframe.attr("src")
            if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
            val cleanUrl = fixUrlNull(iframeUrl)
            
            if (cleanUrl != null && !cleanUrl.contains("about:blank")) {
                if (loadExtractor(cleanUrl, pageUrl, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        
        // Final fallback script-comber for loose stream configurations
        val rawStreamRegex = Regex("""["'](https?[^"']+\.(?:m3u8|mpd|mp4)[^"']*)["']""")
        rawStreamRegex.findAll(html).forEach { match ->
            val cleanStreamUrl = match.groupValues[1].replace("\\/", "/")
            val linkType = if (cleanStreamUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
            
            callback(
                ExtractorLink(
                    source = "Lucifer Mirror",
                    name = "Lucifer Mirror",
                    url = cleanStreamUrl,
                    referer = pageUrl,
                    quality = Qualities.Unknown.value,
                    type = linkType,
                    headers = architectureHeaders(pageUrl)
                )
            )
            found = true
        }
        
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // First try processing the base page itself
        var anyStreamFound = tryExtractFromPage(data, subtitleCallback, callback)
        
        val baseDocument = try { app.get(data, headers = architectureHeaders(data)).document } catch(e: Exception) { return false }
        
        // Scan for alternative options/servers in the WordPress selector dropdowns
        baseDocument.select(".custom-menu li a, .player-option, #player-option-list li").forEach { option ->
            val optionValue = option.attr("data-value") ?: option.attr("value") ?: ""
            if (optionValue.isNotBlank()) {
                // Correctly resolve paths whether they are absolute links or relative parameters
                val targetUrl = when {
                    optionValue.startsWith("http") -> optionValue
                    optionValue.startsWith("?") -> "${data.substringBefore("?")}$optionValue"
                    else -> "${data.removeSuffix("/")}/${optionValue.removePrefix("/")}"
                }
                
                val success = tryExtractFromPage(targetUrl, subtitleCallback, callback)
                if (success) anyStreamFound = true
            }
        }

        return anyStreamFound
    }

    private fun architectureHeaders(url: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to url,
            "Origin" to mainUrl
        )
    }
                             }
                             
