package com.livesports

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.app

class LiveSportsProvider : MainAPI() {
    override var name = "Live Sports"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var lang = "en"

    // The fallback URL if Firebase fetch fails
    private val defaultBaseUrl = "https://cfymarkscanjiostar80.top"

    data class ChannelStreamResponse(
        val streamUrls: List<StreamUrl>?,
        val related: List<LiveEventData>?,
        val prevChannel: String?,
        val nextChannel: String?
    )

    data class StreamUrl(
        val api: String?, 
        val id: Int?,
        val link: String?, 
        val title: String?, 
        val type: String?, 
        val webLink: String?
    )

    // 1. BUILDS THE HOMEPAGE (Fixes the Blind Screen)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val baseUrl = FirebaseRemoteConfigFetcher.getProviderApiUrl() ?: defaultBaseUrl
        val apiUrl = "$baseUrl/categories/live-events.txt"

        val encryptedPayload = app.get(apiUrl).text
        if (encryptedPayload.isBlank()) return null

        val decryptedJson = CryptoUtils.decryptData(encryptedPayload) ?: return null
        
        // Parse the events and filter only published ones
        val events = parseJson<List<LiveEventData>>(decryptedJson).filter { it.publish == 1 }

        val homePages = events.groupBy { it.cat ?: it.eventInfo?.eventCat ?: "Live Events" }.map { (category, categoryEvents) ->
            val searchResponses = categoryEvents.map { event ->
                newLiveSearchResponse(
                    name = event.title,
                    url = event.toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = event.image ?: event.eventInfo?.eventLogo
                }
            }
            HomePageList(category, searchResponses)
        }

        return newHomePageResponse(homePages, hasNext = false)
    }

    // 2. SEARCH (Disabled for Live Events)
    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    // 3. LOADS THE MATCH DETAILS
    override suspend fun load(url: String): LoadResponse? {
        val event = parseJson<LiveEventData>(url)
        
        return newLiveStreamLoadResponse(
            name = event.title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = event.image ?: event.eventInfo?.eventLogo
            this.plot = event.eventInfo?.startTime?.let { "Starts: $it" }
        }
    }

    // 4. EXTRACTS THE VIDEO STREAMS
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val event = parseJson<LiveEventData>(data)
        val baseUrl = FirebaseRemoteConfigFetcher.getProviderApiUrl() ?: defaultBaseUrl
        val streamUrl = "$baseUrl/channels/${event.slug.lowercase()}.txt"

        val encryptedStreamPayload = app.get(streamUrl).text
        if (encryptedStreamPayload.isBlank()) return false

        val decryptedStreamJson = CryptoUtils.decryptData(encryptedStreamPayload) ?: return false
        val streamResponse = parseJson<ChannelStreamResponse>(decryptedStreamJson)

        streamResponse.streamUrls?.forEach { stream ->
            val serverName = stream.title ?: "Server"
            val rawLink = stream.link ?: return@forEach

            // Split URLs with pipe headers (e.g., http://video.mp4|User-Agent=...)
            val (cleanUrl, headers) = parseStreamLink(rawLink)

            if (stream.type == "7" && stream.api != null) {
                // DRM Protection Handling
                val drmInfo = stream.api.split(":")
                if (drmInfo.size == 2) {
                    val drmKidBytes = drmInfo[0].replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val drmKidBase64 = Base64.encodeToString(drmKidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                    val drmKeyBytes = drmInfo[1].replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val drmKeyBase64 = Base64.encodeToString(drmKeyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                    
                    val drmHeaders = headers.toMutableMap()
                    drmHeaders["drm_scheme"] = "clearkey"
                    drmHeaders["drm_license_key"] = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"${drmKeyBase64}\",\"kid\":\"${drmKidBase64}\"}]}"

                    callback.invoke(
                        newDrmExtractorLink(
                            this.name,
                            "$serverName (DRM)",
                            cleanUrl,
                            if (cleanUrl.contains(".m3u8") || cleanUrl.contains(".mpd")) ExtractorLinkType.DASH else INFER_TYPE,
                            CLEARKEY_UUID
                        ) {
                            this.referer = ""
                            this.quality = Qualities.Unknown.value
                            this.headers = drmHeaders
                            this.kid = drmKidBase64
                            this.key = drmKeyBase64
                        }
                    )
                }
            } else {
                // Standard Direct Link Processing
                val finalHeaders = headers.toMutableMap()
                if (cleanUrl.contains(".m3u8") && !finalHeaders.containsKey("User-Agent")) {
                    finalHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36"
                }

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        serverName,
                        cleanUrl,
                        if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                        if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                    }
                )
            }
        }
        return true
    }

    // Helper function to split pipe headers
    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        if (!link.contains("|")) return Pair(link, headers)
        
        val parts = link.split("|", limit = 2)
        val url = parts[0]
        if (parts.size > 1) {
            parts[1].split("&").forEach { headerPair ->
                val keyValue = headerPair.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val headerName = when (key.lowercase()) {
                        "user-agent" -> "User-Agent"
                        "referer" -> "Referer"
                        "origin" -> "Origin"
                        "cookie" -> "Cookie"
                        else -> key
                    }
                    headers[headerName] = keyValue[1].trim()
                }
            }
        }
        return Pair(url, headers)
    }
}
