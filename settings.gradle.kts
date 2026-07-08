pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "filmengine"

include(":core:color-science")
include(":core:image-engine")
include(":core:film-engine")
include(":core:recipe-engine")
include(":render:cpu-renderer")
include(":render:gpu-renderer")
include(":desktop:cli-renderer")
include(":tooling:film-lab")
include(":backend:backend-api")
include(":android:camera-app")
include(":android:camera-core")
