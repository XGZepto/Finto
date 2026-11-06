pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "finto-android"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:designsystem")
include(":core:testing")
include(":feature:auth")
include(":feature:summary")
include(":feature:transactions")
include(":feature:accounts")
include(":feature:imports")
include(":feature:settings")
include(":feature:reports")
include(":feature:recurring")
include(":feature:investments")
include(":feature:health")
include(":feature:ask")
