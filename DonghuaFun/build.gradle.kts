plugins {
    // This tells the subproject to inherit the Cloudstream plugin setup from the root
    id("com.lagradost.CloudstreamPlugin")
}

cloudstream {
    // Points directly to your localized plugin initialization class file
    mainClass = "com.donghuafun.DonghuaFunPlugin"
}

dependencies {
    // Using string notation bypasses the unresolved object reference check
    implementation("com.lagradost:cloudstream3:core")
}
