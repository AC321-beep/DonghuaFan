package com.Animexin

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
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
        "anime/?status=&type=movie&order=update" to "Movies",
        "anime/?status=completed&type=&order=update" to "Completed"
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

        val isChallenge = listOf("just a moment", "security verification", "attention required", "cloudflare")
            .any { doc.title().lowercase().contains(it) } || doc.select("div.cf-turnstile").isNotEmpty()

        if (isChallenge || response.code in listOf(403, 503)) {
            if (resolveCloudflare(url)) {
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
        val poster = img?.let {
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        } ?: this.selectFirst("noscript img")?.attr("src")

        val epText = this.selectFirst(".eggmeta .eggepisode, .bt .epx, .epx")?.text()?.trim()
        val epNum = epText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val type = this.selectFirst(".typez, .eggtype")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)

            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),
                "User-Agent" to CFState.userAgent
            ).filterValues { it.isNotBlank() }

            if (epNum != null) this.addSub(epNum)
            if (href.contains("movie", true) || type.equals("Movie", true)) this.type = TvType.Movie
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getSafeDocument("$mainUrl/?s=$query")
            .select("div.listupd article.bs, div.listupd div.bs, div.listupd div.bsx")
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
        val poster = img?.let {
            it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
        } ?: doc.selectFirst("noscript img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("div.entry-content, .infox .desc, .bigcontent .desc")?.text()?.trim()
        val isMovie = doc.selectFirst(".spe, .type")?.text()?.contains("Movie", ignoreCase = true) == true

        val commonHeaders = mapOf(
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Referer" to "$mainUrl/",
            "Cookie" to (CookieManager.getInstance().getCookie(mainUrl) ?: ""),
            "User-Agent" to CFState.userAgent
        ).filterValues { it.isNotBlank() }

        if (isMovie) {
            val href = doc.selectFirst("div.eplister > ul > li a, .eplister li a, .eps a")?.attr("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.posterHeaders = commonHeaders
            }
        }

        val episodes = doc.select("div.eplister li, ul.eplister li, .eplister li, .epslist li, .episodlist li")
            .mapNotNull { info ->
                val a = info.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

                val epImg = info.selectFirst("a img")
                val epPoster = epImg?.let {
                    it.attr("data-lazy-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("data-src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                        ?: it.attr("src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") }
                } ?: info.selectFirst("noscript img")?.attr("src")

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
            this.posterHeaders = commonHeaders
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

        // Detect language from any combination of label / attribute / URL.
        fun detectLang(vararg sources: String?): String? {
            val combined = sources.filterNotNull().joinToString(" ").lowercase()
            val hasIndo = combined.contains("indonesia") || combined.contains("indo") || combined.contains("bahasa")
            val hasEng = combined.contains("english") || Regex("""\beng\b""").containsMatchIn(combined)

            return when {
                hasEng && !hasIndo -> "eng"
                hasIndo && !hasEng -> "ind"
                else -> null
            }
        }

        // Short label appended to the picker name.
        fun langTag(lang: String?): String? = when (lang) {
            "eng" -> "[Eng]"
            "ind" -> "[Indo]"
            else -> null
        }

        suspend fun invokeExtractor(iframeUrl: String, lang: String? = null) {
            var finalUrl = iframeUrl.trim()
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) finalUrl = "https://animexin.dev$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            val dedupUrl = finalUrl.substringBefore("?")
            if (!extractedIframeUrls.add(dedupUrl)) return

            val tag = langTag(lang)

            // Tag lang on every link AND append [Eng]/[Indo] to the displayed name/source.
            // Emit immediately — no buffering, no delay.
            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                val localized = if (tag != null) {
                    link.copy(
                        name = if (!link.name.contains(tag)) "${link.name} $tag" else link.name,
                        source = if (!link.source.contains(tag)) "${link.source} $tag" else link.source
                    )
                } else link

                if (yieldedStreamUrls.add(localized.url)) callback(localized)
            }

            try {
                val isHandled = when {
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        OkRu().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
                        true
                    }
                    "d.tube" in finalUrl || "dtube" in finalUrl -> {
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
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

        // 1) Server dropdown / list entries
        val servers = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], " +
            ".server-list li a[data-em], .server option, .player option"
        )

        // Read the user's system/app locale (e.g. "en" for English, "id" or "in" for Indonesian)
        val userLang = Locale.getDefault().language

        // Sort servers dynamically based on user language preference before processing them
        val sortedServers = servers.sortedByDescending { server ->
            val lang = detectLang(
                server.text(),
                server.attr("data-lang"),
                server.attr("data-em"),
                server.attr("data-embed"),
                server.attr("value")
            )

            when {
                // User prefers Indonesian
                (userLang == "id" || userLang == "in") && lang == "ind" -> 2
                (userLang == "id" || userLang == "in") && lang == "eng" -> 1
                
                // User prefers English (or anything else defaults to English priority)
                lang == "eng" -> 2
                lang == "ind" -> 1
                
                else -> 0
            }
        }

        coroutineScope {
            sortedServers.map { server ->
                async {
                    val lang = detectLang(
                        server.text(),
                        server.attr("data-lang"),
                        server.attr("data-em"),
                        server.attr("data-embed"),
                        server.attr("value")
                    )

                    val rawData = server.attr("value")
                        .ifBlank { server.attr("data-em") }
                        .ifBlank { server.attr("data-embed") }
                        .trim()

                    if (rawData.isNotBlank()) {
                        val decoded = try {
                            String(Base64.decode(rawData, Base64.DEFAULT))
                        } catch (e: Exception) {
                            rawData
                        }

                        val iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                        } else if (decoded.startsWith("http") || decoded.startsWith("//")) {
                            decoded
                        } else null

                        if (!iframeSrc.isNullOrBlank()) {
                            invokeExtractor(iframeSrc, lang)
                        }
                    }
                }
            }.awaitAll()
        }

        // 2) Bare iframes in page
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                val lang = detectLang(src, iframe.attr("title"), iframe.attr("data-lang"))
                invokeExtractor(src, lang)
            }
        }

        return true
    }
}
