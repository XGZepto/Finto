plugins {
    alias(libs.plugins.finto.android.library)
    alias(libs.plugins.finto.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// The server the app talks to is fixed when the APK is built:
// ./gradlew assembleRelease -Pfinto.server=https://finto.example.com/
// (or FINTO_SERVER in the environment). Without either, it is the production Finto.
val fintoServer: String = providers.gradleProperty("finto.server")
    .orElse(providers.environmentVariable("FINTO_SERVER"))
    .getOrElse("https://finto.zepto.page/")
    .let { if (it.endsWith("/")) it else "$it/" }

android {
    buildFeatures { buildConfig = true }
    defaultConfig { buildConfigField("String", "FINTO_SERVER", "\"$fintoServer\"") }
}

dependencies {
    api(projects.core.model)
    api(libs.okhttp)
    api(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
}
