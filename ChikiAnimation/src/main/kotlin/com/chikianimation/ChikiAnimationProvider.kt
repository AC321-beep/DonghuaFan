package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

private object ProviderRx {
    val seasonNum  = Regex("(?i)(?:season\\s*(\\d+)|s(\\d+))")
    val episodeNum = Regex("(?i)(?:episode|ep)\\s*(\\d+)")
    val rangeTo    = Regex("(\\d+)\\s*(?:to|-)\\s*\\d+")
    val rangeParen = Regex("\\((\\d+)\\s*(?:to|-)\\s*\\d+\\)")
    val rangeDash  = Regex("(\\d+)-(\\d+)")
    val digits     = Regex("\\d+")
    val episodePre = Regex("(?i)^\\s*Episode\\s*")
    val gdriveFileId  = Regex("/file/d/([a-zA-Z0-9_-]{10,})")
    val gdriveQueryId = Regex("[?&]id=([a-zA-Z0-9_-]{10,})")
    val gdriveUuid1   = Regex("""<input[^>]*?name=["']uuid["'][^>]*?value=["']([^"']+)["']""")
    val gdriveUuid2   = Regex("""<input[^>]*?value=["']([^"']+)["'][^>]*?name=["']uuid["']""")
    val dmVideoIdGeo = Regex("video=([a-zA-Z0-9_-]+)")
    val dmVideoIdStd = Regex("(?:video/|dai\\.ly/|embed/video/)([a-zA-Z0-9_-]+)")
    val dmM3u8       = Regex("[\"']url[\"']\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']")
    val anyUrl       = Regex("https?://[^\\s\"'<>\\\\)]+")
}

abstract class ChikiAnimationProvider : MainAPI() {

    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    companion object {
        private const val PAGE_FETCH_TIMEOUT_MS = 15_000L
        private const val EXTRACTOR_TIMEOUT_MS = 20_000L
        private const val GRACE_AFTER_FIRST_EMIT_MS = 1_500L
        private const val MAX_CONCURRENT_EXTRACTORS = 4
        private const val CARD_SELECTOR =
            "div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx"
        private const val EPISODE_LIST_SELECTOR = ".episodelist li, .eplister li"
        private const val EPISODE_LINK_SELECTOR = ".episodelist li > a[href], .eplister li > a[href]"
        private val BLACKLIST_HOSTS = setOf(
            "youtube", "disqus", "googlesyndication", "doubleclick"
        )
    }

    private val defaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // get() so it reads mainUrl from whichever subclass is running
    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to defaultUserAgent,
            "Referer" to mainUrl,
            "Origin" to mainUrl
        )

    private val gdriveHeaders = mapOf(
        "User-Agent" to defaultUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://drive.google.com/"
    )

    private suspend fun safeExtract(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    /**
     * Resolve an <img> URL. On chikianimation.online, WordPress lazy-load
     * plugins hide the real URL in data-src / data-lazy-src and put a base64
     * placeholder GIF in src. Always prefer the lazy attributes and reject
     * base64 placeholders. On chikianimation.com, data-src is empty and we
     * fall through to src — identical behaviour to before.
     */
    private fun Element.resolveLazyImg(): String? {
        val raw = attr("data-src").ifEmpty { attr("data-lazy-src") }
            .ifEmpty { attr("data-original") }
            .ifEmpty { attr("src") }
        return raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val items = try {
            app.get(url, headers = defaultHeaders).document
                .select(CARD_SELECTOR)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return "$mainUrl/$base"
        return when {
            !base.contains("?") -> "${mainUrl}/${base.trimEnd('/')}/page/$page/"
            else -> "$mainUrl/$base&page=$page"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href]")
            ?: selectFirst("a[itemprop=url]")
            ?: selectFirst("h2 a[href]")
            ?: selectFirst("a[href]")
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (href.isBlank()) return null

        if (href.contains("/genres/") ||
            href.contains("/bookmark") ||
            href.contains("/privacy") ||
            href.contains("/contact") ||
            href.contains("/dmca")
        ) return null

        val title = selectFirst("div.tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("div.tt h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        // Grab any img element then resolve its real URL via the lazy-aware helper.
        // Works for both .com (direct src) and .online (lazy data-src).
        val imgElement = selectFirst("img.ts-post-image")
            ?: selectFirst("div.limit img")
            ?: selectFirst("img")

        val posterUrl = fixUrlNull(imgElement?.resolveLazyImg())

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.trim()
        val allItems = mutableListOf<SearchResponse>()

        for (page in 1..2) {
            try {
                val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
                val docs = app.get(url, headers = defaultHeaders).document
                    .select(CARD_SELECTOR)
                    .mapNotNull { it.toSearchResult() }
                allItems.addAll(docs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        return allItems.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        // Poster on the detail page — same lazy-aware logic as cards
        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: fixUrlNull(
                (
                    document.selectFirst("div.thumb img.wp-post-image")
                        ?: document.selectFirst("div.thumb img")
                        ?: document.selectFirst("img.wp-post-image")
                        ?: document.selectFirst("img")
                )?.resolveLazyImg()
            )
            ?: ""

        val description = document
            .selectFirst("div.entry-content[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()
            ?: document.selectFirst("div[itemprop=description]")?.text()?.trim()

        val genres = document.select("div.genxed a, span.genxed a, .spe .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val typeText = (
            document.selectFirst("div.typez, .spe, .infox .spe, div.anime-info .type")
                ?.text()?.lowercase() ?: ""
            ) + " " + title.lowercase()

        val isMovie = typeText.contains("movie", ignoreCase = true)

        if (isMovie) {
            val watchHref = document.selectFirst(EPISODE_LINK_SELECTOR)
                ?.attr("href")?.trim() ?: url

            return newMovieLoadResponse(title, url, TvType.Movie, watchHref) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        var epListElements = document.select(EPISODE_LIST_SELECTOR)
        if (epListElements.isEmpty()) {
            val epPage = document.selectFirst(EPISODE_LINK_SELECTOR)?.attr("href")?.trim()
            if (!epPage.isNullOrBlank()) {
                epListElements = try {
                    app.get(fixUrl(epPage), headers = defaultHeaders).document
                        .select(EPISODE_LIST_SELECTOR)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    org.jsoup.select.Elements()
                }
            }
        }

        val episodes = epListElements.mapNotNull { info ->
            val href = info.selectFirst("a[href]")?.attr("href")?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val epNumText = info.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val rawTitle = info.selectFirst(".epl-title")?.text()?.trim()
                ?: info.selectFirst("a span")?.text()?.trim()
                ?: info.selectFirst("a")?.text()?.trim() ?: ""
            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            val combinedText = "$epNumText $rawTitle"

            val seasonNum = ProviderRx.seasonNum.find(combinedText)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2] }.toIntOrNull()
            }

            val epNum = ProviderRx.episodeNum.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: ProviderRx.rangeTo.find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: ProviderRx.rangeParen.find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: ProviderRx.rangeDash.find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: ProviderRx.digits.find(epNumText)?.value?.toIntOrNull()
                ?: ProviderRx.digits.find(rawTitle)?.value?.toIntOrNull()

            val cleanName = rawTitle.replace(ProviderRx.episodePre, "").trim()
                .ifBlank { rawTitle.ifBlank { "Episode" } }

            newEpisode(fixUrl(href)) {
                this.name = cleanName
                this.posterUrl = poster
                if (seasonNum != null) this.season = seasonNum
                if (epNum != null) this.episode = epNum
                if (dateText != null) {
                    this.addDate(dateText, format = "MMMM d, yyyy")
                    this.description = dateText
                }
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
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

        val emittedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val processedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val processedDmIds = Collections.synchronizedSet(mutableSetOf<String>())
        val processedGdriveIds = Collections.synchronizedSet(mutableSetOf<String>())
        val emitCount = AtomicInteger(0)

        val firstEmit = CompletableDeferred<Unit>()

        val countingCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                emitCount.incrementAndGet()
                callback.invoke(link)
                if (!firstEmit.isCompleted) firstEmit.complete(Unit)
            }
        }

        fun getIframeSrc(iframe: Element): String {
            val src = iframe.attr("src")
            if (src.isNotBlank()) return src
            val dSrc = iframe.attr("data-src")
            if (dSrc.isNotBlank()) return dSrc
            val lSrc = iframe.attr("data-litespeed-src")
            if (lSrc.isNotBlank()) return lSrc
            return iframe.attr("data-lazy-src")
        }

        fun safeBase64Decode(value: String): String? = try {
            String(Base64.decode(value, Base64.DEFAULT))
        } catch (_: Exception) {
            try {
                String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP))
            } catch (_: Exception) {
                null
            }
        }

        suspend fun resolveGdriveStream(fileId: String): String? {
            val initialUrl =
                "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0"

            val resp1 = try {
                app.get(
                    initialUrl,
                    referer = "https://drive.google.com/",
                    allowRedirects = false,
                    headers = gdriveHeaders
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }

            if (resp1.code in 300..399) {
                val loc = resp1.headers["Location"] ?: resp1.headers["location"] ?: return null
                return if (loc.startsWith("http")) loc
                       else "https://drive.usercontent.google.com$loc"
            }

            val html = try { resp1.text } catch (_: Exception) { "" }
            if (html.isBlank()) return null

            val uuid = ProviderRx.gdriveUuid1.find(html)?.groupValues?.get(1)
                ?: ProviderRx.gdriveUuid2.find(html)?.groupValues?.get(1)
                ?: return null

            val confirmUrl =
                "https://drive.usercontent.google.com/download?id=$fileId" +
                "&export=download&confirm=t&uuid=$uuid"

            val resp2 = try {
                app.get(
                    confirmUrl,
                    referer = "https://drive.google.com/",
                    allowRedirects = false,
                    headers = gdriveHeaders
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return confirmUrl
            }

            if (resp2.code in 300..399) {
                val loc = resp2.headers["Location"] ?: resp2.headers["location"]
                if (!loc.isNullOrBlank()) {
                    return if (loc.startsWith("http")) loc
                           else "https://drive.usercontent.google.com$loc"
                }
            }

            return confirmUrl
        }

        suspend fun handleGoogleDrive(cleanUrl: String): Boolean {
            val fileId = ProviderRx.gdriveFileId.find(cleanUrl)?.groupValues?.get(1)
                ?: ProviderRx.gdriveQueryId.find(cleanUrl)?.groupValues?.get(1)
                ?: return false

            if (!processedGdriveIds.add(fileId)) return false

            val resolved = resolveGdriveStream(fileId) ?: return false

            val before = emitCount.get()
            countingCallback(
                newExtractorLink("Google Drive", "Google Drive", resolved, ExtractorLinkType.VIDEO) {
                    this.referer = "https://drive.google.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to defaultUserAgent,
                        "Accept" to "*/*"
                    )
                }
            )
            return emitCount.get() > before
        }

        suspend fun handleUrl(rawUrl: String, ref: String) {
            val cleanUrl = try { fixUrl(rawUrl) } catch (_: Exception) { return }
            if (!cleanUrl.startsWith("http")) return
            if (!processedUrls.add(cleanUrl)) return
            if (BLACKLIST_HOSTS.any { cleanUrl.contains(it, true) }) return

            try {
                if (cleanUrl.contains("drive.google.com", true) ||
                    cleanUrl.contains("drive.usercontent.google.com", true)
                ) {
                    val before = emitCount.get()
                    if (handleGoogleDrive(cleanUrl) && emitCount.get() > before) return
                }

                if (cleanUrl.contains("galaxydonghua.xyz", true)) {
                    val before = emitCount.get()
                    safeExtract { GalaxyDonghua().getUrl(cleanUrl, "$mainUrl/", subtitleCallback, countingCallback) }
                    if (emitCount.get() > before) return
                }

                if (cleanUrl.contains("skylineai.cloud", true)) {
                    val before = emitCount.get()
                    safeExtract { SkylineAI().getUrl(cleanUrl, ref, subtitleCallback, countingCallback) }
                    if (emitCount.get() > before) return
                }

                if (cleanUrl.contains("ghbrisk.com", true)) {
                    val before = emitCount.get()
                    safeExtract { Ghbrisk().getUrl(cleanUrl, ref, subtitleCallback, countingCallback) }
                    if (emitCount.get() > before) return
                }

                if (cleanUrl.contains("geo.dailymotion.com/player", true)) {
                    val videoId = ProviderRx.dmVideoIdGeo.find(cleanUrl)?.groupValues?.get(1)
                    if (videoId != null && processedDmIds.add(videoId)) {
                        val before = emitCount.get()
                        safeExtract {
                            val apiUrl = "https://geo.dailymotion.com/videos/$videoId"
                            val reqHeaders = mapOf(
                                "User-Agent" to defaultUserAgent,
                                "Referer" to cleanUrl,
                                "Accept" to "application/json",
                                "x-dm-geo-embedder" to mainUrl
                            )
                            val apiRes = app.get(apiUrl, headers = reqHeaders).text

                            val streamUrl = ProviderRx.dmM3u8.find(apiRes)?.groupValues?.get(1)

                            if (!streamUrl.isNullOrBlank()) {
                                val m3u8Url = streamUrl.replace("\\/", "/")
                                countingCallback(newExtractorLink(
                                    "Dailymotion", "Dailymotion", m3u8Url, ExtractorLinkType.M3U8
                                ) {
                                    this.referer = cleanUrl
                                    this.headers = mapOf(
                                        "User-Agent" to defaultUserAgent,
                                        "Origin" to "https://geo.dailymotion.com",
                                        "Referer" to cleanUrl
                                    )
                                    this.quality = Qualities.Unknown.value
                                })
                            }
                        }

                        if (emitCount.get() == before) {
                            safeExtract {
                                loadExtractor(
                                    "https://www.dailymotion.com/embed/video/$videoId",
                                    ref, subtitleCallback, countingCallback
                                )
                            }
                        }
                        if (emitCount.get() > before) return
                    }
                } else if (cleanUrl.contains("dailymotion.com", true) ||
                           cleanUrl.contains("dai.ly", true)
                ) {
                    val videoId = ProviderRx.dmVideoIdStd.find(cleanUrl)?.groupValues?.get(1)
                    if (videoId == null || processedDmIds.add(videoId)) {
                        val before = emitCount.get()
                        safeExtract { loadExtractor(cleanUrl, ref, subtitleCallback, countingCallback) }
                        if (emitCount.get() > before) return
                    }
                }

                val beforeGeneric = emitCount.get()
                safeExtract { loadExtractor(cleanUrl, referer = ref, subtitleCallback, countingCallback) }
                if (emitCount.get() > beforeGeneric) return

                if (cleanUrl.contains(".m3u8", true)) {
                    safeExtract {
                        M3u8Helper.generateM3u8("Generic HLS", cleanUrl, ref)
                            .forEach { countingCallback(it) }
                    }
                    if (emitCount.get() > beforeGeneric) return
                } else if (cleanUrl.contains(".mp4", true)) {
                    countingCallback(
                        newExtractorLink("Generic MP4", "Generic MP4", cleanUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ref
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        suspend fun processDecodedHtml(decoded: String, ref: String) {
            Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) handleUrl(src, ref)
            }
            ProviderRx.anyUrl.findAll(decoded).forEach { m ->
                handleUrl(m.value, ref)
            }
        }

        val mirrorOptions = document.select(
            "select.mirror option, .mobius option, select#mirror option, select[name=mirror] option"
        )
        val serverListItems = document.select(
            ".server_list li, ul.episodes li, .mirror_link, .mirrors li"
        )

        val sourceJobs: List<suspend () -> Unit> = buildList {
            for (option in mirrorOptions) {
                val value = option.attr("value").trim()
                if (value.isBlank()) continue
                add {
                    if (value.startsWith("http") || value.startsWith("//")) {
                        handleUrl(value, data)
                    } else {
                        val decoded = safeBase64Decode(value)
                        if (!decoded.isNullOrBlank()) processDecodedHtml(decoded, data)
                    }
                }
            }
            for (el in serverListItems) {
                val videoAttr = el.attr("data-video")
                    .ifBlank { el.attr("data-src") }
                    .ifBlank { el.attr("data-embed") }
                if (videoAttr.isBlank()) continue
                add {
                    if (videoAttr.startsWith("http") || videoAttr.startsWith("//")) {
                        handleUrl(videoAttr, data)
                    } else if (videoAttr.length > 20) {
                        val decoded = safeBase64Decode(videoAttr)
                        if (!decoded.isNullOrBlank()) {
                            if (decoded.startsWith("http")) handleUrl(decoded, data)
                            else processDecodedHtml(decoded, data)
                        }
                    }
                }
            }
        }

        if (sourceJobs.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) handleUrl(src, data)
            }
            return emitCount.get() > 0
        }

        val semaphore = Semaphore(MAX_CONCURRENT_EXTRACTORS)

        coroutineScope {
            val jobs = sourceJobs.map { work ->
                launch {
                    try {
                        withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                            semaphore.withPermit { work() }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
            }

            val allDone = CompletableDeferred<Unit>()
            val waiter = launch {
                jobs.forEach { it.join() }
                allDone.complete(Unit)
            }

            select<Unit> {
                firstEmit.onAwait { }
                allDone.onAwait { }
            }

            if (firstEmit.isCompleted && !allDone.isCompleted) {
                withTimeoutOrNull(GRACE_AFTER_FIRST_EMIT_MS) {
                    jobs.forEach { it.join() }
                }
            }

            jobs.forEach { it.cancel() }
            waiter.cancel()
        }

        return emitCount.get() > 0
    }
}
