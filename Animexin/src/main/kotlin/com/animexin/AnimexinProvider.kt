package com.Animexin

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AnimexinLogger {
    private const val PREFS_NAME = "animexin_settings"
    private const val KEY_VERBOSE = "verbose_logging"

    @Volatile
    var isVerbose: Boolean = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isVerbose = prefs.getBoolean(KEY_VERBOSE, false)
    }

    fun setVerbose(context: Context, enabled: Boolean) {
        isVerbose = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VERBOSE, enabled)
            .apply()
    }

    inline fun d(subTag: String, msg: () -> String) {
        if (isVerbose) {
            Log.d("AnimeXin/$subTag", msg())
        }
    }

    fun e(subTag: String, msg: String, throwable: Throwable? = null) {
        if (isVerbose) {
            if (throwable != null) {
                Log.e("AnimeXin/$subTag", msg, throwable)
            } else {
                Log.e("AnimeXin/$subTag", msg)
            }
        }
    }
}

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

    private suspend fun getSafeDocument(url: String): Document {
        AnimexinLogger.d("HTTP") { "GET $url" }
        val responseResult = runCatching { app.get(url, interceptor = cfInterceptor) }

        val needsSolve = responseResult.fold(
            onSuccess = { resp ->
                val doc = resp.document
                val title = doc.title().lowercase()

                resp.code in listOf(403, 503) ||
                listOf("just a moment", "security verification", "attention required", "cloudflare", "verify you are human").any { title.contains(it) } ||
                doc.selectFirst("div.cf-turnstile, #cf-wrapper") != null
            },
            onFailure = { e ->
                AnimexinLogger.e("HTTP", "Request failed for $url:${e.message}", e)
                e.message?.contains("403") == true || e.message?.contains("503") == true
            }
        )

        if (needsSolve) {
            AnimexinLogger.d("CF") { "Challenge detected for $url, invoking CFSolver" }
            if (CFSolver(url).solve()) {
                return app.get(url, interceptor = cfInterceptor).document
            } else {
                throw Error("Cloudflare bypass was cancelled or failed.")
            }
        }

        return responseResult.getOrThrow().document
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

    private fun decodeBase64Safe(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http", ignoreCase = true) ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("<")
        ) {
            return trimmed
        }
        return try {
            val normalized = trimmed
                .replace("\\s+".toRegex(), "")
                .replace('-', '+')
                .replace('_', '/')
            val padded = when (normalized.length % 4) {
                2 -> "$normalized=="
                3 -> "$normalized="
                else -> normalized
            }
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            AnimexinLogger.e("Provider", "Base64 decode failed for input (${trimmed.take(24)}...)", e)
            trimmed
        }
    }

    private fun Element.extractIframeSrc(): String? {
        val candidates = listOf(
            this.attr("data-lazy-src"),
            this.attr("data-src"),
            this.attr("src")
        )
        return candidates.firstOrNull { src ->
            src.isNotBlank() &&
            !src.equals("about:blank", ignoreCase = true) &&
            !src.startsWith("data:", ignoreCase = true) &&
            !src.startsWith("javascript:", ignoreCase = true)
        }?.trim()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        AnimexinLogger.d("Provider") { "loadLinks started for: $data" }
        val document = getSafeDocument(data)
        val extractedIframeUrls = ConcurrentHashMap.newKeySet<String>()
        val yieldedStreamUrls = ConcurrentHashMap.newKeySet<String>()

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

        fun langTag(lang: String?): String? = when (lang) {
            "eng" -> "[Eng]"
            "ind" -> "[Indo]"
            else -> null
        }

        suspend fun invokeExtractor(iframeUrl: String, lang: String? = null) {
            var finalUrl = iframeUrl.trim()
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("/")) finalUrl = "$mainUrl$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"

            // Preserve video ID params (?v=, ?id=, ?vid=, ?video=) while stripping tracking params (?pub=, ?nochat=)
            val basePart = finalUrl.substringBefore("?")
            val idParam = Regex("""[?&](?:v|id|vid|video)=([^&]+)""").find(finalUrl)?.value.orEmpty()
            val dedupUrl = basePart + idParam
            if (!extractedIframeUrls.add(dedupUrl)) {
                AnimexinLogger.d("Provider") { "Skipping duplicate iframe: $dedupUrl" }
                return
            }

            val tag = langTag(lang)

            val trackingCallback: (ExtractorLink) -> Unit = { link ->
                val localized = if (tag != null) {
                    val newName = if (!link.name.contains(tag)) "${link.name}$tag" else link.name
                    val newSource = if (!link.source.contains(tag)) "${link.source}$tag" else link.source

                    ExtractorLink(
                        source = newSource,
                        name = newName,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
                } else {
                    link
                }

                if (yieldedStreamUrls.add(localized.url)) {
                    AnimexinLogger.d("Provider") { "Emitted link -> [${localized.name}]${localized.url}" }
                    callback(localized)
                }
            }

            try {
                val isHandled = when {
                    "rumble.com" in finalUrl || "rmbl.ws" in finalUrl -> {
                        AnimexinLogger.d("Provider") { "Routing to Rumble (lang=$lang):$finalUrl" }
                        Rumble().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback)
                        true
                    }
                    "odysee.com" in finalUrl -> {
                        AnimexinLogger.d("Provider") { "Routing to Odysee (lang=$lang):$finalUrl" }
                        Odysee().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback)
                        true
                    }
                    "mega.nz" in finalUrl || "mega.co.nz" in finalUrl -> {
                        AnimexinLogger.d("Provider") { "Routing to MegaNz (lang=$lang):$finalUrl" }
                        MegaNz().getUrl(finalUrl, mainUrl, subtitleCallback, trackingCallback)
                        true
                    }
                    "ok.ru" in finalUrl || "odnoklassniki.ru" in finalUrl -> {
                        AnimexinLogger.d("Provider") { "Routing to OkRu (lang=$lang):$finalUrl" }
                        OkRu().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
                        true
                    }
                    "d.tube" in finalUrl || "dtube" in finalUrl -> {
                        AnimexinLogger.d("Provider") { "Routing to Dtube (lang=$lang):$finalUrl" }
                        Dtube().getUrl(finalUrl, mainUrl)?.forEach { trackingCallback(it) }
                        true
                    }
                    else -> false
                }

                if (!isHandled) {
                    AnimexinLogger.d("Provider") { "Routing to loadExtractor (lang=$lang):$finalUrl" }
                    loadExtractor(finalUrl, referer = mainUrl, subtitleCallback, trackingCallback)
                }
            } catch (e: Exception) {
                AnimexinLogger.e("Provider", "Extractor failed for $finalUrl:${e.message}", e)
            }
        }

        // 1) Server dropdown / list entries
        val servers = document.select(
            ".mobius option, select.mirror option, .server-list li a[data-embed], " +
            ".server-list li a[data-em], .server option, .player option"
        )
        AnimexinLogger.d("Provider") { "Found ${servers.size} server options" }

        val userLang = Locale.getDefault().language

        val sortedServers = servers.sortedByDescending { server ->
            val lang = detectLang(
                server.text(),
                server.attr("data-lang"),
                server.attr("data-em"),
                server.attr("data-embed"),
                server.attr("value")
            )

            when {
                (userLang == "id" || userLang == "in") && lang == "ind" -> 2
                (userLang == "id" || userLang == "in") && lang == "eng" -> 1
                lang == "eng" -> 2
                lang == "ind" -> 1
                else -> 0
            }
        }

        coroutineScope {
            sortedServers.map { server ->
                async {
                    val label = server.text().trim()
                    val lang = detectLang(
                        label,
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
                        val decoded = decodeBase64Safe(rawData)

                        val iframeSrc = if (decoded.contains("<iframe", ignoreCase = true)) {
                            Jsoup.parse(decoded).selectFirst("iframe")?.extractIframeSrc()
                        } else if (decoded.startsWith("http", ignoreCase = true) || decoded.startsWith("//")) {
                            decoded
                        } else null

                        AnimexinLogger.d("Provider") { "Server '$label' -> lang=$lang, src=$iframeSrc" }

                        if (!iframeSrc.isNullOrBlank()) {
                            invokeExtractor(iframeSrc, lang)
                        }
                    }
                }
            }.awaitAll()
        }

        // 2) Bare iframes in page
        val bareIframes = document.select("iframe")
        AnimexinLogger.d("Provider") { "Found ${bareIframes.size} bare <iframe> tags" }
        bareIframes.forEach { iframe ->
            val src = iframe.extractIframeSrc()
            if (!src.isNullOrBlank() && !src.contains("youtube", true) && !src.contains("disqus", true)) {
                val lang = detectLang(src, iframe.attr("title"), iframe.attr("data-lang"))
                AnimexinLogger.d("Provider") { "Bare iframe -> lang=$lang, src=$src" }
                invokeExtractor(src, lang)
            }
        }

        AnimexinLogger.d("Provider") { "loadLinks finished: ${yieldedStreamUrls.size} unique stream(s) emitted" }
        return true
    }
}
