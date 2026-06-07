package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi.Companion.loadExtractor
import org.jsoup.nodes.Document

class DonghuaFunProvider : MainAPI() {
    override var mainUrl = "https://donghuafun.com"
    override var name = "DonghuaFun"
    override var lang = "zh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    // ── URL helpers ───────────────────────────────────────────────────────────

    private fun detailUrlToId(url: String): String =
        Regex("""/id/(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

    private fun playUrl(showId: String, sid: Int, nid: Int) =
        "$mainUrl/index.php/vod/play/id/$showId/sid/$sid/nid/$nid.html"

    // ── Home page ─────────────────────────────────────────────────────────────

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

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/index.php/vod/search.html",
            params = mapOf("wd" to query)
        ).document
        return doc.parseShowCards()
    }

    // ── Show detail & episode list ────────────────────────────────────────────

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

    // ── Video extraction ──────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val html = response.text

        // 1. Parse out the MacPlayer setup object containing video properties
        val playerJson = Regex("""player_aaaa\s*=\s*(\{[^<]+?\})""")
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            var videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(playerJson)?.groupValues?.get(1)
                ?.replace("\\/", "/")

            val videoType = Regex(""""type"\s*:\s*"([^"]+)"""")
                .find(playerJson)?.groupValues?.get(1) ?: "m3u8"

            if (!videoUrl.isNullOrEmpty()) {
                if (videoUrl.startsWith("//")) {
                    videoUrl = "https:$videoUrl"
                }

                // If it's a direct streamable video file asset (.m3u8 or .mp4)
                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                    val quality = when {
                        data.contains("/sid/1/") -> "4K"
                        data.contains("/sid/2/") -> "1080P ENG"
                        data.contains("/sid/3/") -> "1080P Indo"
                        else -> "Auto"
                    }
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name $quality",
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = when (quality) {
                                "4K" -> Qualities.P2160.value
                                else -> Qualities.P1080.value
                            }
                        }
                    )
                    return true
                } else {
                    // 2. If it's an embed player address (like Dailymotion), route via core Extractors
                    return loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }

        // Fallback 1: Fall back to raw DOM layout frame interrogation
        val iframeSrc = response.document
            .selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }

        if (!iframeSrc.isNullOrEmpty()) {
            return loadExtractor(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
        }

        // Fallback 2: General fallback scan matching link paths anywhere on page
        val directUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""")
            .find(html)?.value
        if (!directUrl.isNullOrEmpty()) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = directUrl,
                    type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
