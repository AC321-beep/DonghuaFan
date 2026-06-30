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
    override var lang = "zh" // 🔴 CHANGED TO "zh"
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
            
            // EPISODE FIX
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        var anyStreamFound = false

        suspend fun extractIframes(doc: org.jsoup.nodes.Document, refererUrl: String) {
            doc.select(".player-embed iframe, #pembed iframe, .playcon iframe, iframe").forEach { iframe ->
                
                // 🔴 FIX 1: OK.RU FIX
                // Prioritize 'src' to avoid capturing invisible data-src placeholder images. 
                // Only falls back to data-src if the primary src is empty or blank.
                var rawSrc = iframe.attr("src")
                if (rawSrc.isBlank() || rawSrc.contains("about:blank")) {
                    rawSrc = iframe.attr("data-src")
                }
                if (rawSrc.isBlank() || rawSrc.contains("about:blank")) {
                    rawSrc = iframe.attr("data-lazy-src")
                }
                
                if (rawSrc.startsWith("//")) rawSrc = "https:$rawSrc"
                val clean = fixUrlNull(rawSrc) ?: return@forEach
                
                if (clean.isNotBlank() && !clean.contains("about:blank")) {
                    
                    // --- DAILYMOTION LOGIC ---
                    if ("dailymotion" in clean) {
                        var token = Regex("""[?&]video=([^&]+)""").find(clean)?.groupValues?.get(1)
                        if (token == null) token = extractDailymotionToken(refererUrl)
                        
                        if (token != null) {
                            // 🔴 FIX 2: DAILYMOTION DOUBLE-URL FIX
                            // Checks if the extracted token is already a full URL before appending
                            val embedUrl = if (token.startsWith("http")) token else "https://www.dailymotion.com/video/$token"
                            if (loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)) {
                                anyStreamFound = true
                            }
                            return@forEach 
                        }
                    }

                    // --- NATIVE EXTRACTORS (OK.ru, VidHide, etc.) ---
                    if (loadExtractor(clean, refererUrl, subtitleCallback, callback)) {
                        anyStreamFound = true
                    } else {
                        // --- FALLBACK SCRAPER ---
                        try {
                            val iframeHtml = app.get(clean, headers = mapOf("Referer" to refererUrl)).text
                            val rawStreamRegex = Regex("""["'](https?[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                            rawStreamRegex.findAll(iframeHtml).forEach { match ->
                                val cleanStreamUrl = match.groupValues[1].replace("\\/", "/")
                                callback(
                                    newExtractorLink(name, "Fallback Server", cleanStreamUrl, INFER_TYPE) {
                                        this.referer = clean
                                    }
                                )
                                anyStreamFound = true
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }

        // 1️⃣ Fetch the primary episode page
        val baseDocument = try { app.get(data, headers = defaultHeaders).document } catch(e: Exception) { return@coroutineScope false }
        extractIframes(baseDocument, data)

        // 2️⃣ Collect all Mirror URLs from the dropdown
        val mirrorUrls = baseDocument.select("select.mirror option").mapNotNull { option ->
            val url = option.attr("value")
            if (url.startsWith("http") && url != data) url else null
        }.distinct()

        // 3️⃣ Visit each mirror URL in the background to prevent timeouts
        mirrorUrls.map { mirrorUrl ->
            async {
                try {
                    val resp = app.get(mirrorUrl, headers = defaultHeaders)
                    val destUrl = resp.url
                    
                    if (destUrl != mirrorUrl && !destUrl.contains("luciferdonghua.in")) {
                        if (loadExtractor(destUrl, data, subtitleCallback, callback)) {
                            anyStreamFound = true
                        }
                    } else {
                        extractIframes(resp.document, mirrorUrl)
                    }
                } catch (e: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope anyStreamFound
    }
}
