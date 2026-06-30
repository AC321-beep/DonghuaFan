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

        // 🔴 Smart Episode Parser (Supports grids and lists safely)
        document.select(".eplister ul li a, #episode_list li a, .listeps ul li a, .bxcl ul li a, .epcl li a").forEach { linkElement ->
            val epHref = fixUrlNull(linkElement.attr("href")) ?: return@forEach
            
            val rawName = linkElement.selectFirst(".epl-num, .epnum")?.text()?.trim() 
                ?: linkElement.ownText().trim().takeIf { it.isNotBlank() }
                ?: linkElement.text().trim()
            
            val epTitle = linkElement.selectFirst(".epl-title, .title")?.text()?.trim()
            val epDate = linkElement.selectFirst(".epl-date, .date")?.text()?.trim()

            val epNum = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(rawName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""").findAll(rawName).lastOrNull()?.value?.toIntOrNull()

            val cleanName = if (rawName.matches(Regex("""^\d+$"""))) {
                "Episode $rawName"
            } else if (rawName.contains("Episode", ignoreCase = true)) {
                rawName
            } else {
                epNum?.let { "Episode $it" } ?: rawName
            }

            val finalName = if (!epTitle.isNullOrBlank() && !cleanName.contains(epTitle)) {
                "$cleanName - $epTitle"
            } else cleanName

            episodes.add(
                newEpisode(data = epHref) {
                    this.name = finalName
                    this.episode = epNum
                    this.date = epDate
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        var anyStreamFound = false

        // 🔴 Dual-Extractor: Pulls URLs from both iframes AND hidden player_aaaa JSON
        suspend fun extractVideoLinks(html: String, refererUrl: String) {
            val doc = Jsoup.parse(html)
            val urlsToProcess = mutableListOf<String>()

            // 1. Check for standard iframes
            doc.select(".player-embed iframe, #pembed iframe, .playcon iframe, iframe").forEach { iframe ->
                var rawSrc = iframe.attr("src")
                if (rawSrc.isBlank() || rawSrc == "about:blank" || rawSrc.contains("data:image")) {
                    rawSrc = iframe.attr("data-src").takeIf { it.isNotBlank() } 
                             ?: iframe.attr("data-lazy-src").takeIf { it.isNotBlank() } 
                             ?: ""
                }
                if (rawSrc.startsWith("//")) rawSrc = "https:$rawSrc"
                fixUrlNull(rawSrc)?.let { urlsToProcess.add(it) }
            }

            // 2. Check for hidden player_aaaa script (Catches Dailymotion missing links!)
            val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            if (playerJson != null) {
                var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                else if (encrypt == 2) {
                    rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                }
                if (rawUrl.isNotBlank()) urlsToProcess.add(rawUrl)
            }

            // 3. Process every link we found
            urlsToProcess.distinct().filter { it.isNotBlank() && !it.contains("about:blank") }.forEach { clean ->
                
                // --- VIDHIDE ALIAS FIX ---
                if (clean.contains("yurn.online", ignoreCase = true) || clean.contains("vidhide", ignoreCase = true)) {
                    val vidhideUrl = clean.replace(Regex("""(yurn\.online|vidhide[a-z0-9A-Z]*\.[a-z]+)"""), "vidhidepro.com")
                    if (loadExtractor(vidhideUrl, refererUrl, subtitleCallback, callback)) {
                        anyStreamFound = true
                    }
                    return@forEach
                }

                // --- DAILYMOTION MANUAL EXTRACTION OVERRIDE ---
                if ("dailymotion" in clean) {
                    val token = Regex("""(?:video=|/video/|/embed/video/)([^&?"']+)""").find(clean)?.groupValues?.get(1)
                    if (token != null) {
                        var foundDm = false
                        try {
                            val apiResponse = app.get(
                                "https://www.dailymotion.com/player/metadata/video/$token",
                                headers = mapOf(
                                    "User-Agent" to defaultHeaders["User-Agent"]!!,
                                    "Referer" to refererUrl
                                )
                            ).text
                            
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

        val baseHtml = try { app.get(data, headers = defaultHeaders, timeout = 15000).text } catch(e: Exception) { return@coroutineScope false }
        val baseDocument = Jsoup.parse(baseHtml)
        
        extractVideoLinks(baseHtml, data)

        val mirrorUrls = baseDocument.select("select.mirror option").mapNotNull { option ->
            val url = option.attr("value")
            if (url.startsWith("http") && url != data) url else null
        }.distinct()

        mirrorUrls.mapIndexed { index, mirrorUrl ->
            async {
                delay(index * 300L) 
                try {
                    val mirrorHtml = app.get(mirrorUrl, headers = defaultHeaders, timeout = 15000).text
                    extractVideoLinks(mirrorHtml, mirrorUrl)
                } catch (e: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope anyStreamFound
    }
}
