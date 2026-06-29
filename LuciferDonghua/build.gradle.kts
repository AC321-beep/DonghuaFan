plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.luciferdonghua"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    // We use 'api' so these libraries are visible to the Kotlin compiler 
    // without triggering configuration cache errors.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    compileOnly("com.lagradost:cloudstream3:pre-release")
}

cloudstream {
    description = "Watch Donghua from Multi-Source"
    authors = listOf("AC321-beep")
    tvTypes = listOf("Anime")
    language = "zh"
    version = 4
    iconUrl = "https://i0.wp.com/luciferdonghua.in/wp-content/uploads/2022/12/cropped-lucifer-donghua-DP-192x192.webp"
}
