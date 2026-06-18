package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder

// ----- 1. Dailymotion Extractor -----
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

// ----- 2. Rumble Extractor -----
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
                    val startIndex = maxOf(0, match.range.first - 150)
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

// ----- 3. IMPROVED PlayStreamplay (All sub player) -----
class PlayStreamplay : ExtractorApi() {
    override val name = "All sub player"
    override val mainUrl = "https://play.streamplay.co.in"
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
