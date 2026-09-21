package com.donghuastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

open class DonghuastreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "DonghuaStream"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    // PRE-COMPILED REGEXES (Huge performance boost over compiling inside loops)
    private val junkRegex = Regex("(?i)(English Sub|Multiple Subtitles|Subtitles|Good Sub|Download Link|Download Linl|\\(4K\\)|\\[4K\\]|\\(1080p\\)|\\[1080p\\]|4K|1080p|720p|Full Movie|Eps Full|Movie)")
    private val epJunkRegex = Regex("(?i)(Episode|Ep\\.?|Part|SP|Special)\\s*\\d+.*")
    private val nonAlphaNumericRegex = Regex("[^a-zA-Z0-9]")
    private val dateRegex = Regex("""([a-zA-Z]+\s+\d{1,2},\s+\d{4})""")
    private val fullMovieRegex = Regex("""(?i)\bfull\b""")
    private val epNumRegex = Regex("""(?i)(?:Ep|Eps|Episode|Ep\.|Part|SP|Special)\s*(\d+(?:\s*[-~]\s*\d+)?)""")
    private val rangeRegex = Regex("""\b(\d+[-~]\d+)\b""")
    private val digitsRegex = Regex("""\d+""")
    private val videoIdRegex = Regex("""[?&]video=([a-zA-Z0-9_-]+)""")

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "special_edition" to "Special Edition"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Special Edition") {
            val combinedResults = coroutineScope {
                val movieDeferred = async { app.get("$mainUrl${if (page == 1) "" else "/pagg/$page"}/?s=movie", cacheTime = 0).document }
                val specialDeferred = async { app.get("$mainUrl${if (page == 1) "" else "/pagg/$page"}/?s=special", cacheTime = 0).document }

                val movieResults = movieDeferred.await().select("div.listupd > article").mapNotNull { it.toSearchResult() }
                val specialResults = specialDeferred.await().select("div.listupd > article").mapNotNull { it.toSearchResult() }

                (movieResults + specialResults).distinctBy { it.name.trim().lowercase() }
            }

            return newHomePageResponse(
                list = HomePageList(request.name, combinedResults, isHorizontalImages = false),
                hasNext = combinedResults.isNotEmpty()
            )
        }

        val home = if (page == 1) {
            coroutineScope {
                val headers = defaultHeaders + mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")
                val homeDocDeferred = async { app.get("$mainUrl/", headers = headers, cacheTime = 0).document }
                val dirDocDeferred = async { app.get("$mainUrl/${request.data}1", headers = headers, cacheTime = 0).document }

                val homeDoc = homeDocDeferred.await()
                val dirDoc = dirDocDeferred.await()

                val containers = homeDoc.select(".bixbox, .releases")
                val latestContainer = containers.find {
                    val headerTitle = it.selectFirst("h2, h3, .moxhead, .sec-title")?.text() ?: ""
                    headerTitle.contains("Latest", ignoreCase = true) || headerTitle.contains("Recent", ignoreCase = true)
                } ?: homeDoc.selectFirst(".releases.latesthome") ?: containers.lastOrNull()

                val homeArticles = latestContainer?.select("article")?.mapNotNull { it.toSearchResult() } ?: emptyList()
                val dirArticles = dirDoc.select("div.listupd > article").mapNotNull { it.toSearchResult() }

                // Clean chained regex replacement 
                (homeArticles + dirArticles).distinctBy {
                    it.name.replace(junkRegex, "")
                        .replace(epJunkRegex, "")
                        .replace(nonAlphaNumericRegex, "")
                        .lowercase()
                }
            }
        } else {
            app.get("$mainUrl/${request.data}$page", headers = defaultHeaders, cacheTime = 0)
                .document.select("div.listupd > article")
                .mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = true // Assuming continuous pagination
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("div.bsx > a, a[href]") ?: return null
        
        val title = aTag.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt, .tt h2, h2, h3, h4")?.text()?.takeIf { it.isNotBlank() }
            ?: aTag.text().takeIf { it.isNotBlank() } ?: return null

        val href = fixUrlNull(aTag.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.getImageAttr())

        return newMovieSearchResponse(title.trim(), href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // Simplified fallback chain using takeIf
    private fun Element.getImageAttr(): String? {
        return attr("data-src").takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("src")
    }

    // Parallelized search execution
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        (1..3).map { page ->
            async {
                try {
                    val url = "$mainUrl/pagg/$page/?s=$query"
                    app.get(url).document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    // Simplified element retrieval with takeIf chains
    private fun getEpisodesElements(document: Document): Elements {
        return document.select("table tbody tr:not(:has(th)), div.episodelist ul li, div.eplister ul li, ul.eplister li, .ep_list li, .episodelist li, .eplister li, #episodelist li, .lsteps li, .list1 li").takeIf { it.isNotEmpty() }
            ?: document.select("div.episodelist a[href], div.eplister a[href], .ep_list a[href], .episodelist a[href], .eplister a[href], #episodelist a[href], .lsteps a[href], .list1 a[href]").takeIf { it.isNotEmpty() }
            ?: document.select("div.listupd article, div.bixbox article, div.related article")
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val isEpisodePage = document.selectFirst(".infox, .tsinfo, .anime-info") == null

        if (isEpisodePage) {
            var seriesUrl = document.select("div.ts-breadcrumb a").toList().findLast {
                val h = it.attr("href")
                h.isNotBlank() && h != mainUrl && h != "$mainUrl/" && !h.endsWith("/anime/") && !h.endsWith("/movie/") && !h.endsWith("/series/")
            }?.attr("href") ?: document.selectFirst(".naveps a:contains(All)")?.attr("href")

            if (!seriesUrl.isNullOrBlank() && fixUrl(seriesUrl) != url) {
                return load(fixUrl(seriesUrl))
            }

            val titleRaw = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
            val title = titleRaw.substringBefore(" Episode").substringBefore(" Movie").trim()
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            val epElements = getEpisodesElements(document)

            if (epElements.isNotEmpty()) {
                val episodes = parseEpisodes(epElements)
                return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                }
            }

            return newMovieLoadResponse(titleRaw, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.ime > img")?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text() ?: ""
        val tvtag = if (type.contains("Movie", ignoreCase = true)) TvType.Movie else TvType.TvSeries

        val epElements = getEpisodesElements(document)

        return if (tvtag == TvType.TvSeries || epElements.size > 1) {
            val episodes = parseEpisodes(epElements)
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val href = epElements.firstOrNull()?.selectFirst("a[href]")?.attr("href") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private fun parseEpisodes(epElements: Elements): List<Episode> {
        return epElements.mapNotNull { info ->
            val aTag = info.selectFirst("a[href]") ?: info.takeIf { it.tagName() == "a" && it.hasAttr("href") }
            val href1 = aTag?.attr("href") ?: return@mapNotNull null

            val rawTitle = info.selectFirst(".epl-title, .ep-title, .title, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: aTag.text().trim().takeIf { it.isNotBlank() }
                ?: info.text().trim()

            val fullText = info.text()
            val dateMatch = dateRegex.find(fullText)?.value?.trim()
            val isFullMovie = rawTitle.contains("Full Movie", ignoreCase = true) ||
                              rawTitle.contains("Eps Full", ignoreCase = true) ||
                              fullMovieRegex.containsMatchIn(rawTitle)

            val trueEpMatch = epNumRegex.findAll(rawTitle).firstOrNull()
            val matchStr = trueEpMatch?.groupValues?.get(1)?.trim()

            var episodeNum: Int? = null
            val epName: String

            if (isFullMovie && matchStr == null) {
                episodeNum = 0
                epName = if (dateMatch != null) "Full Movie: $dateMatch" else "Full Movie"
            } else if (matchStr != null) {
                episodeNum = digitsRegex.find(matchStr)?.value?.toIntOrNull()
                epName = if (dateMatch != null) "Episode $matchStr: $dateMatch" else "Episode $matchStr"
            } else {
                val rangeMatch = rangeRegex.find(rawTitle)
                if (rangeMatch != null) {
                    val rawRange = rangeMatch.groupValues[1]
                    episodeNum = digitsRegex.find(rawRange)?.value?.toIntOrNull()
                    epName = if (dateMatch != null) "Episode $rawRange: $dateMatch" else "Episode $rawRange"
                } else {
                    val numbers = digitsRegex.findAll(rawTitle).map { it.value }.toList()
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

            val posterr = info.selectFirst("img")?.getImageAttr() ?: ""
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
        val doc = app.get(data, headers = defaultHeaders).document
        val options = doc.select("option[data-index]")

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = iframeUrl
            var extReferer = iframeUrl

            if (!finalUrl.contains("ok.ru", ignoreCase = true) && finalUrl.contains("dailymotion", ignoreCase = true)) {
                val videoIdMatch = videoIdRegex.find(finalUrl)
                if (videoIdMatch != null) {
                    finalUrl = "https://www.dailymotion.com/video/${videoIdMatch.groupValues[1]}"
                    extReferer = mainUrl
                }
            }

            when {
                "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> OkRuCustom().getUrl(finalUrl, extReferer, subtitleCallback, callback)
                "rumble.com" in finalUrl -> Rumble().getUrl(finalUrl, finalUrl, subtitleCallback, callback)
                "play.streamplay.co.in" in finalUrl -> PlayStreamplay().getUrl(finalUrl, finalUrl, subtitleCallback, callback)
                finalUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(label, label, finalUrl, INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
                else -> loadExtractor(finalUrl, referer = extReferer, subtitleCallback, callback)
            }
        }

        for (option in options) {
            val base64 = option.attr("value")
            if (base64.isBlank()) continue
            val label = option.text().trim()
            val decodedHtml = try {
                base64Decode(base64)
            } catch (_: Exception) {
                continue
            }

            Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")?.let(::httpsify)?.let {
                invokeExtractor(it, label)
            }
        }

        if (options.isEmpty()) {
            doc.selectFirst(".player-area iframe, .playcon iframe")?.attr("src")?.let(::httpsify)?.let {
                invokeExtractor(it, "Server")
            }
        }

        return true
    }
}
