plugins {
    alias(libs.plugins.finto.android.library)
    alias(libs.plugins.finto.android.compose)
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material.icons.extended)
    implementation(projects.core.model)
}
