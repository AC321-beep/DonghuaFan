plugins {
    id("com.android.library") 
    // kotlin("android") is NOT needed. It's handled by the root project.
}

cloudstream {
    description = "Aggressively blocks ads and donation popups across all providers."
    authors = listOf("AC321-beep")   
    status = 1
    language = "en"
    version = 4
    iconUrl = "https://raw.githubusercontent.com/AC321-beep/DonghuaFan/refs/heads/master/Adfree/Icon.png"
}

android {
    namespace = "com.net.optimizer" 
    
    // minSdk, compileSdk, and compileOptions are REMOVED.
    // The root build.gradle.kts automatically handles them for all modules.
    
    buildTypes {
        release {
            isMinifyEnabled = true // Keep this true for optimal performance
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro" // Your custom rules
            )
        }
    }
}
