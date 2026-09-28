package com.anime4i

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class Anime4iProvider : MainAPI() {
    override var mainUrl = "https://anime4i.com"
    override var name = "Anime4i"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.AsianDrama)

    private companion object {
        const val PAGE_FETCH_TIMEOUT_MS = 15_000L
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private val cardSelector = "article.bs, div.bsx"

    // Exactly TWO categories as requested
    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Latest Releases",
        "anime/?status=&type=&order=popular" to "Popular Today"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Appends the page number correctly to the queries defined in mainPageOf
        val url = "$mainUrl/${request.data}&page=$page"

        val document = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                app.get(url, headers = defaultHeaders).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return newHomePageResponse(request.name, emptyList())

        // Scrape the cards
        val items = document.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").ifEmpty { this.selectFirst(".tt h2, .tt")?.text() ?: "" }.trim()
        if (title.isBlank()) return null

        val href = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }.ifEmpty { img.attr("data-lazy-src") }
            }
        )

        val epString = this.selectFirst(".epx")?.text()?.replace(Regex("[^0-9]"), "")
        val epNum = epString?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
                        val document = app.get(url, headers = defaultHeaders).document
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
        var document = app.get(url, headers = defaultHeaders).document

        // If user clicks a direct episode link, resolve the parent anime series page
        if (url.contains("-episode-")) {
            val seriesHref = document.selectFirst(
                ".allep a, .ts-breadcrumb li:nth-child(2) a, a.series, .naveps .nve a[href*='/anime/']"
            )?.attr("href")

            if (!seriesHref.isNullOrBlank() && seriesHref != url) {
                try {
                    document = app.get(seriesHref, headers = defaultHeaders).document
                } catch (_: Exception) {}
            }
        }

        val title = document.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".thumb img, .infox .thumb img")?.attr("src")
        )
        val description = document.selectFirst(".entry-content, .infox .desc, .mindes")?.text()?.trim()

        var epListElements = document.select(".episodelist li, .eplister li")
        if (epListElements.isEmpty()) {
            val epPage = document.selectFirst(".episodelist li > a, .eplister li > a")?.attr("href") ?: ""
            if (epPage.isNotBlank() && epPage != url) {
                try {
                    val doc = app.get(epPage, headers = defaultHeaders).document
                    epListElements = doc.select(".episodelist li, .eplister li")
                } catch (_: Exception) {}
            }
        }

        val episodes = epListElements.mapNotNull { info ->
            val href = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val episodeText = info.selectFirst(".epl-num")?.text()
                ?: info.selectFirst(".epl-title")?.text()
                ?: info.selectFirst("a span")?.text()
                ?: "Episode"
            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

            newEpisode(href) {
                this.name = episodeText.trim()
                this.posterUrl = poster
                if (!dateText.isNullOrBlank()) {
                    this.description = dateText
                }
            }
        }.distinctBy { it.data }.reversed()

        val finalEpisodes = episodes.ifEmpty {
            listOf(
                newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                app.get(data, headers = defaultHeaders).document
            }
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
                callback(link)
            }
        }

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
                finalUrl = "$mainUrl$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

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
                val isHandled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRuCustom().getUrl(finalUrl, mainUrl, trackingSubtitleCallback, trackingCallback)
                        true
                    }
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
            } catch (_: Exception) {}
        }

        val rawHtml = document.html()
        val globalUrlRegex = Regex("""https?://(?:www\.)?(?:ok\.ru|odnoklassniki\.ru|dailymotion\.com|dai\.ly|emturbovid\.com|p2pstream\.vip|upns\.live|bysekoze\.com|abyssplayer\.com|playhydrax\.com)[^"'\s<>]+""")
        val rawMatches = globalUrlRegex.findAll(rawHtml)
            .map { it.value.replace("\\/", "/") }
            .toList()

        val serverElements = document.select(
            "select.mirror option, .mobius option, .server-list li a[data-embed], .server-list li a[data-em], select[name='server'] option"
        )

        val directIframeSrcs = document
            .select("#pembed iframe, #embed_holder iframe, .playerx iframe, .video-content iframe, .player-embed iframe, iframe")
            .mapNotNull { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) src else null
            }

        coroutineScope {
            // Raw HTML scan
            rawMatches.map { cleanUrl ->
                async { invokeExtractor(cleanUrl, "Raw Source") }
            }.awaitAll()

            // Dropdown server options
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

            // Direct DOM iframes
            directIframeSrcs.map { src ->
                async { invokeExtractor(src, "Direct Server") }
            }.awaitAll()
        }

        return true
    }
}
