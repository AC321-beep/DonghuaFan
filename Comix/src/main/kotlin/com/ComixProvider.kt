package com.comix

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class ComixProvider : MainAPI() {

    override var mainUrl = "https://comix.to"
    override var name = "Comix"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.Anime, TvType.Others)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "hot"      to "Hot Updates",
        "latest"   to "Latest Releases",
        "trending" to "Trending Today",
        "follows"  to "Most Followed",
    )

    @Volatile private var cipher: ComixCipher? = null
    @Volatile private var captureInFlight = false
    @Volatile private var prewarmStarted = false

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cfInterceptor = WebViewResolver(Regex(".*comix\\.to.*"))

    private fun browserHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1"
        ) + extra

    private suspend fun fetchHtml(url: String): String {
        return app.get(url, interceptor = cfInterceptor, headers = browserHeaders()).text
    }

    private val cipherCacheFile: File?
        get() {
            val ctx = CommonActivity.activity ?: return null
            return File(ctx.cacheDir, "comix_cipher.json")
        }

    private fun loadCachedCipher(): ComixCipher? {
        val f = cipherCacheFile ?: return null
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            val mat = CipherMaterial.fromJson(JSONObject(f.readText())) ?: return null
            ComixCipher(mat)
        }.getOrNull()
    }

    private fun saveCachedCipher(mat: CipherMaterial) {
        runCatching { cipherCacheFile?.writeText(mat.toJson().toString()) }
    }

    private fun invalidateCipher() {
        cipher = null
        runCatching { cipherCacheFile?.delete() }
    }

    private fun cachedCipher(): ComixCipher? {
        cipher?.let { return it }
        return loadCachedCipher()?.also { cipher = it }
    }

    private suspend fun captureCipher(): ComixCipher? {
        cipher?.let { return it }
        loadCachedCipher()?.let { cipher = it; return it }

        if (captureInFlight) return null
        
        captureInFlight = true
        return try {
            val mat = captureCipherMaterial() ?: return null
            val c = ComixCipher(mat)
            cipher = c
            saveCachedCipher(mat)
            c
        } finally {
            captureInFlight = false
        }
    }

    private fun maybeStartPrewarm() {
        if (prewarmStarted || cipher != null || loadCachedCipher() != null) return
        prewarmStarted = true
        bgScope.launch {
            delay(2000)
            captureCipher()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureCipherMaterial(): CipherMaterial? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null

        suspendCancellableCoroutine { cont ->
            val web = WebView(activity)
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            var pollRunnable: Runnable? = null

            fun finish(result: CipherMaterial?) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                pollRunnable?.let { handler.removeCallbacks(it) }
                runCatching { web.stopLoading() }
                runCatching { web.loadUrl("about:blank") }
                runCatching { web.destroy() }
                if (cont.isActive) cont.resume(result)
            }

            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = browserHeaders()["User-Agent"]
            }

            web.addJavascriptInterface(object {
                @JavascriptInterface
                fun submit(json: String) {
                    val mat = runCatching {
                        CipherMaterial.fromJson(JSONObject(json))
                    }.getOrNull()
                    if (mat != null && mat.isValid()) {
                        finish(mat)
                    }
                }
            }, "ComixCipherBridge")

            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                }
            }

            cont.invokeOnCancellation { finish(null) }

            timeoutRunnable = Runnable {
                finish(null)
            }.also { handler.postDelayed(it, CIPHER_CAPTURE_TIMEOUT_MS) }

            pollRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    web.evaluateJavascript(CAPTURE_SCRIPT, null)
                    web.evaluateJavascript(
                        """
                        (function(){
                            if (window.__comixTriggered) return;
                            var el = document.querySelector('.npager a, a[href*="/browse"], a[href*="/title/"]');
                            if (el) {
                                window.__comixTriggered = true;
                                el.click();
                            } else {
                                window.scrollTo(0, document.body.scrollHeight);
                            }
                        })();
                        """.trimIndent(), null
                    )
                    handler.postDelayed(this, 800L)
                }
            }.also { handler.postDelayed(it, 800L) }

            web.loadUrl(mainUrl)
        }
    }

    private suspend fun getSigned(
        path: String,
        params: Map<String, List<String>>,
    ): String? {
        val c = cachedCipher() ?: return null

        return try {
            val canonical = params.toSortedMap().entries.joinToString("&") { (rawName, values) ->
                val name = rawName.removeSuffix("[]")
                if (values.size == 1 && !rawName.endsWith("[]")) {
                    "$name=${values.single().trim()}"
                } else {
                    values.mapIndexed { i, v -> "$name[$i]=${v.trim()}" }.joinToString("&")
                }
            }
            val token = c.sign(path, canonical)

            val encoded = buildString {
                append(mainUrl).append(path).append("?")
                var first = true
                params.toSortedMap().forEach { (rawName, values) ->
                    val name = rawName.removeSuffix("[]")
                    fun emit(k: String, v: String) {
                        if (!first) append("&")
                        first = false
                        append(URLEncoder.encode(k, "UTF-8"))
                            .append("=")
                            .append(URLEncoder.encode(v.trim(), "UTF-8"))
                    }
                    if (values.size == 1 && !rawName.endsWith("[]")) {
                        emit(name, values.single())
                    } else {
                        values.forEachIndexed { i, v -> emit("$name[$i]", v) }
                    }
                }
                if (!first) append("&")
                append("_=").append(URLEncoder.encode(token, "UTF-8"))
            }

            val raw = app.get(
                encoded,
                interceptor = cfInterceptor,
                headers = browserHeaders(
                    mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                    )
                ),
            ).text

            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null

            if (root.has("e")) {
                val decrypted = runCatching { c.decrypt(root.optString("e")) }.getOrNull()
                if (decrypted == null) {
                    if (cipher === c) {
                        invalidateCipher()
                        prewarmStarted = false
                    }
                    return null
                }
                return decrypted
            }
            return raw
        } catch (t: Throwable) {
            if (cipher === c) {
                invalidateCipher()
                prewarmStarted = false
            }
            null
        }
    }

    private fun extractInitialDataJson(htmlOrDoc: Any): JSONObject? {
        val doc: Document = when (htmlOrDoc) {
            is Document -> htmlOrDoc
            is String   -> Jsoup.parse(htmlOrDoc)
            else        -> return null
        }
        val script = doc.selectFirst("script#initial-data") ?: return null
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun parseMangaFromJson(obj: JSONObject): SearchResponse? {
        val title = (obj.optString("title").takeIf { it.isNotBlank() }
            ?: obj.optString("name").takeIf { it.isNotBlank() }
            ?: obj.optString("manga_title").takeIf { it.isNotBlank() }
            ?: return null)

        val relUrl = (obj.optString("url").takeIf  { it.isNotBlank() }
            ?: obj.optString("href").takeIf  { it.isNotBlank() }
            ?: obj.optString("slug").takeIf  { it.isNotBlank() }?.let { "/title/$it" }
            ?: obj.optString("hid").takeIf   { it.isNotBlank() }?.let { "/title/$it" }
            ?: obj.optString("link").takeIf  { it.isNotBlank() }
            ?: return null)

        val poster = (obj.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("poster")?.optString("medium")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("cover")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("cover")?.optString("medium")?.takeIf { it.isNotBlank() }
            ?: obj.optString("cover").takeIf { it.isNotBlank() }
            ?: obj.optString("thumbnail").takeIf { it.isNotBlank() }
            ?: obj.optString("image").takeIf { it.isNotBlank() })

        val latest = (obj.optInt("latestChapter", 0).takeIf { it > 0 }
            ?: obj.optInt("latest_chapter", 0).takeIf { it > 0 }
            ?: obj.optInt("chapters_count", 0).takeIf { it > 0 }
            ?: obj.optJSONObject("latest_chapter")?.optInt("number", 0)?.takeIf { it > 0 })

        return newAnimeSearchResponse(title, fixUrl(relUrl), TvType.Anime) {
            poster?.let { this.posterUrl = fixUrl(it) }
            latest?.let { addSub(it) }
        }
    }

    private fun readQueries(
        initial: JSONObject,
        matcher: (JSONArray) -> Boolean
    ): List<SearchResponse> {
        val queries = initial.optJSONObject("queries") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        val keys = queries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
            if (!matcher(parsed)) continue

            val value = queries.opt(k)
            val arr = when (value) {
                is JSONArray  -> value
                is JSONObject -> value.optJSONArray("items")
                else          -> null
            } ?: continue

            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    private fun toSearchResult(card: Element): SearchResponse? {
        val anchor = if (card.tagName() == "a") card
                     else card.selectFirst("a[href*='/title/'], a[href]") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = card.selectFirst("h3, h2, .title, .manga-title, .lrow__title")
            ?.text()?.trim()
            ?: anchor.attr("title").ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null
        val poster = card.selectFirst("img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
        val latestEp = card.selectFirst(".chapter, .latest-chapter, .lrow__chapter")
            ?.text()?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            poster?.let { this.posterUrl = fixUrl(it) }
            if (latestEp != null && latestEp > 0) addSub(latestEp)
        }
    }

    private fun extractSearchResultsDom(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        doc.select(".list-grid .lrow, div.lrow, .lrow, .list-grid > div, .list-grid--cards > div")
            .forEach { el -> toSearchResult(el)?.let { results.add(it) } }
        if (results.isEmpty()) {
            doc.select("article, .manga-card, .comic-item, a[href*='/title/']").forEach { el ->
                if (el.tagName() == "a" &&
                    el.parents().any { p -> p.hasClass("lrow") || p.hasClass("list-grid") }
                ) return@forEach
                toSearchResult(el)?.let { results.add(it) }
            }
        }
        return results.distinctBy { it.url }
    }

    private data class PageResult(
        val items: List<SearchResponse>,
        val hasNext: Boolean
    )

    private fun readHasNext(root: JSONObject?, page: Int, itemCount: Int): Boolean {
        val resultObj = root?.optJSONObject("result")
        val meta = resultObj?.optJSONObject("meta")
            ?: resultObj?.optJSONObject("pagination")
            ?: root?.optJSONObject("meta")

        if (meta != null) {
            val lastPage = meta.optInt("lastPage", meta.optInt("last_page", -1))
            if (lastPage > 0) return page < lastPage
            if (meta.has("hasNext"))       return meta.optBoolean("hasNext")
            if (meta.has("has_next_page")) return meta.optBoolean("has_next_page")
        }

        resultObj?.optJSONObject("links")?.let { l ->
            val next = l.optString("next")
            if (next.isNotBlank() && next != "null") return true
        }

        if (root != null) {
            if (root.has("has_next_page")) return root.optBoolean("has_next_page")
            if (root.has("hasNext"))       return root.optBoolean("hasNext")
        }

        return itemCount >= 28
    }

    private fun arrToResults(arr: JSONArray): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
        }
        return out
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        if (request.data == "hot" || request.data == "latest") {
            maybeStartPrewarm()
        }

        if (request.data == "trending" || request.data == "follows") {
            if (page > 1) return null
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            if (items.isEmpty()) return null
            return newHomePageResponse(
                request, items.distinctBy { it.url }, hasNext = false
            )
        }

        val apiResult = fetchQueryPage(request, page)
        if (apiResult != null && apiResult.items.isNotEmpty()) {
            return newHomePageResponse(
                request,
                apiResult.items.distinctBy { it.url },
                hasNext = apiResult.hasNext
            )
        }

        if (page == 1) {
            val items = parseMainPage(fetchHtml("$mainUrl/"), request, 1)
            if (items.isNotEmpty()) {
                return newHomePageResponse(
                    request,
                    items.distinctBy { it.url },
                    hasNext = true 
                )
            }
        }

        return null
    }

    private fun parseMainPage(
        html: String,
        request: MainPageRequest,
        page: Int
    ): List<SearchResponse> {
        if (html.isBlank()) return emptyList()
        var items: List<SearchResponse> = emptyList()

        extractInitialDataJson(html)?.let { initial ->
            val precise: ((String, JSONObject) -> Boolean)? = when (request.data) {
                "trending" -> { st, p -> st == "top"  && p.optString("type") == "trending" }
                "follows"  -> { st, p -> st == "top"  && p.optString("type") == "follows" }
                "hot"      -> { st, p -> st == "list" && p.optString("scope") == "hot" }
                "latest"   -> { st, p ->
                    st == "list" && p.optJSONObject("order")?.optString("created_at") == "desc"
                }
                else -> null
            }
            if (precise != null) {
                items = readQueries(initial) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val params = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = params.optInt("page", 1)
                    if (page > 1 && jsonPage != page) return@readQueries false
                    precise(subtype, params)
                }
            }
            if (items.isEmpty() && request.data == "latest") {
                items = readQueries(initial) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val params = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = params.optInt("page", 1)
                    if (page > 1 && jsonPage != page) return@readQueries false
                    (subtype == "list" || subtype == "browse") &&
                        params.optString("scope") != "hot" &&
                        params.optJSONObject("order")?.optString("created_at") == "desc"
                }
            }
        }
        if (items.isEmpty()) items = extractSearchResultsDom(Jsoup.parse(html))
        return items
    }

    private suspend fun fetchQueryPage(
        request: MainPageRequest,
        page: Int
    ): PageResult? {
        val params: Map<String, List<String>>? = when (request.data) {
            "hot" -> mapOf(
                "scope"                     to listOf("hot"),
                "page"                      to listOf(page.toString()),
                "order[chapter_updated_at]" to listOf("desc"),
                "limit"                     to listOf("28"),
            )
            "latest" -> mapOf(
                "page"              to listOf(page.toString()),
                "order[created_at]" to listOf("desc"),
                "limit"             to listOf("28"),
            )
            else -> null
        }

        if (params != null && cachedCipher() != null) {
            val body = getSigned("/api/v1/manga", params)
            if (!body.isNullOrBlank()) {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr: JSONArray? = root?.optJSONObject("result")?.optJSONArray("items")
                    ?: root?.optJSONArray("items")
                    ?: root?.optJSONObject("data")?.optJSONArray("items")
                if (arr != null) {
                    val items = arrToResults(arr)
                    if (items.isNotEmpty()) {
                        val hasNext = readHasNext(root, page, items.size)
                        return PageResult(items, hasNext)
                    }
                }
            }
        }

        val homeSsrHtml = runCatching { fetchHtml("$mainUrl/?page=$page") }.getOrNull()
        if (!homeSsrHtml.isNullOrBlank()) {
            val initial = extractInitialDataJson(homeSsrHtml)
            if (initial != null) {
                val items = readQueries(initial) { k ->
                    if (k.length() < 3 || k.optString(0) != "manga") return@readQueries false
                    val subtype = k.optString(1)
                    val p = k.optJSONObject(2) ?: return@readQueries false
                    val jsonPage = p.optInt("page", -1)
                    if (jsonPage != page) return@readQueries false
                    when (request.data) {
                        "hot"    -> subtype == "list" && p.optString("scope") == "hot"
                        "latest" -> subtype == "list" &&
                            p.optJSONObject("order")?.optString("created_at") == "desc"
                        else -> false
                    }
                }
                if (items.isNotEmpty()) return PageResult(items, hasNext = true)
            }
        }

        val browseUrl = when (request.data) {
            "hot"    -> "$mainUrl/browse?page=$page&scope=hot&order[chapter_updated_at]=desc"
            "latest" -> "$mainUrl/browse?page=$page&order[created_at]=desc"
            else     -> return null
        }
        val browseHtml = runCatching { fetchHtml(browseUrl) }.getOrNull()
        if (!browseHtml.isNullOrBlank()) {
            val items = parseMainPage(browseHtml, request, page)
            if (items.isNotEmpty()) return PageResult(items, hasNext = items.size >= 28)
        }

        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/browse?q=$encodedQuery"

        val html = fetchHtml(searchUrl)
        if (html.isBlank()) return emptyList()

        var results: List<SearchResponse> = emptyList()
        extractInitialDataJson(html)?.let { initial ->
            results = readQueries(initial) { k -> k.length() >= 1 && k.optString(0) == "manga" }
        }
        if (results.isEmpty()) results = extractSearchResultsDom(Jsoup.parse(html))

        val lower = cleanQuery.lowercase()
        val filtered = results.filter { it.name.lowercase().contains(lower) }
        return (filtered.ifEmpty { results }).distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun formatChapterNum(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isBlank()) return null

        val document = Jsoup.parse(html)
        val initialData = extractInitialDataJson(document)

        val parsedChapterLinks = mutableMapOf<String, Pair<String, String>>()

        fun extractChaptersFromHtml(doc: Document) {
            doc.select("a.mchap-row__primary, a[href*='-chapter-']").forEach { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val m = Regex("""-chapter-([\d.]+)""", RegexOption.IGNORE_CASE).find(href) ?: return@forEach
                val numStr = m.groupValues[1].toDoubleOrNull()?.let { formatChapterNum(it) } ?: return@forEach
                if (a.hasClass("mchap-row__primary") || a.parents().any { it.hasClass("mchap-item") }) {
                    if (!parsedChapterLinks.containsKey(numStr)) {
                        val visible = a.text().trim()
                        parsedChapterLinks[numStr] = visible.ifBlank { "Ch. $numStr" } to href
                    }
                }
            }
        }

        extractChaptersFromHtml(document)

        var maxPage = 1
        document.select(".npager__num").forEach { el ->
            val p = el.text().toIntOrNull() ?: 1
            if (p > maxPage) maxPage = p
        }

        if (maxPage > 1) {
            val pages = (2..maxPage).toList()
            for (chunk in pages.chunked(5)) {
                coroutineScope {
                    chunk.map { pageNum ->
                        async {
                            val pUrl = if (url.contains("?")) "$url&page=$pageNum" else "$url?page=$pageNum"
                            val response = runCatching { fetchHtml(pUrl) }.getOrNull()
                            if (!response.isNullOrBlank()) extractChaptersFromHtml(Jsoup.parse(response))
                        }
                    }.awaitAll()
                }
            }
        }

        var detail: JSONObject? = null
        initialData?.optJSONObject("queries")?.let { queries ->
            val keys = queries.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
                if (parsed.length() >= 2 && parsed.optString(0) == "manga" && parsed.optString(1) == "detail") {
                    detail = queries.optJSONObject(k)
                    if (detail != null) break
                }
            }
        }
        val d = detail

        val fallbackTitle: String = run {
            val slug = url.substringAfter("/title/", "").substringAfter("-", "")
            val guess = slug.replace('-', ' ').trim()
            if (guess.isBlank()) "Untitled" else
                guess.split(' ').joinToString(" ") { w ->
                    if (w.isEmpty()) w else w[0].uppercase() + w.drop(1)
                }
        }

        val mangaTitle = d?.optString("title")?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val posterUrl = d?.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: d?.optJSONObject("poster")?.optString("medium")
        val plot = d?.optString("synopsis")?.takeIf { it.isNotBlank() }
        val statusStr = d?.optString("status")?.takeIf { it.isNotBlank() }
        val yearInt = d?.optInt("year", 0)?.takeIf { it > 0 }

        val genres = buildList {
            if (d != null) {
                listOf("genres", "tags", "demographics", "formats").forEach { f ->
                    d.optJSONArray(f)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optString("title")
                                ?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
            }
        }.distinct()

        val latestChapterNum = d?.optInt("latestChapter", 0) ?: 0
        val firstChapterUrl  = d?.optString("firstChapterUrl")?.takeIf { it.isNotBlank() }
        val startsAtZero = firstChapterUrl?.contains("-chapter-0", ignoreCase = true) == true
        val startCh = if (startsAtZero) 0 else 1

        val allChapterKeys = mutableSetOf<String>()
        if (latestChapterNum > 0) for (i in startCh..latestChapterNum) allChapterKeys.add(i.toString())
        parsedChapterLinks.keys.forEach { allChapterKeys.add(it) }
        val sortedKeys = allChapterKeys.toList().sortedBy { it.toDoubleOrNull() ?: 0.0 }

        val episodes = sortedKeys.mapIndexed { _, key ->
            val realData = parsedChapterLinks[key]
            val epUrl = if (realData != null) {
                realData.second
            } else if (key == "0" && startsAtZero && firstChapterUrl != null) {
                firstChapterUrl
            } else if (key == "1" && !startsAtZero && firstChapterUrl != null) {
                firstChapterUrl
            } else {
                val replaced = firstChapterUrl?.replace(Regex("-chapter-[\\d.]+", RegexOption.IGNORE_CASE), "-chapter-$key")
                if (replaced != null && replaced != firstChapterUrl) {
                    replaced
                } else {
                    "${url.removeSuffix("/")}-chapter-$key"
                }
            }
            
            val epName = realData?.first ?: "Ch. $key"
            val chNum = key.toFloatOrNull()?.toInt()

            newEpisode(fixUrl(epUrl)) {
                this.name = epName
                if (chNum != null) {
                    this.episode = chNum
                }
                this.posterUrl = posterUrl
            }
        }

        return newAnimeLoadResponse(mangaTitle, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.plot      = plot
            this.tags      = genres
            this.year      = yearInt
            this.showStatus = when (statusStr?.lowercase()) {
                "completed", "finished"             -> ShowStatus.Completed
                "releasing", "ongoing", "on_hiatus" -> ShowStatus.Ongoing
                else -> null
            }
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterName = Regex("-chapter-([\\d.]+)").find(data)
            ?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"

        activity.runOnUiThread {
            ComixReaderDialogFragment.show(
                activity = activity,
                title = name,
                chapterName = chapterName,
                chapterUrl = data,
                targetChapter = 0 
            )
        }
        return true
    }

    private companion object {
        const val CIPHER_CAPTURE_TIMEOUT_MS = 30_000L

        val CAPTURE_SCRIPT = """
            (function () {
                if (window.__comixCipherHook) return;
                window.__comixCipherHook = true;
                
                var captures = window.__comixCipherCaptures = [];
                var seen = window.__comixSeenLengths = {};
                var originalAtob = window.atob;

                var stealthAtob = function (value) {
                    var decoded = originalAtob.call(window, value);
                    try {
                        var len = decoded.length;
                        var key = 'L' + len;
                        if (!seen[key]) { seen[key] = true; }
                        
                        if (len === 256 || len === 24 || len === 32) {
                            var bytes = new Array(len);
                            for (var i = 0; i < len; i++) bytes[i] = decoded.charCodeAt(i) & 255;
                            captures.push(bytes);
                            
                            var sboxes = captures.filter(function (x) { return x.length === 256; }).slice(0, 3);
                            var keys   = captures.filter(function (x) { return x.length === 24 || x.length === 32; }).slice(0, 3);
                            if (sboxes.length === 3 && keys.length === 3) {
                                var payload = JSON.stringify({ sboxes: sboxes, keys: keys });
                                try { ComixCipherBridge.submit(payload); } catch (e) {}
                            }
                        }
                    } catch (e) {}
                    return decoded;
                };

                var origFpToString = Function.prototype.toString;
                Function.prototype.toString = function () {
                    if (this === stealthAtob) return 'function atob() { [native code] }';
                    return origFpToString.call(this);
                };

                Object.defineProperty(window, 'atob', {
                    value: stealthAtob,
                    writable: true,
                    configurable: true
                });
            })();
        """.trimIndent()
    }
}
