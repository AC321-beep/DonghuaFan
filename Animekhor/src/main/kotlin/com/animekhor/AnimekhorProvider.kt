package com.Animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        "anime/?status=&sub=&order=latest" to "Latest Added",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home = document.select("div.listupd > article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.bsx > a")?.attr("title") ?: return null
        val href = fixUrlNull(this.selectFirst("div.bsx > a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.bsx > a img")?.getsrcAttribute())
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.getsrcAttribute(): String {
        val src = this.attr("src")
        val dataSrc = this.attr("data-src")
        return when {
            dataSrc.startsWith("http") -> dataSrc
            src.startsWith("http") -> src
            else -> ""
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val document = app.get("$mainUrl/page/$i/?s=$query").document
            val results = document.select("div.listupd > article").mapNotNull {
                it.toSearchResult()
            }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text()
        
        val tvtag = if (type?.contains("Movie", ignoreCase = true) == true) TvType.Movie else TvType.TvSeries

        if (tvtag == TvType.Movie) {
            val href = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val epPage = document.selectFirst(".eplister li > a")?.attr("href") ?: ""
            val doc = app.get(epPage).document
            val epPoster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            
            val episodes = doc.select("div.episodelist > ul > li").mapNotNull { info ->
                val href1 = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val episodeText = info.selectFirst("a span")?.text() ?: ""
                
                // Safer parsing: Fallback to full text if dashes are missing
                val parsedEpisode = episodeText.substringAfter("-").substringBeforeLast("-").trim()
                val episodeName = parsedEpisode.takeIf { it.isNotEmpty() } ?: episodeText

                newEpisode(href1) {
                    this.name = episodeName
                    this.posterUrl = epPoster
                }
            }.reversed()

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
        val servers = document.select(".mobius option")
        
        // Use standard Coroutines to replace deprecated apmap
        coroutineScope {
            servers.map { server ->
                async {
                    val base64 = server.attr("value")
                    if (base64.isEmpty()) return@async // Skip if empty

                    val decodedUrl = String(Base64.decode(base64, Base64.DEFAULT))
                    val regex = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    var url = regex.find(decodedUrl)?.groups?.get(1)?.value
                    
                    // Prevent passing bad/null URLs to loadExtractor
                    if (url.isNullOrEmpty()) return@async
                    
                    if (url.startsWith("//")) {
                        url = "https:$url"
                    }
                    
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }.awaitAll()
        }
        return true
    }
}
