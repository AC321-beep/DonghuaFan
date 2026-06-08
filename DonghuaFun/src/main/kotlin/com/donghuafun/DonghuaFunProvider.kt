package com.donghuafun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
        private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // -------------------------------------------------------------------
    //  URL helpers
    // -------------------------------------------------------------------
    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    private fun sidNidFromPlayUrl(url: String): Pair<Int, Int> {
        val sid = Regex("""/sid/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val nid = Regex("""/nid/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return sid to nid
    }

    // -------------------------------------------------------------------
    //  Main page
    // -------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/type/id/20.html"         to "Trending Donghua",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data
                     else request.data.replace(".html", "/page/$page.html")
        val doc = app.get(pageUrl).document
        return newHomePageResponse(request.name, parseShowCards(doc))
    }

    // -------------------------------------------------------------------
    //  Search
    // -------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return parseShowCards(doc)
    }

    // -------------------------------------------------------------------
    //  Detail page (episodes with sorting)
    // -------------------------------------------------------------------
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

        // Raw episodes before sorting
        val rawEpisodes = mutableListOf<Pair<Episode, Int>>()

        // 1) Episode list from anthology tabs (multiple servers)
        val serverTabs = doc.select(".anthology-tab a.vod-playerUrl")
        if (serverTabs.isNotEmpty()) {
            serverTabs.forEachIndexed { idx, tab ->
                val serverName = tab.text().trim()
                val serverId = tab.attr("data-form")
                val listDiv = doc.selectFirst(".anthology-list-box[data-form='$serverId']")
                    ?: doc.select(".anthology-list-box")?.getOrNull(idx)

                listDiv?.select("a[href*='/vod/play/id/$showId/']")?.forEach { a ->
                    val epUrl = fixUrl(a.attr("href"))
                    val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                    val episodeNumber = parseEpisodeNumber(epName)
                    val episode = newEpisode(epUrl) {
                        name = "$epName [$serverName]"
                    }
                    rawEpisodes.add(episode to episodeNumber)
                }
            }
        }

        // 2) Fallback: any play link
        if (rawEpisodes.isEmpty()) {
            doc.select("a[href*='/vod/play/id/$showId/']").forEach { a ->
                val epUrl = fixUrl(a.attr("href"))
                val epName = a.text().trim()
                val episodeNumber = parseEpisodeNumber(epName)
                val episode = newEpisode(epUrl) { name = epName }
                rawEpisodes.add(episode to episodeNumber)
            }
        }

        // 3) Last resort: generate numeric episodes
        if (rawEpisodes.isEmpty() && showId.isNotEmpty()) {
            val epCountText = doc.selectFirst(".video-info-main em, .detail-status")?.text() ?: ""
            val epCount = Regex("""EP(\d+)""").find(epCountText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            for (n in 1..epCount) {
                val epUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html"
                val episode = newEpisode(epUrl) { name = "EP$n" }
                rawEpisodes.add(episode to n)
            }
        }

        // Sort episodes: numeric ascending, then specials at the end
        val sortedEpisodes = rawEpisodes.sortedWith(compareBy({ it.second }, { it.first.name }))
            .map { it.first }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster?.let { fixUrl(it) }
            plot = description
            tags?.let { this.tags = it }
            year?.let { this.year = it }
            addEpisodes(DubStatus.None, sortedEpisodes)
        }
    }

    // Helper: extract numeric episode number
    private fun parseEpisodeNumber(name: String): Int {
        val match = Regex("""EP(\d+)""", RegexOption.IGNORE_CASE).find(name)
        if (match != null) return match.groupValues[1].toInt()
        if (name.contains("movie", ignoreCase = true) || name.contains("special", ignoreCase = true)) {
            return 10000 + name.hashCode().coerceIn(0, 9999)
        }
        return Int.MAX_VALUE
    }

    // -------------------------------------------------------------------
    //  Link extraction (unchanged)
    // -------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)

        // ----- 1) Try MacCMS Ajax API
        val showId = detailUrlToId(data)
        if (showId.isNotEmpty()) {
            try {
                val (sid, nid) = sidNidFromPlayUrl(data)
                val apiJson = app.get(
                    "$mainUrl/index.php/ajax/suggest",
                    params = mapOf(
                        "mid"  to "1",
                        "id"   to showId,
                        "sid"  to sid.toString(),
                        "nid"  to nid.toString(),
                        "type" to "1",
                    ),
                    headers = headers + ("X-Requested-With" to "XMLHttpRequest")
                ).text
                if (apiJson.isNotEmpty() && apiJson != "false" && !apiJson.startsWith("<")) {
                    if (extractFromPlayerJson(apiJson, data, subtitleCallback, callback)) return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Ajax API failed: ${e.message}")
            }
        }

        // ----- 2) Scan HTML for player_aaaa / player_bbbb
        val html = try { app.get(data, headers = headers).text } catch (e: Exception) { "" }
        val playerVars = listOf("player_aaaa", "player_bbbb", "player_cccc", "playinfo", "videoInfo")
        for (varName in playerVars) {
            val pattern = Regex("""var\s+$varName\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            val json = pattern.find(html)?.groupValues?.get(1)
            if (json != null) {
                Log.d(TAG, "Found player var: $varName")
                if (extractFromPlayerJson(json, data, subtitleCallback, callback)) return true
            }
        }

        // ----- 3) Iframe fallback
        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        val iframes = doc?.select("iframe[src], iframe[data-src]") ?: emptyList()
        for (iframe in iframes) {
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                Log.d(TAG, "Iframe fallback: $src")
                if (src.contains("/m3u8/") && src.contains("?url=")) {
                    val m3u8Url = Regex("""[?&]url=([^&]+)""").find(src)?.groupValues?.get(1)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    if (m3u8Url?.contains(".m3u8") == true) {
                        callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = data
                            this.quality = Qualities.P1080.value
                            this.headers = headers
                        })
                        return true
                    }
                }
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                return true
            }
        }

        return false
    }

    // -------------------------------------------------------------------
    //  JSON extractor
    // -------------------------------------------------------------------
    private suspend fun extractFromPlayerJson(
        json: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: Regex(""""link"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: return false

        val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""

        val decodedUrl = decodeUrl(rawUrl, encryptType)
            .let { if (it.startsWith("//")) "https:$it" else it }

        // Dailymotion
        if (from.equals("dailymotion", ignoreCase = true) || decodedUrl.contains("dailymotion.com") ||
            decodedUrl.matches(Regex("^[a-zA-Z0-9]{20,}$"))) {
            val dmId = if (decodedUrl.contains("dailymotion")) {
                Regex("""dailymotion\.com/(?:video/|embed/video/)([a-zA-Z0-9]+)""")
                    .find(decodedUrl)?.groupValues?.get(1) ?: decodedUrl
            } else {
                decodedUrl
            }
            val dmEmbed = "https://www.dailymotion.com/embed/video/$dmId"
            return loadExtractor(dmEmbed, referer, subtitleCallback, callback)
        }

        // Direct m3u8
        if (decodedUrl.contains(".m3u8")) {
            callback(newExtractorLink(name, name, decodedUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            })
            return true
        }

        // Direct mp4
        if (decodedUrl.contains(".mp4")) {
            callback(newExtractorLink(name, name, decodedUrl, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            })
            return true
        }

        return loadExtractor(decodedUrl, referer, subtitleCallback, callback)
    }

    // -------------------------------------------------------------------
    //  URL decoder
    // -------------------------------------------------------------------
    private fun decodeUrl(raw: String, encryptType: Int): String {
        return try {
            when (encryptType) {
                1 -> java.net.URLDecoder.decode(raw, "UTF-8")
                2 -> raw.reversed()
                3 -> String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
                else -> raw
            }.replace("\\/", "/")
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed: ${e.message}")
            raw.replace("\\/", "/")
        }
    }

    // -------------------------------------------------------------------
    //  Card parser (now a regular private method, not an extension)
    // -------------------------------------------------------------------
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
