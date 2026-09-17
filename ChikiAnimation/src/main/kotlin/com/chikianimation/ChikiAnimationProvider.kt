package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

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
        "Referer"    to mainUrl,
        "Origin"     to mainUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val primaryUrl = buildPageUrl(request.data, page)
        val fallbackUrl = buildFallbackPageUrl(page)

        // 1) Try the primary archive URL
        var items = fetchMainPageItems(primaryUrl)
        if (items.isNotEmpty()) {
            Log.e(TAG, "[getMainPage] ✓ primary '${request.name}' page=$page → $primaryUrl (${items.size} items)")
            return newHomePageResponse(request.name, items, true)
        }

        // 2) Fallback to sitewide /page/N/ (used while the site archives are down)
        if (fallbackUrl != primaryUrl) {
            Log.e(TAG, "[getMainPage] primary empty for '${request.name}', trying fallback → $fallbackUrl")
            items = fetchMainPageItems(fallbackUrl)
            if (items.isNotEmpty()) {
                Log.e(TAG, "[getMainPage] ✓ fallback '${request.name}' page=$page → $fallbackUrl (${items.size} items)")
                return newHomePageResponse(request.name, items, true)
            }
        }

        Log.e(TAG, "[getMainPage] ✗ both primary and fallback failed for '${request.name}' page=$page")
        return newHomePageResponse(request.name, emptyList(), false)
    }

    private suspend fun fetchMainPageItems(url: String): List<SearchResponse> {
        return try {
            val response = app.get(url, headers = defaultHeaders)
            Log.e(TAG, "[fetchMainPageItems] HTTP ${response.code} ← $url")
            if (response.code !in 200..299) return emptyList()
            response.document
                .select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(TAG, "[fetchMainPageItems] EXCEPTION $url → ${e.message}")
            emptyList()
        }
    }

    private fun buildPageUrl(base: String, page: Int): String {
        if (page <= 1) return "$mainUrl/$base"
        return when {
            !base.contains("?") -> {
                val trimmed = base.trimEnd('/')
                "$mainUrl/$trimmed/page/$page/"
            }
            else -> "$mainUrl/$base&page=$page"
        }
    }

    /** Temporary sitewide pagination fallback: https://chikianimation.com/page/N/ */
    private fun buildFallbackPageUrl(page: Int): String {
        return if (page <= 1) mainUrl else "$mainUrl/page/$page/"
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
                        val url = if (page == 1)
                            "$mainUrl/?s=$encoded"
                        else
                            "$mainUrl/page/$page/?s=$encoded"

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
                ?.attr("href")?.trim()
                ?: url

            return newMovieLoadResponse(title, url, TvType.Movie, watchHref) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        var epListElements = document.select(".episodelist li, .eplister li")

        if (epListElements.isEmpty()) {
            val epPage = document
                .selectFirst(".episodelist li > a[href], .eplister li > a[href]")
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
                ?: info.selectFirst("a")?.text()?.trim()
                ?: ""

            val dateText = info.selectFirst(".epl-date, .date, .time")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }

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

            val cleanName = rawTitle
                .replace(Regex("""(?i)^\s*Episode\s*"""), "")
                .trim()
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
        val t0 = System.currentTimeMillis()
        Log.e(TAG, "════════ SECTION: loadLinks ════════")
        Log.e(TAG, "[loadLinks] data='$data'")

        val document = try {
            app.get(data, headers = defaultHeaders).document
        } catch (e: Exception) {
            Log.e(TAG, "[loadLinks] page fetch failed: ${e.message}")
            return false
        }

        var found = false

        fun getIframeSrc(iframe: Element): String {
            return iframe.attr("src").ifBlank {
                iframe.attr("data-src").ifBlank {
                    iframe.attr("data-litespeed-src").ifBlank {
                        iframe.attr("data-lazy-src")
                    }
                }
            }
        }

        fun safeBase64Decode(value: String): String? {
            return try {
                String(Base64.decode(value, Base64.DEFAULT))
            } catch (e: Exception) {
                try {
                    String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP))
                } catch (e2: Exception) { null }
            }
        }

        // --------------------------------------------------------------------
        //  Google Drive → playable stream
        //
        //  NOTE:  The Drive file in this provider is served by Google through
        //  YouTube's SABR / UMP pipeline:
        //
        //     POST https://rr5---sn-....c.drive.google.com/videoplayback
        //          ?source=webdrive&sabr=1&driveid=<FILE_ID>
        //     Content-Type: application/vnd.yt-ump
        //
        //  That response is *not* an mp4.  It can only be consumed by a
        //  player that speaks the SABR protocol (i.e. the YouTube iframe).
        //
        //  Therefore the only reliable way to play a Drive file inside
        //  CloudStream is to route it through a service that wraps the
        //  YouTube iframe (gdriveplayer.* mirrors) or to fetch the
        //  confirmation-protected direct download URL for small / non-SABR
        //  files.
        // --------------------------------------------------------------------
        suspend fun handleGoogleDrive(cleanUrl: String, ref: String): Boolean {
            if (!cleanUrl.contains("drive.google.com", ignoreCase = true)) return false

            val fileId = Regex("""/file/d/([a-zA-Z0-9_-]{10,})""").find(cleanUrl)?.groupValues?.get(1)
                ?: Regex("""[?&]id=([a-zA-Z0-9_-]{10,})""").find(cleanUrl)?.groupValues?.get(1)
                ?: return false

            Log.e(TAG, "[GDrive] fileId=$fileId")

            val driveViewUrl = "https://drive.google.com/file/d/$fileId/view"
            val encodedDriveUrl = try {
                URLEncoder.encode(driveViewUrl, "UTF-8")
            } catch (_: Exception) {
                driveViewUrl
            }

            // ---- 1) gdriveplayer.* mirrors (preferred - they proxy SABR) ----
            val gdrivePlayerUrls = listOf(
                "https://gdriveplayer.to/embed2.php?link=$encodedDriveUrl",
                "https://gdriveplayer.co/embed2.php?link=$encodedDriveUrl",
                "https://gdriveplayer.me/embed2.php?link=$encodedDriveUrl",
                "https://gdriveplayer.io/embed2.php?link=$encodedDriveUrl",
                "https://databasegdriveplayer.co/player.php?link=$encodedDriveUrl",
                "https://databasegdriveplayer.xyz/player.php?link=$encodedDriveUrl",
                "https://anime.gdriveplayer.to/embed2.php?link=$encodedDriveUrl"
            )

            for (gpUrl in gdrivePlayerUrls) {
                try {
                    Log.e(TAG, "[GDrive] trying mirror: $gpUrl")
                    if (loadExtractor(gpUrl, referer = ref, subtitleCallback, callback)) {
                        Log.e(TAG, "[GDrive] ✓ mirror succeeded: $gpUrl")
                        return true
                    }
                } catch (_: Exception) { }
            }

            // ---- 2) Direct Drive download / confirm flow ----
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            try {
                val directUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                val response = app.get(
                    directUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "https://drive.google.com/"
                    ),
                    allowRedirects = false
                )
                Log.e(TAG, "[GDrive] direct probe: code=${response.code} ct=${response.headers["Content-Type"]}")

                // 2a) Follow any 30x redirect straight to the CDN
                if (response.code in 300..399) {
                    val redirectUrl = response.headers["Location"]
                    if (!redirectUrl.isNullOrBlank() && redirectUrl.startsWith("http")) {
                        Log.e(TAG, "[GDrive] 30x redirect: ${redirectUrl.take(160)}")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} – Drive",
                                url = redirectUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://drive.google.com/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to ua)
                            }
                        )
                        return true
                    }
                }

                // 2b) Direct hit - file is served inline
                if (response.code == 200) {
                    val ct = response.headers["Content-Type"] ?: ""
                    if (ct.contains("video", true) || ct.contains("octet-stream", true)) {
                        Log.e(TAG, "[GDrive] 200 direct video ct=$ct")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} – Drive",
                                url = directUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://drive.google.com/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to ua)
                            }
                        )
                        return true
                    }

                    // 2c) Large-file "virus scan" confirmation page
                    if (ct.contains("text/html", true)) {
                        val html = response.text
                        val doc = Jsoup.parse(html)

                        val form = doc.selectFirst("form")
                        val action = form?.attr("action")?.takeIf { it.isNotBlank() }
                        val uuid = form?.selectFirst("input[name=uuid]")?.attr("value")?.takeIf { it.isNotBlank() }
                        val confirm = form?.selectFirst("input[name=confirm]")?.attr("value")?.takeIf { it.isNotBlank() }
                        val at = form?.selectFirst("input[name=at]")?.attr("value")?.takeIf { it.isNotBlank() }

                        if (!action.isNullOrBlank()) {
                            val params = mutableListOf<String>()
                            if (!uuid.isNullOrBlank()) params.add("uuid=$uuid")
                            if (!confirm.isNullOrBlank()) params.add("confirm=$confirm")
                            if (!at.isNullOrBlank()) params.add("at=$at")
                            params.add("id=$fileId")
                            params.add("export=download")
                            val confirmedUrl = "$action?" + params.joinToString("&")
                            Log.e(TAG, "[GDrive] confirm flow: ${confirmedUrl.take(160)}")

                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} – Drive (confirmed)",
                                    url = confirmedUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://drive.google.com/"
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("User-Agent" to ua)
                                }
                            )
                            return true
                        }
                    }
                }
            } catch (_: Exception) { }

            // ---- 3) Absolute last resort - hand the raw URL to the player ----
            try {
                Log.e(TAG, "[GDrive] last resort: emitting raw download URL")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} – Drive (direct)",
                        url = "https://drive.google.com/uc?export=download&id=$fileId",
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://drive.google.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to ua)
                    }
                )
                return true
            } catch (_: Exception) { }

            return false
        }

        suspend fun handleUrl(rawUrl: String, ref: String, depth: Int = 0) {
            if (depth > 2) return

            val cleanUrl = try { fixUrl(rawUrl) } catch (e: Exception) { return }
            if (!cleanUrl.startsWith("http")) return

            if (cleanUrl.contains("youtube", true) ||
                cleanUrl.contains("disqus", true) ||
                cleanUrl.contains("googlesyndication", true) ||
                cleanUrl.contains("doubleclick", true)
            ) return

            Log.e(TAG, "[handleUrl] (depth=$depth) $cleanUrl")

            try {
                // ---- Google Drive FIRST (before any generic fallback) ----
                if (handleGoogleDrive(cleanUrl, ref)) {
                    Log.e(TAG, "[handleUrl] ✔ Google Drive handled")
                    found = true
                    return
                }

                if (cleanUrl.contains("geo.dailymotion.com/player", true)) {
                    val videoId = Regex("""video=([a-zA-Z0-9_-]+)""").find(cleanUrl)?.groupValues?.get(1)
                    if (videoId != null) {
                        try {
                            val apiUrl = "https://geo.dailymotion.com/videos/$videoId"
                            val playerId = Regex("""player/([a-zA-Z0-9]+)\.html""").find(cleanUrl)?.groupValues?.get(1)
                            val reqHeaders = mapOf(
                                "Referer" to cleanUrl,
                                "Accept" to "application/json",
                                "x-dm-geo-embedder" to mainUrl,
                                "x-dm-geo-player-id" to (playerId ?: "")
                            ).filterValues { it.isNotBlank() }

                            val apiRes = app.get(apiUrl, headers = reqHeaders).text
                            val streamUrl = Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(apiRes)?.groupValues?.get(1)

                            if (!streamUrl.isNullOrBlank()) {
                                Log.e(TAG, "[DM] ✓ direct m3u8: ${streamUrl.take(140)}")
                                callback.invoke(
                                    newExtractorLink(
                                        source = "Dailymotion",
                                        name = "Dailymotion HD",
                                        url = streamUrl.replace("\\/", "/"),
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = cleanUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                found = true
                                return
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[DM] api failed: ${e.message}")
                        }

                        val realDmUrl = "https://www.dailymotion.com/embed/video/$videoId"
                        Log.e(TAG, "[DM] fallback to Dailymotion extractor")
                        com.lagradost.cloudstream3.extractors.Dailymotion().getUrl(realDmUrl, ref, subtitleCallback, callback)
                        found = true
                        return
                    }
                }
                else if (cleanUrl.contains("dailymotion.com", true) || cleanUrl.contains("dai.ly", true)) {
                    Log.e(TAG, "[DM] direct dailymotion link")
                    com.lagradost.cloudstream3.extractors.Dailymotion().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                if (cleanUrl.contains("galaxydonghua", true)) {
                    Log.e(TAG, "[handleUrl] ✔ ROUTE GalaxyDonghua")
                    GalaxyDonghua().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                if (cleanUrl.contains("skylineai.cloud", true)) {
                    Log.e(TAG, "[handleUrl] ✔ ROUTE SkylineAI")
                    SkylineAI().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                if (cleanUrl.contains("ghbrisk.com", true)) {
                    Log.e(TAG, "[handleUrl] ✔ ROUTE Ghbrisk")
                    Ghbrisk().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                val ok = loadExtractor(cleanUrl, referer = ref, subtitleCallback, callback)
                if (ok) {
                    Log.e(TAG, "[handleUrl] ✔ loadExtractor TRUE")
                    found = true
                    return
                }

                val html = app.get(cleanUrl, headers = mapOf("Referer" to ref)).text
                val streamRegex = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
                var foundGeneric = false

                streamRegex.findAll(html).forEach { m ->
                    val fileUrl = m.groupValues[1].replace("\\/", "/")
                    if (fileUrl.contains(".m3u8", ignoreCase = true)) {
                        Log.e(TAG, "[generic] m3u8: ${fileUrl.take(140)}")
                        M3u8Helper.generateM3u8("Generic HLS", fileUrl, cleanUrl).forEach { callback.invoke(it) }
                        foundGeneric = true
                    } else if (fileUrl.contains(".mp4", ignoreCase = true)) {
                        Log.e(TAG, "[generic] mp4: ${fileUrl.take(140)}")
                        callback.invoke(
                            newExtractorLink(
                                source = "Generic MP4",
                                name = "Generic MP4",
                                url = fileUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = cleanUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundGeneric = true
                    }
                }

                if (!foundGeneric) {
                    val iframeNode = Jsoup.parse(html).selectFirst("iframe")
                    val nestedIframe = iframeNode?.let { getIframeSrc(it) }
                    if (!nestedIframe.isNullOrBlank() && nestedIframe.startsWith("http")) {
                        Log.e(TAG, "[generic] nested iframe: ${nestedIframe.take(140)}")
                        handleUrl(nestedIframe, cleanUrl, depth + 1)
                        foundGeneric = true
                    }
                }
                if (foundGeneric) found = true

            } catch (e: Exception) {
                Log.e(TAG, "[handleUrl] ex: ${e.message}")
            }
        }

        suspend fun processDecodedHtml(decoded: String, ref: String) {
            try {
                Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                    val src = getIframeSrc(iframe)
                    if (src.isNotBlank()) handleUrl(src, ref)
                }
            } catch (e: Exception) { }

            Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).forEach { m ->
                handleUrl(m.value, ref)
            }
        }

        val mirrorOptions = document.select(
            "select.mirror option, .mobius option, select#mirror option, select[name=mirror] option"
        )

        val serverListItems = document.select(".server_list li, ul.episodes li, .mirror_link, .mirrors li")

        Log.e(TAG, "[loadLinks] mirror options found: ${mirrorOptions.size}")
        Log.e(TAG, "[loadLinks] server list items found: ${serverListItems.size}")

        coroutineScope {
            mirrorOptions.map { option ->
                async {
                    val value = option.attr("value").trim()
                    if (value.isBlank()) return@async

                    if (value.startsWith("http") || value.startsWith("//")) {
                        Log.e(TAG, "[mirror] raw url: ${value.take(140)}")
                        handleUrl(value, data)
                        return@async
                    }

                    val decoded = safeBase64Decode(value)
                    if (!decoded.isNullOrBlank()) {
                        Log.e(TAG, "[mirror] ✓ decoded: ${decoded.take(180)}")
                        processDecodedHtml(decoded, data)
                    } else {
                        Log.e(TAG, "[mirror] base64 decode failed for: ${value.take(80)}")
                    }
                }
            }.awaitAll()

            serverListItems.map { el ->
                async {
                    val videoAttr = el.attr("data-video").ifBlank { el.attr("data-src") }.ifBlank { el.attr("data-embed") }
                    if (videoAttr.isNotBlank()) {
                        if (videoAttr.startsWith("http") || videoAttr.startsWith("//")) {
                            Log.e(TAG, "[serverList] raw url: ${videoAttr.take(140)}")
                            handleUrl(videoAttr, data)
                        } else if (videoAttr.length > 20) {
                            val decoded = safeBase64Decode(videoAttr)
                            if (!decoded.isNullOrBlank()) {
                                Log.e(TAG, "[serverList] ✓ decoded: ${decoded.take(180)}")
                                if (decoded.startsWith("http")) handleUrl(decoded, data)
                                else processDecodedHtml(decoded, data)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        if (!found) {
            Log.e(TAG, "[loadLinks] fallback: scanning <iframe> elements")
            document.select("iframe").forEach { iframe ->
                val src = getIframeSrc(iframe)
                if (src.isNotBlank()) handleUrl(src, data)
            }
        }

        if (!found) {
            Log.e(TAG, "[loadLinks] fallback: scanning <script> bodies")
            document.select("script").forEach { script ->
                val body = script.data()

                Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
                    .findAll(body).forEach { m -> handleUrl(m.value, data) }

                Regex("""['"]([A-Za-z0-9+/=_-]{60,})['"]""").findAll(body).forEach { m ->
                    val blob = m.groupValues[1]
                    val decoded = safeBase64Decode(blob)
                    if (!decoded.isNullOrBlank()) {
                        processDecodedHtml(decoded, data)
                    }
                }
            }
        }

        Log.e(TAG, "[loadLinks] ════ FINAL RETURN found=$found at +${System.currentTimeMillis() - t0}ms ════")
        return found
    }

    companion object {
        private const val TAG = "ChikiDbg"
    }
}
