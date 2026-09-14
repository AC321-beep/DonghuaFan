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
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: run {
                    Log.e("AbyssPlayer", "Failed to find 'datas' payload")
                    return
                }

            val root = JSONObject(
                String(
                    Base64.decode(encodedData, Base64.DEFAULT),
                    Charsets.ISO_8859_1
                )
            )

            val userId = root.optString("user_id")
            val slug = root.optString("slug")
            val md5Id = root.optString("md5_id")
            val mediaStr = root.optString("media")

            if (userId.isBlank() || slug.isBlank() || md5Id.isBlank() || mediaStr.isBlank()) {
                Log.e("AbyssPlayer", "Missing required JSON fields")
                return
            }

            val md5Hex = MessageDigest.getInstance("MD5")
                .digest("$userId:$slug:$md5Id".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )

            val metaData = JSONObject(
                String(
                    cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1)),
                    Charsets.UTF_8
                )
            )

            val streamHeaders = mapOf(
                "User-Agent" to iosUserAgent,
                "Referer" to url,
                "Origin" to mainUrl
            )

            var found = false

            val mp4 = metaData.optJSONObject("mp4")
            val sources = mp4?.optJSONArray("sources")
            val domains = mp4?.optJSONArray("domains")

            if (sources != null && domains != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.optJSONObject(i) ?: continue

                    val sub = source.optString("sub")
                    if (sub.isBlank()) continue

                    val label = source.optString("label")

                    // IMPORTANT: use the domain index from the source object.
                    val domainIndex = source.optInt("domain", 0)
                    val domain = domains.optString(domainIndex)
                        .ifBlank { domains.optString(0) }

                    if (domain.isBlank()) continue

                    // Prefer the real file name from decrypted JSON.
                    // Abyss/Hydrax usually uses master.m3u8 if file is absent.
                    val candidates = listOf(
                        source.optString("file"),
                        source.optString("url"),
                        "master.m3u8",
                        "index.m3u8",
                        "v.m3u8"
                    )
                        .map { it.replace("\\/", "/").trimStart('/') }
                        .filter { it.isNotBlank() }
                        .distinct()

                    for (file in candidates) {
                        val finalUrl = if (file.startsWith("http", true)) {
                            file
                        } else {
                            "https://$domain/$sub/$file"
                        }

                        try {
                            val code = app.get(finalUrl, headers = streamHeaders).code
                            if (code !in 200..299) continue

                            val quality = label
                                .replace(Regex("""[^0-9]"""), "")
                                .toIntOrNull()
                                ?: Qualities.Unknown.value

                            callback(
                                newExtractorLink(
                                    name = this.name,
                                    source = "${this.name} $label",
                                    url = finalUrl,
                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                                ) {
                                    this.referer = url
                                    this.headers = streamHeaders
                                    this.quality = quality
                                }
                            )

                            found = true
                            break
                        } catch (_: Exception) {
                            // Try next candidate
                        }
                    }

                    if (found) break
                }
            }

            // Fallback if mp4/domains/sources are missing
            if (!found) {
                val fallback = metaData.optString("hls")
                    .ifBlank { metaData.optString("url") }
                    .ifBlank { metaData.optString("file") }
                    .replace("\\/", "/")

                if (fallback.isNotBlank() && !fallback.endsWith(".fd")) {
                    callback(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = fallback,
                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.headers = streamHeaders
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("AbyssPlayer", "AES Decryption crashed: ${e.message}")
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
