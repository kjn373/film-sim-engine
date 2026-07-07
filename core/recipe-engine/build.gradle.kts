plugins {
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":core:image-engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
