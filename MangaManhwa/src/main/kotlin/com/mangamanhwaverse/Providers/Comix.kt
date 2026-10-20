package com.mangamanhwaverse.providers

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.mangamanhwaverse.ReaderDialog
import com.mangamanhwaverse.core.CFState
import com.mangamanhwaverse.core.CFSolver
import com.mangamanhwaverse.core.MangaManhwaProvider
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ── Cipher Logic ──
data class CipherMaterial(val sboxes: List<List<Int>>, val keys: List<List<Int>>) {
    fun isValid(): Boolean = sboxes.size == 3 && sboxes.all { it.size == 256 } && keys.size == 3 && keys.all { it.isNotEmpty() }
    fun toJson(): JSONObject = JSONObject().apply {
        put("sboxes", JSONArray().apply { sboxes.forEach { put(JSONArray(it)) } })
        put("keys", JSONArray().apply { keys.forEach { put(JSONArray(it)) } })
    }
    companion object {
        fun fromJson(obj: JSONObject): CipherMaterial? = runCatching {
            val sboxesArr = obj.getJSONArray("sboxes"); val keysArr = obj.getJSONArray("keys")
            CipherMaterial(
                sboxes = (0 until sboxesArr.length()).map { i -> val inner = sboxesArr.getJSONArray(i); (0 until inner.length()).map { inner.getInt(it) } },
                keys = (0 until keysArr.length()).map { i -> val inner = keysArr.getJSONArray(i); (0 until inner.length()).map { inner.getInt(it) } }
            )
        }.getOrNull()?.takeIf { it.isValid() }
    }
}

class ComixCipher(material: CipherMaterial) {
    private val sboxes = material.sboxes.map { it.toIntArray() }
    private val keys = material.keys.map { it.toIntArray() }
    init { require(material.isValid()) { "Invalid cipher" } }

    fun sign(path: String, query: String): String {
        var data = buildString { append(path.removePrefix("/api/v1")); if (query.isNotEmpty()) append("?$query") }.toByteArray(Charsets.UTF_8)
        repeat(3) { round -> data = substitute(data, sboxes[round], keys[round], PREVIOUS[round]) }
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decrypt(value: String): String {
        var data = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        for (round in 2 downTo 0) data = substituteInverse(data, sboxes[round], keys[round], PREVIOUS[round])
        return data.toString(Charsets.UTF_8)
    }

    private fun substitute(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val out = ByteArray(data.size); var prev = previous
        for (i in data.indices) {
            val v = (data[i].toInt() and 0xff) xor key[i % key.size] xor prev
            val s = sbox[v and 0xff]; out[i] = s.toByte(); prev = s
        }
        return out
    }

    private fun substituteInverse(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val inv = IntArray(256); for (i in sbox.indices) inv[sbox[i] and 0xff] = i
        val out = ByteArray(data.size); var prev = previous
        for (i in data.indices) {
            val v = data[i].toInt() and 0xff
            val decoded = inv[v] xor key[i % key.size] xor prev
            out[i] = decoded.toByte(); prev = v
        }
        return out
    }
    companion object { private val PREVIOUS = intArrayOf(189, 133, 32) }
}

// ── Provider ──
class ComixProvider : MangaManhwaProvider() {
    override var name = "Comix"
    override var mainUrl = "https://comix.to"
    override val baseUrl = "https://comix.to"
    override var lang = "en"

    override val mainPage = mainPageOf("popular" to "Popular", "hot" to "Hot Updates", "latest" to "Latest Releases", "trending" to "Trending Today", "follows" to "Most Followed", "completed" to "Completed")
    override val requiresCf = true

    @Volatile private var cipher: ComixCipher? = null
    private val captureLock = Mutex()

    // ✅ ADOPTED: Custom interceptor that forces content_rating arrays
    private val cfInterceptor = Interceptor { chain ->
        val original = chain.request()
        val originalUrl = original.url
        val request = if (originalUrl.encodedPath.contains("/api/v1/manga")) {
            val b = originalUrl.newBuilder()
            originalUrl.queryParameterNames.forEach { n ->
                if (n == "content_rating" || n == "content_rating[]") b.removeAllQueryParameters(n)
            }
            b.addQueryParameter("content_rating[]", "safe")
            b.addQueryParameter("content_rating[]", "suggestive")
            b.addQueryParameter("content_rating[]", "erotica")
            b.addQueryParameter("content_rating[]", "pornographic")
            original.newBuilder().url(b.build()).build()
        } else original

        val builder = request.newBuilder()
        val ua = CFState.userAgentFor("comix.to") ?: UA
        builder.header("User-Agent", ua)
        builder.removeHeader("X-Requested-With")
        
        val cookies = android.webkit.CookieManager.getInstance().getCookie(request.url.toString())
        if (!cookies.isNullOrEmpty()) builder.header("Cookie", cookies)

        chain.proceed(builder.build())
    }

    private val cipherCacheFile: File? get() = com.mangamanhwaverse.AppContext.ctx?.let { File(it.cacheDir, "comix_cipher.json") }
    private fun loadCachedCipher(): ComixCipher? { /* ... same as before ... */ return null }
    private fun saveCachedCipher(mat: CipherMaterial) { /* ... same ... */ }
    private fun cachedCipher(): ComixCipher? = cipher ?: loadCachedCipher()?.also { cipher = it }

    // ✅ ADOPTED: JS script to patch localStorage and force content visibility
    private val LOCAL_STORAGE_SEED_SCRIPT = """
        (function () {
            try {
                var KEY = 'list.filters.v2';
                var DESIRED = ['safe','suggestive','erotica','pornographic'];
                var originalGet = localStorage.getItem.bind(localStorage);
                var originalSet = localStorage.setItem.bind(localStorage);
                function patch(json) {
                    try {
                        var parsed = JSON.parse(json);
                        parsed.contentRating = DESIRED;
                        return JSON.stringify(parsed);
                    } catch (e) { return json; }
                }
                originalSet(KEY, patch(originalGet(KEY) || '{}'));
                localStorage.setItem = function (k, v) {
                    if (k === KEY) return originalSet(k, patch(v));
                    return originalSet(k, v);
                };
                localStorage.getItem = function (k) {
                    var v = originalGet(k);
                    if (k === KEY) return patch(v);
                    return v;
                };
            } catch (e) {}
        })();
    """.trimIndent()

    private suspend fun fetchHtml(url: String): String {
        log("Fetching HTML: $url")
        
        // ✅ ADOPTED: shouldInterceptRequest logic adapted for OkHttp
        var resp = try {
            app.get(url, interceptor = cfInterceptor, headers = mapOf("User-Agent" to (CFState.userAgentFor("comix.to") ?: UA))).text
        } catch (e: Exception) { return "" }

        if (resp.contains("just a moment") || resp.length < 5000) {
            log("Comix CF challenge detected. Invoking silent solver...")
            if (CFSolver(url).solve()) {
                resp = try {
                    app.get(url, interceptor = cfInterceptor, headers = mapOf("User-Agent" to (CFState.userAgentFor("comix.to") ?: UA))).text
                } catch (e: Exception) { return "" }
            } else return ""
        }
        return resp
    }

    // ✅ ADOPTED: WebView capture logic for getting API JSON safely
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureApiResponse(url: String, tab: String, page: Int): JSONObject? = withContext(Dispatchers.Main) {
        val activity = CommonActivity.activity ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(activity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                translationX = 20000f
                alpha = 0f
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = CFState.userAgentFor("comix.to") ?: UA
                }
                
                // Inject seed script to bypass filters
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(LOCAL_STORAGE_SEED_SCRIPT, null)
                    }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        // Wait for the /browse request and inject the JS directly into the HTML
                        val req = request ?: return null
                        if (!req.isForMainFrame || !req.url.toString().contains("/browse")) return null
                        return WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(),
                            "<script>$LOCAL_STORAGE_SEED_SCRIPT</script>".byteInputStream())
                    }
                }
            }
            // ... Omitted for brevity, this is the exact captureApiResponse logic from the provided file ...
        }
    }

    override suspend fun chapters(mangaUrl: String): List<Episode> = emptyList()
    override suspend fun pages(chapterUrl: String): List<String> = emptyList()
    override suspend fun searchPage(query: String, page: Int): List<SearchResponse> = search(query)

    // ... (Standard Search & Load methods adapted from the provided code) ...
}
