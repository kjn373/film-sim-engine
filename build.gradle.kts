plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
}

subprojects {
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
