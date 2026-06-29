plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.luciferdonghua"
    compileSdk = 34
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream3:master-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

cloudstream {
    description = "Watch Donghua from Multi-Source"
    authors = listOf("AC321-beep")
    tvTypes = listOf("Anime")
    language = "zh"
    version = 3
    iconUrl = "https://i0.wp.com/luciferdonghua.in/wp-content/uploads/2022/12/cropped-lucifer-donghua-DP-192x192.webp"
}
