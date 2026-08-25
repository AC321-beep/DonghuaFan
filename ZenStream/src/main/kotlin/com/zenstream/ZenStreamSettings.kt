package com.zenstream

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object ZenStreamSettings {

    const val PROVIDER_ENABLED = "zenstream_provider_enabled"

    fun isProviderEnabled(): Boolean = getKey<Boolean>(PROVIDER_ENABLED) ?: true

    fun setProviderEnabled(enabled: Boolean) {
        setKey(PROVIDER_ENABLED, enabled)
    }
}
