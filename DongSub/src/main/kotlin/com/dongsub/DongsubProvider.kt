package com.dongsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DongsubProvider : MainAPI() {
    override var mainUrl = "https://www.dongsub.net"
    override var name = "Dongsub"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.AsianDrama
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // Blogger labels that actually exist on dongsub.net
    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "search/label/Donghua?&max-results=20" to "Donghua",
        "search/label/Movie?&max-results=20" to "Movie",
        "search/label/Live%20Action?&max-results=20" to "Live Action"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Blogger token pagination is unreliable; only page 1.
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val url = if (request.data.isEmpty()) "$mainUrl/" else "$mainUrl/${request.data}"

        val doc: Document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = parseCards(doc)
        return newHomePageResponse(request.name, items)
    }

    private fun parseCards(doc: Document): List<AnimeSearchResponse> {
        return doc.select("article.post-outer-container, div.blog-posts article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        // Prefer the clean "series title" link, not the h3 which has "Episode N ..." suffix
        val a = selectFirst("div.series-title a.home-title")
            ?: selectFirst("div.bt a.grid2-tt")
            ?: selectFirst("h3.post-title a")
            ?: selectFirst("a.gbox")
            ?: return null

        val title = a.text().trim().ifBlank { return null }
        val href = fixUrlNull(a.attr("href")) ?: return null

        val img = selectFirst("img.gambar, img.lazyload, img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("data-original")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
        )

        val epText = selectFirst("span.epsid, span.tipeps span")?.text()
        val epNum = epText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return parseCards(doc)
    }

    /** Pull the "Series" label (e.g. "A Will Eternal Final Season 4") out of an episode page. */
    private fun extractSeriesLabel(doc: Document): String? {
        // 1. The <a rel="tag"> right after "Series" in .info-stream
        doc.selectFirst("span.info-stream a[rel=tag], span.info-stream a[data]")?.let {
            val t = it.text().trim()
            if (t.isNotBlank()) return t
        }
        // 2. Fallback: var labelopt = '...' in an inline <script>
        doc.select("script").forEach { s ->
            val m = Regex("""var\s+labelopt\s*=\s*['"]([^'"]+)['"]""").find(s.data())
            if (m != null) return m.groupValues[1]
        }
        // 3. Fallback: any /search/label/ link with a data attribute
        doc.selectFirst("a[data][href*=/search/label/]")?.let {
            val t = it.text().trim()
            if (t.isNotBlank()) return t
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        val rawTitle = doc.selectFirst("h1.title-stream, h1.entry-title, h1")
            ?.text()?.trim().orEmpty()

        // "A Will Eternal Final Season 4 Episode 13 Subtitles" -> "A Will Eternal Final Season 4"
        val title = rawTitle
            .replace(Regex("(?i)\\s*(?:Episode|Eps\\.?|Ep\\.?)\\s*\\d+.*$"), "")
            .trim()
            .ifBlank { rawTitle }

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".thumbox img, .bigcover img, .ime img")?.attr("src")
        )

        val plot = doc.selectFirst("div.desc p, .descNime, .sinoposis, .entry-content p")
            ?.text()?.trim()

        // Find the series label, then hit the label search page for ALL episodes.
        val label = extractSeriesLabel(doc)
        val episodes = mutableListOf<Episode>()

        if (!label.isNullOrBlank()) {
            val labelUrl = "$mainUrl/search/label/${
                URLEncoder.encode(label, "UTF-8")
            }?&max-results=500"

            val labelDoc = try {
                app.get(labelUrl, headers = defaultHeaders).document
            } catch (_: Exception) {
                null
            }

            labelDoc?.let { ld ->
                parseCards(ld).forEach { card ->
                    episodes.add(newEpisode(card.url) {
                        this.name = card.name
                        this.episode = card.episode
                        this.posterUrl = card.posterUrl
                    })
                }
            }
        }

        // Fallback: single episode
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { this.name = rawTitle })
        }

        // Blogger label pages are newest-first; we want oldest-first in the episode list
        val ordered = episodes.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, ordered)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }

        var handled = false
        val seen = mutableSetOf<String>()

        // Primary source: the <select id="selectServ"> option values
        doc.select("select#selectServ option[value]").forEach { opt ->
            val raw = opt.attr("value").trim()
            if (raw.isBlank()) return@forEach
            if (!seen.add(raw)) return@forEach

            // Dailymotion geo player: https://geo.dailymotion.com/player/xXXXX.html?video=VIDEOID
            val dmId = Regex("""[?&]video=([A-Za-z0-9]+)""")
                .find(raw)?.groupValues?.getOrNull(1)

            val candidates = buildList {
                if (dmId != null) {
                    add("https://www.dailymotion.com/video/$dmId")
                    add("https://www.dailymotion.com/embed/video/$dmId")
                }
                add(raw) // keep the original as a last resort
            }

            for (c in candidates) {
                try {
                    loadExtractor(
                        c,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    handled = true
                    break
                } catch (_: Exception) {
                    // try next candidate
                }
            }
        }

        // Secondary: any real iframes present
        doc.select("iframe").forEach { frame ->
            val src = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
            if (!src.startsWith("http")) return@forEach
            if (!seen.add(src)) return@forEach
            try {
                loadExtractor(
                    src,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                handled = true
            } catch (_: Exception) {
            }
        }

        return handled
    }
}
