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

    enum class EventState(val emoji: String) {
        LIVE("🔴"),
        UPCOMING("🔜"),
        ENDED("✅"),
        UNKNOWN("📺")
    }

    // Single robust date parser
    private fun parseEventDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(dateStr)?.time
        } catch (e: Exception) { null }
    }

    private fun getEventState(event: LiveEventData): EventState {
        val info = event.eventInfo ?: return EventState.UNKNOWN
        val startTime = parseEventDate(info.startTime)
        val endTime = parseEventDate(info.endTime)
        val now = System.currentTimeMillis()

        return when {
            endTime != null && now >= endTime -> EventState.ENDED
            startTime != null && now >= startTime -> EventState.LIVE
            startTime != null && now < startTime -> EventState.UPCOMING
            else -> EventState.UNKNOWN
        }
    }

    private fun getFormattedTime(event: LiveEventData): String {
        val timeMs = parseEventDate(event.eventInfo?.startTime) ?: return ""
        return try {
            SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US).format(Date(timeMs))
        } catch (e: Exception) { "" }
    }

    private fun createDisplayTitle(event: LiveEventData): String {
        val info = event.eventInfo
        return if (info?.teamA != null && info.teamB != null && info.teamA != info.teamB) "${info.teamA} vs ${info.teamB}" else event.title
    }

    private fun generateMatchCardUrl(event: LiveEventData): String {
        val info = event.eventInfo
        val state = getEventState(event)
        val time = getFormattedTime(event)

        val isLive = state == EventState.LIVE
        val isEnded = state == EventState.ENDED

        return buildString {
            append("https://sportslivecard.livesportz.workers.dev/?")
            append("title=${java.net.URLEncoder.encode(info?.eventName ?: event.title, "UTF-8")}")
            append("&teamA=${java.net.URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")}")
            append("&teamB=${java.net.URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")}")
            info?.teamAFlag?.let { append("&teamAImg=$it") }
            info?.teamBFlag?.let { append("&teamBImg=$it") }
            info?.eventLogo?.let { append("&eventLogo=$it") }

            append("&isLive=$isLive")
            append("&isEnded=$isEnded")

            if (time.isNotBlank()) {
                append("&time=${java.net.URLEncoder.encode(time, "UTF-8")}")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = LiveSportsProviderManager.fetchLiveEvents()
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val categoryOrder = listOf("football", "cricket", "boxing", "motorsport")
        val sortedCategories = grouped.keys.sortedBy { category ->
            val index = categoryOrder.indexOf(category.lowercase())
            if (index == -1) Int.MAX_VALUE else index
        }

        val lists = sortedCategories.mapNotNull { category ->
            val list = grouped[category] ?: return@mapNotNull null

            val icon = when (category.lowercase()) {
                "cricket" -> "🏏"
                "football" -> "⚽"
                "motorsport" -> "🏎️"
                "boxing" -> "🥊"
                "basketball" -> "🏀"
                "tennis" -> "🎾"
                "ice hockey" -> "🏒"
                "baseball" -> "⚾"
                else -> "📺"
            }

            // Advanced Chronological Sorting
            val sortedList = list.sortedWith(Comparator { a, b ->
                val stateA = getEventState(a)
                val stateB = getEventState(b)

                if (stateA != stateB) {
                    stateA.compareTo(stateB)
                } else {
                    val timeA = parseEventDate(a.eventInfo?.startTime) ?: 0L
                    val timeB = parseEventDate(b.eventInfo?.startTime) ?: 0L

                    if (stateA == EventState.UPCOMING) {
                        timeA.compareTo(timeB)
                    } else {
                        timeB.compareTo(timeA)
                    }
                }
            })

            val items = sortedList.map { event ->
                val state = getEventState(event)
                val time = getFormattedTime(event)
                val baseTitle = createDisplayTitle(event)

                val title = buildString {
                    if (state != EventState.UNKNOWN) append("${state.emoji} ")
                    append(baseTitle)

                    // Display time on UI only if not live and not ended
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

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val events = LiveSportsProviderManager.fetchLiveEvents()

        return events
            .filter { event ->
                val haystack = listOfNotNull(
                    event.title,
                    event.eventInfo?.teamA,
                    event.eventInfo?.teamB,
                    event.eventInfo?.eventName,
                    event.eventInfo?.eventType,
                    event.eventInfo?.eventCat,
                    event.cat
                ).joinToString(" ")
                haystack.contains(query, ignoreCase = true)
            }
            .map { event ->
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
                    formats = event.formats ?: emptyList(),
                    eventInfo = event.eventInfo
                )

                newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
                    this.posterUrl = loadData.poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)
        val plot = buildString {
            data.eventInfo?.let {
                it.eventType?.let { append("📌 $it\n") }
                it.eventName?.let { append("🏆 $it\n") }
                it.startTime?.let { timeStr ->
                    val parsedMs = parseEventDate(timeStr)
                    if (parsedMs != null) {
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
            val (url, headers) = parseStreamLink(stream.link ?: return@forEach)
            if (url.isBlank()) return@forEach
            val resolved = resolveEmbedUrlIfNeeded(url, headers) ?: return@forEach

            when (stream.type) {
                "7" -> {
                    val drmParts = stream.api?.split(":") ?: return@forEach
                    if (drmParts.size == 2) {
                        val kid = drmParts[0].replace("-", "").hexToBase64UrlOrNull() ?: drmParts[0]
                        val key = drmParts[1].replace("-", "").hexToBase64UrlOrNull() ?: drmParts[1]
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
                else -> {
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

        if (headers.containsKey("Referer") && !headers.containsKey("Origin")) {
            try {
                val uri = URI(headers["Referer"]!!)
                val portStr = if (uri.port != -1) ":${uri.port}" else ""
                headers["Origin"] = "${uri.scheme}://${uri.host}$portStr"
            } catch (e: Exception) { }
        }

        headers["Connection"] = "keep-alive"
        return parts[0] to headers
    }

    private fun isDirectStreamUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mpd") || u.contains(".mp4") ||
            u.contains(".ts") || u.contains(".mkv") || u.contains(".webm")
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String, headers: Map<String, String>): String? {
        if (isDirectStreamUrl(url)) return url

        return suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val ctx = context
                if (ctx == null) { cont.resume(null); return@post }

                var resolved = false
                fun finish(result: String?) {
                    if (resolved) return
                    resolved = true
                    try { cont.resume(result) } catch (_: Exception) {}
                }

                val webView = WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = headers["User-Agent"]
                            ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                    }

                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun onStreamUrlFound(u: String) {
                            if (u.isBlank()) return
                            Handler(Looper.getMainLooper()).post {
                                try { destroy() } catch (_: Exception) {}
                                finish(u)
                            }
                        }
                    }, "StreamBridge")

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if ((reqUrl.contains(".m3u8") || reqUrl.contains(".mpd")) && !resolved) {
                                Handler(Looper.getMainLooper()).post {
                                    try { destroy() } catch (_: Exception) {}
                                    finish(reqUrl)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            if (resolved) return

                            val probe = """
                                (function() {
                                    function send(u) {
                                        if (typeof u === 'string' && u.length > 4) {
                                            window.StreamBridge.onStreamUrlFound(u);
                                            return true;
                                        }
                                        return false;
                                    }
                                    try {
                                        var names = ['playbackURL','videoUrl','streamUrl','m3u8Url',
                                                     'dashUrl','source','file','url','stream'];
                                        for (var i = 0; i < names.length; i++) {
                                            try { if (send(window[names[i]])) return; } catch(e){}
                                        }
                                    } catch(e){}
                                    try {
                                        var v = document.querySelector('video');
                                        if (v && send(v.currentSrc || v.src)) return;
                                    } catch(e){}
                                    try {
                                        if (window.videojs && videojs.getAllPlayers) {
                                            var p = videojs.getAllPlayers()[0];
                                            if (p && send(p.currentSrc())) return;
                                        }
                                    } catch(e){}
                                    try {
                                        if (window.jwplayer) {
                                            var jw = jwplayer();
                                            if (jw && jw.getPlaylistItem) {
                                                var item = jw.getPlaylistItem();
                                                if (item && send(item.file)) return;
                                            }
                                        }
                                    } catch(e){}
                                })();
                            """.trimIndent()

                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!resolved) view.evaluateJavascript(probe, null)
                            }, 600)
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!resolved) view.evaluateJavascript(probe, null)
                            }, 2000)
                        }
                    }

                    loadUrl(url, headers)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    if (!resolved) {
                        try { webView.destroy() } catch (_: Exception) {}
                        finish(null)
                    }
                }, 15000)
            }
        }
    }
}
