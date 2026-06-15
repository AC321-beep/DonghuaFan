package com.livesports

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class IPTVProvider(
    private val customName: String = "LiveSports IPTV",
    private val customMainUrl: String = "https://fifabd.site/OPLLX7/LIVE2.m3u"
) : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        const val EXT_M3U = "#EXTM3U"
        const val EXT_INF = "#EXTINF"
    }

    override var lang = "en"
    override var mainUrl = customMainUrl
    override var name = customName
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val headers = mapOf(
        "Accept" to "*/*",
        "Cache-Control" to "no-cache, no-store",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    private val customHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(headers))
            .build()
    }

    private suspend fun getWithCustomHeaders(url: String): String {
        val request = Request.Builder().url(url).build()
        return customHttpClient.newCall(request).execute().use { it.body.string() }
    }

    private fun decryptContent(content: String): String {
        if (content.startsWith(EXT_M3U) || content.startsWith(EXT_INF)) return content
        val trimmed = content.trim()
        if (trimmed.length < 79) return trimmed

        return try {
            val part1 = trimmed.substring(0, 10)
            val part2 = trimmed.substring(34, trimmed.length - 54)
            val part3 = trimmed.substring(trimmed.length - 10)
            val encryptedData = part1 + part2 + part3
            val iv = Base64.decode(trimmed.substring(10, 34), Base64.DEFAULT)
            val key = Base64.decode(trimmed.substring(trimmed.length - 54, trimmed.length - 10), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(Base64.decode(encryptedData, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (e: Exception) {
            content
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val raw = getWithCustomHeaders(mainUrl)
        val decrypted = decryptContent(raw)
        val data = IptvPlaylistParser().parseM3U(decrypted)

        val homeLists = data.items.groupBy { it.attributes["group-title"] ?: "Uncategorized" }
            .map { (group, channels) ->
                val items = channels.map { channel ->
                    val loadData = LoadData(
                        url = channel.url ?: "",
                        title = channel.title ?: "Unknown",
                        poster = channel.attributes["tvg-logo"] ?: "",
                        nation = group,
                        key = channel.key ?: "",
                        keyid = channel.keyid ?: "",
                        userAgent = channel.userAgent ?: "",
                        cookie = channel.cookie ?: "",
                        licenseUrl = channel.licenseUrl ?: "",
                        drmKeys = channel.drmKeys,
                        headers = channel.headers
                    )
                    newLiveSearchResponse(channel.title ?: "Channel", loadData.toJson(), TvType.Live) {
                        this.posterUrl = channel.attributes["tvg-logo"]
                        this.lang = group
                    }
                }
                HomePageList(group, items, isHorizontalImages = true)
            }
        return newHomePageResponse(homeLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val raw = getWithCustomHeaders(mainUrl)
        val decrypted = decryptContent(raw)
        val data = IptvPlaylistParser().parseM3U(decrypted)
        return data.items.filter { it.title?.contains(query, ignoreCase = true) == true }
            .map { channel ->
                val loadData = LoadData(
                    url = channel.url ?: "",
                    title = channel.title ?: "",
                    poster = channel.attributes["tvg-logo"] ?: "",
                    nation = channel.attributes["group-title"] ?: "",
                    key = channel.key ?: "",
                    keyid = channel.keyid ?: "",
                    userAgent = channel.userAgent ?: "",
                    cookie = channel.cookie ?: "",
                    licenseUrl = channel.licenseUrl ?: "",
                    drmKeys = channel.drmKeys,
                    headers = channel.headers
                )
                newLiveSearchResponse(channel.title ?: "", loadData.toJson(), TvType.Live) {
                    this.posterUrl = channel.attributes["tvg-logo"]
                    this.lang = channel.attributes["group-title"]
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = data.nation
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        val headers = buildMap {
            putAll(loadData.headers)
            if (loadData.userAgent.isNotBlank()) put("User-Agent", loadData.userAgent)
            if (loadData.cookie.isNotBlank()) put("Cookie", loadData.cookie)
        }

        when {
            loadData.url.contains("mpd") -> handleMpd(loadData, headers, callback)
            loadData.url.contains("&e=.m3u") || loadData.url.contains("play.php?") ->
                callback.invoke(createExtractor(loadData.url, ExtractorLinkType.M3U8, headers))
            else -> callback.invoke(createExtractor(loadData.url, INFER_TYPE, headers))
        }
        return true
    }

    private suspend fun handleMpd(loadData: LoadData, headers: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val hasValidKeys = loadData.key.isNotBlank() && loadData.keyid.isNotBlank()
        val hasLicenseUrl = loadData.licenseUrl.isNotBlank()

        if (hasValidKeys) {
            var key = loadData.key.base64ToHexOrNull() ?: loadData.key.trim()
            var kid = loadData.keyid.base64ToHexOrNull() ?: loadData.keyid.trim()

            if (loadData.drmKeys.isNotEmpty()) {
                val mpdXml = getMpdStream(loadData.url, headers)
                val mpdKid = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                    .find(mpdXml)?.groupValues?.get(1)?.replace("-", "")?.lowercase()
                if (!mpdKid.isNullOrEmpty()) {
                    val mapped = loadData.drmKeys[mpdKid]
                    if (!mapped.isNullOrEmpty()) {
                        kid = mpdKid
                        key = mapped
                    }
                }
            }
            callback.invoke(newDrmExtractorLink(name, name, loadData.url, INFER_TYPE, CLEARKEY_UUID) {
                quality = Qualities.Unknown.value
                if (headers.isNotEmpty()) this.headers = headers
                this.key = key.hexToBase64UrlOrNull() ?: key
                this.kid = kid.hexToBase64UrlOrNull() ?: kid
            })
        } else if (hasLicenseUrl) {
            val mpdXml = getMpdStream(loadData.url, headers)
            val kidHex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                .find(mpdXml)?.groupValues?.get(1) ?: UUID.randomUUID().toString()
            val kidBase64 = kidHex.replace("-", "").hexToBase64UrlOrNull() ?: kidHex
            val keyBase64 = fetchKeyFromLicenseServer(loadData.licenseUrl, kidBase64)
            if (keyBase64.isNotBlank()) {
                callback.invoke(newDrmExtractorLink(name, name, loadData.url, INFER_TYPE, CLEARKEY_UUID) {
                    quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) this.headers = headers
                    this.key = keyBase64
                    this.kid = kidBase64
                })
            } else {
                callback.invoke(newDrmExtractorLink(name, name, loadData.url, INFER_TYPE, CLEARKEY_UUID) {
                    quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) this.headers = headers
                    this.licenseUrl = loadData.licenseUrl
                })
            }
        } else {
            callback.invoke(createExtractor(loadData.url, ExtractorLinkType.DASH, headers))
        }
    }

    private suspend fun getMpdStream(url: String, headers: Map<String, String>): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(headers))
            .build()
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    private suspend fun fetchKeyFromLicenseServer(licenseUrl: String, kid: String): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(mapOf(
                "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android)",
                "Content-Type" to "application/json;charset=UTF-8"
            )))
            .build()
        val body = "{\"kids\":[\"$kid\"],\"type\":\"temporary\"}"
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(licenseUrl).post(body).build()
        return client.newCall(request).execute().use { resp ->
            val json = parseJson<Map<String, Any>>(resp.body.string())
            ((json["keys"] as? List<Map<String, String>>)?.firstOrNull()?.get("k") ?: "").trim()
        }
    }

    private suspend fun createExtractor(url: String, type: ExtractorLinkType?, headers: Map<String, String>, customName: String = name): ExtractorLink {
        return newExtractorLink(customName, customName, url, type) {
            referer = ""
            quality = Qualities.Unknown.value
            if (headers.isNotEmpty()) this.headers = headers
        }
    }

    data class LoadData(
        val url: String, val title: String, val poster: String, val nation: String,
        val key: String, val keyid: String, val userAgent: String, val cookie: String,
        val licenseUrl: String, val drmKeys: Map<String, String>, val headers: Map<String, String>
    )
}
