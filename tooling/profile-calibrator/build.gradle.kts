plugins {
    kotlin("plugin.serialization")
    application
}

dependencies {
    api(project(":core:color-science"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

application {
    mainClass = "app.filmengine.profile.MainKt"
}
