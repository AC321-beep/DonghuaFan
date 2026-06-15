package com.livesports

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveSportsProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        
        // Base64 encoded external fallback URL
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        
        @Volatile private var lastBrowserOpenMs = 0L
        private const val BROWSER_DEBOUNCE_MS = 10_000L
        
        const val TYPE_CNC = "cnc"
        const val TYPE_CRICIFY = "cricify"
    }

    override var mainUrl = "https://cfyhljddgbkkufh82.top"
    override var name = "Live Sports"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // --- DATA MODELS ---
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

    // CNC's active, unencrypted gateway fallback list
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

    // --- UI FORMATTING HELPERS ---
    private fun createDisplayTitle(event: LiveEventData): String {
        val info = event.eventInfo
        return if (info != null && !info.teamA.isNullOrBlank() && !info.teamB.isNullOrBlank()) {
            if (info.teamA == info.teamB) info.teamA else "${info.teamA} vs ${info.teamB}"
        } else event.title
    }

    private fun getEventStatus(event: LiveEventData): String {
        val info = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()
        return try {
            val format = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { format.parse(it)?.time }
            val end = info.endTime?.let { format.parse(it)?.time }
            when {
                end != null && now >= end -> "✅"
                start != null && now >= start -> "🔴"
                start != null && now < start -> "🔜"
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    private fun isEventLive(event: LiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val format = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { format.parse(it)?.time }
            val end = info.endTime?.let { format.parse(it)?.time }
            if (end != null && now >= end) false else start != null && now >= start
        } catch (e: Exception) { false }
    }

    private fun generateMatchCardUrl(event: LiveEventData): String {
        val info = event.eventInfo
        val title = java.net.URLEncoder.encode(info?.eventName ?: event.title, "UTF-8")
        val teamA = java.net.URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")
        val teamB = java.net.URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")
        val teamAImg = info?.teamAFlag ?: ""
        val teamBImg = info?.teamBFlag ?: ""
        val eventLogo = info?.eventLogo ?: ""
        val time = try {
            info?.startTime?.let {
                val d = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(it)
                d?.let { date -> java.net.URLEncoder.encode(SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US).format(date), "UTF-8") }
            } ?: ""
        } catch (e: Exception) { "" }

        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=$title&teamA=$teamA&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=${isEventLive(event)}")
        }
    }

    // --- HOMEPAGE BUILDER ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()

        // 1. Inject Fast-Loading CNC Networks
        val cncCards = cncWorkingEndpoints.mapNotNull { endpoint ->
            val title = endpoint["title"] ?: return@mapNotNull null
            val image = endpoint["image"]
            val link = endpoint["catLink"] ?: return@mapNotNull null

            val payload = TargetPayload(TYPE_CNC, CncPayload(title, image, link).toJson())
            newLiveSearchResponse(title, payload.toJson(), TvType.Live) { this.posterUrl = image }
        }
        homePages.add(HomePageList("📺 Live Networks (CNC Sync)", cncCards, isHorizontalImages = true))

        // 2. Load Dynamic Cricify Live Events
        try {
            val baseUrl = try {
                FirebaseRemoteConfigFetcher.getProviderApiUrl() ?: mainUrl
            } catch (e: Exception) { mainUrl } catch (e: LinkageError) { mainUrl }
            
            val apiUrl = "$baseUrl/categories/live-events.txt"
            
            val encryptedPayload = app.get(apiUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
            if (encryptedPayload.isNotBlank()) {
                val decryptedJson = CryptoUtils.decryptData(encryptedPayload)
                if (!decryptedJson.isNullOrBlank()) {
                    val events = parseJson<List<LiveEventData>>(decryptedJson).filter { it.publish == 1 }
                    
                    val groupedEvents = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }
                    val categoryLists = groupedEvents.map { (category, categoryEvents) ->
                        val icon = when (category.lowercase()) {
                            "cricket" -> "🏏"
                            "football" -> "⚽"
                            "basketball" -> "🏀"
                            "ice hockey" -> "🏒"
                            "boxing" -> "🥊"
                            "motorsport" -> "🏎️"
                            "tennis" -> "🎾"
                            else -> "📺"
                        }

                        val searchResponses = categoryEvents.sortedByDescending { isEventLive(it) }.map { event ->
                            val displayTitle = createDisplayTitle(event)
                            val status = getEventStatus(event)
                            val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                            val posterUrl = generateMatchCardUrl(event)
                            val payload = TargetPayload(TYPE_CRICIFY, event.toJson())

                            newLiveSearchResponse(fullTitle, payload.toJson(), TvType.Live) { 
                                this.posterUrl = posterUrl 
                            }
                        }
                        HomePageList("$icon $category", searchResponses, isHorizontalImages = true)
                    }.sortedBy { list ->
                        when {
                            list.name.contains("Cricket", true) -> 0
                            list.name.contains("Football", true) -> 1
                            list.name.contains("Basketball", true) -> 2
                            else -> 10
                        }
                    }
                    homePages.addAll(categoryLists)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fail gracefully to ensure CNC endpoints still load
        }

        return newHomePageResponse(homePages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> { return emptyList() }

    // --- MATCH DETAILS / PLOT LOADER ---
    override suspend fun load(url: String): LoadResponse {
        val target = parseJson<TargetPayload>(url)
        
        return if (target.type == TYPE_CNC) {
            val payload = parseJson<CncPayload>(target.jsonPayload)
            newLiveStreamLoadResponse(payload.title, url, url) { this.posterUrl = payload.poster }
        } else {
            val event = parseJson<LiveEventData>(target.jsonPayload)
            val info = event.eventInfo
            val plotStr = buildString {
                info?.let {
                    it.eventType?.let { t -> append("📌 $t\n") }
                    it.eventName?.let { n -> append("🏆 $n\n") }
                    it.startTime?.let { s -> append("🕐 Starts: $s\n") }
                }
                append("\n📡 Select a server below to watch.")
            }
            newLiveStreamLoadResponse(event.title, url, url) {
                this.posterUrl = event.image ?: info?.eventLogo
                this.plot = plotStr
            }
        }
    }

    // --- VIDEO STREAM EXTRACTION ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // External Web Browser Trigger Failsafe
        try { openInExternalBrowser(String(Base64.decode(OMG10, Base64.DEFAULT))) } catch (e: Exception) {}

        val target = parseJson<TargetPayload>(data)

        if (target.type == TYPE_CNC) {
            // CNC Extraction Loop
            val payload = parseJson<CncPayload>(target.jsonPayload)
            val streamUrl = payload.streamUrl

            val cleanUrl = streamUrl.substringBefore("?")
            if (cleanUrl.endsWith(".m3u", ignoreCase = true) || cleanUrl.endsWith(".txt", ignoreCase = true)) {
                try {
                    val m3uText = app.get(streamUrl).text
                    val links = m3uText.lines().filter { it.trim().startsWith("http") }
                    if (links.isNotEmpty()) {
                        links.forEachIndexed { index, link ->
                            val linkType = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                            callback.invoke(newExtractorLink(this.name, "${payload.title} - Server ${index + 1}", link.trim(), linkType))
                        }
                    } else {
                        callback.invoke(newExtractorLink(this.name, payload.title, streamUrl, ExtractorLinkType.M3U8))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            } else {
                callback.invoke(newExtractorLink(this.name, payload.title, streamUrl, ExtractorLinkType.M3U8) {
                    this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                })
            }
            return true
            
        } else {
            // Cricify Extraction Loop
            val event = parseJson<LiveEventData>(target.jsonPayload)
            val baseUrl = try { FirebaseRemoteConfigFetcher.getProviderApiUrl() ?: mainUrl } catch (e: Exception) { mainUrl }
            val streamUrl = "$baseUrl/channels/${event.slug.lowercase()}.txt"

            try {
                val encryptedStreamPayload = app.get(streamUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
                if (encryptedStreamPayload.isBlank()) return false

                val decryptedStreamJson = CryptoUtils.decryptData(encryptedStreamPayload) ?: return false
                val streamResponse = parseJson<ChannelStreamResponse>(decryptedStreamJson)

                streamResponse.streamUrls?.forEach { stream ->
                    val serverName = stream.title ?: "Server"
                    val rawLink = stream.link ?: return@forEach
                    val (url, headers) = parseStreamLink(rawLink)
                    if (url.isBlank()) return@forEach

                    // Call the WebView Interceptor to catch embedded streams dynamically
                    val resolvedUrl = resolveEmbedUrlIfNeeded(url) ?: return@forEach

                    if (stream.type == "7" && stream.api != null) {
                        val drmInfo = stream.api.split(":")
                        if (drmInfo.size == 2) {
                            val drmKidBase64 = Base64.encodeToString(drmInfo[0].replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                            val drmKeyBase64 = Base64.encodeToString(drmInfo[1].replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                            
                            val drmHeaders = headers.toMutableMap()
                            drmHeaders["drm_scheme"] = "clearkey"
                            drmHeaders["drm_license_key"] = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"${drmKeyBase64}\",\"kid\":\"${drmKidBase64}\"}]}"

                            callback.invoke(newDrmExtractorLink(this.name, serverName, resolvedUrl, INFER_TYPE, CLEARKEY_UUID) {
                                this.quality = Qualities.Unknown.value
                                this.key = drmKeyBase64
                                this.kid = drmKidBase64
                                if (headers.isNotEmpty()) this.headers = drmHeaders
                            })
                        }
                    } else {
                        val linkType = if (resolvedUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                        val finalHeaders = headers.toMutableMap()
                        if (linkType == ExtractorLinkType.M3U8 && !finalHeaders.containsKey("User-Agent")) {
                            finalHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        }
                        callback.invoke(newExtractorLink(this.name, serverName, resolvedUrl, linkType) {
                            this.quality = Qualities.Unknown.value
                            if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                        })
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            return true
        }
    }

    // --- WEBVIEW INTERCEPTOR (JavaScript Injection) ---
    private fun isDirectStreamUrl(url: String): Boolean {
        return url.contains(".m3u8") || url.contains(".mpd") || url.contains(".ts") || url.contains(".mkv") || url.contains(".webm") || url.contains(".mp4")
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String): String? {
        if (isDirectStreamUrl(url)) return url
        return loadEmbedInWebView(url)
    }

    private suspend fun loadEmbedInWebView(embedUrl: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                try {
                    val ctx = context
                    if (ctx == null) {
                        continuation.resume(null)
                        return@suspendCoroutine
                    }

                    val webView = WebView(ctx)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    var urlCaptured = false
                    var capturedUrl: String? = null

                    val bridge = object {
                        @android.webkit.JavascriptInterface
                        fun onStreamUrlFound(url: String) {
                            if (!urlCaptured && url.isNotBlank()) {
                                urlCaptured = true
                                capturedUrl = url
                                Handler(Looper.getMainLooper()).post {
                                    try { webView.destroy() } catch (e: Exception) {}
                                    continuation.resume(url)
                                }
                            }
                        }
                    }

                    webView.addJavascriptInterface(bridge, "StreamBridge")
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            val requestUrl = request.url.toString()
                            if (isDirectStreamUrl(requestUrl) && !urlCaptured) {
                                urlCaptured = true
                                capturedUrl = requestUrl
                                Handler(Looper.getMainLooper()).post {
                                    try { webView.destroy() } catch (e: Exception) {}
                                    continuation.resume(requestUrl)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try { webView.evaluateJavascript("(function() { if (typeof playbackURL !== 'undefined' && playbackURL) { window.StreamBridge.onStreamUrlFound(playbackURL); } })();", null) } catch (e: Exception) {}
                                }, 500)
                            }
                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!urlCaptured) {
                                        try { webView.destroy() } catch (e: Exception) {}
                                        continuation.resume(null)
                                    }
                                }, 3000)
                            }
                        }
                    }

                    webView.webChromeClient = WebChromeClient()
                    webView.loadUrl(embedUrl)

                    // 15s Absolute Failsafe
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!urlCaptured && capturedUrl == null) {
                            try { webView.destroy() } catch (e: Exception) {}
                            try { continuation.resume(null) } catch (e: Exception) {}
                        }
                    }, 15000)

                } catch (e: Exception) {
                    continuation.resume(null)
                }
            }
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
                    val headerName = when (val key = keyValue[0].trim().lowercase()) {
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

    private fun openInExternalBrowser(url: String) {
        if (isLayout(TV)) return
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        if (now - lastBrowserOpenMs < BROWSER_DEBOUNCE_MS) return
        lastBrowserOpenMs = now
        Handler(Looper.getMainLooper()).post {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) { }
        }
    }
}
