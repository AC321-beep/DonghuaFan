package com.myanimelive

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import com.lagradost.cloudstream3.app

@CloudstreamPlugin
class MyAnimeLivePlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize NewPipe with a downloader that uses CloudStream's http client
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val response = app.get(
                    request.url(),
                    headers = request.headers().toMap()
                        .mapValues { it.value.firstOrNull() ?: "" }
                ).also { it }

                return Response.Builder()
                    .responseCode(response.code)
                    .responseMessage(response.message)
                    .responseBody(response.text)
                    .responseHeaders(response.headers.toMultimap())
                    .latestUrl(response.url)
                    .build()
            }
        })

        registerMainAPI(MyAnimeLiveProvider())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(YoutubeExtractor())
    }
}
