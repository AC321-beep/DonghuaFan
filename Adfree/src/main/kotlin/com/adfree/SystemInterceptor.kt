// In SystemInterceptor.kt
private fun shouldBlockIntent(intent: Intent?, providerName: String?): Boolean {
    if (intent == null) return false
    val uri = intent.data
    val scheme = uri?.scheme?.lowercase()
    val host = uri?.host?.lowercase()
    val url = uri?.toString() ?: ""

    // 1. Block non-HTTP schemes (UPI, Paytm, PhonePe, Market, etc.)
    if (scheme != null && scheme != "http" && scheme != "https") {
        return true
    }

    // 2. Block if host is in the donation/ad blocklist
    if (host != null && FilterStore.isHostBlocked(host)) return true

    // 3. Block if the URL contains donation/ad keywords
    if (url.contains("buymeacoffee", true) || url.contains("donate", true) ||
        url.contains("support", true) || url.contains("patreon", true) ||
        url.contains("cncverse", true) || url.contains("upi", true) ||
        url.contains("paypal", true)) {
        return true
    }

    // 4. Block if the provider is explicitly blocked and host is not safe
    if (providerName != null && FilterStore.getBlockedProviders().contains(providerName)) {
        if (host == null || !FilterStore.isHostSafe(host)) return true
    }

    return false
}
