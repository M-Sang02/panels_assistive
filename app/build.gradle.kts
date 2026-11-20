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
        versionCode = 28
        versionName = "0.2.8"
    }
}

dependencies {
    // Required by LucideGlyphView for ResourcesCompat.getFont().
    implementation("androidx.core:core-ktx:1.15.0")
}
