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
        val titleElement = this.selectFirst(".tt h2, .tt")
        val title = titleElement?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a[itemprop=url], a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.ts-post-image, img")?.attr("src"))
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
        val description = document.selectFirst(".desc, .entry-content p, .synopsis p")?.text()?.trim()
        val tags = document.select(".genxed a, .genx a, .genres a").map { it.text() }
        
        val yearText = document.selectFirst("span.year, span.split:contains(Released), .info-content span:contains(Year)")?.text()
        val year = Regex("""\d{4}""").find(yearText ?: "")?.value?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        document.select(".episodelist ul li, .eplister ul li, #episode_list li, .bxcl ul li").forEach { ep ->
            val linkElement = ep.selectFirst("a") ?: return@forEach
            val epHref = fixUrlNull(linkElement.attr("href")) ?: return@forEach
            
            val playInfoH3 = ep.selectFirst(".playinfo h3")?.text()?.trim()
            val playInfoSpan = ep.selectFirst(".playinfo span")?.text()?.trim()
            
            val eplTitle = ep.selectFirst(".epl-title, .title")?.text()?.trim() ?: ""
            val eplNum = ep.selectFirst(".epl-num, .epnum, .ts-chl-te")?.text()?.trim() ?: ""
            
            val rawName = eplNum.takeIf { it.isNotBlank() } ?: linkElement.ownText().trim().makeSafe()
            val fullTextToSearch = playInfoH3 ?: "$rawName $eplTitle $playInfoSpan"
            
            // 1️⃣ Dynamic Season Extraction
            val seasonNum = Regex("""(?:Season|S)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(fullTextToSearch)?.groupValues?.get(1)?.toIntOrNull()

            var epNum: Int? = null

            // 2️⃣ Dynamic Episode Extraction
            val explicitEp = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(fullTextToSearch)
            if (explicitEp != null) {
                epNum = explicitEp.groupValues[1].toIntOrNull()
            }
            if (epNum == null && eplNum.isNotBlank()) {
                epNum = Regex("""^(\d+)""").find(eplNum)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (epNum == null) {
                epNum = Regex("""[\[\(](\d+)[\]\)]""").find(fullTextToSearch)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (epNum == null) {
                val allNumbers = Regex("""\d+""").findAll(fullTextToSearch).map { it.value.toInt() }.toList()
                epNum = allNumbers.firstOrNull { it != seasonNum }
            }

            // 3️⃣ Dynamic Typo Correction
            if (epNum != null && epNum > 100) {
                val epStr = epNum.toString()
                val assumedSeason = seasonNum ?: 1 
                val seasonStr = assumedSeason.toString()
                
                if (epStr.startsWith(seasonStr) && epStr.length > seasonStr.length) {
                    val potentialFix = epStr.removePrefix(seasonStr).toIntOrNull()
                    if (potentialFix != null && potentialFix > 0) {
                        epNum = potentialFix
                    }
                } else if (epNum > 1000) {
                    epNum = epNum % 1000
                }
            }

            // 4️⃣ Trim Name for Cloudstream UI to prevent duplicates
            val rawEpName = playInfoH3 ?: (if (eplTitle.isNotBlank()) eplTitle else fullTextToSearch)
            
            var cleanName = rawEpName.replace(title, "", ignoreCase = true)
                .replace(Regex("""(?i)Season\s*\d+"""), "")
                .replace(Regex("""(?i)S\d+"""), "")
                .replace(Regex("""(?i)Episode\s*\d+"""), "")
                .replace(Regex("""(?i)Ep\s*\d+"""), "")
                .replace(Regex("""(?i)Eps\s*\d+"""), "")
                .replace(Regex("""(?i)English\s+Sub(?:title)?s?"""), "")
                .replace(Regex("""(?i)Sub(?:title)?s?"""), "")
                .replace(Regex("""\[4K\]|\(4K\)|4K"""), "") 
                .trim(' ', '-', ':', '|', '_', '.', '/', '\\')

            // Remove any leading standalone episode numbers to avoid "Episode 72. 72"
            if (epNum != null) {
                var previousName = ""
                while (cleanName != previousName) {
                    previousName = cleanName
                    cleanName = cleanName.replace(Regex("""^0*$epNum\b\s*[\-\.]*\s*"""), "")
                }
            }
            cleanName = cleanName.trim(' ', '-', ':', '|', '_', '.', '/', '\\')

            val finalName = cleanName.takeIf { it.isNotBlank() }

            episodes.add(newEpisode(epHref) {
                this.name = finalName
                this.episode = epNum
                this.season = seasonNum
            })
        }

        val isDescending = (episodes.firstOrNull()?.episode ?: 0) > (episodes.lastOrNull()?.episode ?: 0)
        val finalEpisodes = if (isDescending) episodes.reversed() else episodes

        val episodeMap = mutableMapOf(
            DubStatus.Subbed to finalEpisodes
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

        suspend fun extractVideoLinks(html: String, refererUrl: String) {
            val urlsToProcess = mutableSetOf<String>()

            val dmRegex = Regex("""dailymotion\.com/(?:embed/video/|video/|[^"']+\?video=)([a-zA-Z0-9]+)""")
            dmRegex.findAll(html).forEach { match ->
                val token = match.groupValues[1]
                urlsToProcess.add("https://www.dailymotion.com/video/$token")
            }

            val doc = Jsoup.parse(html)
            doc.select("iframe, .player-embed iframe, #pembed iframe, .video-content iframe, .playcon iframe").forEach { element ->
                var rawSrc = element.attr("src")
                if (rawSrc.isBlank() || rawSrc == "about:blank" || rawSrc.contains("data:image")) {
                    rawSrc = element.attr("data-src").takeIf { it.isNotBlank() } 
                             ?: element.attr("data-lazy-src").takeIf { it.isNotBlank() } 
                             ?: ""
                }
                
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
                
                val cleanJsonUrl = fixUrlNull(rawUrl)
                if (!cleanJsonUrl.isNullOrBlank()) urlsToProcess.add(cleanJsonUrl)
            }

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
                    okruUrl = okruUrl.replace("videoembed", "video")
                    
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
            val urlRaw = option.attr("value")
            if (urlRaw.isBlank()) null else fixUrlNull(urlRaw)
        }.filter { it != data }.distinct()

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

    private fun String.makeSafe(): String {
        return if (this.isBlank()) "0" else this
    }
}
