package com.livesports

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class IPTVProvider(
    private val customName: String = "LiveSports IPTV",
    private val customMainUrl: String = "https://fifabangladesh2-xyz-ekkj.spidy.online/AYN/tsports.m3u"
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

    private val defaultHeaders = mapOf(
        "Accept" to "*/*",
        "Cache-Control" to "no-cache, no-store",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    private val customHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(defaultHeaders))
            .build()
    }

    private fun getWithCustomHeaders(url: String): String {
        val request = Request.Builder().url(url).build()
        return customHttpClient.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private fun decryptContent(content: String): String {
        if (content.startsWith(EXT_M3U) || content.startsWith(EXT_INF)) return content
        val trimmed = content.trim()
        if (trimmed.length < 79) return trimmed
        return CryptoUtils.decryptData(trimmed) ?: content
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val raw = getWithCustomHeaders(mainUrl)
        val decrypted = decryptContent(raw)
        val data = IptvPlaylistParser().parseM3U(decrypted)

        val homeLists = data.items.groupBy { it.attributes["group-title"] ?: "Uncategorized" }
            .map { (group, channels) ->
                val items = channels.map { channel ->
                    val loadData = IptvLoadData(
                        url = channel.url ?: "", title = channel.title ?: "Unknown",
                        poster = channel.attributes["tvg-logo"] ?: "", nation = group,
                        key = channel.key ?: "", keyid = channel.keyid ?: "",
                        userAgent = channel.userAgent ?: "", cookie = channel.cookie ?: "",
                        licenseUrl = channel.licenseUrl ?: "", drmKeys = channel.drmKeys, headers = channel.headers
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

    override suspend fun search(query: String): List<SearchResponse> { return emptyList() }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<IptvLoadData>(url)
        return newLiveStreamLoadResponse(data.title, url, url) { this.posterUrl = data.poster; this.plot = data.nation }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = parseJson<IptvLoadData>(data)
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

    private suspend fun handleMpd(loadData: IptvLoadData, headers: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val hasValidKeys = loadData.key.isNotBlank() && loadData.keyid.isNotBlank()
        val hasLicenseUrl = loadData.licenseUrl.isNotBlank()

        if (hasValidKeys) {
            var key = loadData.key.base64ToHexOrNull() ?: loadData.key.trim()
            var kid = loadData.keyid.base64ToHexOrNull() ?: loadData.keyid.trim()

            if (loadData.drmKeys.isNotEmpty()) {
                val mpdXml = getMpdStream(loadData.url, headers)
                val mpdKid = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""").find(mpdXml)?.groups?.get(1)?.value?.replace("-", "")?.lowercase()
                if (!mpdKid.isNullOrEmpty() && !loadData.drmKeys[mpdKid].isNullOrEmpty()) {
                    kid = mpdKid; key = loadData.drmKeys[mpdKid]!!
                }
            }
            callback.invoke(newDrmExtractorLink(name, name, loadData.url, INFER_TYPE, CLEARKEY_UUID) {
                quality = Qualities.Unknown.value; if (headers.isNotEmpty()) this.headers = headers
                this.key = key.hexToBase64UrlOrNull() ?: key; this.kid = kid.hexToBase64UrlOrNull() ?: kid
            })
        } else if (hasLicenseUrl) {
            val mpdXml = getMpdStream(loadData.url, headers)
            val kidHex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""").find(mpdXml)?.groups?.get(1)?.value ?: UUID.randomUUID().toString()
            val kidBase64 = kidHex.replace("-", "").hexToBase64UrlOrNull() ?: kidHex
            
            // Calling the exact logic found in the Smali
            val keyBase64 = fetchKeyFromLicenseServer(loadData.licenseUrl, kidBase64)
            
            if (keyBase64.isNotBlank()) {
                callback.invoke(newDrmExtractorLink(name, name, loadData.url, INFER_TYPE, CLEARKEY_UUID) {
                    quality = Qualities.Unknown.value; if (headers.isNotEmpty()) this.headers = headers
                    this.key = keyBase64; this.kid = kidBase64
                })
            } else {
                callback.invoke(newDrmExtractorLink(name, name, loadData.url, INFER_TYPE, CLEARKEY_UUID) {
                    quality = Qualities.Unknown.value; if (headers.isNotEmpty()) this.headers = headers; this.licenseUrl = loadData.licenseUrl
                })
            }
        } else {
            callback.invoke(createExtractor(loadData.url, ExtractorLinkType.DASH, headers))
        }
    }

    private fun getMpdStream(url: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(url).build()
        return OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(headers))
            .build()
            .newCall(request)
            .execute()
            .use { it.body?.string() ?: "" }
    }

    // This is the direct translation of the getDRMKeysFromLicenseServer Smali method
    private fun fetchKeyFromLicenseServer(licenseUrl: String, kid: String): String {
        return try {
            val client = OkHttpClient.Builder()
                .addInterceptor(HeaderReplacementInterceptor(mapOf(
                    "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android)", // Exact UA from Smali
                    "Content-Type" to "application/json;charset=UTF-8"
                )))
                .build()
            
            val payload = "{\"kids\":[\"$kid\"],\"type\":\"temporary\"}"
            val request = Request.Builder()
                .url(licenseUrl)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
                
            client.newCall(request).execute().use { resp ->
                val responseString = resp.body?.string() ?: ""
                val jsonResponse = parseJson<Map<String, Any>>(responseString)
                val keysList = jsonResponse["keys"] as? List<Map<String, String>>
                (keysList?.firstOrNull()?.get("k") ?: "").trim()
            }
        } catch (e: Exception) { 
            "" 
        }
    }

    private suspend fun createExtractor(url: String, type: ExtractorLinkType?, headers: Map<String, String>, customName: String = name): ExtractorLink {
        return newExtractorLink(customName, customName, url, type) {
            referer = ""; quality = Qualities.Unknown.value; if (headers.isNotEmpty()) this.headers = headers
        }
    }
}
