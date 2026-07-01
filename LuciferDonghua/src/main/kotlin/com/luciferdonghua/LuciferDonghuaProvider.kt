package com.luciferdonghua

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlinx.coroutines.* 

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

    // --- Dynamic Season Helpers ---
    private fun extractSeasonNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""(?i)(?:Season|S|Part|Book)\s*(\d+)|(\d+)(?:st|nd|rd|th)\s*Season""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: match?.groupValues?.get(2)?.toIntOrNull()
    }

    private fun getBaseTitle(text: String): String {
        return text.replace(Regex("""(?i)\s*(?:[-–|]*\s*(?:Season|S|Part|Book)\s*\d+|(?:\d+(?:st|nd|rd|th)\s*Season)).*"""), "").trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val rawTitle = document.selectFirst("h1.entry-title, .title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".thumb img, .poster img")?.attr("src"))
        val description = document.selectFirst(".entry-content p, .synopsis p, .desc")?.text()?.trim()
        val tags = document.select(".genx a, .genres a").map { it.text() }
        val yearText = document.selectFirst(".split span:contains(Released), .info-content span:contains(Year)")?.text()
        val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

        fun extractEpisodes(doc: org.jsoup.nodes.Document, defaultSeason: Int = 1): List<Episode> {
            val episodes = mutableListOf<Episode>()
            doc.select(".eplister ul li, #episode_list li, .listeps ul li, .bxcl ul li, .epcl li, .episodelist ul li").forEach { ep ->
                val linkElement = ep.selectFirst("a") ?: return@forEach
                val epHref = fixUrlNull(linkElement.attr("href")) ?: return@forEach
                
                val eplNum = ep.selectFirst(".epl-num, .epnum, .ts-chl-te")?.text()?.trim()
                val eplTitle = ep.selectFirst(".epl-title, .title")?.text()?.trim() ?: ""
                
                val rawName = eplNum ?: linkElement.ownText().trim().takeIf { it.isNotBlank() } ?: linkElement.text().trim()
                val fullTextToSearch = "$rawName $eplTitle"
                
                val seasonNum = extractSeasonNumber(fullTextToSearch) ?: defaultSeason

                var epNum = Regex("""(?i)(?:Episode|Ep)\s*(\d+)""").find(rawName)?.groupValues?.get(1)?.toIntOrNull()
                if (epNum == null) {
                    epNum = Regex("""\d+""").find(rawName)?.value?.toIntOrNull()
                }

                if (epNum != null && epNum in 1000..1999) {
                    epNum -= 1000 
                }

                var cleanName = if (rawName.contains(Regex("""(?i)(Episode|Ep)"""))) {
                    rawName
                } else {
                    "Episode ${epNum ?: rawName}"
                }
                
                if (eplTitle.isNotBlank() && !cleanName.contains(eplTitle)) {
                    cleanName = "$cleanName - $eplTitle"
                }

                episodes.add(
                    newEpisode(data = epHref) {
                        this.name = cleanName
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }
            return episodes
        }

        val allEpisodes = mutableListOf<Episode>()
        val baseTitle = getBaseTitle(rawTitle)
        val mainSeasonNum = extractSeasonNumber(rawTitle) ?: 1

        allEpisodes.addAll(extractEpisodes(document, mainSeasonNum))

        val seasonUrlsToFetch = mutableMapOf<String, Int>()

        document.select(".liteseasons ul li a, .season-list a, .seasons a, .series-sys a, select.season-select option, .half-nav a").forEach { element ->
            val sUrl = if (element.tagName() == "option") element.attr("value") else element.attr("href")
            if (sUrl.startsWith("http") && sUrl != url && !sUrl.contains("mirror") && !sUrl.contains("player")) {
                val sText = element.text()
                val sNum = extractSeasonNumber(sText) ?: extractSeasonNumber(sUrl.substringAfterLast("/")) ?: 1
                seasonUrlsToFetch[sUrl] = sNum
            }
        }

        try {
            val searchUrl = "$mainUrl/?s=${baseTitle.replace(" ", "+")}"
            // Small delay to prevent rate-limiting the search endpoint
            delay(300)
            val searchDoc = app.get(searchUrl, headers = defaultHeaders).document
            
            searchDoc.select("article.bs").forEach { result ->
                val resultTitle = result.selectFirst(".tt h2")?.text()?.trim() ?: return@forEach
                val resultUrl = fixUrlNull(result.selectFirst("a[itemprop=url]")?.attr("href")) ?: return@forEach
                
                if (resultUrl != url && !seasonUrlsToFetch.containsKey(resultUrl)) {
                    val resultBaseTitle = getBaseTitle(resultTitle)
                    
                    if (resultBaseTitle.equals(baseTitle, ignoreCase = true) || resultTitle.contains(baseTitle, ignoreCase = true)) {
                        val sNum = extractSeasonNumber(resultTitle) ?: extractSeasonNumber(resultUrl.substringAfterLast("/")) ?: 1
                        seasonUrlsToFetch[resultUrl] = sNum
                    }
                }
            }
        } catch (e: Exception) {}

        if (seasonUrlsToFetch.isNotEmpty()) {
            val otherSeasonEpisodes = coroutineScope {
                seasonUrlsToFetch.entries.mapIndexed { index, (seasonUrl, targetSeasonNum) ->
                    async {
                        try {
                            // Stagger the requests so we don't trigger DDoS protection
                            delay(index * 300L)
                            val seasonDoc = app.get(seasonUrl, headers = defaultHeaders).document
                            extractEpisodes(seasonDoc, targetSeasonNum)
                        } catch (e: Exception) {
                            emptyList<Episode>()
                        }
                    }
                }.awaitAll()
            }
            otherSeasonEpisodes.forEach { allEpisodes.addAll(it) }
        }

        val distinctEpisodes = allEpisodes.distinctBy { it.data }
        val isDescending = (distinctEpisodes.firstOrNull()?.episode ?: 0) > (distinctEpisodes.lastOrNull()?.episode ?: 0)
        val finalEpisodes = if (isDescending) distinctEpisodes.reversed() else distinctEpisodes

        val episodeMap = mutableMapOf(
            DubStatus.Subbed to finalEpisodes
        )

        return newAnimeLoadResponse(baseTitle, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.episodes = episodeMap
        }
    }

    // --- YOUR EXACT ORIGINAL EXTRACTOR CODE BELOW ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        var anyStreamFound = false

        suspend fun extractVideoLinks(html: String, refererUrl: String) {
            val urlsToProcess = mutableSetOf<String>()

            val dmRegex = Regex("""dailymotion\.com/(?:embed/video/|video/|[^"']+\?video=)([a-zA-Z0-9]+)""")
            dmRegex.findAll(html).forEach { match ->
                val token = match.groupValues[1]
                urlsToProcess.add("https://www.dailymotion.com/video/$token")
            }

            val doc = Jsoup.parse(html)
            doc.select("iframe, .player-embed script, #pembed script, .playcon script").forEach { element ->
                var rawSrc = element.attr("src")
                if (rawSrc.isBlank() || rawSrc == "about:blank" || rawSrc.contains("data:image")) {
                    rawSrc = element.attr("data-src").takeIf { it.isNotBlank() } 
                             ?: element.attr("data-lazy-src").takeIf { it.isNotBlank() } 
                             ?: ""
                }
                
                // 🔴 FIX: Restored fixUrlNull to catch relative/proxy iframe links (like Rumble)
                val cleanUrl = fixUrlNull(rawSrc)
                if (!cleanUrl.isNullOrBlank() && !cleanUrl.contains("about:blank")) {
                    urlsToProcess.add(cleanUrl)
                }
            }

            val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            if (playerJson != null) {
                var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                else if (encrypt == 2) {
                    rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                }
                
                // 🔴 FIX: Also fixUrlNull here just in case JSON returns relative paths
                val cleanJsonUrl = fixUrlNull(rawUrl)
                if (!cleanJsonUrl.isNullOrBlank()) urlsToProcess.add(cleanJsonUrl)
            }

            // 🔴 FIX: Removed the aggressive `.filter { it.startsWith("http") }` that was deleting valid links
            urlsToProcess.forEach { clean ->
                
                if (clean.contains("yurn.online", ignoreCase = true) || clean.contains("vidhide", ignoreCase = true)) {
                    val vidhideUrl = clean.replace(Regex("""(yurn\.online|vidhide[a-z0-9A-Z]*\.[a-z]+)"""), "vidhidepro.com")
                    if (loadExtractor(vidhideUrl, refererUrl, subtitleCallback, callback)) {
                        anyStreamFound = true
                    }
                    return@forEach
                }

                if ("dailymotion.com/video/" in clean) {
                    val token = clean.substringAfterLast("/")
                    var foundDm = false
                    try {
                        val apiResponse = app.get(
                            "https://www.dailymotion.com/player/metadata/video/$token",
                            headers = mapOf("Referer" to "https://www.dailymotion.com/")
                        ).text
                        
                        val m3u8Match = Regex(""""type"\s*:\s*"application\\?/x-mpegURL"[^}]+"url"\s*:\s*"([^"]+)"""")
                            .find(apiResponse)?.groupValues?.get(1)?.replace("\\/", "/")
                            ?: Regex("""(?:\"url\"|\"stream_url\")\s*:\s*\"([^\"]+\.m3u8[^\"]*)\"""")
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
                        if (loadExtractor(clean, refererUrl, subtitleCallback, callback)) {
                            anyStreamFound = true
                        }
                    }
                    return@forEach 
                }

                var okruUrl = clean
                if (okruUrl.contains("ok.ru", ignoreCase = true)) {
                    okruUrl = okruUrl.substringBefore("?")
                    okruUrl = okruUrl.replace("videoembed", "video") // This is your exact original line!
                    
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
                    return@forEach
                }

                if (loadExtractor(clean, refererUrl, subtitleCallback, callback)) {
                    anyStreamFound = true
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
