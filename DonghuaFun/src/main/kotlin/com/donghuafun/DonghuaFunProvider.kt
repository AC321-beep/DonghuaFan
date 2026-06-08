    // ── Link Extraction (UPDATED: Prioritize 4K then 1080P ENG, skip Indo) ─────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val showId = detailUrlToId(data)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer"    to data
        )

        // Define sources to try in order: 4K (sid=1), 1080P ENG (sid=2), skip sid=3 (Indo)
        val sourcesToTry = listOf(
            SourceInfo(1, "4K"),
            SourceInfo(2, "1080P ENG")
        )

        var found = false
        for (source in sourcesToTry) {
            // Build URL with this sid, keep nid=1 (site uses nid=latest, but your ordering works)
            val testUrl = "$mainUrl/index.php/vod/play/id/$showId/sid/${source.sid}/nid/1.html"
            Log.d(TAG, "Trying source ${source.sid} (${source.qualityLabel}) via $testUrl")
            
            val result = tryLoadLinksForSource(testUrl, showId, source.sid, headers, subtitleCallback, callback)
            if (result) {
                found = true
                Log.d(TAG, "✓ Found links from ${source.qualityLabel} source (sid=${source.sid})")
                break // Stop after first successful source (prioritizes 4K, then 1080P ENG)
            }
        }

        // Fallback to original method if both priorities failed
        if (!found) {
            Log.d(TAG, "Priority sources failed, falling back to original extraction")
            val (sid, nid) = sidNidFromPlayUrl(data)
            found = tryOriginalLoadLinks(showId, sid, nid, data, headers, subtitleCallback, callback)
        }

        return found
    }

    // Helper to try loading from a specific sid
    private suspend fun tryLoadLinksForSource(
        url: String,
        showId: String,
        sid: Int,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ── Strategy 1: MacCMS JSON API with this sid ───────────────────────
        if (showId.isNotEmpty()) {
            try {
                val apiJson = app.get(
                    "$mainUrl/index.php/ajax/suggest",
                    params = mapOf(
                        "mid"  to "1",
                        "id"   to showId,
                        "sid"  to sid.toString(),
                        "nid"  to "1", // nid=1 often returns the latest/available video for this sid
                        "type" to "1",
                    ),
                    headers = mapOf(
                        "User-Agent"       to USER_AGENT,
                        "Referer"          to url,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                ).text

                Log.d(TAG, "API response for sid=$sid: ${apiJson.take(200)}")

                if (apiJson.isNotEmpty() && apiJson != "false" && !apiJson.startsWith("<")) {
                    if (extractVideoFromJson(apiJson, url, headers, subtitleCallback, callback)) return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call failed for sid=$sid: ${e.message}")
            }
        }

        // ── Strategy 2: Inline player_aaaa in HTML for this sid ─────────────
        val html = try { app.get(url, headers = headers).text } catch (e: Exception) { "" }

        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            Log.d(TAG, "Found inline player_aaaa for sid=$sid")
            if (extractVideoFromJson(playerJson, url, headers, subtitleCallback, callback)) return true
        }

        // ── Strategy 3: iframe scan ─────────────────────────────────────────
        val doc = try { app.get(url, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src], iframe[data-src]")?.forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                Log.d(TAG, "iframe fallback for sid=$sid: $src")
                loadExtractor(fixUrl(src), url, subtitleCallback, callback)
                return true
            }
        }

        return false
    }

    // Your original loadLinks logic preserved as fallback
    private suspend fun tryOriginalLoadLinks(
        showId: String,
        sid: Int,
        nid: Int,
        data: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Original fallback: showId=$showId sid=$sid nid=$nid")

        if (showId.isNotEmpty()) {
            try {
                val apiJson = app.get(
                    "$mainUrl/index.php/ajax/suggest",
                    params = mapOf(
                        "mid"  to "1",
                        "id"   to showId,
                        "sid"  to sid.toString(),
                        "nid"  to nid.toString(),
                        "type" to "1",
                    ),
                    headers = mapOf(
                        "User-Agent"       to USER_AGENT,
                        "Referer"          to data,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                ).text

                if (apiJson.isNotEmpty() && apiJson != "false" && !apiJson.startsWith("<")) {
                    if (extractVideoFromJson(apiJson, data, headers, subtitleCallback, callback)) return true
                }
            } catch (e: Exception) { }
        }

        val html = try { app.get(data, headers = headers).text } catch (e: Exception) { "" }
        val playerJson = Regex("""var\s+player_aaaa\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        if (playerJson != null) {
            if (extractVideoFromJson(playerJson, data, headers, subtitleCallback, callback)) return true
        }

        val doc = try { app.get(data, headers = headers).document } catch (e: Exception) { null }
        doc?.select("iframe[src], iframe[data-src]")?.forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                return true
            }
        }

        return false
    }
