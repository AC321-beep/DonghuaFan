package com.dongsub

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class DongsubProvider : MainAPI() {
    override var mainUrl = "https://www.dongsub.net"
    override var name = "Dongsub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.AsianDrama)

    private companion object {
        const val PAGE_FETCH_TIMEOUT_MS = 15_000L
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Blogger uses tokens for deep pagination, so we keep the homepage to Page 1
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val document = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                app.get("$mainUrl/", headers = defaultHeaders).document
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return newHomePageResponse(request.name, emptyList())

        val home = ArrayList<HomePageList>()

        // 1. Popular Posts from sidebar
        val popularItems = document.select(".widget.PopularPosts article.post").mapNotNull { post ->
            val a = post.selectFirst(".post-title a") ?: return@mapNotNull null
            val title = a.text().trim()
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(post.selectFirst(".item-thumbnail img")?.attr("src"))

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
        if (popularItems.isNotEmpty()) {
            home.add(HomePageList("Populer", popularItems))
        }

        // 2. Latest Releases main feed
        val latestItems = document.select("article.post-outer-container").mapNotNull {
            it.toSearchResult()
        }
        if (latestItems.isNotEmpty()) {
            home.add(HomePageList("Latest Release", latestItems))
        }

        return newHomePageResponse(home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElem = this.selectFirst("h3.post-title a, a.grid2-tt") ?: return null
        val title = titleElem.text().trim()
        val href = fixUrlNull(titleElem.attr("href")) ?: return null

        val imgElem = this.selectFirst("img.gambar, img")
        val posterUrl = fixUrlNull(imgElem?.attr("data-src")?.ifEmpty { imgElem.attr("src") })

        val epString = this.selectFirst(".epsid, .tipeps")?.text()?.replace(Regex("[^0-9]"), "")
        val epNum = epString?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?q=$encoded"

        val document = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }

        return document.select("article.post-outer-container, .post-filter").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        // Clean up the title (remove "Episode X Subtitles" from the main show title)
        val rawTitle = document.selectFirst("h1.post-title, h2.heading, h3.post-title")?.text()?.trim() ?: ""
        val title = rawTitle.replace(Regex("(?i)Episode\\s*\\d+.*$"), "").trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".bigcover img, .ime img")?.attr("src")
        )
        val description = document.selectFirst(".sinoposis, .descNime, .entry-content")?.text()?.trim()

        // EXPLICITLY target only the actual episode list for this specific show (.bxcl)
        // This prevents scraping the "Recommended" sidebar widgets which caused the global list bug.
        var episodes = document.select(".bxcl ul li, #eplist ul li").mapNotNull { ep ->
            val a = ep.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            
            // Try to get just the chapter number, fallback to full text
            val epName = ep.selectFirst(".chapternum")?.text() 
                ?: ep.selectFirst(".eph-num")?.text() 
                ?: a.text()
                
            newEpisode(epHref) {
                this.name = epName.trim()
            }
        }.reversed()

        // Fallback: If no list exists (Blogger single post), use the current page as the only episode
        if (episodes.isEmpty()) {
            episodes = listOf(
                newEpisode(url) {
                    this.name = rawTitle.ifBlank { title }
                    this.posterUrl = poster
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                app.get(data, headers = defaultHeaders)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val document = response.document
        val rawHtml = response.text
        val extractedUrls = ConcurrentHashMap.newKeySet<String>()

        // 1. Direct iframes matching Dailymotion
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("dailymotion.com") || src.contains("dai.ly")) {
                extractedUrls.add(src)
            }
        }

        // 2. Base64 decoded server buttons (Dongsub heavily hides iframes in data-embed attributes)
        document.select("[data-embed], .DagPlayOpt").forEach { elem ->
            val raw = elem.attr("data-embed").ifEmpty { elem.attr("data-src") }
            if (raw.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(raw, Base64.DEFAULT))
                    val iframeSrc = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src") ?: decoded
                    if (iframeSrc.contains("dailymotion") || iframeSrc.contains("dai.ly")) {
                        extractedUrls.add(iframeSrc)
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Scan script tags and raw HTML for Dailymotion URLs (embedded in Blogger player scripts)
        val dmRegex = Regex("""https?://(?:www\.)?(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/)[a-zA-Z0-9]+""")
        dmRegex.findAll(rawHtml).forEach { match ->
            extractedUrls.add(match.value)
        }

        var handled = false
        for (dmUrl in extractedUrls) {
            val cleanUrl = if (dmUrl.startsWith("//")) "https:$dmUrl" else dmUrl
            try {
                loadExtractor(cleanUrl, referer = mainUrl, subtitleCallback, callback)
                handled = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Skip broken/unresponsive embed links
            }
        }

        return handled
    }
}
