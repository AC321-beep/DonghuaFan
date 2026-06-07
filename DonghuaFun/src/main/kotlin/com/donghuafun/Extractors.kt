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

        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        // CDN referer accepted by the stream servers
        private const val CDN_REFERER = "https://play.donghuafun.com/"

        /**
         * Ordered list of extraction patterns.
         *
         * Priority 1-3: explicit JS variable assignments (most reliable).
         * Priority 4:   atob()-wrapped Base64 blobs (very common on modern players).
         * Priority 5-6: direct URL literals with m3u8/mp4 extension.
         * Priority 7:   escaped URL literals (slashes replaced with \/).
         * Priority 8:   playlist keyword fallback.
         *
         * Each pattern captures a single group: the raw value to decode/clean.
         */
        val STREAM_PATTERNS = listOf(
            // 1. var url = "https://..."  or  let/const url = "https://..."
            Regex("""(?:var|let|const)\s+url\s*=\s*["'](https?://[^"']+)["']"""),
            // 2. { url: "https://..." }  or  "url":"https://..."
            Regex("""["']url["']\s*:\s*["'](https?://[^"']+)["']"""),
            // 3. let config = { ..., url: "https://..." }  (multiline)
            Regex("""let\s+config\s*=\s*\{[\s\S]*?url\s*:\s*["'](https?://[^"']+)["']"""),
            // 4. atob("BASE64") — Base64-encoded stream URL
            Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]{20,})["']\s*\)"""),
            // 5. Bare https URL ending in .m3u8 or .mp4 (with optional query string)
            Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)"""),
            // 6. Escaped https URL  https:\/\/...\.m3u8 or .mp4
            Regex("""(https?:\\/\\/[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)"""),
            // 7. Playlist keyword fallback
            Regex("""(https?://[^\s"'<>\\]+playlist[^\s"'<>\\]*)"""),
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl: $url  referer=$referer")

        val freshSessId = UUID.randomUUID().toString()
        val generatedUid = "yfwcjdv${System.currentTimeMillis()}lgdv"

        // ── Step 1: Fetch the player page ─────────────────────────────────────
        val response = app.get(
            url,
            referer = referer ?: "https://donghuafun.com/",
            headers = mapOf(
                "User-Agent"                to CHROME_UA,
                "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Cookie"                    to "__scdnsessid=$freshSessId; __scdnuid=$generatedUid;",
                "Sec-Fetch-Dest"            to "iframe",
                "Sec-Fetch-Mode"            to "navigate",
                "Sec-Fetch-Site"            to "cross-site",
                "Upgrade-Insecure-Requests" to "1",
            )
        )
        val html = response.text
        Log.d(TAG, "Player HTML snippet: ${html.take(3000)}")

        // ── Step 2: Try all patterns against the primary page ─────────────────
        val directResult = extractStreamUrl(html)
        if (directResult != null) {
            // Use the player page URL as CDN referer so the CDN accepts the request
            invokeStreamLink(directResult, CDN_REFERER, callback)
            return
        }

        // ── Step 3: Check for a nested iframe and recurse one level ───────────
        val iframeSrc = Regex("""<iframe[\s\S]*?src=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)

        if (!iframeSrc.isNullOrEmpty()) {
            var nestedUrl = iframeSrc.replace("\\/", "/")
            when {
                nestedUrl.startsWith("//") -> nestedUrl = "https:$nestedUrl"
                nestedUrl.startsWith("/")  -> nestedUrl = "https://play.donghuafun.com$nestedUrl"
            }

            Log.d(TAG, "Nested iframe: $nestedUrl")

            val subResponse = app.get(
                nestedUrl,
                referer = url,
                headers = mapOf(
                    "User-Agent" to CHROME_UA,
                    "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
            )
            val subHtml = subResponse.text
            Log.d(TAG, "Nested HTML snippet: ${subHtml.take(3000)}")

            val nestedResult = extractStreamUrl(subHtml)
            if (nestedResult != null) {
                invokeStreamLink(nestedResult, CDN_REFERER, callback)
                return
            }
        }

        Log.d(TAG, "KSRPlayer: exhausted all strategies, no link found for $url")
    }

    // ── Pattern Extraction Helper ──────────────────────────────────────────────

    /**
     * Runs every pattern in [STREAM_PATTERNS] against [html].
     * For atob() matches (index 3), Base64-decodes the captured group.
     * Returns the first clean absolute URL found; null if nothing matches.
     */
    private fun extractStreamUrl(html: String): String? {
        for ((index, pattern) in STREAM_PATTERNS.withIndex()) {
            val match = pattern.find(html) ?: continue

            var raw = if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty())
                match.groupValues[1]
            else
                match.value

            raw = raw.replace("\\/", "/").trim()

            // atob pattern (index 3) → Base64-decode the captured value
            if (index == 3) {
                raw = try {
                    String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
                        .replace("\\/", "/").trim()
                } catch (e: Exception) {
                    Log.e(TAG, "atob decode failed: ${e.message}")
                    continue
                }
            }

            if (!raw.startsWith("http")) continue

            Log.d(TAG, "Pattern #$index matched: $raw")
            return raw
        }
        return null
    }

    // ── Stream Link Invoker ────────────────────────────────────────────────────

    /**
     * Delivers the resolved stream URL to CloudStream.
     *
     * The [cdnReferer] MUST be the player origin (play.donghuafun.com) so the
     * CDN's hotlink protection accepts the request. Passing the episode page URL
     * here is what caused ERROR_CODE_IO_BAD_HTTP_STATUS (2004) — the CDN saw a
     * foreign referer and returned 403.
     */
    private suspend fun invokeStreamLink(
        streamUrl: String,
        cdnReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = streamUrl.replace("\\/", "/")
        val isPlaylist = cleanUrl.contains(".m3u8") || cleanUrl.contains("playlist")

        Log.d(TAG, "invokeStreamLink: $cleanUrl  playlist=$isPlaylist  referer=$cdnReferer")

        // Headers sent with every segment/chunk request
        val streamHeaders = mapOf(
            "User-Agent" to CHROME_UA,
            "Referer"    to cdnReferer,
            "Origin"     to "https://play.donghuafun.com",
        )

        if (isPlaylist) {
            M3u8Helper.generateM3u8(
                source    = name,
                streamUrl = cleanUrl,
                referer   = cdnReferer,
                headers   = streamHeaders,
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = cleanUrl,
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.referer = cdnReferer
                    this.quality = Qualities.P1080.value
                    this.headers = streamHeaders
                }
            )
        }
    }
}
