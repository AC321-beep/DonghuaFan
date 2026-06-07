package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType

open class DonghuaFunExtractor : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://donghuafun.com"
    override val requiresReferer = true

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://donghuafun.com/"
        )

        // Step 1: Get HTML page
        val html = try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            println("Failed to get HTML: ${e.message}")
            return
        }

        // Step 2: Extract player_aaaa JSON from script
        val playerRegex = Regex("""var player_aaaas*=s*({[^}]+})""")
        val playerJson = playerRegex.find(html)?.groupValues?.get(1) ?: return

        println("Player JSON: $playerJson")

        // Step 3: Parse video info using regex (simpler than JSON parser)
        val videoIdRegex = Regex(""""url":"([^"]+)""")
        val fromRegex = Regex(""""from":"([^"]+)""")
        val serverRegex = Regex(""""server":"([^"]+)""")

        val videoId = videoIdRegex.find(playerJson)?.groupValues?.get(1) ?: return
        val from = fromRegex.find(playerJson)?.groupValues?.get(1) ?: "unknown"
        val server = serverRegex.find(playerJson)?.groupValues?.get(1) ?: "no"

        println("Video ID: $videoId")
        println("From: $from")
        println("Server: $server")

        // Step 4: Handle Dailymotion (most common)
        if (from.lowercase() == "dailymotion") {
            val dailymotionUrl = "https://www.dailymotion.com/embed/video/$videoId"
            
            // Get Dailymotion player page to extract m3u8
            val playerHtml = app.get(dailymotionUrl, headers = headers).text
            
            // Extract m3u8 URL from Dailymotion player
            val m3u8Regex = Regex("""(https?://[^s"']+dailymotion[^"s]*.m3u8[^s"']*)""")
            val m3u8Urls = m3u8Regex.findAll(playerHtml).map { it.value }.toList()

            println("Found ${m3u8Urls.size} Dailymotion m3u8 URLs: $m3u8Urls")

            m3u8Urls.forEach { streamUrl ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = "${name} - Dailymotion 1080p",
                        url = streamUrl,
                        referer = "https://www.dailymotion.com/",
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "https://www.dailymotion.com/"
                        )
                    )
                )
            }

            // Fallback: Use direct Dailymotion embed URL
            if (m3u8Urls.isEmpty()) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "${name} - Dailymotion",
                        url = dailymotionUrl,
                        referer = "https://donghuafun.com/",
                        quality = Qualities.P720.value,
                        type = ExtractorLinkType.VIDEO,
                        headers = headers
                    )
                )
            }
        }

        // Step 5: Handle other common video hosts
        else if (from.lowercase() in listOf("vod", "mp4", "local")) {
            // Direct video URL on same server
            callback(
                ExtractorLink(
                    source = name,
                    name = "${name} - ${from.uppercase()}",
                    url = "https://donghuafun.com$videoId",
                    referer = "https://donghuafun.com/",
                    quality = Qualities.P720.value,
                    type = ExtractorLinkType.VIDEO,
                    headers = headers
                )
            )
        }

        // Step 6: Fallback - scan entire HTML for m3u8 URLs
        if (!html.contains("ExtractorLink")) {
            val allM3u8Regex = Regex("""(https?://[^s"']+.m3u8[^s"']*)""")
            val allM3u8Urls = allM3u8Regex.findAll(html).map { it.value }.toList()

            println("Fallback: Found ${allM3u8Urls.size} m3u8 URLs in HTML")

            allM3u8Urls.forEach { streamUrl ->
                callback(
                    ExtractorLink(
                        source = name,
                        name = "${name} - HLS",
                        url = streamUrl,
                        referer = "https://donghuafun.com/",
                        quality = Qualities.P720.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
                println("Added: $streamUrl")
            }
        }
    }
}
