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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    override val mainPage = mainPageOf(
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
        ReelFrenLog.d("Main/search", "query='$query'")
        
        val activeProviders = ReelFrenStore.enabledProviders().values.toList()
        if (activeProviders.isEmpty()) {
            ReelFrenLog.d("Main/search", "No providers enabled. Aborting search.")
            return emptyList()
        }

        // The API requires a provider to search. 
        // We launch parallel asynchronous searches for every enabled provider and merge the results.
        return coroutineScope {
            val deferredSearches = activeProviders.map { slug ->
                async {
                    runCatching {
                        ReelFrenClient.search(slug, query)
                    }.getOrDefault(emptyList())
                }
            }
            
            val allItems = deferredSearches.awaitAll().flatten()
            
            val mapped = allItems.mapNotNull { item ->
                if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
                
                val actualSlug = item.provider.ifEmpty { "all_dramas" }
                newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                    this.posterUrl = ReelFrenClient.absUrl(item.cover)
                    this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
                }
            }
            
            // Remove duplicates (same ID and provider) just in case the API glitches
            val distinctResults = mapped.distinctBy { it.url }
            
            ReelFrenLog.d("Main/search", "query='$query' distinct_results=${distinctResults.size}")
            distinctResults
        }
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

        var found = false
        p.qualities.forEach { q ->
            val qUrl = ReelFrenClient.absUrl(q.url)
            if (qUrl.isBlank() || !qUrl.startsWith("http")) return@forEach

            val format = q.format.ifEmpty { ReelFrenQuality.inferFormat(qUrl) }
            val linkType = ReelFrenQuality.linkType(qUrl, format)
            
            val finalName = if (p.server.isBlank() || p.server == "1") "ReelFren" else "Server ${p.server}"

            callback(
                com.lagradost.cloudstream3.utils.ExtractorLink(
                    source = name,
                    name = finalName,
                    url = qUrl,
                    referer = "${ReelFrenConfig.REEL_SITE}/", 
                    quality = ReelFrenQuality.of(q.label),
                    type = linkType,
                    headers = ReelFrenClient.headers(qUrl) 
                )
            )
            found = true
        }
        return found
    }
}
