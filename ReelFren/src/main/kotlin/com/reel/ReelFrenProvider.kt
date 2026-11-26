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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
        private val slugOffsets = ConcurrentHashMap<String, Int>()
        
        // Omni-Fetch Cache Variables
        private val omniMutex = Mutex()
        private var omniCache: Map<String, List<HomeItem>> = emptyMap()
        private var masterCacheList: List<HomeItem> = emptyList()
        private var omniCacheTime: Long = 0L
        private const val OMNI_EXPIRY_MS = 5 * 60 * 1000L // Refresh master payload every 5 mins
    }

    override val mainPage get() = mainPageOf(
        "all_dramas" to "All Dramas",
        *ReelFrenStore.enabledProviders().map { it.value to it.key }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        val isPageOne = page <= 1

        if (isPageOne) {
            slugOffsets.remove(slug)
            val now = System.currentTimeMillis()
            
            // OMNI-FETCH: Lock prevents 20 tabs from fetching the API simultaneously. 
            // The first tab downloads the 1.1MB Master Payload, the other 19 tabs instantly read it from RAM.
            val cachedItems = omniMutex.withLock {
                if (now - omniCacheTime > OMNI_EXPIRY_MS || omniCache.isEmpty()) {
                    ReelFrenLog.d("Main/Omni", "Omni-Cache empty/expired. Fetching 1.1MB Master payload...")
                    
                    val masterList = ReelFrenClient.home("all_dramas", page = 1)
                    if (masterList.isNotEmpty()) {
                        omniCache = masterList.groupBy { it.provider.lowercase(Locale.ROOT) }
                        masterCacheList = masterList
                        omniCacheTime = now
                    }
                }
                if (slug == "all_dramas") masterCacheList else omniCache[slug.lowercase(Locale.ROOT)]
            }

            // If the provider was in the master payload, return it instantly!
            if (!cachedItems.isNullOrEmpty()) {
                ReelFrenLog.d("Main/getMainPage", "⚡ INSTANT LOAD: $slug served ${cachedItems.size} items from RAM.")
                slugOffsets[slug] = cachedItems.size
                return mapToHomePageResponse(request.name, slug, cachedItems, hasNext = true)
            }
        }

        // FALLBACK TO NETWORK: If it's Page 2+, or if a rare provider wasn't in the Omni-Cache
        val currentOffset = if (isPageOne) 0 else slugOffsets[slug] ?: ((page - 1) * 20)
        ReelFrenLog.d("Main/getMainPage", "🌐 NETWORK LOAD: page=$page offset=$currentOffset slug=$slug")

        val items = homeSemaphore.withPermit {
            ReelFrenClient.home(slug, page = page, offset = currentOffset)
        }
        
        if (items.isEmpty()) {
            slugOffsets.remove(slug)
            ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=0 added=0 hasNext=false")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        slugOffsets[slug] = currentOffset + items.size
        return mapToHomePageResponse(request.name, slug, items, hasNext = true)
    }

    // Helper function to keep code clean
    private fun mapToHomePageResponse(name: String, fallbackSlug: String, items: List<HomeItem>, hasNext: Boolean): HomePageResponse {
        val mapped = items.mapNotNull { item ->
            if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
            val actualSlug = item.provider.ifEmpty { fallbackSlug }
            newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                this.posterUrl = ReelFrenClient.absUrl(item.cover).takeIf { it.isNotBlank() }
                this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
            }
        }.distinctBy { it.url }
        
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
                withTimeout(10000L) { fastResults.addAll(primarySearches.awaitAll().flatten()) }
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
                        secondaryResults.add(newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                            this.posterUrl = ReelFrenClient.absUrl(item.cover).takeIf { it.isNotBlank() }
                            this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
                        })
                    }
                }
                kotlinx.coroutines.delay(500)
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
        
        if (d.videos.size > 1 || d.episodes > 1) {
            return newTvSeriesLoadResponse(d.title, url, TvType.AsianDrama, d.videos.map { v ->
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
            return newMovieLoadResponse(d.title, url, TvType.Movie, ReelFrenUrl.episode(slug, id, d.videos.firstOrNull()?.episode ?: 1)) {
                this.posterUrl = coverUrl
                this.posterHeaders = ReelFrenClient.posterHeaders(coverUrl)
                this.plot = d.intro
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
        val (slug, id, ep) = ReelFrenUrl.parse(data)
        if (id.isEmpty() || slug.isEmpty()) return false
        val foundLinks = AtomicBoolean(false)

        coroutineScope {
            listOf(1, 2, 3, 4, 5).forEach { serverId ->
                launch {
                    val p = runCatching { ReelFrenClient.playback(slug, id, ep, serverId) }.getOrNull() ?: return@launch
                    ReelFrenSubtitles.emit(p.subtitles, p.qualities, subtitleCallback)
                    p.qualities.forEach { q ->
                        val qUrl = ReelFrenClient.absUrl(q.url)
                        if (qUrl.isNotBlank() && qUrl.startsWith("http")) {
                            callback(com.lagradost.cloudstream3.utils.ExtractorLink(
                                source = name,
                                name = if (p.server.toIntOrNull() != null) "Server ${p.server}" else p.server,
                                url = qUrl,
                                referer = "${ReelFrenConfig.REEL_SITE}/",
                                quality = ReelFrenQuality.of(q.label),
                                type = ReelFrenQuality.linkType(qUrl, q.format),
                                headers = ReelFrenClient.videoHeaders(qUrl)
                            ))
                            foundLinks.set(true)
                        }
                    }
                }
            }
        }
        return foundLinks.get()
    }
}
