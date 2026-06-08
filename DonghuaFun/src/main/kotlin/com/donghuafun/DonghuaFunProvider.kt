package com.donghuafun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // ────────────────────────────────────────────────────────────── URL Helpers
    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    private fun sidNidFromPlayUrl(url: String): Pair<Int, Int> {
        val sid = Regex("""/sid/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val nid = Regex("""/nid/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return sid to nid
    }

    // ────────────────────────────────────────────────────────────── Main Page
    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/type/id/20.html"         to "Trending Donghua",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data
                      else request.data.replace(".html", "/page/$page.html")
        val doc = app.get(pageUrl).document
        return newHomePageResponse(request.name, doc.parseShowCards())
    }

    // ────────────────────────────────────────────────────────────── Search
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return doc.parseShowCards()
    }

    // ────────────────────────────────────────────────────────────── Detail
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showId = detailUrlToId(url)

        val title = doc.selectFirst("h1, .video-title")?.text()?.trim()
            ?: doc.title().substringBefore(" Donghua").trim()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".detail-pic img, .video-cover img")?.attr("data-src")

        val description = doc.selectFirst(".video-desc, .detail-desc, p.desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val tags = doc.select("a[href*='/class/']").map { it.text().trim() }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val serverBlocks = doc.select("div.module-player-list, ul.anthology-list-play")

        if (serverBlocks.isEmpty()) {
            doc.select("a[href*='/vod/play/id/$showId/']").forEach { a ->
                episodes.add(newEpisode(fixUrl(a.attr("href"))) { name = a.text().trim() })
            }
        } else {
            serverBlocks.forEachIndexed { sIdx, block ->
                val serverName = block.previousElementSibling()?.text()?.trim()
                    ?: "Source ${sIdx + 1}"
                block.select("a").forEach { a ->
                    episodes.add(newEpisode(fixUrl(a.attr("href"))) {
                        name = "${a.text().trim()} [$serverName]"
                    })
                }
            }
        }

        // Fallback: generate episodes if none found
        if (episodes.isEmpty() && showId.isNotEmpty()) {
            val epCountText = doc.selectFirst(".video-info-main em, .detail-status")?.text() ?: ""
            val epCount = Regex("""EP(\d+)""").find(epCountText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            for (n in 1..epCount) {
                episodes.add(newEpisode("$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html") {
                    name = "EP$n"
                })
            }
        }

        return newAnimeLoadResponse(title ?: name, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            tags?.let { this.tags = it }
            year?.let { this.year = it }
            addEpisodes(DubStatus.None, episodes)
        }
    }

    // ────────────────────────────────────────────────────────────── Link Extraction
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)

        // 1. Try MacCMS Ajax API (for some sources)
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

        // 2. Scan HTML for player_aaaa, player_bbbb, etc.
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

        // 3. Fallback: any iframe
        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src], iframe[data-src]")?.forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                Log.d(TAG, "Iframe fallback: $src")
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                return true
            }
        }

        return false
    }

    // ────────────────────────────────────────────────────────────── JSON Extractor
    private suspend fun extractFromPlayerJson(
        json: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Extract basic fields
        val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: Regex(""""link"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: return false
        val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""

        val decodedUrl = decodeUrl(rawUrl, encryptType)
            .let { if (it.startsWith("//")) "https:$it" else it }

        Log.d(TAG, "from=$from, decodedUrl=$decodedUrl")

        // ────────────────────────────────────────────────── Dailymotion (4K source)
        if (from.equals("dailymotion", ignoreCase = true) ||
            decodedUrl.contains("dailymotion.com") ||
            decodedUrl.matches(Regex("^[a-zA-Z0-9]{20,}$"))) {   // likely a Dailymotion ID
            val dmId = if (decodedUrl.contains("dailymotion")) {
                Regex("""dailymotion\.com/(?:video/|embed/video/)([a-zA-Z0-9]+)""")
                    .find(decodedUrl)?.groupValues?.get(1) ?: decodedUrl
            } else {
                decodedUrl   // already just the ID
            }
            val dmEmbedUrl = "https://www.dailymotion.com/embed/video/$dmId"
            Log.d(TAG, "Dailymotion embed: $dmEmbedUrl")
            // Let CloudStream's built-in extractors handle Dailymotion
            return loadExtractor(dmEmbedUrl, referer, subtitleCallback, callback)
        }

        // ────────────────────────────────────────────────── Direct m3u8 (1080P ENG source)
        if (decodedUrl.contains(".m3u8")) {
            Log.d(TAG, "Direct m3u8: $decodedUrl")
            callback(newExtractorLink(name, name, decodedUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            })
            return true
        }

        // ────────────────────────────────────────────────── Direct mp4
        if (decodedUrl.contains(".mp4")) {
            callback(newExtractorLink(name, name, decodedUrl, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            })
            return true
        }

        // ────────────────────────────────────────────────── Generic embed (e.g., 1080P Indo)
        Log.d(TAG, "Generic embed fallback: $decodedUrl")
        return loadExtractor(decodedUrl, referer, subtitleCallback, callback)
    }

    // ────────────────────────────────────────────────────────────── URL Decoding
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

    // ────────────────────────────────────────────────────────────── Card Parser
    private fun Document.parseShowCards(): List<SearchResponse> {
        return select("a[href*='/vod/detail/id/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                if (href.isEmpty()) return@mapNotNull null
                val title = a.attr("title").ifEmpty {
                    a.selectFirst("img")?.attr("alt") ?: a.text()
                }.trim()
                if (title.isEmpty()) return@mapNotNull null
                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.let { if (it.startsWith("data:")) null else fixUrl(it) }
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            }
    }
}
