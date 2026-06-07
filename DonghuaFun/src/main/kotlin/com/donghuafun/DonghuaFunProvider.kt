package com.donghuafun

import android.util.Base64
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

    // ── URL Helpers ───────────────────────────────────────────────────────────

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    private fun playUrl(showId: String, sid: Int, nid: Int) =
        "$mainUrl/index.php/vod/play/id/$showId/sid/$sid/nid/$nid.html"

    // ── Home Page Routing ─────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/type/id/20.html"          to "Trending Donghua",
        "$mainUrl/index.php/vod/show/id/20/by/hits.html"  to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/time.html"  to "Recently Updated",
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

        // 1. Locate the player script payload block
        val playerJson = Regex("""player_aaaa\s*=\s*(\{[^<]+?\})""").find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)
            val encryptType = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (!rawUrl.isNullOrEmpty()) {
                // 2. Decode the Base64 streaming token if encrypt type is flag 3
                var videoUrl = if (encryptType == 3) {
                    val decodedBytes = Base64.decode(rawUrl, Base64.DEFAULT)
                    String(decodedBytes, StandardCharsets.UTF_8).replace("\\/", "/")
                } else {
                    rawUrl.replace("\\/", "/")
                }

                if (videoUrl.startsWith("//")) {
                    videoUrl = "https:$videoUrl"
                }

                // 3. Delegate directly to KSRPlayer if processing a play.donghuafun iframe
                if (videoUrl.contains("play.donghuafun.com")) {
                    val ksr = KSRPlayer()
                    ksr.getUrl(videoUrl, data, subtitleCallback, callback)
                    return true
                }

                // 4. Fallback check if it maps straight to an unencrypted stream asset
                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || videoUrl.contains("playlist")) {
                    val quality = when {
                        data.contains("/sid/1/") -> "4K"
                        data.contains("/sid/2/") -> "1080P"
                        else -> "Auto"
                    }
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name $quality",
                            url = videoUrl,
                            type = if (videoUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                } else {
                    if (loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)) return true
                }
            }
        }

        // 5. Macro DOM Fallback: Check for any explicit frame elements
        val iframeSrc = response.document.selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }

        if (!iframeSrc.isNullOrEmpty()) {
            val cleanIframe = fixUrl(iframeSrc)
            if (cleanIframe.contains("play.donghuafun.com")) {
                val ksr = KSRPlayer()
                ksr.getUrl(cleanIframe, data, subtitleCallback, callback)
                return true
            }
            return loadExtractor(cleanIframe, mainUrl, subtitleCallback, callback)
        }

        return false
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
