package com.animekhor

import android.util.Base64
import com.lagradost.api.Log
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
    override var name = "Animekhor"
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
        
        // Improved image URL fetching
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
                val parsedEpisode = if (episodeText.contains("-")) episodeText.substringAfter("-").substringBeforeLast("-").trim() else episodeText.trim()
                newEpisode(href) {
                    this.name = parsedEpisode.takeIf { it.isNotEmpty() } ?: episodeText
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }.reversed() // distinctBy it.url is safer than it.data

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

        Log.e("AnimekhorProvider", "Found ${servers.size} servers on the page.")

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            val finalUrl = fixUrl(iframeUrl)

            Log.e("AnimekhorProvider", "Routing -> $finalUrl | Label -> $label")

            when {
                "p2pstream.vip" in finalUrl -> P2pstream().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "emturbovid" in finalUrl -> Emturbovid().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "rumble.com" in finalUrl -> Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "embedwish" in finalUrl -> Embedwish().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "filelions" in finalUrl -> Filelions().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "swhoi" in finalUrl -> Swhoi().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                "vidhide" in finalUrl -> VidHidePro5().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
                
                // Note: UpnsLive, Listeamed, and AbyssPlayer were removed from here. 
                // They will naturally fall into the `else` block below which uses 
                // Cloudstream's native built-in extractors, fixing the build errors!
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
