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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
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
        
        // INJECT THE SETTINGS BUTTON ON THE HOMEPAGE
        if (page == 1 && slug == "all_dramas") {
            val settingsItem = newMovieSearchResponse("⚙️ ReelFren Settings", "reelfren://settings", TvType.Movie) {
                this.posterUrl = "https://ui-avatars.com/api/?name=Settings&background=2B2B2B&color=fff&size=512"
            }
            return newHomePageResponse(request.name, listOf(settingsItem) + mapped, hasNext = true)
        }
        
        return newHomePageResponse(request.name, mapped, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        ReelFrenLog.d("Main/search", "query='$query'")
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
        
        // INTERCEPT THE SETTINGS BUTTON CLICK
        if (url == "reelfren://settings") {
            withContext(Dispatchers.Main) {
                ReelFrenCloudflare.activity()?.let { act ->
                    ReelFrenPlugin.showSettingsDialog(act)
                }
            }
            throw ErrorLoadingException("Opened Settings Menu. Please press back to return.")
        }
        
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

        p.subtitles.forEach { sub ->
            var cleanUrl = ReelFrenClient.absUrl(sub.url)
            
            if (cleanUrl.contains("/api/proxy/") && cleanUrl.contains("?url=")) {
                val encodedUrl = cleanUrl.substringAfter("?url=")
                cleanUrl = runCatching { URLDecoder.decode(encodedUrl, "UTF-8") }.getOrDefault(cleanUrl)
            }

            if (cleanUrl.isNotBlank()) {
                val displayLang = sub.label.ifEmpty { sub.lang }.ifEmpty { "English" }
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                
                subtitleCallback(SubtitleFile(displayLang, cleanUrl))
                ReelFrenLog.d("Main/loadLinks", "Emitted Subtitle: $displayLang -> $cleanUrl")
            }
        }

        var found = false
        p.qualities.forEach { q ->
            val qUrl = ReelFrenClient.absUrl(q.url)
            if (qUrl.isBlank() || !qUrl.startsWith("http")) return@forEach

            val format = q.format.ifEmpty { ReelFrenQuality.inferFormat(qUrl) }
            val linkType = ReelFrenQuality.linkType(qUrl, format)
            
            val serverStr = if (p.server.isBlank() || p.server == "1") "ReelFren" else "Server ${p.server}"
            val finalName = if (q.label.contains("Auto", true) || q.label.isBlank()) {
                serverStr
            } else {
                "$serverStr - ${q.label}"
            }

            val host = ReelFrenCloudflare.hostOf(qUrl)
            val streamHeaders = mutableMapOf<String, String>()
            streamHeaders["User-Agent"] = ReelFrenStore.userAgent(host)
            
            if (host.contains("reelfren.net")) {
                streamHeaders["Referer"] = "$mainUrl/"
                val cookie = ReelFrenStore.cookie(host)
                if (cookie.isNotEmpty()) streamHeaders["Cookie"] = cookie
            }

            callback(
                com.lagradost.cloudstream3.utils.ExtractorLink(
                    source = name,
                    name = finalName,
                    url = qUrl,
                    referer = if (host.contains("reelfren.net")) "$mainUrl/" else "", 
                    quality = ReelFrenQuality.of(q.label),
                    type = linkType,
                    headers = streamHeaders
                )
            )
            found = true
        }
        return found
    }
}
