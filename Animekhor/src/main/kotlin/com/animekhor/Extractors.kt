package com.animekhor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
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

// --- VIDHIDE CLONES (Fixes Error 2004 via M3u8Helper) ---
abstract class CustomVidHide : ExtractorApi() {
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/t/", "/e/").replace("/v/", "/e/")
        val response = app.get(fixedUrl, referer = referer ?: "https://animekhor.org/").text
        
        val packed = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""", RegexOption.DOT_MATCHES_ALL).find(response)?.value
        val unpacked = if (packed != null) JsUnpacker(packed).unpack() else response
        
        val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
        
        if (m3u8 != null) {
            // Using M3u8Helper bypasses the newExtractorLink compiler issues entirely
            val headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
            )
            M3u8Helper.generateM3u8(name, m3u8, fixedUrl, headers = headers).forEach(callback)
        } else {
            val mp4 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
            if (mp4 != null) {
                callback(
                    newExtractorLink(name, name, mp4, INFER_TYPE) {
                        this.referer = fixedUrl
                    }
                )
            }
        }
    }
}

class Emturbovid : CustomVidHide() {
    override var name = "Player"
    override var mainUrl = "https://emturbovid.com"
}

class Listeamed : CustomVidHide() {
    override var name = "VGPlayer"
    override var mainUrl = "https://listeamed.net"
}

class AbyssPlayer : CustomVidHide() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
}

// --- FILEMOON CLONES ---
abstract class CustomFilemoon : ExtractorApi() {
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        val response = app.get(fixedUrl, referer = referer ?: "https://animekhor.org/").text
        
        val packed = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""", RegexOption.DOT_MATCHES_ALL).find(response)?.value
        val unpacked = if (packed != null) JsUnpacker(packed).unpack() else response
        
        val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
        
        if (m3u8 != null) {
            val headers = mapOf(
                "Origin" to "https://filemoon.sx",
                "Referer" to "https://filemoon.sx/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
            )
            M3u8Helper.generateM3u8(name, m3u8, fixedUrl, headers = headers).forEach(callback)
        }
    }
}

class P2pstream : CustomFilemoon() {
    override var name = "FileMoon Player"
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class UpnsLive : CustomFilemoon() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
}

// --- RUMBLE EXTRACTOR (Direct implementation from your working sample) ---
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
                            name,
                            displayLabel,
                            cleanUrl,
                            INFER_TYPE
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
