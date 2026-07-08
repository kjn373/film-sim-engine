rootProject.name = "filmengine"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core:color-science")
include(":core:image-engine")
include(":core:film-engine")
include(":core:recipe-engine")
include(":render:cpu-renderer")
include(":render:gpu-renderer")
include(":desktop:cli-renderer")
include(":tooling:film-lab")
