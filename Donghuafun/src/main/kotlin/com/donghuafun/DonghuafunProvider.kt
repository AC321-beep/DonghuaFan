package com.donghuafun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "Donghuafun (4K)"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val NETWORK_TIMEOUT_MS = 12_000L
        private const val EXTRACTOR_TIMEOUT_MS = 15_000L
        private const val MAX_CONCURRENT_SOURCES = 4

        private val RE_ID = Regex("""/id/(\d+)\.html""")
        private val RE_EP_NUM = Regex("""(\d+)""")

        private val RE_DM_IFRAME = Regex("""<iframe[^>]+src=['"]([^'"]*dailymotion[^'"]*)['"]""", RegexOption.IGNORE_CASE)
        private val RE_DM_TOKEN = Regex("""[?&]video=([^&"']+)""")
        private val RE_PLAYER_JSON = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)

        private val RE_URL = Regex(""""url"\s*:\s*"([^"]+)"""")
        private val RE_FROM = Regex(""""from"\s*:\s*"([^"]+)"""")
        private val RE_ENCRYPT = Regex(""""encrypt"\s*:\s*(\d+)""")
        private val RE_SUB_RAW = Regex(""""(?:subt|vtt|zimu|subtitle|sub)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val RE_SUB_CONFIG = Regex("""subtitle:\s*\{\s*url:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)

        private val RE_TRACK_SRC = Regex("""<track[^>]+src=['"]([^'"]+)['"][^>]*>""", RegexOption.IGNORE_CASE)
        private val RE_ATTR_LABEL = Regex("""label\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
        private val RE_ATTR_SRCLANG = Regex("""srclang\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
        private val RE_ATTR_LANG = Regex("""lang\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)

        private val RE_LANG_INDO = Regex("""\b(indonesia|indo|bahasa|id)\b""")
        private val RE_LANG_ENG = Regex("""\b(english|eng|rum)\b""")

        private val RE_RES_CLEAN = Regex("""\b\d{3,4}p\b""", RegexOption.IGNORE_CASE)
        private val RE_4K_CLEAN = Regex("""\b4k\b""", RegexOption.IGNORE_CASE)
        private val RE_SQUARE_CLEAN = Regex("""\[.*?]""")
        private val RE_ROUND_CLEAN = Regex("""\(.*?\)""")
    }

    private fun detailUrlToId(url: String): String =
        RE_ID.find(url)?.groupValues?.get(1) ?: ""

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Coming Soon",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isComingSoon = request.name == "Coming Soon"
        val isRecentlyUpdated = request.name == "Recently Updated"
        val isMovie = request.name == "Movies"

        val maxPagesToSearch = if (isComingSoon || isRecentlyUpdated || isMovie) 5 else 1
        val startPage = (page - 1) * maxPagesToSearch + 1
        val endPage = startPage + maxPagesToSearch - 1

        val items = mutableListOf<SearchResponse>()
        var hasNextPage = false

        coroutineScope {
            (startPage..endPage).map { p ->
                async {
                    val pageUrl = if (p == 1) request.data else request.data.replace(".html", "/page/$p.html")
                    val doc = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                        try { app.get(pageUrl).document } catch (e: Exception) { null }
                    }
                    if (doc != null) {
                        val elements = doc.select("a[href*='/vod/detail/id/']")
                        if (elements.isNotEmpty()) {
                            hasNextPage = true
                            parseShowCards(doc, isComingSoon, isRecentlyUpdated, isMovie)
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

                        val doc = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                            try { app.get(pageUrl).document } catch (e: Exception) { null }
                        } ?: break

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
        val doc = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { app.get(url).document }
            ?: throw ErrorLoadingException("Failed to load: $url")
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
            if (tabName.contains("vip", ignoreCase = true)) continue

            val container = listContainers[index]
            for (a in container.select("a[href*='/vod/play/id/$showId/']")) {
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNumber = RE_EP_NUM.find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val finalNumber = if (epNumber > 0) epNumber else episodeMap.size + 1
                val epData = "$tabName||$epUrl"

                if (!episodeMap.containsKey(finalNumber)) {
                    episodeMap[finalNumber] = newEpisode(epData) {
                        name = epName.ifEmpty { "Episode $finalNumber" }
                        episode = finalNumber
                    }
                } else {
                    val existingEp = episodeMap[finalNumber]!!
                    if (!existingEp.data.contains(epUrl)) existingEp.data += ",,$epData"
                }
            }
        }

        episodes.addAll(episodeMap.toSortedMap().values)
        if (episodes.isEmpty() && showId.isNotEmpty()) {
            for (n in 1..50) {
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

    private fun buildFinalName(link: ExtractorLink, tabName: String, fromName: String): String {
        var baseName = if (link.source == this.name) link.name else link.source

        baseName = baseName
            .replace("GeoDailymotion", "Dailymotion", ignoreCase = true)
            .replace("DonghuaFun Player", "DonghuaFun", ignoreCase = true)
            .replace(RE_RES_CLEAN, "")
            .replace(RE_4K_CLEAN, "")
            .replace(RE_SQUARE_CLEAN, "")
            .replace(RE_ROUND_CLEAN, "")
            .trim()

        if (baseName.isEmpty()) baseName = "Server"

        val concatNames = "$tabName $fromName ${link.name}".lowercase()
        val language = when {
            RE_LANG_INDO.containsMatchIn(concatNames) -> "Indo"
            RE_LANG_ENG.containsMatchIn(concatNames) -> "Eng"
            else -> ""
        }

        return buildString {
            append(baseName)
            if (language.isNotEmpty() && !baseName.contains(language, ignoreCase = true)) {
                append(" [$language]")
            }
        }.trim()
    }

  @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uniqueSources = data.split(",,").distinctBy { it.split("||").getOrNull(1) ?: it }
        val serverCounter = AtomicInteger(1)
        val linkFound = AtomicBoolean(false)
        val seenCombos = ConcurrentHashMap.newKeySet<String>()
        val callbackLock = Any()
        val subtitleLock = Any()
        val semaphore = Semaphore(MAX_CONCURRENT_SOURCES)

        coroutineScope {
            val scope = this // Capture the current coroutine scope

            fun emitSubtitle(sub: SubtitleFile) = synchronized(subtitleLock) { subtitleCallback.invoke(sub) }

            fun emit(link: ExtractorLink, tabName: String, fromName: String) {
                val finalName = buildFinalName(link, tabName, fromName)
                val uniqueKey = "$finalName-${link.quality}"

                // Deduplicate synchronously upfront
                if (!seenCombos.add(uniqueKey)) return
                
                linkFound.set(true)

                // Launch a quick coroutine to safely call the suspend function newExtractorLink
                scope.launch {
                    val renamedLink = newExtractorLink(
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

                    // Keep the lock strictly around the callback invocation
                    synchronized(callbackLock) {
                        callback.invoke(renamedLink)
                    }
                }
            }

            uniqueSources.map { source ->
                async {
                    semaphore.withPermit {
                        val parts = source.split("||")
                        val tabName = parts.getOrNull(0) ?: ""
                        val detailPageUrl = parts.getOrNull(1) ?: return@withPermit

                        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to detailPageUrl, "Origin" to mainUrl)
                        val response = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                            try { app.get(detailPageUrl, headers = headers) } catch (e: Exception) { null }
                        } ?: return@withPermit

                        val html = response.text

                        val dailymotionToken = RE_DM_IFRAME.findAll(html)
                            .mapNotNull { m -> RE_DM_TOKEN.find(m.groupValues[1])?.groupValues?.get(1) }
                            .firstOrNull()

                        val playerJson = RE_PLAYER_JSON.find(html)?.groupValues?.get(1) ?: return@withPermit

                        var rawUrl = RE_URL.find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                        val from = RE_FROM.find(playerJson)?.groupValues?.get(1) ?: ""
                        val encrypt = RE_ENCRYPT.find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                        if (encrypt == 1) {
                            rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                        } else if (encrypt == 2) {
                            rawUrl = URLDecoder.decode(String(Base64.decode(rawUrl, Base64.DEFAULT)), "UTF-8")
                        }

                        val subUrlRaw = RE_SUB_RAW.find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                        if (subUrlRaw.isNotEmpty()) {
                            var decodedSub = subUrlRaw
                            try {
                                if (encrypt == 1 && !decodedSub.startsWith("http")) {
                                    decodedSub = URLDecoder.decode(decodedSub, "UTF-8")
                                } else if (encrypt == 2 && !decodedSub.startsWith("http") && !decodedSub.startsWith("/")) {
                                    decodedSub = URLDecoder.decode(String(Base64.decode(decodedSub, Base64.DEFAULT)), "UTF-8")
                                }
                            } catch (e: Exception) {
                                decodedSub = subUrlRaw
                            }
                            if (decodedSub.isNotBlank()) emitSubtitle(SubtitleFile("English", fixUrl(decodedSub)))
                        }

                        RE_TRACK_SRC.findAll(html).forEach { track ->
                            val trackTag = track.value
                            val trackSrc = track.groupValues[1]
                            if (trackSrc.isNotBlank()) {
                                val label = RE_ATTR_LABEL.find(trackTag)?.groupValues?.get(1).orEmpty()
                                    .ifEmpty { RE_ATTR_SRCLANG.find(trackTag)?.groupValues?.get(1).orEmpty() }
                                    .ifEmpty { RE_ATTR_LANG.find(trackTag)?.groupValues?.get(1).orEmpty() }
                                    .ifEmpty { "English" }
                                emitSubtitle(SubtitleFile(label, fixUrl(trackSrc)))
                            }
                        }

                        val playerConfigSub = RE_SUB_CONFIG.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (!playerConfigSub.isNullOrBlank()) emitSubtitle(SubtitleFile("English", fixUrl(playerConfigSub)))

                        if (rawUrl.contains("url=")) rawUrl = URLDecoder.decode(rawUrl.substringAfter("url="), "UTF-8")
                        val isM3u8 = rawUrl.contains(".m3u8", ignoreCase = true)

                        if ((rawUrl.contains("ganjingworld.com", ignoreCase = true) || from.contains("ganjing", ignoreCase = true)) && !isM3u8) {
                            return@withPermit
                        }

                        val collectionCallback: (ExtractorLink) -> Unit = { link -> emit(link, tabName, from) }

                        when {
                            dailymotionToken != null -> {
                                withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                    loadExtractor("https://www.dailymotion.com/video/$dailymotionToken", mainUrl, subtitleCallback, collectionCallback)
                                }
                            }
                            from.equals("dailymotion", ignoreCase = true) -> {
                                val videoIdMatch = RE_DM_TOKEN.find(rawUrl)
                                val normalizedUrl = if (videoIdMatch != null) "https://www.dailymotion.com/video/${videoIdMatch.groupValues[1]}"
                                else if (rawUrl.startsWith("http")) rawUrl else "https://www.dailymotion.com/video/$rawUrl"

                                withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                    loadExtractor(normalizedUrl, mainUrl, subtitleCallback, collectionCallback)
                                }
                            }
                            rawUrl.contains("rumble.com", ignoreCase = true) || from.contains("rumble", ignoreCase = true) -> {
                                val finalRumbleUrl = if (rawUrl.startsWith("http")) rawUrl else "https://rumble.com/embed/$rawUrl"
                                withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                    Rumble().getUrl(finalRumbleUrl, detailPageUrl, subtitleCallback, collectionCallback)
                                }
                            }
                            isM3u8 -> {
                                val extractorUrl = if (rawUrl.startsWith("http")) "https://play.donghuafun.com/m3u8/?url=$rawUrl" else rawUrl
                                withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                    loadExtractor(extractorUrl, "https://donghuafun.com/", subtitleCallback, collectionCallback)
                                }
                            }
                            rawUrl.isNotEmpty() -> {
                                val loaded = withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                                    loadExtractor(rawUrl, detailPageUrl, subtitleCallback, collectionCallback)
                                }
                                if (loaded != true) {
                                    val hostName = from.ifEmpty { "Server ${serverCounter.getAndIncrement()}" }
                                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                    // Launching a coroutine here too since newExtractorLink is a suspend function
                                    scope.launch {
                                        val fallbackLink = newExtractorLink(name, hostName, rawUrl, ExtractorLinkType.VIDEO) {
                                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://donghuafun.com/", "Origin" to "https://donghuafun.com")
                                            this.referer = "https://donghuafun.com/"
                                            this.quality = Qualities.Unknown.value
                                        }
                                        emit(fallbackLink, tabName, from)
                                    }
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return linkFound.get()
    }

    private fun parseShowCards(doc: Document, isComingSoon: Boolean = false, isRecentlyUpdated: Boolean = false, isMovie: Boolean = false): List<SearchResponse> {
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
                val movieKeywords = listOf("movie", "film", "剧场版", "电影", "劇場版", "映画")
                val containsMovieKeyword = movieKeywords.any { keyword -> cardText.contains(keyword, ignoreCase = true) }

                when {
                    isComingSoon -> containsTrailerKeyword
                    isRecentlyUpdated -> !containsTrailerKeyword
                    isMovie -> containsMovieKeyword && !containsTrailerKeyword
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
