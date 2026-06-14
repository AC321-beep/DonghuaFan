package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

open class DonghuastreamProvider : MainAPI() {
    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}$page").document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title")
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("div.bsx a img")?.getImageAttr())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("data-src")
            this.hasAttr("src") -> this.attr("src")
            else -> this.attr("src")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("${mainUrl}/pagg/$i/?s=$query").document
            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
        var poster = document.select("div.ime > img").attr("data-src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().toString()
        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val epPage = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
            val doc = app.get(epPage).document
            val episodes = doc.select("div.episodelist > ul > li").map { info ->
                val href1 = info.select("a").attr("href")
                val episode = info.select("a span").text().substringAfter("-").substringBeforeLast("-")
                val posterr = info.selectFirst("a img")?.attr("data-src") ?: ""
                newEpisode(href1) {
                    this.name = episode.replace(title, "", ignoreCase = true)
                    this.episode = episode.toIntOrNull()
                    this.posterUrl = posterr
                }
            }
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            if (poster.isEmpty()) {
                poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).document
        val options = html.select("option[data-index]")

        for (option in options) {
            val base64 = option.attr("value")
            if (base64.isBlank()) continue
            val label = option.text().trim()
            val decodedHtml = try {
                base64Decode(base64)
            } catch (_: Exception) {
                Log.w("Error", "Base64 decode failed: $base64")
                continue
            }

            val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")?.let(::httpsify)
            if (iframeUrl.isNullOrEmpty()) continue

            // ----- Handle Dailymotion (any label containing "dailymotion") -----
            if (label.contains("dailymotion", ignoreCase = true) || "dailymotion" in iframeUrl) {
                var token: String? = Regex("""[?&]video=([^&]+)""").find(iframeUrl)?.groupValues?.get(1)
                if (token == null) {
                    // Fetch the iframe page and parse player_aaaa
                    val pageHtml = try {
                        app.get(iframeUrl, referer = data).text
                    } catch (e: Exception) { null }
                    if (pageHtml != null) {
                        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                            .find(pageHtml)?.groupValues?.get(1)
                        if (playerJson != null) {
                            var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
                            val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                            else if (encrypt == 2) {
                                rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                                rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                            }
                            if (from.equals("dailymotion", ignoreCase = true)) {
                                token = rawUrl
                            }
                        }
                    }
                }
                if (token != null) {
                    val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$token"
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        continue
                    }
                }
                // fall through to generic extractor if token extraction failed
            }

            // ----- Known extractors -----
            when {
                "rumble.com" in iframeUrl -> {
                    Rumble().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
                }
                "play.streamplay.co.in" in iframeUrl -> {
                    PlayStreamplay().getUrl(iframeUrl, iframeUrl, subtitleCallback, callback)
                }
                "vidmoly" in iframeUrl -> {
                    val cleanedUrl = "http:" + iframeUrl.substringAfter("=\"").substringBefore("\"")
                    loadExtractor(cleanedUrl, referer = iframeUrl, subtitleCallback, callback)
                }
                iframeUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(label, label, iframeUrl, INFER_TYPE) {
                            this.referer = ""
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
                else -> {
                    loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
