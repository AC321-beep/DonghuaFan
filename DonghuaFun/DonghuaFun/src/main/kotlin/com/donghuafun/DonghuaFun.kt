package com.donghuafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DonghuaFun : MainAPI() {

    override var mainUrl            = "https://donghuafun.com"
    override var name               = "DonghuaFun"
    override val hasMainPage        = true
    override val hasDownloadSupport = true
    override var lang               = "en"
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie)

    private val serverNames = mapOf(
        1 to "4K",
        2 to "1080P ENG",
        3 to "1080P Indo"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/show/id/20/page/"          to "All Donghua",
        "$mainUrl/index.php/vod/show/id/20/by/hits/page/"  to "Most Popular",
        "$mainUrl/index.php/vod/show/id/20/by/score/page/" to "Top Rated",
        "$mainUrl/index.php/vod/show/id/20/by/time/page/"  to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = "${request.data}${page}.html"
        val doc  = app.get(url, referer = mainUrl).document
        val home = doc.select(".module-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php/vod/search/wd/${query.encodeURL()}.html"
        val doc = app.get(url, referer = mainUrl).document
        return doc.select(".module-item, .search-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst(
            "h1.page-title, .module-info-heading h1, .module-info-main h1"
        )?.text()?.trim()
            ?: doc.title().split(" - ").firstOrNull()?.trim()
            ?: return null

        val poster = doc.selectFirst(
            ".module-item-pic img, .module-info-item-cover img, .module-info-pic img"
        )?.let { img ->
            (img.attr("data-src").ifBlank { img.attr("src") })
                .takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }

        val plot = doc.selectFirst(
            ".module-info-introduction-content, .video-desc, .module-info-description"
        )?.text()?.trim()

        val tags = doc.select(".module-info-tag a, .video-tags a").map { it.text().trim() }

        val year = doc.selectFirst(".module-info-meta")
            ?.text()
            ?.let { Regex("""(20\d{2})""").find(it)?.value?.toIntOrNull() }

        val allEpisodes = mutableListOf<Episode>()

        val episodeBlocks = doc.select(
            ".module-play-list-content, .module-play-list, .sort-list, [class*=play-list]"
        )

        episodeBlocks.forEachIndexed { sidIndex, block ->
            val sid         = sidIndex + 1
            val serverLabel = serverNames[sid] ?: "Source $sid"
            block.select("a[href]").forEach { a ->
                val epHref = fixUrl(a.attr("href"))
                val epText = a.text().trim()
                val epNum  = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val nid    = Regex("""/nid/(\d+)""").find(epHref)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                allEpisodes += newEpisode(epHref) {
                    name    = "[$serverLabel] $epText"
                    episode = epNum ?: nid
                    season  = sid
                }
            }
        }

        if (allEpisodes.isEmpty()) {
            doc.select("a[href*='/vod/play/id/']").forEach { a ->
                val href   = fixUrl(a.attr("href"))
                val epText = a.text().trim().ifBlank { "EP?" }
                val sidVal = Regex("""/sid/(\d+)""").find(href)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val nidVal = Regex("""/nid/(\d+)""").find(href)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum  = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull()
                allEpisodes += newEpisode(href) {
                    name    = "[${serverNames[sidVal] ?: "Source $sidVal"}] $epText"
                    episode = epNum ?: nidVal
                    season  = sidVal
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = tags
            this.year      = year
            addEpisodes(DubStatus.Subbed, allEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = mainUrl).text

        val playerJson = Regex(
            """var\s+player_aaaa\s*=\s*(\{[^;]+?\})\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.get(1)
            ?: Regex(
                """(?:player_aaaa|playerConfig|MacPlayerConfig)\s*=\s*(\{.+?\})\s*;""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).find(html)?.groupValues?.get(1)

        val rawVideoUrl = playerJson?.let {
            Regex(""""url"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
                ?: Regex("""'url'\s*:\s*'([^']+)'""").find(it)?.groupValues?.get(1)
        } ?: Regex(""""url"\s*:\s*"(https?://[^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return false

        val videoUrl = rawVideoUrl.replace("\\/", "/").trim()
        if (videoUrl.isBlank()) return false

        val sid        = Regex("""/sid/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val sourceName = "${this.name} · ${serverNames[sid] ?: "Source $sid"}"

        return when {
            videoUrl.contains(".m3u8", ignoreCase = true) -> {
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name   = sourceName,
                        url    = videoUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        quality = if (sid == 1) Qualities.P2160.value else Qualities.P1080.value
                        headers = mapOf(
                            "Referer"    to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
                        )
                    }
                )
                true
            }
            videoUrl.contains(".mp4", ignoreCase = true) -> {
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name   = sourceName,
                        url    = videoUrl,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "Referer"    to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
                        )
                    }
                )
                true
            }
            videoUrl.startsWith("http", ignoreCase = true) ->
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            else -> false
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a     = selectFirst("a[href]") ?: return null
        val href  = fixUrl(a.attr("href"))
        if (!href.contains("/vod/detail/")) return null
        val title = a.attr("title").ifBlank {
            selectFirst(".module-item-title, .video-title, .module-card-title")?.text()
        }?.trim() ?: return null
        val poster = selectFirst("img")?.let { img ->
            (img.attr("data-src").ifBlank { img.attr("src") })
                .takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val epBadge = selectFirst(
            ".module-item-caption, .video-info-items, .module-item-note"
        )?.text()?.trim()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(epBadge)
        }
    }

    private fun String.encodeURL(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
