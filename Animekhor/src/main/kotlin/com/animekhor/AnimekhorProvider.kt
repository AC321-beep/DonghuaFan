package com.animekhor

import android.util.Base64
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
                
                // Broadened the selector just in case it's named slightly differently 
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim() 
                val parsedEpisode = if (episodeText.contains("-")) episodeText.substringAfter("-").substringBeforeLast("-").trim() else episodeText.trim()
                
                newEpisode(href) {
                    this.name = parsedEpisode.takeIf { it.isNotEmpty() } ?: episodeText
                    this.posterUrl = poster
                    
                    if (!dateText.isNullOrBlank()) { 
                        // 1. Try to properly parse the Date format AnimeKhor uses (Month dd, yyyy)
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        
                        // 2. Failsafe: Guarantee it appears on your screen by setting it as the description
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
        val servers = document.select(".mobius option, select.mirror option")

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            val finalUrl = fixUrl(iframeUrl)

            when {
                // Proxy & Malformed Hash domains
                "p2pstream.vip" in finalUrl -> P2pstream().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "upns.live" in finalUrl -> UpnsLive().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                
                // Strict Header & Custom Extractors
                "emturbovid" in finalUrl -> Emturbovid().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "bysekoze.com" in finalUrl -> Bysekoze().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "rumble.com" in finalUrl -> Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                
                // VidHide & StreamWish Clones
                "embedwish" in finalUrl -> Embedwish().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "filelions" in finalUrl -> Filelions().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "swhoi" in finalUrl -> Swhoi().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "vidhide" in finalUrl -> VidHidePro5().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                
                // Fallback to Cloudstream Core (Handles AbyssPlayer, ok.ru, Listeamed, etc.)
                else -> loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, callback)
            }
        }

        coroutineScope {
            servers.map { server ->
                async {
                    val base64 = server.attr("value")
                    if (base64.isNotBlank()) {
                        val decodedHtml = try { String(Base64.decode(base64, Base64.DEFAULT)) } catch (e: Exception) { "" }
                        val iframeSrc = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
                        if (!iframeSrc.isNullOrBlank()) {
                            invokeExtractor(iframeSrc, server.text().trim())
                        }
                    }
                }
            }.awaitAll()
        }

        // Fallback: if no dropdown options found, try any iframe on the page
        if (servers.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                    invokeExtractor(src, "Server")
                }
            }
        }

        return true
    }
}
