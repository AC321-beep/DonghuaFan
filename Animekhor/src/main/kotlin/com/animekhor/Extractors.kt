package com.animekhor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Embedwish : StreamWishExtractor() {
    override var name = "Embedwish"
    override var mainUrl = "https://embedwish.com"
}

class Filelions : VidhideExtractor() {
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

// --- FILEMOON DEBUG EXTRACTOR ---
abstract class BaseFilemoon : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "STEP 1: getUrl called with URL: $url")
        try {
            val fixedUrl = url.replace("/#", "/e/")
            Log.d(name, "STEP 2: Fixed URL: $fixedUrl")
            
            val fetchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                "Referer" to "https://animekhor.org/"
            )
            
            val response = app.get(fixedUrl, headers = fetchHeaders)
            Log.d(name, "STEP 3: HTTP GET response code: ${response.code}")
            
            val html = response.text
            Log.d(name, "STEP 4: HTML Length: ${html.length}. Contains eval? ${html.contains("eval(function")}")
            
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[rd]\).*?split\('\|'\).*?\)""", RegexOption.DOT_MATCHES_ALL)
            val packed = packedRegex.find(html)?.value
            
            if (packed != null) {
                Log.d(name, "STEP 5: Packed JS found. Length: ${packed.length}")
            } else {
                Log.e(name, "STEP 5: Packed JS NOT FOUND. Did Regex fail?")
            }
            
            val unpacked = if (packed != null) JsUnpacker(packed).unpack() ?: html else html
            Log.d(name, "STEP 6: Unpacked length: ${unpacked.length}. Contains m3u8? ${unpacked.contains(".m3u8")}")
            
            val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://.*?\.m3u8.*?)["']""").find(unpacked)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                Log.d(name, "STEP 7: Extracted M3U8: $m3u8")
                val streamHeaders = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                Log.d(name, "STEP 8: Generating links with M3u8Helper. Headers: $streamHeaders")
                
                M3u8Helper.generateM3u8(name, m3u8, fixedUrl, headers = streamHeaders).forEach { link ->
                    Log.d(name, "STEP 9: Emitting Link -> ${link.url}")
                    callback(link)
                }
            } else {
                Log.e(name, "STEP 7: Failed to extract M3U8 link from unpacked code.")
            }
        } catch (e: Exception) {
            Log.e(name, "CRASH: ${e.stackTraceToString()}")
        }
    }
}

class P2pstream : BaseFilemoon() {
    override var name = "Filemoon"
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class UpnsLive : BaseFilemoon() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
}

// --- EMTURBOVID DEBUG EXTRACTOR ---
class Emturbovid : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "STEP 1: getUrl called with URL: $url")
        try {
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
            val fetchHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://animekhor.org/"
            )
            
            val response = app.get(url, headers = fetchHeaders)
            Log.d(name, "STEP 2: HTTP GET response code: ${response.code}")
            
            val html = response.text
            Log.d(name, "STEP 3: HTML Length: ${html.length}. Contains eval? ${html.contains("eval(function")}")
            
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[rd]\).*?split\('\|'\).*?\)""", RegexOption.DOT_MATCHES_ALL)
            val packed = packedRegex.find(html)?.value
            
            if (packed != null) {
                Log.d(name, "STEP 4: Packed JS found. Length: ${packed.length}")
            } else {
                Log.e(name, "STEP 4: Packed JS NOT FOUND.")
            }
            
            val unpacked = if (packed != null) JsUnpacker(packed).unpack() ?: html else html
            Log.d(name, "STEP 5: Unpacked length: ${unpacked.length}. Contains m3u8? ${unpacked.contains(".m3u8")}")
            
            val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://.*?\.m3u8.*?)["']""").find(unpacked)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                Log.d(name, "STEP 6: Extracted M3U8: $m3u8")
                val streamHeaders = mapOf(
                    "Origin" to mainUrl, 
                    "Referer" to "$mainUrl/",
                    "User-Agent" to userAgent,
                    "Accept" to "*/*"
                )
                Log.d(name, "STEP 7: Handing to M3u8Helper. Headers: $streamHeaders")
                
                M3u8Helper.generateM3u8(name, m3u8, url, headers = streamHeaders).forEach { link ->
                    Log.d(name, "STEP 8: Emitting Link -> ${link.url}")
                    callback(link)
                }
            } else {
                Log.e(name, "STEP 6: No M3U8 found. Searching for raw MP4...")
                val mp4 = Regex("""(?:file|src)\s*:\s*["'](https?://.*?\.mp4.*?)["']""").find(unpacked)?.groupValues?.get(1)
                
                if (mp4 != null) {
                    Log.d(name, "STEP 7: MP4 found: $mp4")
                    callback.invoke(
                        newExtractorLink(name = name, source = name, url = mp4, type = INFER_TYPE) {
                            this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                        }
                    )
                } else {
                    Log.e(name, "STEP 7: Extractor failed. No video link found in HTML.")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "CRASH: ${e.stackTraceToString()}")
        }
    }
}

class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            return
        }

        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")

            if (cleanUrl.contains("/assets/", ignoreCase = true) || cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) || cleanUrl.contains("tracker", ignoreCase = true)) {
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

                    val qStr = qMatch?.groupValues?.get(1)
                    val qualityInt = qStr?.toIntOrNull() ?: Qualities.Unknown.value
                    val displayLabel = if (qStr != null) "$name ${qStr}p" else name

                    callback(
                        newExtractorLink(name = name, source = displayLabel, url = cleanUrl, type = INFER_TYPE) {
                            this.referer = url
                            this.quality = qualityInt
                        }
                    )
                }
            }
        }
    }
}
