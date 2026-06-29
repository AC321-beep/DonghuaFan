package com.luciferdonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidHidePro
import org.jsoup.nodes.Element
import java.net.URI
import kotlinx.coroutines.* class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "Lucifer Donghua"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasQuickSearch = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = defaultHeaders).document
        val home = document.select("article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, false), home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".tt h2") ?: return null
        val href = fixUrlNull(this.selectFirst("a[itemprop=url]")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.ts-post-image")?.attr("src"))
        return newAnimeSearchResponse(titleElement.text().trim(), href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url, headers = defaultHeaders).document
        return newSearchResponseList(document.select("article.bs").mapNotNull { it.toSearchResult() }, true)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document
        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".thumb img, .poster img")?.attr("src"))
        val description = document.selectFirst(".entry-content p, .synopsis p, .desc")?.text()?.trim()
        val episodes = mutableListOf<Episode>()

        document.select(".eplister ul li, #episode_list li, .listeps ul li").forEach { ep ->
            val link = ep.selectFirst("a")?.attr("href") ?: return@forEach
            episodes.add(newEpisode(fixUrl(link)) { name = ep.text().trim() })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        var anyStreamFound = false

        suspend fun processDoc(doc: org.jsoup.nodes.Document, referer: String) {
            // 1. Process Iframes
            doc.select("iframe").forEach { iframe ->
                val rawSrc = iframe.attr("data-src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("src")
                
                val cleanUrlRaw = fixUrlNull(rawSrc) ?: return@forEach
                val httpsUrl = if (cleanUrlRaw.startsWith("//")) "https:$cleanUrlRaw" else cleanUrlRaw

                if (httpsUrl.isNotBlank() && !httpsUrl.contains("about:blank")) {
                    
                    // Immutable URL resolution based on site logic
                    val finalUrl = when {
                        httpsUrl.contains("dailymotion", true) -> {
                            val match = Regex("""(?:video=|/video/|/embed/video/)([^&?"']+)""").find(httpsUrl)
                            if (match != null) "https://www.dailymotion.com/video/${match.groupValues[1]}" else httpsUrl
                        }
                        httpsUrl.contains("streamplay", true) -> {
                            httpsUrl.replace(Regex("""streamplay\.[a-z\.]+"""), "play.streamplay.co.in")
                        }
                        else -> httpsUrl
                    }

                    // Execute Extractors using the final immutable URL
                    if (finalUrl.contains("vidhide", true)) {
                        try { 
                            val domain = "https://" + URI(finalUrl).host
                            VidHidePro().apply { mainUrl = domain }.getUrl(finalUrl, referer, subtitleCallback, callback)
                            anyStreamFound = true 
                        } catch(e: Exception) {}
                    } else if (loadExtractor(finalUrl, referer, subtitleCallback, callback)) {
                        anyStreamFound = true
                    }
                }
            }
            
            // 2. Global Regex Failsafe for hidden URLs
            val html = doc.html()
            val embedRegex = Regex("""https?://(?:www\.)?(?:vidhidevip|vidhidepro|vidhide|play\.streamplay|rumble)[^\s"'<>]+""")
            embedRegex.findAll(html).forEach { match ->
                if (loadExtractor(match.value, referer, subtitleCallback, callback)) {
                    anyStreamFound = true
                }
            }
            
            // Regex specifically for hidden Dailymotion iframes
            val dmRegex = Regex("""https?://(?:geo\.)?dailymotion\.com/[^\s"'<>]+video=([^&\s"'<>]+)""")
            dmRegex.findAll(html).forEach { match ->
                val standardUrl = "https://www.dailymotion.com/video/${match.groupValues[1]}"
                if (loadExtractor(standardUrl, referer, subtitleCallback, callback)) {
                    anyStreamFound = true
                }
            }
        }

        // Fetch Main Document
        val baseDoc = try { app.get(data, headers = defaultHeaders).document } catch(e: Exception) { return@coroutineScope false }
        processDoc(baseDoc, data)

        // 3. Process External Mirrors
        baseDoc.select("select.mirror option")
            .mapNotNull { it.attr("value") }
            .filter { it.startsWith("http") && it != data }
            .distinct()
            .map { mirror ->
                async {
                    try {
                        val resp = app.get(mirror, headers = defaultHeaders)
                        if (resp.url != mirror && !resp.url.contains("luciferdonghua.in")) {
                            
                            val destUrl = resp.url
                            
                            // Immutable translation for Dailymotion redirects
                            val finalDest = if (destUrl.contains("dailymotion", true)) {
                                val match = Regex("""(?:video=|/video/|/embed/video/)([^&?"']+)""").find(destUrl)
                                if (match != null) "https://www.dailymotion.com/video/${match.groupValues[1]}" else destUrl
                            } else destUrl

                            if (finalDest.contains("vidhide", true)) {
                                try {
                                    val domain = "https://" + URI(finalDest).host
                                    VidHidePro().apply { mainUrl = domain }.getUrl(finalDest, data, subtitleCallback, callback)
                                    anyStreamFound = true
                                } catch(e: Exception) {}
                            } else if (loadExtractor(finalDest, data, subtitleCallback, callback)) {
                                anyStreamFound = true
                            }
                        } else {
                            // Internal page
                            processDoc(resp.document, mirror)
                        }
                    } catch(e: Exception) {}
                }
            }.awaitAll()

        return@coroutineScope anyStreamFound
    }
}
