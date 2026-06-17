dependencies {
    // Optional: add any extra dependencies
}

cloudstream {
    description = "Movies, TV Series and Live Sports"
    authors = listOf("anonymous")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Live")
    language = "en"
    version = 1
    iconUrl = "https://moviebox.ph/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        targetSdk = 35
    }
}
