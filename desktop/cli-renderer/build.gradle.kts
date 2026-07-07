plugins {
    application
}

dependencies {
    implementation(project(":core:film-engine"))
    implementation(project(":render:cpu-renderer"))
}

application {
    mainClass.set("app.filmengine.cli.MainKt")
}
