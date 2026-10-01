package com.mangamanhwaverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URLEncoder

class ComixProvider : MangaManhwaProvider() {

    override var name = "Comix"
    override var mainUrl = "https://comix.to"
    override val baseUrl = "https://comix.to"
    override var lang = "en"

    private val cfInterceptor = WebViewResolver(Regex(".*comix\\.to.*"))

    @Volatile private var cipher: ComixCipher? = null

    private val cipherCacheFile: File?
        get() = AppContext.ctx?.let { File(it.cacheDir, "comix_cipher.json") }

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

    private fun cachedCipher(): ComixCipher? {
        cipher?.let { return it }
        return loadCachedCipher()?.also { cipher = it }
    }

    // ═════════════════════════════════════════════════════════════════
    //  Cipher capture
    // ═════════════════════════════════════════════════════════════════
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureCipherMaterial(): CipherMaterial? {
        val activity = CommonActivity.activity as? androidx.appcompat.app.AppCompatActivity
            ?: return null

        val deferred = CompletableDeferred<CipherMaterial?>()

        withContext(Dispatchers.Main) {
            val web = android.webkit.WebView(activity)
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null
            var pollRunnable: Runnable? = null

            fun finish(result: CipherMaterial?) {
                if (!done.compareAndSet(false, true)) return
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                pollRunnable?.let { handler.removeCallbacks(it) }
                runCatching { web.stopLoading() }
                runCatching { web.loadUrl("about:blank") }
                runCatching { web.destroy() }
                deferred.complete(result)
            }

            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = MangaManhwaProvider.UA
            }

            web.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun submit(json: String) {
                    val mat = runCatching { CipherMaterial.fromJson(JSONObject(json)) }.getOrNull()
                    if (mat != null && mat.isValid()) finish(mat)
                }
            }, "ComixCipherBridge")

            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(
                    view: android.webkit.WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    view?.evaluateJavascript(CAPTURE_SCRIPT, null)
                }
            }

            timeoutRunnable = Runnable { finish(null) }
                .also { handler.postDelayed(it, 30_000L) }

            pollRunnable = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    web.evaluateJavascript(CAPTURE_SCRIPT, null)
                    web.evaluateJavascript(
                        """
                        (function(){
                          if (window.__comixTriggered) return;
                          var el = document.querySelector('.npager a, a[href*="/browse"], a[href*="/title/"]');
                          if (el) { window.__comixTriggered = true; el.click(); }
                          else { window.scrollTo(0, document.body.scrollHeight); }
                        })();
                        """.trimIndent(), null
                    )
                    handler.postDelayed(this, 800L)
                }
            }.also { handler.postDelayed(it, 800L) }

            web.loadUrl(mainUrl)
        }

        return deferred.await()
    }

    private suspend fun ensureCipher(): ComixCipher? {
        cachedCipher()?.let { return it }
        val mat = captureCipherMaterial() ?: return null
        saveCachedCipher(mat)
        return ComixCipher(mat).also { cipher = it }
    }

    // ═════════════════════════════════════════════════════════════════
    //  HTTP helpers
    // ═════════════════════════════════════════════════════════════════
    private suspend fun fetchHtml(url: String): String = runCatching {
        RateLimiter.acquire(RateLimiter.hostOf(url), 3)
        app.get(url, interceptor = cfInterceptor, headers = browserHeaders()).text
    }.getOrDefault("")

    private suspend fun getSigned(path: String, params: Map<String, List<String>>): String? {
        val c = cachedCipher() ?: return null
        return try {
            val canonical = params.toSortedMap().entries.joinToString("&") { (rawName, values) ->
                val key = rawName.removeSuffix("[]")
                if (values.size == 1 && !rawName.endsWith("[]")) {
                    "$key=${values.single().trim()}"
                } else {
                    values.mapIndexed { i, v -> "$key[$i]=${v.trim()}" }.joinToString("&")
                }
            }
            val token = c.sign(path, canonical)

            val encoded = buildString {
                append(mainUrl).append(path).append("?")
                var first = true
                params.toSortedMap().forEach { (rawName, values) ->
                    val key = rawName.removeSuffix("[]")
                    fun emit(k: String, v: String) {
                        if (!first) append("&"); first = false
                        append(URLEncoder.encode(k, "UTF-8"))
                            .append("=")
                            .append(URLEncoder.encode(v.trim(), "UTF-8"))
                    }
                    if (values.size == 1 && !rawName.endsWith("[]")) emit(key, values.single())
                    else values.forEachIndexed { i, v -> emit("$key[$i]", v) }
                }
                if (!first) append("&")
                append("_=").append(URLEncoder.encode(token, "UTF-8"))
            }

            val raw = app.get(
                encoded,
                interceptor = cfInterceptor,
                headers = browserHeaders(mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/",
                ))
            ).text

            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.has("e")) {
                val dec = runCatching { c.decrypt(root.optString("e")) }.getOrNull()
                if (dec == null) { cipher = null; return null }
                return dec
            }
            raw
        } catch (_: Throwable) {
            cipher = null
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  JSON parsing
    // ═════════════════════════════════════════════════════════════════
    private fun extractInitial(doc: Document): JSONObject? {
        val script = doc.selectFirst("script#initial-data") ?: return null
        val text = script.data().ifBlank { script.html() }.trim()
        return runCatching { JSONObject(text) }.getOrNull()
    }

    // ✅ FIX 1: newAnimeSearchResponse + TvType.Anime
    private fun parseCard(obj: JSONObject): SearchResponse? {
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val rel = obj.optString("url").takeIf { it.isNotBlank() }
            ?: obj.optString("hid").takeIf { it.isNotBlank() }?.let { "/title/$it" }
            ?: return null
        val poster = obj.optJSONObject("poster")?.optString("large")?.takeIf { it.isNotBlank() }
            ?: obj.optJSONObject("poster")?.optString("medium")
        val res = newAnimeSearchResponse(title, fixUrl(rel), TvType.Anime)
        poster?.let { res.posterUrl = fixUrl(it) }
        return res
    }

    private fun readQueries(
        initial: JSONObject,
        matcher: (JSONArray) -> Boolean
    ): List<SearchResponse> {
        val queries = initial.optJSONObject("queries") ?: return emptyList()
        val keys = queries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val parsed = runCatching { JSONArray(k) }.getOrNull() ?: continue
            if (!matcher(parsed)) continue
            val arr = when (val v = queries.opt(k)) {
                is JSONArray -> v
                is JSONObject -> v.optJSONArray("items")
                else -> null
            } ?: continue
            val out = (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                parseCard(o)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    // ═════════════════════════════════════════════════════════════════
    //  MangaManhwaProvider implementation
    // ═════════════════════════════════════════════════════════════════
    override suspend fun popular(page: Int): List<SearchResponse> {
        ensureCipher()

        val params = mapOf(
            "scope" to listOf("hot"),
            "page" to listOf(page.toString()),
            "order[chapter_updated_at]" to listOf("desc"),
            "limit" to listOf("28"),
        )

        cachedCipher()?.let {
            getSigned("/api/v1/manga", params)?.let { body ->
                val root = runCatching { JSONObject(body) }.getOrNull()
                val arr = root?.optJSONObject("result")?.optJSONArray("items")
                    ?: root?.optJSONArray("items")
                if (arr != null) {
                    val items = (0 until arr.length()).mapNotNull {
                        val o = arr.optJSONObject(it) ?: return@mapNotNull null
                        parseCard(o)
                    }
                    if (items.isNotEmpty()) return items
                }
            }
        }

        val html = fetchHtml("$mainUrl/")
        val doc = Jsoup.parse(html)
        extractInitial(doc)?.let { initial ->
            val items = readQueries(initial) { k ->
                k.length() >= 3 && k.optString(0) == "manga"
            }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    override suspend fun latest(page: Int): List<SearchResponse> = popular(page)

    // ✅ FIX 2: newAnimeSearchResponse + TvType.Anime in search fallback
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val url = "$mainUrl/browse?q=${URLEncoder.encode(query, "UTF-8")}"
        val html = fetchHtml(url)
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)

        extractInitial(doc)?.let { initial ->
            val items = readQueries(initial) { k ->
                k.length() >= 1 && k.optString(0) == "manga"
            }
            if (items.isNotEmpty()) return items
        }

        return doc.select("a[href*='/title/']").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = a.selectFirst("h3, .title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime)
        }.distinctBy { it.url }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> {
        val html = fetchHtml(mangaUrl)
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)

        val chapters = mutableMapOf<Double, Pair<String, String>>()

        doc.select("a.mchap-row__primary, a[href*='-chapter-']").forEach { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
            val m = Regex("""-chapter-([\d.]+)""", RegexOption.IGNORE_CASE).find(href)
                ?: return@forEach
            val num = m.groupValues[1].toDoubleOrNull() ?: return@forEach
            if (!chapters.containsKey(num)) {
                chapters[num] = (a.text().trim().ifBlank { "Ch. $num" }) to href
            }
        }

        var maxPage = 1
        doc.select(".npager__num").forEach { el ->
            (el.text().toIntOrNull() ?: 1).let { if (it > maxPage) maxPage = it }
        }

        if (maxPage > 1) {
            val pages = (2..maxPage).toList().chunked(5)
            for (batch in pages) {
                coroutineScope {
                    batch.map { p ->
                        async {
                            val u = if (mangaUrl.contains("?")) "$mangaUrl&page=$p"
                                    else "$mangaUrl?page=$p"
                            val h = runCatching { fetchHtml(u) }.getOrDefault("")
                            if (h.isNotBlank()) {
                                Jsoup.parse(h)
                                    .select("a[href*='-chapter-']")
                                    .forEach { a ->
                                        val href = a.attr("href")
                                            .takeIf { it.isNotBlank() } ?: return@forEach
                                        val m = Regex(
                                            """-chapter-([\d.]+)""",
                                            RegexOption.IGNORE_CASE
                                        ).find(href) ?: return@forEach
                                        val num = m.groupValues[1].toDoubleOrNull()
                                            ?: return@forEach
                                        synchronized(chapters) {
                                            if (!chapters.containsKey(num)) {
                                                chapters[num] =
                                                    (a.text().trim().ifBlank { "Ch. $num" }) to href
                                            }
                                        }
                                    }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        return chapters.toSortedMap().entries.mapIndexed { i, entry ->
            newEpisode(fixUrl(entry.value.second)) {
                this.name = entry.value.first
                this.episode = i + 1
            }
        }
    }

    override suspend fun pages(chapterUrl: String): List<String> = emptyList()

    // ✅ FIX 3: added isFinishing/isDestroyed guard
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activity = CommonActivity.activity as? androidx.appcompat.app.AppCompatActivity
            ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        val chapterName = Regex("-chapter-([\\d.]+)").find(data)
            ?.groupValues?.get(1)?.let { "Ch. $it" } ?: "Chapter"

        activity.runOnUiThread {
            ReaderDialog.show(activity, name, chapterName, data, baseUrl, 0)
        }
        return true
    }

    private companion object {
        val CAPTURE_SCRIPT = """
            (function () {
                if (window.__comixCipherHook) return;
                window.__comixCipherHook = true;
                var captures = window.__comixCipherCaptures = [];
                var originalAtob = window.atob;
                var stealthAtob = function (value) {
                    var decoded = originalAtob.call(window, value);
                    try {
                        var len = decoded.length;
                        if (len === 256 || len === 24 || len === 32) {
                            var bytes = new Array(len);
                            for (var i = 0; i < len; i++) bytes[i] = decoded.charCodeAt(i) & 255;
                            captures.push(bytes);
                            var sboxes = captures.filter(function (x) { return x.length === 256; }).slice(0, 3);
                            var keys   = captures.filter(function (x) { return x.length === 24 || x.length === 32; }).slice(0, 3);
                            if (sboxes.length === 3 && keys.length === 3) {
                                ComixCipherBridge.submit(JSON.stringify({ sboxes: sboxes, keys: keys }));
                            }
                        }
                    } catch (e) {}
                    return decoded;
                };
                var origFp = Function.prototype.toString;
                Function.prototype.toString = function () {
                    if (this === stealthAtob) return 'function atob() { [native code] }';
                    return origFp.call(this);
                };
                Object.defineProperty(window, 'atob', { value: stealthAtob, writable: true, configurable: true });
            })();
        """.trimIndent()
    }
}
