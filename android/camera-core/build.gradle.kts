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
    // 1.5+ is required for ImageCapture RAW/DNG output formats (B5)
    val camerax = "1.5.3"
    api("androidx.camera:camera-core:$camerax")
    api("androidx.camera:camera-camera2:$camerax")
    api("androidx.camera:camera-lifecycle:$camerax")
    api(project(":android:gles-renderer"))
    implementation(project(":core:film-engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AGP unit tests run on JUnit4
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // CaptureRender full-chain check against the reference backend
    testImplementation(project(":render:cpu-renderer"))
}
