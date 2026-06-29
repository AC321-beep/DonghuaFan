package com.luciferdonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "Lucifer Donghua"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=movie&sub=" to "Movies",
        "$mainUrl/network/tencent/" to "Tencent Anime",
        "$mainUrl/network/youku/" to "YouKu Anime",
        "$mainUrl/anime/?status=completed" to "Completed"
    )

    // ✅ Removed getExtractorApis – extractors are auto‑detected

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val home = document.select("article.bs").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".tt h2")
        val title = titleElement?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a[itemprop=url]")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.ts-post-image")?.attr("src"))
        val epText = this.selectFirst(".bt .epx")?.text()
        val epCount = epText?.let {
            Regex("""Ep\s*(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true, epCount)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
        val results = document.select("article.bs").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".thumb img, .poster img")?.attr("src"))
        val description = document.selectFirst(".entry-content p, .synopsis p, .desc")?.text()?.trim()
        val tags = document.select(".genx a, .genres a").map { it.text() }

        val yearText = document.selectFirst(".split span:contains(Released), .info-content span:contains(Year)")?.text()
        val year = yearText?.filter { it.isDigit() }?.toIntOrNull()

        val episodes = mutableListOf<Episode>()

        document.select(".eplister ul li, #episode_list li, .listeps ul li").forEach { ep ->
            val linkElement = ep.selectFirst("a") ?: return@forEach
            val epHref = fixUrlNull(linkElement.attr("href")) ?: return@forEach
            val epName = linkElement.selectFirst(".epl-num, .epnum")?.text()?.trim()
                ?: ep.text().trim()
            val epNum = epName.filter { it.isDigit() }.toIntOrNull()

            episodes.add(
                newEpisode(data = epHref) {
                    this.name = "Episode $epName"
                    this.episode = epNum
                }
            )
        }

        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }
        val episodeMap = mutableMapOf(
            DubStatus.Subbed to sortedEpisodes
        )

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.episodes = episodeMap
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1️⃣ Default iframe (Dailymotion)
        val defaultIframe = document.selectFirst("#pembed iframe, .player-embed iframe")
        if (defaultIframe != null) {
            var src = defaultIframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            val clean = fixUrlNull(src)
            if (clean != null && clean.isNotBlank()) {
                loadExtractor(clean, mainUrl, subtitleCallback, callback)
            }
        }

        // 2️⃣ Mirror dropdown – fetch each mirror page and extract
        val mirrorSelect = document.selectFirst("select.mirror")
        if (mirrorSelect != null) {
            val options = mirrorSelect.select("option")
            val mirrors = options.mapNotNull { option ->
                val value = option.attr("value")
                val label = option.text()
                if (value.isNotBlank() && !label.contains("Select", ignoreCase = true)) {
                    label to value
                } else null
            }

            mirrors.forEach { (label, suffix) ->
                val mirrorUrl = "$data$suffix"
                try {
                    val mirrorDoc = app.get(mirrorUrl).document
                    val iframe = mirrorDoc.selectFirst("iframe[src]")
                    if (iframe != null) {
                        var src = iframe.attr("src")
                        if (src.startsWith("//")) src = "https:$src"
                        val clean = fixUrlNull(src)
                        if (clean != null && clean.isNotBlank()) {
                            loadExtractor(clean, mainUrl, subtitleCallback, callback)
                        }
                    } else {
                        val video = mirrorDoc.selectFirst("video[src]")
                        if (video != null) {
                            callback(
                                newExtractorLink("$name - $label", label, video.attr("src"), ExtractorLinkType.M3U8) {
                                    this.referer = mirrorUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        } else {
                            val scriptRegex = Regex("""(?:file|video_url|source|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4))["']""")
                            mirrorDoc.select("script").forEach { script ->
                                scriptRegex.find(script.html())?.let { match ->
                                    val videoUrl = match.groupValues[1]
                                    callback(
                                        newExtractorLink("$name - $label", label, videoUrl, ExtractorLinkType.M3U8) {
                                            this.referer = mirrorUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        // 3️⃣ Final regex fallback on main page
        val scriptRegex = Regex("""(?:file|video_url|source|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4))["']""")
        document.select("script").forEach { script ->
            scriptRegex.find(script.html())?.let { match ->
                val videoUrl = match.groupValues[1]
                callback(
                    newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
