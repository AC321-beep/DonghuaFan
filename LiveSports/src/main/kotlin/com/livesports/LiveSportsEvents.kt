package com.livesports

import android.os.Handler
import android.os.Looper
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = LiveSportsProviderManager.fetchLiveEvents()
        
        // Group events by category
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }
        
        // STRICT CUSTOM ORDER: Football -> Cricket -> Boxing -> Motorsports
        val categoryOrder = listOf("football", "cricket", "boxing", "motorsport", "motorsports")
        
        // Sort the categories. Anything not in the list gets pushed to the bottom.
        val sortedCategories = grouped.keys.sortedBy { category ->
            val index = categoryOrder.indexOf(category.lowercase())
            if (index == -1) Int.MAX_VALUE else index 
        }

        val lists = sortedCategories.mapNotNull { category ->
            val list = grouped[category] ?: return@mapNotNull null
            
            val icon = when (category.lowercase()) { 
                "cricket" -> "🏏"; "football" -> "⚽"; "motorsport", "motorsports" -> "🏎️"; "boxing" -> "🥊"; "basketball" -> "🏀"; "tennis" -> "🎾"; "ice hockey" -> "🏒"; "baseball" -> "⚾"; else -> "📺" 
            }
            
            val items = list.sortedByDescending { isEventLive(it) }.map { event ->
                val status = getEventStatus(event)
                val title = if (status.isNotBlank()) "$status ${createDisplayTitle(event)}" else createDisplayTitle(event)
                val loadData = LiveEventLoadData(
                    eventId = event.id, title = createDisplayTitle(event),
                    poster = generateMatchCardUrl(event), slug = event.slug,
                    formats = event.formats ?: emptyList(), eventInfo = event.eventInfo
                )
                newLiveSearchResponse(title, loadData.toJson(), TvType.Live) { this.posterUrl = generateMatchCardUrl(event) }
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
                it.startTime?.let { time ->
                    try {
                        val parsed = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(time)
                        parsed?.let { d -> append("🕐 ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(d)}\n") }
                    } catch (e: Exception) { append("🕐 $time\n") }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }
        return newLiveStreamLoadResponse(data.title, url, url) { this.posterUrl = data.poster; this.plot = plot }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = parseJson<LiveEventLoadData>(data)
        val streamResponse = fetchChannelStreams(loadData.slug) ?: return false

        streamResponse.streamUrls?.forEach { stream ->
            val serverName = stream.title ?: "Server"
            val (url, headers) = parseStreamLink(stream.link ?: return@forEach)
            if (url.isBlank()) return@forEach
            val resolved = resolveEmbedUrlIfNeeded(url) ?: return@forEach

            when (stream.type) {
                "7" -> {
                    val drmParts = stream.api?.split(":") ?: return@forEach
                    if (drmParts.size == 2) {
                        val kid = drmParts[0].hexToBase64UrlOrNull() ?: drmParts[0]
                        val key = drmParts[1].hexToBase64UrlOrNull() ?: drmParts[1]
                        val drmHeaders = headers.toMutableMap()
                        drmHeaders["drm_scheme"] = "clearkey"
                        drmHeaders["drm_license_key"] = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"${key}\",\"kid\":\"${kid}\"}]}"
                        
                        callback.invoke(newDrmExtractorLink(name, serverName, resolved, INFER_TYPE, CLEARKEY_UUID) {
                            quality = Qualities.Unknown.value; if (drmHeaders.isNotEmpty()) this.headers = drmHeaders
                            this.kid = kid; this.key = key
                        })
                    } else {
                        callback.invoke(createExtractor(resolved, ExtractorLinkType.DASH, headers, serverName))
                    }
                }
                else -> {
                    val type = if (resolved.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    val finalHeaders = headers.toMutableMap()
                    if (type == ExtractorLinkType.M3U8 && !finalHeaders.containsKey("User-Agent")) finalHeaders["User-Agent"] = "Mozilla/5.0"
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
                val key = when (kv[0].lowercase()) { "user-agent" -> "User-Agent"; "referer" -> "Referer"; else -> kv[0] }
                headers[key] = kv[1]
            }
        }
        return parts[0] to headers
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String): String? {
        if (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") || url.contains(".ts")) return url
        return suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val ctx = context
                if (ctx == null) { cont.resume(null); return@post }
                val webView = WebView(ctx).apply {
                    settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false }
                    var urlCaptured = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if ((reqUrl.contains(".m3u8") || reqUrl.contains(".mpd")) && !urlCaptured) {
                                urlCaptured = true; Handler(Looper.getMainLooper()).post { cont.resume(reqUrl); destroy() }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadUrl(url) 
                }
                Handler(Looper.getMainLooper()).postDelayed({ if (!webView.url.isNullOrEmpty()) { try { cont.resume(null); webView.destroy() } catch (e: Exception) {} } }, 15000)
            }
        }
    }

    private fun createDisplayTitle(event: LiveEventData): String {
        val info = event.eventInfo
        return if (info?.teamA != null && info.teamB != null && info.teamA != info.teamB) "${info.teamA} vs ${info.teamB}" else event.title
    }

    private fun getEventStatus(event: LiveEventData): String {
        val info = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()
        return try {
            val format = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { format.parse(it)?.time }
            val end = info.endTime?.let { format.parse(it)?.time }
            when { end != null && now >= end -> "✅"; start != null && now >= start -> "🔴"; start != null && now < start -> "🔜"; else -> "" }
        } catch (e: Exception) { "" }
    }

    private fun isEventLive(event: LiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val format = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { format.parse(it)?.time }
            val end = info.endTime?.let { format.parse(it)?.time }
            (end == null || now < end) && start != null && now >= start
        } catch (e: Exception) { false }
    }

    private fun generateMatchCardUrl(event: LiveEventData): String {
        val info = event.eventInfo
        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=${java.net.URLEncoder.encode(info?.eventName ?: event.title, "UTF-8")}")
            append("&teamA=${java.net.URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")}")
            append("&teamB=${java.net.URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")}")
            info?.teamAFlag?.let { append("&teamAImg=$it") }
            info?.teamBFlag?.let { append("&teamBImg=$it") }
            info?.eventLogo?.let { append("&eventLogo=$it") }
            append("&isLive=${isEventLive(event)}")
        }
    }
}
