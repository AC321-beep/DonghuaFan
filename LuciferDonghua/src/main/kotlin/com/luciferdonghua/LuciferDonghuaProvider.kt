package com.luciferdonghua

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlinx.coroutines.* // Required for parallel mirror processing

class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "Lucifer Donghua"
    override val hasMainPage = true
    override var lang = "zh"
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

        val title = document.selectFirst("h1.entry-title, .title")?.text()?.trim() ?: return null
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
            
            val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()

            val cleanName = if (epName.contains("Episode", ignoreCase = true)) epName else "Episode $epName"

            episodes.add(
                newEpisode(data = epHref) {
                    this.name = cleanName
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
        val pageHtml = try { app.get(pageUrl, headers = defaultHeaders, timeout = 15000).text } catch (e: Exception) { return null }
        val pageDoc = try { Jsoup.parse(pageHtml) } catch (e: Exception) { null }

        pageDoc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
            val src = iframe.attr("src")
            val match = Regex("""(?:video=|/video/|/embed/video/)([^&?"']+)""").find(src)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        var anyStreamFound = false

        suspend fun extractIframes(doc: org.jsoup.nodes.Document, refererUrl: String) {
            doc.select(".player-embed iframe, #pembed iframe, .playcon iframe, iframe").forEach { iframe ->
                
                var rawSrc = iframe.attr("src")
                if (rawSrc.isBlank() || rawSrc == "about:blank" || rawSrc.contains("data:image")) {
                    rawSrc = iframe.attr("data-src").takeIf { it.isNotBlank() } 
                             ?: iframe.attr("data-lazy-src").takeIf { it.isNotBlank() } 
                             ?: ""
                }
                
                if (rawSrc.startsWith("//")) rawSrc = "https:$rawSrc"
                val clean = fixUrlNull(rawSrc) ?: return@forEach
                
                if (clean.isNotBlank() && !clean.contains("about:blank")) {
                    
                    // --- VIDHIDE ALIAS FIX ---
                    if (clean.contains("yurn.online", ignoreCase = true) || clean.contains("vidhide", ignoreCase = true)) {
                        val vidhideUrl = clean.replace(Regex("""(yurn\.online|vidhide[a-z0-9A-Z]*\.[a-z]+)"""), "vidhidepro.com")
                        if (loadExtractor(vidhideUrl, refererUrl, subtitleCallback, callback)) {
                            anyStreamFound = true
                        }
                        return@forEach
                    }

                    // --- DAILYMOTION DOMAIN SPOOFING BYPASS ---
                    if ("dailymotion" in clean) {
                        var token = Regex("""(?:video=|/video/|/embed/video/)([^&?"']+)""").find(clean)?.groupValues?.get(1)
                        if (token == null) token = extractDailymotionToken(refererUrl)
                        
                        if (token != null) {
                            var foundDm = false
                            try {
                                // 🔴 FIX: Injects the Referer to bypass the 403 Forbidden error on geo/private videos
                                val apiResponse = app.get(
                                    "https://www.dailymotion.com/player/metadata/video/$token",
                                    headers = mapOf(
                                        "User-Agent" to defaultHeaders["User-Agent"]!!,
                                        "Referer" to refererUrl
                                    )
                                ).text
                                
                                // Broader Regex to catch URL regardless of structure changes
                                val m3u8Match = Regex("""(?:\"url\"|\"stream_url\")\s*:\s*\"([^\"]+\.m3u8[^\"]*)\"""")
                                    .find(apiResponse)?.groupValues?.get(1)?.replace("\\/", "/")

                                if (m3u8Match != null) {
                                    M3u8Helper.generateM3u8("Dailymotion", m3u8Match, "https://www.dailymotion.com/").forEach { link ->
                                        callback(link)
                                        foundDm = true
                                        anyStreamFound = true
                                    }
                                }
                            } catch (e: Exception) {}

                            if (!foundDm) {
                                val embedUrl = "https://www.dailymotion.com/video/$token"
                                if (loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)) {
                                    anyStreamFound = true
                                }
                            }
                            return@forEach 
                        }
                    }

                    // --- OK.RU REGEX BYPASS FIX ---
                    var okruUrl = clean
                    if (okruUrl.contains("ok.ru", ignoreCase = true)) {
                        okruUrl = okruUrl.substringBefore("?")
                        okruUrl = okruUrl.replace("videoembed", "video")
                    }

                    // --- STANDARD EXTRACTORS ---
                    if (loadExtractor(okruUrl, refererUrl, subtitleCallback, callback)) {
                        anyStreamFound = true
                    } else {
                        try {
                            val iframeHtml = app.get(okruUrl, headers = mapOf("Referer" to refererUrl), timeout = 15000).text
                            val rawStreamRegex = Regex("""["'](https?[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                            rawStreamRegex.findAll(iframeHtml).forEach { match ->
                                val cleanStreamUrl = match.groupValues[1].replace("\\/", "/")
                                callback(
                                    newExtractorLink(name, "Fallback Server", cleanStreamUrl, INFER_TYPE) {
                                        this.referer = okruUrl
                                    }
                                )
                                anyStreamFound = true
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }

        // 1️⃣ Fetch primary page with explicit 15s timeout
        val baseDocument = try { app.get(data, headers = defaultHeaders, timeout = 15000).document } catch(e: Exception) { return@coroutineScope false }
        extractIframes(baseDocument, data)

        val mirrorUrls = baseDocument.select("select.mirror option").mapNotNull { option ->
            val url = option.attr("value")
            if (url.startsWith("http") && url != data) url else null
        }.distinct()

        // 🔴 FIX: Staggered fetching to bypass WordPress/Cloudflare DDoS protection
        mirrorUrls.mapIndexed { index, mirrorUrl ->
            async {
                delay(index * 300L) // Waits 0.3s, 0.6s, 0.9s between requests so the site firewall stays calm
                try {
                    val mirrorDoc = app.get(mirrorUrl, headers = defaultHeaders, timeout = 15000).document
                    extractIframes(mirrorDoc, mirrorUrl)
                } catch (e: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope anyStreamFound
    }
}
