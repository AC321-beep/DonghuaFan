package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ChikiAnimationProvider : MainAPI() {

    override var mainUrl = "https://chikianimation.com"
    override var name = "ChikiAnimation"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update"          to "Recently Updated",
        "anime/?status=&type=&order=popular"         to "Popular",
        "anime/?status=&type=&order=latest"          to "Latest Added",
        "anime/?status=&type=ai+animes&order=update" to "AI Anime",
        "anime/?status=ongoing&type=&order=update"   to "Ongoing",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=movie&order=update"     to "Movies",
        "anime/?status=&type=ona&order=update"       to "Donghua (ONA)"
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        
        val items = try {
            val document = app.get(url, headers = defaultHeaders).document
            document.select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return "$mainUrl/$base"
        return when {
            !base.contains("?") -> {
                val trimmed = base.trimEnd('/')
                "$mainUrl/$trimmed/page/$page/"
            }
            else -> "$mainUrl/$base&page=$page"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("div.bsx > a[href]") 
            ?: this.selectFirst("a[itemprop=url]") 
            ?: this.selectFirst("h2 a[href]") 
            ?: this.selectFirst("a[href]") 
            ?: return null

        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href.isBlank() || href.contains("/genres/") || href.contains("/bookmark") ||
            href.contains("/privacy") || href.contains("/contact") || href.contains("/dmca")
        ) return null

        var title = aTag.attr("title").trim()
        if (title.isBlank()) title = this.select(".tt, .tt h2, h2, h3, h4").text().trim()
        if (title.isBlank()) title = aTag.text().trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img.ts-post-image")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
                    .ifEmpty { img.attr("data-lazy-src") }
                    .ifEmpty { img.attr("data-original") }
            } ?: this.selectFirst("div.limit img")?.attr("src")
              ?: this.selectFirst("img")?.attr("src")
        )

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = query.trim()
        if (encoded.isBlank()) return emptyList()

        for (i in 1..3) {
            try {
                val url = if (i == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$i/?s=$encoded"
                val document = app.get(url, headers = defaultHeaders).document
                val results = document.select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                    .mapNotNull { it.toSearchResult() }
                
                if (!searchResponse.containsAll(results)) {
                    searchResponse.addAll(results)
                } else {
                    break
                }
                if (results.isEmpty()) break
            } catch (e: Exception) {
                break
            }
        }
        return searchResponse.distinctBy { it.url }
    }

    private fun getEpisodesElements(document: org.jsoup.nodes.Document): org.jsoup.select.Elements {
        var eps = document.select("table tbody tr:not(:has(th)), div.episodelist ul li, div.eplister ul li, ul.eplister li, .ep_list li, .episodelist li, .eplister li, #episodelist li, .lsteps li, .list1 li")
        if (eps.isEmpty()) {
            eps = document.select("div.episodelist a[href], div.eplister a[href], .ep_list a[href], .episodelist a[href], .eplister a[href], #episodelist a[href], .lsteps a[href], .list1 a[href]")
        }
        if (eps.isEmpty()) {
            eps = document.select("div.listupd article, div.bixbox article, div.related article")
        }
        return eps
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (e: Exception) {
            return null
        }

        val titleRaw = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
            
        val title = titleRaw.substringBefore(" Episode").substringBefore(" Movie").trim()

        var poster = document.selectFirst("div.ime > img")?.attr("data-src") ?: ""
        if (poster.isEmpty()) poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        if (poster.isEmpty()) poster = document.selectFirst("div.thumb img.wp-post-image")?.attr("src")?.trim() ?: ""

        val description = document.selectFirst("div.entry-content[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()
            ?: ""

        val genres = document.select("div.genxed a, span.genxed a, .spe .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val typeText = (document.selectFirst("div.typez, .spe, .infox .spe, div.anime-info .type")?.text()?.lowercase() ?: "") + " " + title.lowercase()
        val tvtag = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries

        var epElements = getEpisodesElements(document)
        
        if (epElements.isEmpty()) {
            val epPage = document.selectFirst(".episodelist li > a[href], .eplister li > a[href]")?.attr("href")?.trim()
            if (!epPage.isNullOrBlank()) {
                epElements = try {
                    getEpisodesElements(app.get(fixUrl(epPage), headers = defaultHeaders).document)
                } catch (e: Exception) {
                    org.jsoup.select.Elements()
                }
            }
        }

        return if (tvtag == TvType.TvSeries || epElements.size > 1) {
            val episodes = parseEpisodes(epElements, poster)
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            val href = epElements.firstOrNull()?.selectFirst("a[href]")?.attr("href") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }
    }

    private fun parseEpisodes(epElements: org.jsoup.select.Elements, fallbackPoster: String): List<Episode> {
        return epElements.mapNotNull { info ->
            val aTag = info.selectFirst("a[href]") ?: info.takeIf { it.tagName() == "a" && it.hasAttr("href") }
            val href1 = aTag?.attr("href") ?: return@mapNotNull null
            
            var rawTitle = info.selectFirst(".epl-title, .ep-title, .title, h2, h3")?.text()?.trim() ?: ""
            if (rawTitle.isEmpty()) rawTitle = aTag.text().trim() 
            if (rawTitle.isEmpty()) rawTitle = info.text().trim()
            
            var episodeNum: Int? = null
            var epName: String
            
            val fullText = info.text()
            val dateMatch = Regex("""([a-zA-Z]+\s+\d{1,2},\s+\d{4})""").find(fullText)?.value?.trim()
            val isFullMovie = rawTitle.contains("Full Movie", ignoreCase = true) || 
                              rawTitle.contains("Eps Full", ignoreCase = true) ||
                              Regex("""(?i)\bfull\b""").containsMatchIn(rawTitle)

            val trueEpMatch = Regex("""(?i)(?:Ep|Eps|Episode|Ep\.|Part|SP|Special)\s*(\d+(?:\s*[-~]\s*\d+)?)""").findAll(rawTitle).firstOrNull()
            val matchStr = trueEpMatch?.groupValues?.get(1)?.trim()
            
            if (isFullMovie && matchStr == null) {
                episodeNum = 0 
                epName = if (dateMatch != null) "Full Movie: $dateMatch" else "Full Movie"
            } else if (matchStr != null) {
                episodeNum = Regex("""\d+""").find(matchStr)?.value?.toIntOrNull()
                epName = if (dateMatch != null) "Episode $matchStr: $dateMatch" else "Episode $matchStr"
            } else {
                val rangeMatch = Regex("""\b(\d+[-~]\d+)\b""").find(rawTitle)
                if (rangeMatch != null) {
                    val rawRange = rangeMatch.groupValues[1]
                    episodeNum = Regex("""\d+""").find(rawRange)?.value?.toIntOrNull()
                    epName = if (dateMatch != null) "Episode $rawRange: $dateMatch" else "Episode $rawRange"
                } else {
                    val numbers = Regex("""\d+""").findAll(rawTitle).map { it.value }.toList()
                    episodeNum = numbers.lastOrNull { num ->
                        num != "4" && num != "1080" && num != "720" && num != "2160" && !(num.length == 4 && num.startsWith("20"))
                    }?.toIntOrNull()

                    epName = if (episodeNum != null) {
                        if (dateMatch != null) "Episode $episodeNum: $dateMatch" else "Episode $episodeNum"
                    } else {
                        if (dateMatch != null) "Episode: $dateMatch" else "Episode"
                    }
                }
            }
            
            val posterr = info.selectFirst("img")?.let { 
                it.attr("data-src").takeIf { src -> src.isNotBlank() } ?: it.attr("src")
            } ?: fallbackPoster
            
            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim() 

            newEpisode(href1) {
                this.name = epName
                this.episode = episodeNum
                this.posterUrl = posterr
                
                if (!dateText.isNullOrBlank()) { 
                    this.addDate(dateText, format = "MMMM d, yyyy")
                    this.description = dateText
                }
            }
        }.reversed()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, headers = defaultHeaders).document
        } catch (e: Exception) {
            return false
        }

        var found = false

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = try { fixUrl(iframeUrl) } catch (e: Exception) { return }
            var extReferer = iframeUrl 

            if (!finalUrl.startsWith("http")) return
            if (finalUrl.contains("youtube", true) || finalUrl.contains("disqus", true) || finalUrl.contains("googlesyndication", true)) return

            // Native Dailymotion Bypass
            if (finalUrl.contains("dailymotion", ignoreCase = true) || finalUrl.contains("dai.ly", ignoreCase = true)) {
                val videoIdMatch = Regex("""(?:dailymotion\.com/(?:embed/)?video/|geo\.dailymotion\.com/(?:player/[^/]+/video/|player\.html\?video=)|dai\.ly/)([a-zA-Z0-9_-]+)""").find(finalUrl)
                if (videoIdMatch != null) {
                    finalUrl = "https://www.dailymotion.com/video/${videoIdMatch.groupValues[1]}"
                    extReferer = mainUrl
                }
            }

            when {
                "ghbrisk.com" in finalUrl -> {
                    Ghbrisk().getUrl(finalUrl, extReferer, subtitleCallback, callback)
                    found = true
                }
                "galaxydonghua" in finalUrl -> {
                    GalaxyDonghua().getUrl(finalUrl, extReferer, subtitleCallback, callback)
                    found = true
                }
                finalUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(label, label, finalUrl, INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(label)
                        }
                    )
                    found = true
                }
                else -> {
                    // Passes the rebuilt finalUrl and overridden extReferer directly into CloudStream's inbuilt extractors
                    if (loadExtractor(finalUrl, referer = extReferer, subtitleCallback, callback)) {
                        found = true
                    } else {
                        try {
                            val html = app.get(finalUrl, headers = mapOf("Referer" to extReferer)).text
                            val streamRegex = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*)""")
                            
                            streamRegex.findAll(html).forEach { m ->
                                val fileUrl = m.groupValues[1].replace("\\/", "/")
                                if (fileUrl.contains(".m3u8", ignoreCase = true)) {
                                    M3u8Helper.generateM3u8(label, fileUrl, finalUrl).forEach { callback.invoke(it) }
                                    found = true
                                } else if (fileUrl.contains(".mp4", ignoreCase = true)) {
                                    // FIXED: Deprecated ExtractorLink constructor replaced with newExtractorLink
                                    callback.invoke(
                                        newExtractorLink(
                                            source = label,
                                            name = label,
                                            url = fileUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = finalUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    found = true
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
        }

        fun getIframeSrc(iframe: Element): String {
            return iframe.attr("src").ifBlank { iframe.attr("data-src").ifBlank { iframe.attr("data-litespeed-src") } }
        }

        val options = document.select("select.mirror option, .mobius option, select#mirror option, select[name=mirror] option, option[data-index]")
        
        coroutineScope {
            options.map { option ->
                async {
                    val base64 = option.attr("value").trim()
                    if (base64.isBlank()) return@async
                    val label = option.text().trim()

                    if (base64.startsWith("http") || base64.startsWith("//")) {
                        invokeExtractor(base64, label)
                        return@async
                    }

                    val decodedHtml = try {
                        String(Base64.decode(base64, Base64.DEFAULT))
                    } catch (e: Exception) {
                        try { String(Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP)) } catch (e2: Exception) { null }
                    }
                    if (decodedHtml.isNullOrBlank()) return@async

                    try {
                        Jsoup.parse(decodedHtml).select("iframe").forEach { iframe ->
                            val src = getIframeSrc(iframe)
                            if (src.isNotBlank()) invokeExtractor(src, label)
                        }
                    } catch (e: Exception) { }

                    Regex("""https?://[^\s"'<>\\)]+""").findAll(decodedHtml).forEach { m -> invokeExtractor(m.value, label) }
                }
            }.awaitAll()
        }

        if (options.isEmpty() || !found) {
            document.select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) invokeExtractor(src, "Server")
            }
        }

        if (!found) {
            document.select("script").forEach { script ->
                val body = script.data()

                Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""").findAll(body).forEach { m -> invokeExtractor(m.value, "Server") }

                Regex("""['"]([A-Za-z0-9+/=_-]{60,})['"]""").findAll(body).forEach { m ->
                    val blob = m.groupValues[1]
                    val decoded = try { String(Base64.decode(blob, Base64.DEFAULT)) } catch (e: Exception) { null } ?: return@forEach
                    try {
                        Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                            val src = getIframeSrc(iframe)
                            if (src.isNotBlank()) invokeExtractor(src, "Server")
                        }
                    } catch (e: Exception) { }
                }
            }
        }

        return found
    }
}
