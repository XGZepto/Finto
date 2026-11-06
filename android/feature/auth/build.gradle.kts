plugins {
    alias(libs.plugins.finto.android.feature)
}

dependencies {
    // The login screen maps the server's 401 to its own wording.
    implementation(projects.core.network)
}
