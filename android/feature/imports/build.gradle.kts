plugins {
    alias(libs.plugins.finto.android.feature)
}

dependencies {
    // ImportViewModel takes an android.net.Uri, which needs a real framework class in tests.
    testImplementation(libs.robolectric)
}
