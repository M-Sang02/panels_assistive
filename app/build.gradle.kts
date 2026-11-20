plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manh.panelsassistive"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.manh.panelsassistive"
        minSdk = 23
        targetSdk = 34
        versionCode = 30
        versionName = "0.3.0"
    }
}

dependencies {
    // Required by LucideGlyphView for ResourcesCompat.getFont().
    implementation("androidx.core:core-ktx:1.15.0")
    // Optional shell bridge for freeform windows; inactive when Shizuku is missing.
    // 13.0.0 is the newest release whose manifest still allows minSdk 23.
    implementation("dev.rikka.shizuku:api:13.0.0")
    implementation("dev.rikka.shizuku:provider:13.0.0")
}
