package com.animekhor

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }.ifEmpty { img.attr("data-lazy-src") }
            }
        )
        
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        val document = app.get("$mainUrl/page/$page/?s=$query").document
                        document.select("div.listupd > article, div.bsx").mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) { 
                        emptyList() 
                    }
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
                    
                    if (!dateText.isNullOrBlank()) { 
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.distinctBy { it.data }.reversed()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
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

            // Native Cloudstream execution
            try {
                loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("AnimeKhor", "Native extraction failed for $finalUrl: ${e.message}")
            }

            // Custom Fallback execution
            try {
                when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> OkRuCustom().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "p2pstream" in finalUrl -> P2pstream().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "upns.live" in finalUrl -> UpnsLive().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "emturbovid" in finalUrl -> Emturbovid().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "bysekoze.com" in finalUrl -> Bysekoze().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "abyssplayer.com" in finalUrl -> AbyssPlayer().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "rumble.com" in finalUrl -> Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "embedwish" in finalUrl -> Embedwish().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "filelions" in finalUrl -> Filelions().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "swhoi" in finalUrl -> Swhoi().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                    "vidhide" in finalUrl -> VidHidePro5().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("AnimeKhor", "Custom extraction failed for $finalUrl: ${e.message}")
            }
        }

        // STRATEGY 1: Base64 and HTML parsing from selectors
        val serverElements = document.select(".mobius option, select.mirror option, .server-list li a[data-embed], .server-list li a[data-em]")
        coroutineScope {
            serverElements.map { server ->
                async {
                    val rawData = server.attr("value").ifBlank { 
                        server.attr("data-em").ifBlank { server.attr("data-embed") }
                    }.trim()
                    
                    if (rawData.isNotBlank()) {
                        var iframeSrc = ""
                        
                        if (rawData.startsWith("http") || rawData.startsWith("//")) {
                            iframeSrc = rawData
                        } else if (rawData.startsWith("<iframe", ignoreCase = true)) {
                            iframeSrc = Jsoup.parse(rawData).selectFirst("iframe")?.attr("src") ?: ""
                        } else {
                            try {
                                val decoded = String(Base64.decode(rawData, Base64.DEFAULT))
                                iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {
                                    Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                                } else decoded
                            } catch (e: Exception) {}
                        }
                        
                        if (iframeSrc.isNotBlank()) invokeExtractor(iframeSrc, server.text().trim())
                    }
                }
            }.awaitAll()
        }

        // STRATEGY 2: AJAX Interception with pure Regex (Fixes AbyssPlayer missing links)
        val ajaxElements = document.select("ul#episode-nav li[data-post], .mobius li[data-post], .server-list li[data-post]")
        if (ajaxElements.isNotEmpty()) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            coroutineScope {
                ajaxElements.map { element ->
                    async {
                        val postId = element.attr("data-post")
                        val nume = element.attr("data-nume")
                        val type = element.attr("data-type")
                        
                        if (postId.isNotBlank() && nume.isNotBlank()) {
                            val form = mapOf(
                                "action" to "player_ajax",
                                "post" to postId,
                                "nume" to nume,
                                "type" to type
                            )
                            val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                            val response = try { app.post(ajaxUrl, data = form, referer = data, headers = headers).text } catch(e: Exception) { "" }
                            
                            // Aggressive regex to pull URLs like "https:\/\/abyssplayer.com\/caZjqQVVn" out of JSON payloads
                            val rawExtractedUrl = Regex("""https?:[\\/]+[^"'\s\\]+""").find(response)?.value
                            if (rawExtractedUrl != null) {
                                val cleanIframeSrc = rawExtractedUrl.replace("\\/", "/")
                                invokeExtractor(cleanIframeSrc, element.text().trim())
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // STRATEGY 3: Fallback - grab raw iframes directly embedded in the DOM
        document.select("#embed_holder iframe, .playerx iframe, .video-content iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                invokeExtractor(src, "Direct Server")
            }
        }

        return true
    }
}
