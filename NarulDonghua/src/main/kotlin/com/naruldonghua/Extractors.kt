package com.naruldonghua

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
private val RE_M3U8_ANY = Regex("""(https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_MP4_ANY  = Regex("""(https?://[^"'\s\\<>]+\.(?:mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
private val RE_IFRAME   = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

// ════════════════════════════════════════════════════════════════════════════
//  JACKSON PAYLOAD SCHEMA
// ════════════════════════════════════════════════════════════════════════════
data class ByseSource(
    @JsonProperty("file")  val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type")  val type: String? = null,
)

data class ByseSubtitle(
    @JsonProperty("file")     val file: String? = null,
    @JsonProperty("label")    val label: String? = null,
    @JsonProperty("language") val language: String? = null,
)

data class BysePlayback(
    @JsonProperty("sources")   val sources: List<ByseSource>? = null,
    @JsonProperty("tracks")    val tracks: List<ByseSubtitle>? = null,
    @JsonProperty("subtitles") val subtitles: List<ByseSubtitle>? = null,
)

private val BYSE_MAPPER = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .build()

// ════════════════════════════════════════════════════════════════════════════
//  SHARED HELPERS
// ════════════════════════════════════════════════════════════════════════════
private fun chromeHeaders(
    referer: String? = null,
    origin: String? = null,
    accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
): Map<String, String> = buildMap {
    put("User-Agent", DEFAULT_UA)
    put("Accept", accept)
    put("Accept-Language", "en-US,en;q=0.9")
    referer?.let { put("Referer", it) }
    origin?.let { put("Origin", it) }
}

private fun mediaHeadersMinimal(referer: String): Map<String, String> = mapOf(
    "User-Agent" to DEFAULT_UA,
    "Accept" to "*/*",
    "Referer" to referer,
    "Sec-Fetch-Dest" to "video",
    "Sec-Fetch-Mode" to "no-cors",
    "Sec-Fetch-Site" to "cross-site",
)

internal fun originOf(url: String): String? =
    Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)

internal fun hostOf(url: String): String? =
    Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.getOrNull(1)?.lowercase()

internal fun isErrorPage(html: String): Boolean {
    if (html.isBlank()) return true
    val lower = html.lowercase()
    if (lower.contains("cf-browser-verification") || lower.contains("checking your browser")) return true
    if (html.length < 10_000 && (lower.contains("<title>404") || lower.contains("404 not found") || lower.contains("503 service"))) return true
    return false
}

internal fun resolveUrl(uri: String, masterUrl: String, origin: String): String? {
    val trimmed = uri.trim().replace("&amp;", "&")
    if (trimmed.isEmpty() || trimmed.startsWith("javascript:", true) || trimmed.startsWith("data:", true)) return null
    return when {
        trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/")  -> "$origin$trimmed"
        else -> "${masterUrl.substringBefore('?').substringBefore('#').substringBeforeLast('/')}/$trimmed"
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  CRYPTO (envelope decryption — stays on org.json / byte arrays)
// ════════════════════════════════════════════════════════════════════════════
private fun b64UrlDecode(input: String): ByteArray? = try {
    Base64.decode(
        input.trim().replace('-', '+').replace('_', '/')
            .replace(Regex("\\s+"), "")
            .padEnd((input.length + 3) / 4 * 4, '='),
        Base64.DEFAULT
    )
} catch (_: Exception) { null }

private fun tryAesGcm(ctAndTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? = try {
    Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        .doFinal(ctAndTag)
} catch (_: Exception) { null }

// ════════════════════════════════════════════════════════════════════════════
//  BYSE EXTRACTOR — VERIFIED BEST PRACTICE
// ════════════════════════════════════════════════════════════════════════════
class Bysekoze : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "Narul/Byse"

        private fun extractHash(url: String): String? {
            val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
            Regex("""/(?:d|embed|e|v|video|download|a2lyf|cx5h|w6u1)/([a-zA-Z0-9]{8,32})""")
                .find(clean)?.let { return it.groupValues[1] }
            return clean.substringAfterLast('/').takeIf { it.matches(Regex("^[a-zA-Z0-9]{8,32}$")) }
        }

        private fun extractInlinePlayback(html: String): JSONObject? {
            val algorithm = Regex(""""algorithm"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val iv        = Regex(""""iv"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val payload   = Regex(""""payload"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
            val keyParts  = Regex(""""key_parts"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1) ?: return null
            val kp = keyParts.split(",").map { it.replace("\"", "").trim() }.filter { it.isNotBlank() }
            if (kp.isEmpty()) return null
            val pbJson = """{"playback":{"algorithm":"$algorithm","iv":"$iv","payload":"$payload","key_parts":[${kp.joinToString(",") { "\"$it\"" }}]}}"""
            return runCatching { JSONObject(pbJson).optJSONObject("playback") }.getOrNull()
        }

        private fun extractIframeSrc(html: String, baseUrl: String): String? {
            val raw = runCatching {
                Jsoup.parse(html, baseUrl).selectFirst("iframe[src]")?.attr("src")
            }.getOrNull()
                ?: RE_IFRAME.find(html)?.groupValues?.getOrNull(1)
            if (raw.isNullOrBlank()) return null
            return resolveUrl(raw, baseUrl, originOf(baseUrl) ?: return null)
        }
    }

    // Emission coordinator — deduplicates concurrent paths
    private class EmissionCoordinator {
        private val lock = Mutex()
        private val seen = mutableSetOf<String>()
        private var won = false

        suspend fun tryEmit(key: String): Boolean = lock.withLock {
            if (!seen.add(key)) return@withLock false
            won = true
            true
        }

        suspend fun isWon(): Boolean = lock.withLock { won }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(url) ?: throw ErrorLoadingException("Byse: invalid URL $url")
        val hash = extractHash(url) ?: throw ErrorLoadingException("Byse: no hash in $url")
        Log.e(TAG, "=== Byse START hash=$hash origin=$origin")

        val coord = EmissionCoordinator()
        var anyEmitted = false

        val safeEmit: suspend (ExtractorLink) -> Unit = { link ->
            if (coord.tryEmit("${link.url}|${link.referer}")) {
                anyEmitted = true
                callback(link)
            }
        }

        // ── Race the two cheap HTTP paths ─────────────────────────────────
        coroutineScope {
            val inlineJob = async(Dispatchers.IO) {
                try { tryInlineJsonPath(url, origin, hash, referer, subtitleCallback, coord, safeEmit) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "inline path: ${e.message}") }
            }
            val restJob = async(Dispatchers.IO) {
                try { tryRestApiPath(url, origin, hash, referer, subtitleCallback, coord, safeEmit) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w(TAG, "rest path: ${e.message}") }
            }
            inlineJob.await()
            restJob.await()
        }

        if (anyEmitted) {
            Log.e(TAG, "✓ Byse resolved via concurrent HTTP race")
            return
        }

        // ── WebViewResolver — verified framework fallback ────────────────
        val wvResult = try {
            com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = Regex(""".*\.m3u8.*""", RegexOption.IGNORE_CASE),
                useOkhttp = false,          // required for Cloudflare[reference:13]
                script = null,
                scriptCallback = null,
            ).resolveUsingWebView(
                url = "$origin/e/$hash",
                headers = mapOf("Referer" to "$origin/", "User-Agent" to DEFAULT_UA),
            )
        } catch (_: Exception) { null }

        if (wvResult?.url != null) {
            callback(newExtractorLink(name, name, wvResult.url, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.headers = wvResult.request?.headers?.toMap()
                    ?: mediaHeadersMinimal("$origin/")
            })
            Log.e(TAG, "✓ Byse via WebViewResolver")
            return
        }

        throw ErrorLoadingException("Byse: all paths exhausted for hash=$hash")
    }

    // ── Path 1: dynamic iframe → inline JSON → Jackson ───────────────────
    private suspend fun tryInlineJsonPath(
        url: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        coord: EmissionCoordinator,
        emit: suspend (ExtractorLink) -> Unit
    ) {
        if (coord.isWon()) return

        val shellUrl = "$origin/e/$hash"
        val shellHtml = withTimeoutOrNull(8_000L) {
            app.get(shellUrl, headers = chromeHeaders(referer ?: "$origin/")).text
        } ?: return
        if (isErrorPage(shellHtml)) return
        if (coord.isWon()) return

        // (a) Inline JSON directly on shell
        extractInlinePlayback(shellHtml)?.let { pb ->
            val decrypted = decryptBysePlayback(pb) ?: tryExternalByseDecoder(pb, shellUrl)
            if (!decrypted.isNullOrBlank()) {
                emitDecrypted(decrypted, origin, shellUrl, subtitleCallback, emit)
                return
            }
        }
        if (coord.isWon()) return

        // (b) Follow iframe
        val iframeUrl = extractIframeSrc(shellHtml, shellUrl) ?: return
        val iframeOrigin = originOf(iframeUrl) ?: origin

        // (c) Delegate to loadExtractor — verified recursive mechanism[reference:14]
        //     If the iframe points to a known player, loadExtractor handles it.
        val delegated = try {
            loadExtractor(iframeUrl, referer, subtitleCallback) { link ->
                // We need to count emissions; launch a lightweight emission
                // so the coordinator sees it.
                kotlinx.coroutines.runBlocking {
                    if (coord.tryEmit("${link.url}|${link.referer}")) emit(link)
                }
            }
            true
        } catch (_: Exception) { false }

        if (delegated && coord.isWon()) return

        // (d) Fallback: fetch iframe ourselves and parse Byse payload
        val playerHtml = withTimeoutOrNull(8_000L) {
            app.get(iframeUrl, headers = chromeHeaders(shellUrl)).text
        } ?: return
        if (isErrorPage(playerHtml)) return

        val pb = extractInlinePlayback(playerHtml) ?: return
        val decrypted = decryptBysePlayback(pb) ?: tryExternalByseDecoder(pb, iframeUrl) ?: return
        emitDecrypted(decrypted, iframeOrigin, iframeUrl, subtitleCallback, emit)
    }

    // ── Path 2: REST API (discovered origin) ─────────────────────────────
    private suspend fun tryRestApiPath(
        url: String, origin: String, hash: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        coord: EmissionCoordinator,
        emit: suspend (ExtractorLink) -> Unit
    ) {
        if (coord.isWon()) return

        val postBody = "{\"password\":\"\"}".toRequestBody(JSON_MEDIA_TYPE)
        val pbHeaders = mapOf(
            "User-Agent"       to DEFAULT_UA,
            "Content-Type"     to "application/json",
            "Referer"          to "$origin/e/$hash",
            "Origin"           to origin,
            "X-Requested-With" to "XMLHttpRequest",
            "X-Embed-Origin"   to (hostOf(origin) ?: "bysejikuar.com"),
            "X-Embed-Parent"   to url,
            "X-Embed-Referer"  to (referer ?: "https://naruldonghua.com/"),
        )

        val resp = withTimeoutOrNull(10_000L) {
            app.post("$origin/api/videos/$hash/embed/playback", headers = pbHeaders, requestBody = postBody)
        } ?: return

        val text = resp.text.trimStart()
        if (resp.code !in 200..299 || (!text.startsWith("{") && !text.startsWith("["))) return
        if (coord.isWon()) return

        if (emitDecrypted(text, origin, "$origin/e/$hash", subtitleCallback, emit)) return

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val pbObj = root.optJSONObject("playback") ?: root
        val decrypted = decryptBysePlayback(pbObj) ?: tryExternalByseDecoder(pbObj, "$origin/e/$hash")
            ?: return
        emitDecrypted(decrypted, origin, "$origin/e/$hash", subtitleCallback, emit)
    }

    // ── Jackson-based emission (typed schema) ────────────────────────────
    private suspend fun emitDecrypted(
        json: String, pbOrigin: String, refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (ExtractorLink) -> Unit
    ): Boolean {
        val playback = tryParseJson<BysePlayback>(json, BYSE_MAPPER) ?: return false
        var emitted = false

        // Subtitles
        (playback.tracks ?: playback.subtitles)?.forEach { sub ->
            sub.file?.takeIf { it.isNotBlank() }?.let { f ->
                subtitleCallback(newSubtitleFile(
                    sub.label ?: "Sub",
                    resolveUrl(f, refererUrl, pbOrigin) ?: f
                ) { this.headers = mediaHeadersMinimal("$pbOrigin/") })
            }
        }

        // Sources
        playback.sources?.forEach { src ->
            val raw = src.file ?: return@forEach
            val streamUrl = resolveUrl(raw, refererUrl, pbOrigin) ?: raw
            val quality = when {
                src.label?.contains("1080") == true -> Qualities.P1080.value
                src.label?.contains("720")  == true -> Qualities.P720.value
                src.label?.contains("480")  == true -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            if (streamUrl.contains(".m3u8", true) || streamUrl.contains("/hls/", true)) {
                M3u8Helper.generateM3u8(
                    name, streamUrl, "$pbOrigin/",
                    headers = mediaHeadersMinimal("$pbOrigin/")
                ).forEach { emit(it) }
            } else {
                emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.referer = "$pbOrigin/"
                    this.quality = quality
                    this.headers = mediaHeadersMinimal("$pbOrigin/")
                })
            }
            emitted = true
        }

        // Fallback miner if Jackson schema missed anything
        if (!emitted) {
            val deep = extractMediaUrlsFromJson(JSONObject(json))
            deep.distinct().forEach { raw ->
                val streamUrl = resolveUrl(raw, refererUrl, pbOrigin) ?: raw
                emit(newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                    this.referer = "$pbOrigin/"
                    this.headers = mediaHeadersMinimal("$pbOrigin/")
                })
                emitted = true
            }
        }
        return emitted
    }

    // ── Envelope decryption (org.json — regex-extracted, not typed) ──────
    private fun decryptBysePlayback(playback: JSONObject): String? {
        val ivB64 = playback.optString("iv")
        val payloadB64 = playback.optString("payload")
        val keyParts = playback.optJSONArray("key_parts")
        if (ivB64.isNotBlank() && payloadB64.isNotBlank() && keyParts != null) {
            val iv = b64UrlDecode(ivB64)
            val ct = b64UrlDecode(payloadB64)
            val keyOut = ByteArrayOutputStream()
            for (i in 0 until keyParts.length()) b64UrlDecode(keyParts.optString(i))?.let { keyOut.write(it) }
            if (iv != null && ct != null && keyOut.size() > 0) {
                tryAesGcm(ct, keyOut.toByteArray(), iv)?.let { b ->
                    return String(b, Charsets.UTF_8)
                        .trimStart('\uFEFF', ' ', '\n', '\t')
                        .takeIf { it.startsWith("{") || it.startsWith("[") }
                }
            }
        }

        val iv2B64 = playback.optString("iv2")
        val payload2B64 = playback.optString("payload2")
        val dk = playback.optJSONObject("decrypt_keys")
        if (iv2B64.isNotBlank() && payload2B64.isNotBlank() && dk != null) {
            val iv2 = b64UrlDecode(iv2B64)
            val ct2 = b64UrlDecode(payload2B64)
            val edge1 = b64UrlDecode(dk.optString("edge_1"))
            val edge2 = b64UrlDecode(dk.optString("edge_2"))
            if (iv2 != null && ct2 != null && edge1 != null && edge2 != null) {
                tryAesGcm(ct2, edge1 + edge2, iv2)?.let { b ->
                    return String(b, Charsets.UTF_8)
                        .trimStart('\uFEFF', ' ', '\n', '\t')
                        .takeIf { it.startsWith("{") || it.startsWith("[") }
                }
            }
        }
        return null
    }

    private suspend fun tryExternalByseDecoder(playback: JSONObject, referer: String): String? = try {
        val reqJson = JSONObject().apply { put("text", playback); put("agent", DEFAULT_UA) }
        val resp = withTimeoutOrNull(8_000L) {
            app.post("https://enc-dec.app/api/dec-byse", headers = mapOf(
                "User-Agent"   to DEFAULT_UA,
                "Content-Type" to "application/json",
                "Origin"       to "https://enc-dec.app",
                "Referer"      to referer,
            ), requestBody = reqJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        } ?: return null
        val text = resp.text.trimStart()
        if (resp.code != 200 || text.isBlank()) return null
        val root = JSONObject(text)
        root.optJSONObject("result")?.toString()
            ?: root.optJSONArray("result")?.toString()
            ?: root.optString("result").takeIf { it.trimStart().let { t -> t.startsWith("{") || t.startsWith("[") } }
    } catch (_: Exception) { null }

    // ── Deep miner (org.json — unknown-shape fallback) ───────────────────
    private fun extractMediaUrlsFromJson(json: JSONObject, depth: Int = 0): List<String> {
        if (depth > 5) return emptyList()
        val urls = mutableListOf<String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val v = json.opt(key)) {
                is String -> {
                    val s = v.replace("\\/", "/").trim()
                    RE_M3U8_ANY.findAll(s).forEach { urls.add(it.groupValues[1]) }
                    RE_MP4_ANY.findAll(s).forEach { urls.add(it.groupValues[1]) }
                    if (key.equals("file", true) || key.equals("url", true) || key.equals("src", true)) {
                        if (s.startsWith("http") && !s.endsWith(".jpg") && !s.endsWith(".png") && !s.contains("/embed/")) {
                            urls.add(s)
                        }
                    }
                }
                is JSONObject -> urls.addAll(extractMediaUrlsFromJson(v, depth + 1))
                is JSONArray -> for (i in 0 until v.length()) {
                    v.optJSONObject(i)?.let { urls.addAll(extractMediaUrlsFromJson(it, depth + 1)) }
                }
            }
        }
        return urls
    }
}
