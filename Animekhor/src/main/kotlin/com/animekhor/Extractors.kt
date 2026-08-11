package com.animekhor

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities

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

// --- STANDALONE VIDHIDE CLONES (Fixes Error 2004) ---
abstract class CustomVidHide : ExtractorApi() {
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/t/", "/e/").replace("/v/", "/e/")
        val response = app.get(fixedUrl, referer = referer ?: "https://animekhor.org/").text
        
        val packed = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""", RegexOption.DOT_MATCHES_ALL).find(response)?.value
        val unpacked = if (packed != null) JsUnpacker(packed).unpack() else response
        
        val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
        
        if (m3u8 != null) {
            @Suppress("DEPRECATION")
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8,
                    referer = fixedUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true, // Passes directly to ExoPlayer
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                    )
                )
            )
        }
    }
}

class Emturbovid : CustomVidHide() {
    override var name = "Player"
    override var mainUrl = "https://emturbovid.com"
}

class Listeamed : CustomVidHide() {
    override var name = "VGPlayer"
    override var mainUrl = "https://listeamed.net"
}

class AbyssPlayer : CustomVidHide() {
    override var name = "AbyssPlayer"
    override var mainUrl = "https://abyssplayer.com"
}

// --- STANDALONE FILEMOON CLONES ---
abstract class CustomFilemoon : ExtractorApi() {
    override val requiresReferer = true
    
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val fixedUrl = url.replace("/#", "/e/")
        val response = app.get(fixedUrl, referer = referer ?: "https://animekhor.org/").text
        
        val packed = Regex("""eval\(function\(p,a,c,k,e,.*?\).*?split\('\|'\).*?\)""", RegexOption.DOT_MATCHES_ALL).find(response)?.value
        val unpacked = if (packed != null) JsUnpacker(packed).unpack() else response
        
        val m3u8 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked ?: "")?.groupValues?.get(1)
        
        if (m3u8 != null) {
            @Suppress("DEPRECATION")
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8,
                    referer = fixedUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = mapOf(
                        "Origin" to "https://filemoon.sx",
                        "Referer" to "https://filemoon.sx/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                    )
                )
            )
        }
    }
}

class P2pstream : CustomFilemoon() {
    override var name = "FileMoon Player"
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class UpnsLive : CustomFilemoon() {
    override var name = "CloudPlayer"
    override var mainUrl = "https://animekhor.upns.live"
}

class Rumble : ExtractorApi() {
    override val name = "Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = try { app.get(url, referer = mainUrl).text } catch (e: Exception) { return }
        val matches = Regex("""https?:(?:\\/|/)(?:\\/|/)[^"'\s<>‘’“”]+\.(?:mp4|m3u8)[^"'\s<>‘’“”]*""").findAll(html)

        matches.forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            if (!cleanUrl.contains("/assets/", true) && !cleanUrl.contains("loop", true)) {
                @Suppress("DEPRECATION")
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = cleanUrl,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = cleanUrl.contains(".m3u8")
                    )
                )
            }
        }
    }
}
