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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope

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
    }

    override val mainPage get() = mainPageOf(
        "all_dramas" to "All Dramas",
        *ReelFrenStore.enabledProviders().map { it.value to it.key }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        ReelFrenLog.d("Main/getMainPage", "ENTRY page=$page slug=$slug name=${request.name}")
        
        val items = ReelFrenClient.home(slug, page = page)
        if (items.isEmpty()) {
            ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=0 added=0 hasNext=false")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        
        val mapped = items.mapNotNull { item ->
            if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
            
            val actualSlug = item.provider.ifEmpty { slug }
            
            newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                this.posterUrl = ReelFrenClient.absUrl(item.cover)
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

        val deferredSearches = activeProviders.map { slug ->
            bgSearchScope.async {
                val items = runCatching { ReelFrenClient.search(slug, cleanQuery) }.getOrDefault(emptyList())
                items.mapNotNull { item ->
                    if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
                    val actualSlug = item.provider.ifEmpty { "all_dramas" }
                    newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                        this.posterUrl = ReelFrenClient.absUrl(item.cover)
                        this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
                    }
                }
            }
        }

        val fastResults = mutableListOf<SearchResponse>()
        
        try {
            withTimeout(4000L) {
                fastResults.addAll(deferredSearches.awaitAll().flatten())
            }
        } catch (e: TimeoutCancellationException) {
            val completed = deferredSearches.filter { it.isCompleted && !it.isCancelled }.flatMap { it.getCompleted() }
            fastResults.addAll(completed)
            ReelFrenLog.d("Main/search", "Timeout hit. Returning ${completed.size} fast results.")
        }

        val distinctFast = fastResults.distinctBy { it.url }
        cachedSearchResults = distinctFast

        bgSearchScope.launch {
            val allLateResults = deferredSearches.awaitAll().flatten().distinctBy { it.url }
            if (cachedSearchQuery == cleanQuery) {
                cachedSearchResults = allLateResults
                if (allLateResults.size > distinctFast.size) {
                    ReelFrenLog.d("Main/search", "Background cache silently grew from ${distinctFast.size} to ${allLateResults.size} items")
                }
            }
        }

        return distinctFast
    }

    override suspend fun load(url: String): LoadResponse? {
        ReelFrenLog.d("Main/load", "url=$url")
        val (slug, id, _) = ReelFrenUrl.parse(url)
        if (id.isEmpty() || slug.isEmpty()) throw ErrorLoadingException("Invalid URL or missing ID")
        
        val d = ReelFrenClient.detail(slug, id) ?: throw ErrorLoadingException("Failed to load detail")
        val coverUrl = ReelFrenClient.absUrl(d.cover)
        val coverHeaders = ReelFrenClient.posterHeaders(coverUrl)

        if (d.videos.size > 1 || d.episodes > 1) {
            return newTvSeriesLoadResponse(
                name = d.title,
                url = url,
                type = TvType.AsianDrama,
                episodes = d.videos.map { v ->
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
        
        val p = ReelFrenClient.playback(slug, id, ep) ?: return false

        ReelFrenSubtitles.emit(p.subtitles, p.qualities, subtitleCallback)

        val workingLinks = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()

        coroutineScope {
            // FIX: Removed the incorrect indexed mapping. We now correctly map the true server name parsed from the JSON.
            val validationJobs = p.qualities.map { q ->
                async {
                    val qUrl = ReelFrenClient.absUrl(q.url)
                    if (qUrl.isBlank() || !qUrl.startsWith("http")) return@async null

                    val isAlive = runCatching {
                        val checkHeaders = ReelFrenClient.videoHeaders(qUrl).toMutableMap()
                        checkHeaders["Range"] = "bytes=0-100" 
                        val response = com.lagradost.cloudstream3.app.get(qUrl, headers = checkHeaders, timeout = 4000L)
                        response.code in 200..299 || response.code in 300..399
                    }.getOrDefault(false)

                    if (!isAlive) {
                        ReelFrenLog.d("Main/loadLinks", "Filtered out DEAD link: $qUrl")
                        return@async null
                    }

                    val format = q.format.ifEmpty { ReelFrenQuality.inferFormat(qUrl) }
                    
                    // TRUE SERVER NAMING: If the API says this is "Server 2", it is Server 2. 
                    // If the API says it's "1", we name it "Server 1". This prevents the UI from duplicating "Server 1 - 720p" "Server 2 - 540p" incorrectly.
                    val baseServer = if (p.server.isBlank()) "Server 1" else if (p.server.toIntOrNull() != null) "Server ${p.server}" else p.server
                    val cleanLabel = q.label.replace("(?i)auto".toRegex(), "").trim()
                    
                    val finalName = if (cleanLabel.isNotEmpty()) {
                        "$baseServer - $cleanLabel"
                    } else {
                        baseServer
                    }

                    val isObfuscated = qUrl.contains("melolostatic.com", ignoreCase = true) || qUrl.contains("volcengine", ignoreCase = true)

                    if (isObfuscated) {
                        // --- NATIVE JAVASCRIPT BYPASS FIX ---
                        // Because the raw stream is encrypted, we MUST extract the true video source by loading the URL
                        // in a background WebView and grabbing the final decrypted `.m3u8` or `.mp4` string.
                        try {
                            val webViewHtml = com.lagradost.cloudstream3.app.get(qUrl, interceptor = com.lagradost.cloudstream3.network.WebViewResolver(Regex("""\.m3u8|\.mp4"""))).text
                            
                            // Extract the raw, decrypted video URL from the resolved HTML
                            val decryptedUrl = Regex("""(https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)""").find(webViewHtml)?.groupValues?.get(1)
                            
                            if (!decryptedUrl.isNullOrBlank()) {
                                com.lagradost.cloudstream3.utils.ExtractorLink(
                                    source = name,
                                    name = finalName,
                                    url = decryptedUrl,
                                    referer = "${ReelFrenConfig.REEL_SITE}/", 
                                    quality = ReelFrenQuality.of(q.label),
                                    type = ReelFrenQuality.linkType(decryptedUrl, format), 
                                    headers = ReelFrenClient.videoHeaders(decryptedUrl) 
                                ).also { 
                                    callback(it)
                                    workingLinks.add(it)
                                }
                            }
                        } catch (e: Exception) {
                            ReelFrenLog.d("Main/loadLinks", "WebView resolution failed for $qUrl")
                        }
                    } else {
                        // Standard unencrypted extraction
                        com.lagradost.cloudstream3.utils.ExtractorLink(
                            source = name,
                            name = finalName,
                            url = qUrl,
                            referer = "${ReelFrenConfig.REEL_SITE}/", 
                            quality = ReelFrenQuality.of(q.label),
                            type = ReelFrenQuality.linkType(qUrl, format), 
                            headers = ReelFrenClient.videoHeaders(qUrl) 
                        ).also {
                            callback(it)
                            workingLinks.add(it)
                        }
                    }
                }
            }
            
            validationJobs.awaitAll()
        }
        
        return workingLinks.isNotEmpty()
    }
}
