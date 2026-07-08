plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "app.filmengine.camera.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    val camerax = "1.4.1"
    api("androidx.camera:camera-core:$camerax")
    api("androidx.camera:camera-camera2:$camerax")
    api("androidx.camera:camera-lifecycle:$camerax")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AGP unit tests run on JUnit4
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
