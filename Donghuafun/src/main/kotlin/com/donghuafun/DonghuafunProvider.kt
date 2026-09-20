package com.donghuafun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.Locale
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
        
        val maxPagesToSearch = if (isComingSoon || isRecentlyUpdated) 5 else 1 
        val startPage = (page - 1) * maxPagesToSearch + 1
        val endPage = startPage + maxPagesToSearch - 1
        
        val items = mutableListOf<SearchResponse>()
        var hasNextPage = false

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
        val categoriesToScan = listOf("time", "hits")

        val pageResults = coroutineScope {
            categoriesToScan.map { category ->
                async {
                    val categoryResults = mutableListOf<SearchResponse>()
                    for (page in 1..10) { 
                        val pageUrl = if (page == 1) {
                            "$mainUrl/index.php/vod/show/id/20/by/$category.html"
                        } else {
                            "$mainUrl/index.php/vod/show/id/20/by/$category/page/$page.html"
                        }
                        
                        val doc = try { app.get(pageUrl).document } catch (e: Exception) { null } ?: break
                        val parsedCards = parseShowCards(doc) 
                        if (parsedCards.isEmpty()) break
                        
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
        val tabs = doc.select(".anthology-tab a.swiper-slide, .anthology-tab a")
        val listContainers = doc.select(".anthology-list-box")
        
        val episodeMap = mutableMapOf<Int, Episode>() 

        for ((index, tab) in tabs.withIndex()) {
            if (index >= listContainers.size) continue
            
            val tabName = tab.text().trim()
            val container = listContainers[index]
            val episodeLinks = container.select("a[href*='/vod/play/id/$showId/']")

            for (a in episodeLinks) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNumber = parseEpisodeNumber(epName)
                val finalNumber = if (epNumber > 0) epNumber else episodeMap.size + 1
                
                val epData = "$tabName||$epUrl"

                if (!episodeMap.containsKey(finalNumber)) {
                    episodeMap[finalNumber] = newEpisode(epData) { 
                        name = epName.ifEmpty { "Episode $finalNumber" }
                        episode = finalNumber
                    }
                } else {
                    val existingEp = episodeMap[finalNumber]!!
                    if (!existingEp.data.contains(epUrl)) {
                        existingEp.data += ",,$epData" 
                    }
                }
            }
        }

        episodes.addAll(episodeMap.toSortedMap().values)

        if (episodes.isEmpty() && showId.isNotEmpty()) {
            for (n in 1..300) {
                val epUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html"
                episodes.add(newEpisode("Backup||$epUrl") { name = "EP$n" })
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

    // --- DYNAMIC DETECTION HELPERS ---

    private fun detectLang(vararg sources: String?): String? {
        val text = sources.filterNotNull().joinToString(" ").lowercase()
        return when {
            Regex("""\b(indonesia|indo|bahasa|id)\b""").containsMatchIn(text) -> "Indo"
            Regex("""\b(english|eng)\b""").containsMatchIn(text) -> "Eng"
            Regex("""\b(chinese|mandarin|raw|zh|cn)\b""").containsMatchIn(text) -> "RAW"
            Regex("""\b(hindi|hin)\b""").containsMatchIn(text) -> "Hindi"
            Regex("""\b(spanish|esp)\b""").containsMatchIn(text) -> "Esp"
            Regex("""\b(japanese|jap|jp)\b""").containsMatchIn(text) -> "Jap"
            else -> null
        }
    }

    private fun detectResolution(linkName: String, tabName: String): String? {
        val exactMatch = Regex("""\b(\d{3,4}p)\b""", RegexOption.IGNORE_CASE).find(linkName)?.groupValues?.get(1)
        if (exactMatch != null) return exactMatch.lowercase()

        val combined = "$linkName $tabName".lowercase()
        return when {
            combined.contains("4k") || combined.contains("2160") -> "4K"
            combined.contains("1440") || combined.contains("2k") -> "1440p"
            combined.contains("1080") -> "1080p"
            combined.contains("720") -> "720p"
            combined.contains("480") -> "480p"
            combined.contains("360") -> "360p"
            else -> null
        }
    }

    private fun cleanSourceName(rawName: String, from: String): String {
        var name = rawName
            .replace("GeoDailymotion", "Dailymotion", ignoreCase = true)
            .replace("DonghuaFun Player", "DonghuaFun", ignoreCase = true)
            .replace(Regex("""\b\d{3,4}p\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[.*?\]"""), "")
            .trim()

        if (name.isEmpty() || name.equals("Server", ignoreCase = true) || name.startsWith("Server", ignoreCase = true)) {
            val fromClean = from.lowercase()
            name = when {
                fromClean.contains("dailymotion") -> "Dailymotion"
                fromClean.contains("rumble") -> "Rumble"
                fromClean.contains("ganjing") -> "GanjingWorld"
                from.isNotBlank() -> from.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                else -> "Server"
            }
        }
        return name
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linkFound = false
        val sources = data.split(",,") 

        for (source in sources) {
            val parts = source.split("||")
            val tabName = parts.getOrNull(0) ?: ""
            val detailPageUrl = parts.getOrNull(1) ?: continue

            val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to detailPageUrl, "Origin" to mainUrl)
            val response = try { app.get(detailPageUrl, headers = headers) } catch (e: Exception) { null }
            val html = response?.text ?: continue
            val doc = response.document

            var dailymotionToken: String? = null
            doc.select("iframe[src*='dailymotion']")?.forEach { iframe ->
                val src = iframe.attr("src")
                val match = Regex("""[?&]video=([^&]+)""").find(src)
                if (match != null) {
                    dailymotionToken = match.groupValues[1]
                    return@forEach
                }
            }

            val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: continue

            var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
            val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
            else if (encrypt == 2) {
                rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
            }

            val subUrlRaw = Regex(""""(?:subt|vtt|zimu|subtitle|sub)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""

            if (subUrlRaw.isNotEmpty()) {
                var decodedSub = subUrlRaw
                try {
                    if (encrypt == 1 && !decodedSub.startsWith("http")) {
                        decodedSub = URLDecoder.decode(decodedSub, "UTF-8")
                    } else if (encrypt == 2 && !decodedSub.startsWith("http") && !decodedSub.startsWith("/")) {
                        decodedSub = String(Base64.decode(decodedSub, Base64.DEFAULT))
                        decodedSub = URLDecoder.decode(decodedSub, "UTF-8")
                    }
                } catch (e: Exception) {
                    decodedSub = subUrlRaw
                }
                if (decodedSub.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile("English", fixUrl(decodedSub)))
                }
            }

            doc.select("track").forEach { track ->
                val trackSrc = track.attr("src")
                if (trackSrc.isNotBlank()) {
                    val label = track.attr("label").ifEmpty { track.attr("srclang") }.ifEmpty { track.attr("lang") }.ifEmpty { "English" }
                    subtitleCallback.invoke(SubtitleFile(label, fixUrl(trackSrc)))
                }
            }

            val playerConfigSub = Regex("""subtitle:\s*\{\s*url:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (!playerConfigSub.isNullOrBlank()) {
                subtitleCallback.invoke(SubtitleFile("English", fixUrl(playerConfigSub)))
            }

            if (rawUrl.contains("url=")) {
                rawUrl = rawUrl.substringAfter("url=")
                rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
            }

            // A temporary list to collect links generated by the extractors safely
            val extractedLinks = mutableListOf<ExtractorLink>()
            val collectionCallback: (ExtractorLink) -> Unit = { link ->
                extractedLinks.add(link)
            }

            val isM3u8 = rawUrl.contains(".m3u8", ignoreCase = true)

            // Trigger the extractors (these are suspend functions)
            if (dailymotionToken != null) {
                val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$dailymotionToken"
                loadExtractor(embedUrl, detailPageUrl, subtitleCallback, collectionCallback)
            } 
            else if (from.equals("dailymotion", ignoreCase = true)) {
                val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$rawUrl"
                loadExtractor(embedUrl, detailPageUrl, subtitleCallback, collectionCallback)
            } 
            else if (rawUrl.contains("rumble.com", ignoreCase = true) || from.contains("rumble", ignoreCase = true)) {
                val finalRumbleUrl = if (rawUrl.startsWith("http")) rawUrl else "https://rumble.com/embed/$rawUrl"
                Rumble().getUrl(finalRumbleUrl, detailPageUrl, subtitleCallback, collectionCallback)
            }
            else if (isM3u8) {
                val extractorUrl = if (rawUrl.startsWith("http")) {
                    "https://play.donghuafun.com/m3u8/?url=$rawUrl"
                } else rawUrl
                loadExtractor(extractorUrl, "https://donghuafun.com/", subtitleCallback, collectionCallback)
            }
            else if (rawUrl.contains("ganjingworld.com", ignoreCase = true) || from.contains("ganjing", ignoreCase = true)) {
                val finalGanjingUrl = if (rawUrl.startsWith("http")) rawUrl else "https://www.ganjingworld.com/embed/$rawUrl"
                GanjingWorld().getUrl(finalGanjingUrl, detailPageUrl, subtitleCallback, collectionCallback)
            }
            else if (rawUrl.isNotEmpty()) {
                if (!loadExtractor(rawUrl, detailPageUrl, subtitleCallback, collectionCallback)) {
                    
                    // Format unresolved fallback links directly within the suspend context
                    val hostName = from.ifEmpty { "Server 1" }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    val resolution = detectResolution(hostName, tabName) ?: ""
                    val language = detectLang(tabName, hostName, "") ?: ""
                    
                    val resTag = if (resolution.isNotEmpty() && !hostName.contains(resolution, ignoreCase = true)) "[$resolution]" else ""
                    val langTag = if (language.isNotEmpty() && !hostName.contains(language, ignoreCase = true)) "[$language]" else ""
                    
                    val appendedTags = "$resTag $langTag".trim()
                    val finalFallbackName = "$hostName $appendedTags".trim()

                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            finalFallbackName,
                            rawUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://donghuafun.com/",
                                "Origin" to "https://donghuafun.com"
                            )
                            this.referer = "https://donghuafun.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linkFound = true
                }
            }

            // Safely process and rebuild collected links inside the suspend function scope
            if (extractedLinks.isNotEmpty()) {
                linkFound = true
                for (link in extractedLinks) {
                    val sourceName = cleanSourceName(link.name, from)
                    val resolution = detectResolution(link.name, tabName) ?: ""
                    val language = detectLang(tabName, from, link.name) ?: ""

                    val finalName = buildString {
                        append(sourceName)
                        if (resolution.isNotEmpty()) append(" [$resolution]")
                        if (language.isNotEmpty()) append(" [$language]")
                    }.trim()

                    callback.invoke(
                        newExtractorLink(
                            link.source,
                            finalName,
                            link.url,
                            link.type ?: ExtractorLinkType.VIDEO
                        ) {
                            this.referer = link.referer
                            this.quality = link.quality
                            if (link.headers != null) this.headers = link.headers!!
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
        }
        return linkFound
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
                    isComingSoon -> containsTrailerKeyword
                    isRecentlyUpdated -> !containsTrailerKeyword
                    else -> true
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
