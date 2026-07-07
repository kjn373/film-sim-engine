dependencies {
    api(project(":core:image-engine"))
    implementation(project(":core:film-engine"))
    testImplementation(project(":render:cpu-renderer"))

    implementation(platform("org.lwjgl:lwjgl-bom:3.3.6"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    for (natives in listOf("natives-windows", "natives-linux", "natives-macos")) {
        runtimeOnly("org.lwjgl:lwjgl::$natives")
        runtimeOnly("org.lwjgl:lwjgl-glfw::$natives")
        runtimeOnly("org.lwjgl:lwjgl-opengl::$natives")
    }
}
