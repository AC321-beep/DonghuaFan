package com.reel

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelFrenPlugin : Plugin() {
    override fun load(context: Context) {
        // 1. Initialize the persistent store with the app context
        ReelFrenStore.init(context)

        // 2. Register all enabled providers dynamically
        ReelFrenStore.enabledProviders().forEach { (name, slug) ->
            registerMainAPI(ReelFrenProvider(name, slug))
        }

        // 3. Trigger the Cloudflare pre-warmer if needed
        ReelFrenCloudflare.prewarmIfNeeded()
    }
}
