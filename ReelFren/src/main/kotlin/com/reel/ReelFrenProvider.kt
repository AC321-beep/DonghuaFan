package com.reel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private val seenIds = ConcurrentHashMap<String, MutableSet<String>>()

    override val mainPage: List<MainPageData>
        get() {
            val enabled = ReelFrenStore.enabledProviders()
            ReelFrenLog.d("Main/mainPage", "returning ${enabled.size} enabled pages")
            return enabled.map { (display, slug) -> slug to display }
                .let { mainPageOf(*it.toTypedArray()) }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        ReelFrenLog.d("Main/getMainPage", "ENTRY page=$page slug=$slug name=${request.name}")
        val items = mutableListOf<SearchResponse>()
        var rawCount = 0

        val seen = seenIds.getOrPut(slug) { ConcurrentHashMap.newKeySet() }
        if (page <= 1) seen.clear()

        try {
            val homeItems = ReelFrenClient.home(slug, page = page)
            rawCount = homeItems.size
            for (item in homeItems) {
                if (seen.add(item.id)) {
                    toCard(slug, item)?.let { items.add(it) }
                }
            }
            ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=$rawCount added=${items.size}")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ReelFrenLog.t("Main/getMainPage", e)
        }

        return newHomePageResponse(request, items, hasNext = rawCount > 0)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        ReelFrenLog.d("Main/search", "ENTRY query='$term'")

        val results = ConcurrentHashMap<String, SearchResponse>()
        val deadline = System.currentTimeMillis() + 45_000L
        val enabledSlugs = ReelFrenStore.enabledProviders().values.toList()
        val semaphore = Semaphore(4)

        coroutineScope {
            enabledSlugs.map { slug ->
                async {
                    if (System.currentTimeMillis() > deadline) return@async
                    semaphore.withPermit {
                        if (System.currentTimeMillis() > deadline) return@withPermit
                        try {
                            ReelFrenClient.search(slug, term).forEach { item ->
                                toCard(slug, item)?.let { card ->
                                    results.putIfAbsent(card.url, card)
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            ReelFrenLog.t("Main/search", e)
                        }
                    }
                }
            }.awaitAll()
        }

        ReelFrenLog.d("Main/search", "EXIT results=${results.size}")
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        ReelFrenLog.d("Main/load", "ENTRY url=$url")
        val (slug, id, _) = ReelFrenUrl.parse(url)
        if (slug.isEmpty() || id.isEmpty()) return null

        val info = ReelFrenClient.detail(slug, id) ?: return null
        val cover = ReelFrenClient.absUrl(info.cover).ifEmpty { null }
        val imgHeaders = ReelFrenClient.posterHeaders(cover)
        val plot = info.intro.takeIf { it.isNotEmpty() }

        return if (info.videos.size > 1 || info.episodes > 1) {
            newTvSeriesLoadResponse(
                name = info.title,
                url = url,
                type = TvType.TvSeries,
                episodes = info.videos.map { v ->
                    newEpisode(ReelFrenUrl.episode(slug, id, v.episode)) {
                        name = "Episode ${v.episode}"
                        episode = v.episode
                        posterUrl = cover
                    }
                }
            ) {
                posterUrl = cover
                posterHeaders = imgHeaders
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
                posterHeaders = imgHeaders
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
        ReelFrenLog.d("Main/loadLinks", "ENTRY data=$data isCasting=$isCasting")
        val (slug, id, episode) = ReelFrenUrl.parse(data)
        if (slug.isEmpty() || id.isEmpty()) return false

        val playback = ReelFrenClient.playback(slug, id, episode) ?: return false

        playback.subtitles.forEach { sub ->
            subtitleCallback(
                newSubtitleFile(sub.lang, sub.label) { this.url = sub.url }
            )
        }

        var emitted = false
        playback.qualities.forEach { q ->
            ReelFrenDirectLink.build(q)?.let {
                callback(it)
                emitted = true
            }
        }
        ReelFrenLog.d("Main/loadLinks", "EXIT emitted=$emitted")
        return emitted
    }

    private fun toCard(slug: String, item: HomeItem): SearchResponse? {
        if (item.id.isEmpty() || item.title.isEmpty()) return null
        val url = ReelFrenUrl.page(slug, item.id)
        val poster = ReelFrenClient.absUrl(item.cover).ifEmpty { null }
        val imgHeaders = ReelFrenClient.posterHeaders(poster)
        val displayTitle = if (item.episodes > 0) {
            "${item.title} [${item.episodes} EP]"
        } else {
            "${item.title} [NEW]"
        }

        return if (item.episodes > 1) {
            newTvSeriesSearchResponse(displayTitle, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.posterHeaders = imgHeaders
            }
        } else {
            newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                this.posterUrl = poster
                this.posterHeaders = imgHeaders
            }
        }
    }
}
