package com.livesports

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class LiveSportsProvider : MainAPI() {
    override var name = "Live Sports"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    // Replace with your designated remote configuration entrypoint
    private val firebaseConfigUrl = "https://your-firebase-project.firebaseio.com/config.json"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val config = fetchFirebaseConfig() ?: return null
        
        val encryptedPayload = app.get(config.apiUrl).text
        val decryptedJson = CryptoUtils.decryptAesCbc(encryptedPayload, config.encryptionKey, config.encryptionIv)
        val response = parseJson<LiveEventResponse>(decryptedJson)

        val homePages = response.events.groupBy { it.category }.map { (category, events) ->
            val searchResponses = events.map { event ->
                newLiveSearchResponse(
                    name = event.title,
                    url = event.toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = event.thumbnail
                }
            }
            HomePageList(category, searchResponses)
        }

        return newHomePageResponse(homePages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val event = parseJson<LiveEvent>(url)
        
        return newLiveStreamLoadResponse(
            name = event.title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = event.thumbnail
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val event = parseJson<LiveEvent>(data)
        
        for (stream in event.streams) {
            
            // Option A: WebView Interception
            if (stream.useWebView) {
                val interceptedUrl = interceptWebViewStream(stream.url) ?: stream.url
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${stream.name} (WebView Source)",
                        url = interceptedUrl,
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = if (interceptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        stream.headers?.let { this.headers = it }
                    }
                )
                continue
            }

            // Option B: DRM Protection Configuration
            if (stream.isDrm && stream.clearKeyId != null && stream.clearKeyValue != null) {
                val drmHeaders = stream.headers?.toMutableMap() ?: mutableMapOf()
                drmHeaders["drm_scheme"] = "clearkey"
                drmHeaders["drm_license_key"] = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"${stream.clearKeyValue}\",\"kid\":\"${stream.clearKeyId}\"}]}"
                
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${stream.name} (DRM Protected)",
                        url = stream.url,
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = if (stream.url.contains(".m3u8") || stream.url.contains(".mpd")) ExtractorLinkType.DASH else INFER_TYPE
                    ) {
                        this.headers = drmHeaders
                    }
                )
            } else {
                
                // Option C: Standard Dynamic Direct Link Processing
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = stream.name,
                        url = stream.url,
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = if (stream.url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        stream.headers?.let { this.headers = it }
                    }
                )
            }
        }
        return true
    }

    private suspend fun fetchFirebaseConfig(): FirebaseConfig? {
        return try {
            app.get(firebaseConfigUrl).parsed<FirebaseConfig>()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun interceptWebViewStream(url: String): String? {
        return url
    }
}
