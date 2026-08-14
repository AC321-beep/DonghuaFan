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

// 1. FIXED FILEMOON/P2PSTREAM EXTRACTOR: Custom JS Unpacker replacing VidStack
class P2pstream : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://animekhor.p2pstream.vip"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fixes the URL structure from /#id to /e/id which Filemoon servers expect
        val fixedUrl = url.replace("/#", "/e/")
        val response = app.get(fixedUrl, referer = referer ?: mainUrl).text
        
        // Unpack the hidden Filemoon javascript
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\).*?\)""").find(response)?.value
        val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() else response
        
        val m3u8 = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
        
        if (m3u8 != null) {
            val headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
            M3u8Helper.generateM3u8(
                name,
                m3u8,
                mainUrl,
                headers = headers
            ).forEach(callback)
        }
    }
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

// 2. NEW EMTURBOVID EXTRACTOR: Fixes Error 2004 by injecting mandatory headers
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
        val response = app.get(url, referer = referer ?: mainUrl).text
        
        // Emturbovid wraps their players in packed JS
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\).*?\)""").find(response)?.value
        val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() else response
        
        val m3u8 = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
        
        if (m3u8 != null) {
            // THE FIX: Forcing Origin and Referer headers prevents the server from dropping the connection
            val headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to url,
                "Accept" to "*/*"
            )
            M3u8Helper.generateM3u8(
                name,
                m3u8,
                url, // Used as referer
                headers = headers
            ).forEach(callback)
        } else {
            // Fallback for raw mp4 files
            val mp4 = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
            if (mp4 != null) {
                    callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = mp4,
                        type = INFER_TYPE
                    ) {
                        this.referer = url
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

                        callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = displayLabel,
                            url = cleanUrl,
                            quality = qualityInt,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                        }
                    )
                }
            }
        }
    }
}
