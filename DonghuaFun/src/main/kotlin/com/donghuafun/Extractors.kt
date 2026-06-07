package com.donghuafun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// ── KSR Player (play.donghuafun.com) ─────────────────────────────────────────
// Donghua Fun's own video player. Enhanced to support direct video assets 
// and handle nested third-party provider streams (e.g., Dailymotion).

open class KSRPlayer : ExtractorApi() {
    override var name = "DonghuaFun"
    override var mainUrl = "https://play.donghuafun.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            referer = referer ?: "https://donghuafun.com/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            )
        )
        val html = response.text

        // Method 1: MxoneCMS player_aaaa JS variable (most common)
        val playerJson = Regex("""player_aaaa\s*=\s*(\{[^<]+?\})""").find(html)?.groupValues?.get(1)
        if (playerJson != null) {
            var videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/")
            val videoType = Regex(""""type"\s*:\s*"([^"]+)"""")
                .find(playerJson)?.groupValues?.get(1) ?: "m3u8"

            if (!videoUrl.isNullOrEmpty()) {
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"

                // Check if it's a direct streamable file asset
                if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || videoType.contains("m3u8") || videoType.contains("hls")) {
                    val isM3u8 = videoType.contains("m3u8") || videoType.contains("hls") || videoUrl.contains(".m3u8")
                    if (isM3u8) {
                        M3u8Helper.generateM3u8(name, videoUrl, referer ?: mainUrl).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                                this.referer = referer ?: mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    return
                } else {
                    // It's an embed provider link (e.g. Dailymotion), route to core suite
                    if (loadExtractor(videoUrl, referer ?: mainUrl, subtitleCallback, callback)) return
                }
            }
        }

        // Method 2: Packed JS (function(p,a,c,k,e,d){...})
        val packedScript = response.document
            .selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
        if (packedScript != null) {
            val unpacked = JsUnpacker(packedScript).unpack()
            if (unpacked != null) {
                var fileUrl = Regex("""sources:\s*\[\s*\{[^}]*file\s*:\s*"([^"]+)"""")
                    .find(unpacked)?.groupValues?.get(1)?.replace("\\/", "/")
                if (!fileUrl.isNullOrEmpty()) {
                    if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"

                    if (fileUrl.contains(".m3u8") || fileUrl.contains(".mp4")) {
                        val isM3u8 = fileUrl.contains(".m3u8")
                        if (isM3u8) {
                            M3u8Helper.generateM3u8(name, fileUrl, referer ?: mainUrl).forEach(callback)
                        } else {
                            callback(
                                newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = referer ?: mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                        return
                    } else {
                        if (loadExtractor(fileUrl, referer ?: mainUrl, subtitleCallback, callback)) return
                    }
                }
            }
        }

        // Method 3: KSR player API call — the player fetches stream info via AJAX
        val ajaxUrl = Regex("""\$\.ajax\(\s*\{\s*url\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
        if (!ajaxUrl.isNullOrEmpty()) {
            val apiResp = app.get(ajaxUrl, referer = url).parsedSafe<KSRApiResponse>()
            apiResp?.sources?.firstOrNull { it.file.isNotBlank() }?.let { src ->
                var srcFile = src.file
                if (srcFile.startsWith("//")) srcFile = "https:$srcFile"

                if (srcFile.contains(".m3u8") || srcFile.contains(".mp4") || src.type.contains("hls")) {
                    val isM3u8 = srcFile.contains(".m3u8") || src.type.contains("hls")
                    if (isM3u8) {
                        M3u8Helper.generateM3u8(name, srcFile, referer ?: mainUrl).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(name, name, srcFile, ExtractorLinkType.VIDEO) {
                                this.referer = referer ?: mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } else {
                    loadExtractor(srcFile, referer ?: mainUrl, subtitleCallback, callback)
                }
            }
            apiResp?.tracks?.forEach { track ->
                subtitleCallback(
                    com.lagradost.cloudstream3.newSubtitleFile(track.label, track.file)
                )
            }
            return
        }

        // Method 4: Last resort — scan raw HTML for any frame elements or embedded paths
        val iframeSrc = response.document.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrEmpty()) {
            if (loadExtractor(fixUrl(iframeSrc), referer ?: mainUrl, subtitleCallback, callback)) return
        }

        val directUrl = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""")
            .find(html)?.value
        if (!directUrl.isNullOrEmpty()) {
            val isM3u8 = directUrl.contains(".m3u8")
            if (isM3u8) {
                M3u8Helper.generateM3u8(name, directUrl, referer ?: mainUrl).forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, name, directUrl, ExtractorLinkType.VIDEO) {
                        this.referer = referer ?: mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }

    data class KSRApiResponse(
        val sources: List<KSRSource> = emptyList(),
        val tracks: List<KSRTrack> = emptyList()
    )
    data class KSRSource(val file: String = "", val type: String = "", val label: String = "")
    data class KSRTrack(val file: String = "", val label: String = "")
}
