package com.livesports

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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

    private val defaultCricifyBaseUrl = "https://cfymarkscanjiostar80.top"
    
    companion object {
        private const val TYPE_CNC = "cnc"
        private const val TYPE_CRICIFY = "cricify"
    }

    data class TargetPayload(val type: String, val jsonPayload: String)
    data class CncPayload(val title: String, val poster: String?, val streamUrl: String)
    
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

    private val cncWorkingEndpoints = listOf(
        mapOf("title" to "TATA PLAY", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQz_qYe3Y4S5bXXVlPtXQnqtAkLw1-no57QHhPyMgWE0SQmxujzHxZKiDs&s=10", "catLink" to "https://hotstarlive.delta-cloud.workers.dev/?token=240bb9-374e2e-3c13f0-4a7xz5"),
        mapOf("title" to "HOTSTAR", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRWwYjMvB58DMLsL9Ii2fhvw6NBYvD1iVCjOMU8TXBLJt0eibLGOjoRkLJP&s=10", "catLink" to "https://hotstar-live-event.alpha-circuit.workers.dev/?token=a13d9c-4b782a-6c90fd-9a1b84"),
        mapOf("title" to "JIO IND", "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png", "catLink" to "https://jiotv.byte-vault.workers.dev/?token=42e4f5-2d873b-3c37d8-7f3f50"),
        mapOf("title" to "SONY IN", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU", "catLink" to "https://sonyliv.logic-lane.workers.dev?token=a14d9c-4b782a-6c90fd-9a1b84"),
        mapOf("title" to "SUN DIRECT", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSwc4OuqPmOP-Fi9dhfiDw_q-s3rOmgCPla_IaE76VD2KRQ7c4KHeI2zJY&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/suntv.m3u"),
        mapOf("title" to "VOOT IND", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfS6QZFts2FoedMGZE28H7Kh158PsrNIiabFBVJMy_jXa8Tvvb9WAlut8&s=10", "catLink" to "https://jiocinema-live.cloud-hatchh.workers.dev/?token=42e4f5-2d414b-3c37d8-5f3f45"),
        mapOf("title" to "ZEE5", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU", "catLink" to "https://zee5.cloud-hatchh.workers.dev/?token=42e4f5-2d413b-3c37d8-7f3f35"),
        mapOf("title" to "ICC TV", "image" to "https://m.media-amazon.com/images/I/31F7ropt9OL.png", "catLink" to "https://icc.alpha-circuit.workers.dev/?token=42e4f5-2d863b-3c37d8-7f3f69"),
        mapOf("title" to "JIOTV+ S2", "image" to "https://i.ibb.co/VY9ND7rY/image.png", "catLink" to "https://jiotvplus.byte-vault.workers.dev/?token=42e4f5-2d863b-3c38d8-7f3f51"),
        mapOf("title" to "T SPORTS", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRJ0QvfKyjAqcCOumIXjcuYg505GnaBeVk2lQ&usqp=CAU", "catLink" to "https://fifabangladesh2-xyz-ekkj.spidy.online/AYN/tsports.m3u")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()

        val cncCards = cncWorkingEndpoints.mapNotNull { endpoint ->
            val title = endpoint["title"] ?: return@mapNotNull null
            val image = endpoint["image"]
            val link = endpoint["catLink"] ?: return@mapNotNull null

            val payload = TargetPayload(TYPE_CNC, CncPayload(title, image, link).toJson())
            newLiveSearchResponse(title, payload.toJson(), TvType.Live) { this.posterUrl = image }
        }
        homePages.add(HomePageList("📺 Live Networks (CNC Sync)", cncCards, isHorizontalImages = true))

        try {
            val baseUrl = try {
                FirebaseRemoteConfigFetcher.getProviderApiUrl() ?: defaultCricifyBaseUrl
            } catch (e: Exception) { defaultCricifyBaseUrl } catch (e: LinkageError) { defaultCricifyBaseUrl }
            
            val apiUrl = "$baseUrl/categories/live-events.txt"

            val encryptedPayload = app.get(apiUrl).text
            if (encryptedPayload.isNotBlank()) {
                val decryptedJson = CryptoUtils.decryptData(encryptedPayload)
                if (!decryptedJson.isNullOrBlank()) {
                    val events = parseJson<List<LiveEventData>>(decryptedJson).filter { it.publish == 1 }

                    val cricifyGroups = events.groupBy { it.cat ?: it.eventInfo?.eventCat ?: "Live Events" }.map { (category, categoryEvents) ->
                        val searchResponses = categoryEvents.map { event ->
                            val payload = TargetPayload(TYPE_CRICIFY, event.toJson())
                            val info = event.eventInfo
                            val displayTitle = if (!info?.teamA.isNullOrBlank() && !info?.teamB.isNullOrBlank()) {
                                val isLive = event.title.contains("live", ignoreCase = true) || info?.isHot == "1"
                                val prefix = if (isLive) "🔴 " else "➡️ "
                                "$prefix${info.teamA} vs ${info.teamB}"
                            } else {
                                event.title
                            }

                            newLiveSearchResponse(displayTitle, payload.toJson(), TvType.Live) {
                                this.posterUrl = event.image ?: info?.eventLogo
                            }
                        }
                        HomePageList(category, searchResponses, isHorizontalImages = true)
                    }
                    homePages.addAll(cricifyGroups)
                }
            }
        } catch (e: Exception) {}

        return newHomePageResponse(homePages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> { return emptyList() }

    override suspend fun load(url: String): LoadResponse? {
        val target = parseJson<TargetPayload>(url)

        return if (target.type == TYPE_CNC) {
            val payload = parseJson<CncPayload>(target.jsonPayload)
            newLiveStreamLoadResponse(payload.title, url, url) { this.posterUrl = payload.poster }
        } else {
            val event = parseJson<LiveEventData>(target.jsonPayload)
            newLiveStreamLoadResponse(event.title, url, url) {
                this.posterUrl = event.image ?: event.eventInfo?.eventLogo
                this.plot = event.eventInfo?.startTime?.let { "Starts: $it" }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = parseJson<TargetPayload>(data)

        if (target.type == TYPE_CNC) {
            val payload = parseJson<CncPayload>(target.jsonPayload)
            val streamUrl = payload.streamUrl

            // Parse M3U text files to extract the real video URLs
            if (streamUrl.endsWith(".m3u", ignoreCase = true) || streamUrl.endsWith(".txt", ignoreCase = true)) {
                try {
                    val m3uText = app.get(streamUrl).text
                    val links = m3uText.lines().filter { it.trim().startsWith("http") }
                    
                    if (links.isNotEmpty()) {
                        links.forEachIndexed { index, link ->
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${payload.title} - Server ${index + 1}",
                                    url = link.trim(),
                                    type = ExtractorLinkType.M3U8 // Force M3U8
                                )
                            )
                        }
                    } else {
                        // Fallback if the file didn't contain raw HTTP lines
                        callback.invoke(newExtractorLink(this.name, payload.title, streamUrl, ExtractorLinkType.M3U8))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            } else {
                // Cloudflare Workers - Bypass inference and force M3U8 for ExoPlayer
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = payload.title,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    }
                )
            }
            return true

        } else {
            // --- CRICIFY EXTRACTION ---
            val event = parseJson<LiveEventData>(target.jsonPayload)
            val baseUrl = try { FirebaseRemoteConfigFetcher.getProviderApiUrl() ?: defaultCricifyBaseUrl } catch (e: Exception) { defaultCricifyBaseUrl }
            val streamUrl = "$baseUrl/channels/${event.slug.lowercase()}.txt"

            val encryptedStreamPayload = app.get(streamUrl).text
            if (encryptedStreamPayload.isBlank()) return false

            val decryptedStreamJson = CryptoUtils.decryptData(encryptedStreamPayload) ?: return false
            val streamResponse = parseJson<ChannelStreamResponse>(decryptedStreamJson)

            streamResponse.streamUrls?.forEach { stream ->
                val serverName = stream.title ?: "Server"
                val rawLink = stream.link ?: return@forEach
                val (cleanUrl, headers) = parseStreamLink(rawLink)

                if (stream.type == "7" && stream.api != null) {
                    val drmInfo = stream.api.split(":")
                    if (drmInfo.size == 2) {
                        val drmKidBytes = drmInfo[0].replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val drmKidBase64 = Base64.encodeToString(drmKidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        val drmKeyBytes = drmInfo[1].replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        val drmKeyBase64 = Base64.encodeToString(drmKeyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        
                        val drmHeaders = headers.toMutableMap()
                        drmHeaders["drm_scheme"] = "clearkey"
                        drmHeaders["drm_license_key"] = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"${drmKeyBase64}\",\"kid\":\"${drmKidBase64}\"}]}"

                        callback.invoke(newDrmExtractorLink(this.name, "$serverName (DRM)", cleanUrl, ExtractorLinkType.DASH, CLEARKEY_UUID) {
                            this.referer = ""
                            this.quality = Qualities.Unknown.value
                            this.headers = drmHeaders
                            this.kid = drmKidBase64
                            this.key = drmKeyBase64
                        })
                    }
                } else {
                    val finalHeaders = headers.toMutableMap()
                    if (cleanUrl.contains(".m3u8") && !finalHeaders.containsKey("User-Agent")) {
                        finalHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                    }
                    callback.invoke(newExtractorLink(this.name, serverName, cleanUrl, ExtractorLinkType.M3U8) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                        if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                    })
                }
            }
            return true
        }
    }

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
