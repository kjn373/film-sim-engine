plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "app.filmengine.render.gles"
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
    api(project(":core:image-engine"))
    implementation(project(":core:film-engine"))
}
