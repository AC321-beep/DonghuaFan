package com.chikianimation

import android.util.Base64
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

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update"          to "Recently Updated",
        "anime/?status=&type=&order=popular"         to "Popular",
        "anime/?status=&type=ai+animes&order=update" to "AI Anime",
        "anime/?status=ongoing&type=&order=update"   to "Ongoing",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=movie&order=update"     to "Movies"
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
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
        val document = try {
            app.get(data, headers = defaultHeaders).document
        } catch (e: Exception) {
            return false
        }

        val emittedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val processedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val processedDmIds = Collections.synchronizedSet(mutableSetOf<String>())

        val emitCount = AtomicInteger(0)
        val foundFlag = AtomicInteger(0)

        val countingCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                emitCount.incrementAndGet()
                callback.invoke(link)
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

            val fileId = Regex("""/file/d/([a-zA-Z0-9_-]{10,})""").find(cleanUrl)?.groupValues?.get(1)
                ?: Regex("""[?&]id=([a-zA-Z0-9_-]{10,})""").find(cleanUrl)?.groupValues?.get(1)
                ?: return false

            // 1. Direct Googlevideo Stream Extraction (Bypasses Quota HTML pages)
            try {
                val previewUrl = "https://drive.google.com/file/d/$fileId/preview"
                val html = app.get(previewUrl).text
                
                val rawStream = Regex("""(https://[^\s"']+\.googlevideo\.com/videoplayback\?[^\s"']+)""")
                    .find(html)?.groupValues?.get(1)
                    
                if (rawStream != null) {
                    val cleanStream = rawStream.replace("\\u0026", "&").replace("\\/", "/")
                    countingCallback(newExtractorLink(this.name, "Google Drive HD", cleanStream, ExtractorLinkType.VIDEO) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            } catch (_: Exception) {}

            // 2. Try expanded list of proxy players if direct extraction fails
            val driveViewUrl = "https://drive.google.com/file/d/$fileId/view"
            val encodedDriveUrl = try { URLEncoder.encode(driveViewUrl, "UTF-8") } catch (_: Exception) { driveViewUrl }
            val proxyUrls = listOf(
                "https://gdriveplayer.to/embed2.php?link=$encodedDriveUrl",
                "https://gdriveplayer.me/embed2.php?link=$encodedDriveUrl",
                "https://databasegdriveplayer.co/player.php?link=$encodedDriveUrl",
                "https://databasegdriveplayer.xyz/player.php?link=$encodedDriveUrl",
                "https://anime.gdriveplayer.to/embed2.php?link=$encodedDriveUrl",
                "https://gplayer.us/embed2.php?link=$encodedDriveUrl",
                "https://gdriveplayer.io/embed2.php?link=$encodedDriveUrl"
            )

            for (proxy in proxyUrls) {
                try {
                    if (loadExtractor(proxy, referer = ref, subtitleCallback, countingCallback)) {
                        return true
                    }
                } catch (_: Exception) {}
            }

            // 3. Absolute Last Resort: Direct Download API Probe
            try {
                val directUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                val response = app.get(directUrl, allowRedirects = false)
                
                if (response.code in 300..399) {
                    val redirectUrl = response.headers["Location"]
                    if (!redirectUrl.isNullOrBlank() && redirectUrl.startsWith("http")) {
                        countingCallback(newExtractorLink(this.name, "${this.name} - Direct", redirectUrl, ExtractorLinkType.VIDEO) {
                            this.referer = "https://drive.google.com/"
                            this.quality = Qualities.Unknown.value
                        })
                        return true
                    }
                }
            } catch (_: Exception) {}

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
                        val before = emitCount.get()
                        try {
                            val apiUrl = "https://geo.dailymotion.com/videos/$videoId"
                            val reqHeaders = mapOf("Referer" to cleanUrl, "Accept" to "application/json", "x-dm-geo-embedder" to mainUrl)
                            val apiRes = app.get(apiUrl, headers = reqHeaders).text
                            val streamUrl = Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(apiRes)?.groupValues?.get(1)

                            if (!streamUrl.isNullOrBlank()) {
                                val m3u8Url = streamUrl.replace("\\/", "/")
                                // Send ONLY the master playlist directly to ensure native HLS subtitle sync 
                                countingCallback(newExtractorLink("Dailymotion", "Dailymotion", m3u8Url, ExtractorLinkType.M3U8) {
                                    this.referer = cleanUrl
                                    this.quality = Qualities.Unknown.value
                                })
                            }
                        } catch (_: Exception) { }

                        if (emitCount.get() == before) {
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
                } catch (_: Exception) { false }

                if (ok || emitCount.get() > before) {
                    foundFlag.set(1)
                    return
                }

                if (cleanUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8("Generic HLS", cleanUrl, ref).forEach { countingCallback(it) }
                    foundFlag.set(1)
                    return
                } else if (cleanUrl.contains(".mp4", true)) {
                    countingCallback(newExtractorLink("Generic MP4", "Generic MP4", cleanUrl, ExtractorLinkType.VIDEO) {
                        this.referer = ref
                        this.quality = Qualities.Unknown.value
                    })
                    foundFlag.set(1)
                    return
                }
            } catch (_: Exception) {}
        }

        suspend fun processDecodedHtml(decoded: String, ref: String) {
            Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) handleUrl(src, ref)
            }
            Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).forEach { m ->
                handleUrl(m.value, ref)
            }
        }

        val mirrorOptions = document.select("select.mirror option, .mobius option, select#mirror option, select[name=mirror] option")
        val serverListItems = document.select(".server_list li, ul.episodes li, .mirror_link, .mirrors li")

        coroutineScope {
            mirrorOptions.map { option ->
                asyn
