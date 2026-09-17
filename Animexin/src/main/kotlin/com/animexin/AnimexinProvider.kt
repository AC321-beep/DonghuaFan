package com.Animexin

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val cfInterceptor = CFInterceptor()

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Recently Updated",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&order=update" to "Movies",
        "anime/?status=&sub=raw&order=update" to "Anime (RAW)"
    )

    private suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        CommonActivity.activity?.runOnUiThread {
            val dialog = CFDialog(url) { success ->
                if (!resumed) {
                    resumed = true
                    cont.resume(success)
                }
            }
            dialog.show()
        } ?: run {
            if (!resumed) {
                resumed = true
                cont.resume(false)
            }
        }
    }

    private suspend fun getSafeDocument(url: String): Document {
        var response = app.get(url, interceptor = cfInterceptor)
        var doc = response.document
        val title = doc.title().lowercase()
        
        val isChallenge = listOf("just a moment", "security verification", "attention required", "cloudflare").any { title.contains(it) } || doc.select("div.cf-turnstile").isNotEmpty()
        
        if (isChallenge || response.code in listOf(403, 503)) {
            val success = resolveCloudflare(url)
            if (success) {
                response = app.get(url, interceptor = cfInterceptor)
                doc = response.document
            } else {
                throw Error("Cloudflare bypass was cancelled or failed.")
            }
        }
        return doc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val query = request.data.substringAfter("?", "")
            if (query.isNotEmpty()) "$mainUrl/anime/?page=$page&$query" else "$mainUrl/anime/page/$page/"
        }

        val document = getSafeDocument(url)
        val items = document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx, .postbody article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl || href.isBlank()) return null

        val title = this.selectFirst(".egghead .eggtitle")?.text()?.trim()
            ?: this.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".tt h2, .tt h3, .tt h4")?.text()?.trim()
            ?: aTag.attr("title").trim()

        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        var poster = img?.let { 
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        }
        if (poster.isNullOrBlank()) {
            poster = this.selectFirst("noscript img")?.attr("src")
        }

        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()
        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            
            val posterCookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to posterCookies,
                "User-Agent" to CFState.userAgent
            ).filterValues { it.isNotBlank() }

            if (epNum != null) {
                this.addSub(epNum)
            }
            if (href.contains("movie", true) || type.equals("Movie", true)) {
                this.type = TvType.Movie
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getSafeDocument("$mainUrl/?s=$query")
        return document.select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = getSafeDocument(url)

        val seriesBreadcrumb = doc.selectFirst(".ts-breadcrumb li:nth-last-child(2) a, .allc a")?.attr("href")
        if (!seriesBreadcrumb.isNullOrBlank() && seriesBreadcrumb != url && seriesBreadcrumb.contains("/anime/")) {
            doc = getSafeDocument(seriesBreadcrumb)
        }

        val title = doc.selectFirst("h1.entry-title, .infox h1")?.text()?.trim() ?: "Unknown Title"
        
        val img = doc.selectFirst("div.thumb img, div.infox img, .bigcontent img")
        var poster = img?.let { 
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        }
        if (poster.isNullOrBlank()) {
             poster = doc.selectFirst("noscript img")?.attr("src") ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()
        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true

        if (isMovie) {
            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                
                val posterCookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
                this.posterHeaders = mapOf(
                    "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                    "Referer" to "$mainUrl/",
                    "Cookie" to posterCookies,
                    "User-Agent" to CFState.userAgent
                ).filterValues { it.isNotBlank() }
            }
        }

        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .mapNotNull { info ->
                val a = info.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val epImg = info.selectFirst("a img")
                var epPoster = epImg?.let { 
                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                }
                if (epPoster.isNullOrBlank()) epPoster = info.selectFirst("noscript img")?.attr("src")

                val epText = info.selectFirst(".epl-num, .epl-title, .epnum, .epsname")?.text()?.trim() ?: ""
                val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                val dateText = info.selectFirst(".epl-date, .date, .time")?.text()?.trim()

                newEpisode(epHref) {
                    this.name = if (epNum != null) "Episode $epNum" else epText.ifBlank { a.text().trim() }
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(epPoster)
                    if (!dateText.isNullOrBlank()) {
                        this.addDate(dateText, format = "MMMM d, yyyy")
                        this.description = dateText
                    }
                }
            }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            
            val posterCookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to posterCookies,
                "User-Agent" to CFState.userAgent
            ).filterValues { it.isNotBlank() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getSafeDocument(data)
        
        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()

        suspend fun invokeExtractor(iframeUrl: String) {
            var finalUrl = iframeUrl.trim()
            if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (finalUrl.startsWith("/")) {
                finalUrl = "https://animexin.dev$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            val dedupUrl = finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) return

            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                if (yieldedStreamUrls.add(link.url)) {
                    callback(link)
                }
            }

            try {
                // Explicitly route custom extractors so they actually execute
                val isHandled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRu().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback)
                        true
                    }
                    "d.tube" in finalUrl || "dtube" in finalUrl -> {
                        Dtube().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback)
                        true
                    }
                    else -> false
                }

                if (!isHandled) {
                    loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)
                }
            } catch (e: Exception) {
                // Fails silently
            }
        }

        val servers = document.select(".mobius option, select.mirror option, .server-list li a[data-embed], .server-list li a[data-em], .server option, .player option")
        coroutineScope {
            servers.map { server ->
                async {
                    val rawData = server.attr("value").ifBlank { server.attr("data-em").ifBlank { server.attr("data-embed") } }.trim()
                    if (rawData.isNotBlank()) {
                        val decoded = try { String(Base64.decode(rawData, Base64.DEFAULT)) } catch (e: Exception) { rawData }
                        val iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http") || decoded.startsWith("//")) {
                            decoded
                        } else null

                        if (!iframeSrc.isNullOrBlank()) {
                            invokeExtractor(iframeSrc)
                        }
                    }
                }
            }.awaitAll()
        }

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                invokeExtractor(src)
            }
        }

        return true
    }
}

// ==================== EXTRACTORS ====================

class FileMoonSx : Filesim() {
    override val name = "FileMoonSx"
    override val mainUrl = "https://filemoon.sx"
}

class Waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class Wishfast : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://wishfast.top"
}

class Vtbe : ExtractorApi() {
    override val name = "Vtbe"
    override val mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl).document
        val script = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return null
        val unpacked = JsUnpacker(script).unpack() ?: return null
        val match = Regex("""sources:\s*\[\s*\{\s*file:\s*['"](.*?)['"]""").find(unpacked)
        val link = match?.groupValues?.get(1) ?: return null

        return listOf(
            ExtractorLink(
                source = name,
                name = name,
                url = link,
                referer = referer ?: mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
    }
}

class OkRu : ExtractorApi() {
    override val name = "OkRu" 
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val id = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: url.substringAfterLast("/").substringBefore("?")
            if (id.isBlank()) return

            val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id"
            val jsonStr = app.post(apiUrl).text

            if (!jsonStr.startsWith("{")) return
            val json = JSONObject(jsonStr)

            val videos = json.optJSONArray("videos")
            if (videos != null && videos.length() > 0) {
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    val qName = video.optString("name").lowercase()
                    val vidUrl = video.optString("url")

                    if (vidUrl.isBlank() || vidUrl.contains("usr_login")) continue

                    val qualityValue = when (qName) {
                        "mobile" -> Qualities.P144.value
                        "lowest" -> Qualities.P240.value
                        "low" -> Qualities.P360.value
                        "sd" -> Qualities.P480.value
                        "hd" -> Qualities.P720.value
                        "full" -> Qualities.P1080.value
                        "quad" -> Qualities.P1440.value
                        "ultra" -> Qualities.P2160.value
                        else -> Qualities.Unknown.value
                    }

                    callback(newExtractorLink(name = "${this.name} MP4", source = "${this.name} MP4", url = vidUrl.replace("\\u0026", "&").replace("\\/", "/"), type = INFER_TYPE) {
                        this.referer = "https://ok.ru/"
                        this.quality = qualityValue
                    })
                }
            }

            val hlsUrl = json.optString("hlsManifestUrl")
            if (hlsUrl.isNotBlank() && !hlsUrl.contains("usr_login")) {
                M3u8Helper.generateM3u8("$name HLS", hlsUrl.replace("\\u0026", "&").replace("\\/", "/"), url).forEach(callback)
            } else {
                val dashUrl = json.optString("dashManifestUrl")
                if (dashUrl.isNotBlank() && !dashUrl.contains("usr_login")) {
                    callback(newExtractorLink(name = "$name DASH", source = "$name DASH", url = dashUrl.replace("\\u0026", "&").replace("\\/", "/"), type = ExtractorLinkType.DASH) { 
                        this.referer = "https://ok.ru/" 
                    })
                }
            }
        } catch (e: Exception) {
            // Fails silently
        }
    }
}

class Dtube : ExtractorApi() {
    override val name = "DTube"
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            var videoId: String? = null
            val uuidRegex = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""", RegexOption.IGNORE_CASE)
            
            videoId = uuidRegex.find(url)?.groupValues?.get(1)
            if (videoId == null) {
                val response = app.get(url, referer = referer ?: mainUrl).text
                videoId = uuidRegex.find(response)?.groupValues?.get(1)
            }

            if (videoId != null) {
                val m3u8Url = "https://nas2.d.tube/videos/$videoId/master.m3u8"
                M3u8Helper.generateM3u8(name, m3u8Url, url).forEach(callback)
            }
        } catch (e: Exception) {
            // Fails silently
        }
    }
}
