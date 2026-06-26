plugins {
    id("com.android.library") 
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}
version = "1"
cloudstream {
    // This tells the GitHub Action where to host your plugin
    setRepo(System.getenv("REPO_URL") ?: "https://github.com/AC321-beep/DonghuaFan")
}

android {
    namespace = "com.netoptimizer" 
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
