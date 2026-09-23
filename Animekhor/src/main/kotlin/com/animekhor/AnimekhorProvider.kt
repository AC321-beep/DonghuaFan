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
import java.util.concurrent.ConcurrentHashMap

class AnimekhorProvider : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&type=&order=update" to "Recently Updated",
        "anime/?type=comic&order=update" to "Comic Recently Updated",
        "anime/?type=comic" to "Comic Series",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?status=&type=ona" to "Donghua Series",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Broad, layout-agnostic selector. The site has been observed wrapping
     * <article> elements inside intermediate divs (excstf, popconslide,
     * tab-pane, etc). The descendant selector covers all of those, while
     * `div.bsx` / `article.bs` act as final fallbacks if the wrapping class
     * changes again.
     */
    private val cardSelector = "div.listupd article, div.listupd .bsx, div.bsx, article.bs"

    // ---------------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data.replace("anime/?", "anime/page/$page/?")}"
        val document = app.get(url).document
        val home = document
            .select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href]") ?: return null

        val title = linkElement.attr("title")
            .ifBlank { this.selectFirst("h2[itemprop=headline]")?.text().orEmpty() }
            .ifBlank { this.selectFirst(".tt h2")?.text().orEmpty() }
            .ifBlank { this.selectFirst(".tt")?.text().orEmpty() }
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null

        val href = fixUrlNull(linkElement.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src")
                    .ifBlank { img.attr("src") }
                    .ifBlank { img.attr("data-lazy-src") }
                    .ifBlank { img.attr("data-original") }
            }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = coroutineScope {
            (1..3).map { page ->
                async {
                    try {
                        val document = app.get("$mainUrl/page/$page/?s=$query").document
                        document
                            .select(cardSelector)
                            .mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    // ---------------------------------------------------------------------
    // Load (series / movie)
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""

        val poster = document
            .selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("div.thumb img, .poster img, img.ts-post-image")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.trim()
                ?: ""

        val description = document
            .selectFirst("div.entry-content, div[itemprop=description], .desc")
            ?.text()?.trim()

        val typeText = document.selectFirst(".spe")?.text().orEmpty()
        val isMovie = typeText.contains("Movie", ignoreCase = true)

        if (isMovie) {
            val href = document
                .selectFirst(".eplister li > a, .episodelist li > a")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?: url

            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // ----- TV series / anime -----
        var epListElements = document.select(".episodelist li, .eplister li")

        if (epListElements.isEmpty()) {
            // Sometimes the episode list is on a separate page
            val epPage = document
                .selectFirst(".episodelist li > a, .eplister li > a, a.ep-link")
                ?.attr("href")
                .orEmpty()

            if (epPage.isNotBlank()) {
                runCatching {
                    val doc = app.get(epPage).document
                    epListElements = doc.select(".episodelist li, .eplister li")
                }
            }
        }

        val episodes = epListElements.mapNotNull { info ->
            val a = info.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val episodeText = info.selectFirst(".epl-title")?.text()
                ?: info.selectFirst("a span")?.text()
                ?: a.text()
                ?: ""

            val dateText = info
                .selectFirst(".epl-date, .date, .time, span.epl-date")
                ?.text()?.trim()

            // Extract just the episode number if possible
            val parsedEpisode = when {
                episodeText.contains("Episode", ignoreCase = true) -> {
                    episodeText.substringAfter("Episode", "").trim()
                        .substringBefore(" ").trim()
                        .ifBlank { episodeText.trim() }
                }
                episodeText.contains("-") -> {
                    episodeText.substringAfter("-")
                        .substringBeforeLast("-")
                        .trim()
                        .ifBlank { episodeText.trim() }
                }
                else -> episodeText.trim()
            }

            newEpisode(href) {
                this.name = parsedEpisode.takeIf { it.isNotEmpty() } ?: episodeText.trim()
                this.posterUrl = poster
                if (!dateText.isNullOrBlank()) {
                    runCatching {
                        this.addDate(dateText, format = "MMMM d, yyyy")
                    }
                    this.description = dateText
                }
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ---------------------------------------------------------------------
    // Load links
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Iframe URL dedup
        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        // Final stream URL dedup (prevents identical resolutions showing twice)
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()

        suspend fun invokeExtractor(iframeUrl: String) {
            var finalUrl = iframeUrl.trim()
            if (finalUrl.isEmpty()) return

            finalUrl = when {
                finalUrl.startsWith("//") -> "https:$finalUrl"
                finalUrl.startsWith("/") -> "https://ok.ru$finalUrl"
                !finalUrl.startsWith("http") -> "https://$finalUrl"
                else -> finalUrl
            }

            // Normalize ok.ru embed URLs
            if (finalUrl.contains("ok.ru") || finalUrl.contains("odnoklassniki.ru")) {
                val okId = Regex("""/video(?:embed)?/(\d+)""")
                    .find(finalUrl)?.groupValues?.getOrNull(1)
                    ?: finalUrl.substringAfterLast("/")
                if (okId.isNotBlank()) {
                    finalUrl = "https://ok.ru/videoembed/$okId"
                }
            }

            // Hydrax → AbyssPlayer migration
            if (finalUrl.contains("playhydrax.com")) {
                finalUrl = finalUrl.replace("playhydrax.com", "abyssplayer.com")
            }

            // Strip query params for dedup purposes
            val dedupUrl = finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) return

            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                val key = link.url.substringBefore("?")
                if (yieldedStreamUrls.add(key)) {
                    callback(link)
                }
            }

            try {
                val handled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRuCustom().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback); true
                    }
                    "p2pstream" in finalUrl -> {
                        P2pstream().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback); true
                    }
                    "upns.live" in finalUrl -> {
                        UpnsLive().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback); true
                    }
                    "emturbovid" in finalUrl -> {
                        Emturbovid().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback); true
                    }
                    "bysekoze.com" in finalUrl -> {
                        Bysekoze().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback); true
                    }
                    "rumble.com" in finalUrl -> {
                        Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback); true
                    }
                    "abyssplayer.com" in finalUrl -> {
                        AbyssPlayer().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback); true
                    }
                    else -> false
                }

                if (!handled) {
                    loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)
                }
            } catch (_: Exception) {
                // Fails silently per server
            }
        }

        // 1) Raw URL scan for known hosts in HTML
        val rawHtml = document.html()
        val globalUrlRegex = Regex(
            """https?://(?:www\.)?(?:ok\.ru|odnoklassniki\.ru|emturbovid\.com|p2pstream\.vip|upns\.live|bysekoze\.com|abyssplayer\.com|playhydrax\.com|rumble\.com)[^"'\s<>\\]+"""
        )
        globalUrlRegex.findAll(rawHtml).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            invokeExtractor(cleanUrl)
        }

        // 2) Server dropdown / list
        val serverElements = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], " +
            ".server-list li a[data-em], .eplister .mirror option, #server-list option"
        )

        coroutineScope {
            serverElements.map { server ->
                async {
                    val rawData = server.attr("value")
                        .ifBlank { server.attr("data-em") }
                        .ifBlank { server.attr("data-embed") }
                        .trim()

                    if (rawData.isBlank()) return@async

                    val iframeSrc: String = when {
                        rawData.startsWith("http") || rawData.startsWith("//") -> rawData

                        rawData.startsWith("<iframe", ignoreCase = true) ->
                            Jsoup.parse(rawData).selectFirst("iframe")?.attr("src").orEmpty()

                        else -> {
                            // Try base64 decode
                            runCatching {
                                val decoded = String(Base64.decode(rawData, Base64.DEFAULT))
                                if (decoded.contains("<iframe", ignoreCase = true)) {
                                    Jsoup.parse(decoded).selectFirst("iframe")?.attr("src").orEmpty()
                                } else {
                                    decoded
                                }
                            }.getOrDefault("")
                        }
                    }

                    if (iframeSrc.isNotBlank()) invokeExtractor(iframeSrc)
                }
            }.awaitAll()
        }

        // 3) Direct iframes on the page
        document.select("#embed_holder iframe, .playerx iframe, .video-content iframe, iframe[src]")
            .forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() &&
                    !src.contains("youtube", true) &&
                    !src.contains("disqus", true) &&
                    !src.contains("googletagmanager", true) &&
                    !src.contains("google-analytics", true)
                ) {
                    invokeExtractor(src)
                }
            }

        return true
    }
}
