package com.donghuastream

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
    val url: String?,
    val bandwidth: Long,
    val height: Int?
)

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
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
        private val RE_EXT_X_RESOLUTION = Regex("""#EXT-X-RESOLUTION:\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val RE_PATH_Q_P = Regex("""(?:^|[^0-9])(\d{3,4})p(?=[^0-9]|$)""")
        private val RE_PATH_Q_N = Regex("""[_\-/](\d{3,4})(?=[^0-9]|$)""")

        private val JUNK_KEYWORDS = listOf("/assets/", "tracker", "thumb")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fast path — direct media URL
        if (url.endsWith(".mp4", true) || url.endsWith(".m3u8", true)) {
            val t = if (url.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, url, t) { this.referer = url })
            return
        }

        // ---- 1. Fetch embed HTML ----
        val html = withTimeoutOrNull(HTML_TIMEOUT_MS) {
            try {
                app.get(url, referer = referer ?: "$mainUrl/", headers = PAGE_HEADERS).text
            } catch (e: Exception) { null }
        } ?: return

        // ---- 2. Slice JSON ----
        val afterMp4 = html.substringAfter("{\"mp4", "")
        val scriptData = if (afterMp4.isNotEmpty()) afterMp4.substringBefore("\"evt\":{") else html

        // ---- 3. Collect URLs ----
        val seen = LinkedHashSet<String>()
        data class Hit(val url: String, val start: Int)
        val m3u8Hits = mutableListOf<Hit>()
        val mp4Hits  = mutableListOf<Hit>()

        RE_MEDIA_URL.findAll(scriptData).forEach { m ->
            val clean = m.groupValues[1].replace("\\/", "/")
            if (JUNK_KEYWORDS.any { clean.contains(it, true) }) return@forEach
            if (!seen.add(clean)) return@forEach
            if (clean.contains(".m3u8", true)) m3u8Hits += Hit(clean, m.range.first)
            else                                mp4Hits  += Hit(clean, m.range.first)
        }

        if (m3u8Hits.isEmpty() && mp4Hits.isEmpty()) return

        // ---- 4. Fetch m3u8 bodies in parallel ----
        val m3u8Bodies = if (m3u8Hits.isEmpty()) emptyList() else coroutineScope {
            m3u8Hits.map { h ->
                async {
                    try {
                        withTimeoutOrNull(M3U8_TIMEOUT_MS) {
                            app.get(h.url, referer = url, headers = CDN_HEADERS).text
                        }
                    } catch (e: Exception) { null }
                }
            }.awaitAll()
        }

        val emittedLabels = HashSet<String>()
        fun emitLabel(label: String): Boolean = emittedLabels.add(label)

        // ---- 5. Classify each m3u8 ----
        m3u8Hits.forEachIndexed { i, hit ->
            val body = m3u8Bodies.getOrNull(i)

            if (body == null) {
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

            if (parsed.isEmpty()) {
                // Not a master. Try body tag → URL path → bare name.
                val bodyHeight = RE_EXT_X_RESOLUTION.find(body)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                val urlHeight = detectQualityFromUrl(hit.url)
                val q = bodyHeight ?: urlHeight
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
                if (resolved == hit.url || resolved == hit.url.trimEnd('/')) null
                else resolved to v.height
            }

            when {
                valid.isEmpty() -> {
                    // All malformed → drop silently (prevents ExoPlayer 3002)
                }
                valid.size == parsed.size -> {
                    // Clean master → deliver as-is so ABR works
                    if (emitLabel("$name (Auto)")) {
                        callback(newExtractorLink(name, "$name (Auto)", hit.url, ExtractorLinkType.M3U8) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                            this.headers = CDN_HEADERS
                        })
                    }
                }
                else -> {
                    // Partial malformed → split into individual valid variants
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
        mp4Hits.forEach { h ->
            val preceding = scriptData.substring(Math.max(0, h.start - 150), h.start)
            val qMatch = RE_QUALITY_H.findAll(preceding).lastOrNull()
                ?: RE_QUALITY_BRACE.findAll(preceding).lastOrNull()

            var label = name
            if (qMatch != null) {
                val digits = qMatch.groupValues[1].filter { it.isDigit() }.take(4)
                if (digits.length in 3..4) label = "$name ${digits}p"
            } else {
                val fromUrl = detectQualityFromUrl(h.url)
                if (fromUrl != null) label = "$name ${fromUrl}p"
            }

            if (emitLabel(label)) {
                callback(newExtractorLink(name, label, h.url, INFER_TYPE) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                })
            }
        }
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

    /**
     * Extract a resolution from the URL — path only, no host/query.
     * Prefers explicit "<N>p" markers, then "_N" / "-N" / "/N/" patterns.
     * Returns null if no reliable marker is found.
     */
    private fun detectQualityFromUrl(url: String): Int? {
        val path = url
            .substringAfter("://", url)
            .substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')

        RE_PATH_Q_P.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { if (it in 144..2160) return it }

        RE_PATH_Q_N.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { if (it in 144..2160) return it }

        return null
    }
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

        private val RE_VIDEO_ID = Regex("""/video(?:embed)?/(\d+)""")
        private val RE_MID_PARAM = Regex("""[?&]mid=(\d+)""")

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
            // swallow
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
