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
import org.json.JSONObject
import java.net.URI

// ==================== Rumble ====================

private data class RumbleVariant(val url: String?, val bandwidth: Long, val height: Int?)

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    companion object {
        private const val HTML_TIMEOUT_MS = 12_000L
        private const val M3U8_TIMEOUT_MS = 6_000L
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val RAW_UA = "okhttp/4.12.0"

        private val PAGE_HEADERS = mapOf("Accept" to "*/*", "User-Agent" to BROWSER_UA)
        private val CDN_HEADERS  = mapOf("Accept" to "*/*", "User-Agent" to RAW_UA)

        private val RE_MEDIA      = Regex("""(https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:m3u8|mp4)[^"'\s<>‘’“”]*)""")
        private val RE_Q_H        = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""")
        private val RE_Q_BRACE    = Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")
        private val RE_BANDWIDTH  = Regex("""BANDWIDTH=(\d+)""")
        private val RE_RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val RE_X_RES      = Regex("""#EXT-X-RESOLUTION:\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val RE_PATH_Q_P   = Regex("""(?:^|[^0-9])(\d{3,4})p(?=[^0-9]|$)""")
        private val RE_PATH_Q_N   = Regex("""[_\-/](\d{3,4})(?=[^0-9]|$)""")
        private val JUNK          = listOf("/assets/", "tracker", "thumb")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Fast path
            if (url.endsWith(".mp4", true) || url.endsWith(".m3u8", true)) {
                val t = if (url.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(newExtractorLink(name, name, url, t) { this.referer = url })
                return
            }

            // Fetch embed HTML
            val html = withTimeoutOrNull(HTML_TIMEOUT_MS) {
                try { app.get(url, referer = referer ?: "$mainUrl/", headers = PAGE_HEADERS).text }
                catch (e: Exception) { null }
            } ?: return

            val data = html.substringAfter("{\"mp4", "").ifEmpty { html }.substringBefore("\"evt\":{")

            // Collect URLs once
            val seen = HashSet<String>()
            val m3u8s = mutableListOf<Pair<String, Int>>()
            val mp4s  = mutableListOf<Pair<String, Int>>()

            RE_MEDIA.findAll(data).forEach { m ->
                val u = m.groupValues[1].replace("\\/", "/")
                if (JUNK.any { u.contains(it, true) }) return@forEach
                if (!seen.add(u)) return@forEach
                if (u.contains(".m3u8", true)) m3u8s += u to m.range.first
                else                            mp4s  += u to m.range.first
            }
            if (m3u8s.isEmpty() && mp4s.isEmpty()) return

            // Fetch m3u8 bodies in parallel
            val bodies = if (m3u8s.isEmpty()) emptyList() else coroutineScope {
                m3u8s.map { (u, _) -> async {
                    try { withTimeoutOrNull(M3U8_TIMEOUT_MS) {
                        app.get(u, referer = url, headers = CDN_HEADERS).text
                    } } catch (e: Exception) { null }
                } }.awaitAll()
            }

            val emitted = HashSet<String>()
            fun fresh(label: String) = emitted.add(label)

            // Process m3u8s
            m3u8s.forEachIndexed { i, (m3u8Url, _) ->
                val body = bodies.getOrNull(i)

                // Unreachable body: emit master as-is so playback still works
                if (body == null) {
                    if (fresh("$name (Auto)")) callback(link(name, "$name (Auto)", m3u8Url, url, CDN_HEADERS))
                    return@forEachIndexed
                }

                val variants = parseVariants(body)

                // Not a master → use body tag, then URL pattern
                if (variants.isEmpty()) {
                    val q = RE_X_RES.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: qualityFromUrl(m3u8Url)
                    val label = if (q != null) "$name ${q}p" else name
                    if (fresh(label)) callback(link(name, label, m3u8Url, url, CDN_HEADERS))
                    return@forEachIndexed
                }

                // Resolve valid variants
                val valid = variants.mapNotNull { v ->
                    if (v.url == null) null
                    else resolve(m3u8Url, v.url).takeIf { it != m3u8Url }?.let { it to v.height }
                }

                when {
                    valid.isEmpty() -> Unit  // all malformed → drop silently (prevents 3002)
                    valid.size == variants.size -> {
                        // Clean master → deliver as-is so ABR works
                        if (fresh("$name (Auto)")) callback(link(name, "$name (Auto)", m3u8Url, url, CDN_HEADERS))
                    }
                    else -> valid.forEach { (u, h) ->
                        val label = if (h != null) "$name ${h}p" else name
                        if (fresh(label)) callback(link(name, label, u, url, CDN_HEADERS, h))
                    }
                }
            }

            // Process mp4s
            mp4s.forEach { (mp4Url, start) ->
                val preceding = data.substring(maxOf(0, start - 150), start)
                val qMatch = RE_Q_H.findAll(preceding).lastOrNull()
                    ?: RE_Q_BRACE.findAll(preceding).lastOrNull()
                val q = qMatch?.groupValues?.getOrNull(1)?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                    ?: qualityFromUrl(mp4Url)
                val label = if (q != null && q in 144..2160) "$name ${q}p" else name
                if (fresh(label)) callback(link(name, label, mp4Url, url, emptyMap(), q))
            }
        } catch (e: Exception) {
            // swallow
        }
    }

    // ---------- helpers ----------

   private fun link(
    src: String, label: String, u: String, referer: String,
    headers: Map<String, String>, quality: Int? = null
): ExtractorLink = newExtractorLink(src, label, u, INFER_TYPE) {
    this.referer = referer
    this.quality = quality ?: Qualities.Unknown.value
    if (headers.isNotEmpty()) this.headers = headers
}

    private fun parseVariants(text: String): List<RumbleVariant> {
        val out = mutableListOf<RumbleVariant>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = RE_BANDWIDTH.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val h  = RE_RESOLUTION.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val uri = lines.getOrNull(i + 1)?.trim().orEmpty()
                val malformed = uri.isBlank() || uri.startsWith("#")
                out += RumbleVariant(if (malformed) null else uri, bw, h)
                i += 2
            } else i++
        }
        return out
    }

    private fun resolve(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val uri = runCatching { URI(base) }.getOrNull() ?: return path
        val origin = buildString {
            append(uri.scheme ?: "https"); append("://"); append(uri.host.orEmpty())
            val p = uri.port
            if (p > 0 && p != 80 && p != 443) append(":$p")
        }
        return if (path.startsWith("/")) origin + path
        else "$origin${uri.path.orEmpty().substringBeforeLast('/', "")}/$path"
    }

    private fun qualityFromUrl(url: String): Int? {
        val path = url.substringAfter("://", url).substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
        RE_PATH_Q_P.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 144..2160 }?.let { return it }
        RE_PATH_Q_N.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 144..2160 }?.let { return it }
        return null
    }
}

// ==================== PlayStreamplay ====================

open class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    companion object {
        private val HEADERS = mapOf(
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
        private val RE_EVAL  = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        private val RE_TOKEN = Regex("""kaken="(.*?)"""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fixed = if (url.startsWith("//")) "https:$url" else url
            val doc = app.get(fixed, timeout = 10_000).document

            val packed = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
            val code = RE_EVAL.find(packed)?.value ?: return

            val token = RE_TOKEN.find(code)?.groupValues?.getOrNull(1)
                ?: run {
                    val unpacked = JsUnpacker(code).unpack() ?: return
                    RE_TOKEN.find(unpacked)?.groupValues?.getOrNull(1) ?: return
                }

            val response = app.get("$mainUrl/api/?$token", timeout = 10_000).parsedSafe<Response>() ?: return

            response.sources.firstOrNull { it.file.isNotBlank() }?.file?.let { m3u8 ->
                M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = HEADERS).forEach(callback)
            }

            response.tracks.forEach { t ->
                if (t.file.isNotBlank()) subtitleCallback(newSubtitleFile(lang = t.label, url = t.file))
            }
        } catch (e: Exception) {
            // swallow
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Response(
        val query: Query? = null,
        val status: String? = null,
        val message: String? = null,
        @param:JsonProperty("embed_url") val embedUrl: String? = null,
        @param:JsonProperty("download_url") val downloadUrl: String? = null,
        val title: String? = null,
        val poster: String? = null,
        val filmstrip: String? = null,
        val sources: List<Source> = emptyList(),
        val tracks: List<Track> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Query(val source: String? = null, val id: String? = null, val download: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(
        val file: String = "",
        val type: String? = null,
        val label: String? = null,
        val default: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(val file: String = "", val label: String = "Subtitle", val default: Boolean? = null)
}

// ==================== OkRu ====================

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    companion object {
        private val RE_VIDEO_ID = Regex("""/video(?:embed)?/(\d+)""")
        private val RE_MID      = Regex("""[?&]mid=(\d+)""")

        private fun qualityOf(n: String): Int = when (n.lowercase()) {
            "mobile" -> Qualities.P144.value
            "lowest" -> Qualities.P240.value
            "low"    -> Qualities.P360.value
            "sd"     -> Qualities.P480.value
            "hd"     -> Qualities.P720.value
            "full"   -> Qualities.P1080.value
            "quad"   -> Qualities.P1440.value
            "ultra"  -> Qualities.P2160.value
            else     -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = RE_VIDEO_ID.find(url)?.groupValues?.get(1)
                ?: RE_MID.find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank() || !id.all { it.isDigit() }) return

            val jsonStr = app.post("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id").text
            if (!jsonStr.startsWith("{")) return
            val json = JSONObject(jsonStr)

            // 1. MP4 variants
            json.optJSONArray("videos")?.let { videos ->
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    val vidUrl = v.optString("url").replace("\\u0026", "&").replace("\\/", "/")
                    if (vidUrl.isBlank() || vidUrl.contains("usr_login")) continue

                    val q = qualityOf(v.optString("name"))
                    val label = if (q != Qualities.Unknown.value) "MP4 ${q}p" else "MP4 ${v.optString("name")}"

                    callback(newExtractorLink("${name} MP4", "${name} $label", vidUrl, INFER_TYPE) {
                        this.referer = "https://ok.ru/"
                        this.quality = q
                    })
                }
            }

            // 2. HLS
            var hlsOk = false
            val hlsUrl = json.optString("hlsManifestUrl").replace("\\u0026", "&").replace("\\/", "/")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                val links = try { M3u8Helper.generateM3u8("$name HLS", hlsUrl, url) }
                            catch (e: Exception) { emptyList() }
                if (links.isNotEmpty()) { links.forEach(callback); hlsOk = true }
            }

            // 3. DASH fallback
            if (!hlsOk) {
                val dashUrl = json.optString("dashManifestUrl").replace("\\u0026", "&").replace("\\/", "/")
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(newExtractorLink("$name DASH", "$name DASH", dashUrl, ExtractorLinkType.DASH) {
                        this.referer = "https://ok.ru/"
                    })
                }
            }
        } catch (e: Exception) {
            // swallow
        }
    }
}
