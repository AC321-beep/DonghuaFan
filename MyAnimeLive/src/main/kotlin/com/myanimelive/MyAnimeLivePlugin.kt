package com.myanimelive

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response

@CloudstreamPlugin
class MyAnimeLivePlugin : Plugin() {
    override fun load(context: Context) {
        val okClient = OkHttpClient()

        NewPipe.init(object : Downloader() {
            override fun execute(
                request: org.schabi.newpipe.extractor.downloader.Request
            ): Response {
                val reqBuilder = Request.Builder().url(request.url())
                request.headers().forEach { (key, values) ->
                    values.forEach { value -> reqBuilder.addHeader(key, value) }
                }
                if (request.httpMethod() == "POST") {
                    val body = request.dataToSend()
                        ?.let { okhttp3.RequestBody.create(null, it) }
                        ?: okhttp3.RequestBody.create(null, ByteArray(0))
                    reqBuilder.post(body)
                }

                val resp = okClient.newCall(reqBuilder.build()).execute()
                val responseHeaders = mutableMapOf<String, MutableList<String>>()
                resp.headers().names().forEach { name ->
                    responseHeaders[name] = resp.headers(name).toMutableList()
                }

                return Response(
                    resp.code(),
                    resp.message(),
                    responseHeaders,
                    resp.body()?.string(),
                    resp.request().url().toString()
                )
            }
        })

        registerMainAPI(MyAnimeLiveProvider())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(YoutubeExtractor())
    }
}
