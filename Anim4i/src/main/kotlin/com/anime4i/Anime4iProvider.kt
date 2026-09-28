package com.anime4i

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Anime4iProvider : MainAPI() {
    override var mainUrl = "https://anime4i.com"
    override var name = "Anime4i"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/" to "Home",
        "/anime/?status=&type=&order=update&page=" to "Latest Releases",
        "/anime/?status=&type=&order=popular&page=" to "Popular Today"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = when {
            request.data == "/" -> {
                if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
            }
            request.data.endsWith("page=") -> "$mainUrl${request.data}$page"
            else -> "$mainUrl${request.data}$page"
        }

        val document = app.get(url).document

        // 1. Dynamic Tab & Multi-Row Parsing on Root Homepage (Page 1)
        if (request.data == "/" && page == 1) {
            val home = ArrayList<HomePageList>()

            // Popular Today Row
            val popularToday = document.select(".popularslider article.bs").mapNotNull { it.toSearchResult() }
            if (popularToday.isNotEmpty()) {
                home.add(HomePageList("Popular Today", popularToday))
            }

            // Category Tabs (Adventure, Drama, Full CGI, Martial Arts, Urban Fantasy)
            document.select(".series-gen .nav-tabs li a").forEach { tab ->
                val tabName = tab.text().trim()
                val tabId = tab.attr("href").trim() // e.g. #series-750

                if (tabId.startsWith("#")) {
                    val tabItems = document.select("$tabId article.bs").mapNotNull {
                        it.toSearchResult()
                    }
                    if (tabItems.isNotEmpty()) {
                        home.add(HomePageList(tabName, tabItems))
                    }
                }
            }

            // Trending This Week Row
            val trendingItems = document.select(".wpop-weekly article.bs").mapNotNull { it.toSearchResult() }
            if (trendingItems.isNotEmpty()) {
                home.add(HomePageList("Trending This Week", trendingItems))
            }

            // Latest Releases Row
            val latestItems = document.select(".latesthome ~ .listupd article.bs").mapNotNull { it.toSearchResult() }
            if (latestItems.isNotEmpty()) {
                home.add(HomePageList("Latest Release", latestItems))
            }

            // FIX: Using builder instead of constructor
            return newHomePageResponse(home)
        }

        // 2. Standard Paginated Parsing
        val items = document.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(url).document

        return document.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").ifEmpty { this.selectFirst(".tt")?.text() } ?: return null
        val href = a.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        val epString = this.selectFirst(".epx")?.text()?.replace(Regex("[^0-9]"), "")
        val epNum = epString?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        var document = app.get(url).document

        // If the clicked link is a specific episode, try to resolve the parent series page
        if (url.contains("-episode-")) {
            val seriesHref = document.selectFirst(
                ".allep a, .ts-breadcrumb li:nth-child(2) a, a.series, .naveps .nve a[href*='/anime/']"
            )?.attr("href")

            if (!seriesHref.isNullOrBlank() && seriesHref != url) {
                document = app.get(seriesHref).document
            }
        }

        val title = document.selectFirst(".infox h1, h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst(".thumb img, .infox .thumb img")?.attr("src")
        val description = document.selectFirst(".entry-content, .infox .desc, .mindes")?.text()

        // Scrape episodes from the episode list container
        val episodes = document.select(".eplister ul li a").mapNotNull { ep ->
            val epHref = ep.attr("href") ?: return@mapNotNull null
            val epName = ep.selectFirst(".epl-num")?.text()
                ?: ep.selectFirst(".epl-title")?.text()
                ?: "Episode"
            
            // FIX: Using newEpisode builder
            newEpisode(epHref) {
                this.name = epName
            }
        }.reversed()

        // Fallback: If no episode list was found, use the current page as a single episode
        val finalEpisodes = episodes.ifEmpty {
            listOf(
                newEpisode(url) { this.name = title } // FIX: Using newEpisode builder
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            // FIX: Explicitly providing DubStatus.Subbed before the episode list
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val foundUrls = LinkedHashSet<String>()

        // 1. Direct iframes in video containers
        document.select("#pembed iframe, .player-embed iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) foundUrls.add(src)
        }

        // 2. Embedded server selects/options (decoding base64 iframes if present)
        document.select("select.mirror option, .mirror li, select[name='server'] option").forEach { option ->
            val raw = option.attr("value").ifEmpty { option.attr("data-embed") }.ifEmpty { option.attr("data-src") }
            decodeEmbedUrl(raw)?.let { foundUrls.add(it) }
        }

        var handled = false
        foundUrls.forEach { targetUrl ->
            val cleanUrl = if (targetUrl.startsWith("//")) "https:$targetUrl" else targetUrl

            when {
                cleanUrl.contains("ok.ru") -> {
                    OkRuCustom().getUrl(cleanUrl, data, subtitleCallback, callback)
                    handled = true
                }
                cleanUrl.contains("dailymotion.com") -> {
                    loadExtractor(cleanUrl, data, subtitleCallback, callback)
                    handled = true
                }
                else -> {
                    loadExtractor(cleanUrl, data, subtitleCallback, callback)
                    handled = true
                }
            }
        }

        return handled
    }

    private fun decodeEmbedUrl(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("//")) {
            return raw
        }
        return try {
            val decoded = String(Base64.decode(raw, Base64.DEFAULT))
            Regex("""src=["'](https?://[^"']+|//[^"']+)["']""").find(decoded)?.groupValues?.get(1)
                ?: if (decoded.startsWith("http")) decoded else null
        } catch (_: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------
// CUSTOM EXTRACTORS
// ---------------------------------------------------------

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    companion object {
        private val RE_VIDEO_ID = Regex("""/video(?:embed)?/(\d+)""")
        private val RE_MID_PARAM = Regex("""[?&]mid=(\d+)""")

        private val QUALITY_MAP = mapOf(
            "mobile" to Qualities.P144.value,
            "lowest" to Qualities.P240.value,
            "low"    to Qualities.P360.value,
            "sd"     to Qualities.P480.value,
            "hd"     to Qualities.P720.value,
            "full"   to Qualities.P1080.value,
            "quad"   to Qualities.P1440.value,
            "ultra"  to Qualities.P2160.value,
        )
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val id = RE_VIDEO_ID.find(url)?.groupValues?.get(1)
                ?: RE_MID_PARAM.find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")

            if (id.isBlank() || !id.all { it.isDigit() }) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val json = app.post(apiUrl).parsedSafe<OkRuResponse>() ?: return

            // 1. Direct MP4 links
            json.videos?.forEach { video ->
                val qName = video.name?.lowercase().orEmpty()
                val vidUrl = video.url.orEmpty()
                if (vidUrl.isBlank() || vidUrl.contains("usr_login")) return@forEach

                val qualityValue = QUALITY_MAP[qName] ?: Qualities.Unknown.value
                val displayLabel = if (qualityValue != Qualities.Unknown.value) "MP4 ${qualityValue}p" else "MP4 $qName"

                callback(
                    newExtractorLink(
                        name = "${this.name} MP4",
                        source = "${this.name} $displayLabel",
                        url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"),
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    }
                )
            }

            // 2. HLS -> DASH Fallback
            val hlsUrl = json.hlsManifestUrl.orEmpty()
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8(
                    "$name HLS",
                    hlsUrl.replace("\\u0026", "&").replace("\\/", "/"),
                    url
                ).forEach(callback)
            } else {
                val dashUrl = json.dashManifestUrl.orEmpty()
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(
                        newExtractorLink(
                            name = "$name DASH",
                            source = "$name DASH",
                            url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"),
                            type = ExtractorLinkType.DASH
                        ) { this.referer = "https://ok.ru/" }
                    )
                }
            }
        } catch (_: Exception) {
            // Fail silently
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuResponse(
        val videos: List<OkRuVideo>? = null,
        val hlsManifestUrl: String? = null,
        val dashManifestUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuVideo(
        val name: String? = null,
        val url: String? = null,
    )
}
