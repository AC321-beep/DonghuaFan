plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    compileSdk = 33
    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream:pre-release")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.13.4")
}
