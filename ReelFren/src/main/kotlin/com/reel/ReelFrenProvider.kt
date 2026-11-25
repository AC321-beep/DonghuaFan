package com.reel

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

class ReelFrenProvider : MainAPI() {

    override var name = "ReelFren"
    override var mainUrl = ReelFrenConfig.REEL_SITE
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    companion object {
        private var cachedSearchQuery: String = ""
        private var cachedSearchResults: List<SearchResponse> = emptyList()
        private var cachedSearchTime: Long = 0L
        private const val CACHE_EXPIRY_MS = 10 * 60 * 1000L // 10 minutes

        private val bgSearchScope = CoroutineScope(Dispatchers.IO)
        
        // [NEW] Traffic light to strictly limit homepage fetching to 3 concurrent tabs
        private val homeSemaphore = Semaphore(3)
    }

    override val mainPage get() = mainPageOf(
        "all_dramas" to "All Dramas",
        *ReelFrenStore.enabledProviders().map { it.value to it.key }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        ReelFrenLog.d("Main/getMainPage", "ENTRY page=$page slug=$slug name=${request.name}")

        // [NEW] Waits for a permit. Only 3 network requests will execute at a single time.
        val items: List<HomeItem> = homeSemaphore.withPermit {
            ReelFrenClient.home(slug, page = page)
        }
        
        if (items.isEmpty()) {
            ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=0 added=0 hasNext=false")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val mapped = items.mapNotNull { item: HomeItem ->
            if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null

            val actualSlug = item.provider.ifEmpty { slug }

            newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                this.posterUrl = ReelFrenClient.absUrl(item.cover).takeIf { it.isNotBlank() }
                this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
            }
        }
        ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=${items.size} added=${mapped.size} hasNext=true")
        return newHomePageResponse(request.name, mapped, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val cleanQuery = query.trim()
        ReelFrenLog.d("Main/search", "query='$cleanQuery'")

        if (cleanQuery.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        if (cleanQuery == cachedSearchQuery && cachedSearchResults.isNotEmpty() && (now - cachedSearchTime) < CACHE_EXPIRY_MS) {
            ReelFrenLog.d("Main/search", "Returning ${cachedSearchResults.size} cached results for '$cleanQuery'")
            return cachedSearchResults
        }

        val activeProviders = ReelFrenStore.enabledProviders().values.toList()
        if (activeProviders.isEmpty()) {
            ReelFrenLog.d("Main/search", "No providers enabled. Aborting search.")
            return emptyList()
        }

        cachedSearchQuery = cleanQuery
        cachedSearchResults = emptyList()
        cachedSearchTime = now

        val validProviders = activeProviders.filter { it != "all_dramas" }
        if (validProviders.isEmpty()) return emptyList()

        val primaryProviders = validProviders.take(3)
        val secondaryProviders = validProviders.drop(3)

        val fastResults = mutableListOf<SearchResponse>()

        coroutineScope {
            val primarySearches = primaryProviders.map { slug ->
                async {
                    val items = runCatching { ReelFrenClient.search(slug, cleanQuery) }.getOrDefault(emptyList())
                    items.mapNotNull { item ->
                        if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
                        val actualSlug = item.provider.ifEmpty { slug }
                        newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                            this.posterUrl = ReelFrenClient.absUrl(item.cover).takeIf { it.isNotBlank() }
                            this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
                        }
                    }
                }
            }

            try {
                withTimeout(10000L) {
                    fastResults.addAll(primarySearches.awaitAll().flatten())
                }
            } catch (e: TimeoutCancellationException) {
                val completed = primarySearches.filter { it.isCompleted && !it.isCancelled }.flatMap { it.getCompleted() }
                fastResults.addAll(completed)
                ReelFrenLog.d("Main/search", "Timeout hit on primary search. Returning ${completed.size} fast results.")
            }
        }

        val distinctFast = fastResults.distinctBy { it.url }
        cachedSearchResults = distinctFast

        bgSearchScope.launch {
            val secondaryResults = mutableListOf<SearchResponse>()
            for (slug in secondaryProviders) {
                val items = runCatching { ReelFrenClient.search(slug, cleanQuery) }.getOrDefault(emptyList())
                items.forEach { item ->
                    if (item.id.isNotEmpty() && item.title.isNotEmpty()) {
                        val actualSlug = item.provider.ifEmpty { slug }
                        secondaryResults.add(
                            newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                                this.posterUrl = ReelFrenClient.absUrl(item.cover).takeIf { it.isNotBlank() }
                                this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
                            }
                        )
                    }
                }
                kotlinx.coroutines.delay(500)
            }

            val allResults = (distinctFast + secondaryResults).distinctBy { it.url }
            if (cachedSearchQuery == cleanQuery) {
                cachedSearchResults = allResults
                ReelFrenLog.d("Main/search", "Background cache expanded to ${allResults.size} items")
            }
        }

        return distinctFast
    }

    override suspend fun load(url: String): LoadResponse? {
        ReelFrenLog.d("Main/load", "url=$url")
        val (slug, id, _) = ReelFrenUrl.parse(url)
        if (id.isEmpty() || slug.isEmpty()) throw ErrorLoadingException("Invalid URL or missing ID")

        val d: DetailInfo = ReelFrenClient.detail(slug, id) ?: throw ErrorLoadingException("Failed to load detail")

        val coverUrl = ReelFrenClient.absUrl(d.cover).takeIf { it.isNotBlank() }
        val coverHeaders = ReelFrenClient.posterHeaders(coverUrl)

        if (d.videos.size > 1 || d.episodes > 1) {
            return newTvSeriesLoadResponse(
                name = d.title,
                url = url,
                type = TvType.AsianDrama,
                episodes = d.videos.map { v: VideoEntry ->
                    newEpisode(ReelFrenUrl.episode(slug, id, v.episode)) {
                        this.name = "Episode ${v.episode}"
                        this.episode = v.episode
                        this.posterUrl = coverUrl
                    }
                }
            ) {
                this.posterUrl = coverUrl
                this.posterHeaders = coverHeaders
                this.plot = d.intro
            }
        } else {
            val singleVideoId = d.videos.firstOrNull()?.episode ?: 1
            return newMovieLoadResponse(
                name = d.title,
                url = url,
                type = TvType.Movie,
                dataUrl = ReelFrenUrl.episode(slug, id, singleVideoId)
            ) {
                this.posterUrl = coverUrl
                this.posterHeaders = coverHeaders
                this.plot = d.intro
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        ReelFrenLog.d("Main/loadLinks", "url=$data")
        val (slug, id, ep) = ReelFrenUrl.parse(data)
        if (id.isEmpty() || slug.isEmpty()) return false

        val foundLinks = AtomicBoolean(false)

        coroutineScope {
            val serverIds = listOf(1, 2, 3, 4, 5)

            serverIds.forEach { serverId ->
                launch {
                    val p = runCatching { ReelFrenClient.playback(slug, id, ep, serverId) }.getOrNull() ?: return@launch

                    ReelFrenSubtitles.emit(p.subtitles, p.qualities, subtitleCallback)

                    p.qualities.forEach { q ->
                        launch {
                            val qUrl = ReelFrenClient.absUrl(q.url)
                            if (qUrl.isBlank() || !qUrl.startsWith("http")) return@launch

                            val serverNameString = if (p.server.toIntOrNull() != null) "Server ${p.server}" else p.server

                            val link = com.lagradost.cloudstream3.utils.ExtractorLink(
                                source = name,
                                name = serverNameString,
                                url = qUrl,
                                referer = "${ReelFrenConfig.REEL_SITE}/",
                                quality = ReelFrenQuality.of(q.label),
                                type = ReelFrenQuality.linkType(qUrl, q.format),
                                headers = ReelFrenClient.videoHeaders(qUrl)
                            )

                            foundLinks.set(true)
                            callback(link)
                        }
                    }
                }
            }
        }

        return foundLinks.get()
    }
}
