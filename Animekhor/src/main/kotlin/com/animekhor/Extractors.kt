package com.animekhor

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ============================================================================
// CENTRALIZED FALLBACK: Highly Robust JS & DOM Unpacker
// ============================================================================
private suspend fun manualJsUnpackExtraction(
    url: String,
    name: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit
) {
    val safeHeaders = headers.toMutableMap()
    if (!safeHeaders.containsKey("User-Agent")) {
        safeHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    }

    val response = try { app.get(url, headers = safeHeaders).text } catch (e: Exception) { return }
    
    val packedScript = Regex("""eval\(\s*function\s*\(p,a,c,k,e,[a-zA-Z0-9_]\).*?split\('\|'\).*?\)""").find(response)?.value
    val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response

    val m3u8Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
    val m3u8 = m3u8Regex.find(unpacked)?.groupValues?.get(1) 
        ?: Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(unpacked)?.groupValues?.get(1)

    if (m3u8 != null) {
        val cleanM3u8 = m3u8.replace("\\/", "/") 
        M3u8Helper.generateM3u8(name, cleanM3u8, url, headers = safeHeaders).forEach(callback)
    } else {
        val mp4Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val mp4 = mp4Regex.find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"']+\.(?:mp4|mkv)[^"']*)""").find(unpacked)?.groupValues?.get(1)
            
        if (mp4 != null) {
            val cleanMp4 = mp4.replace("\\/", "/")
            callback.invoke(
                newExtractorLink(name = name, source = name, url = cleanMp4, type = INFER_TYPE) {
                    this.referer = url
                }
            )
        }
    }
}

// ============================================================================
// CUSTOM OK.RU EXTRACTOR (Fixes Error 2004 & Avoids Restricted Links)
// ============================================================================
class OkRuCustom : ExtractorApi() {
    override val name = "OkRu Custom"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
            val html = app.get(url, headers = headers).text
            
            val dataOptionsStr = Regex("""data-options=(?:"|')(\{(?:.*?|\\+.)\})(?:"|')""").find(html)?.groupValues?.get(1)
                ?.replace("&quot;", "\"") ?: Regex("""data-options\s*=\s*'(\{.*?\})'""").find(html)?.groupValues?.get(1) ?: return

            val hlsMatch = Regex("""(?:\\"|")hlsManifestUrl(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|")""").find(dataOptionsStr)
            if (hlsMatch != null) {
                val hlsUrl = hlsMatch.groupValues[1].replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
                if (!hlsUrl.contains("usr_login")) {
                    M3u8Helper.generateM3u8(name, hlsUrl, url).forEach(callback)
                    return 
                }
            }

            val dashMatch = Regex("""(?:\\"|")dashManifestUrl(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|")""").find(dataOptionsStr)
            if (dashMatch != null) {
                val dashUrl = dashMatch.groupValues[1].replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
                if (!dashUrl.contains("usr_login")) {
                    callback(
                        newExtractorLink(name = name, source = "$name DASH", url = dashUrl, type = com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH) {
                            this.referer = "https://ok.ru/"
                        }
                    )
                    return 
                }
            }

            val videoRegex = Regex("""(?:\\"|")name(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|").*?(?:\\"|")url(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|")""")
            videoRegex.findAll(dataOptionsStr).forEach { match ->
                val qName = match.groupValues[1]
                val vidUrl = match.groupValues[2].replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/") 
                if (vidUrl.contains("usr_login")) return@forEach

                val qualityValue = when (qName) {
                    "mobile" -> Qualities.P144.value
                    "lowest" -> Qualities.P240.value
                    "low" -> Qualities.P360.value
                    "sd" -> Qualities.P480.value
                    "hd" -> Qualities.P720.value
                    "full" -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }
                
                callback(
                    newExtractorLink(name = this.name, source = "${this.name} $qName", url = vidUrl, type = INFER_TYPE) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    }
                )
            }
        } catch (e: Exception) { Log.e("OkRuCustom", "OkRu extraction failed: ${e.message}") }
    }
}

// ============================================================================
// ADVANCED ABYSS PLAYER EXTRACTOR (AES-256-CTR Decryption)
// ============================================================================
class AbyssPlayer : ExtractorApi() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
            "Referer" to (referer ?: mainUrl)
        )
        
        val response = try { app.get(url, headers = headers).text } catch (e: Exception) { return }

        // STRATEGY A: AES-256-CTR Decryption (Current Abyss/Hydrax Implementation)
        try {
            val encodedDataMatch = Regex("""const\s+datas\s*=\s*"([^"]+)"""").find(response)
            if (encodedDataMatch != null) {
                val encodedData = encodedDataMatch.groupValues[1]
                val decodedJsonBytes = Base64.decode(encodedData, Base64.DEFAULT)
                val decodedJsonString = String(decodedJsonBytes, Charsets.ISO_8859_1) // Decode as Latin-1 matching python script
                
                val data = JSONObject(decodedJsonString)
                val userId = data.opt("user_id")?.toString() ?: ""
                val slug = data.optString("slug")
                val md5Id = data.opt("md5_id")?.toString() ?: ""
                val mediaStr = data.optString("media")

                // Generate MD5 Key
                val seed = "$userId:$slug:$md5Id"
                val md = MessageDigest.getInstance("MD5")
                val md5Hex = md.digest(seed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                // Key is 32 bytes, IV is the first 16 bytes
                val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
                val ivBytes = keyBytes.copyOfRange(0, 16)
                val encryptedMediaBytes = mediaStr.toByteArray(Charsets.ISO_8859_1)

                // Decrypt
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivSpec = IvParameterSpec(ivBytes)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                val decryptedBytes = cipher.doFinal(encryptedMediaBytes)
                val decryptedJsonString = String(decryptedBytes, Charsets.UTF_8)
                val metaData = JSONObject(decryptedJsonString)

                // Extract Final URL
                var videoUrl = metaData.optString("url").ifBlank { metaData.optString("file") }
                
                if (videoUrl.isNotBlank()) {
                    videoUrl = videoUrl.replace("\\/", "/")
                    if (videoUrl.contains(".m3u8") || metaData.optString("type").contains("hls", true)) {
                        M3u8Helper.generateM3u8(name, videoUrl, url, headers = headers).forEach(callback)
                    } else {
                        callback(newExtractorLink(name = name, source = name, url = videoUrl, type = INFER_TYPE) { this.referer = url })
                    }
                    return // Decryption successful, stop executing.
                }
            }
        } catch (e: Exception) {
            Log.e("AbyssPlayer", "AES Decryption failed: ${e.message}")
        }

        // STRATEGY B: Legacy Fallback (JS Unpacking)
        try {
            val packedScript = Regex("""eval\(\s*function\s*\(p,a,c,k,e,[a-zA-Z0-9_]\).*?split\('\|'\).*?\)""").find(response)?.value
            val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response

            val fileRegex = Regex("""(?:file|src|url)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val matches = fileRegex.findAll(unpacked).toList()

            if (matches.isNotEmpty()) {
                matches.forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    if (videoUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, videoUrl, url, headers = headers).forEach(callback)
                    } else {
                        callback(newExtractorLink(name = name, source = name, url = videoUrl, type = INFER_TYPE) { this.referer = url })
                    }
                }
            } else {
                val jsonMatch = Regex("""JSON\.parse\(['"]([A-Za-z0-9+/=]+)['"]\)""").find(unpacked)
                if (jsonMatch != null) {
                    val decodedJson = String(Base64.decode(jsonMatch.groupValues[1], Base64.DEFAULT))
                    fileRegex.findAll(decodedJson).forEach { match ->
                        val videoUrl = match.groupValues[1].replace("\\/", "/")
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(name, videoUrl, url, headers = headers).forEach(callback)
                        } else {
                            callback(newExtractorLink(name = name, source = name, url = videoUrl, type = INFER_TYPE) { this.referer = url })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AbyssPlayer", "Fallback JS Unpack failed: ${e.message}")
        }
    }
}

// ============================================================================
// STANDARD BUILT-IN EXTRACTORS
// ============================================================================

class Embedwish : StreamWishExtractor() {
    override var name = "Embedwish"
    override var mainUrl = "https://embedwish.com"
}

class Filelions : StreamWishExtractor() {
    override var name = "Filelions"
    override var mainUrl = "https://filelions.live"
}

class Swhoi : StreamWishExtractor() {
    override var name = "Swhoi"
    override var mainUrl = "https://swhoi.com"
    override val requiresReferer = true
}

class VidHidePro5 : VidHidePro() {
    override var name = "VidHidePro"
    override val mainUrl = "https://vidhidevip.com"
    override val requiresReferer = true
}

// ============================================================================
// DIRECT CUSTOM EXTRACTORS
// ============================================================================

class P2pstream : ExtractorApi() {
    override var name = "P2pstream"
    override var mainUrl = "https://animekhor.p2pstream.vip"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        val headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
        manualJsUnpackExtraction(fixedUrl, name, headers, callback)
    }
}

class UpnsLive : ExtractorApi() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        val headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
        manualJsUnpackExtraction(fixedUrl, name, headers, callback)
    }
}

class Bysekoze : ExtractorApi() {
    override var name = "VGPlayer"
    override var mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
        manualJsUnpackExtraction(url, name, headers, callback)
    }
}

class Emturbovid : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf("Origin" to mainUrl, "Referer" to url, "Accept" to "*/*")
        manualJsUnpackExtraction(url, name, headers, callback)
    }
}

class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = try { app.get(url, referer = referer ?: mainUrl).text } catch (e: Exception) { return }
        val scrapedUrls = mutableSetOf<String>()
        Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""").findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            if (cleanUrl.contains("/assets/", true) || cleanUrl.contains("loop", true) || cleanUrl.contains("preview", true) || cleanUrl.contains("tracker", true)) return@forEach

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
                    val precedingText = html.substring(Math.max(0, match.range.first - 150), match.range.first)
                    val qMatch = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""").findAll(precedingText).lastOrNull() ?: Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""").findAll(precedingText).lastOrNull()
                    val qualityInt = qMatch?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                    
                    callback(
                        newExtractorLink(name = name, source = if (qualityInt != Qualities.Unknown.value) "$name ${qualityInt}p" else name, url = cleanUrl, type = INFER_TYPE) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }
    }
}
