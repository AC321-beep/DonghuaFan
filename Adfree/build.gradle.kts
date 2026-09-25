plugins {
    id("com.android.library") 
}

cloudstream {
    description = "Blocks known ad and donation popups with a user-editable blocklist."
    authors = listOf("AC321-beep")   
    status = 1
    language = "en"
    version = 8
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
