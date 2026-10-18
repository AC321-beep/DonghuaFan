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

// Detects error / challenge pages so we don't waste requests on them
private fun isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("404 not found")) return true
    if (lower.contains("403 forbidden")) return true
    if (lower.contains("503 service")) return true
    if (lower.contains("just a moment")) return true
    if (lower.contains("cf-browser-verification")) return true
    if (lower.contains("checking your browser")) return true
    if (lower.contains("cloudflare") && lower.contains("challenge")) return true
    // openresty/nginx bare 404 body
    if (html.length in 1..2500 &&
        lower.contains("<title>404") &&
        lower.contains("</html>")) return true
    return false
}

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
 * Reusable JS-unpacker helper with iframe/redirect/script-src fallbacks.
 */
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 3) return false

    Log.e("Narul/JS", "=== manualJsUnpackExtraction (depth=$depth) ===")
    Log.e("Narul/JS", "url=$url name=$name")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val responseObj = try {
        app.get(url, headers = safeHeaders)
    } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}")
        return false
    }
    Log.e("Narul/JS", "status=${responseObj.code}")
    if (responseObj.code == 404 || responseObj.code == 403 || responseObj.code == 410) {
        Log.e("Narul/JS", "HTTP ${responseObj.code} → abort")
        return false
    }

    val response = responseObj.text
    Log.e("Narul/JS", "response length=${response.length}")
    Log.e("Narul/JS", "preview: ${response.take(500)}")

    if (isErrorPage(response)) {
        Log.e("Narul/JS", "error/challenge page → abort")
        return false
    }

    val packedScript = RE_PACKED_SCRIPT.find(response)?.value
    Log.e("Narul/JS", "packed=${packedScript != null}")
    val unpacked = JsUnpacker(packedScript ?: response).unpack() ?: response
    val text = unpacked.replace("\\/", "/")
    Log.e("Narul/JS", "unpacked length=${text.length}")

    emitSubtitlesFromText(text, subtitleCallback)

    // ── Media in unpacked body ────────────────────────────────────────
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

    val origin = originOf(url)

    // ── JS redirect ───────────────────────────────────────────────────
    Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        .find(text)?.groupValues?.getOrNull(1)?.let { redirect ->
            if (redirect.startsWith("http") || redirect.startsWith("/") || redirect.startsWith("//")) {
                val fixed = resolveUrl(redirect, url, origin ?: return@let)
                Log.e("Narul/JS", "JS redirect → $fixed")
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
            }
        }

    // ── iframe wrapper ────────────────────────────────────────────────
    val iframeSrc = Jsoup.parse(text).selectFirst("iframe")?.let { el ->
        el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-litespeed-src") }
    }?.takeIf { it.isNotBlank() }

    if (iframeSrc != null) {
        val fixedIframe = resolveUrl(iframeSrc, url, origin ?: return false)
        Log.e("Narul/JS", "iframe wrapper → $fixedIframe")
        if (manualJsUnpackExtraction(fixedIframe, name, headers, subtitleCallback, callback, depth + 1)) return true
    }

    // ── follow <script src> ───────────────────────────────────────────
    val scriptSrcs = Jsoup.parse(text).select("script[src]")
        .map { it.attr("src") }
        .filter {
            it.isNotBlank() &&
            !it.contains("cloudflare", ignoreCase = true) &&
            !it.contains("google-analytics", ignoreCase = true) &&
            !it.contains("googletagmanager", ignoreCase = true)
        }
        .mapNotNull { resolveUrl(it, url, origin ?: return@mapNotNull null) }
        .distinct()

    for (src in scriptSrcs.take(3)) {
        Log.e("Narul/JS", "following script-src: $src")
        if (manualJsUnpackExtraction(src, name, headers, subtitleCallback, callback, depth + 1)) return true
    }

    Log.e("Narul/JS", "no media, iframe, redirect, or useful script in body")
    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  Plex  (P2pstream / narulplex)
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

    // ──────────────────────────────────────────────────────────────────
    // Strict SPA detector — 404/challenge pages are NOT SPAs
    // ──────────────────────────────────────────────────────────────────
    private fun looksLikeSpa(html: String): Boolean {
        if (html.length < 300) return false
        if (isErrorPage(html)) return false

        val hasRoot = html.contains("id=\"root\"") || html.contains("id=\"app\"")
        val hasVite = html.contains("/assets/index-") || html.contains("/assets/main-")

        // A <script src="*.js"> that is NOT cloudflare insights is a strong SPA signal
        val hasRealBundle = RE_SCRIPT_SRC.findAll(html).any { m ->
            val src = m.groupValues[1]
            src.endsWith(".js", true) &&
                !src.contains("cloudflareinsights", ignoreCase = true) &&
                !src.contains("googletagmanager", ignoreCase = true) &&
                !src.contains("google-analytics", ignoreCase = true)
        }

        return hasRoot || hasVite || hasRealBundle
    }

    // ──────────────────────────────────────────────────────────────────
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
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        // ── Fast path ─────────────────────────────────────────────────
        workingEndpointCache[origin]?.let { ep ->
            Log.e(TAG, "FAST-PATH: cached endpoint=$ep")
            if (probeEndpoint(origin, ep, id, headers, subtitleCallback, callback)) return
            workingEndpointCache.remove(origin)
        }

        // ── Try every plausible entry path ────────────────────────────
        // Previous code only tried /e/{id}, which returns 404 on narulplex
        val entryPaths = listOf(
            "/e/$id", "/v/$id", "/$id", "/embed/$id", "/watch/$id",
            "/?v=$id", "/?id=$id", "/?hash=$id", "/?video=$id", "/?h=$id",
        )

        var spaShell: String? = null
        var spaShellUrl: String? = null

        for (path in entryPaths) {
            val fullUrl = "$origin$path"
            Log.e(TAG, "→ try $fullUrl")

            val resp = try { app.get(fullUrl, headers = headers) } catch (e: Exception) {
                Log.e(TAG, "  fetch fail: ${e.message}"); continue
            }
            val body = resp.text
            Log.e(TAG, "  status=${resp.code} len=${body.length}")

            if (resp.code == 404 || resp.code == 403 || resp.code == 410) {
                Log.e(TAG, "  skip: HTTP ${resp.code}"); continue
            }
            if (isErrorPage(body)) {
                Log.e(TAG, "  skip: error/challenge page"); continue
            }

            // Packed JS?
            if (RE_PACKED_SCRIPT.containsMatchIn(body)) {
                Log.e(TAG, "  packed JS found → extracting")
                if (manualJsUnpackExtraction(fullUrl, name, headers, subtitleCallback, callback)) return
            }

            // Direct media?
            if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return

            // Save a good SPA shell
            if (spaShell == null && looksLikeSpa(body)) {
                spaShell = body
                spaShellUrl = fullUrl
                Log.e(TAG, "  ✓ valid SPA shell → saved")
            }
        }

        // ── SPA API probing ───────────────────────────────────────────
        if (spaShell != null) {
            Log.e(TAG, "Probing SPA API (shell from $spaShellUrl)")
            trySpaApi(origin, id, spaShell, headers, subtitleCallback, callback)
            return
        }

        // ── Last resort: root ─────────────────────────────────────────
        Log.e(TAG, "No shell yet; fetching root")
        val rootResp = try { app.get("$origin/", headers = headers) } catch (_: Exception) { null }
        if (rootResp != null && rootResp.code != 404 && rootResp.code != 403) {
            val body = rootResp.text
            Log.e(TAG, "  root status=${rootResp.code} len=${body.length}")
            if (looksLikeSpa(body)) {
                Log.e(TAG, "Root is SPA shell → probing API")
                trySpaApi(origin, id, body, headers, subtitleCallback, callback)
                return
            }
        }

        Log.e(TAG, "✗ All entry paths failed (domain unreachable, dead, or geo-blocked)")
    }

    // ──────────────────────────────────────────────────────────────────
    private suspend fun trySpaApi(
        origin: String,
        id: String,
        shellHtml: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val jsUrl = findMainJsUrl(shellHtml, origin)
        if (jsUrl == null) {
            Log.e(TAG, "  no JS bundle in shell → skip API discovery")
            // still try default endpoints directly
        } else {
            Log.e(TAG, "jsUrl=$jsUrl")
        }

        val fromJs = buildList<String> {
            if (jsUrl != null) {
                val js = scriptCache[origin] ?: try {
                    val r = app.get(jsUrl, headers = headers)
                    if (r.code == 200 && !isErrorPage(r.text)) {
                        r.text.also { scriptCache[origin] = it }
                    } else null
                } catch (_: Exception) { null }

                if (js != null) {
                    Log.e(TAG, "JS length=${js.length}")
                    RE_API_PATH.findAll(js).map { it.groupValues[1] }.distinct().forEach { path ->
                        if (listOf("video", "source", "stream", "get", "embed", "player")
                                .any { path.contains(it, true) }) add(path)
                    }
                    RE_API_URL.findAll(js).map { it.groupValues[1] }.distinct().forEach { fullUrl ->
                        if (listOf("video", "source", "stream", "get", "embed")
                                .any { fullUrl.contains(it, true) }) add(fullUrl)
                    }
                }
            }
        }
        Log.e(TAG, "JS-discovered endpoints: $fromJs")

        val candidates = (fromJs + DEFAULT_ENDPOINTS).distinct().take(10)
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

    // ──────────────────────────────────────────────────────────────────
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
        val jsonHeaders = headers + ("Accept" to "application/json, text/plain, */*")

        // Query-param variants
        for (param in listOf("id", "hash", "v", "vid")) {
            val u = if (baseUrl.contains('?')) "$baseUrl&$param=$id" else "$baseUrl?$param=$id"
            if (tryFetchJson(u, origin, jsonHeaders, subtitleCallback, callback)) return true
        }

        // Path-param variants
        val pathBase = baseUrl.substringBefore('?').trimEnd('/')
        for (suffix in listOf("/$id", "/hash/$id", "/id/$id")) {
            if (tryFetchJson("$pathBase$suffix", origin, jsonHeaders, subtitleCallback, callback)) return true
        }

        // POST variants
        for (payload in listOf(
            """{"id":"$id"}""", """{"hash":"$id"}""", """{"v":"$id"}"""
        )) {
            Log.e(TAG, "  POST $baseUrl body=$payload")
            try {
                val body = app.post(
                    baseUrl,
                    headers = jsonHeaders + ("Content-Type" to "application/json; charset=utf-8"),
                    requestBody = payload.toRequestBody(JSON_TYPE),
                ).text
                if (body.isBlank()) continue
                if (isErrorPage(body)) continue
                if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return true
                if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) return true
            } catch (_: Exception) { }
        }

        return false
    }

    private suspend fun tryFetchJson(
        url: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.e(TAG, "  GET $url")
        try {
            val resp = app.get(url, headers = headers)
            if (resp.code == 404 || resp.code == 403 || resp.code == 410) return false
            val body = resp.text
            if (body.isBlank()) return false
            if (isErrorPage(body)) return false
            if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) return true
            if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) return true
        } catch (_: Exception) { }
        return false
    }

    // ──────────────────────────────────────────────────────────────────
    private fun findMainJsUrl(shellHtml: String, origin: String): String? {
        val srcs = RE_SCRIPT_SRC.findAll(shellHtml).map { it.groupValues[1] }.toList()
        Log.e(TAG, "  script srcs: $srcs")

        fun absolutize(u: String): String = when {
            u.startsWith("http") -> u
            u.startsWith("//")   -> "https:$u"
            u.startsWith("/")    -> "$origin$u"
            else                 -> "$origin/$u"
        }

        val good = srcs.filter {
            !it.contains("cloudflareinsights", ignoreCase = true) &&
            !it.contains("googletagmanager", ignoreCase = true) &&
            !it.contains("google-analytics", ignoreCase = true)
        }

        good.firstOrNull { it.matches(Regex(".*/assets/(index|main)-[^/]+\\.js.*")) }
            ?.let { return absolutize(it) }
        good.firstOrNull { it.matches(Regex(".*/static/js/[^/]+\\.js.*")) }
            ?.let { return absolutize(it) }
        good.firstOrNull { it.contains("/assets/") && it.contains(".js") }
            ?.let { return absolutize(it) }
        good.firstOrNull { !it.startsWith("http") && !it.startsWith("//") && it.contains(".js") }
            ?.let { return absolutize(it) }
        return null
    }

    // ──────────────────────────────────────────────────────────────────
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

    // ──────────────────────────────────────────────────────────────────
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
        val headers = mapOf(
            "Origin"  to origin,
            "Referer" to "$origin/",
            "User-Agent" to DEFAULT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )

        if (manualJsUnpackExtraction(url, name, headers, subtitleCallback, callback)) return

        // Fallback — try the /embed/{hash} form
        val hash = url.substringAfter("/d/").substringBefore("/").takeIf { it.isNotBlank() }
        if (hash != null) {
            val alt = "$origin/embed/$hash"
            Log.e("Narul/Byse", "fallback → $alt")
            manualJsUnpackExtraction(alt, name, headers, subtitleCallback, callback)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Abyss  (AbyssPlayer)  — unchanged, works
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
