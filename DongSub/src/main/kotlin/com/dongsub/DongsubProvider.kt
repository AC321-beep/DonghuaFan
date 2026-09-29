package com.dongsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val url = if (request.data.isEmpty()) "$mainUrl/" else "$mainUrl/${request.data}"
        val doc: Document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
        return newHomePageResponse(request.name, parseCards(doc))
    }

    private fun parseCards(doc: Document): List<AnimeSearchResponse> {
        return doc.select("article.post-outer-container, div.blog-posts article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
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

    /** Returns the series label + its /search/label/ href (from the "Series" line). */
    private fun extractSeriesAnchor(doc: Document): Pair<String, String?>? {
        doc.selectFirst("span.info-stream a[rel=tag], span.info-stream a[data]")?.let {
            val label = it.text().trim()
            val href = it.attr("href").ifBlank { null }
            if (label.isNotBlank()) return label to href
        }
        // Fallback: any /search/label/ link
        doc.selectFirst("a[href*=/search/label/][data]")?.let {
            val label = it.text().trim()
            val href = it.attr("href").ifBlank { null }
            if (label.isNotBlank()) return label to href
        }
        // Fallback: var labelopt = '...'
        doc.select("script").forEach { s ->
            val m = Regex("""var\s+labelopt\s*=\s*['"]([^'"]+)['"]""").find(s.data())
            if (m != null) return m.groupValues[1] to null
        }
        return null
    }

    /** Fetch every post under a Blogger label via the JSON feed. */
    private suspend fun fetchLabelFeed(label: String): List<Pair<String, String>> {
        // Blogger label paths want %20 for spaces, not '+'.
        val encoded = label.replace(" ", "%20")
        val url = "$mainUrl/feeds/posts/default/-/$encoded?alt=json&max-results=500"

        val text = try {
            app.get(url, headers = defaultHeaders).text
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            val root = JSONObject(text)
            val feed = root.optJSONObject("feed") ?: return emptyList()
            val entries = feed.optJSONArray("entry") ?: return emptyList()

            val result = mutableListOf<Pair<String, String>>()
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val title = entry.optJSONObject("title")?.optString("\$t") ?: continue
                val links = entry.optJSONArray("link") ?: continue

                var href: String? = null
                for (j in 0 until links.length()) {
                    val link = links.getJSONObject(j)
                    if (link.optString("rel") == "alternate") {
                        href = link.optString("href")
                        break
                    }
                }
                if (!href.isNullOrBlank()) result.add(title to href)
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Scrape a Blogger label page for the same cards as the homepage. */
    private suspend fun fetchLabelPage(labelOrHref: String): List<AnimeSearchResponse> {
        val base = if (labelOrHref.startsWith("http")) {
            labelOrHref
        } else {
            "$mainUrl/search/label/${labelOrHref.replace(" ", "%20")}"
        }
        val url = if (base.contains("?")) "$base&max-results=500" else "$base?&max-results=500"

        val doc = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        val rawTitle = doc.selectFirst("h1.title-stream, h1.entry-title, h1")
            ?.text()?.trim().orEmpty()

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

        val episodes = mutableListOf<Episode>()
        val anchor = extractSeriesAnchor(doc)

        if (anchor != null) {
            val (label, href) = anchor

            // 1) Preferred: JSON feed (complete list, no Blogger HTML cap)
            val feed = fetchLabelFeed(label)
            if (feed.isNotEmpty()) {
                feed.forEach { (epTitle, epUrl) ->
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                            .replace(Regex("(?i)\\s*Subtitles\\s*$"), "")
                            .trim()
                    })
                }
            } else {
                // 2) Fallback: scrape the label search page HTML
                val cards = fetchLabelPage(href ?: label)
                cards.forEach { card ->
                    episodes.add(newEpisode(card.url) { this.name = card.name })
                }
            }
        }

        // 3) Last-resort fallback: just the current episode
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { this.name = rawTitle })
        }

        // Blogger feeds/pages are newest-first; flip so ep 1 is first
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

        doc.select("select#selectServ option[value]").forEach { opt ->
            val raw = opt.attr("value").trim()
            if (raw.isBlank()) return@forEach
            if (!seen.add(raw)) return@forEach

            val dmId = Regex("""[?&]video=([A-Za-z0-9]+)""")
                .find(raw)?.groupValues?.getOrNull(1)

            val candidates = buildList {
                if (dmId != null) {
                    add("https://www.dailymotion.com/video/$dmId")
                    add("https://www.dailymotion.com/embed/video/$dmId")
                }
                add(raw)
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
                }
            }
        }

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
