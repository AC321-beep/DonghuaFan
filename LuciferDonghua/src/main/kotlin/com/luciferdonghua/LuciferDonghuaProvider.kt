package com.luciferdonghua

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "Lucifer Donghua"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=movie&sub=" to "Movies",
        "$mainUrl/network/tencent/" to "Tencent Anime",
        "$mainUrl/network/youku/" to "YouKu Anime",
        "$mainUrl/anime/?status=completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = defaultHeaders).document
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
        val document = app.get(url, headers = defaultHeaders).document
        val results = document.select("article.bs").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

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
            val epName = linkElement.selectFirst(".epl-num, .epnum")?.text()?.trim() ?: ep.text().trim()
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
        var anyStreamFound = false
        val baseDocument = try { app.get(data, headers = defaultHeaders).document } catch(e: Exception) { return false }
        
        // 1️⃣ Extract default active iframe on the page (usually Rumble or Dailymotion)
        baseDocument.select(".player-area iframe, .playcon iframe, iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            val clean = fixUrlNull(src)
            if (clean != null && clean.isNotBlank() && !clean.contains("about:blank")) {
                if (loadExtractor(clean, data, subtitleCallback, callback)) {
                    anyStreamFound = true
                }
            }
        }

        // 2️⃣ Base64 Encoded Mirrors (AnimeStream Standard)
        baseDocument.select("select.mirror option, .server_option li, ul#playeroptionsul li").forEach { option ->
            val value = option.attr("data-video").takeIf { it.isNotBlank() }
                ?: option.attr("value").takeIf { it.isNotBlank() }
                ?: return@forEach
                
            if (value.contains("Choose", ignoreCase = true) || value.isBlank()) return@forEach

            // Attempt to Base64 decode the hidden iframe
            val decoded = try {
                if (value.matches(Regex("^[A-Za-z0-9+/=]+$")) && value.length % 4 == 0) {
                    String(Base64.decode(value, Base64.DEFAULT))
                } else value
            } catch (e: Exception) { value }

            // Extract the actual URL from the decoded HTML iframe
            val iframeUrl = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
            var clean = fixUrlNull(if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl)

            if (clean != null && clean.startsWith("http")) {
                if (loadExtractor(clean, data, subtitleCallback, callback)) {
                    anyStreamFound = true
                }
            }
        }

        // 3️⃣ AJAX Mirrors (AnimeStream Fallback Method)
        baseDocument.select("ul#playeroptionsul li").forEach { li ->
            val post = li.attr("data-post")
            val nume = li.attr("data-nume")
            val type = li.attr("data-type")
            if (post.isNotBlank() && nume.isNotBlank()) {
                try {
                    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                    val response = app.post(
                        ajaxUrl,
                        data = mapOf("action" to "player_ajax", "post" to post, "nume" to nume, "type" to type),
                        headers = defaultHeaders + mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text
                    
                    val iframeSrc = Jsoup.parse(response).selectFirst("iframe")?.attr("src")
                        ?: Regex("""src=\\?["']([^"']+)""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
                        
                    val clean = fixUrlNull(if (iframeSrc?.startsWith("//") == true) "https:$iframeSrc" else iframeSrc)
                    if (clean != null && clean.startsWith("http")) {
                        if (loadExtractor(clean, data, subtitleCallback, callback)) {
                            anyStreamFound = true
                        }
                    }
                } catch (e: Exception) {
                    // Ignore AJAX failures
                }
            }
        }

        // 4️⃣ Final regex fallback on main page scripts
        if (!anyStreamFound) {
            val scriptRegex = Regex("""(?:file|video_url|source|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4))["']""")
            baseDocument.select("script").forEach { script ->
                scriptRegex.find(script.html())?.let { match ->
                    val videoUrl = match.groupValues[1]
                    callback(
                        newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    anyStreamFound = true
                }
            }
        }

        return anyStreamFound
    }
}
