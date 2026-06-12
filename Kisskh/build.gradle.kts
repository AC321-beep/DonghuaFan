version = 1

android {
    defaultConfig {
        val localProperties = java.util.Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localProperties.load(localFile.inputStream())
        }
        val kissKhApi = localProperties.getProperty("KissKh") ?: System.getenv("KissKh") ?: ""
        val kisskhSubApi = localProperties.getProperty("KisskhSub") ?: System.getenv("KisskhSub") ?: ""

        buildConfigField("String", "KissKh", "\"$kissKhApi\"")
        buildConfigField("String", "KisskhSub", "\"$kisskhSubApi\"")
    }
}

cloudstream {
    description = "Korean, Chinese, Philippine dramas and anime from Kisskh"
    authors = listOf(providers.gradleProperty("cs_author").orNull ?: "AC321-beep")
    status = 1
    tvTypes = listOf("AsianDrama", "Anime")
    language = "en"
    iconUrl = "https://kisskh.nl/favicon.ico"
}
