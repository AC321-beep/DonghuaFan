package com.livesports

import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LiveSportsEvents : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://cfyhljddgbkkufh82.top"
    override var name = "🏏 LiveSports Events"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Data classes
    data class LiveEventData(
        val id: Int, val title: String, val slug: String, val cat: String?,
        val formats: List<LiveEventFormat>?, val eventInfo: LiveEventInfo?
    )
    data class LiveEventFormat(val name: String?, val link: String?, val api: String?, val type: String?)
    data class LiveEventInfo(
        val teamA: String?, val teamB: String?, val teamAFlag: String?, val teamBFlag: String?,
        val eventName: String?, val eventType: String?, val eventCat: String?, val eventLogo: String?,
        val startTime: String?, val endTime: String?
    )
    data class ChannelStreamResponse(
        val streamUrls: List<StreamUrl>?, val related: List<LiveEventData>?,
        val prevChannel: String?, val nextChannel: String?
    )
    data class StreamUrl(
        val api: String?, val id: Int?, val link: String?, val title: String?,
        val type: String?, val webLink: String?
    )
    data class LiveEventLoadData(
        val eventId: Int, val title: String, val poster: String, val slug: String,
        val formats: List<LiveEventFormat>, val eventInfo: LiveEventInfo?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = LiveSportsProviderManager.fetchLiveEvents()
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }
        val lists = grouped.map { (category, list) ->
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

    override suspend fun search(query: String): List<SearchResponse> {
        val events = LiveSportsProviderManager.fetchLiveEvents()
        return events.filter { event ->
            listOf(event.title, event.eventInfo?.teamA, event.eventInfo?.teamB, event.eventInfo?.eventName)
                .joinToString(" ").contains(query, ignoreCase = true)
        }.map { event ->
            val status = getEventStatus(event)
            val title = if (status.isNotBlank()) "$status ${createDisplayTitle(event)}" else createDisplayTitle(event)
            val loadData = LiveEventLoadData(
                eventId = event.id, title = createDisplayTitle(event),
                poster = generateMatchCardUrl(event), slug = event.slug,
                formats = event.formats ?: emptyList(), eventInfo = event.eventInfo
            )
            newLiveSearchResponse(title, loadData.toJson(), TvType.Live) { this.posterUrl = generateMatchCardUrl(event) }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)
        val plot = buildString {
            data.eventInfo?.let {
                it.eventType?.let { append("📌 $it\n") }
                it.eventName?.let { append("🏆 $it\n") }
                it.startTime?.let { time ->
                    try {
                        val parsed = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(time)
                        append("🕐 ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(parsed)}\n")
                    } catch (e: Exception) { append("🕐 $time\n") }
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
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
                        val kid = drmParts[0].replace("-", "").hexToBase64UrlOrNull() ?: drmParts[0]
                        val key = drmParts[1].replace("-", "").hexToBase64UrlOrNull() ?: drmParts[1]
                        callback.invoke(newDrmExtractorLink(name, serverName, resolved, INFER_TYPE, CLEARKEY_UUID) {
                            quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) this.headers = headers
                            this.kid = kid
                            this.key = key
                        })
                    } else {
                        callback.invoke(createExtractor(resolved, ExtractorLinkType.DASH, headers, serverName))
                    }
                }
                else -> {
                    val type = if (resolved.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    callback.invoke(createExtractor(resolved, type, headers, serverName))
                }
            }
        }
        return true
    }

    private suspend fun createExtractor(url: String, type: ExtractorLinkType?, headers: Map<String, String>, name: String): ExtractorLink {
        return newExtractorLink(this.name, name, url, type) {
            quality = Qualities.Unknown.value
            if (headers.isNotEmpty()) this.headers = headers
        }
    }

    private suspend fun fetchChannelStreams(slug: String): ChannelStreamResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val base = LiveSportsProviderManager.getBaseUrl() ?: return@withContext null
                val url = "$base/channels/${slug.lowercase()}.txt"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    val encrypted = resp.body.string()
                    val decrypted = CryptoUtils.decryptData(encrypted.trim())
                    if (!decrypted.isNullOrBlank()) return@withContext parseJson(decrypted)
                }
            } catch (e: Exception) { e.printStackTrace() }
            null
        }
    }

    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        if (!link.contains("|")) return link to emptyMap()
        val parts = link.split("|", limit = 2)
        val url = parts[0]
        val headers = mutableMapOf<String, String>()
        parts.getOrNull(1)?.split("&")?.forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val key = when (kv[0].lowercase()) {
                    "user-agent" -> "User-Agent"
                    "referer" -> "Referer"
                    "origin" -> "Origin"
                    "cookie" -> "Cookie"
                    else -> kv[0]
                }
                headers[key] = kv[1]
            }
        }
        return url to headers
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String): String? {
        if (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4")) return url
        return loadEmbedInWebView(url)
    }

    private suspend fun loadEmbedInWebView(embedUrl: String): String? {
        return suspendCancellableCoroutine { cont ->
            val ctx = context
            if (ctx == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val webView = WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".mpd")) {
                            if (cont.isActive) cont.resume(reqUrl)
                            destroy()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (cont.isActive) {
                                view.evaluateJavascript("(function(){ return typeof playbackURL !== 'undefined' ? playbackURL : null; })();") { result ->
                                    if (cont.isActive) {
                                        if (result != "null" && result.isNotBlank()) cont.resume(result.trim('"'))
                                        else cont.resume(null)
                                        view.destroy()
                                    }
                                }
                            } else {
                                view.destroy()
                            }
                        }, 1500)
                    }
                }
                loadUrl(embedUrl)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (cont.isActive) {
                    cont.resume(null)
                    webView.destroy()
                }
            }, 30000)
        }
    }

    private fun createDisplayTitle(event: LiveEventData): String {
        val info = event.eventInfo
        return if (info?.teamA != null && info.teamB != null && info.teamA != info.teamB) {
            "${info.teamA} vs ${info.teamB}"
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
            append("&isEnded=${getEventStatus(event) == "✅"}")
        }
    }
}
