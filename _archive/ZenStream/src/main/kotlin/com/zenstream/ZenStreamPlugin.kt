package com.zenstream

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ZenStreamPlugin : Plugin() {
    override fun load(context: Context) {
        if (ZenStreamSettings.isProviderEnabled()) {
            registerMainAPI(ZenStreamProvider())
        }
        this.openSettings = { ctx: Context ->
            ZenStreamSettingsDialog.show(ctx) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
