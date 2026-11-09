plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ye.teacherbag.app"
    compileSdk = 33 // تم التخفيض

    defaultConfig {
        applicationId = "ye.teacherbag.app"
        minSdk = 24 // تم التخفيض ليدعم هواتف أكثر
        targetSdk = 33 // تم التخفيض

        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("TEACHERBAG_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storeType = "PKCS12"
            storePassword = System.getenv("TEACHERBAG_STORE_PASSWORD")
            keyAlias = System.getenv("TEACHERBAG_KEY_ALIAS")
            keyPassword = System.getenv("TEACHERBAG_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            // يبقى توقيع Debug الافتراضي دون تغيير
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
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