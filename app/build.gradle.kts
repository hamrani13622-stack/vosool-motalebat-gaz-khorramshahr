
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.gas.vosool"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.gas.vosool"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }
}
