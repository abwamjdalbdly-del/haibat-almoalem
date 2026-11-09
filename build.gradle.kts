plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ye.teacherbag.app"
    compileSdk = 33

    defaultConfig {
        applicationId = "ye.teacherbag.app"
        minSdk = 24
        targetSdk = 33
        versionCode = 4
        versionName = "0.4"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}