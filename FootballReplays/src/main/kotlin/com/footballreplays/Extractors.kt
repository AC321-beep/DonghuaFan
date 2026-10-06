package com.footballreplays

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val BYSE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
private const val TAG = "FootballReplays"

// ==========================================
// Inbuilt Cloudstream Core Overrides
// ==========================================
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
        return runCatching {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1)
                ?: url.substringAfterLast("/").substringBefore("?")
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

                    val cleanUrl = vidUrl.replace("\\u0026", "&").replace("\\/", "/")
                    links.add(
                        newExtractorLink(name = "$name MP4", source = "$name MP4", url = cleanUrl, type = INFER_TYPE) {
                            this.referer = "https://ok.ru/"
                            this.quality = qualityValue
                        }
                    )
                }
            }

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                val cleanHls = hlsUrl.replace("\\u0026", "&").replace("\\/", "/")
                links.addAll(M3u8Helper.generateM3u8("$name HLS", cleanHls, url))
            }

            links.ifEmpty { null }
        }.getOrNull()
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
        runCatching {
            val id = Regex("""v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
            val api = "https://videa.hu/videaplayer_get_res.php?v=$id"
            val response = app.get(api).text

            for (match in Regex("""quality="([^"]+)".*?mp4="([^"]+)"""").findAll(response)) {
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
                    newExtractorLink(source = name, name = "$name $q", url = link, type = INFER_TYPE) {
                        this.referer = mainUrl
                        this.quality = qualityValue
                    }
                )
            }
        }.onFailure { Log.e(TAG, "VideaHu failed: ${it.message}") }
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
        Log.d(TAG, "===== START HQCloud =====")
        val path = Regex("""(https?://[^/]+)(/[^?]+)""").find(url)?.groupValues?.get(2) ?: run {
            Log.e(TAG, "HQCloud FAILED: no path")
            return
        }
        val domains = listOf("audinifer.com", "vibuxere.com", "streamhg.com", "dhcplay.com", "cybervynx.com")

        var html = ""
        var baseUrl = ""

        for (domain in domains) {
            val newUrl = "https://$domain$path"
            try {
                val response = app.get(newUrl, referer = "https://hgcloud.to/", interceptor = CFInterceptor())
                if (response.text.length > 2000) {
                    html = response.text
                    baseUrl = "https://$domain"
                    Log.d(TAG, "HQCloud SUCCESS domain=$domain len=${response.text.length}")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "HQCloud domain fail $domain", e)
            }
        }

        if (html.length < 2000) {
            Log.e(TAG, "HQCloud FAILED: no html")
            return
        }

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

        if (finalUrl.isEmpty()) {
            Log.e(TAG, "HQCloud FAILED: no finalUrl")
            return
        }

        val cookieString = buildString {
            append("file_id=$fileId; aff=$aff; tsn=7")
            if (refUrl != null) append("; ref_url=${URLEncoder.encode(refUrl, "UTF-8")}")
        }

        callback.invoke(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
            this.referer = baseUrl
            this.headers = mutableMapOf("Cookie" to cookieString)
        })
        Log.d(TAG, "===== END HQCloud SUCCESS =====")
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
        var response = runCatching { app.get(url, headers = headers) }.getOrNull() ?: return

        if (response.text.contains("hash429") || response.text.contains("challenge.html")) {
            response = runCatching {
                app.get(url, interceptor = WebViewResolver(Regex(".*video_ext\\.php.*")), headers = headers)
            }.getOrNull() ?: return
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

    private suspend fun linkcikart(text: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        for (m in Regex("\"(hls|hls_ondemand|dash|dash_sep|dash_ondemand)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(text)) {
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
        return runCatching {
            var videoId = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            val shortId = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                ?: if (videoId == null) url.substringAfterLast("/").takeIf { it.isNotBlank() } else null

            val lookupId = shortId ?: videoId
            if (lookupId != null) {
                runCatching {
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
                }
            }

            if (videoId != null) {
                val nasNodes = listOf("nas1", "nas2", "nas3", "nas4", "video", "ipfs")
                for (node in nasNodes) {
                    val m3u8Url = "https://$node.d.tube/videos/$videoId/master.m3u8"
                    runCatching {
                        if (app.get(m3u8Url).isSuccessful) {
                            return M3u8Helper.generateM3u8(name, m3u8Url, url)
                        }
                    }
                }
            }
            null
        }.getOrNull()
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
        return runCatching {
            val response = app.get(url, referer = mainUrl).document
            val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
            val unpacked = JsUnpacker(script).unpack() ?: return null
            val link = Regex("""sources:\s*\[\s*\{\s*file:\s*['"](.*?)['"]""").find(unpacked)?.groupValues?.get(1) ?: return null

            listOf(
                newExtractorLink(source = name, name = name, url = link, type = ExtractorLinkType.M3U8) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }.getOrNull()
    }
}

// ==========================================
// Byse Models & Extractor
// ==========================================
@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackRoot(
    @JsonProperty("playback") val playback: BysePlayback = BysePlayback()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlayback(
    @JsonProperty("algorithm") val algorithm: String = "",
    @JsonProperty("iv") val iv: String = "",
    @JsonProperty("payload") val payload: String = "",
    @JsonProperty("key_parts") val keyParts: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BysePlaybackDecrypt(
    @JsonProperty("sources") val sources: List<ByseSource> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ByseSource(
    @JsonProperty("file") val url: String = "",
    @JsonProperty("quality") val quality: String = ""
)

open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = "=".repeat((4 - fixed.length % 4) % 4)
        return Base64.decode(fixed + pad, Base64.DEFAULT)
    }

    private suspend fun emitFromPlaybackJson(
        playbackBody: String, embedFrameUrl: String, embedBase: String, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val root = tryParseJson<BysePlaybackRoot>(playbackBody) ?: return false
            val pb = root.playback
            if (pb.keyParts.size < 2) return false

            val key = b64(pb.keyParts[0]) + b64(pb.keyParts[1])
            val iv = b64(pb.iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))

            val decrypted = cipher.doFinal(b64(pb.payload))
            val json = String(decrypted, StandardCharsets.UTF_8).let { if (it.startsWith("\uFEFF")) it.substring(1) else it }
            val parsed = tryParseJson<BysePlaybackDecrypt>(json) ?: return false

            for (s in parsed.sources) {
                if (s.url.isBlank()) continue
                Log.d(TAG, "Byse Fast AES src q=${s.quality} url=${s.url.take(150)}")
                callback.invoke(newExtractorLink(name, name, s.url, ExtractorLinkType.M3U8) {
                    this.referer = embedFrameUrl
                    this.headers = mapOf("Referer" to embedFrameUrl, "Origin" to embedBase, "User-Agent" to BYSE_UA)
                })
            }
            parsed.sources.isNotEmpty()
        }.getOrDefault(false)
    }

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "===== START ByseSX =====")
        val uri = URI(url)
        val code = uri.path.trimEnd('/').substringAfterLast('/')
        val base = "${uri.scheme}://${uri.host}"
        val shellUrl = "$base/e/$code"
        val headers = mapOf("Referer" to "https://www.footreplays.com/", "User-Agent" to BYSE_UA)

        // Stage 1: Try Fast AES Decryption first
        val html = runCatching {
            app.get(shellUrl, headers = headers).text
        }.getOrDefault("")

        val algorithm = Regex(""""algorithm"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val iv = Regex(""""iv"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val payload = Regex(""""payload"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val keyPartsStr = Regex(""""key_parts"\s*:\s*\[(.*?)\]"""").find(html)?.groupValues?.get(1)

        if (algorithm != null && iv != null && payload != null && keyPartsStr != null) {
            val keyParts = keyPartsStr.split(",").map { it.replace("\"", "").trim() }
            val pbJson = """{"playback":{"algorithm":"$algorithm","iv":"$iv","payload":"$payload","key_parts":[${keyParts.joinToString(",") { "\"$it\"" }}]}}"""
            if (emitFromPlaybackJson(pbJson, shellUrl, base, callback)) {
                Log.d(TAG, "===== END ByseSX SUCCESS via Fast AES =====")
                return
            }
        }

        // Stage 2: Fallback to Cloudstream's native WebViewResolver
        Log.d(TAG, "Byse: Fast AES failed, falling back to native WebViewResolver")
        
        val interceptor = WebViewResolver(
            interceptUrl = Regex("""\.m3u8|\.m3u""")
        )

        try {
            val response = app.get(shellUrl, headers = headers, interceptor = interceptor)
            
            val resolvedUrl = response.url
            if (resolvedUrl.contains(".m3u8") || resolvedUrl.contains(".m3u")) {
                callback.invoke(newExtractorLink(name, name, resolvedUrl, ExtractorLinkType.M3U8) {
                    this.referer = shellUrl
                    this.headers = mapOf("Referer" to shellUrl, "User-Agent" to BYSE_UA)
                })
                Log.d(TAG, "===== END ByseSX SUCCESS via WebViewResolver =====")
            } else {
                Log.e(TAG, "ByseSX Failed: Intercepted URL was not an m3u8 stream: $resolvedUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ByseSX Interceptor failed", e)
        }
    }
}
