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

private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    Log.e("Narul/JS", "=== manualJsUnpackExtraction ===")
    Log.e("Narul/JS", "url=$url name=$name")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val response = try {
        app.get(url, headers = safeHeaders).text
    } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}")
        return
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
        return
    }

    val mp4 = RE_MP4_ATTR.find(text)?.groupValues?.get(1)
        ?: RE_MP4_ANY.find(text)?.groupValues?.get(1)
    if (mp4 != null) {
        Log.e("Narul/JS", "mp4=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) {
            this.referer = url
        })
        return
    }
    Log.e("Narul/JS", "no media found in unpacked body")
}

// ════════════════════════════════════════════════════════════════════════════
//  Plex  (P2pstream — classic packed-JS AND React/Vite SPA variants)
//
//  Classic:  https://p2pstream.vip/#{hash}         → /e/{hash}  → packed JS
//  SPA:      https://narulplex.p2pstream.vip/#xyz  → Vite shell → REST API
//
//  All decisions emit Log.e under tag "Narul/Plex".
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
        )

        private val RE_API_PATH = Regex("""["'](/api/[a-zA-Z0-9_\-/]{2,60})["']""")
        private val RE_API_URL = Regex("""["'](https?://api\.[^"']+?/[a-zA-Z0-9_\-/]{2,60})["']""")
        private val RE_JS_BUNDLE = Regex("""src=["']([^"']*?/assets/index-[^"']+\.js)["']""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.e(TAG, "════════════════════════════════════")
        Log.e(TAG, "=== Plex getUrl START ===")
        Log.e(TAG, "url=$url referer=$referer")

        val origin = originOf(url) ?: run {
            Log.e(TAG, "origin=null → abort")
            return
        }
        Log.e(TAG, "origin=$origin")

        val id = url.substringAfter("#", "").substringBefore("&").trim()
        Log.e(TAG, "extracted id='$id'")
        if (id.isBlank()) {
            Log.e(TAG, "id blank → abort")
            return
        }

        val headers = mapOf(
            "Origin"     to origin,
            "Referer"    to "$origin/",
            "User-Agent" to DEFAULT_UA,
        )

        // ── Fast path: previously learned endpoint ──────────────────────
        workingEndpointCache[origin]?.let { ep ->
            Log.e(TAG, "FAST-PATH: cached endpoint=$ep")
            if (probeEndpoint(origin, ep, id, headers, subtitleCallback, callback)) {
                Log.e(TAG, "FAST-PATH SUCCESS")
                return
            }
            Log.e(TAG, "FAST-PATH failed, falling through")
        } ?: Log.e(TAG, "no cached endpoint for $origin")

        // ── Classic /e/{id} ─────────────────────────────────────────────
        val classicUrl = "$origin/e/$id"
        Log.e(TAG, "classic fetch: $classicUrl")
        val classicBody = try {
            app.get(classicUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "classic fetch FAILED: ${e.message}")
            null
        }
        Log.e(TAG, "classic response length=${classicBody?.length ?: 0}")
        if (classicBody != null && classicBody.length < 800) {
            Log.e(TAG, "classic body preview: ${classicBody.take(400)}")
        }

        if (classicBody != null) {
            if (RE_PACKED_SCRIPT.containsMatchIn(classicBody)) {
                Log.e(TAG, "DETECTED: classic packed JS")
                manualJsUnpackExtraction(classicUrl, name, headers, subtitleCallback, callback)
                return
            }
            Log.e(TAG, "not packed, trying direct m3u8/mp4 scan")
            if (tryDirectMedia(classicBody, origin, headers, subtitleCallback, callback)) {
                Log.e(TAG, "direct media SUCCESS on classic body")
                return
            }
            Log.e(TAG, "direct media: no match on classic body")
        }

        // ── SPA ─────────────────────────────────────────────────────────
        val html = classicBody ?: try {
            Log.e(TAG, "classic null, fetching origin root")
            app.get("$origin/", headers = headers).text
        } catch (e: Exception) {
            Log.e(TAG, "origin fetch FAILED: ${e.message}")
            null
        } ?: run {
            Log.e(TAG, "no HTML available → abort")
            return
        }

        val isSpa = looksLikeSpa(html)
        Log.e(TAG, "looksLikeSpa=$isSpa (htmlLen=${html.length})")
        if (!isSpa) {
            Log.e(TAG, "unknown shape → abort. First 300 chars: ${html.take(300)}")
            return
        }

        Log.e(TAG, "SPA detected → probing API")
        trySpaApi(origin, id, html, headers, subtitleCallback, callback)
    }

    private suspend fun trySpaApi(
        origin: String,
        id: String,
        shellHtml: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val jsPath = RE_JS_BUNDLE.find(shellHtml)?.groupValues?.get(1)
        Log.e(TAG, "SPA js path from shell: $jsPath")
        val jsUrl = jsPath?.let {
            if (it.startsWith("http")) it
            else if (it.startsWith("/")) "$origin$it"
            else "$origin/$it"
        } ?: "$origin/assets/index.js"
        Log.e(TAG, "SPA js URL: $jsUrl")

        val js = scriptCache[origin] ?: try {
            Log.e(TAG, "fetching JS bundle (${jsUrl.length} chars in path)")
            app.get(jsUrl, headers = headers).text.also {
                Log.e(TAG, "JS bundle fetched: ${it.length} bytes")
                scriptCache[origin] = it
            }
        } catch (e: Exception) {
            Log.e(TAG, "JS bundle fetch FAILED: ${e.message}")
            null
        } ?: run {
            Log.e(TAG, "no JS bundle → abort")
            return
        }

        val fromJs = buildList<String> {
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
        Log.e(TAG, "endpoints from JS (${fromJs.size}): $fromJs")

        val candidates = (fromJs + DEFAULT_ENDPOINTS).distinct().take(6)
        Log.e(TAG, "probing candidates: $candidates")

        for (ep in candidates) {
            Log.e(TAG, "──────── probing $ep ────────")
            if (probeEndpoint(origin, ep, id, headers, subtitleCallback, callback)) {
                workingEndpointCache[origin] = ep
                Log.e(TAG, "SUCCESS with endpoint=$ep (cached)")
                return
            }
        }
        Log.e(TAG, "ALL endpoints failed")
    }

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

        for (param in listOf("id", "hash", "v", "vid")) {
            val u = if (baseUrl.contains('?')) "$baseUrl&$param=$id"
                    else                    "$baseUrl?$param=$id"
            try {
                Log.e(TAG, "  GET $u")
                val body = app.get(u, headers = headers).text
                Log.e(TAG, "  → ${body.length} bytes, first40='${body.take(40).replace("\n", " ")}'")

                if (body.isBlank()) {
                    Log.e(TAG, "  → empty, next")
                    continue
                }
                if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) {
                    Log.e(TAG, "  → direct media MATCH ($param)")
                    return true
                }
                if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) {
                    Log.e(TAG, "  → JSON parse MATCH ($param)")
                    return true
                }
                Log.e(TAG, "  → no match ($param)")
            } catch (e: Exception) {
                Log.e(TAG, "  → exception: ${e.message}")
            }
        }
        return false
    }

    private suspend fun tryDirectMedia(
        text: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (text.length > 500_000) return false
        val normalized = text.replace("\\/", "/")

        RE_M3U8_ATTR.find(normalized)?.groupValues?.get(1)?.let { m3u8 ->
            Log.e(TAG, "    direct m3u8 (attr)=$m3u8")
            emitHls(m3u8, origin, headers, subtitleCallback, callback)
            return true
        }
        RE_M3U8_ANY.find(normalized)?.groupValues?.get(1)?.let { m3u8 ->
            Log.e(TAG, "    direct m3u8 (any)=$m3u8")
            emitHls(m3u8, origin, headers, subtitleCallback, callback)
            return true
        }
        RE_MP4_ATTR.find(normalized)?.groupValues?.get(1)?.let { mp4 ->
            Log.e(TAG, "    direct mp4 (attr)=$mp4")
            callback(newExtractorLink(name, name, mp4, INFER_TYPE) {
                this.referer = "$origin/"
            })
            return true
        }
        RE_MP4_ANY.find(normalized)?.groupValues?.get(1)?.let { mp4 ->
            Log.e(TAG, "    direct mp4 (any)=$mp4")
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
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) {
            Log.e(TAG, "    tryJsonBody: not JSON (first='${trimmed.take(5).replace("\n", " ")}')")
            return false
        }
        val json = try { JSONObject(body) } catch (e: Exception) {
            Log.e(TAG, "    JSONObject parse failed: ${e.message}")
            return false
        }

        Log.e(TAG, "    JSON keys: ${json.keys().asSequence().toList()}")

        val candidates: List<String> = listOf(
            json.optString("url"),
            json.optString("source"),
            json.optString("src"),
            json.optString("file"),
            json.optString("video_url"),
            json.optString("videoUrl"),
            json.optString("stream_url"),
            json.optString("streamUrl"),
            json.optJSONObject("data")?.optString("url").orEmpty(),
            json.optJSONObject("data")?.optString("src").orEmpty(),
            json.optJSONObject("data")?.optString("file").orEmpty(),
            json.optJSONObject("result")?.optString("url").orEmpty(),
        )
        Log.e(TAG, "    top-level candidates: ${candidates.filter { it.isNotBlank() }}")

        val direct: String? = candidates.firstOrNull { c ->
            c.isNotBlank() && (c.startsWith("http") || c.startsWith("//"))
        }

        if (!direct.isNullOrBlank()) {
            Log.e(TAG, "    → direct from JSON: $direct")
            emitSingleUrl(normalize(direct), origin, headers, subtitleCallback, callback)
            return true
        }

        for (key in listOf("streams", "sources", "links", "videos", "mirrors")) {
            val arr = json.optJSONArray(key) ?: continue
            Log.e(TAG, "    array '$key' size=${arr.length()}")
            var any = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val u = obj.optString("url").ifBlank { obj.optString("file") }
                    .ifBlank { obj.optString("src") }
                    .ifBlank { obj.optString("link") }
                if (u.isBlank()) continue
                Log.e(TAG, "      [$key][$i] url=$u")
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
        url: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.contains(".m3u8")) emitHls(url, origin, headers, subtitleCallback, callback)
        else callback(newExtractorLink(name, name, url, INFER_TYPE) { this.referer = "$origin/" })
    }

    private suspend fun emitHls(
        m3u8: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.e(TAG, "EMITTING HLS: $m3u8")
        M3u8Helper.generateM3u8(name, m3u8, "$origin/", headers = headers).forEach(callback)
        emitSubtitlesFromM3u8(m3u8, headers, subtitleCallback)
    }

    private fun looksLikeSpa(html: String): Boolean =
        html.contains("/assets/index-", ignoreCase = true) ||
            html.contains("id=\"root\"", ignoreCase = true) ||
            html.contains("id=\"app\"", ignoreCase = true) ||
            html.contains("vite", ignoreCase = true)
}

// ════════════════════════════════════════════════════════════════════════════
//  Byse  (Bysekoze / Bysejikuar — FM mirror)
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
//  Abyss  (AbyssPlayer — enc-dec.app decoder)
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
        val pageHeaders = mapOf(
            "User-Agent" to DEFAULT_UA,
            "Origin"     to origin,
            "Referer"    to "$origin/",
        )

        val doc = try { app.get(url, headers = pageHeaders).document } catch (e: Exception) {
            Log.e(TAG, "page fetch failed: ${e.message}")
            return
        }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: run {
            Log.e(TAG, "no 'const datas = ...' found (${scriptData.length} chars of scripts)")
            return
        }
        Log.e(TAG, "encrypted length=${encrypted.length}")

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            app.post("https://enc-dec.app/api/dec-abyss", headers = DECODER_HEADERS, requestBody = reqBody).text
        } catch (e: Exception) {
            Log.e(TAG, "enc-dec POST failed: ${e.message}")
            return
        }
        Log.e(TAG, "decoder response length=${responseText.length}")

        val result = JSONObject(responseText).optJSONObject("result") ?: run {
            Log.e(TAG, "no 'result' in decoder response: ${responseText.take(200)}")
            return
        }

        val seenSubs = HashSet<String>()
        for (key in SUBTITLE_ARRAY_KEYS) {
            val arr = result.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("file").ifBlank { obj.optString("url") }
                    .ifBlank { obj.optString("src") }.ifBlank { obj.optString("srcUrl") }
                if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                val label = obj.optString("label").ifBlank { obj.optString("name") }
                    .ifBlank { obj.optString("lang") }.ifBlank { obj.optString("language") }.ifBlank { "Sub" }
                Log.e(TAG, "subtitle [$key] $label=$subUrl")
                subtitleCallback(newSubtitleFile(label, subUrl) {
                    this.headers = mapOf("Referer" to "$origin/", "Origin" to origin)
                })
            }
        }

        val sources = result.optJSONArray("sources") ?: run {
            Log.e(TAG, "no 'sources' array")
            return
        }
        Log.e(TAG, "sources count=${sources.length()}")
        val seenLinks = HashSet<String>(sources.length())
        val seenM3u8 = HashSet<String>()

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)
            if (src == null) {
                val stringUrl = sources.optString(i)
                if (stringUrl.isBlank() || !seenLinks.add(stringUrl)) continue
                Log.e(TAG, "  source[$i] (string): $stringUrl")
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

            val rawLabel = src.optString("label").ifBlank { src.optString("quality") }
                .ifBlank { src.optString("type") }.ifBlank { src.optString("res") }
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

            Log.e(TAG, "  source[$i] label='$rawLabel' q=$qualityInt url=$srcUrl")

            if (srcUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8("$name HLS", srcUrl, origin).forEach { link ->
                    val finalQ = if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value)
                        qualityInt else link.quality
                    val finalName = when {
                        finalQ == qualityInt && qualityInt != Qualities.Unknown.value -> "$name HLS ${qualityInt}p"
                        link.name == "$name HLS" && display.isNotBlank() -> "$name HLS $display"
                        else -> link.name
                    }
                    callback(ExtractorLink(
                        name = link.name, source = finalName, url = link.url,
                        referer = link.referer, quality = finalQ, type = link.type,
                        headers = link.headers, extractorData = link.extractorData,
                    ))
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
