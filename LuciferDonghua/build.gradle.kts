dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

cloudstream {
    setParams {
        description = "Watch Donghua from Multi-Source"
        authors = listOf("AC321-beep")
        tvTypes = listOf(TvType.Anime) // Must use TvType enum
        language = "zh"
        version = 3
        iconUrl = "https://i0.wp.com/luciferdonghua.in/wp-content/uploads/2022/12/cropped-lucifer-donghua-DP-192x192.webp"
    }
}
