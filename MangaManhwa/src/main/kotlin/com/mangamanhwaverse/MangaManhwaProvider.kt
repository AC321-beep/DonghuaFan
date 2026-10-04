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

    // FIX A: escape the host. `.` in "comix.to" is a regex wildcard and was
    // matching unrelated domains (comixXto, comix-to.evil.example).
    protected open val cfPattern: Regex
        get() = Regex(".*${Regex.escape(hostOf(baseUrl))}.*")

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

    override suspend fun load(url: String): LoadResponse? = runCatching {
        // FIX (P2): if chapters() throws, still return the title page with
        // zero episodes instead of failing the whole load.
        val episodes = runCatching { chapters(url) }.getOrDefault(emptyList())

        newAnimeLoadResponse(
            // FIX (P2): format the slug-derived fallback into Title Case.
            titleFromSlug(url),
            url,
            TvType.Anime
        ) {
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }.getOrNull()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? androidx.appcompat.app.AppCompatActivity
            ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        // FIX C: keep the nullable Double so 0.0 ("Prologue") is distinguishable
        // from a regex miss (real "Chapter" fallback).
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

        // FIX B: only report success after the dialog actually mounted.
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
        val absolute = if (url.startsWith("http")) url else abs(url)

        return runCatching {
            var doc = app.get(
                absolute,
                interceptor = unifiedInterceptor,
                referer = referer,
                headers = browserHeaders()
            ).document

            if (isCfChallenge(doc)) {
                log("CF challenge persists after silent solve — invoking dialog")
                val solved = CFSolver(absolute).solve()
                if (solved) {
                    doc = app.get(
                        absolute,
                        interceptor = unifiedInterceptor,
                        referer = referer,
                        headers = browserHeaders()
                    ).document
                }
            }
            doc
        }.onFailure {
            val msg = it.message ?: ""
            if (msg.contains("SSL", true) || msg.contains("TLS", true)) {
                log("fetch skipped (TLS): $absolute")
            } else {
                logErr("fetch failed $absolute: $msg", it)
            }
        }.getOrNull()
    }

    protected fun abs(url: String) =
        if (url.startsWith("http")) url
        else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    protected fun browserHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
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

    private fun hostOf(url: String) =
        runCatching { URI(url).host }.getOrNull() ?: url

    /** "solo-leveling" / "solo-leveling-chapter-3" → "Solo Leveling Chapter 3". */
    private fun titleFromSlug(url: String): String {
        val raw = url.substringAfterLast("/").replace('-', ' ').trim()
        if (raw.isBlank()) return "Untitled"
        return raw.split(' ').joinToString(" ") { w ->
            if (w.isEmpty()) w else w[0].uppercase() + w.drop(1)
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                       "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
