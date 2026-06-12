android {
    namespace = "com.kisskh"
}

dependencies {
    // Force the correct dependency for this module only
    implementation("com.github.recloudstream:cloudstream:master-SNAPSHOT") {
        isTransitive = false
    }
}
