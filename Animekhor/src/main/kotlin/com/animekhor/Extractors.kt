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

// 1. FILEMOON BASE EXTRACTOR: Resolves hash fragment paths and unpacks obfuscated JS securely
open class BaseFilemoon : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Transforms Animekhor's hash fragment (/#video_id) into Filemoon's native embed path (/e/video_id)
            val fixedUrl = url.replace("/#", "/e/")
            
            // Injects strict headers to bypass anti-bot 403 Forbidden drops
            val fetchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                "Referer" to "https://animekhor.org/"
            )
            
            val response = app.get(fixedUrl, headers = fetchHeaders)
            val doc = response.document
            
            // Uses JSoup to safely grab the multi-line obfuscated script, avoiding Regex multi-line crashes
            val script = doc.selectFirst("script:containsData(eval)")?.data()
            val unpacked = if (script != null) JsUnpacker(script).unpack() ?: response.text else response.text
            
            val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                val streamHeaders = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
                M3u8Helper.generateM3u8(name, m3u8, fixedUrl, headers = streamHeaders).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e(name, "Filemoon Extraction Error: ${e.message}")
        }
    }
}

// The two custom Filemoon clones found in Animekhor's source
class P2pstream : BaseFilemoon() {
    override var name = "Filemoon"
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class UpnsLive : BaseFilemoon() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
}

// 2. FIXED EMTURBOVID EXTRACTOR: Destroys Error 2004 using strict ExoPlayer headers
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
        try {
            val fetchHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                "Referer" to "https://animekhor.org/"
            )
            
            val response = app.get(url, headers = fetchHeaders)
            val doc = response.document
            
            // Safely grab the script tag using JSoup instead of error-prone string matching
            val script = doc.selectFirst("script:containsData(eval)")?.data()
            val unpacked = if (script != null) JsUnpacker(script).unpack() ?: response.text else response.text
            
            val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                // THE FIX: Strict Origin and Referer headers applied directly to the M3U8 payload to block Error 2004
                val streamHeaders = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "Accept" to "*/*"
                )
                M3u8Helper.generateM3u8(name, m3u8, url, headers = streamHeaders).forEach(callback)
            } else {
                val mp4 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                if (mp4 != null) {
                    callback.invoke(
                        newExtractorLink(name = name, source = name, url = mp4, type = INFER_TYPE) {
                            this.headers = mapOf("Referer" to "$mainUrl/")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Emturbovid Extraction Error: ${e.message}")
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
        Log.d(name, "Starting extraction for: $url")
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch Rumble embed page: ${e.message}")
            return
        }

        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")

            if (cleanUrl.contains("/assets/", ignoreCase = true) ||
                cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) ||
                cleanUrl.contains("tracker", ignoreCase = true) ||
                cleanUrl.contains("thumb", ignoreCase = true)) {
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
