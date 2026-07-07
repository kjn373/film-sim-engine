rootProject.name = "filmengine"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core:color-science")
include(":core:image-engine")
include(":render:cpu-renderer")
