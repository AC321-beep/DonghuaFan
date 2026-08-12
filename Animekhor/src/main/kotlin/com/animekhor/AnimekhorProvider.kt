package com.animekhor

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
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
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getsrcAttribute())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private fun Element.getsrcAttribute(): String {
        return this.attr("data-src").takeIf { it.startsWith("http") }
            ?: this.attr("src").takeIf { it.startsWith("http") }
            ?: this.attr("data-lazy-src").takeIf { it.startsWith("http") } ?: ""
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

        coroutineScope {
            servers.map { server ->
                async {
                    try {
                        val base64 = server.attr("value")
                        if (base64.isBlank()) return@async

                        val decodedHtml = try { String(Base64.decode(base64, Base64.DEFAULT)) } catch (e: Exception) { "" }
                        var finalUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
                        
                        if (finalUrl.isNullOrBlank()) return@async
                        if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
                        
                        val label = server.text().trim()
                        val fetchHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to mainUrl
                        )

                        // --- RAW INLINE EXTRACTION: Bypasses the broken Extractor classes natively ---
                        if ("emturbovid" in finalUrl || "listeamed" in finalUrl || "abyssplayer" in finalUrl || "p2pstream.vip" in finalUrl || "upns.live" in finalUrl) {
                            
                            val fixedUrl = finalUrl.replace("/t/", "/e/").replace("/v/", "/e/").replace("/#", "/e/")
                            var response = app.get(fixedUrl, headers = fetchHeaders)
                            var html = response.text
                            
                            // 1. Defeat Listeamed's window.location.replace redirect trap
                            val redirectMatch = Regex("""window\.location\.replace\(['"](.*?)['"]\)""").find(html)
                            if (redirectMatch != null) {
                                response = app.get(redirectMatch.groupValues[1], headers = fetchHeaders)
                                html = response.text
                            }
                            
                            // 2. Unpack JS only if it exists
                            val packedScript = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""").find(html)?.value
                            val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: html else html
                            
                            // 3. GREEDY M3U8 EXTRACTION (Rips out any raw M3U8 string, ignoring "source:" or "file:" labels)
                            var m3u8 = Regex("""https?://[^"'\s<>\[\]\\]+?\.m3u8[^"'\s<>\[\]\\]*""").find(unpacked)?.value
                            
                            // 4. GREEDY BASE64 DECODING (If the link is hidden inside a Base64 array, decode it)
                            if (m3u8 == null) {
                                Regex("""["']([A-Za-z0-9+/]{20,}={0,2})["']""").findAll(unpacked).forEach { match ->
                                    try {
                                        val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                                        val hiddenLink = Regex("""https?://[^"'\s<>\[\]\\]+?\.m3u8[^"'\s<>\[\]\\]*""").find(decoded)?.value
                                        if (hiddenLink != null) m3u8 = hiddenLink
                                    } catch (_: Exception) {}
                                }
                            }

                            // 5. INJECT ERROR 2004 BYPASS HEADERS AND SEND TO EXOPLAYER
                            if (m3u8 != null) {
                                val host = URI(fixedUrl).host
                                val streamHeaders = mapOf(
                                    "Origin" to "https://$host",
                                    "Referer" to response.url,
                                    "Accept" to "*/*",
                                    "User-Agent" to fetchHeaders["User-Agent"]!!
                                )
                                M3u8Helper.generateM3u8(label, m3u8!!, response.url, headers = streamHeaders).forEach(callback)
                            }
                        } 
                        else {
                            // Let Cloudstream natively handle normal URLs (ok.ru, streamwish, rumble, etc.)
                            loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        Log.e("Animekhor", "Extraction Crash: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        
        if (servers.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                    loadExtractor(src, referer = mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
