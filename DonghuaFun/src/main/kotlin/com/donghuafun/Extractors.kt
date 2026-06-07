package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.util.UUID

open class KSRPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "DonghuaFun-KSR"
        private const val TIMEOUT_MS = 10000L 
        private const val CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private const val CDN_REFERER = "https://play.donghuafun.com" 
        
        val STREAM_PATTERNS = listOf(
            Regex("""(?:var|let|const)\s+url\s*=\s*["'](https?://[^"']+)["']"""),
            Regex("""["']url["']\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""let\s+config\s*=\s*\{[\s\S]*?url\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)"""),
            Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)"""),
            Regex("""(https?:\\/\\/[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)"""),
            Regex("""(https?://[^\s"'<>\\]+playlist[^\s"'<>\\]*)""")
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "KSR Extractor processing URL target -> $url")
        val freshSessId = UUID.randomUUID().toString()
        val generatedUid = "yfwcjdv${System.currentTimeMillis()}lgdv"

        val html = try {
            app.get(
                url,
                referer = referer ?: "https://donghuafun.com/",
                timeout = TIMEOUT_MS,
                headers = mapOf(
                    "User-Agent" to CHROME_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Cookie" to "__scdnsessid=$freshSessId; __scdnuid=$generatedUid;",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Upgrade-Insecure-Requests" to "1",
                )
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "Network read cycle faulted on player target frame source: ${e.message}")
            return
        }

        Log.d(TAG, "DOM Processing Stream Engine - Document Size: ${html.length} chars")

        val knownEmbeds = listOf("dailymotion.com", "ok.ru", "okru")
        for (embed in knownEmbeds) {
            if (html.contains(embed)) {
                val embedUrl = Regex("""(https?://[^\s"'<>\\]*${Regex.escape(embed)}[^\s"'<>\\]*)""")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                if (!embedUrl.isNullOrEmpty()) {
                    Log.d(TAG, "Delegating structural nested embed point -> $embedUrl")
                    loadExtractor(embedUrl, url, subtitleCallback, callback)
                    return
                }
            }
        }

        val streamUrl = extractStreamUrl(html)
        if (streamUrl != null) {
            invokeStreamLink(ensureHttps(streamUrl), CDN_REFERER, callback)
            return
        }

        val iframeSrc = Regex("""<iframe[\s\S]*?src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!iframeSrc.isNullOrEmpty()) {
            var nestedUrl = iframeSrc.replace("\\/", "/")
            nestedUrl = when {
                nestedUrl.startsWith("//") -> "https:$nestedUrl"
                nestedUrl.startsWith("/") -> "https://play.donghuafun.com$nestedUrl"
                else -> nestedUrl
            }
            Log.d(TAG, "Running internal recursion check down secondary inner framework: $nestedUrl")
            
            try {
                val subHtml = app.get(nestedUrl, referer = url, timeout = TIMEOUT_MS, headers = mapOf("User-Agent" to CHROME_UA)).text
                val nestedUrl2 = extractStreamUrl(subHtml)
                if (nestedUrl2 != null) {
                    invokeStreamLink(ensureHttps(nestedUrl2), CDN_REFERER, callback)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Secondary framework recursive lookup error down pipeline branch: ${e.message}")
            }
        }
        Log.w(TAG, "No valid manifest signatures could be generated for structural tracking points.")
    }

    private fun ensureHttps(url: String) = if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url

    private fun extractStreamUrl(html: String): String? {
        for ((index, pattern) in STREAM_PATTERNS.withIndex()) {
            val match = pattern.find(html) ?: continue
            var raw = if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) match.groupValues[1] else match.value
            raw = raw.replace("\\/", "/").trim()
            
            if (index == 3) { // Base64 Decode
                raw = try {
                    String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).replace("\\/", "/").trim()
                } catch (e: Exception) {
                    continue
                }
            }
            if (!raw.startsWith("http")) continue
            Log.d(TAG, "Regex Signature Match Index [#$index]: $raw")
            return raw
        }
        return null
    }

    private suspend fun invokeStreamLink(
        streamUrl: String,
        cdnReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isPlaylist = streamUrl.contains(".m3u8") || streamUrl.contains("playlist")
        
        val playerHeaders = mapOf(
            "User-Agent" to CHROME_UA,
            "Referer" to "$cdnReferer/",
            "Origin" to cdnReferer,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Dest" to "empty"
        )

        Log.d(TAG, "Packaging Stream Link Resource: $streamUrl | Playlist: $isPlaylist")
        if (isPlaylist) {
            try {
                M3u8Helper.generateM3u8(
                    name = name,
                    source = streamUrl,
                    referer = "$cdnReferer/",
                    headers = playerHeaders
                ).forEach(callback)
            } catch (e: Exception) {
                Log.e(TAG, "HLS Manifest Engine Generation Phase Fail: ${e.message}")
            }
        } else {
            callback(newExtractorLink(name, name, streamUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$cdnReferer/"
                this.quality = Qualities.P1080.value
                this.headers = playerHeaders
            })
        }
    }
}
