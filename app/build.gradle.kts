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
    // Lets the circle-search trigger call the hidden voice interaction service.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Optional shell bridge for freeform windows; inactive when Shizuku is missing.
    // 13.0.0 is the newest release whose manifest still allows minSdk 23.
    implementation("dev.rikka.shizuku:api:13.0.0")
    implementation("dev.rikka.shizuku:provider:13.0.0")
    // Reads the EXIF rotation of photos picked for the image widget (API 23+).
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
