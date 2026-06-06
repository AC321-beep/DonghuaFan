plugins {
    kotlin("jvm")
}

cloudstream {
    mainClass = "com.donghuafun.DonghuaFunPlugin"
}

dependencies {
    implementation(com.lagradost.cloudstream3.core)
}
