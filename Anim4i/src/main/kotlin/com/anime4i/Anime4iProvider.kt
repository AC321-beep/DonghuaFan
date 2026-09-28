package com.lagradost.cloudstream3.animeproviders

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
        val document = app.get("$mainUrl${request.data}$page").document
        
        // 1. Dynamic Tab Parsing for the Root Homepage
        if (request.data == "/") {
            val home = ArrayList<HomePageList>()

            // Extract the Category Tabs (Adventure, Drama, Full CGI, etc.)
            document.select(".series-gen .nav-tabs li a").forEach { tab ->
                val tabName = tab.text()
                val tabId = tab.attr("href")

                val tabItems = document.select("$tabId article.bs").mapNotNull {
                    it.toSearchResult()
                }

                if (tabItems.isNotEmpty()) {
                    home.add(HomePageList(tabName, tabItems))
                }
            }
            
            // Extract the Trending Section
            val trendingItems = document.select(".wpop-weekly article.bs").mapNotNull { it.toSearchResult() }
            if (trendingItems.isNotEmpty()) {
                home.add(HomePageList("Trending This Week", trendingItems))
            }

            return HomePageResponse(home)
        } 
        
        // 2. Standard Parsing for paginated categories
        val items = document.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url).document

        return document.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title")
        val href = a.attr("href") ?: return null
        val posterUrl = a.selectFirst("img")?.attr("src")
        
        val epString = a.selectFirst(".epx")?.text()?.replace(Regex("[^0-9]"), "")
        val epNum = epString?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".infox h1")?.text() ?: return null
        val poster = document.selectFirst(".thumb img")?.attr("src")
        val description = document.selectFirst(".entry-content, .infox .desc")?.text()
        
        val isEpisodePage = url.contains("-episode-")
        
        if (isEpisodePage) {
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                addEpisodes(Episode(url, name = title))
            }
        }

        val episodes = document.select(".eplister ul li a").mapNotNull { ep ->
            val epHref = ep.attr("href") ?: return@mapNotNull null
            val epName = ep.selectFirst(".epl-num")?.text() ?: ep.selectFirst(".epl-title")?.text() ?: "Episode"
            Episode(epHref, name = epName)
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val iframeUrls = document.select("iframe").mapNotNull { it.attr("src") }
        var handled = false
        
        iframeUrls.forEach { iframeUrl ->
            if (iframeUrl.contains("ok.ru")) {
                // Route explicitly to your custom Ok.ru extractor
                OkRuCustom().getUrl(iframeUrl, data, subtitleCallback, callback)
                handled = true
            } else if (iframeUrl.contains("dailymotion.com")) {
                // Route to CloudStream's native Dailymotion extractor
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
                handled = true
            }
        }

        return handled
    }
}

// ---------------------------------------------------------
// CUSTOM EXTRACTORS (Included in the same file for ease)
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

            // 1. MP4 first
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

            // 2. HLS → 3. DASH fallback
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
        } catch (e: Exception) {
            // Fails silently
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
