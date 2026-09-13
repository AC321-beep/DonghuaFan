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
        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = cleanM3u8,
            referer = url,
            headers = safeHeaders
        ).forEach(callback)
    } else {
        val mp4Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val mp4 = mp4Regex.find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"']+\.(?:mp4|mkv)[^"']*)""").find(unpacked)?.groupValues?.get(1)
            
        if (mp4 != null) {
            val cleanMp4 = mp4.replace("\\/", "/")
            callback.invoke(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = cleanMp4,
                    type = INFER_TYPE
                ) {
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
            val html = app.get(url).text
            
            // 1. Aggressive regex to find HLS/DASH links anywhere in the ok.ru source code
            val hlsUrlRaw = Regex("""(?:\\"|")hlsManifestUrl(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|")""").find(html)?.groupValues?.get(1)
            val dashUrlRaw = Regex("""(?:\\"|")dashManifestUrl(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|")""").find(html)?.groupValues?.get(1)

            if (hlsUrlRaw != null && !hlsUrlRaw.contains("usr_login")) {
                val hlsUrl = hlsUrlRaw.replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
                M3u8Helper.generateM3u8(name, hlsUrl, url).forEach(callback)
                return 
            }

            if (dashUrlRaw != null && !dashUrlRaw.contains("usr_login")) {
                val dashUrl = dashUrlRaw.replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
                callback(
                    newExtractorLink(
                        name = name,
                        source = "$name DASH",
                        url = dashUrl,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH
                    ) {
                        this.referer = "https://ok.ru/"
                    }
                )
                return 
            }

            // 2. Fallback to raw MP4 arrays if HLS/DASH manifests are missing
            val videoRegex = Regex("""(?:\\"|")name(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|").*?(?:\\"|")url(?:\\"|")\s*:\s*(?:\\"|")([^"\\]+)(?:\\"|")""")
            videoRegex.findAll(html).forEach { match ->
                val qName = match.groupValues[1]
                val vidUrl = match.groupValues[2].replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/") 
                
                // Block restricted login links from crashing ExoPlayer (Error 2004)
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
                    newExtractorLink(
                        name = this@OkRuCustom.name,
                        source = "${this@OkRuCustom.name} $qName",
                        url = vidUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("OkRuCustom", "OkRu extraction failed: ${e.message}")
        }
    }
}

// ============================================================================
// ADVANCED ABYSS PLAYER EXTRACTOR
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
                    callback(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } else {
            val jsonConfigRegex = Regex("""JSON\.parse\(['"]([A-Za-z0-9+/=]+)['"]\)""")
            val jsonMatch = jsonConfigRegex.find(unpacked)
            
            if (jsonMatch != null) {
                try {
                    val decodedJson = String(android.util.Base64.decode(jsonMatch.groupValues[1], android.util.Base64.DEFAULT))
                    fileRegex.findAll(decodedJson).forEach { match ->
                        val videoUrl = match.groupValues[1].replace("\\/", "/")
                        
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(name, videoUrl, url, headers = headers).forEach(callback)
                        } else {
                            callback(
                                newExtractorLink(
                                    name = name,
                                    source = name,
                                    url = videoUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AbyssPlayer", "Failed to parse inner config base64")
                }
            }
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
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            if (cleanUrl.contains("/assets/", true) || cleanUrl.contains("loop", true) || 
                cleanUrl.contains("preview", true) || cleanUrl.contains("tracker", true) || 
                cleanUrl.contains("thumb", true)) {
                return@forEach
            }

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
                    val startIndex = Math.max(0, match.range.first - 150)
                    val precedingText = html.substring(startIndex, match.range.first)

                    val qMatch = Regex("""(?:\\"h\\"|"h")\s*:\s*(\d{3,4})""").findAll(precedingText).lastOrNull()
                        ?: Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""").findAll(precedingText).lastOrNull()

                    var displayLabel = name
                    var qualityInt = Qualities.Unknown.value

                    if (qMatch != null) {
                        val qStr = qMatch.groupValues[1]
                        displayLabel = "$name ${qStr}p"
                        qualityInt = qStr.toIntOrNull() ?: Qualities.Unknown.value
                    }

                    callback(
                        newExtractorLink(
                            name = name,
                            source = displayLabel,
                            url = cleanUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }
    }
}
