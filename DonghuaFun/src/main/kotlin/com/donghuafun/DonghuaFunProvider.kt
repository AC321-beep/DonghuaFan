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

        // MacCMS v10 JSON API — returns real player_aaaa data that the
        // play page HTML deliberately omits for SEO bots.
        // Parameters: mid=1 (module), id=showId, sid=source, nid=episode index
        private const val PLAYER_API = "/index.php/ajax/suggest"
    }

    // ── URL Helpers ────────────────────────────────────────────────────────────

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    private fun sidNidFromPlayUrl(url: String): Pair<Int, Int> {
        val sid = Regex("""/sid/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val nid = Regex("""/nid/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return sid to nid
    }

    // ── Home Page ──────────────────────────────────────────────────────────────

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

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return doc.parseShowCards()
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

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

        if (episodes.isEmpty() && showId.isNotEmpty()) {
            val epCountText = doc.selectFirst(".video-info-main em, .detail-status")?.text() ?: ""
            val epCount = Regex("""EP(\d+)""").find(epCountText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            for (n in 1..epCount) {
                episodes.add(newEpisode("$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$n.html") {
                    name = "EP${epCount - n + 1}"
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

    // ── Link Extraction ────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val showId = detailUrlToId(data)
        val (sid, nid) = sidNidFromPlayUrl(data)

        Log.d(TAG, "loadLinks: showId=$showId sid=$sid nid=$nid  url=$data")

        // ── Strategy 1: MacCMS JSON API (the real player data source) ─────────
        // The play page HTML is an SEO shell — player_aaaa is only in the API response.
        if (showId.isNotEmpty()) {
            try {
                val apiUrl = "$mainUrl$PLAYER_API"
                val apiResponse = app.get(
                    apiUrl,
                    params = mapOf(
                        "mid"  to "1",
                        "id"   to showId,
                        "sid"  to sid.toString(),
                        "nid"  to nid.toString(),
                        "type" to "1",
                    ),
                    headers = mapOf(
                        "Referer"          to data,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    ),
                    referer = data,
                )
                val apiJson = apiResponse.text
                Log.d(TAG, "API response: $apiJson")

                if (apiJson.isNotEmpty() && apiJson != "false" && !apiJson.startsWith("<")) {
                    if (handlePlayerJson(apiJson, data, subtitleCallback, callback)) return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call failed: ${e.message}")
            }
        }

        // ── Strategy 2: Parse raw play page HTML as fallback ──────────────────
        // Some MacCMS setups still inline player_aaaa in the HTML for logged-in users.
        val html = try { app.get(data).text } catch (e: Exception) { "" }
        Log.d(TAG, "HTML fallback snippet: ${html.take(1000)}")

        val playerJsonRaw = listOf("player_aaaa", "player_aaab", "player_data")
            .firstNotNullOfOrNull { varName ->
                Regex("""$varName\s*=\s*(\{.+?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1)
            }

        if (playerJsonRaw != null) {
            Log.d(TAG, "Found inline playerJson: $playerJsonRaw")
            if (handlePlayerJson(playerJsonRaw, data, subtitleCallback, callback)) return true
        }

        // ── Strategy 3: iframe scan ───────────────────────────────────────────
        val doc = try { app.get(data).document } catch (e: Exception) { null }
        doc?.select("iframe[src], iframe[data-src]")?.forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                Log.d(TAG, "iframe fallback: $src")
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return false
    }

    // ── Player JSON Handler ────────────────────────────────────────────────────

    private suspend fun handlePlayerJson(
        json: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: return false
        val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""

        Log.d(TAG, "from=$from  rawUrl=$rawUrl  encrypt=$encryptType")

        val videoUrl = decodeVideoUrl(rawUrl, encryptType)
            .let { if (it.startsWith("//")) "https:$it" else it }

        Log.d(TAG, "decoded videoUrl=$videoUrl")

        // Dailymotion (sid/1 = 4K source in the working screenshots)
        if (videoUrl.contains("dailymotion.com") ||
            from.contains("dm", ignoreCase = true) ||
            from.contains("dailymotion", ignoreCase = true)) {
            val dmId = Regex("""dailymotion\.com/(?:video/|embed/video/)([a-zA-Z0-9]+)""")
                .find(videoUrl)?.groupValues?.get(1)
            val dmUrl = if (dmId != null) "https://www.dailymotion.com/video/$dmId" else videoUrl
            Log.d(TAG, "Dailymotion: $dmUrl")
            loadExtractor(dmUrl, referer, subtitleCallback, callback)
            return true
        }

        // OkRu (sid/3 in the working screenshots)
        if (videoUrl.contains("ok.ru") || from.contains("okru", ignoreCase = true)) {
            Log.d(TAG, "OkRu: $videoUrl")
            loadExtractor(videoUrl, referer, subtitleCallback, callback)
            return true
        }

        // Direct .m3u8 / .mp4 stream
        if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
            callback(newExtractorLink(name, name, videoUrl,
                if (videoUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.P1080.value
            })
            return true
        }

        // Any other embed URL — let CloudStream's built-in extractors handle it
        if (loadExtractor(videoUrl, referer, subtitleCallback, callback)) return true

        return false
    }

    // ── Decode Helper ──────────────────────────────────────────────────────────

    private fun decodeVideoUrl(raw: String, encryptType: Int): String {
        return try {
            when (encryptType) {
                1 -> java.net.URLDecoder.decode(raw, "UTF-8")
                2 -> raw.reversed()
                3 -> String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
                else -> raw
            }.replace("\\/", "/")
        } catch (e: Exception) {
            Log.e(TAG, "decode failed type=$encryptType: ${e.message}")
            raw.replace("\\/", "/")
        }
    }

    // ── Document Helper ────────────────────────────────────────────────────────

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
