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

            // ---------- DUMP 1: full HTML in 3.5K chunks ----------
            Log.e("AbyssPlayerDebug", "HTML_DUMP_BEGIN")
            html.chunked(3500).forEachIndexed { i, chunk ->
                Log.e("AbyssPlayerDebug", "HTML[$i] >>> $chunk")
            }
            Log.e("AbyssPlayerDebug", "HTML_DUMP_END")
            // -------------------------------------------------------

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

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(md5Hex.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(md5Hex.toByteArray(Charsets.UTF_8).copyOfRange(0, 16))
            )
            val metaData = JSONObject(
                String(cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8)
            )

            val mp4 = metaData.optJSONObject("mp4") ?: return
            val sources = mp4.optJSONArray("sources") ?: return
            val domains = mp4.optJSONArray("domains")
            val fristDatas = mp4.optJSONArray("fristDatas")

            val streamHeaders = mapOf(
                "User-Agent" to iosUserAgent,
                "Referer" to url,
                "Origin" to mainUrl
            )

            // ---------- DUMP 2: fd response headers + first bytes ----------
            if (fristDatas != null && fristDatas.length() > 0) {
                val fdUrl = fristDatas.optJSONObject(0)
                    ?.optString("url")
                    ?.replace("\\/", "/")
                    .orEmpty()

                if (fdUrl.isNotBlank()) {
                    val probe = try {
                        app.get(
                            fdUrl,
                            headers = streamHeaders + mapOf("Range" to "bytes=0-4095")
                        )
                    } catch (e: Exception) {
                        Log.e("AbyssPlayerDebug", "FD_PROBE open failed: ${e.message}")
                        null
                    }

                    if (probe != null) {
                        Log.e("AbyssPlayerDebug", "FD_PROBE url=$fdUrl")
                        Log.e("AbyssPlayerDebug", "FD_PROBE code=${probe.code}")
                        Log.e("AbyssPlayerDebug", "FD_PROBE content-type=${probe.headers["Content-Type"]}")
                        Log.e("AbyssPlayerDebug", "FD_PROBE content-length=${probe.headers["Content-Length"]}")
                        Log.e("AbyssPlayerDebug", "FD_PROBE content-range=${probe.headers["Content-Range"]}")
                        Log.e("AbyssPlayerDebug", "FD_PROBE content-encoding=${probe.headers["Content-Encoding"]}")
                        Log.e("AbyssPlayerDebug", "FD_PROBE accept-ranges=${probe.headers["Accept-Ranges"]}")
                        Log.e("AbyssPlayerDebug", "FD_PROBE location=${probe.headers["Location"]}")

                        try {
                            val bytes = probe.body?.bytes() ?: ByteArray(0)
                            Log.e("AbyssPlayerDebug", "FD_PROBE bodySize=${bytes.size}")
                            val hex = bytes.take(64).joinToString("") { "%02x".format(it) }
                                val ascii = bytes.take(64)
                                .map { if (it.toInt() in 32..126) it.toInt().toChar() else '.' }
                                .joinToString("")
                            Log.e("AbyssPlayerDebug", "FD_PROBE hex=$hex")
                            Log.e("AbyssPlayerDebug", "FD_PROBE ascii=$ascii")
                        } catch (e: Exception) {
                            Log.e("AbyssPlayerDebug", "FD_PROBE body read failed: ${e.message}")
                        }
                    }
                }
            }

            // ---------- DUMP 3: HLS path probes with different referers ----------
            if (sources.length() > 0) {
                val src = sources.optJSONObject(0)
                val sub = src?.optString("sub").orEmpty()
                val domain = if (domains != null && domains.length() > 0)
                    domains.optString(0) else "$sub.sssrr.org"

                val testPaths = listOf(
                    "https://$domain/hls/$sub/index-f1-v1-a1.m3u8",
                    "https://$domain/hls/$sub/index.m3u8",
                    "https://$domain/hls/$sub/master.m3u8",
                    "https://$domain/hls/$sub.m3u8",
                    "https://$domain/$sub/index-f1-v1-a1.m3u8",
                )
                val testReferers = listOf(
                    url,
                    "$mainUrl/",
                    "https://$domain/",
                    referer ?: "",
                ).filter { it.isNotBlank() }.distinct()

                for (path in testPaths) {
                    for (ref in testReferers) {
                        val h = mapOf(
                            "User-Agent" to iosUserAgent,
                            "Referer" to ref,
                            "Origin" to mainUrl,
                        )
                        try {
                            val r = app.get(path, headers = h)
                            Log.e("AbyssPlayerDebug", "HLS_PROBE $path ref=$ref -> ${r.code}")
                        } catch (e: Exception) {
                            Log.e("AbyssPlayerDebug", "HLS_PROBE $path ref=$ref -> EX ${e.message}")
                        }
                    }
                }
            }

            Log.e("AbyssPlayerDebug", "Debug pass complete, no links emitted")
        } catch (e: Exception) {
            Log.e("AbyssPlayerDebug", "Failed: ${e.message}", e)
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
