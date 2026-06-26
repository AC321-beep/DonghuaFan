import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    id("com.android.library") 
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setRepo(System.getenv("REPO_URL") ?: "https://github.com/AC321-beep/DonghuaFan")
    setRepoName(System.getenv("REPO_NAME") ?: "DonghuaFan")
    
    // CloudStream registers updates based on this integer.
    // When you want to push an update, just change 1 to 2, 3, etc.
    addPlugin(
        "com.netoptimizer.OptimizerPlugin", 
        "Network Optimizer", 
        "SysDev", 
        1
    )
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
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    val cloudstreamVersion = "master-SNAPSHOT"
    implementation("com.github.recloudstream:cloudstream:$cloudstreamVersion")
}
