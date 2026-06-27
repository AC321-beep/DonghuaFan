package com.livesports

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LiveSportsEvents : MainAPI() {
    companion object { var context: android.content.Context? = null }

    override var mainUrl = "https://tv.noobon.top"
    override var name = "LiveSports"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 1. Centralized Enum for clear states (Order defines sorting priority)
    enum class EventState(val emoji: String, val text: String) {
        LIVE("🔴", "Live"),
        UPCOMING("🔜", "Upcoming"),
        ENDED("✅", "Ended"),
        UNKNOWN("📺", "Unknown")
    }

    // 2. Single robust date parser
    private fun parseEventDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(dateStr)?.time
        } catch (e: Exception) { null }
    }

    // 3. Centralized state logic with a 4-hour fallback
    private fun getEventState(event: LiveEventData): EventState {
        val info = event.eventInfo ?: return EventState.UNKNOWN
        val start = parseEventDate(info.startTime) ?: return EventState.UNKNOWN
        
        // If API doesn't provide an end time, assume the match lasts 4 hours
        val end = parseEventDate(info.endTime) ?: (start + TimeUnit.HOURS.toMillis(4))
        val now = System.currentTimeMillis()

        return when {
            now >= end -> EventState.ENDED
            now in start..end -> EventState.LIVE
            now < start -> EventState.UPCOMING
            else -> EventState.UNKNOWN
        }
    }

    // 4. 12-Hour format clean time string
    private fun getFormattedTime(event: LiveEventData): String {
        val timeMs = parseEventDate(event.eventInfo?.startTime) ?: return ""
        return try {
            // 12-hour format: hh:mm a (e.g., 05:30 PM)
            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(timeMs))
        } catch (e: Exception) { "" }
    }

    private fun createDisplayTitle(event: LiveEventData): String {
        val info = event.eventInfo
        return if (info?.teamA != null && info.teamB != null && info.teamA != info.teamB) "${info.teamA} vs ${info.teamB}" else event.title
    }

    // 5. Fixed Match Card Generator
   private fun generateMatchCardUrl(event: LiveEventData): String {
        val info = event.eventInfo
        val state = getEventState(event)
        val time = getFormattedTime(event)
        
        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=${java.net.URLEncoder.encode(info?.eventName ?: event.title, "UTF-8")}")
            append("&teamA=${java.net.URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")}")
            append("&teamB=${java.net.URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")}")
            info?.teamAFlag?.let { append("&teamAImg=$it") }
            info?.teamBFlag?.let { append("&teamBImg=$it") }
            info?.eventLogo?.let { append("&eventLogo=$it") }
            
            // THE FIX: Send the string "Upcoming" directly to the isLive parameter.
            // If the worker supports it, this overrides the "ENDED" text on the badge.
            when (state) {
                EventState.LIVE -> append("&isLive=true")
                EventState.ENDED -> append("&isLive=false")
                EventState.UPCOMING -> append("&isLive=Upcoming") 
                else -> {}
            }
            
            // Only append time for upcoming matches
            if (state == EventState.UPCOMING && time.isNotBlank()) {
                append("&time=${java.net.URLEncoder.encode(time, "UTF-8")}")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = LiveSportsProviderManager.fetchLiveEvents()
        
        // Group events by category
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }
        
        // STRICT CUSTOM ORDER
        val categoryOrder = listOf("football", "cricket", "boxing", "motorsport")
        val sortedCategories = grouped.keys.sortedBy { category ->
            val index = categoryOrder.indexOf(category.lowercase())
            if (index == -1) Int.MAX_VALUE else index 
        }

        val lists = sortedCategories.mapNotNull { category ->
            val list = grouped[category] ?: return@mapNotNull null
            
            val icon = when (category.lowercase()) { 
                "cricket" -> "🏏"; "football" -> "⚽"; "motorsport" -> "🏎️"; "boxing" -> "🥊"; "basketball" -> "🏀"; "tennis" -> "🎾"; "ice hockey" -> "🏒"; "baseball" -> "⚾"; else -> "📺" 
            }
            
            // SORTING LOGIC: State Priority, then Chronological
            val sortedList = list.sortedWith(Comparator { a, b ->
                val stateA = getEventState(a)
                val stateB = getEventState(b)
                
                // 1. Sort by State: LIVE(0) -> UPCOMING(1) -> ENDED(2) -> UNKNOWN(3)
                if (stateA != stateB) return@Comparator stateA.compareTo(stateB)
                
                // 2. Sort by Time based on state
                val timeA = parseEventDate(a.eventInfo?.startTime) ?: 0L
                val timeB = parseEventDate(b.eventInfo?.startTime) ?: 0L
                
                when (stateA) {
                    // For upcoming, closest matches appear first (Ascending)
                    EventState.UPCOMING -> timeA.compareTo(timeB) 
                    // For live/ended, most recently started matches appear first (Descending)
                    else -> timeB.compareTo(timeA) 
                }
            })

            val items = sortedList.map { event ->
                val state = getEventState(event)
                val time = getFormattedTime(event)
                val baseTitle = createDisplayTitle(event)
                
                val title = buildString {
                    if (state != EventState.UNKNOWN) append("${state.emoji} ")
                    append(baseTitle)
                    
                    if (state == EventState.UPCOMING && time.isNotBlank()) {
                        append(" • $time")
                    }
                }
                
                val loadData = LiveEventLoadData(
                    eventId = event.id, title = baseTitle,
                    poster = generateMatchCardUrl(event), slug = event.slug,
                    formats = event.formats ?: emptyList(), eventInfo = event.eventInfo
                )
                
                newLiveSearchResponse(title, loadData.toJson(), TvType.Live) { 
                    this.posterUrl = loadData.poster 
                }
            }
            HomePageList("$icon $category", items, isHorizontalImages = true)
        }
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> { return emptyList() }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)
        val plot = buildString {
            data.eventInfo?.let {
                it.eventType?.let { append("📌 $it\n") }
                it.eventName?.let { append("🏆 $it\n") }
                it.startTime?.let { timeStr ->
                    val parsedMs = parseEventDate(timeStr)
                    if (parsedMs != null) {
                        // 12-hour format applied to stream load info as well
                        append("🕐 ${SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US).format(Date(parsedMs))}\n")
                    } else {
                        append("🕐 $timeStr\n")
                    }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }
        return newLiveStreamLoadResponse(data.title, url, url) { 
            this.posterUrl = data.poster
            this.plot = plot 
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LiveEventLoadData>(data)
        val streamResponse = fetchChannelStreams(loadData.slug) ?: return false

        streamResponse.streamUrls?.forEach { stream ->
            val serverName = stream.title ?: "Server"
            
            // 1. Extract URL and robust headers
            val (url, headers) = parseStreamLink(stream.link ?: return@forEach)
            if (url.isBlank()) return@forEach
            
            // 2. Pass BOTH url and headers to WebView if extraction is needed
            val resolved = resolveEmbedUrlIfNeeded(url, headers) ?: return@forEach

            when (stream.type) {
                "7" -> { // DRM / ClearKey Streams
                    val drmParts = stream.api?.split(":") ?: return@forEach
                    if (drmParts.size == 2) {
                        val kid = drmParts[0].hexToBase64UrlOrNull() ?: drmParts[0]
                        val key = drmParts[1].hexToBase64UrlOrNull() ?: drmParts[1]
                        val drmHeaders = headers.toMutableMap()
                        
                        drmHeaders["drm_scheme"] = "clearkey"
                        drmHeaders["drm_license_key"] = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"${key}\",\"kid\":\"${kid}\"}]}"
                        
                        callback.invoke(newDrmExtractorLink(name, serverName, resolved, INFER_TYPE, CLEARKEY_UUID) {
                            quality = Qualities.Unknown.value
                            if (drmHeaders.isNotEmpty()) this.headers = drmHeaders
                            this.kid = kid
                            this.key = key
                        })
                    } else {
                        callback.invoke(createExtractor(resolved, ExtractorLinkType.DASH, headers, serverName))
                    }
                }
                else -> { // Standard HLS/DASH Streams
                    val type = if (resolved.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    val finalHeaders = headers.toMutableMap()
                    if (type == ExtractorLinkType.M3U8 && !finalHeaders.containsKey("User-Agent")) {
                        finalHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                    }
                    callback.invoke(createExtractor(resolved, type, finalHeaders, serverName))
                }
            }
        }
        return true
    }

    private suspend fun createExtractor(url: String, type: ExtractorLinkType?, headers: Map<String, String>, name: String): ExtractorLink {
        return newExtractorLink(this.name, name, url, type ?: INFER_TYPE) {
            quality = Qualities.Unknown.value
            if (headers.isNotEmpty()) this.headers = headers
        }
    }

    private suspend fun fetchChannelStreams(slug: String): ChannelStreamResponse? {
        return try {
            val url = "${LiveSportsProviderManager.getBaseUrl()}/channels/${slug.lowercase()}.txt"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val decrypted = CryptoUtils.decryptData(response.body?.string()?.trim() ?: "")
                if (!decrypted.isNullOrBlank()) parseJson(decrypted) else null
            } else null
        } catch (e: Exception) { null }
    }

    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        if (!link.contains("|")) return link to emptyMap()
        val parts = link.split("|", limit = 2)
        val headers = mutableMapOf<String, String>()
        
        parts.getOrNull(1)?.split("&")?.forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val key = when (kv[0].lowercase()) { 
                    "user-agent" -> "User-Agent"
                    "referer" -> "Referer"
                    "origin" -> "Origin"
                    else -> kv[0] 
                }
                headers[key] = kv[1]
            }
        }

        // AUTO-INJECT MISSING ORIGIN FOR CDN 2004 FIX
        if (headers.containsKey("Referer") && !headers.containsKey("Origin")) {
            try {
                val uri = URI(headers["Referer"]!!)
                val portStr = if (uri.port != -1) ":${uri.port}" else ""
                headers["Origin"] = "${uri.scheme}://${uri.host}$portStr"
            } catch (e: Exception) { }
        }

        // ANTI-BUFFERING HEADERS FOR EXOPLAYER
        headers["Connection"] = "keep-alive"

        return parts[0] to headers
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String, headers: Map<String, String>): String? {
        if (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") || url.contains(".ts")) return url
        
        return suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val ctx = context
                if (ctx == null) { cont.resume(null); return@post }
                
                var urlCaptured = false
                
                val webView = WebView(ctx).apply {
                    settings.apply { 
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false 
                        userAgentString = headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if ((reqUrl.contains(".m3u8") || reqUrl.contains(".mpd")) && !urlCaptured) {
                                urlCaptured = true
                                Handler(Looper.getMainLooper()).post { cont.resume(reqUrl); destroy() }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    
                    loadUrl(url, headers) 
                }
                
                Handler(Looper.getMainLooper()).postDelayed({ 
                    if (!urlCaptured) { 
                        try { 
                            cont.resume(null)
                            webView.destroy() 
                        } catch (e: Exception) {} 
                    } 
                }, 15000)
            }
        }
    }
}
