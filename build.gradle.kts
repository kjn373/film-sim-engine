plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    kotlin("android") version "2.1.21" apply false
    kotlin("plugin.compose") version "2.1.21" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
}

subprojects {
    // Android modules configure themselves; everything else is plain Kotlin/JVM.
    if (!path.startsWith(":android")) {
        apply(plugin = "org.jetbrains.kotlin.jvm")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        dependencies {
            "testImplementation"(kotlin("test"))
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }
}
