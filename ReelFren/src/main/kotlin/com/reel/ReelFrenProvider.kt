package com.reel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap

class Reelfren : MainAPI() {

    override var mainUrl = ReelFrenConfig.REEL_SITE
    override var name = "ReelFren"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 120L
    override val getMainPageTimeoutMs = 240_000L
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    private val providersMap: LinkedHashMap<String, String> = linkedMapOf(
        "PineDrama"       to "pinedrama",
        "Shorten"         to "shorten",
        "HappyShort"      to "happyshort",
        "Vigloo"          to "vigloo",
        "ReelShort"       to "reelshort",
        "DramaBox"        to "dramabox",
        "WeTV"            to "wetv",
        "MovieBox"        to "moviebox",
        "MovieBox Shorts" to "movieboxshorts",
        "ShortMax"        to "shortmax",
        "FlexTV"          to "flextv",
        "iQIYI"           to "iqiyi",
        "FilmBox"         to "filmbox"
    )

    private val seenIds = ConcurrentHashMap<String, MutableSet<String>>()

    // FIX #2: `mainPage` is a property in this API, not a function.
    override val mainPage: List<MainPageData> =
        providersMap.map { (display, slug) -> slug to display }
            .let { mainPageOf(*it.toTypedArray()) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        val items = mutableListOf<SearchResponse>()

        runCatching {
            val homeItems = ReelFrenClient.home(slug, page = page)
            val seen = seenIds.getOrPut(slug) { mutableSetOf() }
            homeItems.filter { it.id !in seen }.forEach { item ->
                toCard(slug, item)?.let { items.add(it); seen.add(item.id) }
            }
        }
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()

        val results = LinkedHashMap<String, SearchResponse>()
        val deadline = System.currentTimeMillis() + 45_000L

        for (slug in providersMap.values) {
            if (System.currentTimeMillis() > deadline) break
            runCatching {
                ReelFrenClient.search(slug, term).forEach { item ->
                    toCard(slug, item)?.let { results[it.url] = it }
                }
            }
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val (slug, id, _) = ReelFrenUrl.parse(url)
        if (slug.isEmpty() || id.isEmpty()) return null

        val info = ReelFrenClient.detail(slug, id) ?: return null
        val cover = ReelFrenClient.absUrl(info.cover).ifEmpty { null }
        val plot = info.intro.takeIf { it.isNotEmpty() }

        return if (info.episodes > 1) {
            newTvSeriesLoadResponse(
                name = info.title,
                url = url,
                type = TvType.TvSeries,
                episodes = info.videos.map { v ->
                    newEpisode(v.vid) {
                        name = "Episode ${v.episode}"
                        episode = v.episode
                        posterUrl = cover
                    }
                }
            ) {
                posterUrl = cover
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(
                name = info.title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                posterUrl = cover
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (slug, id, episode) = ReelFrenUrl.parse(data)
        if (slug.isEmpty() || id.isEmpty()) return false

        val playback = ReelFrenClient.playback(slug, id, episode) ?: return false

        // FIX #3: use the SubtitleFile constructor directly.
        // The `newSubtitleFile(...)` helper in this version takes a suspend initializer
        // as its 3rd argument — passing a String there causes the type mismatch.
        playback.subtitles.forEach { sub ->
            subtitleCallback(SubtitleFile(sub.lang, sub.label, sub.url))
        }

        var emitted = false
        playback.qualities.forEach { q ->
            ReelFrenDirectLink.build(q)?.let {
                callback(it)
                emitted = true
            }
        }
        return emitted
    }

    private fun toCard(slug: String, item: HomeItem): SearchResponse? {
        if (item.id.isEmpty() || item.title.isEmpty()) return null
        val url = ReelFrenUrl.page(slug, item.id)
        val poster = ReelFrenClient.absUrl(item.cover).ifEmpty { null }
        val displayTitle = if (item.episodes > 0)
            "${item.title} [${item.episodes} EP]"
        else
            "${item.title} [NEW]"

        return if (item.episodes > 1)
            newTvSeriesSearchResponse(displayTitle, url, TvType.TvSeries) { posterUrl = poster }
        else
            newMovieSearchResponse(displayTitle, url, TvType.Movie) { posterUrl = poster }
    }
}
