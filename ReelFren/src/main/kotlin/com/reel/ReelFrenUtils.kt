package com.reel

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

// ── HTTP client ──────────────────────────────────────────────────────────────
object ReelFrenClient {

    const val UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                   "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // ★ Removed CloudflareKiller entirely. Its WebView-based interceptor is the
    //   hang you're chasing: it blocks OkHttp's dispatcher thread waiting for
    //   an activity that may not be in the foreground, with no cancellation
    //   path back to our coroutine. We already have a proper solver in
    //   ReelFrenCloudflare that persists host-scoped cookies + UA.

    fun apiBase() = ReelFrenStore.apiBase()
    fun query(v: String) = URLEncoder.encode(v, "UTF-8")

    fun homeUrl(slug: String, category: String = "", page: Int = 1): String {
        val p = mutableListOf("provider=${query(slug)}", "lang=en")
        if (category.isNotEmpty()) p += "category=${query(category)}"
        val off = page - 1
        if (off > 0) p += "offset=$off"
        return "${apiBase()}/api/home?${p.joinToString("&")}"
    }
    fun detailUrl(slug: String, id: String) =
        "${apiBase()}/api/detail?provider=${query(slug)}&id=${query(id)}"
    fun videoUrl(slug: String, id: String, ep: Int) =
        "${apiBase()}/api/video?provider=${query(slug)}&id=${query(id)}&ep=$ep"
    fun searchUrl(slug: String, term: String) =
        "${apiBase()}/api/search?q=${query(term)}&provider=${query(slug)}"

    fun headers(url: String): Map<String, String> {
        val host = ReelFrenCloudflare.hostOf(url)
        val savedCookie = ReelFrenStore.cookie(host)
        val savedUa = ReelFrenStore.userAgent(host) ?: UA

        val headers = linkedMapOf(
            "User-Agent"      to savedUa,
            "Accept"          to "application/json, text/html, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin"          to ReelFrenConfig.REEL_SITE,
            "Referer"         to "${ReelFrenConfig.REEL_SITE}/"
        )
        if (savedCookie.isNotBlank()) headers["Cookie"] = savedCookie
        ReelFrenCfLog.d("Client.headers",
            "url=$url host=$host cookieLen=${savedCookie.length} " +
            "hasCf=${savedCookie.contains("cf_clearance")} " +
            "uaFromStore=${ReelFrenStore.userAgent(host) != null}")
        return headers
    }

    suspend fun get(url: String): String? {
        ReelFrenCfLog.d("Client.get", "ENTRY url=$url")

        // ── First attempt — plain HTTP, NO cfKiller. ────────────────────────
        ReelFrenCfLog.d("Client.get", "first attempt start")
        val first = try {
            withTimeoutOrNull(20_000L) {
                app.get(url, headers = headers(url)).text
            }
        } catch (e: Exception) {
            ReelFrenCfLog.t("Client.get", e)
            null
        }
        ReelFrenCfLog.d("Client.get",
            "first attempt done len=${first?.length} isChallenge=${first?.let { ReelFrenCloudflare.isChallengeBody(it) }}")

        if (first != null && !ReelFrenCloudflare.isChallengeBody(first)) {
            ReelFrenCfLog.d("Client.get", "first attempt OK -> return")
            return first
        }

        // ── Challenge — run our own solver. ─────────────────────────────────
        val targetHost = ReelFrenCloudflare.hostOf(url)
        val solveUrl = if (targetHost == ReelFrenCloudflare.hostOf(ReelFrenConfig.REEL_SITE))
            ReelFrenConfig.REEL_SITE
        else url
        ReelFrenCfLog.d("Client.get", "invoking solver solveUrl=$solveUrl")

        val solved = withTimeoutOrNull(120_000L) {
            ReelFrenCloudflare.solve(solveUrl)
        } ?: false
        ReelFrenCfLog.d("Client.get", "solver returned solved=$solved")

        if (!solved) {
            ReelFrenCfLog.d("Client.get", "solver failed -> returning null")
            return null
        }

        // ── Retry — plain HTTP, then VERIFY it's not another challenge. ─────
        val retry = try {
            withTimeoutOrNull(20_000L) {
                app.get(url, headers = headers(url)).text
            }
        } catch (e: Exception) {
            ReelFrenCfLog.t("Client.get", e)
            null
        }
        val retryIsChallenge = retry?.let { ReelFrenCloudflare.isChallengeBody(it) } ?: false
        ReelFrenCfLog.d("Client.get",
            "retry done len=${retry?.length} isChallenge=$retryIsChallenge")

        // If the retry is *still* a challenge, `solve()` short-circuited on a
        // stale cookie. Wipe it so the next attempt runs the solver for real.
        if (retry != null && retryIsChallenge) {
            ReelFrenCfLog.d("Client.get",
                "retry STILL challenged — wiping stale cookie for $targetHost")
            ReelFrenStore.saveCookie(targetHost, "")
            return null
        }

        return retry
    }

    suspend fun home(slug: String, category: String = "", page: Int = 1): List<HomeItem> {
        val body = get(homeUrl(slug, category, page))
        ReelFrenCfLog.d("Client.home",
            "slug=$slug page=$page bodyLen=${body?.length} bodyNull=${body == null}")
        return body?.let(ReelFrenParse::homeItems) ?: emptyList()
    }

    suspend fun detail(slug: String, id: String): DetailInfo? {
        val body = get(detailUrl(slug, id))
        ReelFrenCfLog.d("Client.detail",
            "slug=$slug id=$id bodyLen=${body?.length}")
        return body?.let(ReelFrenParse::detail)
    }

    suspend fun playback(slug: String, id: String, ep: Int): PlaybackInfo? {
        val body = get(videoUrl(slug, id, ep))
        ReelFrenCfLog.d("Client.playback",
            "slug=$slug id=$id ep=$ep bodyLen=${body?.length}")
        return body?.let(ReelFrenParse::playback)
    }

    suspend fun search(slug: String, term: String): List<HomeItem> {
        val body = get(searchUrl(slug, term))
        ReelFrenCfLog.d("Client.search",
            "slug=$slug term=$term bodyLen=${body?.length}")
        return body?.let(ReelFrenParse::homeItems) ?: emptyList()
    }

    fun absUrl(value: String): String {
        val clean = value.trim()
        if (clean.isEmpty()) return ""
        if (clean.startsWith("https://", true) || clean.startsWith("http://", true)) return clean
        if (clean.startsWith("//")) return "https:$clean"
        val base = apiBase()
        return if (clean.startsWith("/")) "$base$clean" else "$base/$clean"
    }
}
