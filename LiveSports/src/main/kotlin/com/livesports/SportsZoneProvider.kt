package com.livesports

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

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
    }

    data class FirebaseConfig(
        @JsonProperty("dami") val dami: String? = null,
        @JsonProperty("dami_url") val dami_url: String? = null,
        @JsonProperty("damiUrl") val damiUrl: String? = null
    )

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json").text
            val json = parseJson<FirebaseConfig>(response)
            val url = json.dami ?: json.dami_url ?: json.damiUrl
            if (!url.isNullOrEmpty()) {
                mainUrl = url.removeSuffix("/")
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            println("SportsZone: Failed to load Firebase URL - ${e.message}")
        }
    }

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

    // ANTI-BUFFERING & ANTI-2004 ERROR HEADERS
    private val hlsPlayHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*",
            "Connection" to "keep-alive", // Prevents TCP handshake drops between chunks
            "Cache-Control" to "no-cache", // Forces fresh live playlist fetching
            "Pragma" to "no-cache"
        )

    // ── Data classes ───────────────────────────────────────────────────────────

    data class SportsZoneTvChannel(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String
    )

    data class EventLoadData(
        val title: String,
        val url: String, 
        val posterUrl: String?,
        val category: String?,
        val status: String? = null,
        val date: Long? = null,
        val isDaddyLive: Boolean? = null,
        val tvChannels: List<SportsZoneTvChannel>? = null
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    data class SportsZoneMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("league") val league: String?,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String?,
        @JsonProperty("date") val date: Long?,
        @JsonProperty("popular") val popular: Boolean?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("viewers") val viewers: Int?,
        @JsonProperty("embedUrl") val embedUrl: String?,
        @JsonProperty("substreams") val substreams: List<SportsZoneSubstream>?,
        @JsonProperty("isDaddyLive") val isDaddyLive: Boolean?,
        @JsonProperty("tvChannels") val tvChannels: List<SportsZoneTvChannel>?
    )

    data class SportsZoneSubstream(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("iframe") val iframe: String?,
        @JsonProperty("locale") val locale: String?
    )

    data class ExtractUrlResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("hlsUrl") val hlsUrl: String?,
        @JsonProperty("embedUrl") val embedUrl: String?,
        @JsonProperty("matchId") val matchId: String?,
        @JsonProperty("substreams") val substreams: List<SportsZoneSubstream>?,
        @JsonProperty("error") val error: String?
    )

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun formatMatchDate(timestamp: Long?): String {
        if (timestamp == null) return "soon"
        return try {
            val date = java.util.Date(timestamp)
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(date)
        } catch (e: Exception) {
            "soon"
        }
    }

    private fun matchToSearchResponse(match: SportsZoneMatch): SearchResponse {
        val title = match.title
        val posterUrl = match.poster ?: ""
        val loadData = EventLoadData(
            title = title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            isDaddyLive = match.isDaddyLive,
            tvChannels = match.tvChannels
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    private fun matchToUpcomingSearchResponse(match: SportsZoneMatch): SearchResponse {
        val dateStr = formatMatchDate(match.date)
        val title = "${match.title} [Upcoming - Starts: $dateStr]"
        val posterUrl = match.poster ?: ""
        val loadData = EventLoadData(
            title = match.title,
            url = match.id,
            posterUrl = posterUrl,
            category = match.category,
            status = match.status,
            date = match.date,
            isDaddyLive = match.isDaddyLive,
            tvChannels = match.tvChannels
        )
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    // ── Main Page ─────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        loadFirebaseUrl()
        val lists = mutableListOf<HomePageList>()

        try {
            val allText = app.get("$mainUrl/papi/matches/all", headers = apiHeaders).text
            val allMatches = parseJson<List<SportsZoneMatch>>(allText)

            val liveMatches = allMatches.filter { match ->
                val status = match.status?.lowercase() ?: ""
                val cat = match.category?.lowercase() ?: ""
                status == "live" && cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
            }
            if (liveMatches.isNotEmpty()) {
                val items = liveMatches.map { matchToSearchResponse(it) }
                lists.add(HomePageList("🟢 Live Sports Events", items, isHorizontalImages = true))
            }

            val upcomingMatches = allMatches.filter { match ->
                val status = match.status?.lowercase() ?: ""
                val cat = match.category?.lowercase() ?: ""
                status == "upcoming" && cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
            }
            if (upcomingMatches.isNotEmpty()) {
                val items = upcomingMatches.map { matchToUpcomingSearchResponse(it) }
                lists.add(HomePageList("📅 Upcoming Matches (Live soon)", items, isHorizontalImages = true))
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
            val allMatches = parseJson<List<SportsZoneMatch>>(text)
            allMatches.filter { match ->
                val cat = match.category?.lowercase() ?: ""
                val isSport = cat.isNotBlank() && cat != "24/7-streams" && cat != "live-tv" && cat != "channels" && !cat.contains("stream")
                val titleMatches = match.title.contains(query, ignoreCase = true)
                val leagueMatches = match.league?.contains(query, ignoreCase = true) ?: false
                isSport && (titleMatches || leagueMatches)
            }.map { match ->
                if (match.status == "upcoming") {
                    matchToUpcomingSearchResponse(match)
                } else {
                    matchToSearchResponse(match)
                }
            }
        } catch (e: Exception) {
            println("SportsZone: Search failed - ${e.message}")
            emptyList()
        }
    }

    // ── Load ──────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        loadFirebaseUrl()
        val eventData = parseJson<EventLoadData>(url)
        val matchId = eventData.url
        val title = eventData.title
        val posterUrl = eventData.posterUrl
        val isUpcoming = eventData.status == "upcoming"
        val dateStr = formatMatchDate(eventData.date)

        val streamsList = mutableListOf<StreamInfo>()

        if (eventData.isDaddyLive == true && !eventData.tvChannels.isNullOrEmpty()) {
            eventData.tvChannels.forEach { ch ->
                val chName = if (isUpcoming) "${ch.name} (Upcoming)" else ch.name
                val dlhdProxyUrl = "$mainUrl/papi/tv/dlhd/${ch.id}/playlist.m3u8"
                streamsList.add(StreamInfo(name = chName, url = dlhdProxyUrl))
            }
        }

        if (streamsList.isEmpty()) {
            try {
                val text = app.get("$mainUrl/papi/extract-url/$matchId", headers = apiHeaders).text
                val response = parseJson<ExtractUrlResponse>(text)
                if (response.success) {
                    val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream"
                    streamsList.add(StreamInfo(name = mainStreamName, url = matchId))

                    response.substreams?.forEach { sub ->
                        val localeSuffix = if (!sub.locale.isNullOrBlank()) " (${sub.locale})" else ""
                        val subName = if (isUpcoming) "${sub.name}$localeSuffix (Upcoming)" else "${sub.name}$localeSuffix"
                        streamsList.add(StreamInfo(name = subName, url = sub.id))
                    }
                } else {
                    val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream"
                    streamsList.add(StreamInfo(name = mainStreamName, url = matchId))
                }
            } catch (e: Exception) {
                println("SportsZone: Load failed to query extract-url - ${e.message}")
                val mainStreamName = if (isUpcoming) "Upcoming - Live soon (Starts: $dateStr)" else "Main Stream"
                streamsList.add(StreamInfo(name = mainStreamName, url = matchId))
            }
        }

        val streamData = StreamLoadData(title, streamsList)

        return newLiveStreamLoadResponse(title, url, this.name) {
            this.posterUrl = posterUrl
            this.dataUrl = streamData.toJson()
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (e: Exception) {
            println("SportsZone: loadLinks parse error — ${e.message}")
            return false
        }

        if (streamData.streams.isEmpty()) return false

        var foundAny = false

        streamData.streams.forEach { stream ->
            try {
                if (stream.url.contains("/papi/tv/dlhd/")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = stream.name,
                            url = stream.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            // Apply keep-alive constraints here as well to stabilize the DaddyLive proxy
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl,
                                "Connection" to "keep-alive",
                                "Cache-Control" to "no-cache"
                            )
                        }
                    )
                    foundAny = true
                } else {
                    val text = app.get("$mainUrl/papi/extract-url/${stream.url}", headers = apiHeaders).text
                    val response = parseJson<ExtractUrlResponse>(text)
                    
                    if (response.success) {
                        val hlsUrlStr = response.hlsUrl
                        val embedUrlStr = response.embedUrl

                        // === PRIMARY: Direct HLS ===
                        if (!hlsUrlStr.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${stream.name} (Direct)",
                                    url = hlsUrlStr,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = hlsPlayHeaders // Includes keep-alive & no-cache
                                }
                            )
                            foundAny = true
                        }

                        // === FALLBACK: Embed Extraction ===
                        if (!embedUrlStr.isNullOrBlank()) {
                            try {
                                val embedHtml = app.get(
                                    url = embedUrlStr,
                                    headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
                                        "Referer" to "$mainUrl/"
                                    )
                                ).text

                                val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                                val m3u8Matches = m3u8Pattern.findAll(embedHtml).toList()
                                
                                for ((idx, match) in m3u8Matches.withIndex()) {
                                    val m3u8Url = match.value.replace("\\u0026", "&").replace("\\/", "/")
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${stream.name} (Embed ${idx + 1})",
                                            url = m3u8Url,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.headers = hlsPlayHeaders // Includes keep-alive & no-cache
                                        }
                                    )
                                    foundAny = true
                                }

                                val jsPatterns = listOf(
                                    Regex("""['"]?(hlsUrl|streamUrl|source|file|src)['"]?\s*[:=]\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                                    Regex("""setStream\(['"]([^'"]+)['"]"""),
                                    Regex("""b-cdn\.net[^\s"']*\.m3u8[^\s"']*""")
                                )

                                for (pattern in jsPatterns) {
                                    for (jsMatch in pattern.findAll(embedHtml)) {
                                        val extractedUrl = if (jsMatch.groups.size > 2) {
                                            jsMatch.groups[2]?.value ?: jsMatch.value
                                        } else {
                                            jsMatch.value
                                        }
                                        
                                        val isDuplicate = m3u8Matches.any { it.value == extractedUrl }
                                        if (extractedUrl.contains(".m3u8") && !isDuplicate) {
                                            val cleanUrl = if (extractedUrl.startsWith("http")) extractedUrl else "https://$extractedUrl"
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = this.name,
                                                    name = "${stream.name} (JS)",
                                                    url = cleanUrl.replace("\\u0026", "&").replace("\\/", "/"),
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.headers = hlsPlayHeaders // Includes keep-alive & no-cache
                                                }
                                            )
                                            foundAny = true
                                        }
                                    }
                                }
                            } catch (embedError: Exception) {
                                println("SportsZone: Embed extraction failed for ${stream.name} - ${embedError.message}")
                            }

                            // CloudStream built-in Extractor Fallback
                            try {
                                loadExtractor(embedUrlStr, "$mainUrl/", subtitleCallback, callback)
                                foundAny = true
                            } catch (extractError: Exception) {
                                println("SportsZone: loadExtractor failed for ${stream.name} - ${extractError.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("SportsZone: Failed to load stream link for ${stream.name} - ${e.message}")
            }
        }

        return foundAny
    }
}
