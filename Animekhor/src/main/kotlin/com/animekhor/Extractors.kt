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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private suspend fun manualJsUnpackExtraction(url: String, name: String, headers: Map<String, String>, callback: (ExtractorLink) -> Unit) {
    val safeHeaders = headers.toMutableMap()
    if (!safeHeaders.containsKey("User-Agent")) safeHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    val response = try { app.get(url, headers = safeHeaders).text } catch (e: Exception) { return }
    val packedScript = Regex("""eval\(\s*function\s*\(p,a,c,k,e,[a-zA-Z0-9_]\).*?split\('\|'\).*?\)""").find(response)?.value
    val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response
    val m3u8Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
    val m3u8 = m3u8Regex.find(unpacked)?.groupValues?.get(1) ?: Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(unpacked)?.groupValues?.get(1)

    if (m3u8 != null) {
        val cleanM3u8 = m3u8.replace("\\/", "/")
        M3u8Helper.generateM3u8(name, cleanM3u8, url, headers = safeHeaders).forEach(callback)
    } else {
        val mp4Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val mp4 = mp4Regex.find(unpacked)?.groupValues?.get(1) ?: Regex("""(https?://[^"']+\.(?:mp4|mkv)[^"']*)""").find(unpacked)?.groupValues?.get(1)
        if (mp4 != null) {
            val cleanMp4 = mp4.replace("\\/", "/")
            callback.invoke(newExtractorLink(name = name, source = name, url = cleanMp4, type = INFER_TYPE) { this.referer = url })
        }
    }
}

class OkRuCustom : ExtractorApi() {
    override val name = "OkRu Custom"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) {
                Log.e("OkRuCustom", "Failed to parse Video ID from URL: $url")
                return
            }

            // Direct API Bypass - Avoids slow HTML parsing and restriction blocks
            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val jsonStr = app.post(apiUrl).text

            if (!jsonStr.startsWith("{")) {
                Log.e("OkRuCustom", "Backend API returned non-JSON string. Layout may have changed.")
                return
            }

            val json = JSONObject(jsonStr)
            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8(name, hlsUrl.replace("\\u0026", "&").replace("\\/", "/"), url).forEach(callback)
            }

            val dashUrl = json.optString("dashManifestUrl")
            if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                callback(newExtractorLink(name = name, source = "$name DASH", url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"), type = com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH) { this.referer = "https://ok.ru/" })
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

                    callback(newExtractorLink(name = this.name, source = "${this.name} $qName", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    })
                }
            } else {
                Log.e("OkRuCustom", "Videos array was null. API response missing streams.")
            }
        } catch (e: Exception) {
            Log.e("OkRuCustom", "Extraction crashed: ${e.message}")
        }
    }
}

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
        try {
            val iosUserAgent =
                "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                        "Version/16.6 Mobile/15E148 Safari/604.1"

            val html = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to iosUserAgent,
                    "Referer" to (referer ?: "$mainUrl/")
                )
            ).text

            val encodedData = Regex("""datas\s*=\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1) ?: run {
                Log.e("AbyssPlayerDebug", "No datas payload")
                return
            }

            val root = JSONObject(
                String(Base64.decode(encodedData, Base64.DEFAULT), Charsets.ISO_8859_1)
            )

            val userId = root.optString("user_id")
            val slug = root.optString("slug")
            val md5Id = root.optString("md5_id")
            val mediaStr = root.optString("media")

            val md5Hex = MessageDigest.getInstance("MD5")
                .digest("$userId:$slug:$md5Id".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            Log.e("AbyssPlayerDebug", "md5Hex = $md5Hex")

            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )
            val metaData = JSONObject(
                String(cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8)
            )

            val mp4 = metaData.optJSONObject("mp4") ?: return
            val fristDatas = mp4.optJSONArray("fristDatas") ?: run {
                Log.e("AbyssPlayerDebug", "No fristDatas")
                return
            }

            val streamHeaders = mapOf(
                "User-Agent" to iosUserAgent,
                "Referer" to url,
                "Origin" to mainUrl
            )

            // Decrypt the first .fd chunk with the same AES key.
            val fdUrl = fristDatas.optJSONObject(0)
                ?.optString("url")
                ?.replace("\\/", "/")
                .orEmpty()

            if (fdUrl.isBlank()) {
                Log.e("AbyssPlayerDebug", "No fdUrl")
                return
            }

            // Grab the first 64 KB (enough to see container header)
            val probe = try {
                app.get(
                    fdUrl,
                    headers = streamHeaders + mapOf("Range" to "bytes=0-65535")
                )
            } catch (e: Exception) {
                Log.e("AbyssPlayerDebug", "FD open failed: ${e.message}")
                return
            }

            val encrypted = probe.body?.bytes() ?: ByteArray(0)
            Log.e("AbyssPlayerDebug", "FD raw size=${encrypted.size} code=${probe.code}")

            // Try decrypting with IV = first 16 bytes of md5Hex
            val c1 = Cipher.getInstance("AES/CTR/NoPadding")
            c1.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )
            val dec1 = try { c1.doFinal(encrypted) } catch (e: Exception) {
                Log.e("AbyssPlayerDebug", "Decrypt[md5 IV] failed: ${e.message}")
                ByteArray(0)
            }

            dumpBytes("DEC1_md5IV", dec1)

            // Try decrypting with zero IV, in case key was used differently
            val c2 = Cipher.getInstance("AES/CTR/NoPadding")
            c2.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ByteArray(16))
            )
            val dec2 = try { c2.doFinal(encrypted) } catch (e: Exception) {
                Log.e("AbyssPlayerDebug", "Decrypt[zero IV] failed: ${e.message}")
                ByteArray(0)
            }

            dumpBytes("DEC2_zeroIV", dec2)

            // Try AES-ECB (some CDNs use it on chunk boundaries)
            val c3 = Cipher.getInstance("AES/ECB/NoPadding")
            c3.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val dec3 = try { c3.doFinal(encrypted.copyOf(encrypted.size - encrypted.size % 16)) }
            catch (e: Exception) { ByteArray(0) }
            dumpBytes("DEC3_ECB", dec3)

            Log.e("AbyssPlayerDebug", "Debug pass complete")
        } catch (e: Exception) {
            Log.e("AbyssPlayerDebug", "Failed: ${e.message}", e)
        }
    }

    private fun dumpBytes(tag: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            Log.e("AbyssPlayerDebug", "$tag: empty")
            return
        }
        val head = bytes.take(32)
        val hex = head.joinToString("") { "%02x".format(it) }
        val ascii = head.map { if (it.toInt() in 32..126) it.toInt().toChar() else '.' }.joinToString("")

        // Look for known signatures
        val sig = when {
            head.size >= 8 && head[4].toInt() == 0x66 && head[5].toInt() == 0x74 &&
                    head[6].toInt() == 0x79 && head[7].toInt() == 0x70 -> "MP4/ftyp"
            head.size >= 4 && head[0].toInt() == 0x1A && head[1].toInt() == 0x45 &&
                    head[2].toInt() == 0xDF && head[3].toInt() == 0xA3 -> "MKV/EBML"
            head.size >= 4 && head[0].toInt() == 0x47 -> "MPEG-TS"
            head.size >= 4 && head[0].toInt() == 0x00 && head[1].toInt() == 0x00 &&
                    head[2].toInt() == 0x00 && (head[3].toInt() and 0xFF) < 0x40 -> "MP4 (box)"
            ascii.startsWith("#EXT") -> "HLS m3u8"
            else -> "unknown"
        }

        Log.e("AbyssPlayerDebug", "$tag: $sig")
        Log.e("AbyssPlayerDebug", "$tag hex=$hex")
        Log.e("AbyssPlayerDebug", "$tag ascii=$ascii")
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
