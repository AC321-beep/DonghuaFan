private suspend fun invokeStreamLink(
        streamUrl: String,
        cdnReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isPlaylist = streamUrl.contains(".m3u8") || streamUrl.contains("playlist")
        
        val playerHeaders = mapOf(
            "User-Agent" to CHROME_UA,
            "Referer" to "$cdnReferer/",
            "Origin" to cdnReferer,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Dest" to "empty"
        )

        Log.d(TAG, "Packaging Stream Link Resource: $streamUrl | Playlist: $isPlaylist")
        if (isPlaylist) {
            try {
                M3u8Helper.generateM3u8(
                    name = name,
                    source = streamUrl,
                    referer = "$cdnReferer/",
                    headers = playerHeaders
                ).forEach(callback)
            } catch (e: Exception) {
                Log.e(TAG, "HLS Manifest Engine Generation Phase Fail: ${e.message}")
            }
        } else {
            // FIXED: Using explicit named parameters to satisfy Cloudstream's constructor rules
            callback(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$cdnReferer/"
                    this.quality = Qualities.P1080.value
                    this.headers = playerHeaders
                }
            )
        }
    }
