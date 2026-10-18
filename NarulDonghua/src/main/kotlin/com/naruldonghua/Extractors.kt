package com.naruldonghua

import android.util.Log
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
import org.jsoup.Jsoup
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

private suspend fun emitSubtitlesFromText(
    text: String,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    if (text.isEmpty()) return
    val seen = HashSet<String>()
    RE_SUB_URL.findAll(text).forEach { m ->
        val subUrl = m.groupValues[1].replace("\\/", "/")
        if (seen.add(subUrl)) subtitleCallback(newSubtitleFile("Sub", subUrl))
    }
}

private suspend fun emitSubtitlesFromM3u8(
    m3u8Url: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    val master = try { app.get(m3u8Url, headers = headers).text } catch (_: Exception) { return }
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
        val uri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
        val label = Regex("""NAME="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: Regex("""LANGUAGE="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            ?: "Sub"
        subtitleCallback(newSubtitleFile(label, resolveUrl(uri, m3u8Url, origin)) {
            this.headers = headers
        })
    }
}

/**
 * Reusable JS-unpacker helper with iframe fallback.
 * Returns true if media was found and emitted.
 */
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 2) return false // Prevent infinite loops

    Log.e("Narul/JS", "=== manualJsUnpackExtraction (depth=$depth) ===")
    Log.e("Narul/JS", "url=$url name=$name")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val response = try {
        app.get(url, headers = safeHeaders).text
    } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}")
        return false
    }
    Log.e("Narul/JS", "response length=${response.length}")

    val packedScript = RE_PACKED_SCRIPT.find(response)?.value
    Log.e("Narul/JS", "packed=${packedScript != null}")
    val unpacked = JsUnpacker(packedScript ?: response).unpack() ?: response
    val text = unpacked.replace("\\/", "/")
    Log.e("Narul/JS", "unpacked length=${text.length}")

    emitSubtitlesFromText(text, subtitleCallback)

    val m3u8 = RE_M3U8_ATTR.find(text)?.groupValues?.get(1)
        ?: RE_M3U8_ANY.find(text)?.groupValues?.get(1)
    if (m3u8 != null) {
        Log.e("Narul/JS", "m3u8=$m3u8")
        val normalized = m3u8.replace("\\/", "/")
        M3u8Helper.generateM3u8(name, normalized, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(normalized, safeHeaders, subtitleCallback)
        return true
    }

    val mp4 = RE_MP4_ATTR.find(text)?.groupValues?.get(1)
        ?: RE_MP4_ANY.find(text)?.groupValues?.get(1)
    if (mp4 != null) {
        Log.e("Narul/JS", "mp4=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) {
            this.referer = url
        })
        return true
    }

    // Check for iframe wrapper (common on Bysejikuar/Filemoon /d/ pages)
    val iframeSrc = Jsoup.parse(text).selectFirst("iframe[src]")?.attr("src")
    if (!iframeSrc.isNullOrBlank()) {
        val fixedIframe = when {
            iframeSrc.startsWith("//") -> "https:$iframeSrc"
            iframeSrc.startsWith("/")  -> "${originOf(url)}$iframeSrc"
            else -> iframeSrc
        }
        Log.e("Narul/JS", "found iframe wrapper -> $fixedIframe")
        return manualJsUnpackExtraction(fixedIframe, name, headers, subtitleCallback, callback, depth + 1)
    }

    Log.e("Narul/JS", "no media or iframe found in unpacked body")
    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  Plex  (P2pstream / narulplex subdomains)
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Plex"
        private val scriptCache = ConcurrentHashMap<String, String>()
        private val workingEndpointCache = ConcurrentHashMap<String, String>()

        private val DEFAULT_ENDPOINTS = listOf(
            "/api/v1/video", "/api/video", "/api/source",
            "/api/stream",   "/api/get",   "/api/embed",
            "/api/v1/source", "/api/v1/stream", "/api/v1/get",
        )

        private val RE_API_PATH  = Regex("""["'](/(?:api|v1|ajax|embed)/[a-zA-Z0-9_\-/]{2,80})["']""")
        private val RE_API_URL   = Regex("""["'](https?://[^"']+?/(?:api|v1)/[a-zA-Z0-9_\-/]{2,80})["']""")
        private val RE_SCRIPT_SRC = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val JSON_TYPE    = "application/json; charset=utf-8".toMediaTypeOrNull()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.e(TAG, "=== Plex getUrl START ===")
        Log.e(TAG, "url=$url referer=$referer")

        val origin = originOf(url) ?: return
        val id = url.substringAfter("#", "").substringBefore("&").trim()
        if (id.isBlank()) { Log.e(TAG, "id blank → abort"); return }
        Log.e(TAG, "origin=$origin id=$id")

        val headers = mapOf(
            "Origin"     to origin,
            "Referer"    to "$origin/",
            "User-Agent" to DEFAULT_UA,
        )

        // ── Fast path: cached working endpoint for this origin ────────────
        workingEndpointCache[origin]?.let { ep ->
            Log.e(TAG, "FAST-PATH: cached endpoint=$ep")
            if (probeEndpoint(origin, ep, id, headers, subtitleCallback, callback)) return
            workingEndpointCache.remove(origin)  // stale — invalidate
        }

        // ── Classic /e/{id} ───────────────────────────────────────────────
        val classicUrl = "$origin/e/$id"
        val classicBody = try { app.get(classicUrl, headers = headers).text } catch (_: Exception) { null }
        Log.e(TAG, "classicBody length=${classicBody?.length ?: 0}")

        if (classicBody != null) {
            if (RE_PACKED_SCRIPT.containsMatchIn(classicBody)) {
                Log.e(TAG, "DETECTED: classic packed JS")
                if (manualJsUnpackExtraction(classicUrl, name, headers, subtitleCallback, callback)) return
            }
            if (tryDirectMedia(classicBody, origin, headers, subtitleCallback, callback)) return
        }

        // ── SPA fallback — ALWAYS try if classic failed ───────────────────
        // (Fix #1: previously a 404 from /e/{id} would short-circuit here)
        Log.e(TAG, "Classic failed; trying SPA")

        val shellHtml = classicBody?.takeIf { looksLikeSpa(it) }
            ?: try { app.get("$origin/", headers = headers).text } catch (_: Exception) { null }
            ?: run { Log.e(TAG, "SPA shell fetch failed → abort"); return }

        if (!looksLikeSpa(shellHtml)) {
            Log.e(TAG, "Not an SPA shell → abort")
            return
        }

        Log.e(TAG, "SPA confirmed; probing API")
        trySpaApi(origin, id, shellHtml, headers, subtitleCallback, callback)
    }

    // ──────────────────────────────────────────────────────────────────────
    private suspend fun trySpaApi(
        origin: String,
        id: String,
        shellHtml: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val jsUrl = findMainJsUrl(shellHtml, origin) ?: "$origin/assets/index.js"
        Log.e(TAG, "jsUrl=$jsUrl")

        val js = scriptCache[origin] ?: try {
            app.get(jsUrl, headers = headers).text.also { scriptCache[origin] = it }
        } catch (e: Exception) {
            Log.e(TAG, "JS fetch failed: ${e.message}"); null
        } ?: return

        Log.e(TAG, "JS length=${js.length}")

        val fromJs = buildList<String> {
            RE_API_PATH.findAll(js).map { it.groupValues[1] }.distinct().forEach { path ->
                if (listOf("video", "source", "stream", "get", "embed", "player")
                        .any { path.contains(it, true) }) add(path)
            }
            RE_API_URL.findAll(js).map { it.groupValues[1] }.distinct().forEach { fullUrl ->
                if (listOf("video", "source", "stream", "get", "embed")
                        .any { fullUrl.contains(it, true) }) add(fullUrl)
            }
        }
        Log.e(TAG, "JS-discovered endpoints: $fromJs")

        val candidates = (fromJs + DEFAULT_ENDPOINTS).distinct().take(8)
        Log.e(TAG, "Probing ${candidates.size} endpoints")

        for (ep in candidates) {
            if (probeEndpoint(origin, ep, id, headers, subtitleCallback, callback)) {
                workingEndpointCache[origin] = ep
                Log.e(TAG, "✓ WORKING endpoint: $ep (cached)")
                return
            }
        }
        Log.e(TAG, "✗ No working endpoint found")
    }

    // ──────────────────────────────────────────────────────────────────────
    private suspend fun probeEndpoint(
        origin: String,
        endpoint: String,
        id: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val baseUrl = when {
            endpoint.startsWith("http") -> endpoint
            endpoint.startsWith("/")    -> "$origin$endpoint"
            else                        -> "$origin/$endpoint"
        }

        // ── Query-param style ─────────────────────────────────────────────
        for (param in listOf("id", "hash", "v", "vid")) {
            val u = if (baseUrl.contains('?')) "$baseUrl&$param=$id" else "$baseUrl?$param=$id"
            Log.e(TAG, "  GET $u")
            try {
                val body = app.get(u, headers = headers).text
                if (body.isBlank()) continue
                if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return true
                if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) return true
            } catch (_: Exception) { }
        }

        // ── Path-param style (Fix #3) ─────────────────────────────────────
        val pathBase = baseUrl.substringBefore('?').trimEnd('/')
        for (suffix in listOf("/$id", "/hash/$id", "/id/$id")) {
            val u = "$pathBase$suffix"
            Log.e(TAG, "  GET $u")
            try {
                val body = app.get(u, headers = headers).text
                if (body.isBlank()) continue
                if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return true
                if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) return true
            } catch (_: Exception) { }
        }

        // ── POST JSON body style (Fix #3) ─────────────────────────────────
        val postHeaders = headers + ("Content-Type" to "application/json; charset=utf-8")
        for (payload in listOf(
            """{"id":"$id"}""", """{"hash":"$id"}""", """{"v":"$id"}"""
        )) {
            Log.e(TAG, "  POST $baseUrl body=$payload")
            try {
                val reqBody = payload.toRequestBody(JSON_TYPE)
                val body = app.post(baseUrl, headers = postHeaders, requestBody = reqBody).text
                if (body.isBlank()) continue
                if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return true
                if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) return true
            } catch (_: Exception) { }
        }

        return false
    }

    // ──────────────────────────────────────────────────────────────────────
    private fun findMainJsUrl(shellHtml: String, origin: String): String? {
        val srcs = RE_SCRIPT_SRC.findAll(shellHtml).map { it.groupValues[1] }.toList()
        Log.e(TAG, "  script srcs: $srcs")

        fun absolutize(u: String): String = when {
            u.startsWith("http") -> u
            u.startsWith("//")   -> "https:$u"
            u.startsWith("/")    -> "$origin$u"
            else                 -> "$origin/$u"
        }

        // Priority 1: Vite default  /assets/index-<hash>.js
        srcs.firstOrNull { it.matches(Regex(".*/assets/index-[^/]+\\.js.*")) }
            ?.let { return absolutize(it) }
        // Priority 2: CRA-style  /static/js/main.<hash>.js
        srcs.firstOrNull { it.matches(Regex(".*/static/js/[^/]+\\.js.*")) }
            ?.let { return absolutize(it) }
        // Priority 3: any /assets/*.js
        srcs.firstOrNull { it.contains("/assets/") && it.contains(".js") }
            ?.let { return absolutize(it) }
        // Priority 4: any local .js
        srcs.firstOrNull { !it.startsWith("http") && !it.startsWith("//") && it.contains(".js") }
            ?.let { return absolutize(it) }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    private suspend fun tryDirectMedia(
        text: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (text.length > 500_000) return false
        val normalized = text.replace("\\/", "/")

        RE_M3U8_ATTR.find(normalized)?.groupValues?.get(1)
            ?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_M3U8_ANY.find(normalized)?.groupValues?.get(1)
            ?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_MP4_ATTR.find(normalized)?.groupValues?.get(1)
            ?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        RE_MP4_ANY.find(normalized)?.groupValues?.get(1)
            ?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        return false
    }

    // ──────────────────────────────────────────────────────────────────────
    private suspend fun tryJsonBody(
        body: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!body.trimStart().startsWith("{")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }

        val candidates: List<String> = listOf(
            json.optString("url"), json.optString("source"), json.optString("src"), json.optString("file"),
            json.optString("video_url"), json.optString("videoUrl"),
            json.optString("stream_url"), json.optString("streamUrl"),
            json.optJSONObject("data")?.optString("url").orEmpty(),
            json.optJSONObject("data")?.optString("src").orEmpty(),
            json.optJSONObject("data")?.optString("file").orEmpty(),
            json.optJSONObject("result")?.optString("url").orEmpty()
        )

        val direct = candidates.firstOrNull { c -> c.isNotBlank() && (c.startsWith("http") || c.startsWith("//")) }
        if (!direct.isNullOrBlank()) {
            emitSingleUrl(normalize(direct), origin, headers, subtitleCallback, callback)
            return true
        }

        for (key in listOf("streams", "sources", "links", "videos", "mirrors")) {
            val arr = json.optJSONArray(key) ?: continue
            var any = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val u = obj.optString("url")
                    .ifBlank { obj.optString("file") }
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
        if (url.startsWith("//")) "https:$url" else url

    private suspend fun emitSingleUrl(
        url: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        if (url.contains(".m3u8")) emitHls(url, origin, headers, subtitleCallback, callback)
        else callback(newExtractorLink(name, name, url, INFER_TYPE) { this.referer = "$origin/" })
    }

    private suspend fun emitHls(
        m3u8: String, origin: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ) {
        M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
    }

    private fun looksLikeSpa(html: String): Boolean =
        html.contains("/assets/index-", ignoreCase = true) ||
        html.contains("/static/js/", ignoreCase = true)   ||
        html.contains("id=\"root\"", ignoreCase = true)   ||
        html.contains("id=\"app\"", ignoreCase = true)    ||
        html.contains("vite", ignoreCase = true)          ||
        html.contains("<script type=\"module\"", ignoreCase = true)
}

// ════════════════════════════════════════════════════════════════════════════
//  Byse  (Bysekoze / Bysejikuar)
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.e("Narul/Byse", "getUrl url=$url")
        val origin = originOf(url) ?: return
        val headers = mapOf("Origin" to origin, "Referer" to "$origin/")
        manualJsUnpackExtraction(url, name, headers, subtitleCallback, callback)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Abyss  (AbyssPlayer)
// ════════════════════════════════════════════════════════════════════════════
@Suppress("DEPRECATION")
class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Abyss"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val RE_DATAS = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
        private val DECODER_HEADERS = mapOf(
            "User-Agent"   to DEFAULT_UA,
            "Origin"       to "https://playhydrax.com",
            "Referer"      to "https://playhydrax.com/",
            "Content-Type" to "application/json",
        )
        private val SUBTITLE_ARRAY_KEYS = listOf("subtitles", "tracks", "captions", "subs")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.e(TAG, "=== Abyss getUrl === url=$url")
        val origin = originOf(url) ?: return
        val pageHeaders = mapOf("User-Agent" to DEFAULT_UA, "Origin" to origin, "Referer" to "$origin/")

        val doc = try { app.get(url, headers = pageHeaders).document } catch (e: Exception) {
            Log.e(TAG, "page fetch failed: ${e.message}")
            return
        }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            app.post("https://enc-dec.app/api/dec-abyss", headers = DECODER_HEADERS, requestBody = reqBody).text
        } catch (e: Exception) {
            Log.e(TAG, "enc-dec POST failed: ${e.message}")
            return
        }

        val result = JSONObject(responseText).optJSONObject("result") ?: return

        val seenSubs = HashSet<String>()
        for (key in SUBTITLE_ARRAY_KEYS) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("file").ifBlank { obj.optString("url") }.ifBlank { obj.optString("src") }.ifBlank { obj.optString("srcUrl") }
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = obj.optString("label").ifBlank { obj.optString("name") }.ifBlank { obj.optString("lang") }.ifBlank { obj.optString("language") }.ifBlank { "Sub" }
                subtitleCallback(newSubtitleFile(label, subUrl) {
                    this.headers = mapOf("Referer" to "$origin/", "Origin" to origin)
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
                    M3u8Helper.generateM3u8("$name HLS", stringUrl, origin).forEach(callback)
                    if (seenM3u8.add(stringUrl)) emitSubtitlesFromM3u8(stringUrl, pageHeaders, subtitleCallback)
                } else {
                    callback(newExtractorLink(name, name, stringUrl, INFER_TYPE) { this.referer = origin })
                }
                continue
            }

            if (!src.optBoolean("status", false) && !src.has("url")) continue
            val srcUrl = src.optString("url")
            if (srcUrl.isBlank() || !seenLinks.add(srcUrl)) continue

            val rawLabel = src.optString("label").ifBlank { src.optString("quality") }.ifBlank { src.optString("type") }.ifBlank { src.optString("res") }.ifBlank { src.optString("resolution") }

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
                    val finalQ = if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value) qualityInt else link.quality
                    val finalName = when {
                        finalQ == qualityInt && qualityInt != Qualities.Unknown.value -> "$name HLS ${qualityInt}p"
                        link.name == "$name HLS" && display.isNotBlank() -> "$name HLS $display"
                        else -> link.name
                    }
                    callback(ExtractorLink(name = link.name, source = finalName, url = link.url, referer = link.referer, quality = finalQ, type = link.type, headers = link.headers, extractorData = link.extractorData))
                }
                if (seenM3u8.add(srcUrl)) emitSubtitlesFromM3u8(srcUrl, pageHeaders, subtitleCallback)
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
