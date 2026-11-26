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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.Locale

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
        private const val CACHE_EXPIRY_MS = 10 * 60 * 1000L

        private val bgSearchScope = CoroutineScope(Dispatchers.IO)
        private val homeSemaphore = Semaphore(3)
        
        // Omni-Fetch Cache (No more slugOffsets needed)
        private val omniMutex = Mutex()
        private var omniCache: Map<String, List<HomeItem>> = emptyMap()
        private var masterCacheList: List<HomeItem> = emptyList()
        private var omniCacheTime: Long = 0L
        private const val OMNI_EXPIRY_MS = 5 * 60 * 1000L
    }

    override val mainPage
        get() = mainPageOf(
            "all_dramas" to "All Dramas",
            *ReelFrenStore.enabledProviders().map { it.value to it.key }.toTypedArray()
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        val isPageOne = page <= 1

        if (isPageOne) {
            val now = System.currentTimeMillis()

            val cachedItems = omniMutex.withLock {
                if (now - omniCacheTime > OMNI_EXPIRY_MS || omniCache.isEmpty()) {
                    ReelFrenLog.d("Main/Omni", "Omni-Cache empty/expired. Fetching master payload...")
                    val masterList = ReelFrenClient.home("all_dramas", page = 1)
                    if (masterList.isNotEmpty()) {
                        omniCache = masterList.groupBy { it.provider.lowercase(Locale.ROOT) }
                        masterCacheList = masterList
                        omniCacheTime = now
                    }
                }
                if (slug == "all_dramas") masterCacheList else omniCache[slug.lowercase(Locale.ROOT)]
            }

            if (!cachedItems.isNullOrEmpty()) {
                ReelFrenLog.d("Main/getMainPage", "INSTANT LOAD: $slug served ${cachedItems.size} items from RAM.")
                return mapToHomePageResponse(request.name, slug, cachedItems, hasNext = true)
            }
        }

        ReelFrenLog.d("Main/getMainPage", "NETWORK LOAD: page=$page slug=$slug")

        val items = homeSemaphore.withPermit {
            ReelFrenClient.home(slug, page = page)
        }

        if (items.isEmpty()) {
            ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=0 added=0 hasNext=false")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        return mapToHomePageResponse(request.name, slug, items, hasNext = true)
    }

    private fun mapToHomePageResponse(
        name: String,
        fallbackSlug: String,
        items: List<HomeItem>,
        hasNext: Boolean
    ): HomePageResponse {
        val mapped = items.mapNotNull { item ->
            if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
            
            val actualSlug = if (item.provider.isNotEmpty()) item.provider else fallbackSlug
            val pageUrl = "${ReelFrenConfig.REEL_SITE}/drama/$actualSlug/${item.id}"
            
            newMovieSearchResponse(item.title, pageUrl, TvType.AsianDrama) {
                val rawCover = item.cover.trim()
                if (rawCover.isNotEmpty()) {
                    val finalCover = if (rawCover.startsWith("http", ignoreCase = true)) rawCover else ReelFrenClient.absUrl(rawCover)
                    this.posterUrl = finalCover
                    if (finalCover.contains("volcengine-forward")) {
                        this.posterHeaders = mapOf(
                            "User-Agent" to ReelFrenClient.GLOBAL_MOBILE_UA,
                            "Referer" to "https://volcengine-forward.storyreel.life/",
                            "Origin" to "https://volcengine-forward.storyreel.life"
                        )
                    }
                }
            }
        }

        return newHomePageResponse(name, mapped, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        if (cleanQuery == cachedSearchQuery && cachedSearchResults.isNotEmpty() && (now - cachedSearchTime) < CACHE_EXPIRY_MS) {
            return cachedSearchResults
        }

        val activeProviders = ReelFrenStore.enabledProviders().values.toList().filter { it != "all_dramas" }
        if (activeProviders.isEmpty()) return emptyList()

        cachedSearchQuery = cleanQuery
        cachedSearchResults = emptyList()
        cachedSearchTime = now

        val primaryProviders = activeProviders.take(3)
        val secondaryProviders = activeProviders.drop(3)
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
                withTimeout(10_000L) { fastResults.addAll(primarySearches.awaitAll().flatten()) }
            } catch (e: TimeoutCancellationException) {
                fastResults.addAll(primarySearches.filter { it.isCompleted && !it.isCancelled }.flatMap { it.getCompleted() })
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
                delay(500L)
            }
            val allResults = (distinctFast + secondaryResults).distinctBy { it.url }
            if (cachedSearchQuery == cleanQuery) cachedSearchResults = allResults
        }

        return distinctFast
    }

    override suspend fun load(url: String): LoadResponse? {
        val (slug, id, _) = ReelFrenUrl.parse(url)
        if (id.isEmpty() || slug.isEmpty()) throw ErrorLoadingException("Invalid URL or missing ID")
        val d: DetailInfo = ReelFrenClient.detail(slug, id) ?: throw ErrorLoadingException("Failed to load detail")
        val coverUrl = ReelFrenClient.absUrl(d.cover).takeIf { it.isNotBlank() }

        return if (d.videos.size > 1 || d.episodes > 1) {
            newTvSeriesLoadResponse(d.title, url, TvType.AsianDrama, d.videos.map { v ->
                newEpisode(ReelFrenUrl.episode(slug, id, v.episode)) {
                    this.name = "Episode ${v.episode}"
                    this.episode = v.episode
                    this.posterUrl = coverUrl
                }
            }) {
                this.posterUrl = coverUrl
                this.posterHeaders = ReelFrenClient.posterHeaders(coverUrl)
                this.plot = d.intro
            }
        } else {
            newMovieLoadResponse(d.title, url, TvType.Movie, ReelFrenUrl.episode(slug, id, d.videos.firstOrNull()?.episode ?: 1)) {
                this.posterUrl = coverUrl
                this.posterHeaders = ReelFrenClient.posterHeaders(coverUrl)
                this.plot = d.intro
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val (slug, id, ep) = ReelFrenUrl.parse(data)
        if (id.isEmpty() || slug.isEmpty()) return@coroutineScope false

        val jobs = listOf(1, 2, 3).map { serverId ->
            async {
                val p = runCatching { ReelFrenClient.playback(slug, id, ep, serverId) }.getOrNull() ?: return@async false
                var found = false

                ReelFrenSubtitles.emit(p.subtitles, p.qualities, subtitleCallback)

                p.qualities.forEach { q ->
                    val qUrl = ReelFrenClient.absUrl(q.url)
                    if (qUrl.isNotBlank() && qUrl.startsWith("http")) {
                        callback(
                            com.lagradost.cloudstream3.utils.ExtractorLink(
                                source = name,
                                name = if (p.server.toIntOrNull() != null) "Server ${p.server}" else p.server,
                                url = qUrl,
                                referer = "${ReelFrenConfig.REEL_SITE}/",
                                quality = ReelFrenQuality.of(q.label),
                                type = ReelFrenQuality.linkType(qUrl, q.format),
                                headers = ReelFrenClient.videoHeaders(qUrl)
                            )
                        )
                        found = true
                    }
                }
                found
            }
        }

        jobs.awaitAll().any { it }
    }
}
