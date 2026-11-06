plugins {
    alias(libs.plugins.finto.android.feature)
}

dependencies {
    // MpfImportViewModel takes android.net.Uri, which needs a real framework class in tests.
    testImplementation(libs.robolectric)
    // Server rejections (400/409/413/422) are built as retrofit HttpExceptions in tests.
    testImplementation(libs.retrofit)
    testImplementation(libs.okhttp)
}
