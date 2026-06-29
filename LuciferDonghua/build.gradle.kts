cloudstream {
    description = "Watch Donghua from Multi-Source"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Anime")
    language = "zh"
    version = 2
    iconUrl = "https://i0.wp.com/luciferdonghua.in/wp-content/uploads/2022/12/cropped-lucifer-donghua-DP-192x192.webp"
}

dependencies {
    // These dependencies are required to resolve 'kotlinx', 'coroutineScope', and 'TvType'
    compileOnly("com.lagradost:cloudstream3:pre-release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
