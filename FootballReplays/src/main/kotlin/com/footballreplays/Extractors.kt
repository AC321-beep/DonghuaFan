package com.footballreplays

import android.util.Log
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
        Log.e(TAG, "HQCloud input url=$url referer=$referer")

        val path = Regex("""(https?://[^/]+)(/[^?]+)""").find(url)?.groupValues?.get(2) ?: ""
        Log.e(TAG, "HQCloud extracted path=$path")

        if (path.isEmpty()) {
            Log.e(TAG, "HQCloud FAILED: could not extract path from url")
            return
        }

        val domains = listOf(
            "audinifer.com",
            "vibuxere.com",
            "streamhg.com",
            "dhcplay.com",
            "cybervynx.com"
        )

        var html = ""
        var baseUrl = ""

        for (domain in domains) {
            val newUrl = "https://$domain$path"
            Log.e(TAG, "HQCloud trying domain=$domain url=$newUrl")
            try {
                val response = app.get(newUrl, referer = "https://hgcloud.to/")
                Log.e(TAG, "HQCloud response code=${response.code} length=${response.text.length}")
                if (response.text.length > 2000) {
                    html = response.text
                    baseUrl = "https://$domain"
                    Log.e(TAG, "HQCloud SUCCESS using domain=$domain")
                    break
                } else {
                    Log.e(TAG, "HQCloud response too short (${response.text.length}), skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "HQCloud domain fail: $domain", e)
            }
        }

        if (html.length < 2000) {
            Log.e(TAG, "HQCloud FAILED: no valid html found (final length=${html.length})")
            return
        }

        Log.e(TAG, "HQCloud baseUrl=$baseUrl")

        val fileId = Regex("""\$\.cookie\('file_id',\s*'([^']+)'""").find(html)?.groupValues?.get(1)
        if (fileId == null) {
            Log.e(TAG, "HQCloud FAILED: fileId not found in html")
            return
        }
        Log.e(TAG, "HQCloud fileId=$fileId")

        val aff = Regex("""\$\.cookie\('aff',\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: ""
        Log.e(TAG, "HQCloud aff=$aff")

        val refUrl = Regex("""\$\.cookie\('ref_url',\s*'([^']+)'""").find(html)?.groupValues?.get(1)
        Log.e(TAG, "HQCloud refUrl=$refUrl")

        val packerRegex = Regex(
            """(?s)eval\(function\(p,a,c,k,e,d\)\{.*?\}\('((?:[^'\\]|\\.)*)',\s*\d+,\s*\d+,\s*'((?:[^'\\]|\\.)*)'\s*(?:\.split\('\|'\))?\)"""
        )
        val match = packerRegex.find(html)
        if (match == null) {
            Log.e(TAG, "HQCloud FAILED: packer regex did not match")
            return
        }
        Log.e(TAG, "HQCloud packer regex matched")

        var unpacked = match.groupValues[1]
        val k = match.groupValues[2].split("|")
        Log.e(TAG, "HQCloud packer k size=${k.size}")

        for (i in k.indices.reversed()) {
            val word = i.toString(36)
            if (k[i].isNotEmpty()) {
                unpacked = unpacked.replace(Regex("\\b$word\\b"), k[i])
            }
        }
        Log.e(TAG, "HQCloud unpacked length=${unpacked.length}")
        Log.e(TAG, "HQCloud unpacked preview=${unpacked.take(1000)}")

        var finalUrl = ""
        val varObjRegex = Regex("""var\s+\w+\s*=\s*\{([^}]*)\}""")
        val varMatches = varObjRegex.findAll(unpacked).toList()
        Log.e(TAG, "HQCloud var object matches=${varMatches.size}")

        for ((idx, vm) in varMatches.withIndex()) {
            val objBody = vm.groupValues[1]
            Log.e(TAG, "HQCloud var[$idx] body=${objBody.take(400)}")

            if (!objBody.contains("http")) {
                Log.e(TAG, "HQCloud var[$idx] skipped (no http)")
                continue
            }

            val values = Regex(""":\s*"([^"]+)"""")
                .findAll(objBody)
                .map { it.groupValues[1] }
                .toList()
            Log.e(TAG, "HQCloud var[$idx] values=$values")

            val pathValue = values.firstOrNull {
                it.startsWith("/") && !it.startsWith("/dl") && !it.startsWith("/assets")
            }

            if (pathValue != null) {
                finalUrl = baseUrl + pathValue
                Log.e(TAG, "HQCloud found path value=$pathValue -> finalUrl=$finalUrl")
                break
            }

            val httpValue = values.firstOrNull { it.startsWith("http") }
            if (httpValue != null) {
                finalUrl = httpValue
                Log.e(TAG, "HQCloud found http value -> finalUrl=$finalUrl")
                break
            }
        }

        if (finalUrl.isEmpty()) {
            Log.e(TAG, "HQCloud no url found in var objects, trying m3u8 regex fallback")
            val m3u8Match = Regex("""["']([^"']*m3u8[^"']*)["']""").find(unpacked)
            if (m3u8Match != null) {
                finalUrl = m3u8Match.groupValues[1]
                if (finalUrl.startsWith("/")) finalUrl = baseUrl + finalUrl
                Log.e(TAG, "HQCloud m3u8 fallback matched finalUrl=$finalUrl")
            } else {
                Log.e(TAG, "HQCloud m3u8 fallback no match")
            }
        }

        if (finalUrl.isEmpty()) {
            Log.e(TAG, "HQCloud FAILED: finalUrl empty")
            return
        }

        val cookieString = buildString {
            append("file_id=$fileId; aff=$aff; tsn=7")
            if (refUrl != null) {
                append("; ref_url=${URLEncoder.encode(refUrl, "UTF-8")}")
            }
        }
        Log.e(TAG, "HQCloud cookieString=$cookieString")

        Log.e(TAG, "HQCloud EMITTING link url=$finalUrl")
        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = baseUrl
                this.headers = mutableMapOf("Cookie" to cookieString)
            }
        )
        Log.e(TAG, "===== END HQCloud SUCCESS =====")
    }
}

class HQLinks : HQCloud() {
    override var mainUrl = "https://hglink.to"
}

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
        Log.e(TAG, "===== START VkExtractor =====")
        Log.e(TAG, "Vk input url=$url referer=$referer")

        val commonUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to mainUrl,
        )

        var response = try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            Log.e(TAG, "Vk initial get FAILED", e)
            return
        }
        Log.e(TAG, "Vk initial response code=${response.code} length=${response.text.length}")

        if (response.text.contains("hash429") || response.text.contains("challenge.html")) {
            Log.e(TAG, "Vk challenge detected, retrying with WebViewResolver")
            response = try {
                app.get(url, interceptor = WebViewResolver(Regex(".*video_ext\\.php.*")), headers = headers)
            } catch (e: Exception) {
                Log.e(TAG, "Vk WebViewResolver retry FAILED", e)
                return
            }
            Log.e(TAG, "Vk webview response code=${response.code} length=${response.text.length}")
        }

        val foundLinks = linkcikart(response.text, commonUserAgent, callback)
        Log.e(TAG, "Vk linkcikart foundLinks=$foundLinks")

        if (!foundLinks && url.contains("hash=")) {
            Log.e(TAG, "Vk no direct links, attempting API fallback with hash param")

            val oid = Regex("""oid=([^&]+)""").find(url)?.groupValues?.get(1)
            val id = Regex("""id=([^&]+)""").find(url)?.groupValues?.get(1)
            val hash = Regex("""hash=([^&]+)""").find(url)?.groupValues?.get(1)

            Log.e(TAG, "Vk oid=$oid id=$id hash=$hash")

            if (oid != null && id != null && hash != null) {
                val tokenRegex = Regex("""anonym\.eyJ[\w\.\-]+""")
                val fallbackTokenRegex = Regex(""""access_token"\s*:\s*"([^"]+)"""")

                val token = tokenRegex.find(response.text)?.value
                    ?: fallbackTokenRegex.find(response.text)?.groupValues?.get(1)
                Log.e(TAG, "Vk token=${token?.take(40)}...")

                if (token != null) {
                    val apiUrl = "https://api.vk.com/method/video.get?v=5.269&client_id=52461373"
                    val postData = mapOf(
                        "owner_id" to "",
                        "videos" to "${oid}_${id}_${hash}",
                        "extended" to "0",
                        "is_embed" to "true",
                        "track_code" to "",
                        "access_token" to token
                    )

                    val apiResponse = try {
                        app.post(apiUrl, headers = headers, data = postData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Vk API post FAILED", e)
                        return
                    }
                    Log.e(TAG, "Vk API response code=${apiResponse.code}")
                    Log.e(TAG, "Vk API response body=${apiResponse.text.take(1500)}")

                    val apiFound = linkcikart(apiResponse.text, commonUserAgent, callback)
                    Log.e(TAG, "Vk API linkcikart result=$apiFound")
                } else {
                    Log.e(TAG, "Vk FAILED: token not found")
                }
            } else {
                Log.e(TAG, "Vk FAILED: oid/id/hash missing")
            }
        }
        Log.e(TAG, "===== END VkExtractor =====")
    }

    private suspend fun linkcikart(
        text: String,
        userAgent: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "Vk linkcikart input text length=${text.length}")
        var foundAny = false

        val streamRegex = Regex(
            "\"(hls|hls_ondemand|dash|dash_sep|dash_ondemand)\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE
        )

        streamRegex.findAll(text).forEach { match ->
            val typeRaw = match.groupValues[1].lowercase()
            val videoUrl = match.groupValues[2].replace("\\", "")

            Log.e(TAG, "Vk linkcikart matched type=$typeRaw url=${videoUrl.take(200)}")

            if (videoUrl.isNotBlank()) {
                foundAny = true
                val isDash = typeRaw.contains("dash")
                val typeName = if (isDash) "Dash" else "HLS"
                val linkType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8

                Log.e(TAG, "Vk EMITTING typeName=$typeName url=${videoUrl.take(120)}")

                callback.invoke(
                    newExtractorLink(
                        "${this.name} $typeName",
                        "${this.name} $typeName",
                        videoUrl,
                        linkType
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to mainUrl
                        )
                    }
                )
            }
        }

        Log.e(TAG, "Vk linkcikart result foundAny=$foundAny")
        return foundAny
    }
}

class VkCom : VkExtractor() {
    override var mainUrl = "https://vk.com"
}

// ==========================================
// Byse Extractor (AES-GCM protected playback)
// ==========================================

open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String, label: String): ByteArray {
        Log.e(TAG, "Byse b64UrlDecode[$label] input=$s")
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        val decoded = Base64.getDecoder().decode(fixed + pad)
        Log.e(TAG, "Byse b64UrlDecode[$label] output length=${decoded.size}")
        return decoded
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "===== START ByseSX =====")
        Log.e(TAG, "Byse input url=$url referer=$referer")

        try {
            val uri = URI(url)
            val code = uri.path.trimEnd('/').substringAfterLast('/')
            val base = "${uri.scheme}://${uri.host}"

            Log.e(TAG, "Byse parsed code=$code base=$base")

            val detailsUrl = "$base/api/videos/$code/embed/details"
            Log.e(TAG, "Byse detailsUrl=$detailsUrl")

            val detailsResponse = try {
                app.get(detailsUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Byse details get FAILED", e)
                return
            }
            Log.e(TAG, "Byse detailsResponse code=${detailsResponse.code}")
            Log.e(TAG, "Byse detailsResponse body=${detailsResponse.text.take(1500)}")

            val details = detailsResponse.parsedSafe<ByseDetailsRoot>()
            if (details == null) {
                Log.e(TAG, "Byse FAILED: details parsedSafe returned null")
                return
            }
            Log.e(TAG, "Byse details id=${details.id} code=${details.code} title=${details.title}")
            Log.e(TAG, "Byse details embedFrameUrl=${details.embedFrameUrl}")

            val embedFrameUrl = details.embedFrameUrl
            if (embedFrameUrl.isBlank()) {
                Log.e(TAG, "Byse FAILED: embedFrameUrl blank")
                return
            }

            val embedUri = URI(embedFrameUrl)
            val embedBase = "${embedUri.scheme}://${embedUri.host}"
            val embedCode = embedUri.path.trimEnd('/').substringAfterLast('/')

            Log.e(TAG, "Byse embedBase=$embedBase embedCode=$embedCode")

            val headers = mapOf(
                "referer" to embedFrameUrl,
                "x-embed-parent" to url
            )
            Log.e(TAG, "Byse playback headers=$headers")

            val playbackUrl = "$embedBase/api/videos/$embedCode/embed/playback"
            Log.e(TAG, "Byse playbackUrl=$playbackUrl")

            val playbackResponse = try {
                app.get(playbackUrl, headers = headers)
            } catch (e: Exception) {
                Log.e(TAG, "Byse playback get FAILED", e)
                return
            }
            Log.e(TAG, "Byse playbackResponse code=${playbackResponse.code}")
            Log.e(TAG, "Byse playbackResponse body=${playbackResponse.text.take(1500)}")

            val playbackRoot = playbackResponse.parsedSafe<BysePlaybackRoot>()
            if (playbackRoot == null) {
                Log.e(TAG, "Byse FAILED: playbackRoot parsedSafe returned null")
                return
            }

            val playback = playbackRoot.playback
            Log.e(TAG, "Byse playback algorithm=${playback.algorithm}")
            Log.e(TAG, "Byse playback iv=${playback.iv}")
            Log.e(TAG, "Byse playback keyParts count=${playback.keyParts.size}")
            Log.e(TAG, "Byse playback keyParts=${playback.keyParts}")
            Log.e(TAG, "Byse playback payload length=${playback.payload.length}")

            if (playback.keyParts.size < 2) {
                Log.e(TAG, "Byse FAILED: keyParts size < 2")
                return
            }

            val keyPart0 = b64UrlDecode(playback.keyParts[0], "key0")
            val keyPart1 = b64UrlDecode(playback.keyParts[1], "key1")
            val key = keyPart0 + keyPart1
            Log.e(TAG, "Byse combined key length=${key.size}")

            if (key.size != 16 && key.size != 24 && key.size != 32) {
                Log.e(TAG, "Byse WARNING: AES key length ${key.size} is not 16/24/32")
            }

            val ivBytes = b64UrlDecode(playback.iv, "iv")
            Log.e(TAG, "Byse iv length=${ivBytes.size}")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, ivBytes)
            )

            val encryptedPayload = b64UrlDecode(playback.payload, "payload")
            Log.e(TAG, "Byse encryptedPayload length=${encryptedPayload.size}")

            val decrypted = cipher.doFinal(encryptedPayload)
            Log.e(TAG, "Byse decrypted length=${decrypted.size}")

            val jsonStr = String(decrypted, StandardCharsets.UTF_8)
                .let { if (it.startsWith("\uFEFF")) it.substring(1) else it }

            Log.e(TAG, "Byse decrypted JSON=${jsonStr.take(2000)}")

            val parsedDecrypt = tryParseJson<BysePlaybackDecrypt>(jsonStr)
            if (parsedDecrypt == null) {
                Log.e(TAG, "Byse FAILED: tryParseJson returned null")
                return
            }

            val sources = parsedDecrypt.sources
            Log.e(TAG, "Byse sources count=${sources.size}")

            if (sources.isEmpty()) {
                Log.e(TAG, "Byse WARNING: sources list empty")
            }

            sources.forEachIndexed { index, source ->
                Log.e(TAG, "Byse source[$index] quality=${source.quality} label=${source.label}")
                Log.e(TAG, "Byse source[$index] url=${source.url.take(200)}")

                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = source.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedFrameUrl
                        this.headers = mutableMapOf(
                            "Referer" to embedFrameUrl,
                            "Origin" to embedBase,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/133.0.0.0 Safari/537.36"
                        )
                    }
                )
            }

            Log.e(TAG, "===== END ByseSX SUCCESS =====")
        } catch (e: Exception) {
            Log.e(TAG, "Byse extraction FAILED: ${e.message}", e)
            Log.e(TAG, "Byse stack trace: ${Log.getStackTraceString(e)}")
        }
    }
}

// ---- Byse rotating domain subclasses ----
// These ensure Cloudstream's URL matcher recognises the extractor for common Byse domains.
class Bysefujedu : ByseSX() { override var mainUrl = "https://bysefujedu.com" }
class Bysebimahe : ByseSX() { override var mainUrl = "https://bysebimahe.com" }
class Bysebuho   : ByseSX() { override var mainUrl = "https://bysebuho.com" }
class Bysefast   : ByseSX() { override var mainUrl = "https://bysefast.com" }
class Bysenexo   : ByseSX() { override var mainUrl = "https://bysenexo.com" }
class Bysewiwo   : ByseSX() { override var mainUrl = "https://bysewiwo.com" }
class Bysevipa   : ByseSX() { override var mainUrl = "https://bysevipa.com" }
class Bysedopo   : ByseSX() { override var mainUrl = "https://bysedopo.com" }
class Bysekuwo   : ByseSX() { override var mainUrl = "https://bysekuwo.com" }
class Bysetego   : ByseSX() { override var mainUrl = "https://bysetego.com" }
class Byseroxo   : ByseSX() { override var mainUrl = "https://byseroxo.com" }
class Bysejaro   : ByseSX() { override var mainUrl = "https://bysejaro.com" }

// ==========================================
// Byse Data Classes
// ==========================================

data class ByseDetailsRoot(
    val id: Long,
    val code: String,
    val title: String,
    @JsonProperty("poster_url") val posterUrl: String,
    val description: String,
    @JsonProperty("embed_frame_url") val embedFrameUrl: String
)

data class BysePlaybackRoot(val playback: BysePlayback)
data class BysePlayback(
    val algorithm: String,
    val iv: String,
    val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>
)

data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>)
data class BysePlaybackSource(
    val quality: String,
    val label: String,
    val url: String
)
