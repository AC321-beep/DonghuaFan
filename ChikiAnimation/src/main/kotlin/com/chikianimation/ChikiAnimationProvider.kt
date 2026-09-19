package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

class ChikiAnimationProvider : MainAPI() {

    override var mainUrl = "https://chikianimation.com"
    override var name = "ChikiAnimation"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    companion object {
        private const val TAG = "ChikiGDriveDebug"
    }

    private val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to defaultUserAgent,
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update"          to "Recently Updated",
        "anime/?status=&type=&order=popular"         to "Popular",
        "anime/?status=&type=ai+animes&order=update" to "AI Anime",
        "anime/?status=ongoing&type=&order=update"   to "Ongoing",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=movie&order=update"     to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val items = try {
            val document = app.get(url, headers = defaultHeaders).document
            document.select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
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

        val posterUrl = fixUrlNull(
            selectFirst("img.ts-post-image")?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
                    .ifEmpty { img.attr("data-lazy-src") }
                    .ifEmpty { img.attr("data-original") }
            }
                ?: selectFirst("div.limit img")?.attr("src")
                ?: selectFirst("img")?.attr("src")
        )

        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = query.trim()
        val results = coroutineScope {
            (1..2).map { page ->
                async {
                    try {
                        val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
                        app.get(url, headers = defaultHeaders).document
                            .select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                            .mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (e: Exception) {
            return null
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("div.thumb img.wp-post-image")?.attr("src")?.trim()
            ?: document.selectFirst("div.thumb img")?.attr("src")?.trim()
            ?: document.selectFirst("img.wp-post-image")?.attr("src")?.trim()
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
            val watchHref = document
                .selectFirst(".eplister li > a[href], .episodelist li > a[href]")
                ?.attr("href")?.trim() ?: url

            return newMovieLoadResponse(title, url, TvType.Movie, watchHref) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        var epListElements = document.select(".episodelist li, .eplister li")
        if (epListElements.isEmpty()) {
            val epPage = document.selectFirst(".episodelist li > a[href], .eplister li > a[href]")?.attr("href")?.trim()
            if (!epPage.isNullOrBlank()) {
                epListElements = try {
                    app.get(fixUrl(epPage), headers = defaultHeaders).document
                        .select(".episodelist li, .eplister li")
                } catch (e: Exception) {
                    org.jsoup.select.Elements()
                }
            }
        }

        val episodes = epListElements.mapNotNull { info ->
            val href = info.selectFirst("a[href]")?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val epNumText = info.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val rawTitle = info.selectFirst(".epl-title")?.text()?.trim()
                ?: info.selectFirst("a span")?.text()?.trim()
                ?: info.selectFirst("a")?.text()?.trim() ?: ""
            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val combinedText = "$epNumText $rawTitle"

            val seasonNum = Regex("""(?i)(?:season\s*(\d+)|s(\d+))""").find(combinedText)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2] }.toIntOrNull()
            }

            val epNum = Regex("""(?i)(?:episode|ep)\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*(?:to|-)\s*\d+""").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\((\d+)\s*(?:to|-)\s*\d+\)""").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-(\d+)""").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""").find(epNumText)?.value?.toIntOrNull()
                ?: Regex("""\d+""").find(rawTitle)?.value?.toIntOrNull()

            val cleanName = rawTitle.replace(Regex("""(?i)^\s*Episode\s*"""), "").trim().ifBlank { rawTitle.ifBlank { "Episode" } }

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
        Log.e(TAG, "==== STARTING LOADLINKS ====")
        val document = try {
            app.get(data, headers = defaultHeaders).document
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load episode page: ${e.message}")
            return false
        }

        val emittedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val processedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val processedDmIds = Collections.synchronizedSet(mutableSetOf<String>())

        val emitCount = AtomicInteger(0)
        val foundFlag = AtomicInteger(0)

        val countingCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                val count = emitCount.incrementAndGet()
                Log.e(TAG, ">>> SUCCESS! Link Emitted #$count | Source: ${link.name} | URL: ${link.url}")
                callback.invoke(link)
            } else {
                Log.e(TAG, "Duplicate link blocked: ${link.url}")
            }
        }

        fun getIframeSrc(iframe: Element): String = iframe.attr("src").ifBlank {
            iframe.attr("data-src").ifBlank {
                iframe.attr("data-litespeed-src").ifBlank {
                    iframe.attr("data-lazy-src")
                }
            }
        }

        fun safeBase64Decode(value: String): String? = try {
            String(Base64.decode(value, Base64.DEFAULT))
        } catch (_: Exception) {
            try { String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)) } catch (_: Exception) { null }
        }

        suspend fun handleGoogleDrive(cleanUrl: String, ref: String): Boolean {
            if (!cleanUrl.contains("drive.google.com", ignoreCase = true)) return false
            Log.e(TAG, "Found GDrive URL: $cleanUrl")

            val fileId = Regex("""/file/d/([a-zA-Z0-9_-]{10,})""").find(cleanUrl)?.groupValues?.get(1)
                ?: Regex("""[?&]id=([a-zA-Z0-9_-]{10,})""").find(cleanUrl)?.groupValues?.get(1)
                
            if (fileId == null) {
                Log.e(TAG, "FAILED to extract File ID from GDrive URL.")
                return false
            }

            Log.e(TAG, "Extracted File ID: $fileId")
            
            try {
                Log.e(TAG, "Attempting direct .googlevideo.com extraction to bypass HTML quotas...")
                val previewUrl = "https://drive.google.com/file/d/$fileId/preview"
                // Using full browser headers to avoid Google throwing 403 on the preview page
                val html = app.get(previewUrl, headers = mapOf("User-Agent" to defaultUserAgent, "Accept" to "text/html")).text
                
                val rawStream = Regex("""(https://[^\s"']+\.googlevideo\.com/videoplayback\?[^\s"']+)""")
                    .find(html)?.groupValues?.get(1)
                    
                if (rawStream != null) {
                    val cleanStream = rawStream.replace("\\u0026", "&").replace("\\/", "/")
                    Log.e(TAG, "SUCCESS! Extracted raw Googlevideo stream: ${cleanStream.take(60)}...")
                    countingCallback(newExtractorLink(this.name, "Google Drive HD", cleanStream, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to defaultUserAgent)
                    })
                    return true
                } else {
                    Log.e(TAG, "Direct extraction failed: Could not find videoplayback URL.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct extraction threw exception: ${e.message}")
            }

            val driveViewUrl = "https://drive.google.com/file/d/$fileId/view"
            val encodedDriveUrl = try { URLEncoder.encode(driveViewUrl, "UTF-8") } catch (_: Exception) { driveViewUrl }
            val proxyUrls = listOf(
                "https://gdriveplayer.to/embed2.php?link=$encodedDriveUrl",
                "https://databasegdriveplayer.co/player.php?link=$encodedDriveUrl",
                "https://anime.gdriveplayer.to/embed2.php?link=$encodedDriveUrl"
            )

            var foundProxy = false
            for (proxy in proxyUrls) {
                Log.e(TAG, "Attempting Proxy: $proxy")
                try {
                    if (loadExtractor(proxy, referer = ref, subtitleCallback, countingCallback)) {
                        Log.e(TAG, "Proxy SUCCEEDED: $proxy")
                        foundProxy = true
                    } else {
                        Log.e(TAG, "Proxy returned FALSE.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Proxy threw exception: ${e.message}")
                }
            }

            Log.e(TAG, "handleGoogleDrive finished. Returning: $foundProxy")
            return foundProxy
        }

        suspend fun handleUrl(rawUrl: String, ref: String, depth: Int = 0) {
            if (depth > 1) return
            val cleanUrl = try { fixUrl(rawUrl) } catch (_: Exception) { return }
            if (!cleanUrl.startsWith("http")) return

            if (!processedUrls.add(cleanUrl)) return

            if (cleanUrl.contains("youtube", true) ||
                cleanUrl.contains("disqus", true) ||
                cleanUrl.contains("googlesyndication", true) ||
                cleanUrl.contains("doubleclick", true)
            ) return

            Log.e(TAG, "Checking Extractor for URL: $cleanUrl")

            try {
                if (cleanUrl.contains("skylineai.cloud", true)) {
                    val before = emitCount.get()
                    SkylineAI().getUrl(cleanUrl, ref, subtitleCallback, countingCallback)
                    if (emitCount.get() > before) {
                        foundFlag.set(1)
                        return
                    }
                }

                if (cleanUrl.contains("ghbrisk.com", true)) {
                    val before = emitCount.get()
                    Ghbrisk().getUrl(cleanUrl, ref, subtitleCallback, countingCallback)
                    if (emitCount.get() > before) {
                        foundFlag.set(1)
                        return
                    }
                }

                if (handleGoogleDrive(cleanUrl, ref)) {
                    foundFlag.set(1)
                    return
                }

                if (cleanUrl.contains("geo.dailymotion.com/player", true)) {
                    val videoId = Regex("""video=([a-zA-Z0-9_-]+)""").find(cleanUrl)?.groupValues?.get(1)
                    if (videoId != null && processedDmIds.add(videoId)) {
                        Log.e(TAG, "Processing Dailymotion ID: $videoId")
                        val before = emitCount.get()
                        try {
                            // Fixing Dailymotion 403 Error by syncing User-Agent perfectly
                            val apiUrl = "https://geo.dailymotion.com/videos/$videoId"
                            val reqHeaders = mapOf(
                                "User-Agent" to defaultUserAgent,
                                "Referer" to cleanUrl, 
                                "Accept" to "application/json", 
                                "x-dm-geo-embedder" to mainUrl
                            )
                            val apiRes = app.get(apiUrl, headers = reqHeaders).text
                            val streamUrl = Regex("""["']url["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(apiRes)?.groupValues?.get(1)

                            if (!streamUrl.isNullOrBlank()) {
                                val m3u8Url = streamUrl.replace("\\/", "/")
                                Log.e(TAG, "Pushing native Dailymotion HLS stream to keep subtitles perfectly synced: $m3u8Url")
                                
                                countingCallback(newExtractorLink("Dailymotion", "Dailymotion", m3u8Url, ExtractorLinkType.M3U8) {
                                    this.referer = cleanUrl
                                    // Injecting same headers into ExoPlayer prevents the 403 CDN crash
                                    this.headers = mapOf(
                                        "User-Agent" to defaultUserAgent,
                                        "Origin" to "https://geo.dailymotion.com",
                                        "Referer" to cleanUrl
                                    )
                                    this.quality = Qualities.Unknown.value
                                })
                            }
                        } catch (e: Exception) { 
                            Log.e(TAG, "Dailymotion API scrape failed: ${e.message}")
                        }

                        if (emitCount.get() == before) {
                            Log.e(TAG, "Fallback to direct Dailymotion extractor")
                            try { loadExtractor("https://www.dailymotion.com/embed/video/$videoId", ref, subtitleCallback, countingCallback) } catch (_: Exception) { }
                        }
                        if (emitCount.get() > before) {
                            foundFlag.set(1)
                            return
                        }
                    }
                } else if (cleanUrl.contains("dailymotion.com", true) || cleanUrl.contains("dai.ly", true)) {
                    val videoId = Regex("""(?:video/|dai\.ly/|embed/video/)([a-zA-Z0-9_-]+)""").find(cleanUrl)?.groupValues?.get(1)
                    if (videoId == null || processedDmIds.add(videoId)) {
                        val before = emitCount.get()
                        try { loadExtractor(cleanUrl, ref, subtitleCallback, countingCallback) } catch (_: Exception) { }
                        if (emitCount.get() > before) {
                            foundFlag.set(1)
                            return
                        }
                    }
                }

                val before = emitCount.get()
                val ok = try {
                    loadExtractor(cleanUrl, referer = ref, subtitleCallback, countingCallback)
                } catch (
