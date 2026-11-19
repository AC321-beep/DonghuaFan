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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse

class ReelFrenProvider : MainAPI() {

    override var name = "ReelFren"
    override var mainUrl = ReelFrenConfig.REEL_SITE
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    // THE GLOBAL TAB LOGIC:
    // "All Dramas" is forced as the first (default) tab. 
    // Then we dynamically append all the user's enabled providers from Settings.
    override val mainPage = mainPageOf(
        "all_dramas" to "All Dramas",
        *ReelFrenStore.enabledProviders().map { it.value to it.key }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data // Gets "all_dramas", "pinedrama", etc. from the dropdown
        ReelFrenLog.d("Main/getMainPage", "ENTRY page=$page slug=$slug name=${request.name}")
        
        val items = ReelFrenClient.home(slug, page = page)
        if (items.isEmpty()) {
            ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=0 added=0 hasNext=false")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        
        val mapped = items.mapNotNull { item ->
            if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
            
            // CRITICAL ROUTING: If we are on the global "all_dramas" tab, we MUST use the 
            // item.provider payload so the video routes to the correct specific backend.
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
        // Search globally across all dramas by forcing the "all_dramas" slug
        val items = ReelFrenClient.search("all_dramas", query)
        
        val mapped = items.mapNotNull { item ->
            if (item.id.isEmpty() || item.title.isEmpty()) return@mapNotNull null
            
            val actualSlug = item.provider.ifEmpty { "all_dramas" }
            newMovieSearchResponse(item.title, ReelFrenUrl.page(actualSlug, item.id), TvType.AsianDrama) {
                this.posterUrl = ReelFrenClient.absUrl(item.cover)
                this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
            }
        }
        ReelFrenLog.d("Main/search", "query='$query' results=${mapped.size}")
        return mapped
    }

    override suspend fun load(url: String): LoadResponse? {
        ReelFrenLog.d("Main/load", "url=$url")
        val (slug, id, _) = ReelFrenUrl.parse(url)
        if (id.isEmpty() || slug.isEmpty()) throw ErrorLoadingException("Invalid URL or missing ID")
        
        val d = ReelFrenClient.detail(slug, id) ?: throw ErrorLoadingException("Failed to load detail")
        
        return newMovieLoadResponse(d.title, url, TvType.AsianDrama, d.videos.joinToString(",") { it.vid }) {
            this.posterUrl = ReelFrenClient.absUrl(d.cover)
            this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
            this.plot = d.intro
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

        p.subtitles.forEach { sub ->
            subtitleCallback(SubtitleFile(sub.lang, ReelFrenClient.absUrl(sub.url)))
        }

        var found = false
        p.qualities.forEach { q ->
            val qUrl = ReelFrenClient.absUrl(q.url)
            val format = q.format.ifEmpty { ReelFrenQuality.inferFormat(qUrl) }
            val linkType = ReelFrenQuality.linkType(qUrl, format)
            
            callback(
                com.lagradost.cloudstream3.utils.ExtractorLink(
                    source = name,
                    name = p.server,
                    url = qUrl,
                    referer = "$mainUrl/",
                    quality = ReelFrenQuality.of(q.label),
                    type = linkType,
                    // Send isApiCall = false for standard media requests
                    headers = ReelFrenClient.headers(qUrl, isApiCall = false)
                )
            )
            found = true
        }
        return found
    }
}
