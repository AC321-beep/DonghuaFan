package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "Donghuafun (4K)"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "Donghuafun"
        // Desktop User-Agent to bypass mobile scraper blocks
        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Coming Soon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isComingSoon = request.name == "Coming Soon"
        val isRecentlyUpdated = request.name == "Recently Updated"
        
        // Define max pages to fetch based on category
        val maxPagesToSearch = if (isComingSoon || isRecentlyUpdated) 5 else 1 
        
        // Calculate the exact chunk of backend pages to fetch to prevent duplicates on scrolling
        val startPage = (page - 1) * maxPagesToSearch + 1
        val endPage = startPage + maxPagesToSearch - 1
        
        val items = mutableListOf<SearchResponse>()
        var hasNextPage = false

        // Fetch pages concurrently instead of sequentially for a massive speed boost
        coroutineScope {
            (startPage..endPage).map { p ->
                async {
                    val pageUrl = if (p == 1) request.data else request.data.replace(".html", "/page/$p.html")
                    val doc = try { app.get(pageUrl).document } catch (e: Exception) { null }
                    
                    if (doc != null) {
                        val elements = doc.select("a[href*='/vod/detail/id/']")
                        if (elements.isNotEmpty()) {
                            hasNextPage = true
                            parseShowCards(doc, isComingSoon, isRecentlyUpdated)
                        } else emptyList()
                    } else emptyList()
                }
            }.awaitAll().forEach { items.addAll(it) }
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Since the site lacks proper search, we must scrape directories locally.
        // We now scan BOTH 'time' (newest) and 'hits' (popular) to ensure older shows aren't missed.
        val categoriesToScan = listOf("time", "hits")

        val pageResults = coroutineScope {
            categoriesToScan.map { category ->
                async {
                    val categoryResults = mutableListOf<SearchResponse>()
                    for (page in 1..10) { // Scan 10 pages deep per category
                        val pageUrl = if (page == 1) {
                            "$mainUrl/index.php/vod/show/id/20/by/$category.html"
                        } else {
                            "$mainUrl/index.php/vod/show/id/20/by/$category/page/$page.html"
                        }
                        
                        val doc = try { app.get(pageUrl).document } catch (e: Exception) { null } ?: break
                        val parsedCards = parseShowCards(doc) // Defaults to false for both flags
                        if (parsedCards.isEmpty()) break
                        
                        // Filter locally based on the search query
                        categoryResults.addAll(parsedCards.filter { it.name.contains(query, ignoreCase = true) })
                        
                        val hasNext = doc.select("a.page-next:not(.disabled), a:contains(Next), a:contains(下一页)").isNotEmpty()
                        if (!hasNext) break
                    }
                    categoryResults
                }
            }.awaitAll()
        }
        
        results.addAll(pageResults.flatten())
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showId = detailUrlToId(url)

        val title = doc.selectFirst("h1, .video-title, .detail-title")?.text()?.trim() ?: doc.title().substringBefore(" Donghua").trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content") ?: doc.selectFirst(".detail-pic img, .video-cover img, .card-top img")?.attr("data-src") ?: doc.selectFirst("img.lazy")?.attr("data-src")
        val description = doc.selectFirst(".video-desc, .detail-desc, .card-text")?.text()?.trim() ?: doc.selectFirst("meta[name='description']")?.attr("content")
        val tags = doc.select("a[href*='/class/']").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val tabs = doc.select(".anthology-tab a.vod-playerUrl")
        val fourKTabIndex = tabs.indexOfFirst { it.text().contains("4K", ignoreCase = true) }
        val targetIndex = if (fourKTabIndex != -1) fourKTabIndex else 0

        val listContainers = doc.select(".anthology-list-box")
        if (targetIndex < listContainers.size) {
            val container = listContainers[targetIndex]
            val episodeLinks = container.select("a[href*='/vod/play/id/$showId/']")
            val episodeMap = mutableMapOf<Int, Episode>()

            for (a in episodeLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNumber = parseEpisodeNumber(epName)
                val finalNumber = if (epNumber > 0) epNumber else episodeMap.size + 1
                
                // Broadened the selector just in case it's named slightly differently 
                val dateText = a.selectFirst(".epl-date, .date, .time")?.text()?.trim() 
                
                if (!episodeMap.containsKey(finalNumber)) {
                    episodeMap[finalNumber] = newEpisode(epUrl) { 
                        this.name = epName.ifEmpty { "Episode $finalNumber" }
                        this.episode = finalNumber
                        
                        if (!dateText.isNullOrBlank()) { 
                            // 1. Try to properly parse the Date format AnimeKhor uses (Month dd, yyyy)
                            this.addDate(dateText, format = "MMMM d, yyyy")
                            
                            // 2. Failsafe: Guarantee it appears on your screen by setting it as the description
                            this.description = dateText
                        }
                    }
                }
            }
            episodes.addAll(episodeMap.toSortedMap().values)
        }

        if (episodes.isEmpty() && showId.isNotEmpty()) {
            for (n in 1..300) {
                val epUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html"
                episodes.add(newEpisode(epUrl) { name = "EP$n" })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster?.let { fixUrl(it) }
            plot = description
            tags?.let { this.tags = it }
            year?.let { this.year = it }
            addEpisodes(DubStatus.None, episodes)
        }
    }

    private fun parseEpisodeNumber(name: String): Int {
        return Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailPageUrl = data
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to detailPageUrl, "Origin" to mainUrl)
        
        // Single network request implementation to speed up player loading
        val response = try { app.get(detailPageUrl, headers = headers) } catch (e: Exception) { null }
        val html = response?.text ?: ""
        val doc = response?.document

        // --- Dailymotion Logic Intact ---
        var dailymotionToken: String? = null
        doc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
            val src = iframe.attr("src")
            val match = Regex("""[?&]video=([^&]+)""").find(src)
            if (match != null) {
                dailymotionToken = match.groupValues[1]
                return@forEach
            }
        }
        if (dailymotionToken != null) {
            val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$dailymotionToken"
            if (loadExtractor(embedUrl, detailPageUrl, subtitleCallback, callback)) return true
        }

        // --- Main Player Logic ---
        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return false

        var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
        val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
        else if (encrypt == 2) {
            rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
            rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
        }

        // ==========================================
        // --- IMPROVED SUBTITLE EXTRACTION START ---
        // ==========================================

        // 1. Extract from MacCMS player_aaaa JSON keys
        val subUrlRaw = Regex(""""(?:subt|vtt|zimu|subtitle|sub)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""

        if (subUrlRaw.isNotEmpty()) {
            var decodedSub = subUrlRaw
            try {
                // Decode according to MacCMS encryption, unless it's already a plain HTTP/relative URL
                if (encrypt == 1 && !decodedSub.startsWith("http")) {
                    decodedSub = URLDecoder.decode(decodedSub, "UTF-8")
                } else if (encrypt == 2 && !decodedSub.startsWith("http") && !decodedSub.startsWith("/")) {
                    decodedSub = String(Base64.decode(decodedSub, Base64.DEFAULT))
                    decodedSub = URLDecoder.decode(decodedSub, "UTF-8")
                }
            } catch (e: Exception) {
                decodedSub = subUrlRaw // Fallback to raw string if decoding fails
            }
            if (decodedSub.isNotBlank()) {
                subtitleCallback.invoke(SubtitleFile("English", fixUrl(decodedSub)))
            }
        }

        // 2. Extract from standard HTML <track> elements
        doc?.select("track")?.forEach { track ->
            val trackSrc = track.attr("src")
            if (trackSrc.isNotBlank()) {
                val label = track.attr("label").ifEmpty { track.attr("srclang") }.ifEmpty { track.attr("lang") }.ifEmpty { "English" }
                subtitleCallback.invoke(SubtitleFile(label, fixUrl(trackSrc)))
            }
        }

        // 3. Fallback: Extract from generic player configurations (e.g., DPlayer, ArtPlayer)
        val playerConfigSub = Regex("""subtitle:\s*\{\s*url:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!playerConfigSub.isNullOrBlank()) {
            subtitleCallback.invoke(SubtitleFile("English", fixUrl(playerConfigSub)))
        }

        // ========================================
        // --- IMPROVED SUBTITLE EXTRACTION END ---
        // ========================================

        if (from.equals("dailymotion", ignoreCase = true)) {
            val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$rawUrl"
            if (loadExtractor(embedUrl, detailPageUrl, subtitleCallback, callback)) return true
        } 
        else if (rawUrl.isNotEmpty()) {
            if (rawUrl.contains("url=")) {
                rawUrl = rawUrl.substringAfter("url=")
                rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
            }

            val isM3u8 = rawUrl.contains(".m3u8", ignoreCase = true)
            val streamHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://donghuafun.com/",
                "Origin" to "https://donghuafun.com"
            )

            if (isM3u8) {
                // Route to our newly created DonghuaFunExtractor by prepending the domain
                val extractorUrl = if (rawUrl.startsWith("http")) {
                    "https://play.donghuafun.com/m3u8/?url=$rawUrl"
                } else rawUrl

                if (loadExtractor(extractorUrl, "https://donghuafun.com/", subtitleCallback, callback)) {
                    return true
                }
            } else {
                // Strict positional mapping fallback
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        from.ifEmpty { "Server 1" },
                        rawUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.headers = streamHeaders
                        this.referer = "https://donghuafun.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }
        return false
    }

    private fun parseShowCards(doc: Document, isComingSoon: Boolean = false, isRecentlyUpdated: Boolean = false): List<SearchResponse> {
        return doc.select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .filter { a -> 
                val parent1 = a.parent()
                val parent2 = a.parent()?.parent()
                val parent3 = a.parent()?.parent()?.parent()

                val container = when {
                    parent3 != null && parent3.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent3
                    parent2 != null && parent2.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent2
                    parent1 != null && parent1.select("a[href*='/vod/detail/id/']").distinctBy { it.attr("href") }.size == 1 -> parent1
                    else -> a
                }
                
                val cardText = container.text()
                val keywords = listOf("trailer", "coming soon", "not yet aired", "upcoming", "releasing soon", "0 episode")
                val containsTrailerKeyword = keywords.any { keyword -> cardText.contains(keyword, ignoreCase = true) }

                when {
                    isComingSoon -> containsTrailerKeyword       // ONLY show items with trailer keywords
                    isRecentlyUpdated -> !containsTrailerKeyword // FILTER OUT items with trailer keywords
                    else -> true                                 // For Most Popular/Search: Keep everything
                }
            }
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val title = a.attr("title").ifEmpty { a.selectFirst("img")?.attr("alt") ?: a.text() }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val poster = a.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }
}
