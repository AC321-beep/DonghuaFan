package com.naruldonghua

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ── File-level constants ────────────────────────────────────────────────────
private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val RE_PACKED_SCRIPT = Regex(
    """eval\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[a-zA-Z0-9_]\s*\)[\s\S]*?split\(\s*['"]\|['"]\s*\)[\s\S]*?\}\s*\([\s\S]*?\)\s*\)""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_M3U8_ANY = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
private val RE_MP4_ATTR = Regex(
    """(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""",
    RegexOption.IGNORE_CASE
)
private val RE_MP4_ANY = Regex("""(https?://[^"'\s\\]+\.(?:mp4|mkv)[^"'\s\\]*)""")
private val RE_SUB_URL = Regex(
    """(https?://[^"'\s\\<>]+\.(?:vtt|srt|ass|ssa)(?:\?[^"'\s\\<>]*)?)""",
    RegexOption.IGNORE_CASE
)

private fun originOf(url: String): String? =
    Regex("""^(https?://[^/?#]+)""").find(url)?.groupValues?.getOrNull(1)

private fun resolveUrl(uri: String, masterUrl: String, origin: String): String = when {
    uri.startsWith("http://", true) || uri.startsWith("https://", true) -> uri
    uri.startsWith("//") -> "https:$uri"
    uri.startsWith("/")  -> "$origin$uri"
    else                 -> "${masterUrl.substringBeforeLast('/')}/$uri"
}

/** Emit every subtitle URL found in a blob of text (packed JS, JSON config, …). */
private suspend fun emitSubtitlesFromText(
    text: String,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    if (text.isEmpty()) return
    val seen = HashSet<String>()
    RE_SUB_URL.findAll(text).forEach { m ->
        val subUrl = m.groupValues[1].replace("\\/", "/")
        if (seen.add(subUrl)) {
            subtitleCallback(newSubtitleFile("Sub", subUrl))
        }
    }
}

/** Fetch an HLS master and emit every `#EXT-X-MEDIA:TYPE=SUBTITLES` rendition. */
private suspend fun emitSubtitlesFromM3u8(
    m3u8Url: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    val master = try {
        app.get(m3u8Url, headers = headers).text
    } catch (_: Exception) {
        return
    }
    emitSubtitlesFromM3u8Content(master, m3u8Url, headers, subtitleCallback)
}

private suspend fun emitSubtitlesFromM3u8Content(
    master: String,
    m3u8Url: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    if (!master.contains("#EXT-X-MEDIA", ignoreCase = true)) return
    val origin = originOf(m3u8Url) ?: return

    Regex("""#EXT-X-MEDIA:([^\r\n#]*)""").findAll(master).forEach { match ->
        val attrs = match.groupValues[1]
        if (!attrs.contains("TYPE=SUBTITLES", ignoreCase = true)) return@forEach

        val uri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: return@forEach
        val label = Regex("""NAME="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: Regex("""LANGUAGE="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: "Sub"

        val resolved = resolveUrl(uri, m3u8Url, origin)
        subtitleCallback(newSubtitleFile(label, resolved) { this.headers = headers })
    }
}

/**
 * Reusable JS-unpacker helper. Handles every packed-JS player (Dean Edwards
 * "p,a,c,k,e,d" encoding) which is what p2pstream classic, bysekoze,
 * upns.live and emturbovid all use.
 */
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val response = try {
        app.get(url, headers = safeHeaders).text
    } catch (_: Exception) {
        return
    }

    val packedScript = RE_PACKED_SCRIPT.find(response)?.value
    val unpacked = JsUnpacker(packedScript ?: response).unpack() ?: response
    val text = unpacked.replace("\\/", "/")

    emitSubtitlesFromText(text, subtitleCallback)

    val m3u8 = RE_M3U8_ATTR.find(text)?.groupValues?.get(1)
        ?: RE_M3U8_ANY.find(text)?.groupValues?.get(1)
    if (m3u8 != null) {
        val normalized = m3u8.replace("\\/", "/")
        M3u8Helper.generateM3u8(name, normalized, url, headers = safeHeaders)
            .forEach(callback)
        emitSubtitlesFromM3u8(normalized, safeHeaders, subtitleCallback)
        return
    }

    val mp4 = RE_MP4_ATTR.find(text)?.groupValues?.get(1)
        ?: RE_MP4_ANY.find(text)?.groupValues?.get(1)
    if (mp4 != null) {
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) {
            this.referer = url
        })
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  P2pstream — handles classic packed-JS AND modern SPA (React/Vite) variants.
//
//  Classic (p2pstream.vip):
//      https://p2pstream.vip/#{hash}       → /e/{hash}  → packed JS
//
//  SPA (narulplex.p2pstream.vip):
//      https://narulplex.p2pstream.vip/#q5miiz
//          → serves a Vite SPA shell
//          → JS bundle calls REST API (/api/v1/video/{id}, /api/source/{id}, …)
//          → API returns JSON with m3u8/mp4 URL
//
//  Detection + caching strategy:
//    1. If we already learned a working endpoint for this origin, reuse it.
//    2. Otherwise try classic /e/{id} (works for old subdomains).
//    3. If we got an SPA shell back, fetch the JS bundle (cached per origin),
//       mine it for /api/... paths, and probe them once with the video id.
//    4. Cache whichever endpoint succeeded — no re-probing on next episode.
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "P2pstream"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        // Cache: origin → JS bundle source (fetched once).
        private val scriptCache = ConcurrentHashMap<String, String>()
        // Cache: origin → the endpoint path we know works.
        private val workingEndpointCache = ConcurrentHashMap<String, String>()

        // Endpoint paths to try, in priority order.  Cap at 6 to bound
        // the worst-case request count when the bundle mining fails.
        private val DEFAULT_ENDPOINTS = listOf(
            "/api/v1/video", "/api/video", "/api/source",
            "/api/stream",   "/api/get",   "/api/embed",
        )

        private val RE_API_PATH = Regex(
            """["'](/api/[a-zA-Z0-9_\-/]{2,60})["']"""
        )
        // Full-URL APIs (some deployments use a different subdomain).
        private val RE_API_URL = Regex(
            """["'](https?://api\.[^"']+?/[a-zA-Z0-9_\-/]{2,60})["']"""
        )
        private val RE_JS_BUNDLE = Regex(
            """src=["']([^"']*?/assets/index-[^"']+\.js)["']"""
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url) ?: return
        // Strip the &dl=1 flag so play/download resolve to the same video.
        val id = url.substringAfter("#", "").substringBefore("&").trim()
        if (id.isBlank()) return

        val headers = mapOf(
            "Origin"     to origin,
            "Referer"    to "$origin/",
            "User-Agent" to DEFAULT_UA,
        )

        // 1) Fast path: a previously successful endpoint for this origin.
        workingEndpointCache[origin]?.let { ep ->
            if (probeEndpoint(origin, ep, id, headers, subtitleCallback, callback)) return
        }

        // 2) Classic packed-JS extraction.
        val classicUrl = "$origin/e/$id"
        val classicBody = try {
            app.get(classicUrl, headers = headers).text
        } catch (_: Exception) { null }

        if (classicBody != null) {
            if (RE_PACKED_SCRIPT.containsMatchIn(classicBody)) {
                println("[P2pstream] $origin → classic packed JS")
                manualJsUnpackExtraction(classicUrl, name, headers, subtitleCallback, callback)
                return
            }
            // Not packed. Could still be a JSON redirect, a direct m3u8, or
            // an SPA shell. Try direct m3u8/mp4 first — cheap.
            if (tryDirectMedia(classicBody, origin, headers, subtitleCallback, callback)) return
        }

        // 3) SPA API probing.
        val html = classicBody ?: try {
            app.get("$origin/", headers = headers).text
        } catch (_: Exception) { null } ?: return

        if (!looksLikeSpa(html)) {
            // Neither packed JS nor SPA — give up cleanly.
            println("[P2pstream] $origin → unknown shape, no links emitted")
            return
        }

        println("[P2pstream] $origin → SPA shell, probing API endpoints")
        trySpaApi(origin, id, html, headers, subtitleCallback, callback)
    }

    // ── SPA path ───────────────────────────────────────────────────────
    private suspend fun trySpaApi(
        origin: String,
        id: String,
        shellHtml: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val jsUrl = RE_JS_BUNDLE.find(shellHtml)?.groupValues?.get(1)
            ?.let { if (it.startsWith("http")) it else "$origin${if (it.startsWith("/")) "" else "/"}$it" }
            ?: "$origin/assets/index.js"

        val js = scriptCache[origin] ?: try {
            app.get(jsUrl, headers = headers).text.also { scriptCache[origin] = it }
        } catch (_: Exception) { null } ?: return

        // Build the endpoint candidate list: JS-derived first, then defaults.
        val fromJs = buildList {
            RE_API_PATH.findAll(js).map { it.groupValues[1] }.distinct().forEach { path ->
                if (listOf("video", "source", "stream", "get", "embed", "player")
                        .any { path.contains(it, true) }) {
                    add(path)
                }
            }
            RE_API_URL.findAll(js).map { it.groupValues[1] }.distinct().forEach { fullUrl ->
                if (listOf("video", "source", "stream", "get", "embed")
                        .any { fullUrl.contains(it, true) }) {
                    add(fullUrl)
                }
            }
        }
        val candidates = (fromJs + DEFAULT_ENDPOINTS).distinct().take(6)

        for (ep in candidates) {
            if (probeEndpoint(origin, ep, id, headers, subtitleCallback, callback)) {
                workingEndpointCache[origin] = ep
                println("[P2pstream] $origin → cached endpoint: $ep")
                return
            }
        }
        println("[P2pstream] $origin → all endpoints failed")
    }

    /** Probe a single endpoint with 4 common param names. Returns true on success. */
    private suspend fun probeEndpoint(
        origin: String,
        endpoint: String,
        id: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Absolute URL (from JS mining) vs. relative path.
        val baseUrl = if (endpoint.startsWith("http")) endpoint
                      else if (endpoint.startsWith("/")) "$origin$endpoint"
                      else "$origin/$endpoint"

        for (param in listOf("id", "hash", "v", "vid")) {
            val u = if (baseUrl.contains('?')) "$baseUrl&$param=$id"
                    else                    "$baseUrl?$param=$id"
            try {
                val body = app.get(u, headers = headers).text
                if (body.isBlank()) continue
                if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return true
                if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) return true
            } catch (_: Exception) { /* try next */ }
        }
        return false
    }

    // ── Parsers ────────────────────────────────────────────────────────
    private suspend fun tryDirectMedia(
        text: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Skip huge SPA shells early — no point scanning megabytes.
        if (text.length > 500_000) return false
        val normalized = text.replace("\\/", "/")

        RE_M3U8_ATTR.find(normalized)?.groupValues?.get(1)?.let { m3u8 ->
            emitHls(m3u8, origin, headers, subtitleCallback, callback)
            return true
        }
        RE_M3U8_ANY.find(normalized)?.groupValues?.get(1)?.let { m3u8 ->
            emitHls(m3u8, origin, headers, subtitleCallback, callback)
            return true
        }
        RE_MP4_ATTR.find(normalized)?.groupValues?.get(1)?.let { mp4 ->
            callback(newExtractorLink(name, name, mp4, INFER_TYPE) {
                this.referer = "$origin/"
            })
            return true
        }
        RE_MP4_ANY.find(normalized)?.groupValues?.get(1)?.let { mp4 ->
            callback(newExtractorLink(name, name, mp4, INFER_TYPE) {
                this.referer = "$origin/"
            })
            return true
        }
        return false
    }

    private suspend fun tryJsonBody(
        body: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!body.trimStart().startsWith("{")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }

        // Direct string fields.
        val direct = sequenceOf(
            json.optString("url"),
            json.optString("source"),
            json.optString("src"),
            json.optString("file"),
            json.optString("video_url"),
            json.optString("videoUrl"),
            json.optString("stream_url"),
            json.optString("streamUrl"),
            json.optJSONObject("data")?.optString("url"),
            json.optJSONObject("data")?.optString("src"),
            json.optJSONObject("data")?.optString("file"),
            json.optJSONObject("result")?.optString("url"),
        ).firstOrNull { it.isNotBlank() && (it.startsWith("http") || it.startsWith("//")) }

        if (!direct.isNullOrBlank()) {
            emitSingleUrl(normalize(direct), origin, headers, subtitleCallback, callback)
            return true
        }

        // Array shapes.
        for (key in listOf("streams", "sources", "links", "videos", "mirrors")) {
            val arr = json.optJSONArray(key) ?: continue
            var any = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val u = obj.optString("url").ifBlank { obj.optString("file") }
                    .ifBlank { obj.optString("src") }
                    .ifBlank { obj.optString("link") }
                if (u.isBlank()) continue
                emitSingleUrl(normalize(u), origin, headers, subtitleCallback, callback)
                any = true
            }
            if (any) return true
        }
        return false
    }

    private fun normalize(url: String): String =
        when {
            url.startsWith("//") -> "https:$url"
            else                 -> url
        }

    private suspend fun emitSingleUrl(
        url: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.contains(".m3u8")) {
            emitHls(url, origin, headers, subtitleCallback, callback)
        } else {
            callback(newExtractorLink(name, name, url, INFER_TYPE) {
                this.referer = "$origin/"
            })
        }
    }

    private suspend fun emitHls(
        m3u8: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers)
            .forEach(callback)
        emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
    }

    private fun looksLikeSpa(html: String): Boolean =
        html.contains("/assets/index-", ignoreCase = true) ||
            html.contains("id=\"root\"", ignoreCase = true) ||
            html.contains("id=\"app\"", ignoreCase = true) ||
            html.contains("vite", ignoreCase = true)
}

// ════════════════════════════════════════════════════════════════════════════
//  Bysekoze / Bysejikuar — unchanged
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "VGPlayer"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url) ?: return
        val headers = mapOf(
            "Origin"  to origin,
            "Referer" to "$origin/",
        )
        manualJsUnpackExtraction(url, name, headers, subtitleCallback, callback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  AbyssPlayer — unchanged
// ════════════════════════════════════════════════════════════════════════════
@Suppress("DEPRECATION")
class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val RE_DATAS = Regex("""const\s+datas\s*=\s*"([^"]*)"""")

        private val DECODER_HEADERS = mapOf(
            "User-Agent"   to DEFAULT_UA,
            "Origin"       to "https://playhydrax.com",
            "Referer"      to "https://playhydrax.com/",
            "Content-Type" to "application/json",
        )

        private val SUBTITLE_ARRAY_KEYS =
            listOf("subtitles", "tracks", "captions", "subs")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(url) ?: return
        val pageHeaders = mapOf(
            "User-Agent" to DEFAULT_UA,
            "Origin"     to origin,
            "Referer"    to "$origin/",
        )

        val doc = try {
            app.get(url, headers = pageHeaders).document
        } catch (_: Exception) {
            return
        }

        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = DECODER_HEADERS,
                requestBody = reqBody,
            ).text
        } catch (_: Exception) {
            return
        }

        val result = JSONObject(responseText).optJSONObject("result") ?: return

        val seenSubs = HashSet<String>()
        for (key in SUBTITLE_ARRAY_KEYS) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("file").ifBlank { obj.optString("url") }
                    .ifBlank { obj.optString("src") }
                    .ifBlank { obj.optString("srcUrl") }
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = obj.optString("label").ifBlank { obj.optString("name") }
                    .ifBlank { obj.optString("lang") }
                    .ifBlank { obj.optString("language") }
                    .ifBlank { "Sub" }
                subtitleCallback(newSubtitleFile(label, subUrl) {
                    this.headers = mapOf(
                        "Referer" to "$origin/",
                        "Origin"  to origin,
                    )
                })
            }
        }

        val sources = result.optJSONArray("sources") ?: return
        val seenLinks = HashSet<String>(sources.length())
        val seenM3u8 = HashSet<String>()

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)

            if (src == null) {
                val stringUrl = sources.optString(i)
                if (stringUrl.isBlank() || !seenLinks.add(stringUrl)) continue
                if (stringUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8("$name HLS", stringUrl, origin)
                        .forEach(callback)
                    if (seenM3u8.add(stringUrl)) {
                        emitSubtitlesFromM3u8(stringUrl, pageHeaders, subtitleCallback)
                    }
                } else {
                    callback(newExtractorLink(name, name, stringUrl, INFER_TYPE) {
                        this.referer = origin
                    })
                }
                continue
            }

            if (!src.optBoolean("status", false) && !src.has("url")) continue
            val srcUrl = src.optString("url")
            if (srcUrl.isBlank() || !seenLinks.add(srcUrl)) continue

            val rawLabel = src.optString("label")
                .ifBlank { src.optString("quality") }
                .ifBlank { src.optString("type") }
                .ifBlank { src.optString("res") }
                .ifBlank { src.optString("resolution") }

            val qualityInt = when {
                rawLabel.contains("1080") || rawLabel.contains("FHD", true) -> Qualities.P1080.value
                rawLabel.contains("720")  || rawLabel.contains("HD", true)  -> Qualities.P720.value
                rawLabel.contains("480")  || rawLabel.contains("SD", true)  -> Qualities.P480.value
                rawLabel.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            val display = when {
                qualityInt != Qualities.Unknown.value -> "${qualityInt}p"
                rawLabel.isNotBlank() -> rawLabel.uppercase()
                else -> ""
            }

            if (srcUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8("$name HLS", srcUrl, origin).forEach { link ->
                    val finalQ =
                        if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value)
                            qualityInt else link.quality
                    val finalName = when {
                        finalQ == qualityInt && qualityInt != Qualities.Unknown.value ->
                            "$name HLS ${qualityInt}p"
                        link.name == "$name HLS" && display.isNotBlank() ->
                            "$name HLS $display"
                        else -> link.name
                    }
                    callback(ExtractorLink(
                        name = link.name,
                        source = finalName,
                        url = link.url,
                        referer = link.referer,
                        quality = finalQ,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData,
                    ))
                }
                if (seenM3u8.add(srcUrl)) {
                    emitSubtitlesFromM3u8(srcUrl, pageHeaders, subtitleCallback)
                }
            } else {
                val sourceName = if (display.isNotBlank()) "$name $display" else name
                callback(newExtractorLink(name, sourceName, srcUrl, INFER_TYPE) {
                    this.referer = origin
                    this.quality = qualityInt
                })
            }
        }
    }
}
