package com.animekhor

import android.util.Base64
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
        const val SOLVE_WAIT_TIMEOUT_MS = 90_000L
    }

    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
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

    private fun isCfChallenge(html: String): Boolean = isCfChallengeHtml(html)

    private fun isValidSiteHtml(html: String): Boolean =
        html.length > 500 && !isCfChallenge(html) && isRealSiteHtml(html)

    private fun cfHeaders(url: String, base: Map<String, String> = defaultHeaders): Map<String, String> {
        val host: String = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        val cookies: String = CFState.extractAllCookies(host, url)

        val merged: MutableMap<String, String> = base.toMutableMap()
        merged["User-Agent"] = CFState.getBrowserUserAgent()
        if (cookies.isNotBlank()) {
            merged["Cookie"] = CFState.mergeCookies(base["Cookie"], cookies)
        }
        return merged
    }

    private suspend fun executeGet(url: String, headers: Map<String, String>) = try {
        withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
            app.get(url, headers = cfHeaders(url, headers))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = defaultHeaders
    ): Document? {
        val host: String = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        if (!host.contains("animekhor", ignoreCase = true)) {
            return null
        }

        // Cache check #1
        CFState.getSolvedHtml(url)?.let { html ->
            if (isValidSiteHtml(html)) return Jsoup.parse(html, url)
        }

        withTimeoutOrNull(SOLVE_WAIT_TIMEOUT_MS) { CFState.awaitOngoingSolve(host) }

        // Cache check #2
        CFState.getSolvedHtml(url)?.let { html ->
            if (isValidSiteHtml(html)) return Jsoup.parse(html, url)
        }

        // Cold-start gate: serialize only when the host is unverified or its 30-min TTL expired.
        // Once verified, requests execute in full parallel without semaphore throttling.
        val response = if (!CFState.isHostVerified(host)) {
            val (res, cachedDoc) = CFState.withColdStartLock(host) {
                withTimeoutOrNull(SOLVE_WAIT_TIMEOUT_MS) { CFState.awaitOngoingSolve(host) }
                CFState.getSolvedHtml(url)?.let { html ->
                    if (isValidSiteHtml(html)) {
                        return@withColdStartLock null to Jsoup.parse(html, url)
                    }
                }
                val r = executeGet(url, headers)
                if (r != null && r.code in 200..299 && isValidSiteHtml(r.text)) {
                    CFState.markHostVerified(host)
                }
                r to null
            }
            if (cachedDoc != null) return cachedDoc
            res
        } else {
            executeGet(url, headers)
        } ?: return null

        AnimekhorLogger.d("Provider") { "🌐 Initial Fetch: ${response.code} for $url" }

        if (response.code in 200..299 && isValidSiteHtml(response.text)) {
            CFState.markHostVerified(host)
            return response.document
        }

        // Trigger solver on explicit CF/LiteSpeed challenges OR silent 200 OK interstitials
        // that lack real AnimeKhor DOM markers.
        val challengeDetected = isCfChallenge(response.text) ||
                (response.code in 200..299 && response.text.isNotBlank() && !isRealSiteHtml(response.text))

        if (!challengeDetected) {
            AnimekhorLogger.e(
                "Provider",
                "🛑 Non-challenge error (${response.code}) for $url: ${response.text.take(200)}"
            )
            return null
        }

        CFState.markHostUnverified(host)
        AnimekhorLogger.w("Provider") {
            "🛡 Challenge/Interstitial (${response.code}) for $url: ${response.text.take(250)}"
        }

        if (CommonActivity.activity == null || CFState.isInCooldown(host)) {
            return null
        }

        val isHumanCaptcha = isHumanOnlyChallenge(response.text)
        if (isHumanCaptcha) {
            AnimekhorLogger.w("Provider") { "🧩 Interactive CAPTCHA detected — skipping silent solver" }
        }

        val solved: Boolean = try {
            CFSolver(url, skipSilent = isHumanCaptcha).solve()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

        if (solved) {
            CFState.getSolvedHtml(url)?.let { html ->
                if (isValidSiteHtml(html)) return Jsoup.parse(html, url)
            }

            val retryResponse = executeGet(url, headers)
            if (retryResponse != null && retryResponse.code in 200..299 && isValidSiteHtml(retryResponse.text)) {
                return retryResponse.document
            }

            val act = CommonActivity.activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val html = CFState.fetchViaWebView(act, url)
                if (!html.isNullOrBlank() && isValidSiteHtml(html)) {
                    AnimekhorLogger.d("Provider") { "✅ WebView transport succeeded (${html.length} bytes)" }
                    return Jsoup.parse(html, url)
                }
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}${separator}page=$page"
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

        val page1Doc = try {
            fetchDocument("$mainUrl/?s=$encoded&page=1")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val page1Results = page1Doc.select(cardSelector).mapNotNull { it.toSearchResult() }
        val hasNextPage = page1Doc.selectFirst(".pagination .next, a.next.page-numbers, .hpage a.r") != null

        val page2Results = if (hasNextPage && page1Results.size >= 10) {
            try {
                fetchDocument("$mainUrl/?s=$encoded&page=2")
                    ?.select(cardSelector)
                    ?.mapNotNull { it.toSearchResult() }
                    ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        return (page1Results + page2Results).distinctBy { it.url }
    }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        AnimekhorLogger.d("Provider") { "loadLinks started for: $data" }
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
            if (yieldedStreamUrls.add(link.url)) {
                AnimekhorLogger.d("Provider") { "Emitted link -> [${link.name}] ${link.url}" }
                callback(link)
            }
        }
        val trackingSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (yieldedSubtitleUrls.add(sub.url)) subtitleCallback(sub)
        }

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = iframeUrl.trim()

            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) {
                val base = try { URI(data).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { mainUrl }
                finalUrl = "$base$finalUrl"
            } else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            if (finalUrl.contains("ok.ru") || finalUrl.contains("odnoklassniki.ru")) {
                val okId = Regex("""/video(?:embed)?/(\d+)""").find(finalUrl)?.groupValues?.get(1)
                    ?: finalUrl.substringBefore("?").substringAfterLast("/")
                finalUrl = "https://ok.ru/videoembed/$okId"
            }

            if (finalUrl.contains("playhydrax.com")) {
                finalUrl = finalUrl.replace("playhydrax.com", "abyssplayer.com")
            }

            val dedupUrl = if ("?v=" in finalUrl || "?id=" in finalUrl) finalUrl else finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) {
                AnimekhorLogger.d("Provider") { "Skipping duplicate iframe: $dedupUrl" }
                return
            }

            try {
                val isHandled: Boolean = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to OkRuCustom: $finalUrl" }
                        OkRuCustom().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "p2pstream" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to P2pstream: $finalUrl" }
                        P2pstream().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "upns.live" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to UpnsLive: $finalUrl" }
                        UpnsLive().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "emturbovid" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to Emturbovid: $finalUrl" }
                        Emturbovid().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "bysekoze.com" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to Bysekoze: $finalUrl" }
                        Bysekoze().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "rumble.com" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to Rumble: $finalUrl" }
                        Rumble().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "abyssplayer.com" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to AbyssPlayer: $finalUrl" }
                        AbyssPlayer().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "d.tube" in finalUrl -> {
                        AnimekhorLogger.d("Provider") { "Routing ($label) to Dtube: $finalUrl" }
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach(trackingCallback); true
                    }
                    else -> false
                }

                if (!isHandled) {
                    AnimekhorLogger.d("Provider") { "Routing ($label) to loadExtractor: $finalUrl" }
                    loadExtractor(finalUrl, referer = mainUrl, trackingSubtitleCallback, trackingCallback)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnimekhorLogger.e("Provider", "Extractor failed ($label) for $finalUrl: ${e.message}", e)
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

        AnimekhorLogger.d("Provider") { "loadLinks finished: ${yieldedStreamUrls.size} unique stream(s) emitted" }
        return yieldedStreamUrls.isNotEmpty()
    }
}
