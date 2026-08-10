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

// 1. FIXED FILEMOON EXTRACTOR: Custom script replacing VidStack to handle the "/#" hash fragment
open class P2pstream : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://animekhor.p2pstream.vip"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fixes the URL structure from /#id to /e/id which Filemoon servers natively expect
        val fixedUrl = url.replace("/#", "/e/")
        
        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://animekhor.org/"
        )
        
        val response = app.get(fixedUrl, headers = fetchHeaders).text
        
        // Unpack the hidden Filemoon javascript payload
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\).*?\)""").find(response)?.value
        val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response
        
        // Extract the raw stream link
        val streamUrl = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
        
        if (streamUrl != null) {
            val streamHeaders = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
            
            if (streamUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, streamUrl, fixedUrl, headers = streamHeaders).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(name = name, source = name, url = streamUrl, type = INFER_TYPE) {
                        this.headers = streamHeaders
                    }
                )
            }
        }
    }
}

// 2. NEW CLOUDPLAYER EXTRACTOR: Captures the second Filemoon clone found in the HTML source
class UpnsLive : P2pstream() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
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

// 3. NEW EMTURBOVID EXTRACTOR: Destroys Error 2004 by forcing strict Origin/Referer headers into ExoPlayer
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
        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://animekhor.org/"
        )
        
        val response = app.get(url, headers = fetchHeaders).text
        
        // Unpack Emturbovid's obfuscated player wrapper
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\).*?\)""").find(response)?.value
        val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response
        
        val streamUrl = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
        
        if (streamUrl != null) {
            // THE FIX: These exact headers authorize the chunks and bypass the 403 Forbidden crash
            val streamHeaders = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/" 
            )
            
            if (streamUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, streamUrl, url, headers = streamHeaders).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(name = name, source = name, url = streamUrl, type = INFER_TYPE) {
                        this.headers = streamHeaders
                    }
                )
            }
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
