package com.footballreplays

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.Voe
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal const val TAG = "FootballReplays"

private const val BYSE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

private val CHROME_HINTS = mapOf(
    "sec-ch-ua" to "\"Chromium\";v=\"154\", \"Google Chrome\";v=\"154\", \"Not A(Brand\";v=\"99\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Windows\"",
    "accept-language" to "en-US,en;q=0.9",
    "cache-control" to "no-cache",
    "pragma" to "no-cache",
    "priority" to "u=1, i"
)

// ==========================================
// Inbuilt Cloudstream Core Overrides
// ==========================================
class FileMoonSx : Filesim() {
    override val name = "FileMoonSx"
    override val mainUrl = "https://filemoon.sx"
}

class Waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class Wishfast : StreamWishExtractor() {
    override val name = "StreamWish"
    override val mainUrl = "https://wishfast.top"
}

class VidhidePlus : StreamWishExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhideplus.com"
}

class VidhideHub : StreamWishExtractor() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhidehub.com"
}

class Dhtpre : StreamWishExtractor() {
    override val name = "Dhtpre"
    override val mainUrl = "https://dhtpre.com"
}

class DoodLi : DoodLaExtractor() {
    override var mainUrl = "https://dood.li"
}

class VoeSx : Voe() {
    override var mainUrl = "https://voe.sx"
}

// ==========================================
// Custom OkRu Extractor
// ==========================================
class OkRu : ExtractorApi() {
    override val name = "OkRu" 
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return null

            val jsonStr = app.post("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id").text
            if (!jsonStr.startsWith("{")) return null
            val json = JSONObject(jsonStr)

            val links = mutableListOf<ExtractorLink>()
            val videos = json.optJSONArray("videos")
            
            if (videos != null && videos.length() > 0) {
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    val qName = video.optString("name").lowercase()
                    val vidUrl = video.optString("url")

                    if (vidUrl.isBlank() || vidUrl.contains("usr_login")) continue

                    val qualityValue = when (qName) {
                        "mobile" -> Qualities.P144.value
                        "lowest" -> Qualities.P240.value
                        "low" -> Qualities.P360.value
                        "sd" -> Qualities.P480.value
                        "hd" -> Qualities.P720.value
                        "full" -> Qualities.P1080.value
                        "quad" -> Qualities.P1440.value
                        "ultra" -> Qualities.P2160.value
                        else -> Qualities.Unknown.value
                    }

                    links.add(
                        newExtractorLink(name = "${this.name} MP4", source = "${this.name} MP4", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                            this.referer = "https://ok.ru/"
                            this.quality = qualityValue
                        }
                    )
                }
            }

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                links.addAll(M3u8Helper.generateM3u8("$name HLS", hlsUrl.replace("\\u0026", "&").replace("\\/", "/"), url))
            }

            return links
        } catch (e: Exception) {
            return null
        }
    }
}

// ==========================================
// Custom Videa Extractor
// ==========================================
class VideaHu : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = Regex("""v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
            val api = "https://videa.hu/videaplayer_get_res.php?v=$id"
            val response = app.get(api).text
            
            Regex("""quality="([^"]+)".*?mp4="([^"]+)"""").findAll(response).forEach { match ->
                val q = match.groupValues[1]
                var link = match.groupValues[2]
                if (link.startsWith("//")) link = "https:$link"
                
                val qualityValue = when (q) {
                    "360p" -> Qualities.P360.value
                    "480p" -> Qualities.P480.value
                    "720p" -> Qualities.P720.value
                    "1080p" -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $q",
                        url = link,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.quality = qualityValue
                    }
                )
            }
        } catch (e: Exception) { }
    }
}

// ==========================================
// HQCloud & HQLinks Extractors
// ==========================================
open class HQCloud : ExtractorApi() {
    override val name = "HQCloud"
    override val mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val path = Regex("""(https?://[^/]+)(/[^?]+)""").find(url)?.groupValues?.get(2) ?: return
        val domains = listOf("audinifer.com", "vibuxere.com", "streamhg.com", "dhcplay.com", "cybervynx.com")

        var html = ""
        var baseUrl = ""

        for (domain in domains) {
            val newUrl = "https://$domain$path"
            try {
                val response = app.get(newUrl, referer = "https://hgcloud.to/")
                if (response.text.length > 2000) {
                    html = response.text; baseUrl = "https://$domain"
                    break
                }
            } catch (e: Exception) { }
        }

        if (html.length < 2000) return
        val fileId = Regex("""\$\.cookie\('file_id',\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: return
        val aff = Regex("""\$\.cookie\('aff',\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: ""
        val refUrl = Regex("""\$\.cookie\('ref_url',\s*'([^']+)'""").find(html)?.groupValues?.get(1)

        val packerRegex = Regex("""(?s)eval\(function\(p,a,c,k,e,d\)\{.*?\}\('((?:[^'\\]|\\.)*)',\s*\d+,\s*\d+,\s*'((?:[^'\\]|\\.)*)'\s*(?:\.split\('\|'\))?\)""")
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
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to BYSE_UA, "Referer" to mainUrl)
        var response = try { app.get(url, headers = headers) } catch (e: Exception) { return }
        
        if (response.text.contains("hash429") || response.text.contains("challenge.html")) {
            response = try {
                app.get(url, interceptor = WebViewResolver(Regex(".*video_ext\\.php.*")), headers = headers)
            } catch (e: Exception) { return }
        }

        val found = linkcikart(response.text, callback)
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
                        data = mapOf("owner_id" to "", "videos" to "${oid}_${id}_${hash}", "extended" to "0", "is_embed" to "true", "track_code" to "", "access_token" to token)
                    )
                    linkcikart(apiResp.text, callback)
                }
            }
        }
    }

    // FIXED: Added "suspend" keyword here
    private suspend fun linkcikart(text: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        Regex("\"(hls|hls_ondemand|dash|dash_sep|dash_ondemand)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val t = m.groupValues[1].lowercase()
            val u = m.groupValues[2].replace("\\", "")
            if (u.isNotBlank()) {
                any = true
                val isDash = t.contains("dash")
                callback.invoke(newExtractorLink("${name} ${if (isDash) "Dash" else "HLS"}", "${name} ${if (isDash) "Dash" else "HLS"}", u, if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.headers = mapOf("User-Agent" to BYSE_UA, "Referer" to mainUrl)
                })
            }
        }
        return any
    }
}
class VkCom : VkExtractor() { override var mainUrl = "https://vk.com" }

// ==========================================
// DTube Extractor
// ==========================================
class Dtube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            var videoId = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            val shortId = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: if (videoId == null) url.substringAfterLast("/").takeIf { it.isNotBlank() } else null

            val lookupId = shortId ?: videoId
            if (lookupId != null) {
                try {
                    val apiResponse = app.get("https://api.d.tube/videos/$lookupId").text
                    if (apiResponse.startsWith("{")) {
                        val json = JSONObject(apiResponse)
                        videoId = json.optString("_id").takeIf { it.isNotBlank() }
                            ?: json.optString("id").takeIf { it.isNotBlank() }
                            ?: json.optString("uuid").takeIf { it.isNotBlank() }
                            ?: videoId

                        val directHls = json.optString("hlsUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("manifestUrl").takeIf { it.isNotBlank() }
                            ?: json.optString("gatewayUrl").takeIf { it.isNotBlank() }

                        if (!directHls.isNullOrBlank()) {
                            return M3u8Helper.generateM3u8(name, directHls, url)
                        }
                    }
                } catch (e: Exception) { }
            }

            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video", "ipfs")
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    try {
                        if (app.get(m3u8Url).isSuccessful) {
                            return M3u8Helper.generateM3u8(name, m3u8Url, url)
                        }
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
        return null
    }
} 

// ==========================================
// Vtbe Extractor
// ==========================================
class Vtbe : ExtractorApi() {
    override val name = "Vtbe"
    override val mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val response = app.get(url, referer = mainUrl).document
            val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
            val unpacked = JsUnpacker(script).unpack() ?: return null
            val link = Regex("""sources:\s*\[\s*\{\s*file:\s*['"](.*?)['"]""").find(unpacked)?.groupValues?.get(1) ?: return null

            // FIXED: Using newExtractorLink instead of deprecated ExtractorLink constructor
            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            return null
        }
    }
}

// ==========================================
// Byse Extractor
// ==========================================
open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64(s: String, label: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.getDecoder().decode(fixed + pad)
    }

    private fun findString(json: JSONObject, vararg keySubstrings: String): String? {
        val stack = ArrayDeque<JSONObject>()
        stack.addLast(json)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val keys = cur.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = cur.opt(k)
                val lowerK = k.lowercase()
                if (v is String && keySubstrings.any { lowerK.contains(it.lowercase()) }) return v
                if (v is JSONObject) stack.addLast(v)
                if (v is org.json.JSONArray) {
                    for (i in 0 until v.length()) {
                        val item = v.opt(i)
                        if (item is JSONObject) stack.addLast(item)
                    }
                }
            }
        }
        return null
    }

    private data class AttestData(
        val captchaToken: String?, val fingerprintToken: String?, val viewerId: String?, val deviceId: String?, val raw: String
    )

    private fun parseAttest(body: String): AttestData? {
        return try {
            val j = JSONObject(body)
            AttestData(
                findString(j, "captcha_token", "captchaToken", "x-captcha-token", "captcha"),
                findString(j, "fingerprint_token", "fingerprintToken", "fingerprint"),
                findString(j, "viewer_id", "viewerId"),
                findString(j, "device_id", "deviceId"),
                body
            )
        } catch (e: Exception) { null }
    }

    private suspend fun emitFromPlaybackJson(
        playbackBody: String, embedFrameUrl: String, embedBase: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val root = tryParseJson<BysePlaybackRoot>(playbackBody) ?: return false
        val pb = root.playback

        if (pb.keyParts.size < 2) return false
        val key = b64(pb.keyParts[0], "k0") + b64(pb.keyParts[1], "k1")
        val iv = b64(pb.iv, "iv")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))

        val decrypted = cipher.doFinal(b64(pb.payload, "payload"))
        val json = String(decrypted, StandardCharsets.UTF_8).let { if (it.startsWith("\uFEFF")) it.substring(1) else it }
        val parsed = tryParseJson<BysePlaybackDecrypt>(json) ?: return false

        parsed.sources.forEach { s ->
            callback.invoke(newExtractorLink(name, name, s.url, ExtractorLinkType.M3U8) {
                this.referer = embedFrameUrl
                this.headers = mutableMapOf("Referer" to embedFrameUrl, "Origin" to embedBase, "User-Agent" to BYSE_UA)
            })
        }
        return parsed.sources.isNotEmpty()
    }

    private fun chromeHeaders(
        origin: String, referer: String, embedParent: String, contentTypeJson: Boolean = false
    ): Map<String, String> {
        val h = mutableMapOf<String, String>()
        h.putAll(CHROME_HINTS)
        h["Accept"] = if (contentTypeJson) "application/json, text/plain, */*" else "*/*"
        h["Origin"] = origin
        h["Referer"] = referer
        h["User-Agent"] = BYSE_UA
        h["x-embed-origin"] = "footreplays.com"
        h["x-embed-parent"] = embedParent
        h["x-embed-referer"] = "https://www.footreplays.com/"
        if (contentTypeJson) h["Content-Type"] = "application/json"
        return h
    }

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val uri = URI(url)
        val code = uri.path.trimEnd('/').substringAfterLast('/')
        val base = "${uri.scheme}://${uri.host}"
        val shellUrl = "$base/e/$code"

        // Strategy 1: Intercept the JSON playback payload via WebView
        try {
            val wvPlayback = app.get(
                shellUrl,
                interceptor = WebViewResolver(Regex(""".*/playback.*"""), timeout = 15000),
                headers = mapOf("Referer" to "https://www.footreplays.com/", "User-Agent" to BYSE_UA)
            )
            val body = wvPlayback.text
            if (body.contains("\"playback\"") || body.contains("\"key_parts\"")) {
                val json = if (body.contains("\"playback\"")) body else "{\"playback\":$body}"
                if (emitFromPlaybackJson(json, shellUrl, base, callback)) return
            }
        } catch (e: Exception) { Log.e(TAG, "Byse Strategy 1 failed", e) }

        // Strategy 2: Intercept the m3u8 directly via WebView
        try {
            val wvM3u8 = app.get(
                shellUrl,
                interceptor = WebViewResolver(Regex(""".*\.m3u8.*"""), timeout = 15000),
                headers = mapOf("Referer" to "https://www.footreplays.com/", "User-Agent" to BYSE_UA)
            )
            if (wvM3u8.url.contains(".m3u8")) {
                callback.invoke(newExtractorLink(name, name, wvM3u8.url, ExtractorLinkType.M3U8) {
                    this.referer = base
                    this.headers = mapOf("Origin" to base, "Referer" to base)
                })
                return
            }
        } catch (e: Exception) { Log.e(TAG, "Byse Strategy 2 failed", e) }

        // Strategy 3: API Fallback via Kotlin App HTTP requests
        try {
            val detailsHeaders = chromeHeaders(base, shellUrl, shellUrl, false)
            val detailsResp = app.get("$base/api/videos/$code/embed/details", headers = detailsHeaders)
            val embedFrameUrl = detailsResp.parsedSafe<ByseDetailsRoot>()?.embedFrameUrl ?: shellUrl
            val embedBase = try { "${URI(embedFrameUrl).scheme}://${URI(embedFrameUrl).host}" } catch (e: Exception) { base }

            val attestResp = app.get("$embedBase/access/attest", headers = detailsHeaders)
            val attest = parseAttest(attestResp.text)

            if (attest != null && attest.captchaToken != null) {
                val playbackHeaders = detailsHeaders + mapOf("x-captcha-token" to attest.captchaToken, "Content-Type" to "application/json")
                val bodyMap = mapOf(
                    "fingerprint" to mapOf("token" to (attest.fingerprintToken ?: ""), "viewer_id" to (attest.viewerId ?: ""), "device_id" to (attest.deviceId ?: ""), "confidence" to 0.77)
                )
                val pbResp = app.post("$embedBase/api/videos/$code/embed/playback", headers = playbackHeaders, json = bodyMap)
                if (pbResp.text.contains("\"key_parts\"")) {
                    val json = if (pbResp.text.contains("\"playback\"")) pbResp.text else "{\"playback\":${pbResp.text}}"
                    if (emitFromPlaybackJson(json, embedFrameUrl, embedBase, callback)) return
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Byse Strategy 3 failed", e) }
    }
}

// Byse Data Classes
@JsonIgnoreProperties(ignoreUnknown = true)
data class ByseDetailsRoot(
    val id: Long, val code: String, val title: String,
    @JsonProperty("poster_url") val posterUrl: String,
    val description: String,
    @JsonProperty("embed_frame_url") val embedFrameUrl: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackRoot(val playback: BysePlayback)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlayback(
    val algorithm: String, val iv: String, val payload: String,
    @JsonProperty("key_parts") val keyParts: List<String>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackDecrypt(val sources: List<BysePlaybackSource>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackSource(
    val quality: String, val label: String, val url: String
)
