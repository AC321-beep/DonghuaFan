package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        "anime/?status=&type=&order=latest"          to "Latest Added",
        "anime/?status=&type=ai+animes&order=update" to "AI Anime",
        "anime/?status=ongoing&type=&order=update"   to "Ongoing",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=movie&order=update"     to "Movies",
        "anime/?status=&type=ona&order=update"       to "Donghua (ONA)"
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    private val cfInterceptor = WebViewResolver(Regex("""challenge-platform|cloudflare"""))

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val items = try {
            val document = app.get(url, headers = defaultHeaders, interceptor = cfInterceptor).document
            document.select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
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

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href]")
            ?: selectFirst("a[itemprop=url]")
            ?: selectFirst("h2 a[href]")
            ?: selectFirst("a[href]")
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (href.isBlank() || href.contains("/genres/") || href.contains("/bookmark") ||
            href.contains("/privacy") || href.contains("/contact") || href.contains("/dmca")
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
            } ?: selectFirst("div.limit img")?.attr("src")
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
                        app.get(url, headers = defaultHeaders, interceptor = cfInterceptor).document
                            .select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                            .mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url, headers = defaultHeaders, interceptor = cfInterceptor).document
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("div.thumb img.wp-post-image")?.attr("src")?.trim()
            ?: document.selectFirst("div.thumb img")?.attr("src")?.trim()
            ?: ""

        val description = document.selectFirst("div.entry-content[itemprop=description]")?.text()?.trim()
            ?: document.selectFirst("div.entry-content")?.text()?.trim()

        val genres = document.select("div.genxed a, span.genxed a, .spe .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val typeText = (document.selectFirst("div.typez, .spe, .infox .spe, div.anime-info .type")
            ?.text()?.lowercase() ?: "") + " " + title.lowercase()

        if (typeText.contains("movie", ignoreCase = true)) {
            val watchHref = document.selectFirst(".eplister li > a[href], .episodelist li > a[href]")?.attr("href")?.trim() ?: url
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
                    app.get(fixUrl(epPage), headers = defaultHeaders, interceptor = cfInterceptor).document
                        .select(".episodelist li, .eplister li")
                } catch (e: Exception) {
                    org.jsoup.select.Elements()
                }
            }
        }

        val episodes = epListElements.mapNotNull { info ->
            val href = info.selectFirst("a[href]")?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawTitle = info.selectFirst(".epl-title")?.text()?.trim()
                ?: info.selectFirst("a span")?.text()?.trim()
                ?: info.selectFirst("a")?.text()?.trim() ?: ""
            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val epNum = Regex("""(?i)(\d+(?:\.\d+)?)""").find(rawTitle)?.groupValues?.get(1)?.toFloatOrNull()
            
            val cleanName = rawTitle.replace(Regex("""(?i)^\s*Episode\s*"""), "").trim().ifBlank { rawTitle.ifBlank { "Episode" } }

            newEpisode(fixUrl(href)) {
                this.name = cleanName
                this.posterUrl = poster
                if (epNum != null) this.episode = epNum.toInt()
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
            app.get(data, headers = defaultHeaders, interceptor = cfInterceptor).document
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        var found = false

        suspend fun handleUrl(rawUrl: String, ref: String) {
            val cleanUrl = try { fixUrl(rawUrl) } catch (e: Exception) { return }
            if (!cleanUrl.startsWith("http")) return
            if (cleanUrl.contains("youtube", true) || cleanUrl.contains("disqus", true) || cleanUrl.contains("googlesyndication", true)) return

            try {
                // 1. Check Explicit Custom Extractors
                if (cleanUrl.contains("ghbrisk.com", true)) {
                    Ghbrisk().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                // 2. Try Default CloudStream Extractors (Automatically handles Dailymotion and others)
                val ok = loadExtractor(cleanUrl, referer = ref, subtitleCallback, callback)
                if (ok) {
                    found = true
                    return
                }

                // 3. GENERIC FALLBACK (For Donghuaplay and unsupported iframe mirrors)
                val html = app.get(cleanUrl, headers = mapOf("Referer" to ref), interceptor = cfInterceptor).text
                val streamRegex = Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*)""")
                var foundGeneric = false

                streamRegex.findAll(html).forEach { m ->
                    val fileUrl = m.groupValues[1].replace("\\/", "/")
                    if (fileUrl.contains(".m3u8", ignoreCase = true)) {
                        M3u8Helper.generateM3u8("Generic HLS", fileUrl, cleanUrl).forEach { callback.invoke(it) }
                        foundGeneric = true
                    } else if (fileUrl.contains(".mp4", ignoreCase = true)) {
                        callback.invoke(
                            ExtractorLink(
                                source = "Generic MP4",
                                name = "Generic MP4",
                                url = fileUrl,
                                referer = cleanUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        foundGeneric = true
                    }
                }
                if (foundGeneric) found = true

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Layer 1: Dropdown mirrors
        coroutineScope {
            document.select("select.mirror option, .mobius option, select#mirror option, select[name=mirror] option").map { option ->
                async {
                    val value = option.attr("value").trim()
                    if (value.isBlank()) return@async
                    if (value.startsWith("http") || value.startsWith("//")) {
                        handleUrl(value, data)
                        return@async
                    }
                    val decoded: String? = try { String(Base64.decode(value, Base64.DEFAULT)) } catch (e: Exception) { null }
                    if (decoded.isNullOrBlank()) return@async
                    try {
                        Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                            if (src.isNotBlank()) handleUrl(src, data)
                        }
                    } catch (e: Exception) { }
                    Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).forEach { m -> handleUrl(m.value, data) }
                }
            }.awaitAll()
        }

        // Layer 2: Inline Iframes
        if (!found) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank()) handleUrl(src, data)
            }
        }

        // Layer 3: Script scanning for direct mp4/m3u8 or packed iframes
        if (!found) {
            document.select("script").forEach { script ->
                val body = script.data()
                Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""").findAll(body).forEach { m -> handleUrl(m.value, data) }

                Regex("""['"]([A-Za-z0-9+/=_-]{60,})['"]""").findAll(body).forEach { m ->
                    val blob = m.groupValues[1]
                    val decoded = try { String(Base64.decode(blob, Base64.DEFAULT)) } catch (e: Exception) { null } ?: return@forEach
                    try {
                        Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                            if (src.isNotBlank()) handleUrl(src, data)
                        }
                    } catch (e: Exception) { }
                }
            }
        }
        return found
    }
}
