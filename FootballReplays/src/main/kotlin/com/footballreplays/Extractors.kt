package com.footballreplays

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val TAG = "FootballReplays"

// ==========================================
// HQCloud & HQLinks Extractors
// ==========================================

open class HQCloud : ExtractorApi() {
    override val name = "HQCloud"
    override val mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "===== START HQCloud =====")
        val path = Regex("""(https?://[^/]+)(/[^?]+)""").find(url)?.groupValues?.get(2) ?: ""
        if (path.isEmpty()) { Log.e(TAG, "HQCloud FAILED: no path"); return }

        val domains = listOf(
            "audinifer.com", "vibuxere.com", "streamhg.com",
            "dhcplay.com", "cybervynx.com"
        )

        var html = ""
        var baseUrl = ""

        for (domain in domains) {
            val newUrl = "https://$domain$path"
            try {
                val response = app.get(newUrl, referer = "https://hgcloud.to/")
                if (response.text.length > 2000) {
                    html = response.text; baseUrl = "https://$domain"
                    Log.e(TAG, "HQCloud SUCCESS domain=$domain len=${response.text.length}")
                    break
                }
            } catch (e: Exception) { Log.e(TAG, "HQCloud domain fail $domain", e) }
        }

        if (html.length < 2000) { Log.e(TAG, "HQCloud FAILED: no html"); return }

        val fileId = Regex("""\$\.cookie\('file_id',\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: return
        val aff = Regex("""\$\.cookie\('aff',\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: ""
        val refUrl = Regex("""\$\.cookie\('ref_url',\s*'([^']+)'""").find(html)?.groupValues?.get(1)

        val packerRegex = Regex(
            """(?s)eval\(function\(p,a,c,k,e,d\)\{.*?\}\('((?:[^'\\]|\\.)*)',\s*\d+,\s*\d+,\s*'((?:[^'\\]|\\.)*)'\s*(?:\.split\('\|'\))?\)"""
        )
        val match = packerRegex.find(html) ?: return
        var unpacked = match.groupValues[1]
        val k = match.groupValues[2].split("|")
        for (i in k.indices.reversed()) {
            if (k[i].isNotEmpty()) unpacked = unpacked.replace(Regex("\\b${i.toString(36)}\\b"), k[i])
        }

        var finalUrl = ""
        val varMatches = Regex("""var\s+\w+\s*=\s*\{([^}]*)\}""").findAll(unpacked).toList()
        for (vm in varMatches) {
            val objBody = vm.groupValues[1]
            if (!objBody.contains("http")) continue
            val values = Regex(""":\s*"([^"]+)"""").findAll(objBody).map { it.groupValues[1] }.toList()
            val httpValues = values.filter { it.startsWith("http") }
            val preferred = httpValues.firstOrNull { it.contains(".m3u8") } ?: httpValues.firstOrNull()
            if (preferred != null) { finalUrl = preferred; break }
        }

        if (finalUrl.isEmpty()) return

        val cookieString = buildString {
            append("file_id=$fileId; aff=$aff; tsn=7")
            if (refUrl != null) append("; ref_url=${URLEncoder.encode(refUrl, "UTF-8")}")
        }

        callback.invoke(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
            this.referer = baseUrl
            this.headers = mutableMapOf("Cookie" to cookieString)
        })
        Log.e(TAG, "===== END HQCloud SUCCESS =====")
    }
}

class HQLinks : HQCloud() { override var mainUrl = "https://hglink.to" }

// ==========================================
// VK Extractors
// ==========================================

open class VkExtractor : ExtractorApi() {
    override val name = "Vk"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        val headers = mapOf("User-Agent" to ua, "Referer" to mainUrl)

        var response = try { app.get(url, headers = headers) } catch (e: Exception) { return }
        if (response.text.contains("hash429") || response.text.contains("challenge.html")) {
            response = try {
                app.get(url, interceptor = WebViewResolver(Regex(".*video_ext\\.php.*")), headers = headers)
            } catch (e: Exception) { return }
        }

        val found = linkcikart(response.text, ua, callback)
        if (!found && url.contains("hash=")) {
            val oid = Regex("""oid=([^&]+)""").find(url)?.groupValues?.get(1)
            val id = Regex("""id=([^&]+)""").find(url)?.groupValues?.get(1)
            val hash = Regex("""hash=([^&]+)""").find(url)?.groupValues?.get(1)
            if (oid != null && id != null && hash != null) {
                val token = Regex("""anonym\.eyJ[\w\.\-]+""").find(response.text)?.value
                    ?: Regex(""""access_token"\s*:\s*"([^"]+)"""").find(response.text)?.groupValues?.get(1)
                if (token != null) {
                    val apiResp = app.post(
                        "https://api.vk.com/method/video.get?v=5.269&client_id=52461373",
                        headers = headers,
                        data = mapOf(
                            "owner_id" to "", "videos" to "${oid}_${id}_${hash}",
                            "extended" to "0", "is_embed" to "true", "track_code" to "",
                            "access_token" to token
                        )
                    )
                    linkcikart(apiResp.text, ua, callback)
                }
            }
        }
    }

    private fun linkcikart(text: String, ua: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        Regex("\"(hls|hls_ondemand|dash|dash_sep|dash_ondemand)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val t = m.groupValues[1].lowercase()
                val u = m.groupValues[2].replace("\\", "")
                if (u.isNotBlank()) {
                    any = true
                    val isDash = t.contains("dash")
                    callback.invoke(newExtractorLink(
                        "${name} ${if (isDash) "Dash" else "HLS"}",
                        "${name} ${if (isDash) "Dash" else "HLS"}",
                        u,
                        if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    ) { this.referer = mainUrl; this.headers = mapOf("User-Agent" to ua, "Referer" to mainUrl) })
                }
            }
        return any
    }
}

class VkCom : VkExtractor() { override var mainUrl = "https://vk.com" }

// ==========================================
// Byse Extractor (AES-GCM protected playback)
// ==========================================

open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private fun b64(s: String, label: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        val out = Base64.getDecoder().decode(fixed + pad)
        Log.e(TAG, "Byse b64[$label] outLen=${out.size}")
        return out
    }

    /** Log response headers so we can see Allow / WWW-Authenticate etc. */
    private fun logHeaders(label: String, headers: Map<String, List<String>>) {
        headers.forEach { (k, v) ->
            if (k.equals("allow", true) || k.equals("www-authenticate", true) ||
                k.equals("access-control-allow-methods", true) || k.equals("set-cookie", true)) {
                Log.e(TAG, "Byse HDR[$label] $k = $v")
            }
        }
    }

    private suspend fun tryEndpoint(
        url: String, headers: Map<String, String>, label: String
    ): String? {
        // GET
        try {
            val r = app.get(url, headers = headers)
            logHeaders("$label-GET", r.headers)
            Log.e(TAG, "Byse [$label] GET -> ${r.code} ${r.text.take(120)}")
            if (r.code in 200..299) return r.text
        } catch (e: Exception) { Log.e(TAG, "Byse [$label] GET err=${e.message}") }
        // POST empty
        try {
            val r = app.post(url, headers = headers, data = emptyMap<String, String>())
            logHeaders("$label-POST", r.headers)
            Log.e(TAG, "Byse [$label] POST -> ${r.code} ${r.text.take(120)}")
            if (r.code in 200..299) return r.text
        } catch (e: Exception) { Log.e(TAG, "Byse [$label] POST err=${e.message}") }
        // PATCH
        try {
            val r = app.patch(url, headers = headers, data = emptyMap<String, String>())
            logHeaders("$label-PATCH", r.headers)
            Log.e(TAG, "Byse [$label] PATCH -> ${r.code} ${r.text.take(120)}")
            if (r.code in 200..299) return r.text
        } catch (e: Exception) { Log.e(TAG, "Byse [$label] PATCH err=${e.message}") }
        // DELETE
        try {
            val r = app.delete(url, headers = headers)
            logHeaders("$label-DELETE", r.headers)
            Log.e(TAG, "Byse [$label] DELETE -> ${r.code} ${r.text.take(120)}")
            if (r.code in 200..299) return r.text
        } catch (e: Exception) { Log.e(TAG, "Byse [$label] DELETE err=${e.message}") }
        return null
    }

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "===== START ByseSX =====")
        Log.e(TAG, "Byse input url=$url")

        try {
            val uri = URI(url)
            val code = uri.path.trimEnd('/').substringAfterLast('/')
            val base = "${uri.scheme}://${uri.host}"

            // ---------- 1. Details ----------
            val detailsResp = try {
                app.get("$base/api/videos/$code/embed/details")
            } catch (e: Exception) { Log.e(TAG, "Byse details err", e); return }

            Log.e(TAG, "Byse details code=${detailsResp.code}")
            Log.e(TAG, "Byse details body=${detailsResp.text.take(800)}")
            Log.e(TAG, "Byse details Set-Cookie=${detailsResp.headers["set-cookie"]}")

            val details = detailsResp.parsedSafe<ByseDetailsRoot>()
                ?: run { Log.e(TAG, "Byse FAILED: details null"); return }

            val embedFrameUrl = details.embedFrameUrl
            Log.e(TAG, "Byse embedFrameUrl=$embedFrameUrl id=${details.id}")

            // ---------- 2. PRIMARY: WebView intercept playback ----------
            var playbackBody: String? = null
            var winner = ""

            // 2a. Intercept a response that looks like playback JSON
            try {
                Log.e(TAG, "Byse WebView: loading embed iframe, intercepting playback JSON")
                val resp = app.get(
                    embedFrameUrl,
                    interceptor = WebViewResolver(
                        Regex(""".*(/embed/playback|/playback|playback\?|api/videos/\w+/playback).*""")
                    ),
                    headers = mapOf(
                        "Referer" to "https://www.footreplays.com/",
                        "User-Agent" to ua
                    )
                )
                Log.e(TAG, "Byse WebView resp url=${resp.url} code=${resp.code} len=${resp.text.length}")
                Log.e(TAG, "Byse WebView body=${resp.text.take(800)}")
                if (resp.code in 200..299 &&
                    (resp.text.contains("\"playback\"") || resp.text.contains("\"key_parts\""))) {
                    playbackBody = resp.text
                    winner = "webview-playback"
                    Log.e(TAG, "Byse SUCCESS webview intercepted playback: ${resp.url}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Byse webview playback err=${e.message}")
            }

            // 2b. Fallback: intercept an m3u8 directly
            if (playbackBody == null) {
                try {
                    Log.e(TAG, "Byse WebView: intercepting .m3u8")
                    val resp = app.get(
                        embedFrameUrl,
                        interceptor = WebViewResolver(Regex(""".*\.m3u8.*""")),
                        headers = mapOf("Referer" to "https://www.footreplays.com/", "User-Agent" to ua)
                    )
                    Log.e(TAG, "Byse WebView m3u8 url=${resp.url} code=${resp.code}")
                    if (resp.url.contains(".m3u8")) {
                        callback.invoke(newExtractorLink(name, name, resp.url, ExtractorLinkType.M3U8) {
                            this.referer = embedFrameUrl
                            this.headers = mapOf("Referer" to embedFrameUrl, "User-Agent" to ua)
                        })
                        Log.e(TAG, "Byse SUCCESS via webview m3u8: ${resp.url}")
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Byse webview m3u8 err=${e.message}")
                }
            }

            // ---------- 3. FALLBACK: candidate matrix ----------
            if (playbackBody == null) {
                val embedBase = "${URI(embedFrameUrl).scheme}://${URI(embedFrameUrl).host}"
                val pathSeg = URI(embedFrameUrl).path.trimEnd('/').substringBeforeLast('/').trimStart('/')

                val srcHeaders = mapOf(
                    "Referer" to "$base/e/$code",
                    "Origin" to base,
                    "x-embed-parent" to url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                    "Content-Type" to "application/json",
                    "User-Agent" to ua
                )
                val cdnHeaders = srcHeaders + mapOf(
                    "Referer" to embedFrameUrl,
                    "Origin" to embedBase
                )

                val candidates = mutableListOf<Pair<String, Map<String, String>>>()
                for ((profileName, hdrs) in listOf("src" to srcHeaders, "cdn" to cdnHeaders)) {
                    for (host in listOf(base, embedBase)) {
                        for (suffix in listOf(
                            "api/videos/$code/embed/playback",
                            "api/videos/${details.id}/embed/playback",
                            "api/videos/$code/playback",
                            "api/$pathSeg/$code/playback",
                            "api/$pathSeg/$code",
                            "$pathSeg/$code/playback",
                            "api/videos/$code/embed/play",
                            "api/playback/$code",
                            "api/playback/videos/$code",
                            "api/videos/$code/sources",
                            "api/videos/$code/embed/sources"
                        )) {
                            candidates += "$profileName-$host-$suffix" to (hdrs + mapOf("__url" to "https://$host/$suffix"))
                        }
                    }
                }

                outer@ for ((label, hdrs) in candidates) {
                    val candUrl = hdrs["__url"] ?: continue
                    val cleanHeaders = hdrs.filterKeys { it != "__url" }
                    val body = tryEndpoint(candUrl, cleanHeaders, label)
                    if (body != null) {
                        playbackBody = body
                        winner = label
                        Log.e(TAG, "Byse SUCCESS candidate [$label] body=${body.take(300)}")
                        break@outer
                    }
                }
            }

            if (playbackBody == null) {
                Log.e(TAG, "Byse FAILED: no playback body from any method")
                return
            }

            // ---------- 4. Decrypt ----------
            val root = tryParseJson<BysePlaybackRoot>(playbackBody)
                ?: run { Log.e(TAG, "Byse FAILED: parse playback body. Winner=$winner"); return }

            val pb = root.playback
            Log.e(TAG, "Byse pb algo=${pb.algorithm} ivLen=${pb.iv.length} keys=${pb.keyParts.size} payloadLen=${pb.payload.length}")

            val key = b64(pb.keyParts[0], "k0") + b64(pb.keyParts[1], "k1")
            val iv = b64(pb.iv, "iv")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))

            val decrypted = cipher.doFinal(b64(pb.payload, "payload"))
            val json = String(decrypted, StandardCharsets.UTF_8)
                .let { if (it.startsWith("\uFEFF")) it.substring(1) else it }
            Log.e(TAG, "Byse decrypted=${json.take(1200)}")

            val parsed = tryParseJson<BysePlaybackDecrypt>(json)
                ?: run { Log.e(TAG, "Byse FAILED: parse decrypted"); return }

            val embedBase = "${URI(embedFrameUrl).scheme}://${URI(embedFrameUrl).host}"

            parsed.sources.forEachIndexed { i, s ->
                Log.e(TAG, "Byse src[$i] q=${s.quality} url=${s.url.take(180)}")
                callback.invoke(newExtractorLink(name, name, s.url, ExtractorLinkType.M3U8) {
                    this.referer = embedFrameUrl
                    this.headers = mutableMapOf(
                        "Referer" to embedFrameUrl,
                        "Origin" to embedBase,
                        "User-Agent" to ua
                    )
                })
            }

            Log.e(TAG, "===== END ByseSX SUCCESS via $winner =====")
        } catch (e: Exception) {
            Log.e(TAG, "Byse FAILED: ${e.message}", e)
        }
    }
}

// ==========================================
// Byse Data Classes
// ==========================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class ByseDetailsRoot(
    val id: Long,
    val code: String,
    val title: String,
    @JsonProperty("poster_url") val posterUrl: String,
    val description: String,
    @JsonProperty("embed_frame_url") val embedFrameUrl: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackRoot(val playback: BysePlayback)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlayback(
    val algorithm: String,
    val iv: String,
    val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackSource(
    val quality: String,
    val label: String,
    val url: String
)
