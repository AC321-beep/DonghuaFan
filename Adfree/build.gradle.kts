plugins {
    id("com.android.library") 
}

cloudstream {
    description = "Blocks ads and donation popups with behavior-triggered filtering."
    authors = listOf("AC321-beep")   
    status = 1
    language = "en"
    version = 10
    iconUrl = "https://raw.githubusercontent.com/AC321-beep/DonghuaFan/refs/heads/master/Adfree/Icon.png"
}

android {
    namespace = "com.adfree" 
    
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
