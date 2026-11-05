plugins {
    alias(libs.plugins.finto.android.library)
    alias(libs.plugins.finto.hilt)
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.paging.runtime)
    implementation(projects.core.network)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.turbine)
    testImplementation(libs.androidx.paging.testing)
}
