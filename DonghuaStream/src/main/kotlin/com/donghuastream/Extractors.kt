package com.donghuastream

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder

// ----- Dailymotion Extractor (uncommented and included) -----
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

// ----- Rumble Extractor (unchanged) -----
class Rumble : ExtractorApi() {
    // ... (keep your Rumble code exactly as it is) ...
}

// ----- IMPROVED PlayStreamplay (All sub player) -----
class PlayStreamplay : ExtractorApi() {
    // ... (keep your PlayStreamplay code exactly as it is) ...
}
