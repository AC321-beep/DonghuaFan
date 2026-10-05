package com.footballreplays

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class FootballReplays : MainAPI() {
    override var mainUrl = "https://www.footreplays.com"
    override var name = "FootballReplays"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = mainPageOf(
        "${mainUrl}/international/" to "FIFA/International",
        "${mainUrl}/uefa/" to "UEFA",
        "${mainUrl}/england/" to "England",
        "${mainUrl}/spain/" to "Spain",
        "${mainUrl}/italy/" to "Italy",
        "${mainUrl}/germany/" to "Germany",
        "${mainUrl}/france/" to "France",
        "${mainUrl}/portugal/" to "Portugal",
        "${mainUrl}/other/" to "Other"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val siteurl = if (page > 1) "${request.data.removeSuffix("/")}/page/$page/" else request.data
        Log.e(TAG, "getMainPage page=$page request.name=${request.name} siteurl=$siteurl")

        val document = app.get(siteurl).document
        val home = document.select("div.p-wrap").mapNotNull { it.toMainPageResult() }
        Log.e(TAG, "getMainPage items=${home.size}")

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            )
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val isnot = this.selectFirst("a.p-category")?.attr("href")?.contains("/news/") == true
        val categoryId = this.selectFirst("a.p-category")?.className()
        if (isnot || categoryId?.contains("category-id-283") == true) {
            Log.e(TAG, "toMainPageResult skipped (news or category-283)")
            return null
        }
        return toRecommendationResult()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "$mainUrl/?s=$query"
        } else {
            "$mainUrl/page/$page/?s=$query"
        }
        Log.e(TAG, "search query=$query page=$page url=$url")

        val document = app.get(url).document
        val aramaCevap = document.select("div.p-wrap").mapNotNull { it.toMainPageResult() }
        Log.e(TAG, "search results=${aramaCevap.size}")

        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.e(TAG, "===== load START url=$url =====")
        val document = app.get(url).document

        val title = document.selectFirst("h1.s-title")?.text()?.trim() ?: run {
            Log.e(TAG, "load FAILED: title not found")
            return null
        }
        Log.e(TAG, "load title=$title")

        val poster = fixUrlNull(document.selectFirst("div.s-feat img")?.attr("src"))
        Log.e(TAG, "load poster=$poster")

        val year = document.selectFirst("time.updated-date")?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        Log.e(TAG, "load year=$year")

        val rawDescription = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""
        Log.e(TAG, "load rawDescription=${rawDescription.take(300)}")

        val kickOffRegex = Regex("""Kick-off:\s*([^.]+)""", RegexOption.IGNORE_CASE)
        val kickOffMatch = kickOffRegex.find(rawDescription)?.groupValues?.getOrNull(1)
        Log.e(TAG, "load kickOffMatch=$kickOffMatch")

        val fallbackDate = document.selectFirst("time.updated-date")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()
        Log.e(TAG, "load fallbackDate=$fallbackDate")

        val displayDate = kickOffMatch ?: fallbackDate
        Log.e(TAG, "load displayDate=$displayDate")

        val episodes = mutableListOf<Episode>()
        document.select("table.video-table").forEachIndexed { tIdx, table ->
            val sourceName = table.selectFirst("thead tr th[colspan]")?.text()?.trim() ?: "Source"
            Log.e(TAG, "load table[$tIdx] sourceName=$sourceName")

            table.select("tbody tr").forEachIndexed { rIdx, tr ->
                val part = tr.select("td").firstOrNull()?.text()?.trim() ?: "Video"
                val onclickAttr = tr.selectFirst("a.play-button")?.attr("onclick") ?: run {
                    Log.e(TAG, "load table[$tIdx] row[$rIdx] no play-button")
                    return@forEachIndexed
                }
                Log.e(TAG, "load table[$tIdx] row[$rIdx] onclick=$onclickAttr")

                val regex = Regex("""loadVideo\('([^']+)'\)""")
                val videoUrl = regex.find(onclickAttr)?.groupValues?.get(1) ?: run {
                    Log.e(TAG, "load table[$tIdx] row[$rIdx] loadVideo regex failed")
                    return@forEachIndexed
                }
                Log.e(TAG, "load table[$tIdx] row[$rIdx] videoUrl=$videoUrl")

                val episodeData = "$videoUrl|$sourceName - $part"
                val currentEpisodeSize = episodes.size

                episodes.add(
                    newEpisode(data = episodeData) {
                        this.name = "$sourceName - $part"
                        this.episode = currentEpisodeSize + 1
                    }
                )
            }
        }

        Log.e(TAG, "load total episodes=${episodes.size}")

        val plotText = buildString {
            if (!displayDate.isNullOrBlank()) {
                append("🕒 Match Date: $displayDate\n\n")
            }
            if (rawDescription.isNotBlank()) {
                append("$rawDescription\n\n")
            }
            append("📡 Available Streams: ${episodes.size}")
        }

        Log.e(TAG, "===== load SUCCESS title=$title episodes=${episodes.size} =====")

        return newTvSeriesLoadResponse(title, url, TvType.Others, episodes) {
            this.posterUrl = poster
            this.plot = plotText
            this.year = year
            this.tags = document.select("div.efoot-bar.tag-bar a").map { it.text() }
            this.recommendations = document.select("div.p-wrap.p-grid").mapNotNull { it.toRecommendationResult() }
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val baseTitle = this.selectFirst("h4.entry-title a, a.p-flink")?.attr("title")
            ?.takeIf { it.isNotBlank() } ?: this.selectFirst("h4.entry-title a")?.text()?.trim()
            ?: return null
        val href = fixUrlNull(this.selectFirst("a.p-flink, h4.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.p-featured img")?.attr("src"))

        val dateText = this.selectFirst("time")?.text()
            ?.replace("Last updated:", "", ignoreCase = true)?.trim()

        val displayTitle = if (!dateText.isNullOrBlank()) {
            "$baseTitle • $dateText"
        } else {
            baseTitle
        }

        return newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "===== loadLinks START =====")
        Log.e(TAG, "loadLinks raw data=$data")

        val parts = data.split("|")
        val videoUrl = parts.getOrNull(0) ?: run {
            Log.e(TAG, "loadLinks FAILED: no videoUrl part")
            return false
        }
        val customName = parts.getOrNull(1) ?: "Video"
        val iframeUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl

        Log.e(TAG, "loadLinks videoUrl=$videoUrl")
        Log.e(TAG, "loadLinks customName=$customName")
        Log.e(TAG, "loadLinks iframeUrl=$iframeUrl")

        var emitted = 0
        loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
            emitted++
            Log.e(TAG, "loadLinks EMITTED #$emitted url=${link.url.take(200)} type=${link.type} name=${link.name}")

            val extractedLink = ExtractorLink(
                source = customName,
                name = customName,
                url = link.url,
                referer = link.referer,
                quality = link.quality,
                type = link.type,
                headers = link.headers
            )
            callback(extractedLink)
        }

        // Fallback for unmapped Byse rotating domains (e.g. bysefujedu.com).
        // Cloudstream's auto-matcher only fires for extractors whose mainUrl matches.
        // Byse domains rotate constantly, so we detect the "/d/<id>" pattern and call
        // ByseSX directly.
        if (emitted == 0) {
            val uri = try { URI(iframeUrl) } catch (_: Exception) { null }
            val looksLikeByse = uri != null && uri.path?.contains("/d/") == true

            if (looksLikeByse) {
                Log.e(TAG, "loadLinks no extractor matched, trying ByseSX fallback for $iframeUrl")
                try {
                    ByseSX().getUrl(iframeUrl, "$mainUrl/", subtitleCallback) { link ->
                        emitted++
                        Log.e(TAG, "loadLinks ByseSX fallback EMITTED #$emitted url=${link.url.take(200)} type=${link.type}")

                        val extractedLink = ExtractorLink(
                            source = customName,
                            name = customName,
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            type = link.type,
                            headers = link.headers
                        )
                        callback(extractedLink)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks ByseSX fallback FAILED", e)
                }
            } else {
                Log.e(TAG, "loadLinks no extractor matched and URL doesn't look Byse-like, giving up")
            }
        }

        Log.e(TAG, "===== loadLinks END emitted=$emitted =====")
        return true
    }
}
