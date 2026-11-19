package com.reel

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse

class ReelFrenProvider(
    override var name: String, // FIX: Changed from 'val' to 'var' to match Cloudstream's MainAPI
    private val slug: String
) : MainAPI() {

    override var mainUrl = ReelFrenConfig.REEL_SITE
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        ReelFrenLog.d("Main/getMainPage", "ENTRY page=$page slug=$slug name=$name")
        val items = ReelFrenClient.home(slug, page = page)
        if (items.isEmpty()) {
            ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=0 added=0 hasNext=false")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val mapped = items.map { item ->
            newMovieSearchResponse(item.title, ReelFrenUrl.page(slug, item.id), TvType.AsianDrama) {
                this.posterUrl = ReelFrenClient.absUrl(item.cover)
                this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
            }
        }
        ReelFrenLog.d("Main/getMainPage", "slug=$slug raw=${items.size} added=${mapped.size} hasNext=true")
        return newHomePageResponse(request.name, mapped, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        ReelFrenLog.d("Main/search", "query='$query' slug=$slug")
        val items = ReelFrenClient.search(slug, query)
        val mapped = items.map { item ->
            newMovieSearchResponse(item.title, ReelFrenUrl.page(slug, item.id), TvType.AsianDrama) {
                this.posterUrl = ReelFrenClient.absUrl(item.cover)
                this.posterHeaders = ReelFrenClient.posterHeaders(this.posterUrl)
            }
        }
        ReelFrenLog.d("Main/search", "query='$query' results=${mapped.size}")
        return mapped
    }

    override suspend fun load(url: String): LoadResponse? {
        ReelFrenLog.d("Main/load", "url=$url")
        val (_, id, _) = ReelFrenUrl.parse(url)
        if (id.isEmpty()) throw ErrorLoadingException("Invalid URL or missing ID")
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
        val (_, id, ep) = ReelFrenUrl.parse(data)
        if (id.isEmpty()) return false
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
                    headers = ReelFrenClient.headers(qUrl)
                )
            )
            found = true
        }
        return found
    }
}
