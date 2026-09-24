package com.animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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

    // ---- Browser-mimic headers ----
    // Cloudflare silently blocks requests whose headers don't look like a real
    // browser (it isn't a captcha — a browser passes automatically, OkHttp
    // doesn't). Sending these on every request keeps us off the bot list.
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    // The site wraps <article> inside intermediate containers (excstf,
    // popconslide, tab-pane), so descendant selectors are required — the old
    // direct-child selector `div.listupd > article` now matches nothing.
    private val cardSelector = "div.listupd article, div.listupd .bsx, div.bsx, article.bs"

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
        val url = "$mainUrl/${request.data.replace("anime/?", "anime/page/$page/?")}"
        val document = app.get(url, headers = defaultHeaders).document
        val home = document
            .select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null

        // Prefer the h2[itemprop=headline] so the duplicated raw text sitting
        // before the h2 inside .tt doesn't get prepended to the title.
        val title = linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("h2[itemprop=headline]")?.text()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt h2")?.text()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("src") }
                    .ifBlank { img.attr("data-lazy-src") }
            }
        )

        return newMovieSearchResponse(title.trim(), href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        val encoded = URLEncoder.encode(query, "UTF-8")
                        val document = app
                            .get("$mainUrl/page/$page/?s=$encoded", headers = defaultHeaders)
                            .document
                        document.select(cardSelector).mapNotNull { it.toSearchResult() }
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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document
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
                    val doc = app.get(epPage, headers = defaultHeaders).document
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
        // 1) Fetch the episode page, bounded by a timeout
        val document = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                app.get(data, headers = defaultHeaders).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        // Dedup state — shared across every extractor, thread-safe
        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        // Video dedup — the first occurrence of each URL wins
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            if (yieldedStreamUrls.add(link.url)) {
                callback(link)
            }
        }

        // Subtitle dedup — same URL emitted by two extractors reaches the UI once
        val trackingSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (yieldedSubtitleUrls.add(sub.url)) {
                subtitleCallback(sub)
            }
        }

        suspend fun invokeExtractor(iframeUrl: String, label: String) {
            var finalUrl = iframeUrl.trim()

            if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (finalUrl.startsWith("/")) {
                finalUrl = "https://ok.ru$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            if (finalUrl.contains("ok.ru") || finalUrl.contains("odnoklassniki.ru")) {
                val okId = Regex("""/video(?:embed)?/(\d+)""").find(finalUrl)?.groupValues?.get(1) ?: finalUrl.substringAfterLast("/")
                finalUrl = "https://ok.ru/videoembed/$okId"
            }

            if (finalUrl.contains("playhydrax.com")) {
                finalUrl = finalUrl.replace("playhydrax.com", "abyssplayer.com")
            }

            val dedupUrl = finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) return

            try {
                val isHandled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> { OkRuCustom().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true }
                    "p2pstream" in finalUrl -> { P2pstream().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true }
                    "upns.live" in finalUrl -> { UpnsLive().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true }
                    "emturbovid" in finalUrl -> { Emturbovid().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true }
                    "bysekoze.com" in finalUrl -> { Bysekoze().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true }
                    "rumble.com" in finalUrl -> { Rumble().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true }
                    "abyssplayer.com" in finalUrl -> { AbyssPlayer().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback); true }
                    else -> false
                }

                if (!isHandled) {
                    loadExtractor(finalUrl, referer = mainUrl, trackingSubtitleCallback, trackingCallback)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fails silently — one bad extractor doesn't kill the rest
            }
        }

        // 2) Collect every candidate source first (CPU-only, fast)
        val rawHtml = document.html()
        val globalUrlRegex = Regex("""https?://(?:www\.)?(?:ok\.ru|odnoklassniki\.ru|emturbovid\.com|p2pstream\.vip|upns\.live|bysekoze\.com|abyssplayer\.com|playhydrax\.com)[^"'\s<>]+""")
        val rawMatches = globalUrlRegex.findAll(rawHtml)
            .map { it.value.replace("\\/", "/") }
            .toList()

        val serverElements = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], .server-list li a[data-em]"
        )

        val directIframeSrcs = document
            .select("#embed_holder iframe, .playerx iframe, .video-content iframe")
            .mapNotNull { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) src else null
            }

        // 3) Fan out every source in parallel
        coroutineScope {
            // Raw HTML matches
            rawMatches.map { cleanUrl ->
                async { invokeExtractor(cleanUrl, "Raw Source") }
            }.awaitAll()

            // Server option entries — value, data-em, or data-embed (base64 or raw iframe)
            serverElements.map { server ->
                async {
                    val rawData = server.attr("value")
                        .ifBlank { server.attr("data-em").ifBlank { server.attr("data-embed") } }
                        .trim()
                    if (rawData.isBlank()) return@async

                    val iframeSrc = when {
                        rawData.startsWith("http") || rawData.startsWith("//") -> rawData
                        rawData.startsWith("<iframe", ignoreCase = true) ->
                            Jsoup.parse(rawData).selectFirst("iframe")?.attr("src") ?: ""
                        else -> try {
                            val decoded = String(Base64.decode(rawData, Base64.DEFAULT))
                            if (decoded.contains("<iframe", ignoreCase = true)) {
                                Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                            } else decoded
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            ""
                        }
                    }

                    if (iframeSrc.isNotBlank()) {
                        invokeExtractor(iframeSrc, server.text().trim())
                    }
                }
            }.awaitAll()

            // Direct iframes in the page body
            directIframeSrcs.map { src ->
                async { invokeExtractor(src, "Direct Server") }
            }.awaitAll()
        }

        return true
    }
}
