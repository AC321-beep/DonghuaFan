plugins {
    id("com.android.library") 
    // REMOVED: kotlin("android") - This is strictly blocked in AGP 9.0+
}

cloudstream {
    description = "Aim to piss Adsproviders"
    authors = listOf("AC321-beep")   
    status = 1
    language = "en"
    version = 4
    iconUrl = "https://raw.githubusercontent.com/AC321-beep/DonghuaFan/refs/heads/master/Adfree/Icon.png"
}

android {
    namespace = "com.net.optimizer" 
    
    // minSdk, compileSdk, and compileOptions are removed because your 
    // root build.gradle.kts automatically handles them for all modules.
    
    buildTypes {
        release {
            isMinifyEnabled = true 
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

// REMOVED: The kotlin { jvmToolchain(8) } block. Your root file already sets jvmTarget.JVM_1_8.
// REMOVED: The dependencies { ... } block. Your root file already securely imports the "pre-release" library.
