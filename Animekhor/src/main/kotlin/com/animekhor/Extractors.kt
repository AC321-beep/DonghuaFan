package com.animekhor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
    override val name = "OkRu" 
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val jsonStr = app.post(apiUrl).text

            if (!jsonStr.startsWith("{")) return
            val json = JSONObject(jsonStr)

            // 1. EXTRACT MP4 FIRST (Fastest playback)
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

                    val displayLabel = if (qualityValue != Qualities.Unknown.value) "MP4 ${qualityValue}p" else "MP4 $qName"

                    callback(newExtractorLink(name = "${this.name} MP4", source = "${this.name} $displayLabel", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    })
                }
            }

            // 2. EXTRACT HLS/DASH SECOND (Adaptive fallback)
            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8("$name HLS", hlsUrl.replace("\\u0026", "&").replace("\\/", "/"), url).forEach(callback)
            } else {
                val dashUrl = json.optString("dashManifestUrl")
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(newExtractorLink(name = "$name DASH", source = "$name DASH", url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"), type = com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH) { this.referer = "https://ok.ru/" })
                }
            }
        } catch (e: Exception) {
            // Fails silently
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

class AbyssPlayer : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )

        val doc = try { app.get(url, headers = headers).document } catch (e: Exception) { return }
        val scriptData = doc.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(scriptData)?.groupValues?.getOrNull(1) ?: return

        val payload = """{"text":"$encrypted"}"""
        val reqBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val responseText = try {
            app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = headers + mapOf("Content-Type" to "application/json"),
                requestBody = reqBody
            ).text
        } catch (e: Exception) { return }

        val json = JSONObject(responseText).optJSONObject("result") ?: return
        val sources = json.optJSONArray("sources") ?: return
        
        val dedupSources = mutableSetOf<String>()

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i)
            if (src != null) {
                if (src.optBoolean("status", false) || src.has("url")) {
                    val srcUrl = src.optString("url")
                    if (srcUrl.isNotBlank() && dedupSources.add(srcUrl)) {
                        
                        val rawLabel = src.optString("label")
                            .ifBlank { src.optString("quality") }
                            .ifBlank { src.optString("type") }
                            .ifBlank { src.optString("res") }
                            .ifBlank { src.optString("resolution") }
                        
                        val qualityInt = when {
                            rawLabel.contains("1080") || rawLabel.contains("FHD", true) -> Qualities.P1080.value
                            rawLabel.contains("720") || rawLabel.contains("HD", true) -> Qualities.P720.value
                            rawLabel.contains("480") || rawLabel.contains("SD", true) -> Qualities.P480.value
                            rawLabel.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }

                        val displayString = if (qualityInt != Qualities.Unknown.value) {
                            "${qualityInt}p"
                        } else if (rawLabel.isNotBlank()) {
                            rawLabel.uppercase()
                        } else {
                            ""
                        }

                        if (srcUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8("$name HLS", srcUrl, mainUrl).forEach { link ->
                                val finalQuality = if (link.quality == Qualities.Unknown.value && qualityInt != Qualities.Unknown.value) qualityInt else link.quality
                                val finalName = if (finalQuality == qualityInt && qualityInt != Qualities.Unknown.value) {
                                    "$name HLS ${qualityInt}p"
                                } else if (link.name == "$name HLS" && displayString.isNotBlank()) {
                                    "$name HLS $displayString"
                                } else {
                                    link.name
                                }
                                
                                callback(
                                    ExtractorLink(
                                        name = link.name,
                                        source = finalName,
                                        url = link.url,
                                        referer = link.referer,
                                        quality = finalQuality,
                                        type = link.type,
                                        headers = link.headers,
                                        extractorData = link.extractorData
                                    )
                                )
                            }
                        } else {
                            // Removed "MP4" padding completely
                            val sourceName = if (displayString.isNotBlank()) "$name $displayString" else name
                            callback(
                                newExtractorLink(name = name, source = sourceName, url = srcUrl, type = INFER_TYPE) {
                                    this.referer = mainUrl
                                    this.quality = qualityInt
                                }
                            )
                        }
                    }
                }
            } else {
                val stringUrl = sources.optString(i)
                if (stringUrl.isNotBlank() && dedupSources.add(stringUrl)) {
                    if (stringUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8("$name HLS", stringUrl, mainUrl).forEach(callback)
                    } else {
                        // Removed "MP4" padding completely
                        callback(newExtractorLink(name = name, source = name, url = stringUrl, type = INFER_TYPE) { this.referer = mainUrl })
                    }
                }
            }
        }
    }
}
