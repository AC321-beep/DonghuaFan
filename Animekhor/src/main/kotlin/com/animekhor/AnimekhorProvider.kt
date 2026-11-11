package com.animekhor

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class AnimekhorProvider : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private companion object {
        const val PAGE_FETCH_TIMEOUT_MS = 15_000L
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    private val cardSelector = "div.listupd > article, div.bsx"

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&type=&order=update" to "Recently Updated",
        "anime/?type=comic&order=update" to "Comic Recently Updated",
        "anime/?type=comic" to "Comic Series",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?status=&type=ona" to "Donghua Series",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    // ─────────────────────────────────────────────────────────────────────
    //  Cloudflare helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun isCfChallenge(html: String): Boolean {
        if (html.length > 10_000) return false
        val l = html.lowercase()
        return l.contains("just a moment") ||
                l.contains("checking your browser") ||
                l.contains("please wait while we verify") ||
                l.contains("cf-browser-verification") ||
                l.contains("/cdn-cgi/challenge-platform/") ||
                l.contains("<title>attention required") ||
                (l.contains("cloudflare") && l.contains("ray id"))
    }

    private fun cfHeaders(url: String, base: Map<String, String> = defaultHeaders): Map<String, String> {
        val host: String = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        val cfUa: String? = CFState.userAgentFor(host)
        val cookies: String = try { CookieManager.getInstance().getCookie(url) ?: "" } catch (_: Exception) { "" }

        val merged: MutableMap<String, String> = base.toMutableMap()
        if (cfUa != null && cfUa.isNotBlank()) merged["User-Agent"] = cfUa
        if (cookies.isNotBlank()) merged["Cookie"] = cookies
        return merged
    }

    private suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = defaultHeaders
    ): Document? {
        CFState.getSolvedHtml(url)?.let { html ->
            if (html.length > 500 && !isCfChallenge(html)) return Jsoup.parse(html, url)
        }

        val response = try {
            app.get(url, headers = cfHeaders(url, headers))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null

        // 🔍 DEBUG LOG: Inspect initial HTTP request
        Log.e("Animekhor/Provider", "🌐 Initial Fetch: ${response.code} for $url")
        if (response.code !in 200..299 || isCfChallenge(response.text)) {
            Log.e("Animekhor/Provider", "🛑 Blocked/Challenge Body Snippet: ${response.text.take(300)}")
        }

        if (response.code in 200..299 && !isCfChallenge(response.text)) {
            return response.document
        }

        if (!isCfChallenge(response.text) && response.code !in 200..299) {
            return null
        }

        if (CommonActivity.activity == null) return null
        val host: String = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        if (CFState.isInCooldown(host)) return null

        val solved: Boolean = try {
            CFSolver(url).solve()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

        if (solved) {
            CFState.getSolvedHtml(url)?.let { html ->
                if (html.length > 500 && !isCfChallenge(html)) return Jsoup.parse(html, url)
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Home / Search
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"

        val home: List<SearchResponse> = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                fetchDocument(url)
                    ?.select(cardSelector)
                    ?.mapNotNull { it.toSearchResult() }
                    ?.distinctBy { it.url }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: emptyList<SearchResponse>()

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val title = linkElement.attr("title").ifEmpty { this.selectFirst(".tt")?.text() ?: "" }
            .takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }.ifEmpty { img.attr("data-lazy-src") }
            }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results: List<SearchResponse> = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        fetchDocument("$mainUrl/?s=$encoded&page=$page")
                            ?.select(cardSelector)
                            ?.mapNotNull { it.toSearchResult() }
                            ?: emptyList<SearchResponse>()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList<SearchResponse>()
                    }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Detail
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(url) ?: throw Exception("Failed to load $url")
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
            var epListElements: List<Element> = document.select(".episodelist li, .eplister li")
            if (epListElements.isEmpty()) {
                val epPage = document.selectFirst(".episodelist li > a, .eplister li > a")?.attr("href") ?: ""
                if (epPage.isNotBlank()) {
                    epListElements = fetchDocument(epPage)
                        ?.select(".episodelist li, .eplister li")
                        ?: emptyList()
                }
            }

            val episodes = epListElements.mapNotNull { info ->
                val href = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val episodeText = info.selectFirst(".epl-title")?.text()
                    ?: info.selectFirst("a span")?.text() ?: ""
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()
                val parsedEpisode = if (episodeText.contains("-"))
                    episodeText.substringAfter("-").substringBeforeLast("-").trim()
                else episodeText.trim()

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

    // ─────────────────────────────────────────────────────────────────────
    //  Extractor orchestration — unchanged, only initial fetch CF-aware
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) { fetchDocument(data) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            if (yieldedStreamUrls.add(link.url)) callback(link)
        }
        val trackingSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (yieldedSubtitleUrls.add(sub.url)) subtitleCallback(sub)
        }

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = iframeUrl.trim()

            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) finalUrl = "https://ok.ru$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            if (finalUrl.contains("ok.ru") || finalUrl.contains("odnoklassniki.ru")) {
                val okId = Regex("""/video(?:embed)?/(\d+)""").find(finalUrl)?.groupValues?.get(1)
                    ?: finalUrl.substringAfterLast("/")
                finalUrl = "https://ok.ru/videoembed/$okId"
            }

            if (finalUrl.contains("playhydrax.com")) {
                finalUrl = finalUrl.replace("playhydrax.com", "abyssplayer.com")
            }

            val dedupUrl = finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) return

            try {
                val isHandled: Boolean = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRuCustom().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "p2pstream" in finalUrl -> {
                        P2pstream().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "upns.live" in finalUrl -> {
                        UpnsLive().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "emturbovid" in finalUrl -> {
                        Emturbovid().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "bysekoze.com" in finalUrl -> {
                        Bysekoze().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "rumble.com" in finalUrl -> {
                        Rumble().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "abyssplayer.com" in finalUrl -> {
                        AbyssPlayer().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "d.tube" in finalUrl -> {
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach(trackingCallback); true
                    }
                    else -> false
                }

                if (!isHandled) {
                    loadExtractor(finalUrl, referer = mainUrl, trackingSubtitleCallback, trackingCallback)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // one bad extractor doesn't kill the rest
            }
        }

        val rawHtml = document.html()
        val globalUrlRegex = Regex(
            """https?://(?:www\.)?(?:ok\.ru|odnoklassniki\.ru|emturbovid\.com|p2pstream\.vip|upns\.live|bysekoze\.com|abyssplayer\.com|playhydrax\.com|rumble\.com|d\.tube|vidara\.to)[^"'\s<>]+"""
        )
        val rawMatches: List<String> = globalUrlRegex.findAll(rawHtml)
            .map { it.value.replace("\\/", "/") }
            .toList()

        val serverElements = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], .server-list li a[data-em]"
        )

        val directIframeSrcs: List<String> = document
            .select("#embed_holder iframe, .playerx iframe, .video-content iframe")
            .mapNotNull { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) src else null
            }

        coroutineScope {
            rawMatches.map { cleanUrl ->
                async { invokeExtractor(cleanUrl, "Raw Source") }
            }.awaitAll()

            serverElements.map { server ->
                async {
                    val rawData = server.attr("value")
                        .ifBlank { server.attr("data-em").ifBlank { server.attr("data-embed") } }
                        .trim()
                    if (rawData.isBlank()) return@async

                    val iframeSrc: String = when {
                        rawData.startsWith("http") || rawData.startsWith("//") -> rawData
                        rawData.startsWith("<iframe", ignoreCase = true) ->
                            Jsoup.parse(rawData).selectFirst("iframe")?.attr("src") ?: ""
                        else -> try {
                            val decoded = String(Base64.decode(rawData, Base64.DEFAULT))
                            if (decoded.contains("<iframe", ignoreCase = true))
                                Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                            else decoded
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ""
                        }
                    }

                    if (iframeSrc.isNotBlank()) invokeExtractor(iframeSrc, server.text().trim())
                }
            }.awaitAll()

            directIframeSrcs.map { src ->
                async { invokeExtractor(src, "Direct Server") }
            }.awaitAll()
        }

        return true
    }
}
