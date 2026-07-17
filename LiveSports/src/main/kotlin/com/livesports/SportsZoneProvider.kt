package com.livesports

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SportsZoneProvider : MainAPI() {

    override var mainUrl = "https://dami-tv.pro"
    override var name = "SportsZone"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private var isUrlLoaded = false

    companion object {
        private const val EMBED_DOMAIN = "https://embedindia.st"
        private val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        private val USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        private val EXCLUDED_CATEGORIES = setOf("24/7-streams", "live-tv", "channels")
    }

    data class FirebaseConfig(
        @JsonProperty("dami") val dami: String? = null,
        @JsonProperty("dami_url") val dami_url: String? = null,
        @JsonProperty("damiUrl") val damiUrl: String? = null,
        @JsonProperty("damitv_url") val damitvUrl: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json").text
            val json = parseJson<FirebaseConfig>(response)
            val url = json.dami ?: json.dami_url ?: json.damiUrl ?: json.damitvUrl
            url?.takeIf { it.isNotEmpty() }?.let {
                mainUrl = it.removeSuffix("/")
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            println("SportsZone: Failed to load Firebase URL - ${e.message}")
        }
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT_DESKTOP,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    private val hlsPlayHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT_MOBILE,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

    // ── Data classes ──────────────────────────────────────────────────────────

    data class SportsZoneTvChannel(@JsonProperty("id") val id: String, @JsonProperty("name") val name: String)
    data class SportsZoneStreamedSource(@JsonProperty("source") val source: String, @JsonProperty("id") val id: String)
    
    data class SportsZoneStreamVariant(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("hd") val hd: Boolean? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("source") val source: String,
        @JsonProperty("viewers") val viewers: Int? = null
    )

    data class EventLoadData(
        val title: String, val url: String, val posterUrl: String?, val category: String?,
        val status: String? = null, val date: Long? = null, val isDaddyLive: Boolean? = null,
        val tvChannels: List<SportsZoneTvChannel>? = null, val isStreamed: Boolean? = null,
        val streamedSources: List<SportsZoneStreamedSource>? = null
    )

    data class StreamLoadData(val title: String, val streams: List<StreamInfo>)
    data class StreamInfo(val name: String, val url: String, val headers: Map<String, String> = emptyMap())

    data class SportsZoneMatch(
        @JsonProperty("id") val id: String, @JsonProperty("league") val league: String?,
        @JsonProperty("title") val title: String, @JsonProperty("category") val category: String?,
        @JsonProperty("date") val date: Long?, @JsonProperty("popular") val popular: Boolean?,
        @JsonProperty("poster") val poster: String?, @JsonProperty("status") val status: String?,
        @JsonProperty("viewers") val viewers: Int?, @JsonProperty("embedUrl") val embedUrl: String?,
        @JsonProperty("substreams") val substreams: List<SportsZoneSubstream>?,
        @JsonProperty("isDaddyLive") val isDaddyLive: Boolean?,
        @JsonProperty("tvChannels") val tvChannels: List<SportsZoneTvChannel>?,
        @JsonProperty("isStreamed") val isStreamed: Boolean? = null,
        @JsonProperty("streamedSources") val streamedSources: List<SportsZoneStreamedSource>? = null
    )

    data class SportsZoneSubstream(
        @JsonProperty("id") val id: String, @JsonProperty("name") val name: String,
        @JsonProperty("iframe") val iframe: String?, @JsonProperty("locale") val locale: String?
    )

    data class ExtractUrlResponse(
        @JsonProperty("success") val success: Boolean, @JsonProperty("hlsUrl") val hlsUrl: String?,
        @JsonProperty("embedUrl") val embedUrl: String?, @JsonProperty("matchId") val matchId: String?,
        @JsonProperty("substreams") val substreams: List<SportsZoneSubstream>?, @JsonProperty("error") val error: String?
    )

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun formatMatchDate(timestamp: Long?): String {
        if (timestamp == null) return "soon"
        return try {
            val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            "soon"
        }
    }

    private fun isSportCategory(category: String?): Boolean {
        val cat = category?.lowercase() ?: return false
        return cat.isNotBlank() && cat !in EXCLUDED_CATEGORIES && !cat.contains("stream")
    }

    private fun SportsZoneMatch.toSearchResponse(): SearchResponse {
        val isUpcoming = status?.lowercase() == "upcoming"
        val dateStr = formatMatchDate(date)
        val catDisplay = category?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: "Sports"
        
        val displayTitle = if (isUpcoming) "$catDisplay | $title • $dateStr" else "$catDisplay | $title"
        val safePosterUrl = poster ?: ""

        val loadData = EventLoadData(
            title = displayTitle, url = id, posterUrl = safePosterUrl, category = category,
            status = status, date = date, isDaddyLive = isDaddyLive, tvChannels = tvChannels,
            isStreamed = isStreamed, streamedSources = streamedSources
        )

        return newLiveSearchResponse(displayTitle, loadData.toJson(), TvType.Live) {
            this.posterUrl = safePosterUrl
        }
    }

    private fun encodeUtf8(url: String) = URLEncoder.encode(url, "UTF-8").replace("+", "%20")
    private fun decodeUtf8(url: String) = URLDecoder.decode(url, "UTF-8")

    // ── Main Page ─────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        val lists = mutableListOf<HomePageList>()

        try {
            val allText = app.get("$mainUrl/papi/matches/all", headers = apiHeaders).text
            val allMatches = parseJson<List<SportsZoneMatch>>(allText).filter { isSportCategory(it.category) }

            // Split into Upcoming and Live in a single pass
            val (upcomingMatches, liveMatches) = allMatches.partition { it.status?.lowercase() == "upcoming" }

            if (liveMatches.isNotEmpty()) {
                lists.add(HomePageList("🟢 Live Sports Events", liveMatches.map { it.toSearchResponse() }, isHorizontalImages = true))
            }
            if (upcomingMatches.isNotEmpty()) {
                lists.add(HomePageList("📅 Upcoming Matches (Live soon)", upcomingMatches.map { it.toSearchResponse() }, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            println("SportsZone: Failed to load matches - ${e.message}")
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        loadFirebaseUrl()
        return try {
            val text = app.get("$mainUrl/papi/matches/all", headers = apiHeaders).text
            parseJson<List<SportsZoneMatch>>(text)
                .filter { isSportCategory(it.category) && (it.title.contains(query, true) || it.league?.contains(query, true) == true) }
                .map { it.toSearchResponse() }
        } catch (e: Exception) {
            println("SportsZone: Search failed - ${e.message}")
            emptyList()
        }
    }

    // ── Load ──────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        loadFirebaseUrl()
        val eventData = parseJson<EventLoadData>(url)
        val isUpcoming = eventData.status == "upcoming"
        val dateStr = formatMatchDate(eventData.date)

        val streamsList = buildList {
            // 1. DLHD Proxy Channels
            eventData.tvChannels?.forEach { ch ->
                val chName = if (isUpcoming) "${ch.name} (Upcoming)" else ch.name
                add(StreamInfo(name = chName, url = "$mainUrl/papi/tv/dlhd/${ch.id}/playlist.m3u8"))
            }

            // 2. PPV API Endpoints
            var addedPpvOrStreamed = false
            try {
                val response = app.get("$mainUrl/papi/extract-url/${eventData.url}", headers = apiHeaders).parsedSafe<ExtractUrlResponse>()
                if (response?.success == true) {
                    add(StreamInfo(name = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream", url = eventData.url))
                    addedPpvOrStreamed = true

                    response.substreams?.forEach { sub ->
                        val localeSuffix = sub.locale?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                        add(StreamInfo(name = "${sub.name}$localeSuffix${if (isUpcoming) " (Upcoming)" else ""}", url = sub.id))
                    }
                }
            } catch (e: Exception) {
                println("SportsZone: Load failed to query extract-url - ${e.message}")
            }

            // 3. Streamed.pk sources
            eventData.streamedSources?.let { sources ->
                val sdMulti = sources.size > 1
                sources.forEach { src ->
                    try {
                        val variants = app.get("$mainUrl/papi/stream/${src.source}/${src.id}", headers = apiHeaders).parsedSafe<List<SportsZoneStreamVariant>>()
                        variants?.forEach { st ->
                            val namePrefix = if (sdMulti) "${src.source.replaceFirstChar { it.uppercase() }} " else "Server "
                            val encodedFallback = st.embedUrl?.let { encodeUtf8(it) } ?: ""
                            val customUrl = "streamed://${src.source}?id=${encodeUtf8(src.id)}&num=${st.streamNo}&fallback=$encodedFallback"
                            
                            add(StreamInfo(name = "$namePrefix${st.streamNo}", url = customUrl))
                            addedPpvOrStreamed = true
                        }
                    } catch (e: Exception) {
                        println("SportsZone: Failed to load streamed source - ${e.message}")
                    }
                }
            }

            // 4. Fallback if empty
            if (!addedPpvOrStreamed && isEmpty()) {
                add(StreamInfo(name = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream", url = eventData.url))
            }
        }

        return newLiveStreamLoadResponse(eventData.title, url, this.name) {
            this.posterUrl = eventData.posterUrl
            this.dataUrl = StreamLoadData(eventData.title, streamsList).toJson()
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        loadFirebaseUrl()
        val streamData = parseJson<StreamLoadData>(data)
        if (streamData.streams.isEmpty()) return false

        var foundAny = false

        streamData.streams.forEach { stream ->
            when {
                // 1. DLHD Proxy Streams
                stream.url.contains("/papi/tv/dlhd/") -> {
                    callback.invoke(
                        ExtractorLink(this.name, stream.name, stream.url, "", ExtractorLinkType.M3U8, headers = hlsPlayHeaders)
                    )
                    foundAny = true
                }

                // 2. Streamed.pk streams
                stream.url.startsWith("streamed://") -> {
                    try {
                        val stripped = stream.url.substringAfter("streamed://")
                        val source = stripped.substringBefore("?")
                        val params = stripped.substringAfter("?").split("&").associate { 
                            it.substringBefore("=") to decodeUtf8(it.substringAfter("="))
                        }

                        val streamId = params["id"]
                        val streamNo = params["num"]
                        val fallbackUrl = params["fallback"] ?: ""

                        if (!source.isBlank() && streamId != null && streamNo != null) {
                            val tokenData = app.get("$mainUrl/papi/sd-token", headers = apiHeaders).parsedSafe<Map<String, Any>>()
                            if (tokenData != null) {
                                val token = tokenData["token"]?.toString() ?: ""
                                val tokenPath = tokenData["token_path"]?.toString() ?: ""
                                val expires = (tokenData["expires"] as? Number)?.toLong() ?: 0L

                                val hlsUrl = "https://damitvsd.b-cdn.net/live-sd/streamed/${encodeUtf8(source)}/${encodeUtf8(streamId)}/$streamNo/playlist.m3u8?token=$token&token_path=${encodeUtf8(tokenPath)}&expires=$expires"

                                callback.invoke(ExtractorLink(this.name, "${stream.name} (Direct)", hlsUrl, "", ExtractorLinkType.M3U8, headers = hlsPlayHeaders))
                                foundAny = true
                            }
                        }

                        if (fallbackUrl.isNotEmpty()) {
                            foundAny = extractAndLoadEmbeds(fallbackUrl, stream.name, callback, subtitleCallback) || foundAny
                        }
                    } catch (e: Exception) {
                        println("SportsZone: Failed to load streamed link - ${e.message}")
                    }
                }

                // 3. Standard PPV extraction
                else -> {
                    try {
                        val response = app.get("$mainUrl/papi/extract-url/${stream.url}", headers = apiHeaders).parsedSafe<ExtractUrlResponse>()
                        if (response?.success == true) {
                            response.hlsUrl?.takeIf { it.isNotBlank() }?.let {
                                callback.invoke(ExtractorLink(this.name, "${stream.name} (Direct)", it, "", ExtractorLinkType.M3U8, headers = hlsPlayHeaders))
                                foundAny = true
                            }

                            response.embedUrl?.takeIf { it.isNotBlank() }?.let { embed ->
                                foundAny = extractAndLoadEmbeds(embed, stream.name, callback, subtitleCallback) || foundAny
                            }
                        }
                    } catch (e: Exception) {
                        println("SportsZone: Failed to extract PPV link - ${e.message}")
                    }
                }
            }
        }
        return foundAny
    }

    // Extracted helper for parsing embeds & executing extractors
    private suspend fun extractAndLoadEmbeds(embedUrl: String, streamName: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit): Boolean {
        var found = false
        try {
            val embedHtml = app.get(embedUrl, headers = mapOf("User-Agent" to USER_AGENT_MOBILE, "Referer" to "$mainUrl/")).text
            
            // Regex for M3U8 inside quotes or explicit variables
            val m3u8Matches = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").findAll(embedHtml).map { it.value }.toMutableSet()
            
            Regex("""['"]?(hlsUrl|streamUrl|source|file|src)['"]?\s*[:=]\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").findAll(embedHtml).forEach {
                m3u8Matches.add(it.groupValues[2])
            }

            m3u8Matches.forEachIndexed { idx, url ->
                val cleanUrl = url.replace("\\u0026", "&").replace("\\/", "/").let { if (!it.startsWith("http")) "https://$it" else it }
                callback.invoke(ExtractorLink(this.name, "$streamName (Embed ${idx + 1})", cleanUrl, "", ExtractorLinkType.M3U8, headers = hlsPlayHeaders))
                found = true
            }

            // Fallback to internal extractors
            loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            found = true
        } catch (e: Exception) {
            println("SportsZone: Embed extraction failed - ${e.message}")
        }
        return found
    }
}
