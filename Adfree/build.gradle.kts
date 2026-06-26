plugins {
    id("com.android.library") 
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

version = "2"

cloudstream {
    setRepo(System.getenv("REPO_URL") ?: "https://github.com/AC321-beep/DonghuaFan")
    
    iconUrl = "https://raw.githubusercontent.com/AC321-beep/DonghuaFan/refs/heads/master/Adfree/Icon.png"
}

android {
    namespace = "com.net.optimizer" 
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    buildTypes {
        release {
            isMinifyEnabled = true 
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    val cloudstreamVersion = "master-SNAPSHOT"
    implementation("com.github.recloudstream:cloudstream:$cloudstreamVersion")
}
