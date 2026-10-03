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
        val path = Regex("""(https?://[^/]+)(/[^?]+)""").find(url)?.groupValues?.get(2) ?: ""

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
            try {
                val response = app.get(newUrl, referer = "https://hgcloud.to/")
                if (response.text.length > 2000) {
                    html = response.text
                    baseUrl = "https://$domain"
                    break
                }
            } catch (e: Exception) {
                Log.e("HQCloud", "Domain fail: $domain", e)
            }
        }

        if (html.length < 2000) return

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
            val word = i.toString(36)
            if (k[i].isNotEmpty()) {
                unpacked = unpacked.replace(Regex("\\b$word\\b"), k[i])
            }
        }

        var finalUrl = ""
        val varObjRegex = Regex("""var\s+\w+\s*=\s*\{([^}]*)\}""")
        val varMatches = varObjRegex.findAll(unpacked).toList()

        for (vm in varMatches) {
            val objBody = vm.groupValues[1]
            if (!objBody.contains("http")) continue

            val values = Regex(""":\s*"([^"]+)"""")
                .findAll(objBody)
                .map { it.groupValues[1] }
                .toList()

            val pathValue = values.firstOrNull {
                it.startsWith("/") && !it.startsWith("/dl") && !it.startsWith("/assets")
            }

            if (pathValue != null) {
                finalUrl = baseUrl + pathValue
                break
            }

            val httpValue = values.firstOrNull { it.startsWith("http") }
            if (httpValue != null) {
                finalUrl = httpValue
                break
            }
        }

        if (finalUrl.isEmpty()) {
            val m3u8Match = Regex("""["']([^"']*m3u8[^"']*)["']""").find(unpacked)
            if (m3u8Match != null) {
                finalUrl = m3u8Match.groupValues[1]
                if (finalUrl.startsWith("/")) finalUrl = baseUrl + finalUrl
            }
        }

        if (finalUrl.isEmpty()) return

        val cookieString = buildString {
            append("file_id=$fileId; aff=$aff; tsn=7")
            if (refUrl != null) {
                append("; ref_url=${URLEncoder.encode(refUrl, "UTF-8")}")
            }
        }

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
        val commonUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to mainUrl,
        )

        var response = app.get(url, headers = headers)

        if (response.text.contains("hash429") || response.text.contains("challenge.html")) {
            response = app.get(url, interceptor = WebViewResolver(Regex(".*video_ext\\.php.*")), headers = headers)
        }

        val foundLinks = linkcikart(response.text, commonUserAgent, callback)
        if (!foundLinks && url.contains("hash=")) {
            val oid = Regex("""oid=([^&]+)""").find(url)?.groupValues?.get(1)
            val id = Regex("""id=([^&]+)""").find(url)?.groupValues?.get(1)
            val hash = Regex("""hash=([^&]+)""").find(url)?.groupValues?.get(1)

            if (oid != null && id != null && hash != null) {
                val tokenRegex = Regex("""anonym\.eyJ[\w\.\-]+""")
                val fallbackTokenRegex = Regex(""""access_token"\s*:\s*"([^"]+)"""")

                val token = tokenRegex.find(response.text)?.value
                    ?: fallbackTokenRegex.find(response.text)?.groupValues?.get(1)

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

                    val apiResponse = app.post(apiUrl, headers = headers, data = postData)
                    linkcikart(apiResponse.text, commonUserAgent, callback)
                }
            }
        }
    }

    private suspend fun linkcikart(
        text: String,
        userAgent: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        val streamRegex = Regex("\"(hls|hls_ondemand|dash|dash_sep|dash_ondemand)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        streamRegex.findAll(text).forEach { match ->
            val typeRaw = match.groupValues[1].lowercase()
            val videoUrl = match.groupValues[2].replace("\\", "")

            if (videoUrl.isNotBlank()) {
                foundAny = true
                val isDash = typeRaw.contains("dash")
                val typeName = if (isDash) "Dash" else "HLS"
                val linkType = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8

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

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.getDecoder().decode(fixed + pad)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = URI(url).path.trimEnd('/').substringAfterLast('/')
            val base = URI(url).let { "${it.scheme}://${it.host}" }

            val details = app.get("$base/api/videos/$code/embed/details")
                .parsedSafe<ByseDetailsRoot>() ?: return

            val embedFrameUrl = details.embedFrameUrl
            val embedBase = URI(embedFrameUrl).let { "${it.scheme}://${it.host}" }
            val embedCode = URI(embedFrameUrl).path.trimEnd('/').substringAfterLast('/')

            val headers = mapOf(
                "referer" to embedFrameUrl,
                "x-embed-parent" to url
            )

            val playback = app.get(
                "$embedBase/api/videos/$embedCode/embed/playback",
                headers = headers
            ).parsedSafe<BysePlaybackRoot>()?.playback ?: return

            val key = b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1])

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, b64UrlDecode(playback.iv))
            )

            val decrypted = cipher.doFinal(b64UrlDecode(playback.payload))
            val jsonStr = String(decrypted, StandardCharsets.UTF_8)
                .let { if (it.startsWith("\uFEFF")) it.substring(1) else it }

            val sources = tryParseJson<BysePlaybackDecrypt>(jsonStr)?.sources ?: emptyList()

            sources.forEach { source ->
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
        } catch (e: Exception) {
            Log.e("ByseSX", "Extraction failed: ${e.message}")
        }
    }
}

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
