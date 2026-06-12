@Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)

        val html = try { app.get(data, headers = headers).text } catch (e: Exception) { "" }

        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            val rawUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1)?.replace("\\/", "/")
            val from = Regex(""""from"\s*:\s*"([^"]+)"""").find(playerJson)?.groupValues?.get(1) ?: ""

            if (from.equals("dailymotion", ignoreCase = true) || rawUrl?.contains("dailymotion") == true) {
                val dmId = extractDailymotionId(rawUrl ?: "")
                if (dmId != null) {
                    val videoUrl = "https://www.dailymotion.com/video/$dmId"
                    return loadExtractor(videoUrl, data, subtitleCallback, callback)
                }
            } 
            else if (rawUrl != null && (rawUrl.contains(".m3u8", ignoreCase = true) || rawUrl.contains(".mp4", ignoreCase = true))) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = rawUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = INFER_TYPE // <--- This completely bypasses the deprecated constructor
                    )
                )
                return true
            }
        }

        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src*='dailymotion']")?.forEach { iframe ->
            val src = iframe.attr("src")
            val dmId = extractDailymotionId(src)
            if (dmId != null) {
                val videoUrl = "https://www.dailymotion.com/video/$dmId"
                return loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }

        val dmIdMatch = Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)""").find(html)
        if (dmIdMatch != null) {
            val dmId = dmIdMatch.groupValues[1]
            val videoUrl = "https://www.dailymotion.com/video/$dmId"
            return loadExtractor(videoUrl, data, subtitleCallback, callback)
        }

        doc?.select("iframe[src]")?.forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotBlank() && !src.contains("dailymotion")) {
                if (loadExtractor(src, data, subtitleCallback, callback)) return true
            }
        }

        Log.d(TAG, "No playable source found for $data")
        return false
    }
