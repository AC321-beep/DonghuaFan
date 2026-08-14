package com.animekhor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.Upstream
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
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
    // Added default User-Agent to prevent bot-blocks
    val safeHeaders = headers.toMutableMap()
    if (!safeHeaders.containsKey("User-Agent")) {
        safeHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    }

    val response = app.get(url, headers = safeHeaders).text
    
    // 1. Try unpacking standard eval scripts
    val packedScript = Regex("""eval\(function\(p,a,c,k,e,d\).*?split\('\|'\).*?\)""").find(response)?.value
    val unpacked = if (packedScript != null) JsUnpacker(packedScript).unpack() ?: response else response

    // 2. Broad Regex to catch file:, src:, source:, or just raw links in the DOM
    val m3u8Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
    val m3u8 = m3u8Regex.find(unpacked)?.groupValues?.get(1) 
        ?: Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(unpacked)?.groupValues?.get(1)

    if (m3u8 != null) {
        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = m3u8,
            referer = url,
            headers = safeHeaders
        ).forEach(callback)
    } else {
        // Fallback for raw mp4 files if m3u8 is not found
        val mp4Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
        val mp4 = mp4Regex.find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"']+\.mp4[^"']*)""").find(unpacked)?.groupValues?.get(1)
            
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

// ============================================================================
// STANDARD BUILT-IN EXTRACTORS
// ============================================================================

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

// NEW: VGPlayer Domain mapped to VidHide built-in extractor
class Bysekoze : VidhideExtractor() {
    override var name = "VGPlayer"
    override var mainUrl = "https://bysekoze.com"
}

// ============================================================================
// HYBRID EXTRACTORS & CUSTOM FALLBACKS
// ============================================================================

class P2pstream : ExtractorApi() {
    override var name = "P2pstream"
    override var mainUrl = "https://animekhor.p2pstream.vip"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace("/#", "/e/")
        
        try {
            // Priority: Built-in Cloudstream VidStack Extractor
            VidStack().getUrl(fixedUrl, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            // Fallback: Custom Unpacker
            val headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
            manualJsUnpackExtraction(fixedUrl, name, headers, callback)
        }
    }
}

class UpnsLive : ExtractorApi() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace("/#", "/e/")
        
        try {
            // Priority: Built-in Cloudstream Upstream Extractor
            Upstream().getUrl(fixedUrl, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            // Fallback: Custom Unpacker
            val headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
            manualJsUnpackExtraction(fixedUrl, name, headers, callback)
        }
    }
}

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
        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to url,
            "Accept" to "*/*"
        )
        manualJsUnpackExtraction(url, name, headers, callback)
    }
}

// NEW: Odysee Extractor
class Odysee : ExtractorApi() {
    override var name = "Odysee"
    override var mainUrl = "https://odysee.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url).text
        // Parses the raw mp4 file directly from Odysee's JSON state embed data
        val mp4 = Regex("""contentUrl["']?\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(response)?.groupValues?.get(1)
            ?: Regex("""src=["'](https?://[^"']+\.mp4[^"']*)["']""").find(response)?.groupValues?.get(1)
            
        if (mp4 != null) {
            callback.invoke(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = mp4,
                    type = INFER_TYPE
                )
            )
        }
    }
}

// ============================================================================
// VERBATIM RUMBLE EXTRACTOR (DO NOT ALTER)
// ============================================================================

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

        // 1. Unified Regex: Captures both standard and JSON-escaped URLs safely
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        matches.forEach { match ->
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")

            // 2. The Quarantine Filter: Skips UI/tracker assets so ExoPlayer doesn't crash
            if (cleanUrl.contains("/assets/", ignoreCase = true) ||
                cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) ||
                cleanUrl.contains("tracker", ignoreCase = true) ||
                cleanUrl.contains("thumb", ignoreCase = true)) {
                return@forEach
            }

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    // M3u8Helper automatically handles HLS playlists in modern Cloudstream
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                    
                } else if (cleanUrl.contains(".mp4")) {
                    // 3. Smart Quality Locator: Reads raw HTML before the URL
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

                    // 4. The Fix: Using the newExtractorLink builder and lambda block
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
