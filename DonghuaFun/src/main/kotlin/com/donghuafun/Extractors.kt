package com.donghuafun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.util.UUID

/**
 * KSRPlayer is a fallback extractor for play.donghuafun.com iframes.
 * The primary sources (Dailymotion, OkRu) are handled by loadExtractor()
 * in DonghuaFunProvider directly — this only fires when the site uses its
 * own custom player iframe instead of a known embed.
 */
open class KSRPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "DonghuaFun-KSR"
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private const val CDN_REFERER = "https://play.donghuafun.com/"

        val STREAM_PATTERNS = listOf(
            // 1. var/let/const url = "https://..."
            Regex("""(?:var|let|const)\s+url\s*=\s*["'](https?://[^"']+)["']"""),
            // 2. { url: "https://..." }
            Regex("""["']url["']\s*:\s*["'](https?://[^"']+)["']"""),
            // 3. let config = { ..., url: "https://..." }  (multiline)
            Regex("""let\s+config\s*=\s*\{[\s\S]*?url\s*:\s*["'](https?://[^"']+)["']"""),
            // 4. atob("BASE64") encoded URL
            Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)"""),
            // 5. Bare https .m3u8 or .mp4 URL
            Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)"""),
            // 6. Escaped https .m3u8 or .mp4 URL
            Regex("""(https?:\\/\\/[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)"""),
            // 7. Playlist fallback
            Regex("""(https?://[^\s"'<>\\]+playlist[^\s"'<>\\]*)"""),
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl: $url")

        val freshSessId = UUID.randomUUID().toString()
        val generatedUid = "yfwcjdv${System.currentTimeMillis()}lgdv"

        val response = app.get(
            url,
            referer = referer ?: "https://donghuafun.com/",
            headers = mapOf(
                "User-Agent"                to CHROME_UA,
                "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Cookie"                    to "__scdnsessid=$freshSessId; __scdnuid=$generatedUid;",
                "Sec-Fetch-Dest"            to "iframe",
                "Sec-Fetch-Mode"            to "navigate",
                "Sec-Fetch-Site"            to "cross-site",
                "Upgrade-Insecure-Requests" to "1",
            )
        )
        val html = response.text
        Log.d(TAG, "KSR HTML:\n$html")

        // Check if this iframe actually contains a known embed we can delegate
        val knownEmbeds = listOf("dailymotion.com", "ok.ru", "okru")
        for (embed in knownEmbeds) {
            if (html.contains(embed)) {
                val embedUrl = Regex("""(https?://[^\s"'<>\\]*${Regex.escape(embed)}[^\s"'<>\\]*)""")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                if (!embedUrl.isNullOrEmpty()) {
                    Log.d(TAG, "Delegating known embed: $embedUrl")
                    loadExtractor(embedUrl, url, subtitleCallback, callback)
                    return
                }
            }
        }

        // Try direct stream extraction patterns
        val streamUrl = extractStreamUrl(html)
        if (streamUrl != null) {
            invokeStreamLink(ensureHttps(streamUrl), CDN_REFERER, callback)
            return
        }

        // Nested iframe fallback
        val iframeSrc = Regex("""<iframe[\s\S]*?src=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        if (!iframeSrc.isNullOrEmpty()) {
            var nestedUrl = iframeSrc.replace("\\/", "/")
            nestedUrl = when {
                nestedUrl.startsWith("//") -> "https:$nestedUrl"
                nestedUrl.startsWith("/")  -> "https://play.donghuafun.com$nestedUrl"
                else                       -> nestedUrl
            }
            Log.d(TAG, "Nested iframe: $nestedUrl")
            val subHtml = app.get(nestedUrl, referer = url,
                headers = mapOf("User-Agent" to CHROME_UA)).text
            Log.d(TAG, "Nested HTML:\n$subHtml")
            val nestedUrl2 = extractStreamUrl(subHtml)
            if (nestedUrl2 != null) {
                invokeStreamLink(ensureHttps(nestedUrl2), CDN_REFERER, callback)
                return
            }
        }

        Log.d(TAG, "KSRPlayer: no link found for $url")
    }

    private fun ensureHttps(url: String) =
        if (url.startsWith("http://")) url.replaceFirst("http://", "https://") else url

    private fun extractStreamUrl(html: String): String? {
        for ((index, pattern) in STREAM_PATTERNS.withIndex()) {
            val match = pattern.find(html) ?: continue
            var raw = if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty())
                match.groupValues[1] else match.value
            raw = raw.replace("\\/", "/").trim()
            if (index == 3) {
                raw = try {
                    String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
                        .replace("\\/", "/").trim()
                } catch (e: Exception) { Log.e(TAG, "atob fail: ${e.message}"); continue }
            }
            if (!raw.startsWith("http")) continue
            Log.d(TAG, "Pattern #$index: $raw")
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
        val headers = mapOf(
            "User-Agent" to CHROME_UA,
            "Referer"    to cdnReferer,
            "Origin"     to "https://play.donghuafun.com",
        )
        Log.d(TAG, "invokeStreamLink: $streamUrl  playlist=$isPlaylist")
        if (isPlaylist) {
            // M3u8Helper.generateM3u8 signature: (source, streamUrl, referer, quality?)
            // Headers are NOT a parameter — pass them via the ExtractorLink builder instead.
            try {
                val resolved = app.get(streamUrl, headers = headers, referer = cdnReferer).url
                M3u8Helper.generateM3u8(name, resolved, cdnReferer).forEach(callback)
            } catch (e: Exception) {
                M3u8Helper.generateM3u8(name, streamUrl, cdnReferer).forEach(callback)
            }
        } else {
            callback(newExtractorLink(name, name, streamUrl, ExtractorLinkType.VIDEO) {
                this.referer = cdnReferer
                this.quality = Qualities.P1080.value
                this.headers = headers
            })
        }
    }
}
