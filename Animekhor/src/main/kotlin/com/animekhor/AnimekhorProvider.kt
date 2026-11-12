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

    // Matches the working browser navigation headers from /2.headers
    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-US,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1"
    )

    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to CFState.getBrowserUserAgent(),
            "Referer" to "$mainUrl/"
        ) + browserHeaders

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
    //  Cloudflare / Bot Verification helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun isCfChallenge(html: String): Boolean = isCfChallengeHtml(html)

    private fun cfHeaders(url: String, base: Map<String, String> = defaultHeaders): Map<String, String> {
        val host: String = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        val cfUa: String = CFState.userAgentFor(host) ?: CFState.getBrowserUserAgent()
        val cookies: String = try { CookieManager.getInstance().getCookie(url) ?: "" } catch (_: Exception) { "" }

        val merged: MutableMap<String, String> = base.toMutableMap()
        merged["User-Agent"] = cfUa
        if (cookies.isNotBlank()) {
            merged["Cookie"] = CFState.mergeCookies(base["Cookie"], cookies)
        }
        return merged
    }

    private suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = defaultHeaders
    ): Document? {
        val host: String = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        // Ignore foreign URLs passed by meta-providers like Ultima (e.g. simkl.com)
        if (!host.contains("animekhor", ignoreCase = true)) {
            return null
        }

        CFState.getSolvedHtml(url)?.let { html ->
            if (html.length > 500 && !isCfChallenge(html)) return Jsoup.parse(html, url)
        }

        // If another coroutine is already solving a challenge for this host, wait for it
        // instead of firing concurrent unauthenticated requests that trigger 403 rate limits.
        CFState.awaitOngoingSolve(host)

        CFState.getSolvedHtml(url)?.let { html ->
            if (html.length > 500 && !isCfChallenge(html)) return Jsoup.parse(html, url)
        }

        val response = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                app.get(url, headers = cfHeaders(url, headers))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null

        Log.e("Animekhor/Provider", "🌐 Initial Fetch: ${response.code} for $url")

        // HTTP 200 with real content returns immediately
        if (response.code in 200..299 && !isCfChallenge(response.text)) {
            return response.document
        }

        // Only trigger CFSolver if it is a genuine Cloudflare / Bot Verification challenge
        // on 403/429/503. Static LiteSpeed 403 blocks or 520/522/524 origin errors return null.
        if (response.code !in listOf(403, 429, 503) || !isCfChallenge(response.text)) {
            Log.e(
                "Animekhor/Provider",
                "🛑 Non-challenge response (${response.code}) for $url: ${response.text.take(200)}"
            )
            return null
        }

        Log.e("Animekhor/Provider", "🛑 Challenge Body Snippet: ${response.text.take(300)}")

        if (CommonActivity.activity == null) return null
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

            val retryResponse = try {
                withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                    app.get(url, headers = cfHeaders(url, headers))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (retryResponse != null && retryResponse.code in 200..299 && !isCfChallenge(retryResponse.text)) {
                return retryResponse.document
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Home / Search
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}&page=$page"
        }

        val home: List<SearchResponse> = try {
            fetchDocument(url)
                ?.select(cardSelector)
                ?.mapNotNull { it.toSearchResult() }
                ?.distinctBy { it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: emptyList()

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val title = linkElement.attr("title")
            .ifEmpty { this.selectFirst(".eggtitle")?.text() ?: "" }
            .ifEmpty { this.selectFirst(".tt")?.ownText() ?: "" }
            .ifEmpty { this.selectFirst(".tt")?.text() ?: "" }
            .takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifEmpty { img.attr("data-lazy-src") }
                    .ifEmpty { img.attr("src") }
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
                            ?: emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
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
        if (!url.contains("animekhor", ignoreCase = true)) {
            throw ErrorLoadingException("Unsupported URL: $url")
        }
        val document = fetchDocument(url) ?: throw ErrorLoadingException("Failed to load $url")
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".thumbook .thumb img, .thumb img")?.attr("src")?.trim()
            ?: ""
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
                // If user clicked an episode card from the home page, locate the parent series "/anime/..." link
                val epPage = document.selectFirst(
                    ".nvsc a[href*='/anime/'], .allc a[href*='/anime/'], .breadcrumb a[href*='/anime/'], .ts-breadcrumb a[href*='/anime/'], .episodelist a, .eplister a"
                )?.attr("href") ?: ""
                if (epPage.isNotBlank() && epPage != url) {
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
            }.distinctBy { it.data }.reversed().ifEmpty {
                // Fallback if the page is a direct single-episode post with no series episode list
                listOf(
                    newEpisode(url) {
                        this.name = title.ifEmpty { "Episode 1" }
                        this.posterUrl = poster
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Extractor orchestration
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            fetchDocument(data)
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
                    ?: finalUrl.substringBefore("?").substringAfterLast("/")
                finalUrl = "https://ok.ru/videoembed/$okId"
            }

            if (finalUrl.contains("playhydrax.com")) {
                finalUrl = finalUrl.replace("playhydrax.com", "abyssplayer.com")
            }

            val dedupUrl = if ("?v=" in finalUrl || "?id=" in finalUrl) finalUrl else finalUrl.substringBefore("?")
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
                    .ifBlank { iframe.attr("data-src") }
                    .ifBlank { iframe.attr("data-lazy-src") }
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) src else null
            }

        coroutineScope {
            val jobs = buildList {
                rawMatches.forEach { cleanUrl ->
                    add(async { invokeExtractor(cleanUrl, "Raw Source") })
                }

                serverElements.forEach { server ->
                    add(async {
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
                    })
                }

                directIframeSrcs.forEach { src ->
                    add(async { invokeExtractor(src, "Direct Server") })
                }
            }
            jobs.awaitAll()
        }

        return yieldedStreamUrls.isNotEmpty()
    }
}
