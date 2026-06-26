import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    id("com.android.library") 
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setRepo(System.getenv("REPO_URL") ?: "https://github.com/AC321-beep/DonghuaFan")
}

// 👑 THIS IS THE CORRECT PLUGIN BLOCK FOR CONFIGURATION AND VERSIONING
cloudstreamPlugin {
    className = "com.netoptimizer.OptimizerPlugin"
    description = "Network Traffic Optimizer"
    author = "SysDev"
    version = 1 // 🌟 HERE IS YOUR VERSION NUMBER! Change to 2, 3, etc. to trigger updates.
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
