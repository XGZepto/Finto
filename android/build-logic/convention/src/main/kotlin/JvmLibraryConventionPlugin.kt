import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure Kotlin module: no Android framework, fast plain-JVM tests. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = FintoBuild.JAVA_VERSION
            targetCompatibility = FintoBuild.JAVA_VERSION
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.fromTarget(FintoBuild.JAVA_VERSION.toString()))
        }
        dependencies {
            add("testImplementation", libs.lib("junit"))
        }
        // Android modules test with `testDebugUnitTest`; answer to the same name so
        // one command runs every module's unit tests.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Runs this JVM module's unit tests (alias of `test`)."
            dependsOn("test")
        }
        Unit
    }
}
