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

    private val defaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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
        val allItems = mutableListOf<SearchResponse>()

        for (page in 1..2) {
            try {
                val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
                val docs = app.get(url, headers = defaultHeaders).document
                    .select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                    .mapNotNull { it.toSearchResult() }
                allItems.addAll(docs)
            } catch (_: Exception) {}
        }
        return allItems.distinctBy { it.url }
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
            val epPage = document.selectFirst(".episodelist li > a[href], .eplister li > a[href]")
                ?.attr("href")?.trim()
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
            val href = info.selectFirst("a[href]")?.attr("href")?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val epNumText = info.selectFirst(".epl-num")?.text()?.trim() ?: ""
            val rawTitle = info.selectFirst(".epl-title")?.text()?.trim()
                ?: info.selectFirst("a span")?.text()?.trim()
                ?: info.selectFirst("a")?.text()?.trim() ?: ""
            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            val combinedText = "$epNumText $rawTitle"

            val seasonNum = Regex("(?i)(?:season\\s*(\\d+)|s(\\d+))").find(combinedText)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2] }.toIntOrNull()
            }

            val epNum = Regex("(?i)(?:episode|ep)\\s*(\\d+)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)\\s*(?:to|-)\\s*\\d+").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\\((\\d+)\\s*(?:to|-)\\s*\\d+\\)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)-(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()
                ?: Regex("\\d+").find(rawTitle)?.value?.toIntOrNull()

            val cleanName = rawTitle.replace(Regex("(?i)^\\s*Episode\\s*"), "").trim()
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
        val processedGdriveIds = Collections.synchronizedSet(mutableSetOf<String>())

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

        /**
         * Robust Google Drive handler.
         *
         * Why the old approach failed:
         *  - Modern GDrive preview pages build the .googlevideo.com playback URL in JS,
         *    so it never appears in the initial HTML.
         *  - The third-party proxies (gdriveplayer.to etc.) returned loadExtractor=true
         *    without emitting any link, producing false-positive success.
         *  - Scraping itemJson from /preview only works for a signed-in browser session;
         *    an anonymous HTTP fetch often gets a stripped response (as seen in the
         *    latest logcat where "Could not find any direct download URL" was logged).
         *
         * New strategy:
         *  1. Try the built-in extractor on the original URL.
         *  2. Build the standard public direct-download URLs from the file id and try
         *     each one through the built-in extractors. These endpoints work for any
         *     file that is shared publicly / "anyone with the link".
         *  3. Emit the direct usercontent URL as a generic VIDEO link as a last resort
         *     so the player can follow the redirect chain itself.
         *  4. Only if all that fails, fetch /preview and scrape with a broad regex.
         */
        suspend fun handleGoogleDrive(cleanUrl: String, ref: String): Boolean {
            if (!cleanUrl.contains("drive.google.com", ignoreCase = true) &&
                !cleanUrl.contains("drive.usercontent.google.com", ignoreCase = true)
            ) return false

            // 1) Built-in extractor against whatever URL we were handed
            val beforeInitial = emitCount.get()
            try {
                loadExtractor(cleanUrl, referer = ref, subtitleCallback, countingCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Built-in extractor on original URL threw: ${e.message}")
            }
            if (emitCount.get() > beforeInitial) {
                Log.e(TAG, "Built-in extractor succeeded on original URL.")
                return true
            }

            // 2) Extract file id
            val fileId = Regex("/file/d/([a-zA-Z0-9_-]{10,})").find(cleanUrl)?.groupValues?.get(1)
                ?: Regex("[?&]id=([a-zA-Z0-9_-]{10,})").find(cleanUrl)?.groupValues?.get(1)

            if (fileId == null) {
                Log.e(TAG, "FAILED to extract File ID from GDrive URL.")
                return false
            }

            if (!processedGdriveIds.add(fileId)) {
                Log.e(TAG, "GDrive file $fileId already processed in this call, skipping.")
                return emitCount.get() > 0
            }

            Log.e(TAG, "Extracted File ID: $fileId")

            // 3) Try constructed public direct-download URLs through the extractor system
            val directCandidates = listOf(
                "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0",
                "https://drive.usercontent.google.com/u/0/uc?id=$fileId&export=download",
                "https://drive.usercontent.google.com/uc?id=$fileId&export=download",
                "https://drive.google.com/uc?export=download&id=$fileId",
                "https://drive.google.com/uc?id=$fileId&export=download"
            )

            for (direct in directCandidates) {
                Log.e(TAG, "Trying direct download candidate: $direct")
                val before = emitCount.get()
                try {
                    loadExtractor(direct, referer = "https://drive.google.com/", subtitleCallback, countingCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "Extractor threw on $direct: ${e.message}")
                }
                if (emitCount.get() > before) {
                    Log.e(TAG, "Extractor emitted via direct candidate: $direct")
                    return true
                }

                // If extractors don't handle it, emit usercontent URLs as generic VIDEO
                if (direct.contains("drive.usercontent.google.com")) {
                    countingCallback(
                        newExtractorLink("Google Drive", "Google Drive", direct, ExtractorLinkType.VIDEO) {
                            this.referer = "https://drive.google.com/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to defaultUserAgent,
                                "Accept" to "*/*"
                            )
                        }
                    )
                    if (emitCount.get() > before) {
                        Log.e(TAG, "Emitted direct usercontent URL as generic VIDEO: $direct")
                        return true
                    }
                }
            }

            // 4) Last resort: scrape /preview with a broad file-id-based regex
            Log.e(TAG, "Constructed URLs failed. Fetching preview HTML as last resort...")
            val previewUrl = "https://drive.google.com/file/d/$fileId/preview"
            val html = try {
                app.get(
                    previewUrl,
                    headers = mapOf(
                        "User-Agent" to defaultUserAgent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Referer" to "https://drive.google.com/"
                    )
                ).text
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch preview page: ${e.message}")
                null
            }

            if (!html.isNullOrBlank()) {
                val patterns = listOf(
                    Regex("""https://drive\.usercontent\.google\.com/u/\d+/uc\?id=$fileId[^"'\\\s]*"""),
                    Regex("""https://drive\.usercontent\.google\.com/[^"'\\\s]*?id=$fileId[^"'\\\s]*"""),
                    Regex("""https://drive\.google\.com/uc\?[^"'\\\s]*?id=$fileId[^"'\\\s]*"""),
                    Regex("""https://[^"'\\\s]*?usercontent[^"'\\\s]*?id=$fileId[^"'\\\s]*""")
                )
                for (p in patterns) {
                    val found = p.find(html)?.value
                    if (!found.isNullOrBlank()) {
                        val clean = found.replace("\\u0026", "&")
                            .replace("\\/", "/")
                            .replace("\\u003d", "=")
                        Log.e(TAG, "Found direct URL in preview HTML: $clean")
                        val before = emitCount.get()
                        try {
                            loadExtractor(
                                clean,
                                referer = "https://drive.google.com/",
                                subtitleCallback,
                                countingCallback
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Extractor threw on scraped URL: ${e.message}")
                        }
                        if (emitCount.get() > before) return true

                        countingCallback(
                            newExtractorLink("Google Drive", "Google Drive", clean, ExtractorLinkType.VIDEO) {
                                this.referer = "https://drive.google.com/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to defaultUserAgent)
                            }
                        )
                        if (emitCount.get() > before) return true
                    }
                }
                Log.e(TAG, "No direct URL matched in preview HTML (length=${html.length}).")
            }

            Log.e(TAG, "handleGoogleDrive finished. No links emitted.")
            return false
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
                // Chiki-specific extractors first
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

                // Google Drive
                if (cleanUrl.contains("drive.google.com", true) ||
                    cleanUrl.contains("drive.usercontent.google.com", true)
                ) {
                    val before = emitCount.get()
                    if (handleGoogleDrive(cleanUrl, ref) && emitCount.get() > before) {
                        foundFlag.set(1)
                        return
                    }
                }

                // Dailymotion (geo embed)
                if (cleanUrl.contains("geo.dailymotion.com/player", true)) {
                    val videoId = Regex("video=([a-zA-Z0-9_-]+)").find(cleanUrl)?.groupValues?.get(1)
                    if (videoId != null && processedDmIds.add(videoId)) {
                        Log.e(TAG, "Processing Dailymotion ID: $videoId")
                        val before = emitCount.get()
                        try {
                            val apiUrl = "https://geo.dailymotion.com/videos/$videoId"
                            val reqHeaders = mapOf(
                                "User-Agent" to defaultUserAgent,
                                "Referer" to cleanUrl,
                                "Accept" to "application/json",
                                "x-dm-geo-embedder" to mainUrl
                            )
                            val apiRes = app.get(apiUrl, headers = reqHeaders).text

                            val streamUrl = Regex("[\"']url[\"']\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']")
                                .find(apiRes)?.groupValues?.get(1)

                            if (!streamUrl.isNullOrBlank()) {
                                val m3u8Url = streamUrl.replace("\\/", "/")
                                Log.e(TAG, "Pushing native Dailymotion HLS stream: $m3u8Url")
                                countingCallback(
                                    newExtractorLink(
                                        "Dailymotion", "Dailymotion", m3u8Url, ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = cleanUrl
                                        this.headers = mapOf(
                                            "User-Agent" to defaultUserAgent,
                                            "Origin" to "https://geo.dailymotion.com",
                                            "Referer" to cleanUrl
                                        )
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Dailymotion API scrape failed: ${e.message}")
                        }

                        if (emitCount.get() == before) {
                            Log.e(TAG, "Fallback to direct Dailymotion extractor")
                            try {
                                loadExtractor(
                                    "https://www.dailymotion.com/embed/video/$videoId",
                                    ref, subtitleCallback, countingCallback
                                )
                            } catch (_: Exception) {}
                        }
                        if (emitCount.get() > before) {
                            foundFlag.set(1)
                            return
                        }
                    }
                } else if (cleanUrl.contains("dailymotion.com", true) ||
                           cleanUrl.contains("dai.ly", true)
                ) {
                    val videoId = Regex("(?:video/|dai\\.ly/|embed/video/)([a-zA-Z0-9_-]+)")
                        .find(cleanUrl)?.groupValues?.get(1)
                    if (videoId == null || processedDmIds.add(videoId)) {
                        val before = emitCount.get()
                        try {
                            loadExtractor(cleanUrl, ref, subtitleCallback, countingCallback)
                        } catch (_: Exception) {}
                        if (emitCount.get() > before) {
                            foundFlag.set(1)
                            return
                        }
                    }
                }

                // Generic built-in extractor pass
                val beforeGeneric = emitCount.get()
                val ok = try {
                    loadExtractor(cleanUrl, referer = ref, subtitleCallback, countingCallback)
                } catch (_: Exception) { false }

                if (ok || emitCount.get() > beforeGeneric) {
                    foundFlag.set(1)
                    return
                }

                if (cleanUrl.contains(".m3u8", true)) {
                    Log.e(TAG, "Generating generic M3u8 links for: $cleanUrl")
                    M3u8Helper.generateM3u8("Generic HLS", cleanUrl, ref).forEach { countingCallback(it) }
                    foundFlag.set(1)
                    return
                } else if (cleanUrl.contains(".mp4", true)) {
                    Log.e(TAG, "Yielding generic MP4 link: $cleanUrl")
                    countingCallback(
                        newExtractorLink("Generic MP4", "Generic MP4", cleanUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ref
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundFlag.set(1)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleUrl error on $cleanUrl: ${e.message}")
            }
        }

        suspend fun processDecodedHtml(decoded: String, ref: String) {
            Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) handleUrl(src, ref)
            }
            Regex("https?://[^\\s\"'<>\\\\)]+").findAll(decoded).forEach { m ->
                handleUrl(m.value, ref)
            }
        }

        val mirrorOptions = document.select(
            "select.mirror option, .mobius option, select#mirror option, select[name=mirror] option"
        )
        val serverListItems = document.select(
            ".server_list li, ul.episodes li, .mirror_link, .mirrors li"
        )

        val extractionJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        coroutineScope {
            for (option in mirrorOptions) {
                val value = option.attr("value").trim()
                if (value.isNotBlank()) {
                    val job = async {
                        if (value.startsWith("http") || value.startsWith("//")) {
                            handleUrl(value, data)
                        } else {
                            val decoded = safeBase64Decode(value)
                            if (!decoded.isNullOrBlank()) {
                                processDecodedHtml(decoded, data)
                            }
                        }
                        Unit
                    }
                    extractionJobs.add(job)
                }
            }

            for (el in serverListItems) {
                var videoAttr = el.attr("data-video")
                if (videoAttr.isBlank()) videoAttr = el.attr("data-src")
                if (videoAttr.isBlank()) videoAttr = el.attr("data-embed")

                if (videoAttr.isNotBlank()) {
                    val job = async {
                        if (videoAttr.startsWith("http") || videoAttr.startsWith("//")) {
                            handleUrl(videoAttr, data)
                        } else if (videoAttr.length > 20) {
                            val decoded = safeBase64Decode(videoAttr)
                            if (!decoded.isNullOrBlank()) {
                                if (decoded.startsWith("http")) {
                                    handleUrl(decoded, data)
                                } else {
                                    processDecodedHtml(decoded, data)
                                }
                            }
                        }
                        Unit
                    }
                    extractionJobs.add(job)
                }
            }

            extractionJobs.awaitAll()
        }

        if (foundFlag.get() == 0) {
            document.select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) handleUrl(src, data)
            }
        }

        val totalEmitted = emitCount.get()
        Log.e(TAG, "==== FINISHED LOADLINKS ==== Total Links Found: $totalEmitted")
        return totalEmitted > 0
    }
}
