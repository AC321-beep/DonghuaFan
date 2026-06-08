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

    // ── Link Extraction ────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val showId = detailUrlToId(data)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer"    to data
        )

        Log.d(TAG, "loadLinks: showId=$showId")

        // ── Strategy 1: MacCMS JSON API ───────────────────────────────────────
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
                    headers = mapOf(
                        "User-Agent"       to USER_AGENT,
                        "Referer"          to data,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                ).text

                Log.d(TAG, "API response: $apiJson")

                if (apiJson.isNotEmpty() && apiJson != "false" && !apiJson.startsWith("<")) {
                    if (extractVideoFromJson(apiJson, data, headers, subtitleCallback, callback)) return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call failed: ${e.message}")
            }
        }

        // ── Strategy 2: Inline player_aaaa in HTML ────────────────────────────
        val html = try { app.get(data, headers = headers).text } catch (e: Exception) { "" }

        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            Log.d(TAG, "Found inline player_aaaa")
            if (extractVideoFromJson(playerJson, data, headers, subtitleCallback, callback)) return true
        }

        // ── Strategy 3: iframe scan ───────────────────────────────────────────
        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src], iframe[data-src]")?.forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                Log.d(TAG, "iframe fallback: $src")
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                return true
            }
        }

        return false
    }

    // ── Video Extractor (UPDATED with 4K support) ─────────────────────────────

    private suspend fun extractVideoFromJson(
        json: String,
        referer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: return false
        val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""

        val decodedUrl = decodeVideoUrl(rawUrl, encryptType)
            .let { if (it.startsWith("//")) "https:$it" else it }

        Log.d(TAG, "from=$from  decodedUrl=$decodedUrl")

        // ── Dailymotion ───────────────────────────────────────────────────────
        val isDailymotion = from.contains("dailymotion", ignoreCase = true) ||
                            from.contains("dm", ignoreCase = true) ||
                            decodedUrl.contains("dailymotion.com")

        if (isDailymotion) {
            val dmId = Regex("""dailymotion\.com/(?:video/|embed/video/)([a-zA-Z0-9]+)""")
                .find(decodedUrl)?.groupValues?.get(1) ?: decodedUrl

            val dmEmbedUrl = "https://www.dailymotion.com/embed/video/$dmId"
            val dmHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to "https://www.dailymotion.com/"
            )

            Log.d(TAG, "Fetching Dailymotion embed: $dmEmbedUrl")

            val dmHtml = try {
                app.get(dmEmbedUrl, headers = dmHeaders).text
            } catch (e: Exception) {
                Log.e(TAG, "Dailymotion fetch failed: ${e.message}")
                return false
            }

            // ✅ 4K (2160p) quality added
            val qualityMap = mapOf(
                "2160" to Qualities.P2160.value,
                "1080" to Qualities.P1080.value,
                "720"  to Qualities.P720.value,
                "480"  to Qualities.P480.value,
                "380"  to Qualities.P360.value,
                "240"  to Qualities.P240.value,
            )

            var found = false
            qualityMap.forEach { (label, quality) ->
                val m3u8 = Regex(""""${label}":\s*\{"(?:auto|en)":\s*"([^"]+\.m3u8[^"]*)"""")
                    .find(dmHtml)?.groupValues?.get(1)?.replace("\\/", "/")
                if (m3u8 != null) {
                    Log.d(TAG, "Dailymotion ${label}p: $m3u8")
                    callback(newExtractorLink(name, "$name ${label}p", m3u8, ExtractorLinkType.M3U8) {
                        this.referer  = "https://www.dailymotion.com/"
                        this.quality  = quality
                        this.headers  = dmHeaders
                    })
                    found = true
                }
            }

            // Fallback: any m3u8
            if (!found) {
                Regex("""(https://[^\s"']+\.m3u8[^\s"']*)""").findAll(dmHtml).forEach { match ->
                    val m3u8 = match.value.replace("\\/", "/")
                    Log.d(TAG, "Dailymotion fallback m3u8: $m3u8")
                    callback(newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "https://www.dailymotion.com/"
                        this.quality = Qualities.P720.value
                        this.headers = dmHeaders
                    })
                    found = true
                }
            }

            return found
        }

        // ── OkRu ──────────────────────────────────────────────────────────────
        if (decodedUrl.contains("ok.ru") || from.contains("okru", ignoreCase = true)) {
            Log.d(TAG, "OkRu: $decodedUrl")
            loadExtractor(decodedUrl, referer, subtitleCallback, callback)
            return true
        }

        // ── Direct .m3u8 ──────────────────────────────────────────────────────
        if (decodedUrl.contains(".m3u8")) {
            Log.d(TAG, "Direct m3u8: $decodedUrl")
            callback(newExtractorLink(name, name, decodedUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = headers
            })
            return true
        }

        // ── Direct .mp4 ───────────────────────────────────────────────────────
        if (decodedUrl.contains(".mp4")) {
            Log.d(TAG, "Direct mp4: $decodedUrl")
            callback(newExtractorLink(name, name, decodedUrl, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = headers
            })
            return true
        }

        // ── Generic embed ─────────────────────────────────────────────────────
        Log.d(TAG, "Generic fallback: $decodedUrl")
        return loadExtractor(decodedUrl, referer, subtitleCallback, callback)
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
