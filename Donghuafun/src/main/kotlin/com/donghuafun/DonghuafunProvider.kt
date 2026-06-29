package com.luciferdonghua

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var anyStreamFound = false

        // Core processing function applied to both Main Page and Mirror sub-pages
        suspend fun processDocument(doc: org.jsoup.nodes.Document, refererUrl: String) {
            
            // =========================================================
            // 1. EXACT Dailymotion Logic Intact (From Donghuafun code)
            // =========================================================
            var dailymotionToken: String? = null
            doc.select("iframe[src*='dailymotion']").forEach { iframe ->
                val src = iframe.attr("src")
                val match = Regex("""[?&]video=([^&]+)""").find(src)
                if (match != null) {
                    dailymotionToken = match.groupValues[1]
                    return@forEach
                }
            }
            if (dailymotionToken != null) {
                val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$dailymotionToken"
                if (loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)) {
                    anyStreamFound = true
                }
            }

            // =========================================================
            // 2. Main Player Logic for other Servers (Rumble, Vidhide)
            // =========================================================
            doc.select(".player-embed iframe, #pembed iframe, .playcon iframe, iframe").forEach { iframe ->
                var src = iframe.attr("src")
                if (src.startsWith("//")) src = "https:$src"
                var cleanUrl = fixUrlNull(src) ?: return@forEach
                
                // Skip if it's blank or if we already caught it in the Dailymotion block above
                if (cleanUrl.isNotBlank() && !cleanUrl.contains("about:blank") && !cleanUrl.contains("dailymotion", ignoreCase = true)) {
                    
                    // Vidhide Domain Normalization (So it matches your Extractors.kt)
                    if (cleanUrl.contains("vidhide", ignoreCase = true)) {
                        cleanUrl = cleanUrl.replace(Regex("""vidhide\w*\.[a-z]+"""), "vidhide.com")
                    }

                    // StreamPlay Domain Normalization
                    if (cleanUrl.contains("streamplay", ignoreCase = true)) {
                        cleanUrl = cleanUrl.replace(Regex("""streamplay\.[a-z\.]+"""), "play.streamplay.co.in")
                    }

                    if (loadExtractor(cleanUrl, refererUrl, subtitleCallback, callback)) {
                        anyStreamFound = true
                    }
                }
            }
        }

        // --- Execute Flow ---
        
        // 1. Process the primary episode page
        val baseDocument = try { app.get(data, headers = defaultHeaders).document } catch(e: Exception) { return false }
        processDocument(baseDocument, data)

        // 2. Find and visit all mirror pages in the dropdown
        val mirrorUrls = baseDocument.select("select.mirror option").mapNotNull { option ->
            val url = option.attr("value")
            if (url.startsWith("http") && url != data) url else null
        }.distinct()

        mirrorUrls.forEach { mirrorUrl ->
            try {
                val response = app.get(mirrorUrl, headers = defaultHeaders)
                
                // If it performed an external redirect (Direct link instead of an iframe page)
                if (response.url != mirrorUrl && !response.url.contains("luciferdonghua.in")) {
                    var cleanDest = response.url
                    
                    if (cleanDest.contains("vidhide", ignoreCase = true)) {
                        cleanDest = cleanDest.replace(Regex("""vidhide\w*\.[a-z]+"""), "vidhide.com")
                    }
                    if (loadExtractor(cleanDest, data, subtitleCallback, callback)) {
                        anyStreamFound = true
                    }
                } else {
                    // Otherwise, process the standard mirror iframe page
                    processDocument(response.document, mirrorUrl)
                }
            } catch (e: Exception) {
                // Safely skip dead/timing-out mirror servers
            }
        }

        return anyStreamFound
    }
}
