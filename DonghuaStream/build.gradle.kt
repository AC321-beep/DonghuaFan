plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-parcelize")
}

android {
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    compileOnly("com.lagradost:cloudstream3:main-SNAPSHOT")
}

cloudstream {
    description = "Donghua"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("Anime")
    language = "zh"
    version = 1
    // Updated to use donghuastream.org logo – adjust if the actual logo path is different
    iconUrl = "https://donghuastream.org/template/shoutu45/assets/images/logo-1.png"
}
