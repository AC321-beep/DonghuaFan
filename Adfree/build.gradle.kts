cloudstream {
    description = "Blocks pop-up."
    authors = listOf("AC321-beep")
    status = 1
    language = "en"
    version = 14
    iconUrl = "https://raw.githubusercontent.com/AC321-beep/DonghuaFan/refs/heads/master/Adfree/Icon.png"
}

android {
    namespace = "com.adfree"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Provided by the CloudStream host at runtime.
    // compileOnly keeps it out of the plugin jar so there is only one
    // FragmentManager class in the process.
    compileOnly("androidx.fragment:fragment-ktx:1.6.2")
}
