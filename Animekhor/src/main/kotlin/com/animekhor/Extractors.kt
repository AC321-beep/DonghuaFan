package com.animekhor

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*

// ------------------------------------------------------------
// Shared extraction logic – tries all known patterns
// ------------------------------------------------------------
private suspend fun extractVideoFromHtml(
    html: String,
    pageUrl: String,
    extractorName: String,
    callback: (ExtractorLink) -> Unit
) {
    // 1. Unpack packed JavaScript if present
    var unpacked: String? = null
    val packedRegex = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""")
    packedRegex.find(html)?.let {
        try {
            unpacked = JsUnpacker(it.value).unpack()
        } catch (_: Exception) { /* ignore */ }
    }
    val source = unpacked ?: html

    // 2. Search for M3U8 with multiple patterns
    val m3u8Patterns = listOf(
        Regex("""(?:file|src)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""),
        Regex("""\{[^{}]*"file"\s*:\s*"([^"]+\.m3u8[^"]*)"[^{}]*\}"""),   // JSON object
        Regex("""sources\s*:\s*\[\s*\{[^}]*"file"\s*:\s*"([^"]+\.m3u8[^"]*)"[^}]*\}\s*]""")
    )
    var m3u8: String? = null
    for (pattern in m3u8Patterns) {
        pattern.find(source)?.let { match ->
            m3u8 = match.groupValues.getOrNull(1) ?: match.value
            if (!m3u8.isNullOrBlank()) {
                Log.d("AnimekhorDebug", "Found M3U8 via pattern: $m3u8")
                break
            }
        }
    }

    // 3. If still null, try Base64 decoding (common in VidHide)
    if (m3u8 == null) {
        val base64Regex = Regex("""["']([A-Za-z0-9+/]{40,}={0,2})["']""")
        base64Regex.findAll(source).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                if (decoded.contains(".m3u8")) {
                    m3u8 = decoded
                    Log.d("AnimekhorDebug", "Found M3U8 via Base64: $m3u8")
                    return@forEach
                }
            } catch (_: Exception) { /* ignore */ }
        }
    }

    // 4. If we found a M3U8, generate the links
    if (!m3u8.isNullOrBlank()) {
        val headers = mapOf(
            "Referer" to pageUrl,
            "Origin" to pageUrl.substringBeforeLast('/'),
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        M3u8Helper.generateM3u8(extractorName, m3u8, pageUrl, headers = headers).forEach(callback)
        return
    }

    // 5. Fallback: try MP4
    val mp4Regex = Regex("""(?:file|src)\s*[:=]\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
    mp4Regex.find(source)?.let {
        val mp4 = it.groupValues[1]
        callback.invoke(
            newExtractorLink(name = extractorName, source = extractorName, url = mp4, type = INFER_TYPE) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
            }
        )
        Log.d("AnimekhorDebug", "Found MP4 fallback: $mp4")
    }
}

// ------------------------------------------------------------
// Base Filemoon extractor (fixed)
// ------------------------------------------------------------
abstract class BaseFilemoonExtractor : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://animekhor.org/"
        )

        // Try the exact URL first
        var response = try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            Log.e("AnimekhorDebug", "Filemoon: first request failed: ${e.message}")
            return
        }

        var html = response.text
        var currentUrl = url

        // If 404, try alternative paths
        if (response.code == 404 || html.contains("404 Not Found")) {
            val alternatives = listOf(
                url.replace("/#", "/e/"),
                url.replace("/#", "/embed/"),
                url.replace("/#", "/v/")
            )
            for (alt in alternatives) {
                if (alt == url) continue
                try {
                    val altResponse = app.get(alt, headers = headers)
                    if (altResponse.code != 404) {
                        response = altResponse
                        html = altResponse.text
                        currentUrl = alt
                        Log.d("AnimekhorDebug", "Filemoon: using alternative URL: $alt")
                        break
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }

        // If still 404, give up
        if (response.code == 404 || html.contains("404 Not Found")) {
            Log.e("AnimekhorDebug", "Filemoon: all URLs returned 404")
            return
        }

        extractVideoFromHtml(html, currentUrl, name, callback)
    }
}

// ------------------------------------------------------------
// Base VidHide clone extractor (fixed)
// ------------------------------------------------------------
abstract class BaseVidHideCloneExtractor : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Normalize: /t/ and /v/ -> /e/
        var currentUrl = url
            .replace("/t/", "/e/")
            .replace("/v/", "/e/")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "https://animekhor.org/"
        )

        // First request
        var response = try {
            app.get(currentUrl, headers = headers)
        } catch (e: Exception) {
            Log.e("AnimekhorDebug", "VidHide: first request failed: ${e.message}")
            return
        }

        var html = response.text

        // Skip if 404
        if (response.code == 404 || html.contains("404 Not Found")) {
            // Try stripping query parameters
            val base = currentUrl.substringBefore('?')
            if (base != currentUrl) {
                try {
                    val altResponse = app.get(base, headers = headers)
                    if (altResponse.code != 404) {
                        response = altResponse
                        html = altResponse.text
                        currentUrl = base
                        Log.d("AnimekhorDebug", "VidHide: using stripped URL: $base")
                    }
                } catch (_: Exception) { /* ignore */ }
            }
            if (response.code == 404) {
                Log.e("AnimekhorDebug", "VidHide: URL 404")
                return
            }
        }

        // Follow JavaScript redirect (improved regex)
        val redirectRegex = Regex("""window\.location\.replace\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        val redirectMatch = redirectRegex.find(html)
        if (redirectMatch != null) {
            val newUrl = redirectMatch.groupValues[1]
            Log.d("AnimekhorDebug", "VidHide: following JS redirect to $newUrl")
            try {
                response = app.get(newUrl, headers = headers)
                html = response.text
                currentUrl = newUrl
            } catch (e: Exception) {
                Log.e("AnimekhorDebug", "VidHide: redirect fetch failed: ${e.message}")
                return
            }
        }

        // Extract video from the final HTML
        extractVideoFromHtml(html, currentUrl, name, callback)
    }
}

// ------------------------------------------------------------
// Concrete extractor classes (unchanged names)
// ------------------------------------------------------------
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

class P2pstream : BaseFilemoonExtractor() {
    override var name = "Filemoon"
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class UpnsLive : BaseFilemoonExtractor() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
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

// ------------------------------------------------------------
// Rumble extractor (already good, kept as is)
// ------------------------------------------------------------
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
            val cleanUrl = match.value.replace("\\/", "/")

            if (cleanUrl.contains("/assets/", ignoreCase = true) ||
                cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) ||
                cleanUrl.contains("tracker", ignoreCase = true) ||
                cleanUrl.contains("thumb", ignoreCase = true)
            ) {
                return@forEach
            }

            if (scrapedUrls.add(cleanUrl)) {
                if (cleanUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, cleanUrl, url).forEach(callback)
                } else if (cleanUrl.contains(".mp4")) {
                    val startIndex = max(0, match.range.first - 150)
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
