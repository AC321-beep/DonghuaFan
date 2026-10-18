package com.naruldonghua

import android.util.Base64
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
import org.json.JSONArray
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

/** Detects error / challenge pages so we don't waste requests on them */
private fun isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("404 not found")) return true
    if (lower.contains("403 forbidden")) return true
    if (lower.contains("503 service")) return true
    if (lower.contains("just a moment")) return true
    if (lower.contains("cf-browser-verification")) return true
    if (lower.contains("checking your browser")) return true
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

/** Recursively walks any JSON structure looking for media URLs. */
private fun extractUrlsFromJson(json: JSONObject, depth: Int = 0): List<String> {
    if (depth > 5) return emptyList()
    val urls = mutableListOf<String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val value = json.opt(key)) {
            is String -> {
                val v = value.replace("\\/", "/")
                if ((v.startsWith("http") || v.startsWith("//")) &&
                    (v.contains(".m3u8") || v.contains(".mp4") || v.contains(".mkv") ||
                     v.contains("/manifest") || v.contains("/hls/") || v.contains("/stream/"))
                ) urls.add(v)
            }
            is JSONObject -> urls.addAll(extractUrlsFromJson(value, depth + 1))
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    when (val item = value.opt(i)) {
                        is String -> {
                            val v = item.replace("\\/", "/")
                            if (v.startsWith("http") || v.startsWith("//")) urls.add(v)
                        }
                        is JSONObject -> urls.addAll(extractUrlsFromJson(item, depth + 1))
                        else -> {}
                    }
                }
            }
        }
    }
    return urls
}

/** Reusable JS-unpacker helper with iframe/redirect/script-src fallbacks. */
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    depth: Int = 0,
): Boolean {
    if (depth > 3) return false

    Log.e("Narul/JS", "=== manualJsUnpackExtraction (depth=$depth) url=$url")

    val safeHeaders = if (headers.containsKey("User-Agent")) headers
        else headers + ("User-Agent" to DEFAULT_UA)

    val responseObj = try { app.get(url, headers = safeHeaders) } catch (e: Exception) {
        Log.e("Narul/JS", "fetch FAILED: ${e.message}"); return false
    }
    Log.e("Narul/JS", "status=${responseObj.code}")
    if (responseObj.code == 404 || responseObj.code == 403 || responseObj.code == 410) return false

    val response = responseObj.text
    Log.e("Narul/JS", "response length=${response.length}")
    Log.e("Narul/JS", "preview: ${response.take(300).replace("\n", "\\n")}")

    if (isErrorPage(response)) { Log.e("Narul/JS", "error/challenge page → abort"); return false }

    val packedScript = RE_PACKED_SCRIPT.find(response)?.value
    Log.e("Narul/JS", "packed=${packedScript != null}")
    val unpacked = JsUnpacker(packedScript ?: response).unpack() ?: response
    val text = unpacked.replace("\\/", "/")

    emitSubtitlesFromText(text, subtitleCallback)

    RE_M3U8_ATTR.find(text)?.groupValues?.get(1)?.let { m3u8 ->
        val normalized = m3u8.replace("\\/", "/")
        Log.e("Narul/JS", "m3u8=$normalized")
        M3u8Helper.generateM3u8(name, normalized, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(normalized, safeHeaders, subtitleCallback)
        return true
    }
    RE_M3U8_ANY.find(text)?.groupValues?.get(1)?.let { m3u8 ->
        val normalized = m3u8.replace("\\/", "/")
        Log.e("Narul/JS", "m3u8(any)=$normalized")
        M3u8Helper.generateM3u8(name, normalized, url, headers = safeHeaders).forEach(callback)
        emitSubtitlesFromM3u8(normalized, safeHeaders, subtitleCallback)
        return true
    }
    RE_MP4_ATTR.find(text)?.groupValues?.get(1)?.let { mp4 ->
        Log.e("Narul/JS", "mp4=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) { this.referer = url })
        return true
    }
    RE_MP4_ANY.find(text)?.groupValues?.get(1)?.let { mp4 ->
        Log.e("Narul/JS", "mp4(any)=$mp4")
        callback(newExtractorLink(name, name, mp4.replace("\\/", "/"), INFER_TYPE) { this.referer = url })
        return true
    }

    val origin = originOf(url)

    Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        .find(text)?.groupValues?.getOrNull(1)?.let { redirect ->
            if (redirect.startsWith("http") || redirect.startsWith("/") || redirect.startsWith("//")) {
                val fixed = resolveUrl(redirect, url, origin ?: return@let)
                Log.e("Narul/JS", "JS redirect → $fixed")
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback, depth + 1)) return true
            }
        }

    val iframeSrc = Jsoup.parse(text).selectFirst("iframe")?.let { el ->
        el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-litespeed-src") }
    }?.takeIf { it.isNotBlank() }
    if (iframeSrc != null) {
        val fixedIframe = resolveUrl(iframeSrc, url, origin ?: return false)
        Log.e("Narul/JS", "iframe → $fixedIframe")
        if (manualJsUnpackExtraction(fixedIframe, name, headers, subtitleCallback, callback, depth + 1)) return true
    }

    val scriptSrcs = Jsoup.parse(text).select("script[src]")
        .map { it.attr("src") }
        .filter {
            it.isNotBlank() &&
            !it.contains("cloudflare", ignoreCase = true) &&
            !it.contains("google-analytics", ignoreCase = true) &&
            !it.contains("googletagmanager", ignoreCase = true) &&
            !it.contains("yandex", ignoreCase = true)
        }
        .mapNotNull { resolveUrl(it, url, origin ?: return@mapNotNull null) }
        .distinct()
    for (src in scriptSrcs.take(3)) {
        Log.e("Narul/JS", "following script-src: $src")
        if (manualJsUnpackExtraction(src, name, headers, subtitleCallback, callback, depth + 1)) return true
    }

    Log.e("Narul/JS", "no media / iframe / redirect / useful script in body")
    return false
}

// ════════════════════════════════════════════════════════════════════════════
//  Plex  (P2pstream / narulplex) — SPA + encrypted payload
// ════════════════════════════════════════════════════════════════════════════
class P2pstream : ExtractorApi() {
    override val name = "Plex"
    override val mainUrl = "https://p2pstream.vip"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Plex"
        private val scriptCache = ConcurrentHashMap<String, String>()
        private val workingEndpointCache = ConcurrentHashMap<String, String>()
        private val workingEntryCache = ConcurrentHashMap<String, String>()

        // Priority 1 — proven in logcat: /api/v1/video?id=f1u519 → 200 + encrypted payload
        private val KNOWN_ENDPOINTS = listOf(
            "/api/v1/video?id={id}",
            "/api/v1/video?hash={id}",
            "/api/video?id={id}",
            "/api/v1/source?id={id}",
            "/api/source?id={id}",
        )

        private val ENTRY_TEMPLATES = listOf(
            "/?v={id}", "/?id={id}", "/{id}",
        )

        // enc-dec.app decoder endpoints — try all, skip 404s silently
        private val DECODERS = listOf(
            "dec-vidsrc", "dec-streamtape", "dec-vidlink", "dec-rapid",
            "dec-vidstack", "dec-abyss", "dec-mega", "dec-voe",
            "dec-p2pstream", "dec-plex",
        )

        private val RE_SCRIPT_SRC = Regex(
            """<script[^>]+src=["']([^"']+\.js[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        private val RE_API_PATH = Regex(
            """["'`](/(?:api|v1|v2|ajax|embed|source|video|link|stream|resolve|fetch|play)/[a-zA-Z0-9_\-/.]{2,100})["'`]""",
            RegexOption.IGNORE_CASE
        )
        private val RE_TEMPLATE_PATH = Regex(
            """["'`](/[a-zA-Z0-9_\-./]{2,80}?)/?\$\{[^}]+\}"""
        )
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private val RE_HEX    = Regex("^[0-9a-fA-F]{100,}$")
        private val RE_BASE64 = Regex("^[A-Za-z0-9+/=_-]{200,}$")
    }

    // ────────────────────────────────────────────────────────────────────
    private enum class ProbeResult { FOUND, PAYLOAD_UNKNOWN, NOT_FOUND, RATE_LIMITED, ERROR }

    // ────────────────────────────────────────────────────────────────────
    private fun looksLikeSpa(html: String): Boolean {
        if (html.length < 300) return false
        if (isErrorPage(html)) return false

        val hasRoot = html.contains("id=\"root\"") || html.contains("id=\"app\"")
        val hasVite = html.contains("/assets/index-") || html.contains("/assets/main-")
        val hasBundle = RE_SCRIPT_SRC.findAll(html).any { m ->
            val s = m.groupValues[1]
            s.endsWith(".js", true) &&
                !s.contains("cloudflareinsights", ignoreCase = true) &&
                !s.contains("googletagmanager", ignoreCase = true) &&
                !s.contains("google-analytics", ignoreCase = true) &&
                !s.contains("yandex", ignoreCase = true)
        }
        return hasRoot || hasVite || hasBundle
    }

    // ────────────────────────────────────────────────────────────────────
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
            "Origin"          to origin,
            "Referer"         to "$origin/",
            "User-Agent"      to DEFAULT_UA,
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        // ── Fast path: cached endpoint ───────────────────────────────
        workingEndpointCache[origin]?.let { cached ->
            Log.e(TAG, "FAST-PATH $cached")
            when (probeEndpoint(origin, cached, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND, ProbeResult.PAYLOAD_UNKNOWN -> return
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "⚠ rate-limited on fast path"); return }
                else -> workingEndpointCache.remove(origin)
            }
        }

        // ── Priority 1: known working endpoints ──────────────────────
        for (template in KNOWN_ENDPOINTS) {
            when (probeEndpoint(origin, template, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = template; return }
                ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = template; return }
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "⚠ rate-limited — aborting"); return }
                else -> { /* next */ }
            }
        }

        // ── Priority 2: SPA shell discovery ──────────────────────────
        Log.e(TAG, "Known endpoints exhausted; fetching SPA shell")
        val cachedEntry = workingEntryCache[origin]
        val entries = if (cachedEntry != null) listOf(cachedEntry) + ENTRY_TEMPLATES else ENTRY_TEMPLATES

        var shell: String? = null
        for (tmpl in entries.distinct()) {
            val path = tmpl.replace("{id}", id)
            val fullUrl = "$origin$path"
            Log.e(TAG, "→ shell try $fullUrl")

            val resp = try { app.get(fullUrl, headers = headers) } catch (e: Exception) {
                Log.e(TAG, "  fail: ${e.message}"); continue
            }
            if (resp.code == 429) { Log.e(TAG, "  ⚠ rate-limited"); return }
            if (resp.code != 200) { Log.e(TAG, "  skip HTTP ${resp.code}"); continue }
            if (isErrorPage(resp.text)) { Log.e(TAG, "  skip error"); continue }
            if (!looksLikeSpa(resp.text)) { Log.e(TAG, "  not SPA"); continue }

            shell = resp.text
            workingEntryCache[origin] = tmpl
            Log.e(TAG, "  ✓ SPA shell ($fullUrl)")
            break
        }

        if (shell == null) {
            Log.e(TAG, "✗ No SPA shell — site unreachable or blocked")
            return
        }

        // ── Discover extra endpoints from JS bundle ──────────────────
        val jsUrl = findMainJsUrl(shell, origin)
        Log.e(TAG, "jsUrl=$jsUrl")

        val fromJs = if (jsUrl != null) {
            val js = scriptCache[origin] ?: try {
                val r = app.get(jsUrl, headers = headers)
                if (r.code == 200 && !isErrorPage(r.text)) r.text.also { scriptCache[origin] = it }
                else null
            } catch (_: Exception) { null }

            if (js != null) {
                Log.e(TAG, "JS length=${js.length}")
                dumpInterestingJs(js)
                discoverEndpointsFromJs(js)
            } else emptyList()
        } else emptyList()

        Log.e(TAG, "JS-discovered endpoints (${fromJs.size}): $fromJs")

        val candidates = (fromJs + KNOWN_ENDPOINTS).distinct().take(10)
        Log.e(TAG, "Probing ${candidates.size} templates")

        for (template in candidates) {
            when (probeEndpoint(origin, template, id, headers, subtitleCallback, callback)) {
                ProbeResult.FOUND -> { workingEndpointCache[origin] = template; return }
                ProbeResult.PAYLOAD_UNKNOWN -> { workingEndpointCache[origin] = template; return }
                ProbeResult.RATE_LIMITED -> { Log.e(TAG, "⚠ rate-limited — stopping"); return }
                else -> { /* next */ }
            }
        }

        Log.e(TAG, "✗ All attempts exhausted")
    }

    // ────────────────────────────────────────────────────────────────────
    private suspend fun probeEndpoint(
        origin: String,
        template: String,
        id: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): ProbeResult {
        val path = template.replace("{id}", id)
        val url = when {
            path.startsWith("http") -> path
            path.startsWith("/")    -> "$origin$path"
            else                    -> "$origin/$path"
        }

        Log.e(TAG, "→ GET $url")

        val resp = try {
            app.get(url, headers = headers + ("Accept" to "application/json, */*"))
        } catch (e: Exception) {
            Log.e(TAG, "  fail: ${e.message}")
            return ProbeResult.ERROR
        }

        val body = resp.text
        val preview = body.take(200).replace("\n", "\\n")
        Log.e(TAG, "  ${resp.code} len=${body.length} preview=$preview")

        when (resp.code) {
            429 -> { Log.e(TAG, "  ⚠ rate limited"); return ProbeResult.RATE_LIMITED }
            404, 403, 410 -> { Log.e(TAG, "  not found"); return ProbeResult.NOT_FOUND }
        }
        if (resp.code !in 200..299) return ProbeResult.NOT_FOUND
        if (isErrorPage(body)) return ProbeResult.NOT_FOUND

        // 1. Direct media URL anywhere in body
        if (tryDirectMedia(body, origin, headers, subtitleCallback, callback)) {
            Log.e(TAG, "  ✓ direct media")
            return ProbeResult.FOUND
        }

        // 2. JSON (nested or flat)
        if (tryJsonBody(body, origin, headers, subtitleCallback, callback)) {
            Log.e(TAG, "  ✓ JSON media")
            return ProbeResult.FOUND
        }

        // 3. Iframe wrapper
        if (body.contains("<iframe", ignoreCase = true)) {
            val iframeSrc = Jsoup.parse(body)
                .selectFirst("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
                ?.let { it.attr("data-litespeed-src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") } }
                ?.takeIf { it.isNotBlank() }
            if (iframeSrc != null) {
                Log.e(TAG, "  iframe wrapper: $iframeSrc")
                val fixed = resolveUrl(iframeSrc, url, origin)
                if (manualJsUnpackExtraction(fixed, name, headers, subtitleCallback, callback)) {
                    return ProbeResult.FOUND
                }
            }
        }

        // 4. Hex / base64 / encrypted payload
        val trimmed = body.trim()
        val isHex    = RE_HEX.matches(trimmed)
        val isBase64 = !isHex && RE_BASE64.matches(trimmed)

        if (isHex || isBase64) {
            Log.e(TAG, "  ⚠ encrypted payload (hex=$isHex base64=$isBase64 len=${trimmed.length})")

            // 4a. Simple hex → bytes → UTF8 (unencrypted hex-encoded JSON/URL)
            if (isHex) {
                val decoded = try { hexToUtf8(trimmed) } catch (_: Exception) { null }
                if (decoded != null && decoded.isNotBlank()) {
                    Log.e(TAG, "  hex-decoded preview: ${decoded.take(300).replace("\n", "\\n")}")
                    if (tryDirectMedia(decoded, origin, headers, subtitleCallback, callback)) {
                        Log.e(TAG, "  ✓ hex-decoded → media")
                        return ProbeResult.FOUND
                    }
                    if (tryJsonBody(decoded, origin, headers, subtitleCallback, callback)) {
                        Log.e(TAG, "  ✓ hex-decoded → JSON")
                        return ProbeResult.FOUND
                    }
                }
            }

            // 4b. Simple base64 → UTF8
            if (isBase64) {
                val decoded = try {
                    String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) { null }
                if (decoded != null && decoded.isNotBlank()) {
                    Log.e(TAG, "  b64-decoded preview: ${decoded.take(300).replace("\n", "\\n")}")
                    if (tryDirectMedia(decoded, origin, headers, subtitleCallback, callback)) {
                        Log.e(TAG, "  ✓ b64-decoded → media")
                        return ProbeResult.FOUND
                    }
                    if (tryJsonBody(decoded, origin, headers, subtitleCallback, callback)) {
                        Log.e(TAG, "  ✓ b64-decoded → JSON")
                        return ProbeResult.FOUND
                    }
                }
            }

            // 4c. enc-dec.app decoders
            if (tryEncDecPayload(trimmed, origin, subtitleCallback, callback)) {
                Log.e(TAG, "  ✓ decoded via enc-dec.app")
                return ProbeResult.FOUND
            }

            // 4d. Log full payload for offline analysis
            Log.e(TAG, "══════ UNKNOWN ENCRYPTED PAYLOAD ══════")
            Log.e(TAG, "url=$url")
            Log.e(TAG, "length=${body.length} hex=$isHex base64=$isBase64")
            Log.e(TAG, "full_body=${body.take(6000)}")
            Log.e(TAG, "═════════════════════════════════════")
            return ProbeResult.PAYLOAD_UNKNOWN
        }

        // 5. Ad-redirect page
        if (body.contains("http-equiv=\"refresh\"", ignoreCase = true) ||
            body.contains("location.href", ignoreCase = true)) {
            Log.e(TAG, "  ⚠ ad-redirect page")
            val redirectUrl =
                Regex("""content=["']\s*\d+\s*;\s*url=([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.getOrNull(1)?.trim()
                ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""")
                    .find(body)?.groupValues?.getOrNull(1)
            if (!redirectUrl.isNullOrBlank()) {
                val fixed = resolveUrl(redirectUrl, url, origin)
                Log.e(TAG, "  following redirect → $fixed")
                // Follow once — avoid infinite loop by treating as NOT_FOUND if the redirect loops
                val nextResp = try { app.get(fixed, headers = headers) } catch (_: Exception) { null }
                if (nextResp != null && nextResp.code in 200..299) {
                    if (tryDirectMedia(nextResp.text, origin, headers, subtitleCallback, callback)) return ProbeResult.FOUND
                    if (tryJsonBody(nextResp.text, origin, headers, subtitleCallback, callback)) return ProbeResult.FOUND
                }
            }
        }

        Log.e(TAG, "  200 OK but no media / unrecognised payload")
        return ProbeResult.NOT_FOUND
    }

    // ────────────────────────────────────────────────────────────────────
    private fun hexToUtf8(hex: String): String? {
        if (hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    // ────────────────────────────────────────────────────────────────────
    private suspend fun tryEncDecPayload(
        payload: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val reqBody = """{"text":"$payload"}""".toRequestBody(JSON_TYPE)
        val decHeaders = mapOf(
            "User-Agent"   to DEFAULT_UA,
            "Origin"       to "https://enc-dec.app",
            "Referer"      to "https://enc-dec.app/",
            "Content-Type" to "application/json",
        )

        for (decoder in DECODERS) {
            val endpoint = "https://enc-dec.app/api/$decoder"
            Log.e(TAG, "    try $endpoint")
            try {
                val resp = app.post(endpoint, headers = decHeaders, requestBody = reqBody)
                val text = resp.text
                Log.e(TAG, "    $decoder → ${resp.code} len=${text.length}")

                if (resp.code != 200) continue
                if (!text.contains(".m3u8") && !text.contains(".mp4") &&
                    !text.contains("\"sources\"") && !text.contains("\"url\"")) continue

                try {
                    val json = JSONObject(text)
                    val result = json.optJSONObject("result") ?: json
                    if (tryJsonBody(result.toString(), origin, emptyMap(), subtitleCallback, callback)) return true
                    if (tryDirectMedia(result.toString(), origin, emptyMap(), subtitleCallback, callback)) return true
                } catch (_: Exception) {
                    if (tryDirectMedia(text, origin, emptyMap(), subtitleCallback, callback)) return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "    $decoder fail: ${e.message}")
            }
        }
        return false
    }

    // ────────────────────────────────────────────────────────────────────
    private fun findMainJsUrl(shellHtml: String, origin: String): String? {
        val srcs = RE_SCRIPT_SRC.findAll(shellHtml).map { it.groupValues[1] }.toList()
        Log.e(TAG, "script srcs: $srcs")

        fun absolutize(u: String): String = when {
            u.startsWith("http") -> u
            u.startsWith("//")   -> "https:$u"
            u.startsWith("/")    -> "$origin$u"
            else                 -> "$origin/$u"
        }

        val good = srcs.filter {
            !it.contains("cloudflareinsights", ignoreCase = true) &&
            !it.contains("googletagmanager", ignoreCase = true) &&
            !it.contains("google-analytics", ignoreCase = true) &&
            !it.contains("yandex", ignoreCase = true)
        }
        good.firstOrNull { it.matches(Regex(".*/assets/(index|main)-[^/]+\\.js.*")) }?.let { return absolutize(it) }
        good.firstOrNull { it.matches(Regex(".*/static/js/[^/]+\\.js.*")) }?.let { return absolutize(it) }
        good.firstOrNull { it.contains("/assets/") && it.contains(".js") }?.let { return absolutize(it) }
        return null
    }

    // ────────────────────────────────────────────────────────────────────
    private fun dumpInterestingJs(js: String) {
        // Handle minified single-line bundles: check substrings, not lines
        val keywords = listOf(
            "api/", "/api", "fetch(", "axios", ".get(", ".post(",
            "/source", "/video", "/embed", "/stream", "/resolve",
            "m3u8", "p2pstream", "rate-limit", "error",
        )
        val matches = mutableListOf<String>()
        for (kw in keywords) {
            var idx = 0
            while (idx < js.length && matches.size < 30) {
                val found = js.indexOf(kw, idx)
                if (found < 0) break
                val start = maxOf(0, found - 60)
                val end = minOf(js.length, found + 140)
                matches.add(js.substring(start, end).replace("\n", " "))
                idx = found + kw.length
            }
        }
        Log.e(TAG, "── Interesting JS snippets (${matches.size}) ──")
        matches.distinct().take(30).forEachIndexed { i, s ->
            Log.e(TAG, "  [$i] $s")
        }
    }

    // ────────────────────────────────────────────────────────────────────
    private fun discoverEndpointsFromJs(js: String): List<String> {
        val found = mutableListOf<String>()

        RE_API_PATH.findAll(js)
            .map { it.groupValues[1] }
            .filter { path ->
                listOf("video","source","stream","embed","resolve","fetch","link","play")
                    .any { path.contains(it, true) } &&
                !path.contains("/assets/") && !path.contains("/static/")
            }
            .distinct()
            .forEach { p ->
                found.add("$p?id={id}")
                found.add("$p?hash={id}")
            }

        RE_TEMPLATE_PATH.findAll(js)
            .map { it.groupValues[1] }
            .filter { p ->
                p.length > 3 &&
                listOf("video","source","stream","embed","resolve","fetch","link","play","api")
                    .any { p.contains(it, true) } &&
                !p.contains("/assets/") && !p.contains("/static/")
            }
            .distinct()
            .forEach { p -> found.add("${p.trimEnd('/')}/{id}") }

        return found.distinct().take(8)
    }

    // ────────────────────────────────────────────────────────────────────
    private suspend fun tryDirectMedia(
        text: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (text.length > 800_000) return false
        val normalized = text.replace("\\/", "/")

        RE_M3U8_ATTR.find(normalized)?.groupValues?.get(1)?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_M3U8_ANY.find(normalized)?.groupValues?.get(1)?.let { emitHls(it, origin, headers, subtitleCallback, callback); return true }
        RE_MP4_ATTR.find(normalized)?.groupValues?.get(1)?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        RE_MP4_ANY.find(normalized)?.groupValues?.get(1)?.let { callback(newExtractorLink(name, name, it, INFER_TYPE) { this.referer = "$origin/" }); return true }
        return false
    }

    // ────────────────────────────────────────────────────────────────────
    private suspend fun tryJsonBody(
        body: String,
        origin: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        val json = try { JSONObject(body) } catch (_: Exception) { return false }

        val deepUrls = extractUrlsFromJson(json)
        if (deepUrls.isNotEmpty()) {
            Log.e(TAG, "    deep-json urls: ${deepUrls.take(3)}")
            deepUrls.distinct().forEach { u -> emitSingleUrl(normalize(u), origin, headers, subtitleCallback, callback) }
            return true
        }

        val direct = listOf(
            json.optString("url"), json.optString("source"), json.optString("src"), json.optString("file"),
            json.optString("video_url"), json.optString("videoUrl"),
            json.optString("stream_url"), json.optString("streamUrl"),
        ).firstOrNull { it.isNotBlank() && (it.startsWith("http") || it.startsWith("//")) }

        if (!direct.isNullOrBlank()) {
            emitSingleUrl(normalize(direct), origin, headers, subtitleCallback, callback)
            return true
        }

        for (key in listOf("streams", "sources", "links", "videos", "mirrors", "data", "result")) {
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
            "Origin"     to origin,
            "Referer"    to "$origin/",
            "User-Agent" to DEFAULT_UA,
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        if (manualJsUnpackExtraction(url, name, headers, subtitleCallback, callback)) return

        val hash = url.substringAfter("/d/").substringBefore("/").takeIf { it.isNotBlank() }
        if (hash != null) {
            val alt = "$origin/embed/$hash"
            Log.e("Narul/Byse", "fallback → $alt")
            manualJsUnpackExtraction(alt, name, headers, subtitleCallback, callback)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Abyss  (AbyssPlayer) — unchanged; already working
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
            Log.e(TAG, "page fetch failed: ${e.message}"); return
        }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = RE_DATAS.find(scriptData)?.groupValues?.getOrNull(1) ?: return
        Log.e(TAG, "encrypted length=${encrypted.length}")

        val reqBody = """{"text":"$encrypted"}""".toRequestBody(JSON_MEDIA_TYPE)
        val responseText = try {
            app.post("https://enc-dec.app/api/dec-abyss", headers = DECODER_HEADERS, requestBody = reqBody).text
        } catch (e: Exception) {
            Log.e(TAG, "enc-dec POST failed: ${e.message}"); return
        }
        Log.e(TAG, "decoder response length=${responseText.length}")

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
        Log.e(TAG, "sources count=${sources.length()}")
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
