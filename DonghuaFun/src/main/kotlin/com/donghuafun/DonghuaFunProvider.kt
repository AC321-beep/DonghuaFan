package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun (4K)"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
        private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data
                     else request.data.replace(".html", "/page/$page.html")
        val doc = app.get(pageUrl).document
        return newHomePageResponse(request.name, parseShowCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return parseShowCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showId = detailUrlToId(url)

        val title = doc.selectFirst("h1, .video-title, .detail-title")?.text()?.trim()
            ?: doc.title().substringBefore(" Donghua").trim()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".detail-pic img, .video-cover img, .card-top img")?.attr("data-src")
            ?: doc.selectFirst("img.lazy")?.attr("data-src")

        val description = doc.selectFirst(".video-desc, .detail-desc, .card-text")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

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
                if (epNumber > 0 && !episodeMap.containsKey(epNumber)) {
                    episodeMap[epNumber] = newEpisode(epUrl) { name = "EP$epNumber" }
                }
            }

            episodes.addAll(episodeMap.toSortedMap().values)
            Log.d(TAG, "Found ${episodes.size} episodes from 4K tab")
        }

        if (episodes.isEmpty() && showId.isNotEmpty()) {
            Log.d(TAG, "No episodes found from tabs, generating numeric range 1..300")
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
        val match = Regex("""EP(\d+)""", RegexOption.IGNORE_CASE).find(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)

        val html = try { app.get(data, headers = headers).text } catch (e: Exception) { "" }

        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
            val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            // 1. PRIMARY: Your original logic first (Handles unencrypted Dailymotion links)
            if (from.equals("dailymotion", ignoreCase = true) || rawUrl.contains("dailymotion", ignoreCase = true)) {
                val dmId = extractDailymotionId(rawUrl)
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
                }
            }

            // 2. FALLBACK IMPROVEMENT: MacCMS Decryption for normal episodes (solves "no link")
            try {
                if (encrypt == 1) {
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                } else if (encrypt == 2) {
                    rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                    rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt MacCMS url", e)
            }

            // 3. Check Dailymotion again POST-decryption, just in case the Dailymotion link was encrypted
            if (from.equals("dailymotion", ignoreCase = true) || rawUrl.contains("dailymotion", ignoreCase = true)) {
                val dmId = extractDailymotionId(rawUrl)
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
                }
            }

            // 4. Return regular decrypted video files (.m3u8/.mp4)
            if (rawUrl.contains(".m3u8", ignoreCase = true) || rawUrl.contains(".mp4", ignoreCase = true)) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = rawUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = if (rawUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                return true
            }
        }

        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
            val src = iframe.attr("src")
            val dmId = extractDailymotionId(src)
            if (dmId != null) {
                val videoUrl = "https://www.dailymotion.com/video/$dmId"
                if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
            }
        }

        val dmIdMatch = Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)""").find(html)
        if (dmIdMatch != null) {
            val dmId = dmIdMatch.groupValues[1]
            val videoUrl = "https://www.dailymotion.com/video/$dmId"
            if (loadExtractor(videoUrl, data, subtitleCallback, callback)) return true
        }

        doc?.select("iframe[src]")?.forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && !src.contains("dailymotion")) {
                if (loadExtractor(src, data, subtitleCallback, callback)) return true
            }
        }

        Log.d(TAG, "No playable source found for $data")
        return false
    }

    private fun extractDailymotionId(urlOrId: String): String? {
        // 1. PRIMARY: Your original working check
        if (urlOrId.matches(Regex("^[a-zA-Z0-9]{15,}$"))) return urlOrId
        val pattern = Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)""")
        val match = pattern.find(urlOrId)?.groupValues?.get(1)
        if (match != null) return match

        // 2. FALLBACK: Catch extra Dailymotion formats (like geo parameters or generic 'x' IDs)
        if (urlOrId.matches(Regex("^[xX][a-zA-Z0-9]{5,15}$"))) return urlOrId
        if (urlOrId.matches(Regex("^[a-zA-Z0-9]{6,15}$"))) return urlOrId
        val geoVideoParamPattern = Regex("""[?&]video=([a-zA-Z0-9]+)""")
        geoVideoParamPattern.find(urlOrId)?.let { return it.groupValues[1] }

        val genericPattern = Regex("""dailymotion\.com.*?/([xX][a-zA-Z0-9]+)""")
        genericPattern.find(urlOrId)?.let { return it.groupValues[1] }

        return null
    }

    private fun parseShowCards(doc: Document): List<SearchResponse> {
        return doc.select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                val title = a.attr("title").ifEmpty {
                    a.selectFirst("img")?.attr("alt") ?: a.text()
                }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.takeUnless { it.startsWith("data:") }?.let { fixUrl(it) }
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }
}
