import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    id("com.android.application") // or "com.android.library" depending on your exact setup
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    // 🛡️ STEALTH: Do not use your real username or "guard" in the repo name
    setRepo(System.getenv("REPO_URL") ?: "https://github.com/user/repo")
    setRepoName(System.getenv("REPO_NAME") ?: "System Utilities")
    
    // 🛡️ STEALTH: This is what CloudStream registers internally. 
    // Argument 1: The exact path to your new main plugin class
    // Argument 2: The public display name of the plugin
    // Argument 3: The author name (keep it generic)
    // Argument 4: Version
    addPlugin(
        "com.net.optimizer.OptimizerPlugin", 
        "Network Optimizer", 
        "SysDev", 
        "1.0.0"
    )
}

android {
    // 🛡️ STEALTH: This replaces the package name in the AndroidManifest.xml
    namespace = "com.net.optimizer"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    buildTypes {
        release {
            isMinifyEnabled = true // Highly recommended to further obfuscate the code
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
    
    // Add any other specific dependencies your plugin needs here
}
