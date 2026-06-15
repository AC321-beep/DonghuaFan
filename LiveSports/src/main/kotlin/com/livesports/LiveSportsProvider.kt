package com.livesports

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class LiveSportsProvider : MainAPI() {
    override var name = "Live Sports"
    override var supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override var lang = "en"

    // Data class to cleanly pass the direct CNC stream links between the UI and the Video Player
    data class StreamPayload(
        val title: String,
        val poster: String?,
        val streamUrl: String
    )

    // CNC's active, unencrypted gateway list
    // You can seamlessly add or update links here whenever CNC updates their repository!
    private val cncWorkingEndpoints = listOf(
        mapOf("title" to "TATA PLAY", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQz_qYe3Y4S5bXXVlPtXQnqtAkLw1-no57QHhPyMgWE0SQmxujzHxZKiDs&s=10", "catLink" to "https://hotstarlive.delta-cloud.workers.dev/?token=240bb9-374e2e-3c13f0-4a7xz5"),
        mapOf("title" to "HOTSTAR", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRWwYjMvB58DMLsL9Ii2fhvw6NBYvD1iVCjOMU8TXBLJt0eibLGOjoRkLJP&s=10", "catLink" to "https://hotstar-live-event.alpha-circuit.workers.dev/?token=a13d9c-4b782a-6c90fd-9a1b84"),
        mapOf("title" to "JIO IND", "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png", "catLink" to "https://jiotv.byte-vault.workers.dev/?token=42e4f5-2d873b-3c37d8-7f3f50"),
        mapOf("title" to "SONY IN", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU", "catLink" to "https://sonyliv.logic-lane.workers.dev?token=a14d9c-4b782a-6c90fd-9a1b84"),
        mapOf("title" to "SUN DIRECT", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSwc4OuqPmOP-Fi9dhfiDw_q-s3rOmgCPla_IaE76VD2KRQ7c4KHeI2zJY&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/suntv.m3u"),
        mapOf("title" to "VOOT IND", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfS6QZFts2FoedMGZE28H7Kh158PsrNIiabFBVJMy_jXa8Tvvb9WAlut8&s=10", "catLink" to "https://jiocinema-live.cloud-hatchh.workers.dev/?token=42e4f5-2d414b-3c37d8-5f3f45"),
        mapOf("title" to "ZEE5", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU", "catLink" to "https://zee5.cloud-hatchh.workers.dev/?token=42e4f5-2d413b-3c37d8-7f3f35"),
        mapOf("title" to "ICC TV", "image" to "https://m.media-amazon.com/images/I/31F7ropt9OL.png", "catLink" to "https://icc.alpha-circuit.workers.dev/?token=42e4f5-2d863b-3c37d8-7f3f69"),
        mapOf("title" to "JIOTV+ S2", "image" to "https://i.ibb.co/VY9ND7rY/image.png", "catLink" to "https://jiotvplus.byte-vault.workers.dev/?token=42e4f5-2d863b-3c38d8-7f3f51"),
        mapOf("title" to "T SPORTS", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRJ0QvfKyjAqcCOumIXjcuYg505GnaBeVk2lQ&usqp=CAU", "catLink" to "https://fifabangladesh2-xyz-ekkj.spidy.online/AYN/tsports.m3u")
    )

    // 1. BUILDS THE HOMEPAGE INSTANTLY (No API Decryption Delay)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channelCards = cncWorkingEndpoints.mapNotNull { endpoint ->
            val title = endpoint["title"] ?: return@mapNotNull null
            val image = endpoint["image"]
            val link = endpoint["catLink"] ?: return@mapNotNull null

            // We bundle the exact stream URL into the click payload
            val payload = StreamPayload(title, image, link)

            newLiveSearchResponse(
                name = title,
                url = payload.toJson(),
                type = TvType.Live
            ) {
                this.posterUrl = image
            }
        }

        val homePages = listOf(
            HomePageList("Live Networks (CNC Sync)", channelCards)
        )

        return newHomePageResponse(homePages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList() // Disabled for direct list routing
    }

    // 2. LOADS THE MATCH DETAILS
    override suspend fun load(url: String): LoadResponse? {
        val payload = parseJson<StreamPayload>(url)
        
        return newLiveStreamLoadResponse(
            name = payload.title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = payload.poster
        }
    }

    // 3. EXTRACTS THE VIDEO STREAMS DIRECTLY
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parseJson<StreamPayload>(data)
        val streamUrl = payload.streamUrl

        // Some CNC endpoints point to a raw M3U text file on GitHub instead of an M3U8 video feed.
        // If it's a playlist file, we fetch it and pass the internal streams.
        if (streamUrl.endsWith(".m3u", ignoreCase = true) && streamUrl.contains("githubusercontent")) {
            try {
                val m3uText = app.get(streamUrl).text
                // Find all lines that look like a URL in the playlist
                val links = m3uText.lines().filter { it.startsWith("http") }
                
                links.forEachIndexed { index, link ->
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${payload.title} - Server ${index + 1}",
                            url = link.trim(),
                            referer = "",
                            quality = Qualities.Unknown.value,
                            type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                        )
                    )
                }
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // For Cloudflare Workers and direct M3U8 feeds, pass them straight to ExoPlayer
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = payload.title,
                url = streamUrl,
                referer = "",
                quality = Qualities.Unknown.value,
                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                // Attach a standard User-Agent just in case the worker checks for one
                this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
        )

        return true
    }
}
