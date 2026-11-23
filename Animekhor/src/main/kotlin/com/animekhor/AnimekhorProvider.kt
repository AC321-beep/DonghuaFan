package com.animekhor

import android.content.Context
import android.util.Base64
import android.util.Log
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

object AnimekhorLogger {
    private const val PREFS_NAME = "animekhor_settings"
    private const val KEY_VERBOSE = "verbose_logging"

    @Volatile
    var isVerbose: Boolean = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isVerbose = prefs.getBoolean(KEY_VERBOSE, false)
    }

    fun setVerbose(context: Context, enabled: Boolean) {
        isVerbose = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VERBOSE, enabled)
            .apply()
    }

    // Simplified to strict String parameters to prevent compiler lambda mismatches
    fun d(subTag: String, msg: String) {
        if (isVerbose) Log.d("AnimeKhor/$subTag", msg)
    }

    fun i(subTag: String, msg: String) {
        Log.i("AnimeKhor/$subTag", msg)
    }

    fun w(subTag: String, msg: String) {
        Log.w("AnimeKhor/$subTag", msg)
    }

    fun e(subTag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e("AnimeKhor/$subTag", msg, throwable)
        else Log.e("AnimeKhor/$subTag", msg)
    }
}

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

    private val cfInterceptor = CFInterceptor()

    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1"
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

    private fun isCfChallenge(html: String): Boolean = isCfChallengeHtml(html)

    private fun isValidSiteHtml(html: String): Boolean =
        html.length > 500 && !isCfChallenge(html) && isRealSiteHtml(html)

    // Using implicit return type to avoid unresolved NiceResponse compiler panics
    private suspend fun executeGet(url: String, headers: Map<String, String> = browserHeaders) = try {
        withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
            app.get(url, headers = headers, interceptor = cfInterceptor)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AnimekhorLogger.e("HTTP", "GET failed for $url: ${e.message}", e)
        null
    }

    private suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = browserHeaders
    ): Document? {
        val host = runCatching { URI(url).host }.getOrNull() ?: ""
        if (!host.contains("animekhor", ignoreCase = true)) return null

        AnimekhorLogger.d("Provider", "fetchDocument called for: $url")

        // 1. Optimistic Fast-Path Fetch
        // We do NOT wait on a lock here. This allows all 7 homepage tabs to load instantly in parallel if anti-bot is off.
        AnimekhorLogger.d("Provider", "Firing optimistic parallel fetch for $url")
        var response = executeGet(url, headers)

        if (response != null && response.code in 200..299 && isValidSiteHtml(response.text)) {
            AnimekhorLogger.d("Provider", "✅ Optimistic fetch successful! Code: ${response.code} Length: ${response.text.length}")
            CFState.markHostVerified(host)
            return response.document
        }

        // 2. Verify if it's ACTUALLY a challenge
        val code = response?.code ?: 0
        val text = response?.text.orEmpty()
        val cfMitigated = response?.headers?.toMap()?.entries?.any {
            it.key.equals("cf-mitigated", ignoreCase = true) && it.value.contains("challenge", ignoreCase = true)
        } == true

        AnimekhorLogger.d("Provider", "Optimistic fetch failed check. Code: $code, cfMitigated: $cfMitigated, TextLength: ${text.length}")

        val challengeDetected = cfMitigated ||
                isCfChallenge(text) ||
                (code in listOf(403, 503) && !isRealSiteHtml(text) &&
                        (text.contains("cloudflare", ignoreCase = true) || text.contains("ddos-guard", ignoreCase = true))) ||
                (code in 200..299 && text.isNotBlank() && text.length < 3500 && !isRealSiteHtml(text))

        if (!challengeDetected) {
            if (code in 200..299 && text.isNotBlank()) {
                AnimekhorLogger.d("Provider", "Returned non-challenge HTML directly.")
                CFState.markHostVerified(host)
                return response?.document
            }
            AnimekhorLogger.e("Provider", "🛑 Unknown Error ($code) for $url: ${text.take(200)}")
            return null
        }

        // 3. Challenge Confirmed. Now we serialize via Lock.
        CFState.markHostUnverified(host)
        AnimekhorLogger.w("Provider", "🛡 Challenge confirmed for $url. Waiting for solver lock...")

        if (CommonActivity.activity == null || CFState.isInCooldown(host)) {
            AnimekhorLogger.w("Provider", "⏸ Solver skipped. Activity null or in cooldown.")
            return null
        }

        // Simple Lock: No generics, no Pairs, no compiler panic.
        CFState.withColdStartLock(host) {
            AnimekhorLogger.d("Provider", "Lock acquired for $url. Checking if another thread already verified host.")
            if (!CFState.isHostVerified(host)) {
                AnimekhorLogger.i("Provider", "Host unverified. Launching CFSolver...")
                val isHumanCaptcha = isHumanOnlyChallenge(text)
                val solved = try {
                    CFSolver(url, skipSilent = isHumanCaptcha).solve()
                } catch (e: Exception) {
                    AnimekhorLogger.e("Provider", "CFSolver crashed: ${e.message}", e)
                    false
                }
                
                if (solved) {
                    AnimekhorLogger.i("Provider", "Solver returned true. Marking verified.")
                    CFState.markHostVerified(host)
                }
            } else {
                AnimekhorLogger.d("Provider", "Host was verified by another concurrent thread. Skipping solve.")
            }
        }

        // 4. Retry Request after Lock releases
        AnimekhorLogger.d("Provider", "Lock released. Retrying target URL: $url")
        response = executeGet(url, headers)
        
        if (response != null && response.code in 200..299 && isValidSiteHtml(response.text)) {
            AnimekhorLogger.i("Provider", "✅ Retry successful after solve!")
            CFState.markHostVerified(host)
            return response.document
        }

        // 5. WebView Fallback
        AnimekhorLogger.w("Provider", "⚠️ Retry failed (Code: ${response?.code}). Attempting WebView fallback...")
        val act = CommonActivity.activity
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            val html = CFState.fetchViaWebView(act, url)
            if (!html.isNullOrBlank() && isValidSiteHtml(html)) {
                CFState.markHostVerified(host)
                AnimekhorLogger.i("Provider", "✅ WebView transport succeeded (${html.length} bytes)")
                return Jsoup.parse(html, url)
            }
        }
        
        AnimekhorLogger.e("Provider", "❌ All fetch attempts failed for $url")
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

    private fun decodeBase64Safe(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http", ignoreCase = true) ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("<")
        ) {
            return trimmed
        }
        return try {
            val normalized = trimmed
                .replace("\\s+".toRegex(), "")
                .replace('-', '+')
                .replace('_', '/')
            val padded = when (normalized.length % 4) {
                2 -> "$normalized=="
                3 -> "$normalized="
                else -> normalized
            }
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            AnimekhorLogger.e("Provider", "Base64 decode failed for input (${trimmed.take(24)}...)", e)
            trimmed
        }
    }

    private fun Element.extractIframeSrc(): String? {
        val candidates = listOf(
            this.attr("data-lazy-src"),
            this.attr("data-src"),
            this.attr("src")
        )
        return candidates.firstOrNull { src ->
            src.isNotBlank() &&
            !src.equals("about:blank", ignoreCase = true) &&
            !src.startsWith("data:", ignoreCase = true) &&
            !src.startsWith("javascript:", ignoreCase = true)
        }?.trim()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        AnimekhorLogger.i("Provider", "loadLinks started for: $data")
        val document = try {
            fetchDocument(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AnimekhorLogger.e("Provider", "loadLinks fetchDocument error for $data: ${e.message}", e)
            null
        } ?: run {
            AnimekhorLogger.w("Provider", "loadLinks aborted: fetchDocument returned null for $data")
            return false
        }

        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            if (yieldedStreamUrls.add(link.url)) {
                AnimekhorLogger.d("Provider", "Emitted link -> [${link.name}] ${link.url}")
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
                AnimekhorLogger.d("Provider", "Skipping duplicate iframe: $dedupUrl")
                return
            }

            try {
                val isHandled: Boolean = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to OkRuCustom: $finalUrl")
                        OkRuCustom().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "p2pstream" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to P2pstream: $finalUrl")
                        P2pstream().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "upns.live" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to UpnsLive: $finalUrl")
                        UpnsLive().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "emturbovid" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to Emturbovid: $finalUrl")
                        Emturbovid().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "bysekoze.com" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to Bysekoze: $finalUrl")
                        Bysekoze().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "rumble.com" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to Rumble: $finalUrl")
                        Rumble().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "abyssplayer.com" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to AbyssPlayer: $finalUrl")
                        AbyssPlayer().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true
                    }
                    "d.tube" in finalUrl -> {
                        AnimekhorLogger.d("Provider", "Routing ($label) to Dtube: $finalUrl")
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach(trackingCallback); true
                    }
                    else -> false
                }

                if (!isHandled) {
                    AnimekhorLogger.d("Provider", "Routing ($label) to loadExtractor: $finalUrl")
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
            ".mobius option, select.mirror option, .server-list li a[data-embed], " +
            ".server-list li a[data-em], .server option, .player option"
        )

        val directIframeSrcs: List<String> = document
            .select("#embed_holder iframe, .playerx iframe, .video-content iframe, iframe")
            .mapNotNull { iframe ->
                val src = iframe.extractIframeSrc()
                if (!src.isNullOrBlank() &&
                    !src.contains("youtube", true) &&
                    !src.contains("disqus", true) &&
                    !src.contains("facebook", true) &&
                    !src.contains("google", true)
                ) src else null
            }

        AnimekhorLogger.i(
            "Provider",
            "Sources parsed for $data -> raw=${rawMatches.size}, servers=${serverElements.size}, iframes=${directIframeSrcs.size}"
        )

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

                        val decoded = decodeBase64Safe(rawData)
                        val iframeSrc: String = when {
                            decoded.startsWith("http", ignoreCase = true) || decoded.startsWith("//") -> decoded
                            decoded.contains("<iframe", ignoreCase = true) ->
                                Jsoup.parse(decoded).selectFirst("iframe")?.extractIframeSrc() ?: ""
                            else -> ""
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

        AnimekhorLogger.i("Provider", "loadLinks finished for $data: ${yieldedStreamUrls.size} stream(s) emitted")
        return yieldedStreamUrls.isNotEmpty()
    }
}
