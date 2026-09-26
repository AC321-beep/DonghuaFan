package com.donghuastream

import android.util.Log
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * Rumble extractor modelled on OCE's MasterLinkGenerator + M3u8MasterVerifier,
 * without depending on the OCE package.
 *
 * Three ideas absorbed from OCE:
 *
 *  1. rumble.com/hls-vod rejects browser UAs from non-browser HTTP clients
 *     (TLS fingerprint mismatch → Cloudflare 403). "okhttp/4.12.0" passes.
 *
 *  2. Master playlists sometimes contain malformed #EXT-X-STREAM-INF lines
 *     (no URI on the following line). Feeding those to ExoPlayer causes
 *     error 3002 (PARSING_MANIFEST_MALFORMED).
 *
 *  3. Clean masters should be delivered AS-IS so ExoPlayer's ABR works and the
 *     quality menu appears. Only masters with malformed variants need to be
 *     split into individual variant links.
 */
private data class MasterVariant(
    val url: String?,        // null = malformed (no URI line after STREAM-INF)
    val bandwidth: Long,
    val height: Int?
)

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val TAG = "Rumble"
        private const val HTML_TIMEOUT_MS = 12_000L
        private const val M3U8_TIMEOUT_MS = 6_000L

        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val RAW_UA = "okhttp/4.12.0"

        private val PAGE_HEADERS = mapOf(
            "Accept" to "*/*",
            "User-Agent" to BROWSER_UA
        )
        private val CDN_HEADERS = mapOf(
            "Accept" to "*/*",
            "User-Agent" to RAW_UA
        )

        private val RE_MEDIA_URL = Regex(
            """(https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:m3u8|mp4)[^"'\s<>‘’“”]*)"""
        )
        private val RE_QUALITY_H = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_QUALITY_BRACE = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")
        private val RE_BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
        private val RE_RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")

        private val JUNK_KEYWORDS = listOf("/assets/", "tracker", "thumb")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "=== getUrl START ===")
        Log.d(TAG, "url=$url")
        Log.d(TAG, "referer=$referer")

        // Fast path
        if (url.endsWith(".mp4", true) || url.endsWith(".m3u8", true)) {
            Log.d(TAG, "[1] fast path — direct media URL")
            val t = if (url.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, t) { this.referer = url })
            Log.d(TAG, "=== getUrl END (fast path) ===")
            return
        }

        // ---- 1. Fetch HTML ----
        Log.d(TAG, "[2] fetching embed HTML…")
        val html = withTimeoutOrNull(HTML_TIMEOUT_MS) {
            try {
                app.get(url, referer = referer ?: "$mainUrl/", headers = PAGE_HEADERS).text
            } catch (e: Exception) {
                Log.e(TAG, "[2] HTML fetch threw: ${e.message}")
                null
            }
        }
        if (html == null) {
            Log.e(TAG, "[2] HTML fetch FAILED (null or timeout)")
            Log.d(TAG, "=== getUrl END (no html) ===")
            return
        }
        Log.d(TAG, "[2] HTML fetched: ${html.length} bytes")

        // ---- 2. Slice JSON ----
        val afterMp4 = html.substringAfter("{\"mp4", "")
        val hasMp4Marker = afterMp4.isNotEmpty()
        val scriptData = if (hasMp4Marker) afterMp4.substringBefore("\"evt\":{") else html
        Log.d(TAG, "[3] marker '{\"mp4' found: $hasMp4Marker")
        Log.d(TAG, "[3] scriptData length: ${scriptData.length}")

        // ---- 3. Collect URLs ----
        val seen = LinkedHashSet<String>()
        data class Hit(val url: String, val start: Int)
        val m3u8Hits = mutableListOf<Hit>()
        val mp4Hits  = mutableListOf<Hit>()

        var totalMatches = 0
        var junkRejected = 0
        RE_MEDIA_URL.findAll(scriptData).forEach { m ->
            totalMatches++
            val clean = m.groupValues[1].replace("\\/", "/")
            if (JUNK_KEYWORDS.any { clean.contains(it, true) }) {
                junkRejected++
                Log.d(TAG, "[4] junk rejected: $clean")
                return@forEach
            }
            if (!seen.add(clean)) {
                Log.d(TAG, "[4] dup rejected: $clean")
                return@forEach
            }
            if (clean.contains(".m3u8", true)) {
                m3u8Hits += Hit(clean, m.range.first)
                Log.d(TAG, "[4] m3u8 accepted: $clean")
            } else {
                mp4Hits += Hit(clean, m.range.first)
                Log.d(TAG, "[4] mp4 accepted: $clean")
            }
        }
        Log.d(TAG, "[4] regex hits: $totalMatches total, $junkRejected junk, " +
            "${m3u8Hits.size} m3u8, ${mp4Hits.size} mp4")

        if (m3u8Hits.isEmpty() && mp4Hits.isEmpty()) {
            Log.e(TAG, "[4] NO MEDIA URLS FOUND — check slice marker / regex")
            Log.d(TAG, "=== getUrl END (no urls) ===")
            return
        }

        // ---- 4. Fetch m3u8 bodies in parallel ----
        Log.d(TAG, "[5] fetching ${m3u8Hits.size} m3u8 bodies (RAW UA)…")
        val m3u8Bodies = if (m3u8Hits.isEmpty()) emptyList() else coroutineScope {
            m3u8Hits.map { h ->
                async {
                    val t0 = System.currentTimeMillis()
                    val body = try {
                        withTimeoutOrNull(M3U8_TIMEOUT_MS) {
                            app.get(h.url, referer = url, headers = CDN_HEADERS).text
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[5] body fetch threw for ${h.url}: ${e.message}")
                        null
                    }
                    val ms = System.currentTimeMillis() - t0
                    Log.d(TAG, "[5] body ${if (body != null) "ok" else "NULL"} " +
                        "(${body?.length ?: 0} bytes, ${ms}ms): ${h.url}")
                    body
                }
            }.awaitAll()
        }

        val emittedLabels = HashSet<String>()
        fun emitLabel(label: String): Boolean {
            val isNew = emittedLabels.add(label)
            Log.d(TAG, "[6] emitLabel('$label') → ${if (isNew) "accepted" else "duplicate, skipped"}")
            return isNew
        }

        // ---- 5. Classify each m3u8 ----
        m3u8Hits.forEachIndexed { i, hit ->
            Log.d(TAG, "[6.$i] processing m3u8: ${hit.url}")
            val body = m3u8Bodies.getOrNull(i)

            if (body == null) {
                Log.d(TAG, "[6.$i] body NULL — fallback: deliver master as-is")
                if (emitLabel("$name (Auto)")) {
                    callback(newExtractorLink(name, "$name (Auto)", hit.url, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = CDN_HEADERS
                    })
                }
                return@forEachIndexed
            }

            val parsed = parseVariants(body)
            Log.d(TAG, "[6.$i] parseVariants: ${parsed.size} STREAM-INF blocks")
            parsed.forEachIndexed { j, v ->
                Log.d(TAG, "[6.$i.$j] bw=${v.bandwidth} h=${v.height} " +
                    "url=${v.url ?: "MALFORMED (no uri)"}")
            }

            if (parsed.isEmpty()) {
                Log.d(TAG, "[6.$i] not a master (no STREAM-INF) — treat as single-quality")
                val q = detectQualityFromUrl(hit.url)
                val label = if (q != null) "$name ${q}p" else name
                if (emitLabel(label)) {
                    callback(newExtractorLink(name, label, hit.url, ExtractorLinkType.M3U8) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = CDN_HEADERS
                    })
                }
                return@forEachIndexed
            }

            val valid = parsed.mapNotNull { v ->
                if (v.url == null) return@mapNotNull null
                val resolved = resolveUrl(hit.url, v.url)
                if (resolved == hit.url || resolved == hit.url.trimEnd('/')) {
                    Log.d(TAG, "[6.$i] self-reference dropped: $resolved")
                    null
                } else resolved to v.height
            }

            Log.d(TAG, "[6.$i] valid variants: ${valid.size}/${parsed.size}")

            when {
                valid.isEmpty() -> {
                    Log.e(TAG, "[6.$i] ALL MALFORMED — dropping master (would cause 3002)")
                }
                valid.size == parsed.size -> {
                    Log.d(TAG, "[6.$i] CLEAN master → deliver as-is (ABR works)")
                    if (emitLabel("$name (Auto)")) {
                        callback(newExtractorLink(name, "$name (Auto)", hit.url, ExtractorLinkType.M3U8) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                            this.headers = CDN_HEADERS
                        })
                    }
                }
                else -> {
                    Log.d(TAG, "[6.$i] PARTIAL malformed → split into ${valid.size} variants")
                    valid.forEach { (variantUrl, height) ->
                        val label = if (height != null) "$name ${height}p" else name
                        if (emitLabel(label)) {
                            callback(newExtractorLink(name, label, variantUrl, ExtractorLinkType.M3U8) {
                                this.referer = url
                                this.quality = height ?: Qualities.Unknown.value
                                this.headers = CDN_HEADERS
                            })
                        }
                    }
                }
            }
        }

        // ---- 6. mp4s ----
        Log.d(TAG, "[7] processing ${mp4Hits.size} mp4 hits")
        mp4Hits.forEachIndexed { i, h ->
            val preceding = scriptData.substring(Math.max(0, h.start - 150), h.start)
            val qMatch = RE_QUALITY_H.findAll(preceding).lastOrNull()
                ?: RE_QUALITY_BRACE.findAll(preceding).lastOrNull()

            var label = name
            if (qMatch != null) {
                val digits = qMatch.groupValues[1].filter { it.isDigit() }.take(4)
                if (digits.length in 3..4) label = "$name ${digits}p"
                Log.d(TAG, "[7.$i] quality found: $label")
            } else {
                Log.d(TAG, "[7.$i] no quality in JSON → label '$label'")
            }

            if (emitLabel(label)) {
                callback(newExtractorLink(name, label, h.url, INFER_TYPE) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                })
            }
        }

        Log.d(TAG, "=== getUrl END — ${emittedLabels.size} total links emitted ===")
    }

    // ---------- helpers ----------

    private fun parseVariants(text: String): List<MasterVariant> {
        val out = mutableListOf<MasterVariant>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = RE_BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val h  = RE_RESOLUTION.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val uri = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                val malformed = uri.isBlank() || uri.startsWith("#")
                out += MasterVariant(if (malformed) null else uri, bw, h)
                i += 2
            } else i++
        }
        return out
    }

    private fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val uri = runCatching { URI(base) }.getOrNull() ?: return path
        val origin = buildString {
            append(uri.scheme ?: "https"); append("://"); append(uri.host.orEmpty())
            val p = uri.port
            if (p > 0 && p != 80 && p != 443) append(":$p")
        }
        if (path.startsWith("/")) return origin + path
        val dir = uri.path.orEmpty().substringBeforeLast('/', "")
        return "$origin$dir/$path"
    }

    private fun detectQualityFromUrl(url: String): Int? =
        Regex("""\d{3,4}""").findAll(url)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 144..4320 }
            .maxOrNull()
}

open class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L

        private val STREAMPLAY_HEADERS = mapOf(
            "pragma" to "no-cache",
            "priority" to "u=0, i",
            "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-site" to "none",
            "sec-fetch-user" to "?1",
            "upgrade-insecure-requests" to "1",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        )
        private val RE_EVAL_BLOCK = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        private val RE_TOKEN = Regex("""kaken="(.*?)"""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url

        val doc = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.get(fixedUrl, timeout = 10000).document
            } catch (e: Exception) {
                null
            }
        } ?: return

        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val packedCode = RE_EVAL_BLOCK.find(packedScript)?.value ?: return

        // Fast path: token often present in the packed script already — skip JsUnpacker
        val token = RE_TOKEN.find(packedCode)?.groupValues?.getOrNull(1)
            ?: run {
                val unpackedJs = JsUnpacker(packedCode).unpack() ?: return
                RE_TOKEN.find(unpackedJs)?.groupValues?.getOrNull(1) ?: return
            }

        val apiUrl = "$mainUrl/api/?$token"
        val response = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                app.get(apiUrl, timeout = 10000).parsedSafe<Response>()
            } catch (e: Exception) {
                null
            }
        } ?: return

        val m3u8Url = response.sources.firstOrNull { it.file.isNotBlank() }?.file
        if (!m3u8Url.isNullOrEmpty()) {
            M3u8Helper.generateM3u8(name, m3u8Url, mainUrl, headers = STREAMPLAY_HEADERS).forEach(callback)
        }

        response.tracks.forEach { subtitle ->
            if (subtitle.file.isNotBlank()) {
                subtitleCallback(newSubtitleFile(lang = subtitle.label, url = subtitle.file))
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Response(
        val query: Query? = null,
        val status: String? = null,
        val message: String? = null,
        @param:JsonProperty("embed_url")
        val embedUrl: String? = null,
        @param:JsonProperty("download_url")
        val downloadUrl: String? = null,
        val title: String? = null,
        val poster: String? = null,
        val filmstrip: String? = null,
        val sources: List<Source> = emptyList(),
        val tracks: List<Track> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Query(
        val source: String? = null,
        val id: String? = null,
        val download: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(
        val file: String = "",
        val type: String? = null,
        val label: String? = null,
        val default: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        val file: String = "",
        val label: String = "Subtitle",
        val default: Boolean? = null,
    )
}

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    companion object {
        private const val FETCH_TIMEOUT_MS = 12_000L

        // Precompiled regexes
        private val RE_VIDEO_ID = Regex("""/video(?:embed)?/(\d+)""")
        private val RE_MID_PARAM = Regex("""[?&]mid=(\d+)""")

        // Static quality map — no re-allocation on every video entry
        private val QUALITY_MAP = mapOf(
            "mobile" to Qualities.P144.value,
            "lowest" to Qualities.P240.value,
            "low"    to Qualities.P360.value,
            "sd"     to Qualities.P480.value,
            "hd"     to Qualities.P720.value,
            "full"   to Qualities.P1080.value,
            "quad"   to Qualities.P1440.value,
            "ultra"  to Qualities.P2160.value,
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Multi-pattern id extraction — handles /video/, /videoembed/, ?mid=, and bare-id URLs
            val id = RE_VIDEO_ID.find(url)?.groupValues?.get(1)
                ?: RE_MID_PARAM.find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank() || !id.all { it.isDigit() }) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"

            val json = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                try {
                    app.post(apiUrl).parsedSafe<OkRuResponse>()
                } catch (e: Exception) {
                    null
                }
            } ?: return

            // 1. MP4 variants first (fastest playback)
            json.videos?.forEach { video ->
                val qName = video.name?.lowercase() ?: ""
                val vidUrl = video.url ?: ""

                if (vidUrl.isBlank() || vidUrl.contains("usr_login")) return@forEach

                val qualityValue = QUALITY_MAP[qName] ?: Qualities.Unknown.value
                val displayLabel = if (qualityValue != Qualities.Unknown.value) "MP4 ${qualityValue}p" else "MP4 $qName"

                callback(
                    newExtractorLink(
                        name = "${this.name} MP4",
                        source = "${this.name} $displayLabel",
                        url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"),
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    }
                )
            }

            // 2. HLS fallback — track success so DASH can run if HLS parse yields nothing
            val hlsUrl = json.hlsManifestUrl.orEmpty()
            var hlsSucceeded = false

            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                val cleanHls = hlsUrl.replace("\\u0026", "&").replace("\\/", "/")
                val hlsLinks = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    try {
                        M3u8Helper.generateM3u8("$name HLS", cleanHls, url)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()

                if (hlsLinks.isNotEmpty()) {
                    hlsLinks.forEach(callback)
                    hlsSucceeded = true
                }
            }

            // 3. DASH fallback — runs if HLS was missing OR failed to parse
            if (!hlsSucceeded) {
                val dashUrl = json.dashManifestUrl.orEmpty()
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(
                        newExtractorLink(
                            name = "$name DASH",
                            source = "$name DASH",
                            url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"),
                            type = ExtractorLinkType.DASH
                        ) { this.referer = "https://ok.ru/" }
                    )
                }
            }
        } catch (e: Exception) {
            // swallow — extractor failure must not crash the provider
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuResponse(
        val videos: List<OkRuVideo>? = null,
        val hlsManifestUrl: String? = null,
        val dashManifestUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkRuVideo(
        val name: String? = null,
        val url: String? = null,
    )
}
