package com.donghuafun

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

        // Try named server blocks first
        val serverBlocks = doc.select("div.module-player-list, ul.anthology-list-play")
        if (serverBlocks.isEmpty()) {
            // Fallback: grab all play links for this show directly
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

        // Last resort: build episode list from EP count in the page header
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

        // MxoneCMS injects: var player_aaaa = { "url": "...", "type": "m3u8", ... }
        val playerJson = Regex("""player_aaaa\s*=\s*(\{[^<]+?\})""")
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            val videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(playerJson)?.groupValues?.get(1)
                ?.replace("\\/", "/")

            val videoType = Regex(""""type"\s*:\s*"([^"]+)"""")
                .find(playerJson)?.groupValues?.get(1) ?: "m3u8"

            if (!videoUrl.isNullOrEmpty()) {
                if (videoUrl.startsWith("http")) {
                    val quality = when {
                        data.contains("/sid/1/") -> "4K"
                        data.contains("/sid/2/") -> "1080P ENG"
                        data.contains("/sid/3/") -> "1080P Indo"
                        else -> "Auto"
                    }
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "$name $quality",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = when (quality) {
                                "4K" -> Qualities.UHD4K.value
                                else -> Qualities.P1080.value
                            },
                            isM3u8 = videoType.contains("m3u8") || videoType.contains("hls")
                        )
                    )
                    return true
                } else {
                    // External embed (Bilibili, Youku, Filemoon, etc.)
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }
        }

        // Fallback 1: iframe on the page
        val iframeSrc = response.document
            .selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }

        if (!iframeSrc.isNullOrEmpty()) {
            loadExtractor(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
            return true
        }

        // Fallback 2: raw .m3u8 / .mp4 URL anywhere in the page source
        val directUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""")
            .find(html)?.value
        if (!directUrl.isNullOrEmpty()) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = directUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = directUrl.contains(".m3u8")
                )
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
