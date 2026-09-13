package com.animekhor

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AnimekhorProvider : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&type=&order=update" to "Recently Updated",
        "anime/?type=comic&order=update" to "Comic Recently Updated",
        "anime/?type=comic" to "Comic Series",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?status=&type=ona" to "Donghua Series",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home = document.select("div.listupd > article, div.bsx").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val title = linkElement.attr("title").ifEmpty { this.selectFirst(".tt")?.text() } ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img -> img.attr("data-src").ifEmpty { img.attr("src") }.ifEmpty { img.attr("data-lazy-src") } }
        )
        
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        val document = app.get("$mainUrl/page/$page/?s=$query").document
                        document.select("div.listupd > article, div.bsx").mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text()
        val tvtag = if (type?.contains("Movie", ignoreCase = true) == true) TvType.Movie else TvType.TvSeries

        if (tvtag == TvType.Movie) {
            val href = document.selectFirst(".eplister li > a, .episodelist li > a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
                this.posterHeaders = mapOf("Referer" to mainUrl)
            }
        } else {
            var epListElements = document.select(".episodelist li, .eplister li")
            if (epListElements.isEmpty()) {
                val epPage = document.selectFirst(".episodelist li > a, .eplister li > a")?.attr("href") ?: ""
                if (epPage.isNotBlank()) {
                    val doc = app.get(epPage).document
                    epListElements = doc.select(".episodelist li, .eplister li")
                }
            }

            val episodes = epListElements.mapNotNull { info ->
                val href = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val episodeText = info.selectFirst(".epl-title")?.text() ?: info.selectFirst("a span")?.text() ?: ""
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim() 
                val parsedEpisode = if (episodeText.contains("-")) episodeText.substringAfter("-").substringBeforeLast("-").trim() else episodeText.trim()
                
                newEpisode(href) {
                    this.name = parsedEpisode.takeIf { it.isNotEmpty() } ?: episodeText
                    this.posterUrl = poster
                    this.posterHeaders = mapOf("Referer" to mainUrl)
                    if (!dateText.isNullOrBlank()) { 
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.distinctBy { it.data }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.posterHeaders = mapOf("Referer" to mainUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val extractedUrls = mutableSetOf<String>()

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = iframeUrl.trim()
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            if (finalUrl.contains("ok.ru") || finalUrl.contains("odnoklassniki.ru")) {
                val okId = Regex("""/video(?:embed)?/(\d+)""").find(finalUrl)?.groupValues?.get(1) ?: finalUrl.substringAfterLast("/")
                finalUrl = "https://ok.ru/videoembed/$okId"
            }

            if (!extractedUrls.add(finalUrl)) return

            // ---> TRACER INJECTION <---
            Log.d("AnimeKhorTracer", "Provider routing URL to extractors: $finalUrl | Label: $label")

            try { loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, callback) } catch (e: Exception) { Log.e("AnimeKhor", "Native extraction failed: ${e.message}") }

            try {
                when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> OkRuCustom().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "p2pstream" in finalUrl -> P2pstream().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "upns.live" in finalUrl -> UpnsLive().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "emturbovid" in finalUrl -> Emturbovid().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "bysekoze.com" in finalUrl -> Bysekoze().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "abyssplayer.com" in finalUrl -> AbyssPlayer().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "rumble.com" in finalUrl -> Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) { Log.e("AnimeKhor", "Custom extraction failed: ${e.message}") }
        }

        // STRATEGY 1: NUCLEAR RAW HTML SCAN
        val rawHtml = document.html()
        val globalUrlRegex = Regex("""https?://(?:www\.)?(?:ok\.ru|odnoklassniki\.ru|abyssplayer\.com|emturbovid\.com|p2pstream\.vip|upns\.live|bysekoze\.com)[^"'\s<>]+""")
        globalUrlRegex.findAll(rawHtml).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            invokeExtractor(cleanUrl, "Raw HTML Scan")
        }

        // STRATEGY 2: BASE64 AND SELECTOR PARSING
        val serverElements = document.select(".mobius option, select.mirror option, .server-list li a[data-embed], .server-list li a[data-em]")
        coroutineScope {
            serverElements.map { server ->
                async {
                    val rawData = server.attr("value").ifBlank { server.attr("data-em").ifBlank { server.attr("data-embed") } }.trim()
                    if (rawData.isNotBlank()) {
                        var iframeSrc = ""
                        if (rawData.startsWith("http") || rawData.startsWith("//")) {
                            iframeSrc = rawData
                        } else if (rawData.startsWith("<iframe", ignoreCase = true)) {
                            iframeSrc = Jsoup.parse(rawData).selectFirst("iframe")?.attr("src") ?: ""
                        } else {
                            try {
                                val decoded = String(Base64.decode(rawData, Base64.NO_WRAP))
                                iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded else decoded
                            } catch (e: Exception) {}
                        }
                        if (iframeSrc.isNotBlank()) invokeExtractor(iframeSrc, server.text().trim())
                    }
                }
            }.awaitAll()
        }

        // STRATEGY 3: STRICT AJAX INTERCEPTION
        val ajaxElements = document.select("ul#episode-nav li[data-post], .mobius li[data-post], .server-list li[data-post]")
        if (ajaxElements.isNotEmpty()) {
            coroutineScope {
                ajaxElements.map { element ->
                    async {
                        val postId = element.attr("data-post")
                        val nume = element.attr("data-nume")
                        if (postId.isNotBlank() && nume.isNotBlank()) {
                            val form = mapOf("action" to "player_ajax", "post" to postId, "nume" to nume, "type" to element.attr("data-type"))
                            val response = try { app.post("$mainUrl/wp-admin/admin-ajax.php", data = form, referer = data, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text } catch(e: Exception) { "" }
                            
                            val embedUrl = try {
                                JSONObject(response).optString("embed_url").ifBlank { response }
                            } catch (e: Exception) {
                                response
                            }
                            
                            val iframeSrc = Jsoup.parse(embedUrl).selectFirst("iframe")?.attr("src") 
                                ?: Regex("""src=["'](.*?)["']""").find(embedUrl)?.groupValues?.get(1) 
                                ?: Regex("""(https?://[^"'\s]+)""").find(embedUrl)?.groupValues?.get(1)
                                
                            if (!iframeSrc.isNullOrBlank()) invokeExtractor(iframeSrc, element.text().trim())
                        }
                    }
                }.awaitAll()
            }
        }

        // STRATEGY 4: RAW DOM IFRAMES
        document.select("#embed_holder iframe, .playerx iframe, .video-content iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                invokeExtractor(src, "Direct Server")
            }
        }

        Log.d("AnimeKhorTracer", "Total unique URLs sent to extractors: ${extractedUrls.size}")
        return true
    }
}
