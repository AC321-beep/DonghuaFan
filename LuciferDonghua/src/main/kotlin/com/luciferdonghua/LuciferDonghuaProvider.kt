package com.luciferdonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidHidePro
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import kotlinx.coroutines.* 

class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "Lucifer Donghua"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasQuickSearch = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val supportedTypes = setOf(TvType.Anime)

    // ... [Use your established getMainPage, search, and load functions here] ...

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        var anyStreamFound = false

        suspend fun processDoc(doc: org.jsoup.nodes.Document, referer: String) {
            // Dailymotion Logic
            doc.select("iframe[src*='dailymotion']").forEach { iframe ->
                val match = Regex("""[?&]video=([^&]+)""").find(iframe.attr("src"))
                if (match != null) {
                    val embed = "https://geo.dailymotion.com/player/xkyen.html?video=${match.groupValues[1]}"
                    if (loadExtractor(embed, referer, subtitleCallback, callback)) anyStreamFound = true
                }
            }

            // VidHidePro and other iframes
            doc.select("iframe").forEach { iframe ->
                var src = iframe.attr("src")
                if (src.startsWith("//")) src = "https:$src"
                if (src.contains("vidhide", true)) {
                    try { VidHidePro().getUrl(src, referer, subtitleCallback, callback); anyStreamFound = true } catch(e: Exception) {}
                } else if (loadExtractor(src, referer, subtitleCallback, callback)) {
                    anyStreamFound = true
                }
            }
        }

        val baseDoc = try { app.get(data, headers = defaultHeaders).document } catch(e: Exception) { return@coroutineScope false }
        processDoc(baseDoc, data)

        // Process mirrors in parallel using coroutineScope
        baseDoc.select("select.mirror option").mapNotNull { it.attr("value") }.distinct().map { mirror ->
            async {
                try {
                    val resp = app.get(mirror, headers = defaultHeaders)
                    if (resp.url != mirror && !resp.url.contains("luciferdonghua.in")) {
                        if (resp.url.contains("vidhide", true)) {
                            VidHidePro().getUrl(resp.url, data, subtitleCallback, callback)
                        } else {
                            loadExtractor(resp.url, data, subtitleCallback, callback)
                        }
                    } else {
                        processDoc(resp.document, mirror)
                    }
                } catch(e: Exception) {}
            }
        }.awaitAll()

        return@coroutineScope anyStreamFound
    }
}
