package com.zenstream

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object ZenStreamSettings {

    const val PROVIDER_ENABLED = "zenstream_provider_enabled"
    private const val PROVIDER_ORDER_KEY = "zenstream_provider_order"

    // Main provider toggle (enable/disable the whole provider)
    fun isProviderEnabled(): Boolean = getKey<Boolean>(PROVIDER_ENABLED) ?: true
    fun setProviderEnabled(enabled: Boolean) = setKey(PROVIDER_ENABLED, enabled)

    // Individual provider toggles
    fun isProviderToggleEnabled(key: String): Boolean =
        getKey<Boolean>("${key}_enabled") ?: true // default enabled

    fun setProviderToggleEnabled(key: String, enabled: Boolean) =
        setKey("${key}_enabled", enabled)

    // Provider order (default = registry keys)
    private fun getDefaultOrder(): List<String> = ZenStreamProviderRegistry.keys

    fun getProviderOrder(): List<String> {
        val saved = getKey<String>(PROVIDER_ORDER_KEY)
        return if (saved.isNullOrBlank()) getDefaultOrder()
        else saved.split(",").filter { it.isNotBlank() }
    }

    fun saveProviderOrder(order: List<String>) =
        setKey(PROVIDER_ORDER_KEY, order.joinToString(","))

    fun resetProviderOrder() =
        setKey(PROVIDER_ORDER_KEY, null as String?)

    // Helper: get enabled providers in order
    fun getEnabledProviders(): List<String> =
        getProviderOrder().filter { isProviderToggleEnabled(it) }
}
