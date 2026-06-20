class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fix for missing protocol in certain iframes (e.g., Allsub1)
        val fixedUrl = if (url.startsWith("//")) "https:$url" else url

        Log.d(name, "Loading: $fixedUrl")
        val html = app.get(fixedUrl).text

        // Direct m3u8
        var m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)?.value
        if (m3u8 != null) {
            Log.d(name, "Found direct m3u8: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // Unpacked script
        val packed = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.value ?: return
        val unpacked = JsUnpacker(packed).unpack() ?: return

        m3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)
        if (m3u8 != null) {
            Log.d(name, "Found m3u8 in unpacked: $m3u8")
            M3u8Helper.generateM3u8(name, m3u8, mainUrl).forEach(callback)
            return
        }

        // Token API fallback
        val token = Regex("""kaken\s*=\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
        if (token != null) {
            val apiUrl = "$mainUrl/api/?$token"
            Log.d(name, "Calling API: $apiUrl")
            val apiJson = app.get(apiUrl).text
            val apiM3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(apiJson)?.groupValues?.get(1)
            if (apiM3u8 != null) {
                M3u8Helper.generateM3u8(name, apiM3u8, mainUrl).forEach(callback)
            }
        }
    }
}
