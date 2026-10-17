package com.naruldonghua

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin

class NarulDonghuaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NarulDonghuaProvider())
    }
}
