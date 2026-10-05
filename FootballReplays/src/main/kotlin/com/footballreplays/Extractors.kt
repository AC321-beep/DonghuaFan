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

private const val BYSE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

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

        if (finalUrl.isEmpty()) { Log.e(TAG, "HQCloud FAILED: no finalUrl"); return }

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
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ua = BYSE_UA
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

    private suspend fun linkcikart(
        text: String,
        ua: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex(
            "\"(hls|hls_ondemand|dash|dash_sep|dash_ondemand)\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { m ->
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
                ) {
                    this.referer = mainUrl
                    this.headers = mapOf("User-Agent" to ua, "Referer" to mainUrl)
                })
            }
        }
        return any
    }
}

class VkCom : VkExtractor() { override var mainUrl = "https://vk.com" }

// ==========================================
// Byse Extractor — WebView-based (PoW + attestation)
// ==========================================

open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64(s: String, label: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        val out = Base64.getDecoder().decode(fixed + pad)
        Log.e(TAG, "Byse b64[$label] outLen=${out.size}")
        return out
    }

    private fun byseHeaders(base: String, code: String): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$base/e/$code",
        "Origin" to base,
        "x-embed-origin" to "footreplays.com",
        "x-embed-parent" to "$base/e/$code",
        "x-embed-referer" to "https://www.footreplays.com/",
        "User-Agent" to BYSE_UA
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
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
            val detailsHeaders = byseHeaders(base, code)
            val detailsResp = app.get("$base/api/videos/$code/embed/details", headers = detailsHeaders)
            Log.e(TAG, "Byse details code=${detailsResp.code}")
            Log.e(TAG, "Byse details body=${detailsResp.text.take(500)}")
            Log.e(TAG, "Byse details x-byse-server=${detailsResp.headers["x-byse-server"]}")
            Log.e(TAG, "Byse details set-cookie=${detailsResp.headers["set-cookie"]}")

            val details = detailsResp.parsedSafe<ByseDetailsRoot>()
                ?: run { Log.e(TAG, "Byse FAILED: details null"); return }

            val embedFrameUrl = details.embedFrameUrl
            Log.e(TAG, "Byse embedFrameUrl=$embedFrameUrl")

            if (embedFrameUrl.isBlank()) {
                Log.e(TAG, "Byse FAILED: blank embedFrameUrl"); return
            }

            val embedBase = "${URI(embedFrameUrl).scheme}://${URI(embedFrameUrl).host}"

            // ---------- 2a. Warm-up load ----------
            // Mimics the FIRST click: SPA runs settings -> challenge -> attest,
            // stores the attestation token, but does NOT fire /playback yet.
            Log.e(TAG, "Byse WebView: warm-up load (challenge/attest)")
            try {
                app.get(
                    embedFrameUrl,
                    interceptor = WebViewResolver(
                        Regex(""".*/api/videos/[^/]+/embed/settings.*""")
                    ),
                    headers = mapOf(
                        "Referer" to "https://www.footreplays.com/",
                        "User-Agent" to BYSE_UA
                    )
                )
                Log.e(TAG, "Byse warm-up: settings intercepted (challenge/attest should now be cached)")
            } catch (e: Exception) {
                Log.e(TAG, "Byse warm-up FAILED: ${e.message}")
            }

            // Give the SPA a moment to finish the PoW computation + attest call
            kotlinx.coroutines.delay(4_000L)

            // ---------- 2b. Intercept load ----------
            // Mimics the SECOND click: token is cached, SPA immediately fires /playback.
            Log.e(TAG, "Byse WebView: intercept load (playback)")
            val wv = try {
                app.get(
                    embedFrameUrl,
                    interceptor = WebViewResolver(
                        Regex(""".*/api/videos/[^/]+/(embed/)?(playback|sources?).*""")
                    ),
                    headers = mapOf(
                        "Referer" to "https://www.footreplays.com/",
                        "User-Agent" to BYSE_UA
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Byse WebView intercept FAILED: ${e.message}", e)
                return
            }

            Log.e(TAG, "Byse WebView resp.url=${wv.url}")
            Log.e(TAG, "Byse WebView resp.code=${wv.code}")
            Log.e(TAG, "Byse WebView resp.body=${wv.text.take(600)}")

            val playbackBody: String = when {
                wv.text.contains("\"playback\"") && wv.text.contains("\"key_parts\"") -> wv.text
                wv.text.contains("\"key_parts\"") -> "{\"playback\":${wv.text}}"
                else -> {
                    Log.e(TAG, "Byse FAILED: intercepted body is not playback JSON. url=${wv.url}")
                    return
                }
            }

            val root = tryParseJson<BysePlaybackRoot>(playbackBody)
                ?: run { Log.e(TAG, "Byse FAILED: playback parse null"); return }

            val pb = root.playback
            Log.e(TAG, "Byse pb algo=${pb.algorithm} ivLen=${pb.iv.length} keys=${pb.keyParts.size} payloadLen=${pb.payload.length}")

            if (pb.keyParts.size < 2) {
                Log.e(TAG, "Byse FAILED: keyParts.size < 2"); return
            }

            // ---------- 3. AES-GCM decrypt ----------
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

            // ---------- 4. Emit sources ----------
            parsed.sources.forEachIndexed { i, s ->
                Log.e(TAG, "Byse src[$i] q=${s.quality} label=${s.label} url=${s.url.take(200)}")
                callback.invoke(newExtractorLink(name, name, s.url, ExtractorLinkType.M3U8) {
                    this.referer = embedFrameUrl
                    this.headers = mutableMapOf(
                        "Referer" to embedFrameUrl,
                        "Origin" to embedBase,
                        "User-Agent" to BYSE_UA
                    )
                })
            }

            Log.e(TAG, "===== END ByseSX SUCCESS (${parsed.sources.size} sources) =====")
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
