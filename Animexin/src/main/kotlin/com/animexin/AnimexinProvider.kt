package com.Animexin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val episodeRegex = Regex("""(\d+)""")

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Latest Release",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "New Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val path = request.data.substringBefore("?")
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) {
                "$mainUrl/${path}page/$page/?$query"
            } else {
                "$mainUrl/${path}page/$page/"
            }
        }

        val document = app.get(url, headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
        )).document

        // Target the main content area
        val container = document.selectFirst(".listupd") ?: document
        
        // Select show items - .bs is the primary container for each show
        val items = container.select(".bs, .bsx, .styleegg")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Find the main link - usually inside .bsx or directly
        val linkElement = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        if (href.isBlank() || href == mainUrl) return null

        // --- Extract Title ---
        // Primary: .eggtitle (most reliable on this site)
        var title = this.selectFirst(".eggtitle")?.text()?.trim()
        
        // Fallback: .tt or h2
        if (title.isNullOrBlank()) {
            title = this.selectFirst(".tt")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {
            title = this.selectFirst("h2")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {
            title = this.selectFirst(".title")?.text()?.trim()
        }
        // Last resort: link text or title attribute
        if (title.isNullOrBlank()) {
            title = linkElement.attr("title").trim()
        }
        if (title.isNullOrBlank()) {
            title = linkElement.text().trim()
        }
        
        if (title.isNullOrBlank()) return null

        // --- Extract Poster ---
        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            listOf(
                img?.attr("data-lazy-src"),
                img?.attr("data-src"),
                img?.attr("src")
            ).firstOrNull { !it.isNullOrBlank() }
        ) ?: fixUrlNull(
            this.selectFirst("meta[property=og:image]")?.attr("content")
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        val container = document.selectFirst(".listupd") ?: document
        
        return container.select(".bs, .bsx, .styleegg")
            .asSequence()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // --- Title ---
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst(".infox h1")?.text()?.trim()
            ?: "Unknown Title"

        // --- Poster ---
        val img = document.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        val poster = fixUrlNull(
            listOf(
                img?.attr("data-lazy-src"),
                img?.attr("data-src"),
                img?.attr("src"),
                document.selectFirst("meta[property=og:image]")?.attr("content")
            ).firstOrNull { !it.isNullOrBlank() }
        )

        // --- Description ---
        val description = document.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()

        // --- Check if Movie ---
        val typeStr = document.selectFirst(".spe, .type")?.text() ?: ""
        val isMovie = typeStr.contains("Movie", ignoreCase = true)

        if (isMovie) {
            val href = document.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: ""
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // --- TV Series / Anime ---
        // Find episode list items
        val episodeElements = document.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .asSequence()
            .filter { it.selectFirst("a") != null }

        val episodes = episodeElements.mapNotNull { info ->
            val epHref = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            // Episode poster (if available)
            val epImg = info.selectFirst("a img")
            val epPoster = listOf(
                epImg?.attr("data-lazy-src"),
                epImg?.attr("data-src"),
                epImg?.attr("src")
            ).firstOrNull { !it.isNullOrBlank() }

            // Episode number/name
            val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text() ?: ""
            val epNum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

            val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

            newEpisode(epHref) {
                this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { "Episode" }
                this.episode = epNum
                this.posterUrl = epPoster
                if (!dateText.isNullOrBlank()) {
                    this.addDate(dateText, format = "MMMM d, yyyy")
                    this.description = dateText
                }
            }
        }.toList().reversed() // Newest first

        // Fallback: if no episodes found, try a simpler selector
        val finalEpisodes = if (episodes.isEmpty()) {
            document.select(".eplister a[href]")
                .asSequence()
                .mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank()) null else newEpisode(href) {
                        this.name = a.text().ifBlank { "Episode" }
                    }
                }.toList().reversed()
        } else {
            episodes
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, finalEpisodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = document.select(".mobius option, select.mirror option, .server option, .player option")

        coroutineScope {
            servers.map { server ->
                async {
                    val value = server.attr("value")
                    if (value.isNotEmpty()) {
                        val decoded = try { base64Decode(value) } catch (e: Exception) { value }

                        val iframeSrc = if (decoded.contains("<iframe")) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http")) {
                            decoded
                        } else {
                            null
                        }

                        if (iframeSrc != null) {
                            val url = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                            loadExtractor(url, subtitleCallback, callback)
                        }
                    }
                }
            }.awaitAll()
        }
        return true
    }

    private fun base64Decode(str: String): String {
        return try {
            String(Base64.decode(str, Base64.DEFAULT))
        } catch (e: Exception) {
            str
        }
    }
}
