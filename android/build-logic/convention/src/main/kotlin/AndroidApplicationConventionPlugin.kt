import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-application"))
        extensions.configure<ApplicationExtension> {
            compileSdk = FintoBuild.COMPILE_SDK
            defaultConfig {
                minSdk = FintoBuild.MIN_SDK
                targetSdk = FintoBuild.TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = FintoBuild.JAVA_VERSION
                targetCompatibility = FintoBuild.JAVA_VERSION
            }
            testOptions.unitTests.isIncludeAndroidResources = true
            testOptions.unitTests.all { test ->
                listOf("robolectric.dependency.repo.url", "finto.liveServer").forEach { key ->
                    providers.gradleProperty(key).orNull?.let { test.systemProperty(key, it) }
                }
            }
        }
    }
}
