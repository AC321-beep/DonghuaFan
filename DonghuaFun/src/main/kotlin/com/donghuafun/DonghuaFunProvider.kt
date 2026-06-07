package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.nio.charset.StandardCharsets

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaFun"
    }

    // ── URL Helpers ───────────────────────────────────────────────────────────

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    private fun playUrl(showId: String, sid: Int, nid: Int) =
        "$mainUrl/index.php/vod/play/id/$showId/sid/$sid/nid/$nid.html"

    // ── Home Page Routing ─────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/type/id/20.html"         to "Trending Donghua",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html" to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data
                      else request.data.replace(".html", "/page/$page.html")
        val doc = app.get(pageUrl).document
        val items = doc.parseShowCards()
        return newHomePageResponse(request.name, items)
    }

    // ── Search Logic ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return doc.parseShowCards()
    }

    // ── Detail & Episode List Processing ──────────────────────────────────────

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
                val href = fixUrl(a.attr("href"))
                val epName = a.text().trim()
                episodes.add(newEpisode(href) { name = epName })
            }
        } else {
            serverBlocks.forEachIndexed { sIdx, block ->
                val serverName = block.previousElementSibling()?.text()?.trim()
                    ?: "Source ${sIdx + 1}"
                block.select("a").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    val epName = a.text().trim()
                    episodes.add(newEpisode(href) { name = "$epName [$serverName]" })
                }
            }
        }

        if (episodes.isEmpty() && showId.isNotEmpty()) {
            val epCountText = doc.selectFirst(".video-info-main em, .detail-status")?.text() ?: ""
            val epCount = Regex("""EP(\d+)""").find(epCountText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            for (n in 1..epCount) {
                episodes.add(newEpisode(playUrl(showId, 1, n)) {
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

    // ── Video Link Extraction Layer ───────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val html = response.text

        Log.d(TAG, "loadLinks URL: $data")
        Log.d(TAG, "HTML snippet: ${html.take(2000)}")

        // 1. Locate player_aaaa JSON payload — use DOT_MATCHES_ALL so it handles
        //    multiline JS blocks; try multiple key names the site may use.
        val playerJsonRaw = listOf(
            Regex("""player_aaaa\s*=\s*(\{.+?\})\s*;""", RegexOption.DOT_MATCHES_ALL),
            Regex("""player_aaab\s*=\s*(\{.+?\})\s*;""", RegexOption.DOT_MATCHES_ALL),
            Regex("""player_data\s*=\s*(\{.+?\})\s*;""", RegexOption.DOT_MATCHES_ALL),
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }

        Log.d(TAG, "playerJson: $playerJsonRaw")

        if (playerJsonRaw != null) {
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(playerJsonRaw)?.groupValues?.get(1)
            val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""")
                .find(playerJsonRaw)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            Log.d(TAG, "rawUrl=$rawUrl  encryptType=$encryptType")

            if (!rawUrl.isNullOrEmpty()) {
                // 2. Decode based on encrypt flag
                var videoUrl = decodeVideoUrl(rawUrl, encryptType)

                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                Log.d(TAG, "decoded videoUrl=$videoUrl")

                // 3. Delegate to KSRPlayer for play.donghuafun.com iframes
                if (videoUrl.contains("play.donghuafun.com")) {
                    val ksr = KSRPlayer()
                    ksr.getUrl(videoUrl, data, subtitleCallback, callback)
                    return true
                }

                // 4. Direct stream asset
                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || videoUrl.contains("playlist")) {
                    val quality = when {
                        data.contains("/sid/1/") -> "4K"
                        data.contains("/sid/2/") -> "1080P"
                        else -> "Auto"
                    }
                    val linkType = if (videoUrl.contains(".mp4"))
                        ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name $quality",
                            url = videoUrl,
                            type = linkType
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }

                // 5. Try loadExtractor for any other recognised embed
                if (loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)) return true
            }
        }

        // 6. Macro DOM Fallback: check for explicit iframe elements
        val iframeSrc = response.document
            .selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }

        Log.d(TAG, "iframeSrc fallback: $iframeSrc")

        if (!iframeSrc.isNullOrEmpty()) {
            val cleanIframe = fixUrl(iframeSrc)
            if (cleanIframe.contains("play.donghuafun.com")) {
                val ksr = KSRPlayer()
                ksr.getUrl(cleanIframe, data, subtitleCallback, callback)
                return true
            }
            return loadExtractor(cleanIframe, mainUrl, subtitleCallback, callback)
        }

        Log.d(TAG, "No link found for: $data")
        return false
    }

    // ── Decrypt / Decode Helper ───────────────────────────────────────────────

    /**
     * Handles all known encrypt flag values used by the site:
     *   0 = plain URL
     *   1 = URL-encoded (percent-encoded)
     *   2 = reversed string
     *   3 = Base64
     * Unknown types fall back to returning the raw value so we never silently
     * drop a URL that might work as-is.
     */
    private fun decodeVideoUrl(raw: String, encryptType: Int): String {
        return try {
            when (encryptType) {
                1 -> java.net.URLDecoder.decode(raw, "UTF-8")
                2 -> raw.reversed()
                3 -> {
                    val bytes = Base64.decode(raw, Base64.DEFAULT)
                    String(bytes, StandardCharsets.UTF_8)
                }
                else -> raw
            }.replace("\\/", "/")
        } catch (e: Exception) {
            Log.e(TAG, "decodeVideoUrl failed for type=$encryptType: ${e.message}")
            raw.replace("\\/", "/")
        }
    }

    // ── Global Document Helpers ───────────────────────────────────────────────

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

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
    }
}
