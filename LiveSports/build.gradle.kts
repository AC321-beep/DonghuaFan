plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.livesports"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "Live Sports Events and Matches"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Live")
    language = "en"
    version = 1
}
