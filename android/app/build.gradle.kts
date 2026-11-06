plugins {
    alias(libs.plugins.finto.android.application)
    alias(libs.plugins.finto.android.compose)
    alias(libs.plugins.finto.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "page.zepto.finto"

    defaultConfig {
        applicationId = "page.zepto.finto"
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes from Gradle properties or the environment, so no
    // key material is ever committed. Without them the release build is unsigned.
    val storeFilePath = providers.gradleProperty("finto.signing.storeFile")
        .orElse(providers.environmentVariable("FINTO_SIGNING_STORE_FILE")).orNull
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("finto.signing.storePassword")
                    .orElse(providers.environmentVariable("FINTO_SIGNING_STORE_PASSWORD")).orNull
                keyAlias = providers.gradleProperty("finto.signing.keyAlias")
                    .orElse(providers.environmentVariable("FINTO_SIGNING_KEY_ALIAS")).orNull
                keyPassword = providers.gradleProperty("finto.signing.keyPassword")
                    .orElse(providers.environmentVariable("FINTO_SIGNING_KEY_PASSWORD")).orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Release code (R8, not debuggable) signed with the debug key, to install
        // beside the others and judge real speed: debug builds run interpreted Compose.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.feature.auth)
    implementation(projects.feature.summary)
    implementation(projects.feature.transactions)
    implementation(projects.feature.accounts)
    implementation(projects.feature.imports)
    implementation(projects.feature.settings)
    implementation(projects.feature.reports)
    implementation(projects.feature.recurring)
    implementation(projects.feature.investments)
    implementation(projects.feature.health)
    implementation(projects.feature.ask)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.paging.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
