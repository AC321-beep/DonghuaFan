    // ═══════════════════════════════════════════════════════════════════════
    //  Query API for pages 2+ — real endpoint: GET /api/v1/manga
    // ═══════════════════════════════════════════════════════════════════════
    private data class PageResult(
        val items: List<SearchResponse>,
        val hasNext: Boolean
    )

    // Cached session token for the `_` query parameter. Populated from
    // /api/v1/user on first use, falls back to the cfg token, falls back
    // to a random cache-buster.
    @Volatile private var sessionToken: String? = null

    private suspend fun fetchQueryPage(
        request: MainPageRequest,
        page: Int
    ): PageResult? {
        val (order, extraQuery, limit) = when (request.data) {
            "hot"    -> Triple("chapter_updated_at", "&scope=hot", 31)
            "latest" -> Triple("created_at", "", 10)
            else     -> return null
        }

        val baseQs = buildString {
            append("order%5B$order%5D=desc")
            append(extraQuery)
            append("&content_rating%5B%5D=safe")
            append("&content_rating%5B%5D=suggestive")
            append("&page=$page")
            append("&limit=$limit")
        }

        val jsonHeaders = browserHeaders(
            mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )
        )

        // Try several URL variants in order of likelihood. The first one
        // that returns a valid `{status, result:{items, meta}}` wins.
        val variants = mutableListOf<String>()

        // 1. Best: use a real session token if we have one.
        fetchSessionToken()
        sessionToken?.let { variants.add("$mainUrl/api/v1/manga?$baseQs&_=$it") }
        // 2. Fallback: cfg token from <meta name="cfg">.
        cfgToken?.let { variants.add("$mainUrl/api/v1/manga?$baseQs&_=$it") }
        // 3. Fallback: no `_` at all — many APIs accept this.
        variants.add("$mainUrl/api/v1/manga?$baseQs")
        // 4. Last resort: random cache-buster of roughly the same shape.
        variants.add("$mainUrl/api/v1/manga?$baseQs&_=" + randomCacheBuster())

        for (url in variants) {
            val text = runCatching {
                app.get(url, headers = jsonHeaders, interceptor = cfInterceptor).text
            }.getOrNull() ?: continue
            parsePageResponse(text)?.let { return it }
        }
        return null
    }

    /**
     * Fetch the session token used as the `_` query parameter. The site's
     * client obtains it from /api/v1/user and appends it to every /manga
     * request. We cache the result so it's only fetched once per session.
     */
    private suspend fun fetchSessionToken() {
        if (sessionToken != null) return
        val text = runCatching {
            app.get(
                "$mainUrl/api/v1/user",
                headers = browserHeaders(mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                )),
                interceptor = cfInterceptor
            ).text
        }.getOrNull() ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        // Try common shapes for the token.
        val candidates = listOf("token", "_", "csrf", "csrfToken", "session", "sessionId")
        for (key in candidates) {
            val v = json.optString(key)
            if (v.isNotBlank() && v.length in 40..500) { sessionToken = v; return }
            json.optJSONObject("data")?.optString(key)?.takeIf { it.isNotBlank() }?.let {
                sessionToken = it; return
            }
        }
    }

    private fun randomCacheBuster(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return (1..129).map { chars.random() }.joinToString("")
    }

    /**
     * Response envelope:
     *   {"status":"ok","result":{"items":[…],"meta":{"hasNext":true,…}}}
     */
    private fun parsePageResponse(response: String): PageResult? {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return null

        runCatching { JSONArray(trimmed) }.getOrNull()?.let { arr ->
            val items = arrToResults(arr)
            return if (items.isEmpty()) null else PageResult(items, hasNext = items.size >= 5)
        }

        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        root.optJSONObject("result")?.let { result ->
            result.optJSONArray("items")?.let { itemsArr ->
                val items = arrToResults(itemsArr)
                if (items.isNotEmpty()) {
                    val hasNext = result.optJSONObject("meta")?.optBoolean("hasNext")
                        ?: (items.size >= 5)
                    return PageResult(items, hasNext)
                }
            }
        }

        for (field in listOf("items", "data", "results", "list")) {
            root.optJSONArray(field)?.let { arr ->
                val items = arrToResults(arr)
                if (items.isNotEmpty()) return PageResult(items, items.size >= 5)
            }
            root.optJSONObject(field)?.let { obj ->
                obj.optJSONArray("items")?.let { arr ->
                    val items = arrToResults(arr)
                    if (items.isNotEmpty()) {
                        val hasNext = obj.optJSONObject("meta")?.optBoolean("hasNext")
                            ?: (items.size >= 5)
                        return PageResult(items, hasNext)
                    }
                }
            }
        }

        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = root.opt(k)
            val arr = when (v) {
                is JSONArray  -> v
                is JSONObject -> v.optJSONArray("items")
                else          -> null
            } ?: continue
            val items = arrToResults(arr)
            if (items.isNotEmpty()) {
                val hasNext = (v as? JSONObject)
                    ?.optJSONObject("meta")?.optBoolean("hasNext")
                    ?: (items.size >= 5)
                return PageResult(items, hasNext)
            }
        }
        return null
    }

    private fun arrToResults(arr: JSONArray): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parseMangaFromJson(it)?.let { r -> out.add(r) } }
        }
        return out
    }
