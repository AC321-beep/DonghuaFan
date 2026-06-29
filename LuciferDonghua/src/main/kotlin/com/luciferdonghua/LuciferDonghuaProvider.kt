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

    // Custom headers – essential for Dailymotion and to avoid blocking
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
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
            val epName = linkElement.selectFirst(".epl-num, .epnum")?.text()?.trim()
                ?: ep.text().trim()
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

    // ----- Dailymotion token extraction (matching Donghuastream logic) -----
    private suspend fun extractDailymotionToken(pageUrl: String): String? {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = defaultHeaders).document

        // Helper: try to extract from a page (mirror or main)
        suspend fun tryExtractFromPage(pageUrl: String, pageDoc: org.jsoup.nodes.Document): Boolean {
            // 1. Try any iframe via loadExtractor with the pageUrl as referer
            val iframe = pageDoc.selectFirst("iframe[src]")
            if (iframe != null) {
                var src = iframe.attr("src")
                if (src.startsWith("//")) src = "https:$src"
                val clean = fixUrlNull(src)
                if (clean != null && clean.isNotBlank()) {
                    // Special Dailymotion handling
                    if ("dailymotion" in clean) {
                        var token = Regex("""[?&]video=([^&]+)""").find(clean)?.groupValues?.get(1)
                        if (token == null) {
                            token = extractDailymotionToken(clean)
                        }
                        if (token != null) {
                            val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$token"
                            val success = loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)
                            if (success) return true
                        }
                        // If token extraction failed, fall through to generic loadExtractor
                    }

                    val success = loadExtractor(clean, pageUrl, subtitleCallback, callback)
                    if (success) return true
                }
            }

            // 2. Try to use loadExtractor on the page itself (in case the page is a player)
            val pageExtractSuccess = loadExtractor(pageUrl, pageUrl, subtitleCallback, callback)
            if (pageExtractSuccess) return true

            // 3. Fallback: regex for video URLs in the page
            val scriptRegex = Regex("""(?:file|video_url|source|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4))["']""")
            pageDoc.select("script").forEach { script ->
                scriptRegex.find(script.html())?.let { match ->
                    val videoUrl = match.groupValues[1]
                    callback(
                        newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                            this.referer = pageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }

            // 4. Check for video tag
            val video = pageDoc.selectFirst("video[src]")
            if (video != null) {
                callback(
                    newExtractorLink(name, name, video.attr("src"), ExtractorLinkType.M3U8) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            return false
        }

        // 1️⃣ Default iframe (Dailymotion, Rumble, etc.) with data as referer
        val defaultIframe = document.selectFirst("#pembed iframe, .player-embed iframe")
        if (defaultIframe != null) {
            var src = defaultIframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            val clean = fixUrlNull(src)
            if (clean != null && clean.isNotBlank()) {
                // Special Dailymotion handling
                if ("dailymotion" in clean) {
                    var token = Regex("""[?&]video=([^&]+)""").find(clean)?.groupValues?.get(1)
                    if (token == null) {
                        token = extractDailymotionToken(clean)
                    }
                    if (token != null) {
                        val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$token"
                        loadExtractor(embedUrl, data, subtitleCallback, callback)
                    } else {
                        loadExtractor(clean, data, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(clean, data, subtitleCallback, callback)
                }
            }
        }

        // 2️⃣ Mirror dropdown – fetch each mirror page and extract
        val mirrorSelect = document.selectFirst("select.mirror")
        if (mirrorSelect != null) {
            val options = mirrorSelect.select("option")
            val mirrors = options.mapNotNull { option ->
                val value = option.attr("value")
                val label = option.text()
                if (value.isNotBlank() && !label.contains("Select", ignoreCase = true)) {
                    label to value
                } else null
            }

            mirrors.forEach { (label, suffix) ->
                val mirrorUrl = "$data$suffix"
                try {
                    val mirrorDoc = app.get(mirrorUrl, headers = defaultHeaders).document
                    tryExtractFromPage(mirrorUrl, mirrorDoc)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        // 3️⃣ Final regex fallback on main page (if nothing else worked)
        val scriptRegex = Regex("""(?:file|video_url|source|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4))["']""")
        document.select("script").forEach { script ->
            scriptRegex.find(script.html())?.let { match ->
                val videoUrl = match.groupValues[1]
                callback(
                    newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
