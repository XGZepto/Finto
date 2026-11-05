plugins {
    alias(libs.plugins.finto.android.library)
    alias(libs.plugins.finto.hilt)
    alias(libs.plugins.kotlin.serialization)
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
