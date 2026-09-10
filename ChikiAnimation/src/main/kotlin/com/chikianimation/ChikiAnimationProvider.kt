package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update"          to "Recently Updated",
        "anime/?status=&type=&order=popular"         to "Popular",
        "anime/?status=&type=&order=latest"          to "Latest Added",
        "anime/?status=ongoing&type=&order=update"   to "Ongoing",
        "anime/?status=completed&type=&order=update" to "Completed",
        "anime/?status=&type=movie&order=update"     to "Movies",
        "anime/?status=&type=ona&order=update"       to "Donghua (ONA)",
        "genres/ai-generated/"                       to "AI Anime"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

       override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        println("ChikiAnimation: loading ${request.name} → $url")

        val items = try {
            val document = app.get(url, headers = headers).document
            val list = document
                .select("div.listupd article.bs, div.listupd div.bsx, article.bs, div.bsx")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            println("ChikiAnimation: ${request.name} returned ${list.size} items")
            list
        } catch (e: Exception) {
            println("ChikiAnimation: ${request.name} failed — ${e.message}")
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
            else -> "$mainUrl/$base&paged=$page"
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
                        val url = if (page == 1)
                            "$mainUrl/?s=$encoded"
                        else
                            "$mainUrl/page/$page/?s=$encoded"

                        app.get(url, headers = headers).document
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
            app.get(url, headers = headers).document
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
                    app.get(fixUrl(epPage), headers = headers).document
                        .select(".episodelist li, .eplister li")
                } catch (e: Exception) {
                    org.jsoup.select.Elements()
                }
            }
        }

        val episodes = epListElements.mapNotNull { info ->
            val href = info.selectFirst("a[href]")?.attr("href")?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val rawTitle = info.selectFirst(".epl-title")?.text()?.trim()
                ?: info.selectFirst("a span")?.text()?.trim()
                ?: info.selectFirst("a")?.text()?.trim()
                ?: ""

            val dateText = info.selectFirst(".epl-date, .date, .time")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }

            val epNum = Regex("""(?i)(\d+(?:\.\d+)?)""")
                .find(rawTitle)?.groupValues?.get(1)?.toFloatOrNull()

            val cleanName = rawTitle
                .replace(Regex("""(?i)^\s*Episode\s*"""), "")
                .trim()
                .ifBlank { rawTitle.ifBlank { "Episode" } }

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
            app.get(data, headers = headers).document
        } catch (e: Exception) {
            println("ChikiAnimation: failed to fetch $data — ${e.message}")
            return false
        }

        var found = false

        suspend fun handleUrl(rawUrl: String, ref: String) {
            val cleanUrl = try { fixUrl(rawUrl) } catch (e: Exception) { return }
            if (!cleanUrl.startsWith("http")) return

            if (cleanUrl.contains("youtube", true) ||
                cleanUrl.contains("disqus", true) ||
                cleanUrl.contains("googlesyndication", true) ||
                cleanUrl.contains("doubleclick", true)
            ) return

            try {
                println("ChikiAnimation: handleUrl → $cleanUrl (ref=$ref)")

                // Dailymotion branch
                val dmMatch = Regex(
                    """(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/)([a-zA-Z0-9]+)"""
                ).find(cleanUrl)
                if (dmMatch != null) {
                    val videoId = dmMatch.groupValues[1]
                    Dailymotion().getUrl(
                        "https://www.dailymotion.com/embed/video/$videoId",
                        ref, subtitleCallback, callback
                    )
                    found = true
                    return
                }

                // Ghbrisk (StreamWish mirror)
                if (cleanUrl.contains("ghbrisk.com", true)) {
                    Ghbrisk().getUrl(cleanUrl, ref, subtitleCallback, callback)
                    found = true
                    return
                }

                // Everything else → core registry
                val ok = loadExtractor(cleanUrl, referer = ref, subtitleCallback, callback)
                if (ok) found = true
            } catch (e: Exception) {
                println("ChikiAnimation: extractor threw for $cleanUrl — ${e.message}")
            }
        }

        // ─── Layer 1: mirror dropdown ────────────────────────────────
        val mirrorOptions = document.select(
            "select.mirror option, .mobius option, select#mirror option, select[name=mirror] option"
        )
        println("ChikiAnimation: ${mirrorOptions.size} mirror options")

        coroutineScope {
            mirrorOptions.map { option ->
                async {
                    val value = option.attr("value").trim()
                    if (value.isBlank()) return@async

                    if (value.startsWith("http") || value.startsWith("//")) {
                        handleUrl(value, data)
                        return@async
                    }

                    val decoded: String? = try {
                        String(Base64.decode(value, Base64.DEFAULT))
                    } catch (e: Exception) {
                        try {
                            String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP))
                        } catch (e2: Exception) { null }
                    }
                    if (decoded.isNullOrBlank()) return@async

                    try {
                        Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                            val src = iframe.attr("src").ifBlank {
                                iframe.attr("data-src").ifBlank {
                                    iframe.attr("data-litespeed-src")
                                }
                            }
                            if (src.isNotBlank()) handleUrl(src, data)
                        }
                    } catch (e: Exception) { }

                    Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).forEach { m ->
                        handleUrl(m.value, data)
                    }
                }
            }.awaitAll()
        }

        // ─── Layer 2: iframes ────────────────────────────────────────
        if (!found) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank {
                    iframe.attr("data-src").ifBlank {
                        iframe.attr("data-litespeed-src")
                    }
                }
                if (src.isNotBlank()) handleUrl(src, data)
            }
        }

        // ─── Layer 3: script scan ────────────────────────────────────
        if (!found) {
            document.select("script").forEach { script ->
                val body = script.data()

                Regex("""(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""")
                    .findAll(body).forEach { m -> handleUrl(m.value, data) }

                Regex("""['"]([A-Za-z0-9+/=_-]{60,})['"]""").findAll(body).forEach { m ->
                    val blob = m.groupValues[1]
                    val decoded: String? = try {
                        String(Base64.decode(blob, Base64.DEFAULT))
                    } catch (e: Exception) {
                        try {
                            String(Base64.decode(blob, Base64.URL_SAFE or Base64.NO_WRAP))
                        } catch (e2: Exception) { null }
                    }
                    if (decoded.isNullOrBlank()) return@forEach

                    try {
                        Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                            if (src.isNotBlank()) handleUrl(src, data)
                        }
                    } catch (e: Exception) { }

                    Regex("""https?://[^\s"'<>\\)]+""").findAll(decoded).forEach { mm ->
                        handleUrl(mm.value, data)
                    }
                }
            }
        }

        println("ChikiAnimation: loadLinks finished — found=$found")
        return found
    }
}
