package com.animekhor

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu Custom"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            val document = app.get(url, headers = headers).document
            
            val dataOptionsAttr = document.selectFirst("div[data-options]")?.attr("data-options") ?: return
            val jsonStr = Parser.unescapeEntities(dataOptionsAttr, true)
            val json = JSONObject(jsonStr)

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8(name, hlsUrl, url).forEach(callback)
            }

            val dashUrl = json.optString("dashManifestUrl")
            if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                callback(
                    newExtractorLink(name = name, source = "$name DASH", url = dashUrl, type = com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH) {
                        this.referer = "https://ok.ru/"
                    }
                )
            }

            val videos = json.optJSONArray("videos")
            if (videos != null) {
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    val qName = video.optString("name")
                    val vidUrl = video.optString("url")
                    
                    if (vidUrl.isBlank() || vidUrl.contains("usr_login")) continue
                    
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
            }
        } catch (e: Exception) {
            Log.e("OkRuCustom", "OkRu extraction failed: ${e.message}")
        }
    }
}

class AbyssPlayer : ExtractorApi() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36", "Referer" to (referer ?: mainUrl))
        val response = try { app.get(url, headers = headers).text } catch (e: Exception) { return }

        try {
            val encodedData = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1) ?: return
            
            val decodedJsonBytes = Base64.decode(encodedData, Base64.NO_WRAP)
            val decodedJsonString = String(decodedJsonBytes, Charsets.ISO_8859_1) 
            val data = JSONObject(decodedJsonString)
            
            val userId = data.optString("user_id")
            val slug = data.optString("slug")
            val md5Id = data.optString("md5_id")
            val mediaStr = data.optString("media")

            val seed = "$userId:$slug:$md5Id"
            val md5Hex = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)
            val encryptedMediaBytes = mediaStr.toByteArray(Charsets.ISO_8859_1)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))

            val decryptedBytes = cipher.doFinal(encryptedMediaBytes)
            val metaData = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            val videoUrl = metaData.optString("url").ifBlank { metaData.optString("file") }.replace("\\/", "/")
            
            if (videoUrl.isNotBlank()) {
                if (videoUrl.contains(".m3u8") || metaData.optString("type").contains("hls", true)) {
                    M3u8Helper.generateM3u8(name, videoUrl, url, headers = headers).forEach(callback)
                } else {
                    callback(newExtractorLink(name = name, source = name, url = videoUrl, type = INFER_TYPE) { this.referer = url })
                }
            }
        } catch (e: Exception) {
            Log.e("AbyssPlayer", "AES Decryption failed: ${e.message}")
        }
    }
}

class P2pstream : ExtractorApi() {
    override var name = "P2pstream"
    override var mainUrl = "https://animekhor.p2pstream.vip"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        manualJsUnpackExtraction(fixedUrl, name, mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/"), callback)
    }
}

class UpnsLive : ExtractorApi() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        manualJsUnpackExtraction(fixedUrl, name, mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/"), callback)
    }
}

class Bysekoze : ExtractorApi() {
    override var name = "VGPlayer"
    override var mainUrl = "https://bysekoze.com"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        manualJsUnpackExtraction(url, name, mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/"), callback)
    }
}

class Emturbovid : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        manualJsUnpackExtraction(url, name, mapOf("Origin" to mainUrl, "Referer" to url, "Accept" to "*/*"), callback)
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
