package com.donghuafun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
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
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        // Dailymotion geo-player instance used by this site — extracted from the iframe src
        private const val DM_PLAYER_ID = "xkyen"

        // sid → (label, quality)
        // sid=1 = 4K (dailymotion), sid=2 = 1080P ENG (1080eng), sid=3 = 1080P Indo (skip)
        private val SOURCES = mapOf(
            1 to ("4K"        to Qualities.P2160.value),
            2 to ("1080P ENG" to Qualities.P1080.value),
        )
        // from-field values that belong to the sources we want
        private val WANTED_FROM = setOf("dailymotion", "1080eng")
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

        // ── Episode collection ──────────────────────────────────────────────
        // The site stores episodes in REVERSE order: nid=1 is the latest episode.
        // We collect only sid=1 (4K) links to get the episode names, then at
        // loadLinks() time we also fetch sid=2 (1080P ENG) for the same nid.
        // This avoids duplicating episode rows; instead we serve both qualities
        // from a single episode entry.
        val episodes = mutableListOf<Episode>()

        // Prefer scraping from the anthology list (most accurate, has ep names)
        val allServerBlocks = doc.select("div.anthology-list-box")
        val firstServerBlock = allServerBlocks.firstOrNull()

        if (firstServerBlock != null) {
            // Collect ep name + nid from the FIRST source block (sid=1 / 4K)
            // The list is reverse-ordered on-site; we reverse to get EP01 first.
            val epLinks = firstServerBlock.select("a[href*='/vod/play/id/$showId/sid/1/']")
                .toList().reversed()
            epLinks.forEach { a ->
                val href = fixUrl(a.attr("href"))
                val epName = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                episodes.add(newEpisode(href) { name = epName })
            }
        }

        // Fallback: direct link scan
        if (episodes.isEmpty()) {
            doc.select("a[href*='/vod/play/id/$showId/sid/1/']")
                .toList().reversed()
                .forEach { a ->
                    episodes.add(newEpisode(fixUrl(a.attr("href"))) {
                        name = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                    })
                }
        }

        // Last resort: construct URLs from episode count
        if (episodes.isEmpty() && showId.isNotEmpty()) {
            val epCountText = doc.selectFirst(".video-info-main em, .detail-status")?.text() ?: ""
            val epCount = Regex("""(\d+)""").find(epCountText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            for (n in 1..epCount) {
                // nid=1 is the LATEST, so nid=epCount is EP1
                val nid = epCount - n + 1
                episodes.add(newEpisode("$mainUrl/index.php/vod/play/id/$showId/sid/1/nid/$nid.html") {
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
    // Each episode data URL points to sid=1/nid=X.
    // We fetch BOTH sid=1 (4K) and sid=2 (1080P ENG) and emit two ExtractorLinks.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val showId = detailUrlToId(data)
        val (_, nid) = sidNidFromPlayUrl(data)

        Log.d(TAG, "loadLinks: showId=$showId nid=$nid")

        var anyFound = false

        // Iterate over the sources we care about (4K=sid1, 1080ENG=sid2)
        for ((sid, labelQuality) in SOURCES) {
            val (label, quality) = labelQuality
            val playUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/$sid/nid/$nid.html"

            val dmVideoId = fetchDailymotionVideoId(playUrl, showId, sid, nid)
            if (dmVideoId == null) {
                Log.w(TAG, "[$label] Could not get DM video id for sid=$sid nid=$nid")
                continue
            }

            Log.d(TAG, "[$label] DM video id: $dmVideoId")

            // Build the geo-player embed URL and extract the real m3u8 streams
            val found = extractDailymotionStreams(dmVideoId, label, quality, callback)
            if (found) anyFound = true
        }

        return anyFound
    }

    // ── Fetch Dailymotion video ID ─────────────────────────────────────────────
    // Tries: 1) Ajax API  2) Inline player_aaaa  3) Iframe src attribute

    private suspend fun fetchDailymotionVideoId(
        playUrl: String,
        showId: String,
        sid: Int,
        nid: Int,
    ): String? {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to playUrl)

        // Strategy 1: MacCMS Ajax API
        if (showId.isNotEmpty()) {
            try {
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
                        "Referer"          to playUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                ).text

                Log.d(TAG, "Ajax API [sid=$sid]: $apiJson")

                if (apiJson.isNotEmpty() && apiJson != "false" && !apiJson.startsWith("<")) {
                    val id = extractDmIdFromJson(apiJson)
                    if (id != null) return id
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ajax API failed sid=$sid: ${e.message}")
            }
        }

        // Strategy 2 & 3: Parse the play page HTML
        val html = try {
            app.get(playUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "Fetch play page failed: ${e.message}")
            return null
        }

        // Inline player_aaaa JSON
        val playerJson = Regex(
            """var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
            Log.d(TAG, "player_aaaa from=$from sid=$sid")
            // Only use if this page's "from" matches what we expect for this sid
            // (sid=1 → dailymotion, sid=2 → 1080eng)
            val id = extractDmIdFromJson(playerJson)
            if (id != null) return id
        }

        // Iframe src fallback — look for geo.dailymotion embed
        val iframeSrc = Regex("""geo\.dailymotion\.com/player/[^"?]+\?video=([a-zA-Z0-9]+)""")
            .find(html)?.groupValues?.get(1)
        if (iframeSrc != null) return iframeSrc

        return null
    }

    // ── Parse DM video ID from a JSON blob ────────────────────────────────────
    // player_aaaa.url IS the video ID directly when encrypt=0.
    // When encrypt != 0 we decode then strip any DM URL prefix.

    private fun extractDmIdFromJson(json: String): String? {
        val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
            .find(json)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
        val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""")
            .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val decoded = decodeVideoUrl(rawUrl, encryptType)

        // If it already looks like a raw DM video ID (alphanumeric, ~18 chars) return it
        if (decoded.matches(Regex("""[a-zA-Z0-9_-]{5,25}"""))) return decoded

        // Otherwise try to extract from a full DM URL
        val fromUrl = Regex("""dailymotion\.com/(?:video/|embed/video/)([a-zA-Z0-9]+)""")
            .find(decoded)?.groupValues?.get(1)
        if (fromUrl != null) return fromUrl

        // Or from a geo embed URL
        val fromGeo = Regex("""geo\.dailymotion\.com/player/[^?]+\?video=([a-zA-Z0-9]+)""")
            .find(decoded)?.groupValues?.get(1)
        return fromGeo
    }

    // ── Extract M3U8 streams from Dailymotion geo-player ──────────────────────
    // The geo.dailymotion.com embed page contains the stream data as JSON in a
    // <script> block. We parse out the per-quality m3u8 URLs.

    private suspend fun extractDailymotionStreams(
        videoId: String,
        sourceLabel: String,
        sourceQuality: Int,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = "https://geo.dailymotion.com/player/$DM_PLAYER_ID.html?video=$videoId"
        val dmReferer = "https://www.dailymotion.com/"
        val dmHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to dmReferer)

        Log.d(TAG, "Fetching DM embed [$sourceLabel]: $embedUrl")

        val html = try {
            app.get(embedUrl, headers = dmHeaders).text
        } catch (e: Exception) {
            Log.e(TAG, "DM embed fetch failed: ${e.message}")
            return false
        }

        // Quality map: label in JSON → CloudStream quality value
        val qualityMap = listOf(
            "2160" to Qualities.P2160.value,
            "1080" to Qualities.P1080.value,
            "720"  to Qualities.P720.value,
            "480"  to Qualities.P480.value,
            "360"  to Qualities.P360.value,
            "240"  to Qualities.P240.value,
        )

        var found = false

        qualityMap.forEach { (label, quality) ->
            // DM embed JSON pattern: "1080":{"auto":"https://...m3u8...","en":"..."}
            val m3u8 = Regex(""""$label"\s*:\s*\{[^}]*"(?:auto|en|fr|[a-z]{2})"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (m3u8 != null) {
                Log.d(TAG, "[$sourceLabel] ${label}p m3u8: $m3u8")
                callback(
                    newExtractorLink(
                        source  = name,
                        name    = "$name $sourceLabel ${label}p",
                        url     = m3u8,
                        type    = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = dmReferer
                        this.quality = quality
                        this.headers = dmHeaders
                    }
                )
                found = true
            }
        }

        // Fallback: any m3u8 in the page
        if (!found) {
            Regex("""(https://[^\s"'\\]+\.m3u8[^\s"']*)""").findAll(html).forEach { match ->
                val m3u8 = match.value.replace("\\/", "/")
                Log.d(TAG, "[$sourceLabel] fallback m3u8: $m3u8")
                callback(
                    newExtractorLink(
                        source  = name,
                        name    = "$name $sourceLabel",
                        url     = m3u8,
                        type    = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = dmReferer
                        this.quality = sourceQuality
                        this.headers = dmHeaders
                    }
                )
                found = true
            }
        }

        return found
    }

    // ── Decode Helper ──────────────────────────────────────────────────────────

    private fun decodeVideoUrl(raw: String, encryptType: Int): String {
        return try {
            when (encryptType) {
                1    -> java.net.URLDecoder.decode(raw, "UTF-8")
                2    -> raw.reversed()
                3    -> String(
                    android.util.Base64.decode(raw, android.util.Base64.DEFAULT),
                    Charsets.UTF_8
                )
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
