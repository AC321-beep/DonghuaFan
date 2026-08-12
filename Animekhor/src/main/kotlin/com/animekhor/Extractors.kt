package com.animekhor

import android.util.Base64
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

// --- FILEMOON & CLONES ---
abstract class BaseFilemoonExtractor : ExtractorApi() {
    // Disabled to prevent Cloudstream's core API from interfering with our manual app.get()
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        
        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to "https://animekhor.org/"
        )
        
        val response = app.get(fixedUrl, headers = fetchHeaders).text
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""").find(response)?.value
        val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() else response
        
        val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
            ?: Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked ?: "")?.groupValues?.get(1)

        if (m3u8 != null) {
            val streamHeaders = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
            M3u8Helper.generateM3u8(name, m3u8, fixedUrl, headers = streamHeaders).forEach(callback)
        }
    }
}

class P2pstream : BaseFilemoonExtractor() {
    override var name = "Filemoon"
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class UpnsLive : BaseFilemoonExtractor() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
}


// --- EMTURBOVID & VIDHIDE CLONES ---
abstract class BaseVidHideCloneExtractor : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/t/", "/e/").replace("/v/", "/e/")
        
        val fetchHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to "https://animekhor.org/"
        )
        
        val response = app.get(fixedUrl, headers = fetchHeaders).text
        val packedScript = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""").find(response)?.value
        val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() else response
        
        var m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
            ?: Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked ?: "")?.groupValues?.get(1)

        // Decodes the new Base64 array string obfuscation used by Vidhide
        if (m3u8 == null && unpacked != null) {
            val base64Regex = Regex("""["']([A-Za-z0-9+/]{20,}={0,2})["']""")
            base64Regex.findAll(unpacked).forEach { match ->
                try {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                    if (decoded.contains(".m3u8")) {
                        m3u8 = decoded
                    }
                } catch (_: Exception) {}
            }
        }

        if (m3u8 != null) {
            val streamHeaders = mapOf(
                "Origin" to mainUrl,
                "Referer" to fixedUrl,
                "Accept" to "*/*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
            M3u8Helper.generateM3u8(name, m3u8!!, fixedUrl, headers = streamHeaders).forEach(callback)
        } else {
            val mp4 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
            if (mp4 != null) {
                callback.invoke(
                    newExtractorLink(name = name, source = name, url = mp4, type = INFER_TYPE) {
                        this.referer = fixedUrl
                    }
                )
            }
        }
    }
}

class Emturbovid : BaseVidHideCloneExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}

class Listeamed : BaseVidHideCloneExtractor() {
    override var name = "VGPlayer"
    override var mainUrl = "https://listeamed.net"
}

class AbyssPlayer : BaseVidHideCloneExtractor() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
}

// --- RUMBLE ---
class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = try {
            app.get(url, referer = referer ?: mainUrl).text
        } catch (e: Exception) {
            return
        }

        val scrapedUrls = mutableSetOf<String>()
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")

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
