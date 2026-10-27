plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ye.teacherbag.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ye.teacherbag.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}
