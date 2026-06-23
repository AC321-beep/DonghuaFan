package com.livesports

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FifaLive : MainAPI() {
    override var lang = "en"
    override var mainUrl: String = base64Decode("aHR0cHM6Ly9ob3N0LmNsb3VkcGxheS5tZQ==")
    override var name = "⚽FifaLive" 
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiHeaders = mapOf(
        "Connection" to "Keep-Alive",
        "User-Agent" to "okhttp/4.12.0",
        "X-Package" to base64Decode("Y29tLmNsb3VkcGxheS5hcHA=")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val req = app.get("$mainUrl/app.php", headers = apiHeaders)
        val res = req.parsedSafe<CloudPlayResponse>()
            ?: throw Error("Failed to parse app.php. Text: ${req.text}")

        val decryptedJson = decryptPayload(res.payload, res.iv)
        
        // --- FILTERING LOGIC ---
        // We parse the master list and immediately throw away any playlist 
        // that is not related to "fifa" or "fancode"
        val streams = parseJson<CloudPlayStreams>(decryptedJson).streams.filter { stream ->
            val folderName = stream.name?.lowercase() ?: ""
            folderName.contains("fifa") || folderName.contains("fancode")
        }

        val homePageLists = mutableListOf<HomePageList>()
        streams.amap { stream ->
            val sections = fetchHomeSections(stream.name ?: "Unknown", stream.url, stream.logo)
            homePageLists.addAll(sections)
        }

        return newHomePageResponse(homePageLists)
    }

    private suspend fun fetchHomeSections(
        sectionName: String,
        url: String,
        fallbackLogo: String?
    ): List<HomePageList> {
        val isHost = url.contains(base64Decode("aG9zdC5jbG91ZHBsYXkubWU="))
        val headers = if (isHost) apiHeaders else emptyMap()

        val resText = app.get(url, headers = headers).text
        if (resText.isBlank()) return emptyList()

        try {
            val channels = parseJson<List<CloudPlayChannel>>(resText)
            if (channels.isNotEmpty() && channels[0].m3u8_url != null) {
                val shows = channels.map { channel ->
                    val channelName = channel.name ?: "Unknown"
                    val posterUrl = channel.logo ?: fallbackLogo ?: ""
                    newLiveSearchResponse(channelName, channel.toJson(), TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                }
                return listOf(HomePageList(sectionName, shows, isHorizontalImages = true))
            }
        } catch (_: Exception) {}

        try {
            val subStreams = parseJson<List<CloudPlayStream>>(resText)
            if (subStreams.isNotEmpty()) {
                val sections = mutableListOf<HomePageList>()
                subStreams.amap { subStream ->
                    val subSections = fetchHomeSections(
                        subStream.name ?: sectionName,
                        subStream.url,
                        subStream.logo ?: fallbackLogo
                    )
                    sections.addAll(subSections)
                }
                return sections
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    private suspend fun fetchChannels(url: String, fallbackLogo: String?): List<SearchResponse> {
        val shows = mutableListOf<SearchResponse>()
        val isHost = url.contains(base64Decode("aG9zdC5jbG91ZHBsYXkubWU="))
        val headers = if (isHost) apiHeaders else emptyMap()

        val resText = app.get(url, headers = headers).text
        if (resText.isBlank()) return shows

        try {
            val channels = parseJson<List<CloudPlayChannel>>(resText)
            if (channels.isNotEmpty() && channels[0].m3u8_url != null) {
                return channels.map { channel ->
                    val channelName = channel.name ?: "Unknown"
                    val posterUrl = channel.logo ?: fallbackLogo ?: ""
                    newLiveSearchResponse(channelName, channel.toJson(), TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val subStreams = parseJson<List<CloudPlayStream>>(resText)
            if (subStreams.isNotEmpty()) {
                val allShows = subStreams.amap { subStream ->
                    fetchChannels(subStream.url, subStream.logo ?: fallbackLogo)
                }.flatten()
                shows.addAll(allShows)
            }
        } catch (_: Exception) {}

        return shows
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/app.php", headers = apiHeaders).parsedSafe<CloudPlayResponse>()
            ?: return emptyList()

        val decryptedJson = decryptPayload(res.payload, res.iv)
        
        // Also apply the filter to search so JioTV junk doesn't appear in results
        val streams = parseJson<CloudPlayStreams>(decryptedJson).streams.filter { stream ->
            val folderName = stream.name?.lowercase() ?: ""
            folderName.contains("fifa") || folderName.contains("fancode")
        }

        val allChannels = streams.amap { stream ->
            fetchChannels(stream.url, stream.logo)
        }.flatten()

        return allChannels.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<CloudPlayChannel>(url)
        val title = data.name ?: "Unknown"
        val poster = data.logo ?: ""

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.plot = data.group
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = parseJson<CloudPlayChannel>(data)

        if (channel.m3u8_url != null) {
            val isTs = channel.m3u8_url.contains(".ts", ignoreCase = true)
            callback.invoke(
                newExtractorLink(
                    this.name,
                    channel.name ?: "HLS",
                    channel.m3u8_url,
                    if (isTs) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    if (channel.headers != null) {
                        this.headers = channel.headers
                    }
                    channel.headers?.forEach { (key, value) ->
                        if (key.equals("referer", ignoreCase = true)) {
                            this.referer = value
                        }
                    }
                }
            )
        }
        return true
    }

    private fun decryptPayload(payloadBase64: String, ivBase64: String): String {
        val SECRET = base64Decode("YmFja3VwLXVwZGF0ZS0zLjM=")
        val PACKAGE = base64Decode("Y29tLmNsb3VkcGxheS5hcHA=")

        val digest = MessageDigest.getInstance("SHA-256")
        val keyHash = digest.digest((SECRET + PACKAGE).toByteArray(Charsets.UTF_8))

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKeySpec = SecretKeySpec(keyHash, "AES")
        val ivParameterSpec = IvParameterSpec(base64DecodeArray(ivBase64))

        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

        val decrypted = cipher.doFinal(base64DecodeArray(payloadBase64))
        return String(decrypted, Charsets.UTF_8)
    }
}
