import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("android-library"))
        extensions.configure<LibraryExtension> {
            namespace = fintoNamespace()
            compileSdk = FintoBuild.COMPILE_SDK
            defaultConfig {
                minSdk = FintoBuild.MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
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
        dependencies {
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        }
    }
}
