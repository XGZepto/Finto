plugins {
    alias(libs.plugins.finto.android.library)
}

// Test doubles and rules shared by the feature modules' unit tests.
dependencies {
    api(projects.core.data)
    api(projects.core.model)
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
    implementation(libs.androidx.paging.runtime)
}
