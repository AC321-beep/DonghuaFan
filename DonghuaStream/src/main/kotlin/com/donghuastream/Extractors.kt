package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder

// ----- 1. Custom Dailymotion Unlocker (unchanged) -----
class Extractor : ExtractorApi() {
    override val name = "Donghua Dailymotion"
    override val mainUrl = "donghuastream.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var token: String? = Regex("""[?&]video=([^&]+)""").find(url)?.groupValues?.get(1)

        if (token == null) {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://donghuastream.org",
                "Origin" to "https://donghuastream.org"
            )

            val pageHtml = try {
                app.get(url, headers = headers).text
            } catch (e: Exception) { return }
            
            val pageDoc = try { Jsoup.parse(pageHtml) } catch (e: Exception) { null }

            pageDoc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
                val src = iframe.attr("src")
                val match = Regex("""[?&]video=([^&]+)""").find(src)
                if (match != null) token = match.groupValues[1]
            }

            if (token == null) {
                val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                    .find(pageHtml)?.groupValues?.get(1)
                
                if (playerJson != null) {
                    var rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                    val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""
                    val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(playerJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    if (encrypt == 1) rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                    else if (encrypt == 2) {
                        rawUrl = String(Base64.decode(rawUrl, Base64.DEFAULT))
                        rawUrl = URLDecoder.decode(rawUrl, "UTF-8")
                    }

                    if (from.equals("dailymotion", ignoreCase = true)) {
                        val match = Regex("""[?&]video=([^&]+)""").find(rawUrl)
                        token = match?.groupValues?.get(1) ?: rawUrl.substringAfterLast("/")
                    }
                }
            }
        }

        if (token != null) {
            val embedUrl = "https://geo.dailymotion.com/player/xkyen.html?video=$token"
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
        }
    }
}

// ----- 2. Rumble Extractor (improved for trailers) – FIXED SUSPEND ISSUES -----
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "Starting extraction for: $url")
        val effectiveReferer = referer ?: mainUrl

        val html = try {
            val requestHeaders = headers.toMutableMap().apply {
                this["Referer"] = effectiveReferer
            }
            app.get(url, headers = requestHeaders).text
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch Rumble embed page: ${e.message}")
            return
        }

        val scrapedUrls = mutableSetOf<String>()

        // ----- 1. Direct URLs (.mp4 / .m3u8) -----
        val urlRegex = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""")
        val matches = urlRegex.findAll(html)

        for (match in matches) {
            val rawUrl = match.value
            val cleanUrl = rawUrl.replace("\\/", "/")

            if (cleanUrl.contains("/assets/", ignoreCase = true) ||
                cleanUrl.contains("loop", ignoreCase = true) ||
                cleanUrl.contains("preview", ignoreCase = true) ||
                cleanUrl.contains("tracker", ignoreCase = true) ||
                cleanUrl.contains("thumb", ignoreCase = true) ||
                cleanUrl.contains("poster", ignoreCase = true)) {
                continue
            }

            if (scrapedUrls.add(cleanUrl)) {
                extractVideoUrl(cleanUrl, match, html, effectiveReferer, callback)
            }
        }

        // ----- 2. JSON-like structures with common keys -----
        val jsonPatterns = listOf(
            Regex("""\{[^{}]*"(?:url|videoUrl|src|file)"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"[^{}]*\}"""),
            Regex("""\{(?:[^{}]*"(?:url|src|file)"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"[^{}]*?)\}""")
        )
        for (pattern in jsonPatterns) {
            for (match in pattern.findAll(html)) {
                val videoUrl = match.groupValues[1]
                if (scrapedUrls.add(videoUrl)) {
                    val quality = findQuality(match.range, html)
                    val displayLabel = if (quality > 0) "$name ${quality}p" else name
                    callback(
                        newExtractorLink(
                            name,
                            displayLabel,
                            videoUrl,
                            INFER_TYPE
                        ) {
                            this.referer = effectiveReferer
                            this.quality = quality
                        }
                    )
                }
            }
        }

        // ----- 3. JavaScript `window` objects containing video data -----
        val windowPatterns = listOf(
            Regex("""window\.VIDEO\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL),
            Regex("""window\.rumble\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL),
            Regex("""window\.videoData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        )
        for (pattern in windowPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                val jsonStr = match.groupValues[1]
                val urlMatches = Regex(""""(?:url|videoUrl|src|file|hls|mp4)"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""").findAll(jsonStr)
                for (urlMatch in urlMatches) {
                    val videoUrl = urlMatch.groupValues[1]
                    if (scrapedUrls.add(videoUrl)) {
                        val quality = findQuality(urlMatch.range, jsonStr)
                        val displayLabel = if (quality > 0) "$name ${quality}p" else name
                        callback(
                            newExtractorLink(
                                name,
                                displayLabel,
                                videoUrl,
                                INFER_TYPE
                            ) {
                                this.referer = effectiveReferer
                                this.quality = quality
                            }
                        )
                    }
                }
                if (scrapedUrls.isNotEmpty()) break
            }
        }

        // ----- 4. Fallback to generic extractor -----
        if (scrapedUrls.isEmpty()) {
            Log.d(name, "No video found, falling back to generic loadExtractor")
            loadExtractor(url, referer = effectiveReferer, subtitleCallback, callback)
        }
    }

    // Marked as suspend because it calls suspend functions
    private suspend fun extractVideoUrl(
        cleanUrl: String,
        match: MatchResult,
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (cleanUrl.contains(".m3u8")) {
            M3u8Helper.generateM3u8(name, cleanUrl, referer).forEach(callback)
        } else if (cleanUrl.contains(".mp4")) {
            val quality = findQuality(match.range, html)
            val displayLabel = if (quality > 0) "$name ${quality}p" else name
            callback(
                newExtractorLink(
                    name,
                    displayLabel,
                    cleanUrl,
                    INFER_TYPE
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            )
        }
    }

    private fun findQuality(range: IntRange, text: String): Int {
        val start = maxOf(0, range.first - 250)
        val preceding = text.substring(start, range.first)
        val qMatch = Regex("""(?:\\"h\\"|"h"|height|quality)\s*:\s*(\d{3,4})""")
            .findAll(preceding).lastOrNull()
            ?: Regex("""(?:\\"|")(\d{3,4})(?:\\"|")\s*:\s*\{""")
                .findAll(preceding).lastOrNull()
        return qMatch?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
    }
}

// ----- 3. PlayStreamplay (allsub player) – improved (no suspend issues) -----
class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to mainUrl,
        "Origin" to mainUrl
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "Loading embed: $url")
        val effectiveReferer = referer ?: mainUrl

        val html = try {
            val requestHeaders = headers.toMutableMap().apply {
                this["Referer"] = effectiveReferer
            }
            app.get(url, headers = requestHeaders).text
        } catch (e: Exception) {
            Log.e(name, "Failed to fetch embed page: ${e.message}")
            return
        }

        // 1) Direct m3u8
        var m3u8 = findUrl(html, Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""))
        if (m3u8 != null) {
            Log.d(name, "Found direct m3u8: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, effectiveReferer).forEach(callback)
            return
        }

        // 2) Unpack packed JavaScript (eval(...))
        val packedRegex = Regex("""eval\s*\(function\s*\([^)]*\)\s*\{[^}]*\}\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val packedMatch = packedRegex.find(html)
        if (packedMatch != null) {
            try {
                val unpacked = JsUnpacker(packedMatch.value).unpack()
                if (unpacked != null) {
                    m3u8 = findUrl(unpacked, Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""))
                    if (m3u8 == null) {
                        m3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)
                    }
                    if (m3u8 != null) {
                        Log.d(name, "Found m3u8 in unpacked: $m3u8")
                        M3u8Helper.generateM3u8(name, m3u8, effectiveReferer).forEach(callback)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(name, "Failed to unpack JavaScript: ${e.message}")
            }
        }

        // 3) JSON patterns
        val jsonPatterns = listOf(
            Regex("""\{[^{}]*"file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"[^{}]*\}"""),
            Regex("""\{[^{}]*"src"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"[^{}]*\}"""),
            Regex("""\{[^{}]*"url"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"[^{}]*\}""")
        )
        for (pattern in jsonPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                m3u8 = match.groupValues[1]
                Log.d(name, "Found m3u8 in JSON: $m3u8")
                M3u8Helper.generateM3u8(name, m3u8, effectiveReferer).forEach(callback)
                return
            }
        }

        // 4) kaken token → API
        val token = Regex("""kaken\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (token != null) {
            val apiUrl = "$mainUrl/api/?$token"
            Log.d(name, "Calling API: $apiUrl")
            try {
                val apiJson = app.get(apiUrl, headers = headers).text
                val apiM3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(apiJson)?.groupValues?.get(1)
                if (apiM3u8 != null) {
                    Log.d(name, "Found m3u8 via API: $apiM3u8")
                    M3u8Helper.generateM3u8(name, apiM3u8, effectiveReferer).forEach(callback)
                    return
                }
            } catch (e: Exception) {
                Log.w(name, "API call failed: ${e.message}")
            }
        }

        // 5) Fallback to generic extractor
        Log.d(name, "No m3u8 found, falling back to generic loadExtractor")
        loadExtractor(url, referer = effectiveReferer, subtitleCallback, callback)
    }

    private fun findUrl(text: String, regex: Regex): String? {
        return regex.find(text)?.groupValues?.get(1)
    }
}
