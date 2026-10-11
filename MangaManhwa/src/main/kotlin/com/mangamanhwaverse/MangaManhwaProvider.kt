package com.mangamanhwaverse

import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Interceptor
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.URI

abstract class MangaManhwaProvider : MainAPI() {

    final override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    abstract val baseUrl: String

    override val mainPage = mainPageOf(
        "popular"   to "Popular",
        "latest"    to "Latest",
        "trending"  to "Trending",
        "hot"       to "Hot Updates",
        "follows"   to "Most Followed",
        "completed" to "Completed",
        "new"       to "New Releases"
    )

    abstract suspend fun popular(page: Int): List<SearchResponse>

    open suspend fun latest(page: Int): List<SearchResponse>      = emptyList()
    open suspend fun trending(page: Int): List<SearchResponse>    = emptyList()
    open suspend fun hot(page: Int): List<SearchResponse>         = emptyList()
    open suspend fun follows(page: Int): List<SearchResponse>     = emptyList()
    open suspend fun completed(page: Int): List<SearchResponse>   = emptyList()
    open suspend fun newReleases(page: Int): List<SearchResponse> = emptyList()

    abstract suspend fun searchPage(query: String, page: Int): List<SearchResponse>
    abstract suspend fun chapters(mangaUrl: String): List<Episode>
    abstract suspend fun pages(chapterUrl: String): List<String>

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    protected open val cfPattern: Regex
        get() {
            val host = hostOf(baseUrl)
            if (host.isBlank()) return Regex("(?!)") // never matches
            return Regex(".*${Regex.escape(host)}.*")
        }

    private val cfResolver by lazy { WebViewResolver(cfPattern) }

    private val unifiedInterceptor: Interceptor by lazy {
        Interceptor { chain ->
            val original = chain.request()
            val modified = original.newBuilder().apply {
                removeHeader("X-Requested-With")

                CFState.userAgentFor(original.url.host)?.let {
                    header("User-Agent", it)
                }

                val cookies = CookieManager
                    .getInstance()
                    .getCookie(original.url.toString())
                if (!cookies.isNullOrEmpty()) {
                    header("Cookie", cookies)
                }
            }.build()

            cfResolver.intercept(object : Interceptor.Chain by chain {
                override fun request(): Request = modified
            })
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = runCatching {
            when (request.data) {
                "popular"   -> popular(page)
                "latest"    -> latest(page)
                "trending"  -> trending(page)
                "hot"       -> hot(page)
                "follows"   -> follows(page)
                "completed" -> completed(page)
                "new"       -> newReleases(page)
                else        -> emptyList()
            }
        }.getOrDefault(emptyList())

        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? =
        runCatching { searchPage(query, 1) }.getOrNull()

    override suspend fun load(url: String): LoadResponse? {
        val episodes = try {
            chapters(url)
        } catch (t: Throwable) {
            logErr("chapters() failed for $url: ${t.message}", t)
            emptyList()
        }

        return runCatching {
            newAnimeLoadResponse(titleFromSlug(url), url, TvType.Anime) {
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
        }.onFailure { logErr("load() construction failed for $url", it) }
         .getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? androidx.appcompat.app.AppCompatActivity
            ?: run {
                logErr("loadLinks: no AppCompatActivity host")
                return false
            }
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterNumRaw = Regex("(?:chapter|ch)[-\\s]?([\\d.]+)", RegexOption.IGNORE_CASE)
            .find(data)
            ?.groupValues?.get(1)
            ?.toDoubleOrNull()
        val chapterNum = chapterNumRaw?.toInt() ?: 0

        val chapterName = when {
            chapterNumRaw == null -> "Chapter"
            chapterNumRaw == 0.0  -> "Prologue"
            chapterNumRaw % 1.0 == 0.0 -> "Ch. ${chapterNumRaw.toInt()}"
            else -> "Ch. $chapterNumRaw"
        }

        val mounted = CompletableDeferred<Boolean>()
        activity.runOnUiThread {
            runCatching {
                ReaderDialog.show(
                    activity = activity,
                    title = name,
                    chapterName = chapterName,
                    chapterUrl = data,
                    referer = baseUrl,
                    targetChapter = chapterNum
                )
            }.onSuccess { mounted.complete(true) }
             .onFailure { mounted.complete(false) }
        }
        return mounted.await()
    }

    protected suspend fun fetch(url: String, referer: String? = baseUrl): Document? {
        val absolute = abs(url)

        return try {
            var doc = app.get(
                absolute,
                interceptor = unifiedInterceptor,
                referer = referer,
                headers = browserHeaders()
            ).document

            if (isCfChallenge(doc)) {
                log("CF challenge on $absolute — invoking solver dialog")
                val solved = CFSolver(absolute).solve()
                if (!solved) {
                    logErr("CF solver returned false for $absolute")
                    return null
                }
                doc = app.get(
                    absolute,
                    interceptor = unifiedInterceptor,
                    referer = referer,
                    headers = browserHeaders()
                ).document
                if (isCfChallenge(doc)) {
                    logErr("CF challenge persists after solve: $absolute")
                    return null
                }
            }
            doc
        } catch (t: Throwable) {
            val msg = t.message ?: ""
            if (msg.contains("SSL", true) || msg.contains("TLS", true)) {
                log("fetch skipped (TLS): $absolute")
            } else {
                logErr("fetch failed $absolute: $msg", t)
            }
            null
        }
    }

    /**
     * Resolve a possibly-relative URL against [baseUrl].
     * Handles: absolute http(s), protocol-relative "//host/path",
     * root-relative "/path", and bare "path".
     */
    protected fun abs(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/")  -> "$baseUrl$url"
            else                 -> "$baseUrl/$url"
        }
    }

    protected fun browserHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"122\", \"Chromium\";v=\"122\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1"
        ) + extra

    protected fun isCfChallenge(doc: Document): Boolean {
        val title = doc.title()
        return title.contains("Just a moment", true) ||
               title.contains("Attention Required", true) ||
               title.contains("Checking your browser", true) ||
               doc.selectFirst("div#cf-challenge-running") != null ||
               doc.selectFirst("form#challenge-form") != null ||
               doc.html().contains("cf-chl-")
    }

    protected fun log(msg: String) {
        if (Settings.verboseLog()) android.util.Log.d("MangaManhwa/$name", msg)
    }
    protected fun logErr(msg: String, t: Throwable? = null) {
        android.util.Log.e("MangaManhwa/$name", msg, t)
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull().orEmpty()

     protected fun titleFromSlug(url: String): String {
        val cleaned = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val raw = cleaned.substringAfterLast("/")
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
        if (raw.isBlank()) return "Untitled"
        return raw.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w[0].uppercase() + w.drop(1) }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                       "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
